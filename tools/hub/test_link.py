"""Behavioural regression for link.py — the six rules a client depends on.

These are exactly the behaviours the relay used to own; when the endpoint moved
to the PC (executor --listen), they had to move with it. This test proves them
against a real in-process DirectLink and real WebSocket clients, without a
relay, a config file or the executor:

    python test_link.py

Covered:
  1. bad key             -> close 4001
  2. no hello in time    -> close 4000
  3. frame over ceiling  -> error too_large, socket stays open
  4. bad frame           -> error bad_frame, socket stays open
  5. duplicate call id   -> duplicate_call (in flight AND already answered)
  6. app ping            -> pong
  +  welcome always carries a devices array with the endpoint itself.
"""

from __future__ import annotations

import asyncio
import json
import sys

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

import hubkit
import link

KEY = "nm_test"
DEVICE = {
    "id": "pc-test",
    "name": "Test PC",
    "kind": "computer",
    "os": "test",
    "version": "test/0.1",
    "actions": ["info"],
}

passed: list[str] = []


def ok(name: str) -> None:
    passed.append(name)
    print(f"  ok  {name}")


async def recv_until(ws, pred, timeout: float = 5.0):
    """Next frame matching pred; other frames (devices broadcasts) are skipped."""
    while True:
        frame = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if pred(frame):
            return frame


async def expect_error(ws, code: str) -> dict:
    return await recv_until(ws, lambda f: f.get("type") == "error" and f.get("code") == code)


async def expect_close(ws, timeout: float = 5.0) -> tuple[int, str]:
    try:
        await asyncio.wait_for(ws.recv(), timeout)
    except ConnectionClosed as exc:
        rcvd = exc.rcvd
        return (rcvd.code if rcvd else 1006, (rcvd.reason if rcvd else ""))
    return (0, "still open")


async def hello(ws, device_id: str = "phone-test") -> dict:
    await ws.send(
        json.dumps(
            {
                "type": "hello",
                "device": {
                    "id": device_id,
                    "name": "Test Phone",
                    "kind": "phone",
                    "os": "test",
                    "version": "test/0.1",
                    "actions": [],
                },
            }
        )
    )
    return await recv_until(ws, lambda f: f.get("type") == "welcome")


async def main() -> int:
    attached: dict[str, link.LinkedSocket] = {}

    async def handler(frame: dict) -> None:
        call_id = str(frame.get("id") or "")
        await asyncio.sleep(0.05)
        sock = attached.get("sock")
        if sock is not None:
            await sock.send(json.dumps(hubkit.ok(call_id, {"echo": frame.get("action")})))

    direct = link.DirectLink(
        key=KEY,
        device=DEVICE,
        handler=handler,
        on_attach=lambda peer, sock: attached.__setitem__("sock", sock),
        version="test/0.1",
        frame_limit=1024,
    )
    direct.hello_timeout_s = 0.4  # rule 2 without a 15-second test

    async with serve(direct.handler, "127.0.0.1", 0, max_size=link.SERVER_MAX_FRAME) as server:
        port = server.sockets[0].getsockname()[1]
        url = f"ws://127.0.0.1:{port}/v1/hub"
        headers = {"Authorization": f"Bearer {KEY}"}

        # 1 — bad key ------------------------------------------------------
        async with connect(url, additional_headers={"Authorization": "Bearer nope"}, proxy=None) as ws:
            code, reason = await expect_close(ws)
            assert code == 4001, (code, reason)
            ok("bad key -> 4001")

        # 2 — no hello -----------------------------------------------------
        async with connect(url, additional_headers=headers, proxy=None) as ws:
            code, reason = await expect_close(ws)
            assert code == 4000, (code, reason)
            ok("no hello in time -> 4000")

        async with connect(url, additional_headers=headers, proxy=None) as ws:
            welcome = await hello(ws)
            devices = welcome.get("devices") or []
            assert [d.get("id") for d in devices] == ["pc-test"], devices
            assert devices[0].get("controllable") is True, devices
            assert devices[0].get("kind") == "computer", devices
            assert (welcome.get("server") or {}).get("frame_limit") == 1024
            ok("welcome carries a devices array with the endpoint")

            # 6 — ping/pong -------------------------------------------------
            await ws.send(json.dumps({"type": "ping"}))
            await recv_until(ws, lambda f: f.get("type") == "pong")
            ok("ping -> pong")

            # 4 — bad frame keeps the socket open ---------------------------
            await ws.send("this is not json")
            await expect_error(ws, "bad_frame")
            await ws.send(json.dumps({"type": "ping"}))
            await recv_until(ws, lambda f: f.get("type") == "pong")
            ok("bad frame -> error bad_frame, socket stays open")

            # 3 — oversized frame keeps the socket open --------------------
            await ws.send(json.dumps("x" * 2000))
            await expect_error(ws, "too_large")
            await ws.send(json.dumps({"type": "ping"}))
            await recv_until(ws, lambda f: f.get("type") == "pong")
            ok("oversized frame -> error too_large, socket stays open")

            # 5a — duplicate while in flight -------------------------------
            call = {"type": "call", "id": "c1", "to": "pc-test", "action": "info", "args": {}}
            await ws.send(json.dumps(call))
            await ws.send(json.dumps(call))
            await expect_error(ws, "duplicate_call")
            await recv_until(ws, lambda f: f.get("type") == "result" and f.get("id") == "c1")
            ok("duplicate call id in flight -> duplicate_call")

            # 5b — duplicate after the answer ------------------------------
            call2 = dict(call, id="c2")
            await ws.send(json.dumps(call2))
            await recv_until(ws, lambda f: f.get("type") == "result" and f.get("id") == "c2")
            await ws.send(json.dumps(call2))
            await expect_error(ws, "duplicate_call")
            ok("duplicate answered call id -> duplicate_call")

            # a call to somewhere else is refused, not answered locally ---
            await ws.send(json.dumps({"type": "call", "id": "c3", "to": "pc-other", "action": "info", "args": {}}))
            await expect_error(ws, "device_offline")
            ok("call to an unknown device -> device_offline")

    print(f"\nPASS - {len(passed)} checks")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except AssertionError as exc:
        print(f"\nFAIL — {exc}")
        sys.exit(1)
