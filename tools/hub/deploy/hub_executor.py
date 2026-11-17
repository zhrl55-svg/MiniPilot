"""Windows-side hub executor.

The phone's `hub_call` tool talks to a *relay*; every device (phone, PC) is a
WebSocket client of that relay. The relay shipped with only the routing half
(`dev_relay.py`); the client half existed only as Kotlin inside the phone
(`HubClient.kt`). This file is that client for a PC.

Wire contract (nanoMuse Hub 0.31, as pinned in the phone's source):

  connect   ws://<relay>/v1/hub
            Authorization: Bearer <key>
  first     {"type":"hello","device":{id,name,kind,os,version,actions}}
            -> relay answers {"type":"welcome",...} then {"type":"devices",...}
  inbound   {"type":"call","id":...,"from":...,"action":...,"args":{...}}
  answer    {"type":"result","id":<same id>,"ok":true,"body":{...}}
            or      {"type":"result","id":<same id>,"ok":false,"error":"...","message":"..."}
  keepalive {"type":"ping"} -> {"type":"pong"}

Actions declared in `hello` are what the relay advertises to the phone; a
device with an empty action list is "not controllable" and the relay refuses
calls to it. So the list must name every action this process implements.

Config file (JSON), next to this script:

  {
    "relay": "ws://192.168.2.102:8787/v1/hub",
    "api_key": "...",
    "device_id": "pc-asus",
    "device_name": "ASUS",
    "actions": ["info","shell","files","file.get","file.put","screen"],
    "root": "C:\\Users\\zhrl5",
    "shell_timeout_s": 120,
    "file_max": 8388608,
    "out_max": 200000
  }
"""

from __future__ import annotations

import base64
import json
import os
import platform
import socket
import subprocess
import sys
import time
import traceback
from pathlib import Path

try:
    import websocket  # websocket-client
except ImportError:  # pragma: no cover
    print("需要 websocket-client：pip install websocket-client", file=sys.stderr)
    raise

# Windows: children must never allocate a console window, otherwise every
# remote command flashes a terminal on the user desktop.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "hub_device.json"
PROTO_VERSION = "0.1"
ACTIONS = ["info", "shell", "files", "file.get", "file.put", "screen"]

STARTED = time.time()


LOG_PATH = HERE / "hub.log"
LOG_MAX_BYTES = 1_000_000


def log(tag: str, message: str) -> None:
    """Log to hub.log, and to stdout when there is one.

    The task is launched with pythonw.exe so that no console window pops up
    (the user asked for that explicitly). pythonw has no stdout — sys.stdout
    is None — so print() would raise or vanish. The file is the real
    destination; stdout is only a convenience when running by hand.
    """
    line = f"[{time.strftime('%H:%M:%S')}] [{tag}] {message}"
    try:
        if LOG_PATH.exists() and LOG_PATH.stat().st_size > LOG_MAX_BYTES:
            # Keep the tail rather than growing without bound.
            tail = LOG_PATH.read_text(encoding="utf-8", errors="replace")[-200_000:]
            LOG_PATH.write_text(tail, encoding="utf-8")
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except Exception:
        pass
    try:
        if sys.stdout is not None:
            print(line, flush=True)
    except Exception:
        pass


# --------------------------------------------------------------- config


def default_device_id(prefix: str = "pc") -> str:
    host = (socket.gethostname() or "unknown").lower()
    clean = "".join(c if c.isalnum() else "-" for c in host).strip("-")[:24]
    return f"{prefix}-{clean}"


def load_config() -> dict:
    with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
        raw = json.load(fh)
    raw.setdefault("device_id", default_device_id())
    raw.setdefault("device_name", raw["device_id"])
    raw.setdefault("actions", ACTIONS)
    raw.setdefault("root", str(Path.home()))
    raw.setdefault("shell_timeout_s", 120)
    raw.setdefault("file_max", 8 * 1024 * 1024)
    raw.setdefault("out_max", 200_000)
    return raw


# --------------------------------------------------------------- actions


def act_info(cfg: dict, args: dict) -> dict:
    def run(cmd):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=20,
                                 creationflags=NO_WINDOW)
            return out.stdout.strip()
        except Exception as exc:  # noqa: BLE001
            return f"<{type(exc).__name__}>"

    return {
        "os": platform.system(),
        "release": platform.release(),
        "version": platform.version(),
        "machine": platform.machine(),
        "hostname": socket.gethostname(),
        "user": os.environ.get("USERNAME", ""),
        "python": platform.python_version(),
        "cwd": os.getcwd(),
        "uptime_s": int(time.time() - STARTED),
        "shell_program": find_bash() or "cmd.exe",
        "roots": [str(r) for r in allowed_roots(cfg)],
    }


