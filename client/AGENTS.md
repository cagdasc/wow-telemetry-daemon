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
- `domain` contains pure domain models, repository contracts, and the event observation use case. `data` contains the Ktor WebSocket repository, DTO decoding, Room metadata schema, and its Koin module. `desktopApp` owns presentation and application wiring.
- Room schema includes `Spell`, `SpellName`, and `Creature`, with primary-key ID lookups. No metadata import, combat metrics, or encounter state machine exists yet; describe these as future work unless implemented.
- Preserve valid event payload fields and raw IDs through DTO mapping. Parse at the data boundary when a client feature needs structured fields.
- Use Kotlin coroutines with structured concurrency and cancellation-aware work. Keep blocking I/O off the UI thread, use an appropriate I/O dispatcher, and expose UI state reactively.
- Keep JDK-only APIs in `desktopApp` or a JVM source set, not `commonMain`.
- Include the exported Room schema and migrations when changing the current database schema.
- Keep all rule evaluation deterministic and testable. Never use an LLM to decide facts or outcomes such as missing interrupts or cooldown tracking.
- Keep Koog usage outside `domain`. LLMs may phrase already-computed results, but must not calculate combat metrics or invent event facts.

## WebSocket event contract

- The daemon sends one JSON text frame per combat-log line, including typed
  `UNRECOGNIZED_EVENT` and `MALFORMED_EVENT` fallbacks.
- Current fields are `timestamp`, tagged `event`, and raw `fields`. `event` contains a `type` discriminator and a
  type-specific `data` object.
- Event data uses generated sealed classes for every spec event. Shared `header`, `spell`, and optional `advanced_block`
  models represent common layouts.
- IDs, including spell, encounter, map, and GUID values, remain strings. `fields` preserves ordered string-or-null event
  data losslessly.
- Unrecognized events include the original event name and parsed raw fields;
  malformed events include a reason and the exact original line bytes.
- The timestamp is copied from the log; it is not synchronized UTC time.
- Encounter markers arrive as normal events, including `ENCOUNTER_START` and `ENCOUNTER_END`. The protocol has no separate phase triggers or entity-template fields.
- Coordinate protocol changes with the daemon. Preserve compatibility when practical and test both sides.
