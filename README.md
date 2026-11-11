# WoW Telemetry

Out-of-process telemetry for World of Warcraft combat logs. It never touches the game process and adds no in-game Lua hooks.

- **`daemon/`** (Rust) tails `WoWCombatLog.txt`, parses each combat-log line into a typed event, and publishes one JSON text frame per event over WebSocket. It also writes JSON lines to stdout.
- **`client/`** (Kotlin Multiplatform, Desktop/JVM) connects to the daemon, groups events into batches, and asks a local Ollama model to narrate each batch. Supporting documents from `./knowledge` are added to the prompt as context.

The WebSocket JSON stream is the only contract between them.

## What works today

- Typed events for the events in the combat-log spec, plus typed fallbacks for unrecognized and malformed lines.
- Sequenced frames, a 10 MB in-memory replay buffer, and `RESUME` after a client reconnect.
- A `LOG_CAUGHT_UP` marker that tells the client a batch is complete.
- Manual Connect/Disconnect, clearing client history, and clearing the daemon's log file.
- Ollama model discovery and selection, a temperature setting, and a configurable batch-flush strategy.

## Not implemented yet

Metadata import, combat metrics, encounter analysis, and durable replay across daemon restarts. Narration only rephrases the received events; it does not compute combat rules.

## Quick start

```sh
cd daemon && cargo run --release -- "path/to/WoWCombatLog.txt"
cd client && ./gradlew :desktopApp:run
```

Enter the daemon address (default `127.0.0.1:8765`) in the client and press Connect.

The WebSocket listener is unauthenticated and unencrypted. It binds to localhost by default; use `--ws-addr 0.0.0.0:8765` only on a trusted network.

See `daemon/README.md` and `client/README.md` for details, and `AGENTS.md` for the protocol contract.
