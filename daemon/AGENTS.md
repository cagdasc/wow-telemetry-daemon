# Daemon Agent Guide

Read the workspace-root `AGENTS.md` for system-wide architecture and current WebSocket contract.

## Overview and Responsibilities

`daemon/` is a lightweight, out-of-process Rust sidecar for World of Warcraft combat log telemetry.
It tails `WoWCombatLog.txt`, tokenizes and parses combat-log event records (CLEU), and broadcasts typed events across WebSockets to connected clients (e.g. the Kotlin Multiplatform client in `client/`) as Protobuf binary frames by default (`--format protobuf`) or JSON text frames (`--format json`), while optionally writing JSON Lines to stdout (`--stdout`).

### Key Responsibilities
- Tail the combat log file out of process without hooking into or interfering with the game process.
- Low-latency, byte-oriented parsing of raw combat-log lines using `memchr` scanning.
- Preserve event fields losslessly and publish each recognized event as a typed frame with monotonic sequencing (`seq`).
- Provide resilient connection recovery via an in-memory replay ring buffer (`ReplayBuffer`).
- Support log truncation and reader rewinding on demand (`CLEAR_LOG`) while gracefully handling WoW file pointer quirks.
- Keep the daemon strictly independent from client-side storage, metadata lookup, deterministic analysis, and UI presentation.

---

## Project Structure and Architecture

```
daemon/
├── build.rs                    # Compiles proto/wow_telemetry.proto via prost-build
├── Cargo.toml                  # Package manifest and dependencies (memchr, prost, tungstenite, etc.)
├── proto/
│   └── wow_telemetry.proto     # Protobuf schema (Envelope, Event, Control, Command, typed payloads)
├── scripts/
│   ├── generate_combat_log_artifacts.py  # Code generator for proto, Rust specs, and Kotlin mappers
│   ├── mock_stream.py          # Synthetic event streaming test harness
│   ├── run.sh                  # Interactive bash launcher (Linux/macOS)
│   ├── run-windows.bat         # Windows batch launcher
│   └── run-windows.ps1         # Windows interactive PowerShell launcher
└── src/
    ├── main.rs                 # CLI entry point and StderrLogger configuration
    ├── lib.rs                  # Library module declarations and generated protobuf includes
    ├── cli.rs                  # CLI argument parsing (Config struct and flags)
    ├── tailer.rs               # Background file tailer thread (file watching, rotation, NUL handling)
    ├── parser.rs               # Byte-level tokenizer and CLEU parser using memchr
    ├── pipeline.rs             # Central event coordination pipeline and thread orchestration
    ├── protocol.rs             # Protocol types, wire formats (Protobuf/JSON), control and command messages
    ├── websocket.rs            # Multi-client WebSocket server, replay ring buffer, client queues
    ├── proto_events.rs         # Protobuf serialization mapping for spec events
    ├── spec_events.rs          # Event definitions, field types, and specification lookup tables
    └── typed_events.rs         # Strongly-typed Rust data structures for all CLEU event payloads
```

### Module Breakdown

#### `src/main.rs`
- Application entry point.
- Initializes a minimal stderr logger (`StderrLogger`) that outputs logs at `log::Level::Info` or above without polluting stdout.
- Reads environment CLI arguments via `cli::from_env()` and hands off execution to `pipeline::run_pipeline(config)`.

#### `src/cli.rs`
- Defines `Config` and parses command-line arguments.
- Defaults:
  - Combat log path: `WoWCombatLog.txt`
  - WebSocket address: `127.0.0.1:8765`
  - WebSocket enabled: `true`
  - Start position: seek to end of file (`start_at_end = true`)
  - Stdout JSON Lines: `false`
  - Wire format: `WireFormat::Protobuf`
- Flags: `--file <path>` (`-f`, `--log-file` for existing logs), `--ws-addr <addr>`, `--format <protobuf|json>`, `--stdout`, `--quiet`, `--from-start`, `--no-websocket`.

