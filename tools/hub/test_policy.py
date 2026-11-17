"""Regression for policy.Gate — approvals must be rare enough to stay read.

The complaint that started this: every step of a multi-step remote task raised
its own approval card, which trains the person to tap "allow" without reading.
The fix is a session grant (one approval covers the same action from the same
device for approval_grant_s), plus a hard rule that destructive shell commands
never ride on a grant.

    python test_policy.py
"""

from __future__ import annotations

import time

import hubkit
import policy

passed: list[str] = []


def ok(name: str) -> None:
    passed.append(name)
    print(f"  ok  {name}")


ACTIONS = ("info", "files", "file.get", "file.put", "shell", "screen", "notify")


def make_cfg(**overrides) -> hubkit.Config:
    pol = {
        "default": "ask",
        "allow_read_only_shell": True,
        "approval_grant_s": 600,
        "actions": {
            "info": "bypass",
            "files": "bypass",
            "file.get": "bypass",
            "shell": "ask",
            "file.put": "ask",
            "screen": "ask",
            "notify": "bypass",
        },
    }
    pol.update(overrides.pop("policy", {}))
    return hubkit.Config(
        relay="",
        api_key="k",
        device_id="pc-test",
        device_name="Test PC",
        actions=ACTIONS,
        policy=pol,
        **overrides,
    )


def expect(gate: policy.Gate, sender: str, action: str, args: dict, mode: str, why: str) -> None:
    got = gate.decide(sender, action, args)
    assert got.mode == mode, f"{why}: expected {mode}, got {got.mode} ({got.reason})"
    ok(why)


def main() -> int:
    gate = policy.Gate(make_cfg())
    phone = "phone-test"

    expect(gate, phone, "shell", {"command": "ls -la"}, policy.BYPASS, "read-only shell bypasses")
    expect(gate, phone, "shell", {"command": "echo hi > out.txt"}, policy.ASK,
           "writing shell asks the first time")
    expect(gate, phone, "shell", {"command": "grep -r x ."}, policy.BYPASS,
           "second read-only command needs no card")
    expect(gate, phone, "info", {}, policy.BYPASS, "info is policy bypass")

    gate.note_approval(phone, "shell")
    expect(gate, phone, "shell", {"command": "echo hi > out.txt"}, policy.BYPASS,
           "same sender + action rides the grant")
    expect(gate, phone, "file.put", {"path": "a.txt", "content": "x"}, policy.ASK,
           "a grant does not cross actions")
    expect(gate, "phone-other", "shell", {"command": "echo hi > out.txt"}, policy.ASK,
           "a grant does not cross devices")
    expect(gate, phone, "shell", {"command": "rm -rf /tmp/hubtest"}, policy.ASK,
           "destructive shell asks even with a live grant")
    expect(gate, phone, "shell", {"command": "rm -f /tmp/hubtest"}, policy.ASK,
           "rm -f also asks every time")
    expect(gate, phone, "shell", {"command": "cmd /c del x.txt"}, policy.ASK,
           "del also asks every time")
    expect(gate, phone, "shell", {"command": "format c:"}, policy.ASK,
           "format also asks every time")

    # Expiry: a grant is a session convenience, not a lasting permission.
    short = policy.Gate(make_cfg(policy={"approval_grant_s": 0.05}))
    short.note_approval(phone, "shell")
    time.sleep(0.12)
    expect(short, phone, "shell", {"command": "echo hi > out.txt"}, policy.ASK,
           "grant expires after approval_grant_s")

    # approval_grant_s = 0 turns grants off entirely.
    off = policy.Gate(make_cfg(policy={"approval_grant_s": 0}))
    off.note_approval(phone, "shell")
    expect(off, phone, "shell", {"command": "echo hi > out.txt"}, policy.ASK,
           "grant_s=0 disables grants")

    # A trusted sender overrides everything, including the destructive check.
    trusted = policy.Gate(make_cfg(policy={"by_sender": {phone: "bypass"}}))
    expect(trusted, phone, "shell", {"command": "rm -rf /tmp/hubtest"}, policy.BYPASS,
           "trusted device bypasses even destructive commands")
    expect(trusted, "phone-other", "shell", {"command": "rm -rf /tmp/hubtest"}, policy.ASK,
           "trust does not leak to other devices")

    # A deny by policy stays a deny, and unknown actions never reach a person.
    deny = policy.Gate(make_cfg(policy={"by_sender": {phone: "deny"}}))
    expect(deny, phone, "shell", {"command": "ls"}, policy.DENY, "by_sender deny is honored")
    expect(gate, phone, "teleport", {}, policy.DENY, "unknown action is denied")

    # The shipped direct config really carries the grant window.
    cfg = hubkit.load_config("config.listen.json")
    assert float(cfg.policy.get("approval_grant_s", 0)) == 600, "config.listen.json lacks approval_grant_s=600"
    ok("config.listen.json sets approval_grant_s=600")

    print(f"\nPASS - {len(passed)} checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
