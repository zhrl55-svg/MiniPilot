"""Start the stub hub relay on port 8790 (never prod).

The stub relay routes between a fake phone and the executor locally; no task
run by probe-runner needs it today (the headless `claude -p` scratch dir has no
network access to any hub), but any future probe task that touches the relay
must connect here, never to ws://192.168.2.102:8787 or the tailnet address.

    python tools/probe/start_relay.py                # 127.0.0.1:8790, key nm_probe
    python tools/probe/start_relay.py --keys nm_probe nm_phone

It runs until Ctrl+C. See tools/hub/dev_relay.py for the underlying relay.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
DEV_RELAY = HERE.parent / "hub" / "dev_relay.py"
DEFAULT_PORT = 8790


def main() -> int:
    p = argparse.ArgumentParser(description="stub hub relay on 8790")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--keys", nargs="*", default=["nm_probe", "nm_phone"])
    args = p.parse_args()

    if not DEV_RELAY.exists():
        print(f"missing {DEV_RELAY}", file=sys.stderr)
        return 1

    cmd = [sys.executable, str(DEV_RELAY), "--port", str(args.port)]
    for k in args.keys:
        cmd += ["--key", k]
    print(f"starting: {' '.join(cmd)}", file=sys.stderr)
    return subprocess.call(cmd)


if __name__ == "__main__":
    sys.exit(main())
