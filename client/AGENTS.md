# Client project instructions

- This directory is the Kotlin Multiplatform client project; the current
  configured target is Desktop/JVM.
- Keep WebSocket transport and other JDK-only APIs in `desktopApp` or `jvmMain`,
  not `commonMain`.
- The daemon sends one JSON text frame per recognized combat-log event. Keep
  transport handling lossless; parse event JSON only when a client feature
  needs its fields.
- The default endpoint is `ws://127.0.0.1:8765`. Support configuration through
  `--ws-url` and `WOW_TELEMETRY_WS_URL`.
- LAN use requires the daemon to bind to a reachable interface, typically
  `0.0.0.0:8765`, and the client to use the daemon host's LAN IP. The current
  listener is unauthenticated and unencrypted; document this limitation.
- Validate changes with `./gradlew :desktopApp:build` and relevant tests.

## Architecture and implementation

- The client owns metadata, persistence, deterministic analysis, and presentation. The daemon owns combat-log file I/O, parsing, and event publishing.
- Current implementation reconnects to the daemon and prints event JSON. It has no RoomDB, spell/NPC entities, or encounter state machines yet. Describe these as future work unless implemented.
- Keep transport handling lossless. Parse JSON only when a client feature needs event fields; preserve raw IDs and event data.
- Use Kotlin coroutines with structured concurrency and cancellation-aware work. Keep blocking I/O off the UI thread, use an appropriate I/O dispatcher, and expose UI state reactively.
- Keep JDK-only APIs in `desktopApp` or a JVM source set, not `commonMain`. The shared source set may use common KMP libraries such as Ktor and kotlinx.coroutines.
- If adding Room/SQLite metadata, include schema, migrations, and indexed ID lookups. Do not add database abstractions before a feature requires them.
- Keep all rule evaluation deterministic and testable. Never use an LLM to decide facts or outcomes such as missing interrupts or cooldown tracking.

## WebSocket event contract

- The daemon sends one JSON text frame per recognized combat-log event.
- Current fields are `timestamp`, `event`, `fields`, and optional `source_guid`, `source_name`, `dest_guid`, `dest_name`, `spell_id`, `spell_name`, and `amount`.
- IDs and timestamps are strings. `fields` preserves ordered string-or-null event data. JSON property names are snake_case.
- The timestamp is copied from the log; it is not synchronized UTC time.
- Encounter markers arrive as normal events, including `ENCOUNTER_START` and `ENCOUNTER_END`. The protocol has no separate phase triggers or entity-template fields.
- Coordinate protocol changes with the daemon. Preserve compatibility when practical and test both sides.
