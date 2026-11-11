# Combat-log streaming follow-up

## Problem to revisit

The client has seen missing events near the beginning and end of a log stream.
Determine whether this comes from the game's log writes, the daemon's startup
position, partial lines at EOF, or file rotation/truncation before changing the
tailing behavior.

## Current behavior

- The daemon opens the configured file and seeks to its current end on startup,
  intentionally skipping all existing content.
- It emits a record only after reading a newline. An incomplete final line
  stays buffered while the file is unchanged.
- On detected truncation or replacement, it reopens the file from offset zero
  and clears the pending partial line.
- The daemon does not modify or clear the game log.

These policies can make startup records or a final unterminated record appear
missing, depending on when the daemon starts and how the game writes or rotates
the file. Confirm the exact failure mode before selecting a policy.

## Next investigation and implementation

1. Reproduce and count events from a controlled log for: daemon startup with
   existing content, a line appended in multiple writes, a final line without a
   newline, truncation, and replacement/rotation.
2. Observe the actual WoW log lifecycle: when records are flushed, whether a
   new log file is created per session, and how its path changes.
3. Decide and document the intended startup policy: follow only new records,
   replay the current file from its beginning, or support an explicit choice.
   Avoid silently replaying old logs or changing existing default behavior.
4. Prefer improving the daemon's follow/reopen logic over clearing the source
   log. Clearing is destructive, can discard evidence/events, and should not be
   done automatically. Only consider it with an explicit user-approved workflow
   if observation shows it is necessary.
5. Implement the chosen policy with bounded buffering, ordering, and explicit
   I/O error reporting. Define how incomplete trailing bytes are handled on
   rotation and shutdown, and avoid dropping them without a visible outcome.
6. Add focused tailer tests for the reproduced cases, then run `cargo test`
   from `daemon/`.

No behavior change is intended by this reminder.