def find_bash():
    """Git Bash on this box.

    `shell=True` on Windows means cmd.exe, where `ls`, `cat`, `grep` and every
    POSIX path form do not exist — the caller has to fight quoting and `&`.
    Git Bash gives the caller the same shell vocabulary it has on the phone.
    """
    for c in (
        r"C:\Users\zhrl5\AppData\Local\hermes\git\bin\bash.exe",
        r"C:\Users\zhrl5\AppData\Local\hermes\git\usr\bin\bash.exe",
        r"C:\Program Files\Git\bin\bash.exe",
        r"C:\Program Files\Git\usr\bin\bash.exe",
        r"C:\Program Files (x86)\Git\bin\bash.exe",
    ):
        if os.path.isfile(c):
            return c
    return None


def act_shell(cfg: dict, args: dict) -> dict:
    command = (args.get("command") or "").strip()
    if not command:
        return {"ok": False, "error": "shell needs args.command"}
    cwd = args.get("cwd") or cfg["root"]
    timeout_ms = int(args.get("timeout_ms") or 0)
    timeout_s = timeout_ms / 1000.0 if timeout_ms else float(cfg["shell_timeout_s"])
    t0 = time.time()
    bash = find_bash()
    if args.get("detach"):
        return _detach_shell(cfg, command, cwd, bash, t0)
    try:
        if bash:
            # Git Bash: POSIX shell. cwd has to be a Windows path it accepts,
            # so the config root is passed through as-is.
            proc = subprocess.run(
                [bash, "-c", command],
                cwd=cwd if os.path.isdir(cwd) else None,
                capture_output=True,
                timeout=timeout_s,
                creationflags=NO_WINDOW,
            )
        else:
            proc = subprocess.run(
                command,
                shell=True,
                cwd=cwd if os.path.isdir(cwd) else None,
                capture_output=True,
                timeout=timeout_s,
                creationflags=NO_WINDOW,
            )
        stdout = proc.stdout.decode("utf-8", "replace")
        stderr = proc.stderr.decode("utf-8", "replace")
        return {
            "stdout": stdout[: cfg["out_max"]],
            "stderr": stderr[: cfg["out_max"]],
            "exit_code": proc.returncode,
            "duration_ms": int((time.time() - t0) * 1000),
            "cwd": cwd,
        }
    except subprocess.TimeoutExpired as exc:
        return {
            "stdout": (exc.stdout or b"").decode("utf-8", "replace")[: cfg["out_max"]],
            "stderr": f"timeout after {timeout_s}s",
            "exit_code": 124,
            "duration_ms": int((time.time() - t0) * 1000),
        }
    except Exception as exc:  # noqa: BLE001
        return {"stdout": "", "stderr": f"{type(exc).__name__}: {exc}", "exit_code": 1,
                "duration_ms": int((time.time() - t0) * 1000)}


def _detach_shell(cfg: dict, command: str, cwd: str, bash, t0: float) -> dict:
    """Start a long-lived process and hand back its pid right away.

    A service started in the foreground inherits the pipes of the shell that
    started it, so `capture_output=True` waits for an EOF that never arrives
    while the service is alive: the call times out although the service is up
    and healthy. A detached child gets no pipes at all, so nothing waits on
    it — the phone gets the pid and can go on to poll the service itself.
    """
    argv = [bash, "-c", command] if bash else command
    flags = (
        getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        | getattr(subprocess, "DETACHED_PROCESS", 0)
    )
    try:
        proc = subprocess.Popen(
            argv,
            cwd=cwd if os.path.isdir(cwd) else None,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
            shell=not bash,
        )
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"detach failed: {type(exc).__name__}: {exc}"}
    log("hub", f"detached pid={proc.pid}: {command!r}")
    return {
        "pid": proc.pid,
        "detached": True,
        "command": command,
        "cwd": cwd,
        "duration_ms": int((time.time() - t0) * 1000),
    }


class PathNotAllowed(Exception):
    """The path is outside every root this executor may touch."""


def allowed_roots(cfg: dict) -> list[Path]:
    """`root` plus the extra roots the config opens."""
    roots = [Path(cfg["root"])]
    for extra in cfg.get("roots") or []:
        roots.append(Path(extra))
    return [Path(os.path.abspath(str(r))) for r in roots]


def _inside(root: Path, candidate: Path) -> bool:
    r = os.path.normcase(os.path.realpath(str(root)))
    c = os.path.normcase(os.path.realpath(str(candidate)))
    return c == r or c.startswith(r + os.sep)


