#!/usr/bin/env python3
"""Emit device → Claude messages as one ASCII-safe JSON line per finding.

Two channels:
  mailbox  — local JSONL appended by the phone/human on the PC
  alert    — pulled from the phone over debug RPC. MiniPilot writes alerts to
             /var/minis/shared/self-improve/alerts.jsonl on the device; the
             mounted repo is PC→phone only (a snapshot), so nothing can flow
             back through a shared file. This watcher polls the device every
             30 s, mirrors new lines into minis-inbox/alerts.jsonl on the PC,
             and emits them with a `_channel":"alert"` marker.

Windows console code pages mangle raw CJK, so every emitted line is
re-encoded with ensure_ascii=True. Only lines ending in \\n are consumed;
a device may append a large JSON line in chunks.
"""
import json
import os
import sys
import time
import urllib.request

# The canonical implementation lives in the repo (tools/probe/); this file
# is the local operator entry point. BASE points at .claude/bridge/ so the
# mailbox file, cursors and the PC-side alert mirror keep their old paths.
BASE = os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".claude", "bridge")
)
MAILBOX = os.path.join(BASE, "minis-to-claude.jsonl")
MAILBOX_CURSOR = os.path.join(BASE, ".claude-cursor")

ALERT_LOCAL = os.path.abspath(
    os.path.join(BASE, "..", "..", "minis-inbox", "alerts.jsonl")
)
ALERT_CURSOR = os.path.join(BASE, ".self-improve-cursor")
DEVICE_ALERT = "/var/minis/shared/self-improve/alerts.jsonl"
RPC_URL = "http://127.0.0.1:5321"
POLL_S = 30

_rpc_id = 0


def rpc(method, params=None, timeout=30):
    global _rpc_id
    _rpc_id += 1
    body = json.dumps(
        {"jsonrpc": "2.0", "id": _rpc_id, "method": method, "params": params or {}},
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        RPC_URL, data=body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except (OSError, ValueError) as exc:
        return None, str(exc)
    if "error" in payload:
        return None, str(payload["error"])
    return payload.get("result") or {}, None


def complete_line_count(path):
    try:
        with open(path, "rb") as f:
            return f.read().count(b"\n")
    except OSError:
        return 0


def read_cursor(path):
    try:
        with open(path, encoding="utf-8") as c:
            return int(c.read().strip() or 0)
    except (OSError, ValueError):
        return 0


def write_cursor(path, n):
    try:
        with open(path, "w", encoding="utf-8") as c:
            c.write(str(n))
    except OSError:
        pass


def emit(payload, channel):
    payload.setdefault("_channel", channel)
    print(json.dumps(payload, ensure_ascii=True), flush=True)


def tail_mailbox():
    """One pass over newly appended complete lines in the local mailbox."""
    with open(MAILBOX, "a", encoding="utf-8"):
        pass
    last = read_cursor(MAILBOX_CURSOR)
    total = complete_line_count(MAILBOX)
    if last > total:
        last = 0
        write_cursor(MAILBOX_CURSOR, 0)
    with open(MAILBOX, "r", encoding="utf-8", errors="replace") as f:
        for _ in range(last):
            f.readline()
        while True:
            pos = f.tell()
            line = f.readline()
            if not line:
                break
            if not line.endswith("\n"):
                f.seek(pos)
                break
            last += 1
            write_cursor(MAILBOX_CURSOR, last)
            stripped = line.strip()
            if not stripped:
                continue
            try:
                emit(json.loads(stripped), "mailbox")
            except ValueError:
                emit({"raw": stripped}, "mailbox")


def pull_device_alerts():
    """Mirror new device alert lines into the local JSONL, then emit them."""
    parent = os.path.dirname(ALERT_LOCAL)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    with open(ALERT_LOCAL, "a", encoding="utf-8"):
        pass

    result, err = rpc("debug.shellExecute", {
        "command": f"cat {DEVICE_ALERT} 2>/dev/null", "timeout": 30,
    })
    if err or result is None:
        print(f"alert pull unavailable: {err}", file=sys.stderr, flush=True)
        return
    if result.get("exit_code") not in (0, None):
        return  # file does not exist yet — normal before the first alert
    text = result.get("output") or ""
    lines = [line for line in text.splitlines() if line.strip()]
    last = read_cursor(ALERT_CURSOR)
    if last > len(lines):
        last = 0
        write_cursor(ALERT_CURSOR, 0)
    for line in lines[last:]:
        last += 1
        try:
            payload = json.loads(line)
        except ValueError:
            payload = {"raw": line}
        with open(ALERT_LOCAL, "a", encoding="utf-8") as out:
            out.write(json.dumps(payload, ensure_ascii=False) + "\n")
        write_cursor(ALERT_CURSOR, last)
        emit(payload, "alert")


def main():
    tail_mailbox()
    pull_device_alerts()
    print("bridge listening (mailbox + device alerts)", file=sys.stderr, flush=True)
    while True:
        time.sleep(POLL_S)
        try:
            tail_mailbox()
            pull_device_alerts()
        except Exception as exc:  # noqa: BLE001 - stay alive across transient RPC failures
            print(f"bridge poll failed: {exc}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
