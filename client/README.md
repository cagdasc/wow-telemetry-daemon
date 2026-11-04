# WoW Telemetry Client

A Kotlin Multiplatform project with a Desktop/JVM client that connects to the
`wow-telemetry-daemon` WebSocket and narrates incoming combat-event batches.

## Run

The client defaults to `ws://127.0.0.1:8765`:

```sh
./gradlew :desktopApp:run
```

To connect to a daemon on another computer, pass its LAN address:

```sh
./gradlew :desktopApp:run --args="--ws-url ws://192.168.0.15:8765"
```

You can also configure the endpoint with `WOW_TELEMETRY_WS_URL`; the command
line option takes precedence:

```sh
WOW_TELEMETRY_WS_URL=ws://192.168.1.50:8765 ./gradlew :desktopApp:run
```

The client retries the connection every three seconds if the daemon is not
reachable or disconnects. The data layer decodes one JSON text frame per event,
preserves raw string IDs and ordered fields, and exposes events as a Flow. Each
frame carries a tagged `event` object with a `type` discriminator and a
type-specific `data` object. The sealed event classes and decoder are generated
from the daemon's combat-log specification.

## Architecture

- `domain/` contains framework-independent event models, repository contracts,
  and the event observation use case.
- `data/network/` owns Ktor WebSocket transport and event decoding;
  `data/local/` owns Room storage, repositories, and migrations. `data/`
  composes their Koin modules.
- `combat-log/` turns transport events into enriched, analysis-ready event and
  batch models.
- `agent/` owns Koog/Ollama narration and role-based chat memory.
- `presentation/` owns the lifecycle-aware ViewModel and reactive state;
  `desktopApp/` owns Compose UI and JVM-specific startup/wiring.
- The client batches streamed events until 100 events arrive or 1 second passes
  without a new event, then automatically rephrases the batch with a Koog agent
  and Ollama at `http://localhost:11434` using the
  `llama3.2:latest` model. Start Ollama and pull the model (`ollama pull llama3.2:latest`) to use it. Each batch
  displays its first and last
  log timestamps. Koog chat memory carries previous batches and assistant
  replies as separate turns. **Stop chat** clears that history; **Start new chat**
  begins with empty context. Chat history has no size limit yet and may exceed
  the model's context window in long sessions. The agent only describes
  supplied log data and must not invent facts.

Character name, class, specialization, realm, and region can be saved in the
Character settings dialog. The profile is stored in its own Room database and
added as the first user turn of each new chat; it is not embedded in the system
prompt. Profile storage is separate from the existing spell/creature metadata
database, which remains at schema version 1 and requires no migration.

The indexed metadata repositories are ready, but metadata import and encounter
analysis are not implemented yet.

## LAN setup

The daemon listens on localhost by default. To accept clients from another
computer, start it on the daemon host with:

```sh
wow-telemetry-daemon /path/to/WoWCombatLog.txt --ws-addr 0.0.0.0:8765
```

Use the daemon host's LAN IP (not `0.0.0.0`) in the client's `--ws-url`, and
allow TCP port `8765` through the daemon host's firewall. The daemon currently
uses an unauthenticated, unencrypted WebSocket; use it only on a trusted
network.

## Build

```sh
./gradlew :desktopApp:compileKotlin
```

The shared domain, data, combat-log, agent, and presentation modules use Kotlin
Multiplatform with a configured JVM target. The desktop app uses Ktor CIO for
WebSocket transport. Room looks up metadata by indexed primary key; SQLite does
not guarantee strict O (1) lookup. Client test tasks are intentionally not run
unless explicitly requested.
