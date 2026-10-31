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