#### `src/tailer.rs`
- Resilient combat log tailer running in a dedicated thread (`combat-log-tailer`).
- **Existing log files & Cold start**: In `--file` mode, fails immediately if the file does not exist. In live tail mode, if the log file does not yet exist, `wait_for_reader` polls every 500ms (`MISSING_FILE_RETRY`) so the daemon can start before WoW launches.
- **Reading loop**: Uses `BufReader::read_until(b'\n')`.
- **EOF & Batch notification**: When hitting EOF after reading available complete lines, emits `TailMessage::CaughtUp` once, then sleeps 2ms (`EOF_SLEEP`). Clients use `LOG_CAUGHT_UP` to delimit event batches.
- **File replacement & rotation**: Detects file rotation or replacement across platforms:
  - Unix: compares device ID and inode (`open.dev() != current.dev() || open.ino() != current.ino()`).
  - Windows: compares file creation timestamp (`open.creation_time() != current.creation_time()`).
  - Truncation: detects if `reader.stream_position()? > open_metadata.len()`.
  - Reopens seamlessly from the start of the replacement file.
- **In-place truncation & NUL-byte stripping**: When `TailCommand::ClearLog` is received, `truncate_log` calls `set_len(0)` and seeks the reader back to 0. Because WoW holds its open handle and continues writing at its stale file offset, the file system fills the intervening gap with NUL bytes (`\0`). `without_leading_nuls` strips all leading NULs from read lines so subsequent event parsing succeeds cleanly.
- **Channel interface**: Communicates with the pipeline via bounded `SyncSender<TailMessage>` (capacity 1024) and receives commands via `Receiver<TailCommand>` (capacity 8).

#### `src/pipeline.rs`
- The central orchestrator connecting the tailer, parser, stdout writer, and WebSocket server.
- Spawns the `combat-log-tailer` thread.
- Listens on `receiver.recv()` for `TailMessage`:
  - `TailMessage::Line(line)`: Strips trailing `\r`/`\n`, parses with `parser::parse_line`, logs warnings on `UnrecognizedEvent` or `MalformedEvent`, serializes to JSON if needed by stdout or JSON WebSocket clients, and dispatches to `websocket.broadcast_with(...)`.
  - `TailMessage::CaughtUp`: Flushes stdout buffers and broadcasts `Control::LogCaughtUp`.
  - `TailMessage::LogCleared`: Clears the WebSocket replay buffer via `websocket.clear_replay()`, broadcasts `Control::LogCleared`, and responds to the command initiator.
  - `TailMessage::Error`: Terminates the pipeline and surfaces the I/O error.

#### `src/parser.rs`
- High-performance, low-allocation tokenizer and CLEU parser.
- Uses `memchr` and `memchr2` for rapid byte scanning without allocating intermediate strings where possible.
- Splits the timestamp string (`MM/DD HH:MM:SS.mmm`) from the event payload.
- Implements `Fields` iterator to tokenize comma-delimited fields while handling:
  - Quoted fields containing commas.
  - Escaped double quotes (`""`).
  - Bracket-nested subfields (`[...]`) used in advanced combatant info.
  - `nil` unit fields representing null/empty values.
- Emits `CombatEvent<'a>` containing `Cow<'a, str>` timestamps, raw field values, and a typed `TypedCombatLogEvent`.
- Fallbacks: Unrecognized event names become `TypedCombatLogEvent::UnrecognizedEvent(data)`; unparseable lines become `TypedCombatLogEvent::MalformedEvent(data)` preserving the original bytes and parse error reason.

#### `src/protocol.rs`
- Defines wire formats (`WireFormat::Protobuf` and `WireFormat::Json`), `PROTOCOL_VERSION = 1`, control message types, and client commands.
- Control messages:
  - `HELLO`: Dispatched upon initial client handshake with `epoch` and `last_seq`.
  - `LOG_CAUGHT_UP`: Delimits completion of an available batch.
  - `LOG_CLEARED`: Emitted after log truncation and replay buffer wipe.
  - `REPLAY_GAP`: Dispatched with reasons `EPOCH_CHANGED`, `INVALID_RESUME`, or `BUFFER_EVICTED` (`missed_from`, `available_from`).
  - `CLEAR_LOG_RESULT`: Result of a client-issued `CLEAR_LOG` command.
- Client commands:
  - `RESUME`: Requests replay of frames from `from_seq` for a given `epoch`.
  - `CLEAR_LOG`: Truncates combat log file and clears daemon replay buffer.

