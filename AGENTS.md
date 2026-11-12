# WoW Telemetry System Guide

## System overview

This workspace contains two independent services that work together:

- `daemon/` is a Rust sidecar. It tails the WoW combat log, recognizes and parses CLEU records, and publishes events over WebSockets (Protobuf by default, JSON with `--format json`).
- `client/` is a Kotlin Multiplatform app. It receives the stream and owns client-side state, persistence, analysis, and presentation.

Keep raw file I/O and low-level parsing in the daemon. Keep application state, metadata, deterministic analysis, and presentation in the client. Neither service should depend on the other's implementation; the WebSocket JSON contract is their boundary.

Design pillars:

- Never add in-game Lua hooks or interfere with the game process.
- Keep high-frequency parsing and transport efficient. Avoid allocations on hot paths where practical, and measure before adding complexity.
- Keep rule outcomes deterministic and separate from any generated wording. Never use an LLM to infer facts or decide combat rules.
- Describe implemented behavior accurately. Treat planned capabilities as plans, not existing features.

## Rust daemon (`daemon/`)

### Responsibilities

- Tail `WoWCombatLog.txt` as an out-of-process sidecar.
- Scan byte-oriented log records with `memchr` and parse recognized combat-log events.
- Preserve event fields and publish each recognized event as one Protobuf binary frame (or JSON text frame with `--format json`) to connected WebSocket clients. Also write JSON lines to stdout.
- Keep daemon state and parsing independent from client storage and presentation.

### Constraints

- Keep the daemon's memory footprint and latency low. Preserve bounded channels and queues unless a measured requirement justifies changing them.
- Avoid heap allocations in byte scanning and field extraction when practical. Current parsing uses a `Vec` for fields and allocates for escaped quotes; serialization also allocates. Do not describe the current path as allocation-free.
- Keep parsing tolerant of quoted CSV fields and combat-log format variation. Preserve unrecognized or event-specific fields rather than silently discarding data.
- Return or report errors explicitly. Use standard Rust error types for local errors; use `thiserror` for reusable domain errors or `anyhow` at an application boundary when appropriate. Do not add dependencies without need.
- Bind to localhost by default. LAN binding is unauthenticated and unencrypted; document this limitation when changing network behavior.

## Kotlin Multiplatform client (`client/`)

### Responsibilities

- Consume the daemon's WebSocket event stream without data loss.
- Own client-side metadata, persistence, deterministic analysis, and user-facing state.
- Keep lookup and analysis work efficient; use indexed local queries for ID lookups.
- Present information without changing the underlying event facts.

### Constraints

- Current client config has domain and data KMP modules plus a Desktop/JVM presentation app. Domain models and Room spell/NPC metadata schema exist; metadata import and encounter analysis do not.
- Keep JDK-only APIs in `desktopApp` or a JVM source set, not `commonMain`.
- Use coroutines and structured concurrency for asynchronous work. Keep blocking I/O off the UI thread; use the appropriate I/O dispatcher for blocking operations.
- Expose UI state reactively. Do not block the main thread.
- The client has Room tables `Spell`, `SpellName`, and `Creature` with primary-key ID lookups. Include exported schema and migrations when changing them.
- Preserve transport messages losslessly. Parse event JSON only when a client feature needs its fields.
- The default endpoint is `ws://127.0.0.1:8765`. Support `--ws-url` and `WOW_TELEMETRY_WS_URL`, with the command-line option taking precedence.
- LAN use requires binding the daemon to a reachable interface, typically `0.0.0.0:8765`, and using the daemon host's LAN IP in the client. The current listener has no authentication or encryption; use only on trusted networks.

## Data contract and WebSocket protocol

The daemon sends one frame for each recognized combat-log event: Protobuf binary by default (`daemon/proto/wow_telemetry.proto`, `Envelope{seq, event|control}`, `protocol_version` in `Hello`, seq 0 for unsequenced frames), or JSON text with `--format json`. Clients detect the format by frame type and may send commands as JSON text or binary `Command`. The JSON serialized shape is:

