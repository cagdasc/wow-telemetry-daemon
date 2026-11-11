mod parser;
mod spec_events;
mod state;
mod tailer;
mod typed_events;
mod websocket;

use std::env;
use std::io::{self, BufWriter, Write};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, RwLock, mpsc};

use parser::parse_line;
use state::State;
use tailer::TailMessage;
use typed_events::TypedCombatLogEvent;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args_os().skip(1);
    let mut path = None;
    let mut websocket_address: SocketAddr = "127.0.0.1:8765".parse()?;
    let mut websocket_enabled = true;
    let mut start_at_end = true;

    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--help" | "-h") => {
                println!(
                    "Usage: wow-telemetry-daemon [PATH] [--ws-addr ADDRESS] [--no-websocket] [--from-start]\n\n\
                     Tails PATH (default: WoWCombatLog.txt), writes parsed events as JSON lines,\n\
                     and broadcasts them to WebSocket clients (default: 127.0.0.1:8765).\n\
                     Use --ws-addr 0.0.0.0:8765 to accept connections from other machines.\n\
                     Use --from-start to read existing file contents instead of seeking to EOF."
                );
                return Ok(());
            }
            Some("--no-websocket") => websocket_enabled = false,
            Some("--from-start") => start_at_end = false,
            Some("--ws-addr") => {
                let address = args.next().ok_or("--ws-addr requires an address")?;
                websocket_address = address
                    .to_str()
                    .ok_or("WebSocket address must be valid UTF-8")?
                    .parse()?;
            }
            _ => {
                if path.is_some() {
                    return Err("expected at most one combat log path".into());
                }
                path = Some(PathBuf::from(arg));
            }
        }
    }
    let path = path.unwrap_or_else(|| PathBuf::from("WoWCombatLog.txt"));

    let (control_sender, control_receiver) = mpsc::sync_channel(8);
    let websocket = if websocket_enabled {
        let server =
            websocket::WebSocketServer::bind_with_clear_log(websocket_address, control_sender)?;
        eprintln!("WebSocket server listening on ws://{}", server.address());
        Some(server)
    } else {
        None
    };

    let (sender, receiver) = mpsc::sync_channel(1024);
    let tail_path = path.clone();
    let tail_thread = std::thread::Builder::new()
        .name("combat-log-tailer".into())
        .spawn(move || tailer::tail_file(tail_path, sender, control_receiver, start_at_end))?;
    let state = Arc::new(RwLock::new(State::default()));
    let stdout = io::stdout();
    let mut output = BufWriter::new(stdout.lock());
    let mut line_number = 0_u64;

    while let Ok(message) = receiver.recv() {
        match message {
            TailMessage::Line(mut line) => {
                line_number += 1;
                while matches!(line.last(), Some(b'\n' | b'\r')) {
                    line.pop();
                }
                match parse_line(&line) {
                    Ok(Some(event)) => {
                        match &event.typed_event {
                            TypedCombatLogEvent::UnrecognizedEvent(data) => eprintln!(
                                "{}:{line_number}: unrecognized event {}",
                                path.display(),
                                data.event_name
                            ),
                            TypedCombatLogEvent::MalformedEvent(data) => eprintln!(
                                "{}:{line_number}: malformed event: {}",
                                path.display(),
                                data.reason
                            ),
                            _ => {}
                        }
                        state
                            .write()
                            .map_err(|_| io::Error::other("state lock poisoned"))?
                            .record(&event);
                        let event_json = serde_json::to_string(&event)?;
                        output.write_all(event_json.as_bytes())?;
                        output.write_all(b"\n")?;
                        output.flush()?;
                        if let Some(websocket) = &websocket {
                            websocket.broadcast(Arc::from(event_json))?;
                        }
                    }
                    Ok(None) => {}
                    Err(error) => {
                        eprintln!("{}:{line_number}: {error}", path.display());
                    }
                }
            }
            TailMessage::CaughtUp => {
                if let Some(websocket) = &websocket {
                    websocket.broadcast(Arc::from(r#"{"control":"LOG_CAUGHT_UP"}"#))?;
                }
            }
            TailMessage::LogCleared(response) => {
                if let Some(websocket) = &websocket {
                    websocket.clear_replay()?;
                    websocket.broadcast(Arc::from(r#"{"control":"LOG_CLEARED"}"#))?;
                }
                let _ = response.send(Ok(()));
            }
            TailMessage::Error(error) => return Err(error.into()),
        }
    }

    tail_thread.join().map_err(|_| "tailer thread panicked")?;
    Ok(())
}
