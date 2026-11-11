# wow-telemetry-daemon

A lightweight sidecar that tails a WoW combat log and writes typed combat-log
events as JSON Lines to stdout and to connected WebSocket clients.

## Run

```sh
cargo run -- [PATH]
```

The default path is `WoWCombatLog.txt`. The file must exist when the daemon
starts. It begins at the current end of the file, so existing lines are skipped.

By default, the daemon listens for WebSocket clients at
`ws://127.0.0.1:8765`. Each parsed log line is sent as one JSON text frame,
with the same object shape as the stdout JSON Lines output. After reading all
currently available complete lines, the daemon sends
`{"control":"LOG_CAUGHT_UP"}` once for that idle period. This is a temporary
watermark, not a terminal end-of-log signal; the daemon continues tailing and
sends another marker after later lines. Every frame also has a monotonic
`"seq"`. On connect the daemon sends `{"control":"HELLO","epoch":...,"last_seq":...}`;
a client may then send `{"command":"RESUME","epoch":...,"from_seq":N}` to replay
frames from a 10 MB in-memory buffer. If frames were evicted or the daemon
restarted, it sends `{"control":"REPLAY_GAP",...}`. `{"command":"CLEAR_LOG"}`
truncates the log file and empties the buffer. To choose another address:

```sh
cargo run -- [PATH] --ws-addr 127.0.0.1:9000
```

For clients on the local network, bind to an appropriate interface, for
example `--ws-addr 0.0.0.0:8765`. This exposes an unauthenticated, unencrypted
WebSocket listener; use it only on a trusted network. Use `--no-websocket` to
disable the listener and retain stdout output only. Slow clients whose outbound
queue fills apply backpressure: the daemon pauses event processing until the
client catches up instead of dropping events or disconnecting it. This can
delay processing while a client is persistently slow.

For the Windows launcher, cross-build the latest executable for
`x86_64-pc-windows-gnu` and copy it beside `run-windows.ps1`:

```sh
cargo build --release --target x86_64-pc-windows-gnu
cp target/x86_64-pc-windows-gnu/release/wow-telemetry-daemon.exe scripts/wow-telemetry-daemon.exe
```

Then edit `LOG_DIR` in `run-windows.bat` to the World of Warcraft `Logs`
directory. The launcher checks that the executable supports `--from-start`
before backing up or removing any logs.
Double-click the batch file before enabling combat logging. The launcher copies
existing `WoWCombatLog-*.txt` files into a timestamped `Backups` directory under
`Logs`, removes the originals after all copies succeed, waits for a new
timestamped log, then starts the daemon on that file. The daemon reads the new
file from its beginning, including bytes written before it opens the file. You
can override the folder by setting `WOW_COMBAT_LOG_DIR` before running the
batch file. The daemon binds to `0.0.0.0:8765`, so allow that port through
Windows Firewall if a client connects from another computer.

## Generate a mock event stream

To exercise a client without a live WoW log, use the mock stream script. It
starts the real daemon against a temporary empty log, then appends random rows
from `sample_log/WoWCombatLog-103126_002522.txt` in batches:

```sh
python3 scripts/mock_stream.py --ws-addr 0.0.0.0:8765
```

Connect the client to the machine running the script. By default, the script
waits three seconds for clients to connect, then sends 10,000 rows in batches
of 100 every 0.1 seconds, waits two seconds for the daemon to process the final
batch, then stops the daemon. To stream continuously until
Ctrl+C:

```sh
python3 scripts/mock_stream.py --events 0 --ws-addr 0.0.0.0:8765
```

For a one-write bulk test, use `--bulk`. The script counts recognized events
emitted by the daemon and reports processing time and events per second:

```sh
python3 scripts/mock_stream.py --bulk --events 50000 --ws-addr 0.0.0.0:8765
```

Bulk mode requires a finite event count. Its timer covers appending the rows and
waiting for the daemon to parse and emit all recognized events; it does not
measure when a remote client finishes consuming them.

Use `--sample PATH`, `--events COUNT`, `--batch-size COUNT`, `--interval
SECONDS`, `--startup-delay SECONDS`, `--drain-delay SECONDS`,
`--drain-timeout SECONDS`, and `--seed INTEGER` to customize the sample and
stream. For example:

```sh
python3 scripts/mock_stream.py --events 50000 --batch-size 500 --interval 0.05 --seed 42
```

The script requires Python 3 and Cargo. It does not modify the source sample
log or require extra Python packages.

## Combat-log format and event coverage

`resources/combat-log-spec.yml` is the format reference used by the parser. The
parser recognizes all 62 event names in that specification, plus
`PLAYER_REGEN_DISABLED` and `PLAYER_REGEN_ENABLED`. Recognized events are:

