"""A minimal hub relay for local development — the router mode of link.py.

It implements the routing half of nanoMuse Hub 0.31 — devices, call, result,
event (both the call-scoped and the account-level push) — so the executor can
be exercised end to end without the real cloud. Everything a client depends on
before routing (auth, hello/welcome, frame limits, ping/pong, duplicate call
ids) lives in [link.Link]; this file only decides where a call goes.

**Dev only.** One process, memory only, no persistence, one static key. In
production this is `cloud/nanomuse_cloud/hub.py` (or a hardened rewrite); the
frame shapes are identical, so nothing on the executor side changes.

    python dev_relay.py --port 8787 --key nm_dev --key nm_phone
"""

from __future__ import annotations

import argparse
import asyncio
import time
from typing import Any

from websockets.asyncio.server import serve

import hubkit
import link
from hubkit import brief
from link import Link, PendingCall, Peer

TAG = "relay"


class Relay(Link):
    def __init__(self, keys: list[str], frame_limit: int):
        super().__init__(keys, frame_limit, tag=TAG, version=f"dev-relay/{hubkit.PROTO_VERSION}")

    # ------------------------------------------------------------------ calls
    async def route_call(self, peer: Peer, frame: dict[str, Any], call_id: str) -> None:
        to = str(frame.get("to") or "")
        action = str(frame.get("action") or "")
        args = frame.get("args") or {}

        if to == peer.device_id:
            self._error(peer.ws, "self_call", "cannot call yourself", call_id)
            return
        target = self.peers.get(to)
        if target is None:
            self._error(peer.ws, "device_offline", f"{to} is not connected", call_id)
            return
        if target.kind == "web":
            self._error(peer.ws, "not_controllable", "a web device is never a target", call_id)
            return
        if not target.controllable:
            self._error(peer.ws, "not_controllable", f"{target.name} has remote control off", call_id)
            return

        self._begin_call(peer, frame, call_id, to)
        self.log.info("call %s: %s -> %s %s", call_id, peer.name, target.name, brief(args, 120))
        await self._send(
            target.ws,
            {
                "type": "call",
                "id": call_id,
                "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                "action": action,
                "args": args,
            },
        )

    async def on_result(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        record = self._finish_call(call_id)
        if record is None:
            self._error(peer.ws, "unknown_call", f"no pending call {call_id}", call_id)
            return
        caller = self.peers.get(record.caller.device_id)
        if caller is None:
            return
        out = dict(frame)
        out["from"] = {"id": peer.device_id, "name": peer.name, "kind": peer.kind}
        if not frame.get("ok"):
            self.log.info("call %s failed: %s", call_id, frame.get("error"))
        await self._send(caller.ws, out)

    async def on_working(self, peer: Peer, frame: dict[str, Any]) -> None:
        """[T-hub-working] Forward a progress frame to the caller of the call.

        The relay never interprets a frame, but it does decide where it goes:
        a `working` frame belongs to the call it names, so it goes to the
        peer that made that call — the same peer the result will go to.
        """
        call_id = str(frame.get("id") or "")
        record = self.pending.get(call_id)
        if record is None:
            self.log.debug("working for an unknown call %s", call_id)
            return
        caller = self.peers.get(record.caller.device_id)
        if caller is None:
            return
        out = dict(frame)
        out["from"] = {"id": peer.device_id, "name": peer.name, "kind": peer.kind}
        await self._send(caller.ws, out)

    async def on_event(self, peer: Peer, frame: dict[str, Any], raw: str | bytes) -> None:
        call_id = frame.get("id")
        body = frame.get("body") or {}
        if call_id:
            record = self.pending.get(str(call_id))
            if record is None:
                self._error(peer.ws, "unknown_call", f"no pending call {call_id}", str(call_id))
                return
            other = record.target_id if peer.device_id == record.caller.device_id else record.caller.device_id
            target = self.peers.get(other)
            if target is None:
                return
            await self._send(
                target.ws,
                {
                    "type": "event",
                    "id": str(call_id),
                    "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                    "body": body,
                },
            )
            return

        # account-level push (relay 0.31): no id, only approval stages, rate limited
        stage = body.get("stage")
        if stage not in link.PUSH_STAGES:
            self.log.debug("dropping push with stage %r", stage)
            return
        size = len(raw) if isinstance(raw, (bytes, str)) else 0
        if size > link.PUSH_BODY_MAX:
            self._error(peer.ws, "too_large", f"push bodies are capped at {link.PUSH_BODY_MAX} bytes")
            return
        now = time.time()
        while peer.pushes and now - peer.pushes[0] > 60:
            peer.pushes.popleft()
        if len(peer.pushes) >= link.PUSH_PER_MINUTE:
            self._error(peer.ws, "rate_limited", "30 pushes a minute")
            return
        peer.pushes.append(now)

        to = str(frame.get("to") or "*")
        targets = [p for p in self.peers.values() if to == "*" or p.device_id == to]
        for target in targets:
            if target.device_id == peer.device_id:
                continue
            await self._send(
                target.ws,
                {
                    "type": "event",
                    "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                    "body": body,
                },
            )


async def main() -> int:
    parser = argparse.ArgumentParser(description="Minis hub dev relay (router mode)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--key", action="append", dest="keys", default=[], help="repeatable: one key per device")
    parser.add_argument("--frame-limit", type=int, default=link.DEFAULT_FRAME_LIMIT)
    args = parser.parse_args()
    if not args.keys:
        args.keys = ["nm_dev"]

    relay = Relay(args.keys, args.frame_limit)
    async with serve(relay.handler, args.host, args.port, max_size=link.SERVER_MAX_FRAME):
        relay.log.info("listening on ws://%s:%d/v1/hub (keys=%s)", args.host, args.port, args.keys)
        await asyncio.Future()


if __name__ == "__main__":
    import sys

    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