def _resolve(cfg: dict, path: str) -> Path:
    p = Path(path)
    if not p.is_absolute():
        p = Path(cfg["root"]) / p
    p = Path(os.path.abspath(str(p)))
    roots = allowed_roots(cfg)
    if not any(_inside(root, p) for root in roots):
        raise PathNotAllowed(
            f"path is outside this executor's root: {p} "
            f"(allowed: {', '.join(str(r) for r in roots)})"
        )
    return p


def act_files(cfg: dict, args: dict) -> dict:
    path = args.get("path") or "."
    depth = int(args.get("depth") or 1)
    try:
        base = _resolve(cfg, path)
    except PathNotAllowed as exc:
        return {"ok": False, "error": "not_allowed", "message": str(exc)}
    if not base.exists():
        return {"ok": False, "error": f"no such path: {base}"}
    if base.is_file():
        st = base.stat()
        return {"entries": [{"name": base.name, "path": str(base), "size": st.st_size,
                             "is_dir": False, "mtime": int(st.st_mtime)}]}
    entries = []
    try:
        for root, dirs, files in os.walk(base):
            rel = Path(root).relative_to(base)
            if len(rel.parts) > depth:
                dirs[:] = []
                continue
            for name in sorted(files):
                fp = Path(root) / name
                try:
                    st = fp.stat()
                except OSError:
                    continue
                entries.append({
                    "name": name, "path": str(fp), "size": st.st_size,
                    "is_dir": False, "mtime": int(st.st_mtime),
                })
            for name in sorted(dirs):
                entries.append({"name": name, "path": str(Path(root) / name),
                                "size": 0, "is_dir": True, "mtime": 0})
    except PermissionError as exc:
        return {"ok": False, "error": f"permission denied: {exc}"}
    return {"entries": entries, "base": str(base)}


def act_file_get(cfg: dict, args: dict) -> dict:
    path = args.get("path") or ""
    if not path:
        return {"ok": False, "error": "file.get needs args.path"}
    if not path:
        return {"ok": False, "error": "file.get needs args.path"}
    try:
        fp = _resolve(cfg, path)
    except PathNotAllowed as exc:
        return {"ok": False, "error": "not_allowed", "message": str(exc)}
    if not fp.is_file():
        return {"ok": False, "error": f"not a file: {fp}"}
    data = fp.read_bytes()
    if len(data) > int(cfg["file_max"]):
        return {"ok": False, "error": f"file too large: {len(data)} bytes"}
    mime = "application/octet-stream"
    if fp.suffix.lower() in (".txt", ".md", ".log", ".json", ".py", ".kt", ".js", ".html", ".css"):
        mime = "text/plain; charset=utf-8"
    return {"data": base64.b64encode(data).decode("ascii"), "mime": mime,
            "size": len(data), "path": str(fp)}


def act_file_put(cfg: dict, args: dict) -> dict:
    path = args.get("path") or ""
    data = args.get("data") or ""
    if not path:
        return {"ok": False, "error": "file.put needs args.path"}
    try:
        blob = base64.b64decode(data)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"bad base64: {exc}"}
    try:
        fp = _resolve(cfg, path)
    except PathNotAllowed as exc:
        return {"ok": False, "error": "not_allowed", "message": str(exc)}
    try:
        fp.parent.mkdir(parents=True, exist_ok=True)
        fp.write_bytes(blob)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"write failed: {exc}"}
    return {"path": str(fp), "size": len(blob), "written": True}


def act_screen(cfg: dict, args: dict) -> dict:
    """Screenshot via PowerShell; returns base64 PNG."""
    ps = (
        "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
        "$b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds; "
        "$bmp=New-Object System.Drawing.Bitmap $b.Width,$b.Height; "
        "$g=[System.Drawing.Graphics]::FromImage($bmp); "
        "$g.CopyFromScreen($b.Location,[System.Drawing.Point]::Empty,$b.Size); "
        "$ms=New-Object System.IO.MemoryStream; "
        "$bmp.Save($ms,[System.Drawing.Imaging.ImageFormat]::Png); "
        "[Console]::Out.Write([Convert]::ToBase64String($ms.ToArray()))"
    )
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            capture_output=True, timeout=30, creationflags=NO_WINDOW,
        )
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"screenshot failed: {type(exc).__name__}: {exc}"}
    b64 = out.stdout.decode("ascii", "ignore").strip()
    if not b64:
        return {"ok": False, "error": "screenshot produced no data",
                "stderr": out.stderr.decode("utf-8", "replace")[:400]}
    return {"data": b64, "mime": "image/png"}


DISPATCH = {
    "info": act_info,
    "shell": act_shell,
    "files": act_files,
    "file.get": act_file_get,
    "file.put": act_file_put,
    "screen": act_screen,
}


# --------------------------------------------------------------- client