- **Damage:** `SPELL_DAMAGE`, `SPELL_PERIODIC_DAMAGE`, `RANGE_DAMAGE`,
  `DAMAGE_SHIELD`, `DAMAGE_SPLIT`, `SPELL_DAMAGE_SUPPORT`,
  `SPELL_PERIODIC_DAMAGE_SUPPORT`, `RANGE_DAMAGE_SUPPORT`,
  `SWING_DAMAGE_LANDED_SUPPORT`, `SWING_DAMAGE`, `SWING_DAMAGE_LANDED`,
  `ENVIRONMENTAL_DAMAGE`.
- **Healing and absorbs:** `SPELL_HEAL`, `SPELL_PERIODIC_HEAL`,
  `SPELL_HEAL_SUPPORT`, `SPELL_PERIODIC_HEAL_SUPPORT`, `SPELL_ABSORBED`,
  `SPELL_ABSORBED_SUPPORT`, `SPELL_HEAL_ABSORBED`.
- **Auras and utility:** `SPELL_AURA_APPLIED`, `SPELL_AURA_REMOVED`,
  `SPELL_AURA_REFRESH`, `SPELL_AURA_BROKEN`, `SPELL_AURA_BROKEN_SPELL`,
  `SPELL_AURA_APPLIED_DOSE`, `SPELL_AURA_REMOVED_DOSE`, `SPELL_INTERRUPT`,
  `SPELL_DISPEL`, `SPELL_DISPEL_FAILED`, `SPELL_STOLEN`, `SPELL_ENERGIZE`,
  `SPELL_DRAIN`, `SPELL_LEECH`, `SPELL_PERIODIC_ENERGIZE`, `SPELL_CAST_START`,
  `SPELL_CAST_SUCCESS`, `SPELL_EMPOWER_START`, `SPELL_EMPOWER_END`,
  `SPELL_EMPOWER_INTERRUPT`, `SPELL_CAST_FAILED`, `SWING_MISSED`,
  `SPELL_MISSED`, `RANGE_MISSED`, `SPELL_PERIODIC_MISSED`,
  `DAMAGE_SHIELD_MISSED`, `SPELL_SUMMON`, `SPELL_RESURRECT`,
  `SPELL_INSTAKILL`.
- **Unit, encounter, and log metadata:** `UNIT_DIED`, `UNIT_DESTROYED`,
  `UNIT_DISSIPATES`, `UNIT_HEALTH`, `ENCOUNTER_START`, `ENCOUNTER_END`,
  `CHALLENGE_MODE_START`, `CHALLENGE_MODE_END`, `ZONE_CHANGE`, `MAP_CHANGE`,
  `WORLD_MARKER_PLACED`, `WORLD_MARKER_REMOVED`, `COMBATANT_INFO`,
  `COMBAT_LOG_VERSION`.
- **Combat state:** `PLAYER_REGEN_DISABLED`, `PLAYER_REGEN_ENABLED`.

The parser accepts a two-or-more-space separator or a tab between timestamp and
event data. CSV commas inside quoted fields and doubled quotes are handled.
The timestamp is retained as written; its format (including local-time UTC
offsets) is not validated or normalized. Unknown event names are ignored.

Each JSON object contains `timestamp`, a tagged `event` object, and raw
`fields`. The event object has `type` and `data` properties. `type` is the
combat-log event name; `data` contains that event's typed model. Models share
`header`, `spell`, and optional `advanced_block` objects where the
specification defines them, plus event-specific fields. IDs such as spell,
encounter, map, and GUID values remain strings. Numeric combat values use JSON
numbers. A YAML `nil` value becomes JSON `null`.

`fields` preserves every CSV value after the event name, in order; array index
0 corresponds to event offset 1 in the YAML. Unknown or newly appended values
remain available in `fields`. Advanced-logging offsets are selected from row
width, not the version header.

The YAML specification generates the parser's per-event field layouts, Rust
and Kotlin event models, and JSON schema. Inherited layouts, variable
`SPELL_ABSORBED` forms, optional advanced-logging blocks, nested unit/spell
blocks, and `COMBATANT_INFO` CSV/bracket fields are handled. Numeric, boolean,
and hexadecimal values are converted to their typed event fields. A mid-log
`COMBAT_LOG_VERSION` clears the daemon's event counters and combat-active flag
before recording the new version event. The daemon does not maintain pet
ownership, aura, or encounter-analysis state.

The generated `resources/combat-log-spec.json` mirrors the YAML reference.
`resources/combat-log-event.schema.json` describes the WebSocket JSON shape.
Unknown event names are emitted as `UNRECOGNIZED_EVENT` with their original
name and parsed fields. Invalid lines are emitted as `MALFORMED_EVENT` with a
parse reason and the exact original bytes, so clients can report them rather
than losing the record.
The Rust and Kotlin event models and parser metadata are also generated from
the YAML specification:

```sh
python3 -m pip install PyYAML
python3 scripts/generate_combat_log_artifacts.py
python3 scripts/generate_combat_log_artifacts.py --check
```
