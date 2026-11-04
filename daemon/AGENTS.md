# Daemon Agent Guide

Read the workspace-root `AGENTS.md` for the system architecture and current WebSocket contract.

## Responsibilities

- Tail `WoWCombatLog.txt` out of process.
- Recognize and parse combat-log events from bytes, using `memchr` for scanning.
- Write one typed event per log line as JSON lines to stdout and broadcast one JSON text frame per event to WebSocket clients.
- Keep the daemon independent from client-specific storage, metadata, and presentation.

## Constraints

- Preserve bounded queues, low memory use, and current event ordering.
- Keep byte scanning and field extraction allocation-light. The current parser allocates its field `Vec` and may
  allocate to unescape quoted fields; JSON serialization also allocates.
- Preserve the raw ordered event fields and support quoted commas, escaped quotes, `nil` fields, and event-specific
  layouts.
- Publish unknown event names and malformed lines as typed fallback events; retain unknown fields or malformed raw
  bytes and a parse reason instead of dropping those records.
- Keep the JSON property names, string ID values, and timestamp semantics in the root contract. Coordinate serialization
  changes with the client.
- Surface file, parse, serialization, and network failures. Do not silently drop events or replace errors with
  success-shaped defaults.
- Keep the listener bound to localhost by default. If documenting or changing LAN binding, state that WebSocket traffic
  has no authentication or encryption.
- Keep dependencies focused. Use clear Rust ownership and explicit error types; use `thiserror` or `anyhow` only where
  their error model fits.

## Validation

Run `cargo test` from `daemon/`. For protocol changes, also build/test the client and verify the actual serialized event
shape.