class Executor:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.ws = None

    def hello_frame(self) -> dict:
        return {
            "type": "hello",
            "device": {
                "id": self.cfg["device_id"],
                "name": self.cfg["device_name"],
                "kind": "computer",
                "os": "windows",
                "version": f"minis-hub-executor/{PROTO_VERSION}",
                "actions": list(self.cfg["actions"]),
            },
        }

    def send(self, frame: dict) -> None:
        self.ws.send(json.dumps(frame, ensure_ascii=False))

    def handle(self, frame: dict) -> None:
        kind = frame.get("type")
        if kind == "call":
            self.handle_call(frame)
        elif kind == "ping":
            self.send({"type": "pong"})
        elif kind == "devices":
            names = [d.get("name") for d in frame.get("devices") or []]
            log("hub", f"devices online: {names}")
        elif kind == "welcome":
            log("hub", f"welcome, server={frame.get('server')}")
        elif kind == "error":
            log("hub", f"relay error: {frame.get('code')} {frame.get('message')}")
        else:
            log("hub", f"ignored frame type={kind}")

    def handle_call(self, frame: dict) -> None:
        call_id = str(frame.get("id") or "")
        action = str(frame.get("action") or "")
        args = frame.get("args") or {}
        log("hub", f"call {call_id}: {action}")
        if action == "shell":
            # [H-004] The exact bytes that arrived. A phone that over-escaped
            # its quotes shows up here as '\\"a b\\"' — the repr is what makes
            # the two ends of the wire comparable.
            log("hub", f"cmd_repr: {str(args.get('command') or '')!r}")
        fn = DISPATCH.get(action)
        if fn is None:
            self.send({"type": "result", "id": call_id, "ok": False,
                       "error": "unsupported_action",
                       "message": f"action {action!r} is not implemented"})
            return
        try:
            body = fn(self.cfg, args)
            if isinstance(body, dict) and body.get("ok") is False:
                self.send({"type": "result", "id": call_id, "ok": False,
                           "error": body.get("error", "action_failed"),
                           "message": body.get("message", ""), "body": {}})
                return
            self.send({"type": "result", "id": call_id, "ok": True, "body": body})
        except Exception:  # noqa: BLE001 - a crash must not kill the link
            detail = traceback.format_exc()[-1200:]
            log("hub", f"action {action} crashed:\n{detail}")
            self.send({"type": "result", "id": call_id, "ok": False,
                       "error": "action_crashed", "message": detail[-400:], "body": {}})

    def run(self) -> None:
        relay = self.cfg["relay"]
        key = self.cfg["api_key"]
        delay = 1.0
        while True:
            try:
                log("hub", f"connecting to {relay}")
                self.ws = websocket.create_connection(
                    relay,
                    header=[f"Authorization: Bearer {key}"],
                    timeout=30,
                )
                delay = 1.0
                log("hub", "connected, sending hello")
                self.send(self.hello_frame())
                # Application-level keepalive, done the ONLY way that works:
                # recv() with a timeout.
                #
                # A `if time.time() - last_ping > 25: ping()` placed before a
                # blocking recv() never fires — with no traffic recv() blocks
                # forever, so the ping branch is unreachable. The process then
                # sits alive (a few MB of RSS) while the socket is long dead,
                # which is exactly how this executor kept going "offline" for
                # hours with no error in the log.
                self.ws.settimeout(30)
                while True:
                    try:
                        raw = self.ws.recv()
                    except websocket.WebSocketTimeoutException:
                        # Nothing for 30s: keep the path warm and keep waiting.
                        self.send({"type": "ping"})
                        continue
                    if raw is None or raw == "":
                        raise ConnectionError("closed by peer")
                    if raw is None:
                        continue
                    try:
                        frame = json.loads(raw)
                    except ValueError:
                        log("hub", f"non-JSON frame ignored: {str(raw)[:120]}")
                        continue
                    self.handle(frame)
            except KeyboardInterrupt:
                # Ctrl+C must NOT kill the link.
                #
                # Windows OpenSSH sends Ctrl+C to the process group when the
                # ssh session ends, and a schtasks-launched task shares that
                # console — so a single disconnected shell used to take the
                # executor down for good. It has to come back on its own.
                log("hub", "interrupted (Ctrl+C) — reconnecting")
                time.sleep(delay)
                delay = min(delay * 2, 60.0)
                continue
            except Exception as exc:  # noqa: BLE001
                log("hub", f"link down: {type(exc).__name__}: {exc}")
                time.sleep(delay)
                delay = min(delay * 2, 60.0)


def main() -> int:
    if not CONFIG_PATH.exists():
        print(f"缺少配置文件：{CONFIG_PATH}", file=sys.stderr)
        return 1
    cfg = load_config()
    log("hub", f"device={cfg['device_id']} actions={cfg['actions']} root={cfg['root']}")
    Executor(cfg).run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
