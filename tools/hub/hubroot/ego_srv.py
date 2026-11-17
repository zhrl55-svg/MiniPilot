"""ego lite 的常驻包装服务 —— 手机通过 HTTP 触发一次浏览器任务。

    POST /q   {"url": "...", "js": "...", "wait_ms": 0, "timeout": 120}
              -> {"exit": 0, "ms": 4123, "out": "..."}
    GET  /health
              -> {"ok": true, "ego": "C:\\...\\ego-browser.exe", "version": "0.6.3.17"}

为什么是常驻服务：手机端一次浏览器任务要 4–7 秒（每次 spawn 一个 ego
进程），HTTP 是最短的路。ego 的 node 运行时不能常驻（实测 listen 后本机
连不上），所以「常驻」的是这层包装，不是浏览器本身。

安全（2026-11-17 硬化）：

  * 必须带 `X-Minis-Token` 请求头，token 在启动时生成并写进 `ego_srv.json`
    （该文件不入库）；token 不匹配一律 401。
  * 默认只监听 127.0.0.1：手机侧用 `adb reverse tcp:8799 tcp:8799` 访问
    本机回环，局域网与 tailnet 上的其它设备一律够不着。
  * 并发上限 2，超了回 429：每个请求都会拉起一个 Chromium 进程，不设闸
    就会把机器堆满。
  * 所有异常都回 JSON，不 reset 连接。
"""

from __future__ import annotations

import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(ROOT, "ego_srv.json")
LOG_PATH = os.path.join(ROOT, "ego_srv.log")
ERR_PATH = os.path.join(ROOT, "ego_srv.err")
PID_PATH = os.path.join(ROOT, "ego_srv.pid")

#: ego lite 的安装目录（下面按版本号排序取最新的一个）。
EGO_BASE = os.environ.get("EGO_BASE", r"C:\Program Files\Citro Labs\ego lite\Application")

DEFAULT_JS = 'const o = { title: await p.title() }; const s = await p.snapshot(); o.text = s.slice(0, 4000); console.log(JSON.stringify(o));'

CREATE_NO_WINDOW = 0x08000000

#: 同时最多跑几个 ego 进程。
DEFAULT_PARALLEL = 2

_slots = threading.Semaphore(DEFAULT_PARALLEL)
_cfg: dict = {}


# --------------------------------------------------------------------- 配置


def log(message: str) -> None:
    line = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {message}\n"
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line)
            fh.flush()  # pythonw 没有 stdout，缓冲区不刷就看不到日志
    except OSError:
        pass


def load_config() -> dict:
    """本地配置 + 环境变量；token 首次启动时生成并落盘。"""
    cfg = {
        "ego": "",
        "host": "127.0.0.1",
        "port": 8799,
        "token": "",
        "timeout": 120,
        "parallel": DEFAULT_PARALLEL,
    }
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            cfg.update(json.load(fh))
    except (OSError, ValueError):
        pass
    # 环境变量优先：token 可以完全不放进文件。
    token = os.environ.get("EGO_TOKEN", "")
    if token:
        cfg["token"] = token
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
                json.dump(cfg, fh, indent=2, ensure_ascii=False)
            os.chmod(CONFIG_PATH, 0o600)
        except OSError:
            pass
        log(f"generated a new token into {CONFIG_PATH}")
    try:
        cfg["parallel"] = max(1, int(cfg.get("parallel") or DEFAULT_PARALLEL))
    except (TypeError, ValueError):
        cfg["parallel"] = DEFAULT_PARALLEL
    return cfg


def _version_key(name: str) -> tuple:
    parts = re.findall(r"\d+", name)
    return tuple(int(p) for p in parts) if parts else (0,)


def find_ego(configured: str = "") -> str:
    """ego-browser.exe 的路径：配置 > 已安装版本里最新的那个 > 空。"""
    if configured and os.path.exists(configured):
        return configured
    try:
        versions = sorted(
            (os.path.join(EGO_BASE, name) for name in os.listdir(EGO_BASE)),
            key=lambda p: _version_key(os.path.basename(p)),
            reverse=True,
        )
    except OSError:
        versions = []
    for base in versions:
        exe = os.path.join(base, "ego-browser.exe")
        if os.path.exists(exe):
            return exe
    return ""


def ego_version(exe: str) -> str:
    if not exe:
        return ""
    return os.path.basename(os.path.dirname(exe))


# --------------------------------------------------------------- 单实例


def _pid_alive(pid: int) -> bool:
    try:
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))
        if handle:
            ctypes.windll.kernel32.CloseHandle(handle)
            return True
    except Exception:  # noqa: BLE001
        pass
    return False


def claim_instance() -> bool:
    """只允许一个实例在跑。

    Windows 上 `SO_REUSEADDR` 允许第二个进程抢同一个端口，于是两个实例各
    各有一把并发闸门 —— 闸门就形同虚设。pid 文件 + 端口探测双保险。
    """
    try:
        with open(PID_PATH, "r", encoding="utf-8") as fh:
            old = int((fh.read() or "0").strip() or 0)
    except (OSError, ValueError):
        old = 0
    if old and old != os.getpid() and _pid_alive(old):
        log("another instance is already running (pid %s) - not starting another" % old)
        return False
    try:
        with open(PID_PATH, "w", encoding="utf-8") as fh:
            fh.write(str(os.getpid()))
    except OSError:
        pass
    return True


