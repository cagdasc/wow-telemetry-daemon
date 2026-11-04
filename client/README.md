# WoW Telemetry Client

A Kotlin Multiplatform project with a Desktop/JVM client that connects to the
`wow-telemetry-daemon` WebSocket and displays a summary of the latest parsed
combat event.

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
- `data/` contains the Ktor WebSocket repository, JSON DTO mapping, and Room
  schema for spell and NPC metadata.
- `desktopApp/` contains the presentation layer, JVM WebSocket engine, Koog
  event phrasing, and application lifecycle. Koin modules wire app and data
  dependencies.
- The client batches streamed events until 100 events arrive or 1 second passes
  without a new event, then automatically rephrases the batch with a Koog agent
  and Ollama at `http://localhost:11434` using the
  `llama3.2:3b` model. Start Ollama and pull the model (`ollama pull llama3.2:3b`) to use it. Each batch displays its
  first and last
  log timestamps. The agent only phrases deterministic event summaries and
  does not analyze combat or infer facts.

The Room schema and indexed metadata repositories are ready, but metadata import
and encounter analysis are not implemented yet.

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
./gradlew :desktopApp:build
./gradlew :data:allTests
```

The domain and data modules use Kotlin Multiplatform with a configured JVM
target. The desktop app uses Ktor CIO for WebSocket transport. Room looks up
metadata by indexed primary key; SQLite does not guarantee strict O(1) lookup.
