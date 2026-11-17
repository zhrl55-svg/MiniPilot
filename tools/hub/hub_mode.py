"""Show or change the executor's approval mode (the PC-side switch).

Modes (see policy.py):
  ask    every call asks, read-only included
  smart  read-only shell runs free; one approval covers that action 10 min
  auto   everything except destructive commands runs free

    python hub_mode.py                 # print the effective mode
    python hub_mode.py ask|smart|auto  # persist it; the running executor
                                       # picks it up on the next call

From the phone the same change is `mode.set` — it always raises one approval
card, because widening your own permissions must be a person's decision.
"""

from __future__ import annotations

import sys

import hubkit
import policy

CONFIG = "config.listen.json"


def main(argv: list[str]) -> int:
    cfg = hubkit.load_config(CONFIG)
    gate = policy.Gate(cfg)
    current = gate.current_mode()
    if len(argv) < 2:
        print(current)
        print(f"modes: {', '.join(policy.RUNTIME_MODES)}  file: {gate.mode_file}")
        return 0
    target = argv[1].strip().lower()
    try:
        gate.set_mode(target)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    print(f"{current} -> {target}  ({gate.mode_file})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