def release_instance() -> None:
    try:
        with open(PID_PATH, "r", encoding="utf-8") as fh:
            if int((fh.read() or "0").strip() or 0) == os.getpid():
                os.remove(PID_PATH)
    except (OSError, ValueError):
        pass


def port_served(host: str, port: int) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex((host, port)) == 0


# ------------------------------------------------------------------ 执行


def run_ego(url: str, js: str, wait_ms: int, timeout: int, raw: bool) -> dict:
    """跑一次浏览器任务，返回 {exit, ms, out}。"""
    exe = find_ego(_cfg.get("ego", ""))
    if not exe:
        return {"exit": -1, "ms": 0, "out": f"ego-browser.exe not found under {EGO_BASE}"}

    tag = uuid.uuid4().hex[:10]
    task = os.path.join(ROOT, "ego_q_%s.mjs" % tag)
    body = []
    if raw:
        body.append(js)
    else:
        body.append('const t = await taskSpace("srv");')
        body.append('const p = await t.newPage();')
        body.append('try {')
        if url:
            body.append('  await p.goto(%s);' % json.dumps(url))
        if wait_ms:
            body.append('  await new Promise((r) => setTimeout(r, %d));' % int(wait_ms))
        body.append('  ' + (js or DEFAULT_JS).replace("\n", "\n  "))
        body.append('} finally {')
        body.append('  try { await p.close(); } catch (e) {}')
        body.append('}')
    with open(task, "w", encoding="utf-8") as f:
        f.write("\n".join(body))
    wrapper = "await import('file:///%s')" % os.path.join(ROOT, os.path.basename(task)).replace("\\", "/")
    started = time.time()
    try:
        r = subprocess.run([exe, "nodejs", "-e", wrapper], capture_output=True,
                           timeout=timeout, encoding="utf-8", errors="replace",
                           creationflags=CREATE_NO_WINDOW)  # 不弹黑框
        out = (r.stdout or "") + (r.stderr or "")
        code = r.returncode
    except subprocess.TimeoutExpired:
        out = "TIMEOUT after %ss" % timeout
        code = -1
    except Exception as exc:  # noqa: BLE001
        out = "%s: %s" % (type(exc).__name__, exc)
        code = -1
    finally:
        try:
            os.remove(task)
        except OSError:
            pass
    return {"exit": code, "ms": int((time.time() - started) * 1000), "out": out.strip()}


# ------------------------------------------------------------------ HTTP


class H(BaseHTTPRequestHandler):
    server_version = "minis-ego/1.0"

    def _send(self, obj, code=200):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _authorized(self) -> bool:
        token = str(_cfg.get("token") or "")
        if not token:
            self._send({"err": "server has no token configured"}, 503)
            return False
        supplied = self.headers.get("X-Minis-Token") or ""
        if not hmac.compare_digest(supplied, token):
            log("401 unauthorized from %s" % (self.client_address[0],))
            self._send({"err": "unauthorized"}, 401)
            return False
        return True

    def do_GET(self):
        if self.path.startswith("/health"):
            exe = find_ego(_cfg.get("ego", ""))
            self._send({
                "ok": True,
                "ego": exe,
                "version": ego_version(exe),
                "auth": "token",
                "parallel": _cfg.get("parallel"),
            })
            return
        self._send({"err": "use POST /q"}, 404)

    def do_POST(self):
        if not self.path.startswith("/q"):
            self._send({"err": "use POST /q"}, 404)
            return
        if not self._authorized():
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            self._send({"err": "bad json: %s" % e}, 400)
            return

        if not _slots.acquire(blocking=False):
            log("429 busy")
            self._send({"err": "busy"}, 429)
            return
        started = time.time()
        try:
            body = run_ego(
                req.get("url") or "",
                req.get("js") or "",
                req.get("wait_ms", 0),
                req.get("timeout", _cfg.get("timeout", 120)),
                bool(req.get("raw")),
            )
        except Exception as exc:  # noqa: BLE001 - 连接不许被重置
            log("500 %s: %s" % (type(exc).__name__, exc))
            self._send({"err": "%s: %s" % (type(exc).__name__, exc)}, 500)
            return
        finally:
            _slots.release()
        log("q exit=%s ms=%s url=%s" % (body.get("exit"), body.get("ms"), req.get("url") or ""))
        self._send(body)

    def log_message(self, fmt, *args):
        pass


def main() -> int:
    global _cfg
    _cfg = load_config()
    host = str(_cfg.get("host") or "127.0.0.1")
    port = int(_cfg.get("port") or 8799)
    if port_served(host, port):
        log("something already serves %s:%d - not starting another" % (host, port))
        return 0
    if not claim_instance():
        return 0
    try:
        srv = ThreadingHTTPServer((host, port), H)
    except Exception:
        with open(ERR_PATH, "w", encoding="utf-8") as f:
            import traceback
            f.write(traceback.format_exc())
        raise
    exe = find_ego(_cfg.get("ego", ""))
    log("serving on %s:%d ego=%s version=%s token=%s"
        % (host, port, exe, ego_version(exe), CONFIG_PATH))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
        release_instance()
    return 0


if __name__ == "__main__":
    sys.exit(main())
