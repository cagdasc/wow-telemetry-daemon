use std::env;
use std::error::Error;
use std::ffi::OsString;
use std::net::SocketAddr;
use std::path::PathBuf;

use crate::protocol::WireFormat;

#[derive(Clone, Debug)]
pub struct Config {
    pub path: PathBuf,
    pub websocket_address: SocketAddr,
    pub websocket_enabled: bool,
    pub start_at_end: bool,
    pub stdout_enabled: bool,
    pub format: WireFormat,
    pub tail: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            path: PathBuf::from("WoWCombatLog.txt"),
            websocket_address: "127.0.0.1:8765".parse().expect("valid default address"),
            websocket_enabled: true,
            start_at_end: true,
            stdout_enabled: false,
            format: WireFormat::Protobuf,
            tail: true,
        }
    }
}

pub fn parse_args<I>(args: I) -> Result<Option<Config>, Box<dyn Error>>
where
    I: IntoIterator<Item = OsString>,
{
    let mut args = args.into_iter();
    let mut config = Config::default();
    let mut explicit_path = false;

    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--help" | "-h") => {
                println!(
                    "Usage: wow-telemetry-daemon [PATH] [--file PATH] [--ws-addr ADDRESS] [--no-websocket] [--format protobuf|json] [--stdout] [--quiet] [--from-start]\n\n\
                     Tails PATH (default: WoWCombatLog.txt) and broadcasts events to WebSocket clients (default: 127.0.0.1:8765).\n\
                     Use --file PATH (-f, --log-file) to read an existing log file instead of tailing and waiting for the game.\n\
                     Use --ws-addr 0.0.0.0:8765 to accept connections from other machines.\n\
                     Use --stdout to write parsed events as JSON lines to stdout.\n\
                     Use --quiet to ensure stdout JSON lines are disabled.\n\
                     Use --format json to send JSON text frames instead of protobuf binary frames.\n\
                     Use --from-start to read existing file contents instead of seeking to EOF."
                );
                return Ok(None);
            }
            Some("--file" | "--log-file" | "-f") => {
                let file = args.next().ok_or("--file requires a path")?;
                if explicit_path {
                    return Err("expected at most one combat log path".into());
                }
                config.path = PathBuf::from(file);
                config.start_at_end = false;
                config.tail = false;
                explicit_path = true;
            }
            Some("--no-websocket") => config.websocket_enabled = false,
            Some("--stdout") => config.stdout_enabled = true,
            Some("--quiet") => config.stdout_enabled = false,
            Some("--from-start") => config.start_at_end = false,
            Some("--format") => {
                config.format = match args.next().as_deref().and_then(|value| value.to_str()) {
                    Some("protobuf") => WireFormat::Protobuf,
                    Some("json") => WireFormat::Json,
                    _ => return Err("--format must be protobuf or json".into()),
                };
            }
            Some("--ws-addr") => {
                let address = args.next().ok_or("--ws-addr requires an address")?;
                config.websocket_address = address
                    .to_str()
                    .ok_or("WebSocket address must be valid UTF-8")?
                    .parse()?;
            }
            _ => {
                if explicit_path {
                    return Err("expected at most one combat log path".into());
                }
                config.path = PathBuf::from(arg);
                explicit_path = true;
            }
        }
    }

    Ok(Some(config))
}

pub fn from_env() -> Result<Option<Config>, Box<dyn Error>> {
    parse_args(env::args_os().skip(1))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_strings(args: &[&str]) -> Result<Option<Config>, Box<dyn Error>> {
        parse_args(args.iter().map(OsString::from))
    }

    #[test]
    fn default_config_tails_default_file_from_end() {
        let config = parse_strings(&[]).unwrap().unwrap();
        assert_eq!(config.path, PathBuf::from("WoWCombatLog.txt"));
        assert!(config.start_at_end);
        assert!(config.tail);
        assert!(config.websocket_enabled);
        assert_eq!(config.format, WireFormat::Protobuf);
    }

    #[test]
    fn file_parameter_sets_path_starts_from_beginning_and_disables_tailing() {
        let config = parse_strings(&["--file", "sample_log/custom.txt"])
            .unwrap()
            .unwrap();
        assert_eq!(config.path, PathBuf::from("sample_log/custom.txt"));
        assert!(!config.start_at_end);
        assert!(!config.tail);

        let config_alias = parse_strings(&["--log-file", "my_log.txt"])
            .unwrap()
            .unwrap();
        assert_eq!(config_alias.path, PathBuf::from("my_log.txt"));
        assert!(!config_alias.start_at_end);
        assert!(!config_alias.tail);

        let config_short = parse_strings(&["-f", "short.txt"]).unwrap().unwrap();
        assert_eq!(config_short.path, PathBuf::from("short.txt"));
        assert!(!config_short.start_at_end);
        assert!(!config_short.tail);
    }

    #[test]
    fn file_parameter_errors_if_path_missing() {
        assert!(parse_strings(&["--file"]).is_err());
    }

    #[test]
    fn multiple_paths_error() {
        assert!(parse_strings(&["log1.txt", "log2.txt"]).is_err());
        assert!(parse_strings(&["--file", "log1.txt", "log2.txt"]).is_err());
        assert!(parse_strings(&["log1.txt", "--file", "log2.txt"]).is_err());
    }
}
