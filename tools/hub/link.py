"""Server side of nanoMuse Hub 0.31 — the half every endpoint speaks.

`hubkit` is the frame format; this module is the *connection* half: what a
WebSocket peer must prove and what the endpoint promises before a single call
is routed. The cloud relay and a listening executor need exactly the same
rules, so they share one implementation:

  RelayLink   the dev relay (dev_relay.py): one socket per device, calls are
              routed between peers.
  DirectLink  the executor listening (executor.py --listen): the endpoint IS
              the device, so a `call` is answered by a local handler and the
              result goes back out on the same socket.

Six behaviours are contract, not taste (a client is built around each):

  1. bearer auth — a bad key is closed with 4001 (C1.5: the phone stops
     retrying hard on 4001/4002);
  2. the first frame within 15 s must be `hello`, else 4000 (C1.2);
  3. a frame over the ceiling is answered `too_large` and dropped — the link
     never dies on a big frame (C8);
  4. anything that is not a JSON object is answered `error bad_frame` and the
     socket stays OPEN (C2.1);
  5. a call id that is in flight or was already answered is `duplicate_call`
     (C7 idempotency);
  6. application-level `ping` gets `pong` (C1.4) — the phone drops a link
     that is silent for 50 s, and the relay's answer is the only traffic in
     an idle minute.

Plus one structural rule: `welcome` always carries a `devices` array. The
phone builds `hub_call`'s target list from it (Hub.kt `TYPE_WELCOME`), and an
empty array means the model cannot address anyone.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from websockets.exceptions import ConnectionClosed

import hubkit
from hubkit import setup_logging

#: C1.2 — hello must be the first frame within this many seconds.
HELLO_TIMEOUT_S = 15.0
#: C6/C7 — an unanswered call fails after 15 minutes.
CALL_TIMEOUT_S = 900.0
#: How many answered call ids stay known for duplicate detection.
DONE_MEMORY = 2000
#: C8 — per-peer budget.
FRAMES_PER_SECOND = 60
BYTES_PER_SECOND = 8 * 1024 * 1024
#: C4.3 — account-level pushes (approval stages only).
PUSH_PER_MINUTE = 30
PUSH_BODY_MAX = 16 * 1024
PUSH_STAGES = {"approval", "approval_result"}
#: C8 — the protocol's frame ceiling, advertised in welcome.server.
DEFAULT_FRAME_LIMIT = 16 * 1024 * 1024
#: The websocket library's own limit sits ABOVE the protocol ceiling, so an
#: oversized frame still reaches _parse and gets a protocol error instead of
#: being dropped (or killing the connection) inside the library.
SERVER_MAX_FRAME = 64 * 1024 * 1024


@dataclass
class Peer:
    """One connected device, identified by its hello."""

    ws: Any
    key: str
    device_id: str = ""
    name: str = ""
    kind: str = ""
    info: dict[str, Any] = field(default_factory=dict)
    online: bool = True
    last_seen: float = field(default_factory=time.time)
    pushes: deque = field(default_factory=deque)
    window_start: float = 0.0
    window_frames: int = 0
    window_bytes: int = 0
    warned: bool = False
    #: Direct mode only: the wrapper the local handler sends through.
    socket: Any = None

    @property
    def controllable(self) -> bool:
        """`hello` has no switch for this, so a device with no actions is off."""
        return self.kind != "web" and bool(self.info.get("actions"))

    def describe(self) -> dict[str, Any]:
        return {
            "id": self.device_id,
            "name": self.name,
            "kind": self.kind,
            "os": self.info.get("os", ""),
            "version": self.info.get("version", ""),
            "actions": self.info.get("actions", []),
            "online": self.online,
            "last_seen": int(self.last_seen),
            "controllable": self.controllable,
            "ip": "",
        }


@dataclass
class PendingCall:
    call_id: str
    caller: Peer
    target_id: str
    action: str
    timer: asyncio.Task | None = None


class Link:
    """The WebSocket server half: handshake, frame hygiene, call bookkeeping.

    Subclasses supply the routing: `route_call`, `on_result`, `on_event`, and
    (optionally) what `describe_devices` returns.
    """

    def __init__(
        self,
        keys: list[str] | set[str],
        frame_limit: int = DEFAULT_FRAME_LIMIT,
        *,
        tag: str = "link",
        version: str | None = None,
        features: tuple[str, ...] = ("event_push",),
    ):
        self.keys = set(keys)
        self.frame_limit = int(frame_limit)
        self.version = version or f"minis-link/{hubkit.PROTO_VERSION}"
        self.features = list(features)
        self.log = setup_logging(tag)
        self.hello_timeout_s = HELLO_TIMEOUT_S
        self.peers: dict[str, Peer] = {}
        self.by_ws: dict[int, Peer] = {}
        self.pending: dict[str, PendingCall] = {}
        self.done: deque[str] = deque(maxlen=DONE_MEMORY)
        #: One key owns one device id (contract C1.3); a different id is 4002.
        self.owner: dict[str, str] = {}

    # ── frames ─────────────────────────────────────────────────────────────

    async def _send(self, ws: Any, frame: dict[str, Any]) -> None:
        try:
            await ws.send(json.dumps(frame, ensure_ascii=False))
        except (ConnectionClosed, RuntimeError):
            pass

    def _error(self, ws: Any, code: str, message: str, call_id: str | None = None) -> None:
        frame: dict[str, Any] = {"type": "error", "code": code, "message": message}
        if call_id:
            frame["id"] = call_id
        asyncio.create_task(self._send(ws, frame))

    def describe_devices(self) -> list[dict[str, Any]]:
        return [p.describe() for p in self.peers.values()]

    def devices_frame(self) -> dict[str, Any]:
        return {"type": "devices", "devices": self.describe_devices()}

    async def _broadcast_devices(self) -> None:
        frame = self.devices_frame()
        for peer in list(self.peers.values()):
            await self._send(peer.ws, frame)

    def server_info(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "frame_limit": self.frame_limit,
            "time": int(time.time()),
            "features": self.features,
        }

    # ── lifecycle ──────────────────────────────────────────────────────────

    async def handler(self, ws: Any) -> None:
        """websockets' per-connection entry point."""
        header = ""
        request = getattr(ws, "request", None)
        if request is not None:
            header = request.headers.get("Authorization", "")
        key = header[7:].strip() if header.lower().startswith("bearer ") else ""

        if key not in self.keys:
            self.log.warning("rejecting socket: bad key %r", hubkit.brief(key, 40))
            await ws.close(4001, "bad_key")
            return

        peer = Peer(ws=ws, key=key)
        self.by_ws[id(ws)] = peer
        try:
            if not await self._handshake(peer):
                return
            try:
                async for raw in ws:
                    await self._on_frame(peer, raw)
            except ConnectionClosed:
                pass
        finally:
            peer.online = False
            peer.last_seen = time.time()
            if self.peers.get(peer.device_id) is peer:
                self.peers.pop(peer.device_id, None)
            self.by_ws.pop(id(ws), None)
            self.detach(peer)
            if peer.device_id:
                self.log.info("%s left", peer.name or peer.device_id)
            await self._broadcast_devices()

    async def _handshake(self, peer: Peer) -> bool:
        ws = peer.ws
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=self.hello_timeout_s)
        except asyncio.TimeoutError:
            self.log.warning("closing: no hello within %.1fs", self.hello_timeout_s)
            await ws.close(4000, "hello expected")
            return False
        except ConnectionClosed:
            return False

        frame = self._parse(ws, raw)
        if frame is None or frame.get("type") != "hello":
            await ws.close(4000, "hello expected")
            return False

        device = frame.get("device") or {}
        device_id = str(device.get("id") or "")
        if not device_id:
            await ws.close(4002, "bad_device")
            return False

        bound = self.owner.get(peer.key)
        if bound and bound != device_id:
            self.log.warning("key already owns %s, refusing %s", bound, device_id)
            await ws.close(4002, "bad_device")
            return False
        self.owner[peer.key] = device_id

        previous = self.peers.get(device_id)
        if previous is not None and previous.ws is not ws:
            self.log.info("device %s reconnected; closing the old socket", device_id)
            await previous.ws.close(4003, "replaced")

        peer.device_id = device_id
        peer.name = str(device.get("name") or device_id)
        peer.kind = str(device.get("kind") or "computer")
        peer.info = dict(device)
        self.peers[device_id] = peer

        welcome = {
            "type": "welcome",
            "device_id": device_id,
            "devices": self.describe_devices(),
            "server": self.server_info(),
        }
        await self._send(ws, welcome)
        self.attach(peer)
        await self._broadcast_devices()
        self.log.info("hello from %s (%s) actions=%s", peer.name, device_id, peer.info.get("actions"))
        return True

    def attach(self, peer: Peer) -> None:
        """The peer is welcomed; direct mode binds the executor's send path."""

    def detach(self, peer: Peer) -> None:
        """The peer is gone; direct mode releases the executor's send path."""

    # ── inbound frames ─────────────────────────────────────────────────────

    def _parse(self, ws: Any, raw: str | bytes) -> dict[str, Any] | None:
        if isinstance(raw, bytes):
            self._error(ws, "bad_frame", "binary frames are not part of the protocol")
            return None
        if len(raw) > self.frame_limit:
            self._error(ws, "too_large", f"frame over {self.frame_limit} bytes")
            return None
        try:
            frame = json.loads(raw)
        except ValueError:
            self._error(ws, "bad_frame", "not JSON")
            return None
        if not isinstance(frame, dict):
            self._error(ws, "bad_frame", "not a frame object")
            return None
        return frame

    def _rate_ok(self, peer: Peer, size: int, ws: Any) -> bool:
        now = time.time()
        if now - peer.window_start >= 1.0:
            peer.window_start = now
            peer.window_frames = 0
            peer.window_bytes = 0
            peer.warned = False
        peer.window_frames += 1
        peer.window_bytes += size
        if peer.window_frames > FRAMES_PER_SECOND or peer.window_bytes > BYTES_PER_SECOND:
            if not peer.warned:
                peer.warned = True
                self._error(ws, "rate_limited", "slow down: 60 frames or 8MB a second")
            return False
        return True

    async def _on_frame(self, peer: Peer, raw: str | bytes) -> None:
        if not self._rate_ok(peer, len(raw), peer.ws):
            return
        frame = self._parse(peer.ws, raw)
        if frame is None:
            return
        peer.last_seen = time.time()
        kind = frame.get("type")

        if kind == "call":
            await self._on_call(peer, frame)
        elif kind == "result":
            await self.on_result(peer, frame)
        elif kind == "event":
            await self.on_event(peer, frame, raw)
        elif kind == "working":
            await self.on_working(peer, frame)
        elif kind == "devices":
            await self._send(peer.ws, self.devices_frame())
        elif kind == "rename":
            peer.name = str(frame.get("name") or peer.name)
            await self._broadcast_devices()
        elif kind == "forget":
            await self.on_forget(peer, str(frame.get("device_id") or ""))
        elif kind == "ping":
            await self._send(peer.ws, {"type": "pong"})
        elif kind == "hello":
            self._error(peer.ws, "bad_frame", "hello only once per connection")
        elif kind in ("pong", "profile", "sync", "stop", "devices_ack"):
            pass
        else:
            self._error(peer.ws, "bad_frame", f"unknown frame type {kind!r}")

    # ── calls: the bookkeeping half ────────────────────────────────────────

    def _known_call(self, call_id: str) -> bool:
        return call_id in self.pending or any(entry == call_id for entry in self.done)

    async def _on_call(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        if not call_id:
            self._error(peer.ws, "bad_frame", "call without id")
            return
        if self._known_call(call_id):
            self._error(peer.ws, "duplicate_call", f"call {call_id} is already known", call_id)
            return
        await self.route_call(peer, frame, call_id)

    def _begin_call(self, peer: Peer, frame: dict[str, Any], call_id: str, target_id: str) -> PendingCall:
        record = PendingCall(
            call_id=call_id,
            caller=peer,
            target_id=target_id,
            action=str(frame.get("action") or ""),
        )
        record.timer = asyncio.create_task(self._expire(record))
        self.pending[call_id] = record
        return record

    def _finish_call(self, call_id: str) -> PendingCall | None:
        record = self.pending.pop(call_id, None)
        if record is not None:
            if record.timer is not None:
                record.timer.cancel()
            self.done.append(call_id)
        return record

    async def _expire(self, record: PendingCall) -> None:
        try:
            await asyncio.sleep(CALL_TIMEOUT_S)
        except asyncio.CancelledError:
            return
        if self.pending.pop(record.call_id, None) is not None:
            self.done.append(record.call_id)
            self.log.info("call %s timed out", record.call_id)
            self._error(record.caller.ws, "timeout", "nobody answered within 15 minutes", record.call_id)

    # ── subclass hooks ─────────────────────────────────────────────────────

    async def route_call(self, peer: Peer, frame: dict[str, Any], call_id: str) -> None:
        """Where does this call go? The relay routes, the direct link answers."""
        raise NotImplementedError

    async def on_result(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        self._error(peer.ws, "unknown_call", f"no pending call {call_id}", call_id)

    async def on_event(self, peer: Peer, frame: dict[str, Any], raw: str | bytes) -> None:
        self.log.debug("dropping inbound %s event from %s", (frame.get("body") or {}).get("stage"), peer.name)

    async def on_working(self, peer: Peer, frame: dict[str, Any]) -> None:
        """[T-hub-working] Where does a progress frame go?

        The direct endpoint never routes it: the executor writes it straight
        onto the phone's socket, because the phone and the executor share one
        connection. A relay has to decide, and the answer is the same as for
        the result that follows — the peer that made the call. The relay in
        this repo does that in dev_relay.Relay.on_working; the cloud relay
        needs the same routing, or the frame is dropped there.
        """
        self.log.debug("dropping inbound working frame from %s", peer.name)

    async def on_forget(self, peer: Peer, device_id: str) -> None:
        if device_id in self.peers:
            self._error(peer.ws, "device_online", f"{device_id} is connected right now")
            return
        await self._broadcast_devices()


class DirectLink(Link):
    """The executor itself listening: the endpoint is the device.

    The phone's `call` is handed to [handler] (the executor's `_handle_call`)
    on a task, so a five-minute shell command never blocks the read loop. The
    executor answers through the socket passed to `on_attach`; every outgoing
    `result` is seen here first, which is how in-flight calls are cleared and
    answered ids remembered without the executor knowing about link state.
    """

    def __init__(
        self,
        key: str,
        device: dict[str, Any],
        handler: Callable[[dict[str, Any]], Awaitable[None]],
        *,
        on_attach: Callable[[Peer, Any], None] | None = None,
        on_detach: Callable[[Peer, Any], None] | None = None,
        tag: str = "link",
        version: str | None = None,
        frame_limit: int = DEFAULT_FRAME_LIMIT,
    ):
        super().__init__([key], frame_limit, tag=tag, version=version)
        self.device = dict(device)
        self.call_handler = handler
        self.on_attach = on_attach
        self.on_detach = on_detach

    def describe_devices(self) -> list[dict[str, Any]]:
        """The one device this endpoint is — never the caller."""
        d = self.device
        actions = list(d.get("actions") or [])
        return [
            {
                "id": str(d.get("id") or ""),
                "name": str(d.get("name") or d.get("id") or ""),
                "kind": str(d.get("kind") or "computer"),
                "os": str(d.get("os") or ""),
                "version": str(d.get("version") or ""),
                "actions": actions,
                "online": True,
                "last_seen": int(time.time()),
                "controllable": d.get("kind") != "web" and bool(actions),
                "ip": "",
            }
        ]

    async def route_call(self, peer: Peer, frame: dict[str, Any], call_id: str) -> None:
        target = str(self.device.get("id") or "")
        to = str(frame.get("to") or "")
        if to != target:
            self._error(peer.ws, "device_offline", f"{to or '(no target)'} is not connected here", call_id)
            return
        if not (self.device.get("actions") or []):
            self._error(peer.ws, "not_controllable", "remote control is off on this device", call_id)
            return

        self._begin_call(peer, frame, call_id, target)
        forwarded = dict(frame)
        forwarded["from"] = {"id": peer.device_id, "name": peer.name, "kind": peer.kind}
        asyncio.create_task(self._deliver(peer, forwarded))

    async def _deliver(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        try:
            await self.call_handler(frame)
        except Exception as exc:  # noqa: BLE001 - never leave a caller hanging
            self.log.exception("call handler crashed (id=%s)", call_id)
            self._finish_call(call_id)
            await self._send(peer.ws, hubkit.fail(call_id, "not_allowed", f"executor error: {exc}"))

    def attach(self, peer: Peer) -> None:
        sock = LinkedSocket(self, peer)
        peer.socket = sock
        if self.on_attach is not None:
            self.on_attach(peer, sock)

    def detach(self, peer: Peer) -> None:
        for call_id, record in list(self.pending.items()):
            if record.caller is peer:
                self._finish_call(call_id)
        sock = peer.socket
        peer.socket = None
        if sock is not None and self.on_detach is not None:
            self.on_detach(peer, sock)


class LinkedSocket:
    """What the executor calls `ws` in direct mode.

    Only `send` is needed by `Executor._send`; the wrapper exists so link sees
    every outgoing frame: a `result` means the call is answered, which clears
    pending and feeds duplicate detection. Everything else passes through.
    """

    def __init__(self, link: DirectLink, peer: Peer):
        self.link = link
        self.peer = peer

    async def send(self, data: str) -> None:
        frame: Any = None
        try:
            frame = json.loads(data)
        except (TypeError, ValueError):
            pass
        if isinstance(frame, dict) and frame.get("type") == "result":
            call_id = str(frame.get("id") or "")
            if call_id:
                self.link._finish_call(call_id)
        await self.peer.ws.send(data)