```json
{
  "timestamp": "10/28 15:04:05.123",
  "event": {
    "type": "SPELL_DAMAGE",
    "data": {
      "header": {
        "source_guid": "Player-1",
        "source_name": "Mage",
        "source_flags": 1297,
        "source_raid_flags": 0,
        "dest_guid": "Creature-2",
        "dest_name": "Target",
        "dest_flags": 16,
        "dest_raid_flags": 0
      },
      "spell": {
        "spell_id": "1234",
        "spell_name": "Fireball",
        "spell_school": 4
      },
      "base_amount": 250
    }
  },
  "fields": ["Player-1", "Mage", "0x511", "0", "Creature-2", "Target", "0x10", "0", "1234", "Fireball"]
}
```

- `timestamp` and `event.type` are strings. `event.data` is a typed object specific to `event.type`; models share `header`, `spell`, and optional `advanced_block` structures where applicable.
- `fields` is an ordered array of strings or `null`; `"nil"` input fields become `null`. It preserves raw event data losslessly alongside the typed event.
- IDs, including spell, encounter, map, and GUID values, remain strings at the boundary. Do not assume every combat-log ID fits a numeric type or discard the original `fields`.
- `timestamp` is the original timestamp from the combat log, not a synchronized UTC timestamp or a daemon-generated clock value.
- Every frame (events and control messages) carries a monotonic `"seq"` number added by the daemon. A new connection first receives `{"control":"HELLO","epoch":E,"last_seq":N}`; `epoch` changes on each daemon restart.
- Control messages (`{"control":...}`): `HELLO`, `LOG_CAUGHT_UP` (end of an available batch; the client flushes its batch on it), `LOG_CLEARED`, `REPLAY_GAP` (`reason`: `EPOCH_CHANGED`, `INVALID_RESUME`, `BUFFER_EVICTED`), and `CLEAR_LOG_RESULT`.
- Client commands: `{"command":"RESUME","epoch":E,"from_seq":N}` replays frames after `N` from the daemon's 10 MB in-memory ring buffer; `{"command":"CLEAR_LOG"}` truncates the combat-log file and empties that buffer. The client deduplicates by `seq`. A daemon restart cannot be replayed.
- Encounter and combat signals arrive as ordinary events, such as `ENCOUNTER_START`, `ENCOUNTER_END`, `PLAYER_REGEN_DISABLED`, and `PLAYER_REGEN_ENABLED`. The current protocol has no separate phase-trigger or entity-template fields.

Treat this shape as the current contract, not a promise that all future events have identical fields. When changing serialization, coordinate daemon and client changes and add contract-focused tests. Keep additions backward-compatible when practical; document any breaking change.

## AI development guardrails

- Read the scoped guide in `client/` or `daemon/` before changing that service.
- Keep Rust idiomatic: use clear ownership, explicit error handling, and small focused modules. Do not suppress parse, I/O, or WebSocket failures.
- Keep Kotlin idiomatic: use structured concurrency, cancellation-aware work, and platform-specific APIs only in platform source sets.
- Use Koin modules at data and application boundaries. Keep domain interfaces free of Koin dependencies. Keep Koog out of the domain layer.
- Keep deterministic analysis as ordinary tested code. Never delegate core math or rule logic—including missing-interrupt detection and cooldown tracking—to an LLM.
- LLMs may phrase already-computed results only. They must not invent events, IDs, timings, causes, or rule outcomes.
- Preserve the protocol's raw data and test behavior at service boundaries.

## Validation

- Daemon: run `cargo test` from `daemon/`.
- Client: do not run client tests unless the user explicitly requests them.
- For client changes, run `./gradlew :desktopApp:build` from `client/` only when build validation is needed; do not invoke test tasks as part of the build.
- For protocol changes, validate the serialized payload across the boundary without running client tests unless the user explicitly requests them.
