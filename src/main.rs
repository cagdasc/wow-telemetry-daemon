mod parser;
mod state;
mod tailer;

use std::env;
use std::io::{self, BufWriter, Write};
use std::path::PathBuf;
use std::sync::{Arc, RwLock, mpsc};

use parser::parse_line;
use state::State;
use tailer::TailMessage;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args_os().skip(1);
    let path = match args.next() {
        Some(arg) if arg == "--help" || arg == "-h" => {
            println!(
                "Usage: wow-telemetry-daemon [PATH]\n\nTails PATH (default: WoWCombatLog.txt) and writes parsed combat events as JSON lines."
            );
            return Ok(());
        }
        Some(path) => PathBuf::from(path),
        None => PathBuf::from("WoWCombatLog.txt"),
    };
    if args.next().is_some() {
        return Err("expected at most one combat log path".into());
    }

    let (sender, receiver) = mpsc::sync_channel(1024);
    let tail_path = path.clone();
    let tail_thread = std::thread::Builder::new()
        .name("combat-log-tailer".into())
        .spawn(move || tailer::tail_file(tail_path, sender))?;
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
                        state
                            .write()
                            .map_err(|_| io::Error::other("state lock poisoned"))?
                            .record(&event);
                        serde_json::to_writer(&mut output, &event)?;
                        output.write_all(b"\n")?;
                        output.flush()?;
                    }
                    Ok(None) => {}
                    Err(error) => {
                        eprintln!("{}:{line_number}: {error}", path.display());
                    }
                }
            }
            TailMessage::Error(error) => return Err(error.into()),
        }
    }

    tail_thread.join().map_err(|_| "tailer thread panicked")?;
    Ok(())
}
