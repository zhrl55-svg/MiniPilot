"""Pull self-improve issue cards off the device onto the PC.

Phase 2 of the self-improvement loop: the device writes issue cards to
`/var/minis/shared/self-improve/`; this pulls them into `tools/probe/cards/`
so the PC-side improver can cluster, rank and turn them into proposals.

Transport: the debug JSON-RPC. We deliberately do NOT use `debug.readFile`
— it kills the debug server's accept loop (the file is read on the wrong
thread and the socket stops answering; the app must be restarted). Instead
we bundle the directory with `tar | base64` through `debug.shellExecute`,
which is a normal guest shell command and returns plain text.

    python tools/probe/pull_cards.py --date 2026-11-20
    python tools/probe/pull_cards.py --endpoint 127.0.0.1:5321 --out cards
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import pathlib
import sys
import tarfile
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_device import DEFAULT_PORT, DEFAULT_SERIAL, Device, forward  # noqa: E402

CARDS_DIR = "/var/minis/shared/self-improve"

# Issue cards quote real user conversations, so they never land in the repo.
# Default output is a temp directory outside the working tree; `--out` may
# point anywhere, but a path inside the repo is refused unless the caller
# also passes --allow-in-repo.
DEFAULT_OUT = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "cards"


def fetch_bundle(dev: Device, remote_dir: str = CARDS_DIR) -> bytes:
    """Return a tar.gz of the remote card directory."""
    cmd = (
        f'cd {remote_dir} && tar -czf - *.json 2>/dev/null | base64 | tr -d "\\n"'
    )
    out = dev.call("debug.shellExecute", {"command": cmd, "timeout": 60},
                   timeout=120).get("output", "")
    if not out.strip():
        return b""
    return base64.b64decode(out)


def main() -> int:
    ap = argparse.ArgumentParser(description="pull self-improve issue cards from the device")
    ap.add_argument("--serial", default=DEFAULT_SERIAL, help="adb serial of the phone")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--endpoint", default=None,
                    help="host:port of an already-reachable debug server; skips adb forward")
    ap.add_argument("--remote-dir", default=CARDS_DIR)
    ap.add_argument("--out", default=str(DEFAULT_OUT),
                    help="local output directory (default: outside the repo, in the temp dir)")
    ap.add_argument("--date", default=None, help="keep only cards whose name contains this date")
    ap.add_argument("--allow-in-repo", action="store_true",
                    help="permit an output directory inside the repo working tree")
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out).resolve()
    repo_root = HERE.parent.parent.resolve()
    if repo_root in out_dir.parents and not args.allow_in_repo:
        print(f"refusing to write session-derived cards into the repo: {out_dir}\n"
              f"pick a path outside the working tree, or pass --allow-in-repo.")
        return 2

    if args.endpoint:
        host, _, port = args.endpoint.partition(":")
        dev = Device(host=host, port=int(port or DEFAULT_PORT))
    else:
        forward(args.serial, args.port)
        dev = Device(port=args.port)

    bundle = fetch_bundle(dev, args.remote_dir)
    if not bundle:
        print(f"no cards found in {args.remote_dir}")
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    with tarfile.open(fileobj=io.BytesIO(bundle), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.isfile() or not member.name.endswith(".json"):
                continue
            name = pathlib.Path(member.name).name
            if args.date and args.date not in name:
                continue
            data = tar.extractfile(member).read()
            try:
                json.loads(data.decode("utf-8"))
            except Exception as exc:  # noqa: BLE001
                print(f"skip {name}: not valid JSON ({exc})")
                continue
            (out_dir / name).write_bytes(data)
            written.append((name, len(data), hashlib.sha256(data).hexdigest()[:12]))

    for name, size, digest in sorted(written):
        print(f"{name}  {size:>6}B  sha256:{digest}")
    print(f"pulled {len(written)} card(s) -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
