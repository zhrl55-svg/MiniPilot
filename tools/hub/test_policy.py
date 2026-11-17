"""Regression for policy.Gate — approvals must be rare enough to stay read.

The complaint that started this: every step of a multi-step remote task raised
its own approval card, which trains the person to tap "allow" without reading.
The fix is a session grant (one approval covers the same action from the same
device for approval_grant_s), plus a hard rule that destructive shell commands
never ride on a grant.

    python test_policy.py
"""

from __future__ import annotations

import os
import tempfile
import time

import hubkit
import policy

passed: list[str] = []
_mode_dir = tempfile.mkdtemp(prefix="hub-mode-")
_mode_seq = 0


def mode_file() -> str:
    global _mode_seq
    _mode_seq += 1
    return os.path.join(_mode_dir, f"mode-{_mode_seq}.txt")


def ok(name: str) -> None:
    passed.append(name)
    print(f"  ok  {name}")


ACTIONS = ("info", "files", "file.get", "file.put", "shell", "screen", "notify", "mode.set")


def make_cfg(**overrides) -> hubkit.Config:
    pol = {
        "default": "ask",
        "mode": "smart",
        "mode_file": mode_file(),
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

    # ---- approval modes, Claude Code style -----------------------------
    ask = policy.Gate(make_cfg(policy={"mode": "ask"}))
    expect(ask, phone, "shell", {"command": "ls -la"}, policy.ASK,
           "ask mode asks even for read-only commands")
    expect(ask, phone, "info", {}, policy.BYPASS, "ask mode still honors explicit bypass actions")
    ask.note_approval(phone, "shell")
    expect(ask, phone, "shell", {"command": "echo hi > out.txt"}, policy.ASK,
           "ask mode ignores grants")

    auto = policy.Gate(make_cfg(policy={"mode": "auto"}))
    expect(auto, phone, "shell", {"command": "echo hi > out.txt"}, policy.BYPASS,
           "auto mode runs non-destructive shell without a card")
    expect(auto, phone, "file.put", {"path": "a.txt", "content": "x"}, policy.BYPASS,
           "auto mode runs file.put without a card")
    expect(auto, phone, "shell", {"command": "rm -rf /tmp/hubtest"}, policy.ASK,
           "auto mode still asks for destructive commands")
    expect(auto, phone, "shell", {"command": "shutdown /s"}, policy.ASK,
           "auto mode still asks for shutdown")

    # Changing the mode is meta and always goes to a person.
    for label, g in (("auto", auto), ("smart", gate)):
        expect(g, phone, "mode.set", {"mode": "auto"}, policy.ASK,
               f"mode.set always asks ({label} mode)")
    auto.note_approval(phone, "mode.set")
    expect(auto, phone, "mode.set", {"mode": "auto"}, policy.ASK,
           "a grant never covers mode.set")
    trusted_mode = policy.Gate(make_cfg(policy={"by_sender": {phone: "bypass"}}))
    expect(trusted_mode, phone, "mode.set", {"mode": "auto"}, policy.BYPASS,
           "an explicitly trusted device may set the mode")
    denied_mode = policy.Gate(make_cfg(policy={"by_sender": {phone: "deny"}}))
    expect(denied_mode, phone, "mode.set", {"mode": "auto"}, policy.DENY,
           "a denied device cannot set the mode")

    # The mode is persisted and hot-read, like Claude Code's shift+tab.
    path = mode_file()
    first = policy.Gate(make_cfg(policy={"mode": "smart", "mode_file": path}))
    assert first.current_mode() == "smart"
    first.set_mode("auto")
    second = policy.Gate(make_cfg(policy={"mode": "smart", "mode_file": path}))
    assert second.current_mode() == "auto", "mode file must survive a fresh Gate"
    ok("mode persists across restarts")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("nonsense\n")
    assert second.current_mode() == "smart", "unknown mode file content must fall back"
    ok("invalid mode file falls back to policy.mode")
    try:
        first.set_mode("trust")
    except ValueError:
        ok("runtime modes exclude full trust (by_sender only)")
    else:
        raise AssertionError("set_mode accepted a mode outside RUNTIME_MODES")

    # The shipped direct config really carries the grant window and the action.
    cfg = hubkit.load_config("config.listen.json")
    assert float(cfg.policy.get("approval_grant_s", 0)) == 600, "config.listen.json lacks approval_grant_s=600"
    assert str(cfg.policy.get("mode", "")) == "smart", "config.listen.json should start in smart mode"
    assert "mode.set" in cfg.actions, "config.listen.json must expose mode.set"
    ok("config.listen.json sets approval_grant_s=600, mode=smart, mode.set")

    print(f"\nPASS - {len(passed)} checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
