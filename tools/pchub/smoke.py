#!/usr/bin/env python3
"""Acceptance smoke test for the Ego Bridge (no phone needed).

Runs the same JSON-RPC sequence `minis-mcp-cli` runs — initialize, tools/list,
tools/call — against a running bridge, prints per-call latency, and exits
non-zero on the first failure.

    python ego_mcp.py --port 8788        # in one terminal
    python smoke.py --url http://127.0.0.1:8788/mcp --token-file token.txt
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

# The bridge is on loopback or the tailnet; an HTTP_PROXY in the shell (Clash
# and friends) would otherwise intercept the tailnet hostname and answer 400.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def rpc(url: str, token: str, payload: dict, timeout: float = 180.0) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Authorization", "Bearer " + token)
    with _OPENER.open(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


def call(url: str, token: str, name: str, args: dict) -> tuple[dict, float]:
    t0 = time.time()
    reply = rpc(url, token, {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                             "params": {"name": name, "arguments": args}})
    dt = (time.time() - t0) * 1000
    if "error" in reply:
        raise SystemExit("%s: JSON-RPC error %s" % (name, reply["error"]))
    result = reply.get("result") or {}
    return result, dt


def preview(result: dict, limit: int = 400) -> str:
    text = ""
    for item in result.get("content") or []:
        if item.get("type") == "text":
            text += item.get("text", "")
    return text[:limit].replace("\r", "")


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8788/mcp")
    ap.add_argument("--token", default="")
    ap.add_argument("--token-file", default=str(HERE / "token.txt"))
    ap.add_argument("--url-to-open", default="https://example.com")
    args = ap.parse_args()

    token = args.token.strip()
    if not token:
        token = Path(args.token_file).read_text(encoding="utf-8").strip()

    ok = True

    init, dt = time.time(), 0.0
    reply = rpc(args.url, token, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                  "params": {"protocolVersion": "2025-06-18",
                                             "capabilities": {},
                                             "clientInfo": {"name": "smoke", "version": "1"}}})
    dt = (time.time() - init) * 1000
    print("initialize        %6.0f ms  %s" % (dt, reply.get("result", {}).get("serverInfo")))

    t0 = time.time()
    tools = rpc(args.url, token, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    dt = (time.time() - t0) * 1000
    names = [t["name"] for t in tools.get("result", {}).get("tools", [])]
    print("tools/list        %6.0f ms  %d tools: %s" % (dt, len(names), ", ".join(names)))
    if "browser_open" not in names:
        raise SystemExit("browser_open missing")

    result, dt = call(args.url, token, "browser_open", {"url": args.url_to_open})
    print("browser_open      %6.0f ms  isError=%s" % (dt, result.get("isError")))
    print("  " + preview(result, 300).replace("\n", "\n  "))
    ok = ok and not result.get("isError")

    result, dt = call(args.url, token, "browser_snapshot", {})
    print("browser_snapshot  %6.0f ms  isError=%s" % (dt, result.get("isError")))
    ok = ok and not result.get("isError")

    result, dt = call(args.url, token, "browser_tabs", {})
    print("browser_tabs      %6.0f ms  isError=%s" % (dt, result.get("isError")))
    print("  " + preview(result, 300).replace("\n", "\n  "))
    ok = ok and not result.get("isError")

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
