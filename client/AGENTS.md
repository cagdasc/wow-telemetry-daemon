# Client project instructions

- This directory is the Kotlin Multiplatform client project; the current
  configured target is Desktop/JVM.
- Keep WebSocket transport and other JDK-only APIs in `desktopApp` or `jvmMain`,
  not `commonMain`.
- The daemon sends one Protobuf binary frame per recognized combat-log event (Wire-generated from
  `data/network/src/commonMain/proto`, mapped by the generated `ProtoCombatLogEventMapper`); JSON text frames are still
  decoded when the daemon runs with `--format json`. Keep transport handling lossless.
- The default endpoint is `ws://127.0.0.1:8765`. Support configuration through
  `--ws-url` and `WOW_TELEMETRY_WS_URL`.
- LAN use requires the daemon to bind to a reachable interface, typically
  `0.0.0.0:8765`, and the client to use the daemon host's LAN IP. The current
  listener is unauthenticated and unencrypted; document this limitation.
- Do not run client test tasks unless the user explicitly requests them.
- For client changes, run a production compilation/build only when needed; avoid tasks that invoke tests.

## Stream handling

- `CombatEventRepositoryImpl` tracks `epoch` and last `seq`, sends `RESUME` after reconnect, drops duplicates, logs
  `REPLAY_GAP`, and prints every raw payload from a separate unbounded channel/coroutine so console speed cannot block
  receiving.
- Batches flush only on `LOG_CAUGHT_UP` or `LOG_CLEARED`, plus the strategy in `NarrationSettings.batchFlushStrategy`:
  `SILENCE_WATCHDOG` (default, `batchSilenceTimeoutSeconds` 5-600, default 30) or `FLUSH_ON_DISCONNECT`. Do not
  reintroduce a short idle timeout or size cap.
- The narration batch queue is unbounded so slow Ollama never blocks event receipt.
- Connection is manual (Connect/Disconnect); `--ws-url` and `WOW_TELEMETRY_WS_URL` only prefill the address. Clear
  client history and Clear daemon backlog are independent actions.
- Ingest mode is `LIVE` (default, decode and narrate immediately) or `COLLECT` (spill raw frames to a `.wowlog` session
  file via `CombatLogSessionStore`, decode nothing). "Process now" and the Sessions dialog replay stored frames through
  the same decode pipeline as live frames, in 2,000-frame chunks that Stop can interrupt between chunks. During replay
  batches only bound memory (flush on `ENCOUNTER_START`/`ENCOUNTER_END`, on `LOG_CAUGHT_UP`/`LOG_CLEARED`, and on a gap
  of `batchSilenceTimeoutSeconds` between event timestamps) and are not narrated. When a replay completes with no
  encounter still open, one LLM overview of the computed statistics is requested (`CombatEventNarrator.summarize`);
  an open encounter stays in the batch and no summary is made until a later Process finishes it. Session files are
  append-only, never rotated: header `WOWL` + version byte,
  then `[kind u8][length u32 BE][payload]` records holding frames exactly as received (protobuf or JSON text). Files
  live
  in `<config>/WowTelemetry/sessions` on desktop and `filesDir/sessions` on Android. A truncated tail is ignored. If a
  write fails the repository falls back to `LIVE` and reports `collectError`. Desktop can open any file via a
  `JFileChooser`; Android lists only in-app sessions (no document picker yet).
- The Ask box answers from `CombatLogAnalyzer` (`combat-log`), which accumulates deterministic damage, healing,
  interrupt, death and per-encounter statistics from processed events (live or replayed). The LLM receives only that
  computed summary plus the question (`CombatEventNarrator.answer`, separate chat session) and never raw log rows.
  Statistics reset on Clear View and when a session file is opened.
- Ollama models are discovered from `/api/tags`; build `LLModel(provider = LLMProvider.Ollama, id = selectedModel)`,
  never copy another model's metadata. Supporting docs come from `./knowledge` (`.md`/`.txt`).

## Architecture and implementation

- The client owns metadata, persistence, deterministic analysis, and presentation. The daemon owns combat-log file I/O, parsing, and event publishing.
- `domain` contains pure domain models, repository contracts, and the event observation use case.
- `data/network` owns WebSocket transport and typed DTO decoding; `data/local` owns Room entities, DAOs, repositories,
  and migrations. `data` composes their Koin modules.
- `combat-log` owns deterministic transformation and enrichment of transport events into analysis-ready combat entries
  and batches. Keep rule evaluation deterministic and testable.
- `agent` owns Koog/Ollama interaction and chat memory. Character-profile context is a user turn, never part of the
  system prompt.
- `presentation` owns the lifecycle-aware ViewModel and reactive UI state. `desktopApp` owns Compose screens, JVM-only
  APIs, and platform-specific dependency wiring.
- Room schema includes `Spell`, `SpellName`, and `Creature`, with primary-key ID lookups. No metadata import, combat metrics, or encounter state machine exists yet; describe these as future work unless implemented.
- Preserve valid event payload fields and raw IDs through DTO mapping. Parse at the data boundary when a client feature needs structured fields.
- Use Kotlin coroutines with structured concurrency and cancellation-aware work. Keep blocking I/O off the UI thread, use an appropriate I/O dispatcher, and expose UI state reactively.
- Keep JDK-only APIs in `desktopApp` or a JVM source set, not `commonMain`.
- Include the exported Room schema and migrations when changing the current database schema.
- Keep all rule evaluation deterministic and testable. Never use an LLM to decide facts or outcomes such as missing interrupts or cooldown tracking.
- Keep Koog usage outside `domain`. LLMs may phrase already-computed results, but must not calculate combat metrics or invent event facts.
- Character profiles live in a separate Room database from spell/NPC metadata, so profile changes do not migrate the
  existing metadata database.

## WebSocket event contract

- The daemon sends one frame per combat-log line, including typed
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
