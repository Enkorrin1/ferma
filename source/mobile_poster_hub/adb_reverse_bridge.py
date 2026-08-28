"""Keep the Android device's loopback Hub bridge alive.

The phone uses http://127.0.0.1:18082. ADB maps that device port to the Hub's
host-only listener, avoiding an unreliable public quick tunnel without exposing
the Hub on the LAN. The mapping disappears when ADB or the phone restarts, so
this small supervised process verifies and recreates it continuously.
"""

from __future__ import annotations

import argparse
import signal
import subprocess
import threading
import time


def bridge_present(output: str, port: int) -> bool:
    expected = f"tcp:{port} tcp:{port}"
    return any(expected in line for line in output.splitlines())


def run_adb(adb: str, serial: str, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [adb, "-s", serial, *arguments],
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )


def ensure_bridge(adb: str, serial: str, port: int) -> bool:
    state = run_adb(adb, serial, "get-state")
    if state.returncode != 0 or state.stdout.strip() != "device":
        return False
    listing = run_adb(adb, serial, "reverse", "--list")
    if listing.returncode == 0 and bridge_present(listing.stdout, port):
        return True
    created = run_adb(adb, serial, "reverse", f"tcp:{port}", f"tcp:{port}")
    if created.returncode != 0:
        return False
    verified = run_adb(adb, serial, "reverse", "--list")
    return verified.returncode == 0 and bridge_present(verified.stdout, port)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--poll-seconds", type=float, default=5.0)
    args = parser.parse_args()
    stopping = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stopping.set())
    signal.signal(signal.SIGTERM, lambda *_: stopping.set())
    last_status: bool | None = None
    while not stopping.is_set():
        try:
            healthy = ensure_bridge(args.adb, args.serial, args.port)
        except (OSError, subprocess.SubprocessError):
            healthy = False
        if healthy != last_status:
            print("ADB Hub bridge ready" if healthy else "ADB Hub bridge unavailable", flush=True)
            last_status = healthy
        stopping.wait(max(args.poll_seconds, 1.0))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