#### `src/websocket.rs`
- Multi-client WebSocket server using `tungstenite`.
- Threading model:
  - TCP accept thread accepting incoming client connections.
  - Per-client reader thread handling ping/pong and incoming commands (`RESUME`, `CLEAR_LOG`).
  - Per-client writer thread consuming from a bounded channel (`CLIENT_QUEUE_SIZE = 8192`, `CLIENT_DRAIN_BATCH = 4096`).
- **Replay buffer**: Maintains a 10 MB in-memory ring buffer (`ReplayBuffer`) of recent serialized frames. Old frames are evicted when capacity is reached.
- **Sequence numbering**: Every broadcast frame receives a monotonic `seq` number.
- **Epoch tracking**: Each daemon run generates a unique timestamp `epoch: u64`. Replay across different epochs is disallowed.
- **Backpressure**: If a slow client's bounded outbound queue fills, the daemon pauses event processing until the client drains, guaranteeing no silent event loss.

#### `src/proto_events.rs`, `src/spec_events.rs`, `src/typed_events.rs`
- Generated and spec-driven modules containing AST structures, spec lookup maps, and Protobuf encoding routines.
- Updated via `scripts/generate_combat_log_artifacts.py`.

---

## Concurrency and Channel Architecture

```
                     ┌──────────────────┐
                     │ WoWCombatLog.txt │
                     └────────┬─────────┘
                              │
                     ┌────────▼─────────┐
                     │  tailer thread   │◄──────┐
                     │ (tail_loop, EOF) │       │ TailCommand::ClearLog (8)
                     └────────┬─────────┘       │
                              │ TailMessage (1024)
                     ┌────────▼─────────┐       │
                     │ pipeline thread  │       │
                     │ (parse, serialize│       │
                     └────────┬─────────┘       │
                              │ broadcast       │
                     ┌────────▼─────────┐       │
                     │ WebSocketServer  │───────┘
                     │ (ReplayBuffer)   │
                     └──┬─────────────┬─┘
                        │             │
        Bounded Channel │ (8192)      │ Bounded Channel (8192)
                        ▼             ▼
                 ┌────────────┐ ┌────────────┐
                 │ Client 1   │ │ Client 2   │
                 └────────────┘ └────────────┘
```

1. **Tailer to Pipeline**: `SyncSender<TailMessage>` with capacity `1024`.
2. **WebSocket to Tailer**: `SyncSender<TailCommand>` with capacity `8`.
3. **Pipeline to Client Writers**: Per-client bounded channel of `8192` frames.
4. **Backpressure**: Slow network consumers backpressure the pipeline thread, which in turn backpressures the tailer thread. Memory usage remains strictly bounded.

---

## Development Constraints and Guidelines

- **Allocation Discipline**: Keep byte scanning and field extraction allocation-light. Do not describe the parser as zero-allocation because field `Vec` and unescaping allocate, and serialization allocates.
- **Error Handling**: Return explicit errors. Do not silently drop unrecognized lines or malformed payloads; preserve raw fields or bytes in typed fallback events.
- **Network Security**: The listener binds to localhost (`127.0.0.1:8765`) by default. LAN binding (`0.0.0.0:8765`) is unauthenticated and unencrypted; document this risk when altering network configurations.
- **Protobuf & Code Generation**: `proto/wow_telemetry.proto` is generated by `scripts/generate_combat_log_artifacts.py` along with `src/proto_events.rs` and client mappings. When modifying combat-log event specs, regenerate artifacts and update both daemon and client together.
- **Cross-Compilation**: Release binaries for Windows are compiled with target `x86_64-pc-windows-gnu`. Keep builds warning-free under `clippy` and standard `cargo test`.
- **Timing and Sleeps**: Never introduce per-event sleeps. The tailer sleeps only on EOF (`2ms`) and missing file retry (`500ms`).

---

## Validation

- Run unit and integration tests from `daemon/`:
  ```sh
  cargo test
  ```
- Ensure all 30+ tests pass (covering parser, tailer NUL stripping and rotation, replay buffer, and websocket commands).
- For protocol or serialization changes, build and verify against client expectations without modifying independent client business logic.
