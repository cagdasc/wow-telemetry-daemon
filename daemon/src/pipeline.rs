use std::error::Error;
use std::fs;
use std::io::{self, BufWriter, Write};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use crate::cli::Config;
use crate::parser::parse_line;
use crate::protocol::{Control, WireFormat, event_message};
use crate::tailer::{self, TailMessage};
use crate::typed_events::TypedCombatLogEvent;
use crate::websocket::WebSocketServer;

pub fn run_pipeline(config: Config) -> Result<(), Box<dyn Error>> {
    if !config.tail {
        fs::metadata(&config.path).map_err(|err| {
            format!("cannot open combat log {}: {err}", config.path.display())
        })?;
    }

    let (control_sender, control_receiver) = mpsc::sync_channel(8);
    let websocket = if config.websocket_enabled {
        let server = WebSocketServer::bind_with_clear_log(
            config.websocket_address,
            config.format,
            control_sender,
        )?;
        log::info!("WebSocket server listening on ws://{}", server.address());
        Some(server)
    } else {
        drop(control_sender);
        None
    };

    if let Some(server) = &websocket
        && !config.tail
    {
        log::info!(
            "waiting for WebSocket client to connect before streaming: {}",
            config.path.display()
        );
        while !server.has_clients() {
            thread::sleep(Duration::from_millis(50));
        }
        log::info!(
            "WebSocket client connected; streaming {}",
            config.path.display()
        );
    }

    let (sender, receiver) = mpsc::sync_channel(1024);
    let tail_path = config.path.clone();
    let start_at_end = config.start_at_end;
    let tail = config.tail;
    let tail_thread = thread::Builder::new()
        .name("combat-log-tailer".into())
        .spawn(move || {
            tailer::tail_file(tail_path, sender, control_receiver, start_at_end, tail)
        })?;

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
                let event = parse_line(&line);
                match &event.typed_event {
                    TypedCombatLogEvent::UnrecognizedEvent(data) => log::warn!(
                        "{}:{line_number}: unrecognized event {}",
                        config.path.display(),
                        data.event_name
                    ),
                    TypedCombatLogEvent::MalformedEvent(data) => log::warn!(
                        "{}:{line_number}: malformed event: {}",
                        config.path.display(),
                        data.reason
                    ),
                    _ => {}
                }
                let json_needed = config.stdout_enabled
                    || websocket
                        .as_ref()
                        .is_some_and(|server| server.format() == WireFormat::Json);
                let event_json = if json_needed {
                    Some(serde_json::to_string(&event)?)
                } else {
                    None
                };
                if config.stdout_enabled
                    && let Some(json) = &event_json
                {
                    output.write_all(json.as_bytes())?;
                    output.write_all(b"\n")?;
                }
                if let Some(websocket) = &websocket {
                    websocket.broadcast_with(|seq| {
                        event_message(config.format, seq, event, event_json.as_deref())
                    })?;
                }
            }
            TailMessage::CaughtUp => {
                output.flush()?;
                if let Some(websocket) = &websocket {
                    websocket.broadcast_control(&Control::LogCaughtUp)?;
                    if !config.tail {
                        thread::sleep(Duration::from_millis(150));
                    }
                }
            }
            TailMessage::LogCleared(response) => {
                if let Some(websocket) = &websocket {
                    websocket.clear_replay()?;
                    websocket.broadcast_control(&Control::LogCleared)?;
                }
                let _ = response.send(Ok(()));
            }
            TailMessage::Error(error) => return Err(error.into()),
        }
    }

    tail_thread.join().map_err(|_| "tailer thread panicked")?;
    Ok(())
}
