# WoW Telemetry Client

A Kotlin Multiplatform project with a Desktop/JVM client that connects to the
`wow-telemetry-daemon` WebSocket and narrates incoming combat-event batches.

## Run

The client starts disconnected and prefills `127.0.0.1:8765`:

```sh
./gradlew :desktopApp:run
```

Enter a daemon address in the connection panel and select **Connect**. To
prefill a different address, use `--ws-url`:

```sh
./gradlew :desktopApp:run --args="--ws-url ws://192.168.0.15:8765"
```

`WOW_TELEMETRY_WS_URL` also prefills the address; the command-line option takes
precedence:

```sh
WOW_TELEMETRY_WS_URL=ws://192.168.1.50:8765 ./gradlew :desktopApp:run
```

The client retries every three seconds while connected if the daemon is not
reachable or disconnects. Select **Disconnect** to stop retries. The data layer decodes one JSON text frame per event,
preserves raw string IDs and ordered fields, and exposes the stream as a Flow.
The client sends a WebSocket Ping every 20 seconds to keep idle connections
active; the daemon responds with Pong frames.
The daemon also sends `{"control":"LOG_CAUGHT_UP"}` after draining currently
available complete log lines; this flushes the current client batch and does
not mean logging has ended. Event frames carry a tagged `event` object with a
`type` discriminator and a type-specific `data` object. The sealed event
classes and decoder are generated from the daemon's combat-log specification.

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
- The client batches streamed events until the daemon's caught-up marker arrives (with a quiet-time fallback for older
  daemons), then automatically rephrases
  the batch with a Koog agent
  and Ollama at `http://localhost:11434` using the selected model. Open **Settings** to discover installed Ollama models
  and select one. Each batch
  displays its first and last
  log timestamps. Koog chat memory carries previous batches and assistant
  replies as separate turns. **Clear client history** resets that context while
  narration continues. Chat history has no size limit yet and may exceed
  the model's context window in long sessions. The agent only describes
  supplied log data and must not invent facts.

Character name, class, specialization, realm, and region can be saved in the
Character settings dialog. The profile is stored in its own Room database and
added as the first user turn of each new chat; it is not embedded in the system
prompt. Profile storage is separate from the existing spell/creature metadata
database, which remains at schema version 1 and requires no migration.

**Clear client history** removes the current narration and chat context. **Clear daemon backlog** truncates the
combat-log file and empties the daemon's
replay buffer. WoW may keep writing at its old offset, leaving NUL bytes at the
start of the file; the daemon strips them from the first new line. Narration settings persist
temperature in desktop preferences. Supporting `.md` and `.txt` documents can
be placed in `./knowledge`; the client ranks files by word overlap and adds up
to three relevant documents to the Koog prompt. The directory is created when
the client first requests supporting context.

The daemon's `CLEAR_LOG` WebSocket command is unauthenticated and unencrypted,
like the event stream. Any connected client can discard unread daemon entries;
expose the daemon only on trusted networks.

The indexed metadata repositories are ready, but metadata import and encounter
analysis are not implemented yet.

## LAN setup

The daemon listens on localhost by default. To accept clients from another
computer, start it on the daemon host with:

```sh
wow-telemetry-daemon /path/to/WoWCombatLog.txt --ws-addr 0.0.0.0:8765
```

Enter the daemon host's LAN IP (not `0.0.0.0`) in the client's connection panel, and
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
