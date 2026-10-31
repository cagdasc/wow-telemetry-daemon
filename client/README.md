# WoW Telemetry Client

A Kotlin Multiplatform project with a Desktop/JVM client that connects to the
`wow-telemetry-daemon` WebSocket and prints each incoming event JSON message to
the application console.

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
reachable or disconnects. Incoming WebSocket text messages are printed as
received, one event per line.

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
```

The shared module is Kotlin Multiplatform, but the current project configures
only a JVM target for its desktop application. The WebSocket transport uses the
JDK `java.net.http.WebSocket` API in the desktop app.
