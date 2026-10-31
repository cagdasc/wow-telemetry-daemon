#!/usr/bin/env python3
"""Run the real daemon and append randomized rows from a sample combat log."""

import argparse
import json
import os
import random
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


DAEMON_DIR = Path(__file__).resolve().parents[1]
DEFAULT_SAMPLE = DAEMON_DIR / "sample_log" / "WoWCombatLog-103126_002522.txt"
READY_MESSAGE = "WebSocket server listening on ws://"
RECOGNIZED_EVENTS = set(
    re.findall(
        r"^  ([A-Z][A-Z0-9_]*):$",
        (DAEMON_DIR / "resources" / "combat-log-spec.yml")
        .read_text()
        .split("\nevents:\n", 1)[1]
        .split("\nenums:\n", 1)[0],
        re.MULTILINE,
    )
) | {"PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED"}
EVENT_NAME_PATTERN = re.compile(rb"(?:  |\t)([A-Z][A-Z0-9_]*)(?:,|$)")


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed


def non_negative_float(value: str) -> float:
    parsed = float(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must not be negative")
    return parsed


def is_recognized_row(row: bytes) -> bool:
    event_name = EVENT_NAME_PATTERN.search(row)
    return bool(
        event_name
        and event_name.group(1).decode("ascii", "ignore") in RECOGNIZED_EVENTS
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Launch the real daemon on a temporary log, then append random "
            "sample rows in batches or as one measured bulk write."
        )
    )
    parser.add_argument("--sample", type=Path, default=DEFAULT_SAMPLE)
    parser.add_argument("--ws-addr", default="127.0.0.1:8765")
    parser.add_argument(
        "--events",
        type=int,
        default=10_000,
        help="number of appended rows; use 0 to run until interrupted (default: 10000)",
    )
    parser.add_argument("--batch-size", type=positive_int, default=100)
    parser.add_argument(
        "--bulk",
        action="store_true",
        help="append all requested rows in one write and report daemon processing throughput",
    )
    parser.add_argument(
        "--interval",
        type=non_negative_float,
        default=0.1,
        help="seconds between batches (default: 0.1)",
    )
    parser.add_argument(
        "--startup-delay",
        type=non_negative_float,
        default=3.0,
        help="seconds to wait after the WebSocket listener starts (default: 3)",
    )
    parser.add_argument(
        "--drain-delay",
        type=non_negative_float,
        default=2.0,
        help="seconds to leave the daemon running after the final batch (default: 2)",
    )
    parser.add_argument(
        "--drain-timeout",
        type=positive_int,
        default=60,
        help="seconds to wait for the daemon to process a bulk send (default: 60)",
    )
    parser.add_argument("--seed", type=int, help="set a repeatable random seed")
    args = parser.parse_args()
    if args.events < 0:
        parser.error("--events must be zero or a positive integer")
    if args.bulk and args.events == 0:
        parser.error("--bulk requires a finite positive --events count")
    return args


def stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return

    try:
        if os.name == "nt":
            process.send_signal(signal.CTRL_BREAK_EVENT)
        else:
            os.killpg(process.pid, signal.SIGINT)
        process.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def main() -> int:
    args = parse_args()
    if not args.sample.is_file():
        print(f"Sample log not found: {args.sample}", file=sys.stderr)
        return 2

    rows = [line for line in args.sample.read_bytes().splitlines() if line.strip()]
    if not rows:
        print(f"Sample log contains no rows: {args.sample}", file=sys.stderr)
        return 2

    ready = threading.Event()
    processed = 0
    processed_condition = threading.Condition()
    daemon_command = [
        "cargo",
        "run",
        "--quiet",
        "--manifest-path",
        str(DAEMON_DIR / "Cargo.toml"),
        "--",
    ]

    with tempfile.TemporaryDirectory(prefix="wow-telemetry-mock-") as temp_dir:
        log_path = Path(temp_dir) / "WoWCombatLog.txt"
        log_path.touch()
        daemon_command.extend([str(log_path), "--ws-addr", args.ws_addr])
        popen_options: dict[str, object] = {
            "cwd": DAEMON_DIR,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.PIPE,
            "text": True,
            "bufsize": 1,
        }
        if os.name == "nt":
            popen_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            popen_options["start_new_session"] = True

        try:
            process = subprocess.Popen(daemon_command, **popen_options)
        except OSError as error:
            print(f"Could not start daemon: {error}", file=sys.stderr)
            return 1

        def forward_daemon_logs() -> None:
            assert process.stderr is not None
            for line in process.stderr:
                print(line, end="", file=sys.stderr, flush=True)
                if READY_MESSAGE in line:
                    ready.set()

        def count_daemon_events() -> None:
            nonlocal processed
            assert process.stdout is not None
            for line in process.stdout:
                try:
                    json.loads(line)
                except json.JSONDecodeError:
                    print(f"Non-JSON daemon stdout: {line.rstrip()}", file=sys.stderr)
                    continue
                with processed_condition:
                    processed += 1
                    processed_condition.notify_all()

        log_thread = threading.Thread(target=forward_daemon_logs, daemon=True)
        log_thread.start()
        event_thread = threading.Thread(target=count_daemon_events, daemon=True)
        event_thread.start()
        rng = random.Random(args.seed)

        try:
            if not ready.wait(timeout=60):
                raise RuntimeError("Timed out waiting for the daemon WebSocket listener")
            if process.poll() is not None:
                raise RuntimeError(f"Daemon exited early with code {process.returncode}")

            print(
                f"Daemon is ready; {len(rows)} sample rows loaded. "
                f"Waiting {args.startup_delay:g}s for clients to connect.",
                flush=True,
            )
            time.sleep(args.startup_delay)

            appended = 0
            with log_path.open("ab", buffering=0) as mock_log:
                if args.bulk:
                    batch_rows = rng.choices(rows, k=args.events)
                    expected = sum(is_recognized_row(row) for row in batch_rows)
                    payload = b"\n".join(batch_rows) + b"\n"
                    start = time.perf_counter()
                    mock_log.write(payload)
                    appended = len(batch_rows)
                    print(
                        f"Bulk-appended {appended} rows ({expected} recognized events); "
                        f"waiting up to {args.drain_timeout}s for daemon processing.",
                        flush=True,
                    )
                    deadline = start + args.drain_timeout
                    with processed_condition:
                        while processed < expected and process.poll() is None:
                            remaining = deadline - time.perf_counter()
                            if remaining <= 0:
                                break
                            processed_condition.wait(timeout=min(remaining, 0.25))
                    elapsed = time.perf_counter() - start
                    if processed < expected:
                        raise RuntimeError(
                            f"Daemon processed {processed}/{expected} recognized events "
                            f"within {args.drain_timeout}s"
                        )
                    rate = processed / elapsed if elapsed > 0 else float("inf")
                    print(
                        f"Bulk processing complete: {processed} events in {elapsed:.3f}s "
                        f"({rate:.0f} events/s); {appended} log rows appended.",
                        flush=True,
                    )
                    if args.drain_delay:
                        print(
                            f"Leaving daemon available for clients for "
                            f"{args.drain_delay:g}s before shutdown.",
                            flush=True,
                        )
                        time.sleep(args.drain_delay)
                else:
                    while args.events == 0 or appended < args.events:
                        count = args.batch_size
                        if args.events:
                            count = min(count, args.events - appended)
                        batch = b"\n".join(rng.choices(rows, k=count)) + b"\n"
                        mock_log.write(batch)
                        appended += count
                        print(f"Appended {appended} mock events", flush=True)
                        if args.interval:
                            time.sleep(args.interval)
            if args.events and not args.bulk:
                print(
                    f"Waiting {args.drain_delay:g}s for the daemon to process the final batch.",
                    flush=True,
                )
                time.sleep(args.drain_delay)
        except KeyboardInterrupt:
            print("\nStopping mock stream.", flush=True)
        except RuntimeError as error:
            print(error, file=sys.stderr)
            return 1
        finally:
            stop_process(process)
            log_thread.join(timeout=1)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
