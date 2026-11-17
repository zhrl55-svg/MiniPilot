#!/usr/bin/env python3
"""Ego Bridge — the phone's browser endpoint on this PC.

The PC is the endpoint (see tools/pchub/README.md): this process listens on
loopback and drives the locally installed ego lite browser. The phone reaches
it through the embedded Tailscale node (MiniPilot's Settings > Network), with
`tailscale serve` doing the exposure, so ego keeps its loopback-only rule.

Protocol: MCP Streamable HTTP (JSON-RPC 2.0 over POST /mcp), matching
`minis-mcp-cli/transport/http.py` in the app:
  initialize -> tools/list -> tools/call, notifications answered with 202,
  responses are plain application/json, auth is a bearer token.

Stdlib only. One ego browser space is kept alive across calls so a multi-step
task does not pay the CLI startup (measured 151 ms) plus a page reload every
step.

Run:
    python ego_mcp.py                      # 127.0.0.1:8788, token from token.txt
    python ego_mcp.py --port 8788 --token <secret>
    python ego_mcp.py --self-test          # one real ego call, then exit

Then, to expose it to the tailnet only:
    tailscale serve --bg --http=8788 http://127.0.0.1:8788

The phone adds this MCP server (Settings -> MCP Integrations):
    http://asus.<tailnet>.ts.net:8788/mcp
    header: Authorization: Bearer <token>
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / ".state.json"
TOKEN_FILE = HERE / "token.txt"
LOG_FILE = HERE / "ego_mcp.log"

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "ego-bridge"
SERVER_VERSION = "0.1.0"
SPACE_NAME = "minipilot-pc"
MAX_BODY = 8 * 1024 * 1024
DEFAULT_TIMEOUT_S = 90
SCREENSHOT_TIMEOUT_S = 150

# Removes the per-step observation from a generated action, so a script takes
# one snapshot at the end instead of one per step.
_NO_OBSERVE = re.compile(r"\n[ \t]*await __observe\([^;]*\);")

# ego-browser ships with the ego lite app; PATH is not guaranteed for a
# service, so the known install location is a fallback.
EGO_FALLBACK = Path.home() / ".local" / "share" / "ego" / "active_version_dir" / "ego-browser.exe"

_LOG_LOCK = threading.Lock()


def log(message: str) -> None:
    line = "%s %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), message)
    with _LOG_LOCK:
        try:
            print(line, flush=True)
        except Exception:
            pass
        try:
            with LOG_FILE.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass


# ─────────────────────────────────────────────────────────────────────────────
# ego invocation
# ─────────────────────────────────────────────────────────────────────────────

class EgoError(RuntimeError):
    pass


def ego_binary() -> str:
    env = os.environ.get("EGO_BROWSER", "").strip()
    if env and Path(env).exists():
        return env
    found = shutil.which("ego-browser")
    if found:
        return found
    if EGO_FALLBACK.exists():
        return str(EGO_FALLBACK)
    raise EgoError(
        "ego-browser not found. Install ego lite, or set EGO_BROWSER to its path "
        "(default: %s)" % EGO_FALLBACK
    )


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_state(state: dict) -> None:
    try:
        STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    except Exception as exc:
        log("state write failed: %s" % exc)


class EgoRunner:
    """Serializes ego calls: one browser space, one action at a time."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.state = load_state()
        self.last_error: str | None = None
        self.last_ok_at: float = 0.0

    # -- script assembly -----------------------------------------------------

    @staticmethod
    def _prelude(args: dict) -> str:
        return """
const __a = %s;
let __emitted = false;
function __emit(o) { __emitted = true; console.log("__EGO_RESULT__" + JSON.stringify(o)); }
async function __getTask() {
  const id = __a.spaceId;
  if (id !== null && id !== undefined) {
    const entry = (await listTaskSpaces()).find((s) => s.id === id);
    if (entry) {
      return entry.ownership === "user" ? await takeOverTaskSpace(id) : await taskSpace(id);
    }
  }
  return await taskSpace(__a.spaceName);
}
async function __getPage(task) {
  const pages = await task.pages();
  return pages.find((p) => p.label === "p1") || pages[0] || task.page("p1");
}
async function __observe(page, res, want) {
  try { res.url = await page.url(); } catch (e) {}
  try { res.title = await page.title(); } catch (e) {}
  if (want) {
    try { res.snapshot = String(await page.snapshot(want === true ? undefined : want)); }
    catch (e) { res.snapshot_error = String((e && e.message) || e); }
  }
  return res;
}
""" % json.dumps(args, ensure_ascii=False)

    @staticmethod
    def _action_js(op: str, a: dict) -> str:
        target = json.dumps(a.get("target", ""), ensure_ascii=False)
        text = json.dumps(a.get("text", ""), ensure_ascii=False)
        label = json.dumps(a.get("label", ""), ensure_ascii=False)
        if op == "open":
            url = json.dumps(a.get("url", ""), ensure_ascii=False)
            return """
  if (!%s) throw new Error("open needs a url");
  await page.goto(%s);
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || true));""" % (url, url)
        if op == "click":
            return """
  if (!%s) throw new Error("click needs a target (a snapshot ref like @21, or a selector)");
  await page.click(%s, %s ? { label: %s } : undefined);
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || true));""" % (target, target, label, label)
        if op == "fill":
            return """
  if (!%s) throw new Error("fill needs a target");
  await page.fill(%s, %s);
  res.filled = true;
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || false));""" % (target, target, text)
        if op == "press":
            key = json.dumps(a.get("key", "Enter"), ensure_ascii=False)
            return """
  await page.keyboard.press(%s);
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || true));""" % (key,)
        if op == "select":
            value = json.dumps(a.get("value", ""), ensure_ascii=False)
            return """
  if (!%s) throw new Error("select needs a target");
  await page.selectOption(%s, %s ? { label: %s } : %s);
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || true));""" % (
                target, target, label, label, value)
        if op == "eval":
            script = json.dumps(a.get("script", ""), ensure_ascii=False)
            return """
  const __v = await page.evaluate(%s);
  res.value = __v;
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || false));""" % (script,)
        if op == "wait":
            ms = int(a.get("ms", 500))
            return """
  await page.waitForTimeout(%d);""" % ms
        if op == "wait_for":
            return """
  await page.waitForSelector(%s, { state: "visible" });
  await __observe(page, res, __a.snapshot === false ? null : (__a.snapshot || true));""" % (target,)
        if op == "screenshot":
            path = json.dumps(a.get("path", ""), ensure_ascii=False)
            return """
  const __shot = await page.screenshot({ path: %s });
  res.screenshot = __shot;""" % (path,)
        if op == "tabs":
            return """
  const __tabs = await task.tabs();
  res.tabs = __tabs.map((t) => ({ label: t.label || null, title: t.title, url: t.url, active: t.active, openedBy: t.openedBy }));"""
        if op == "finish":
            keep = json.dumps(a.get("keep", []), ensure_ascii=False)
            return """
  await task.finish({ keep: %s });
  res.finished = true;""" % (keep,)
        if op == "snapshot":
            return """
  await __observe(page, res, __a.scope ? { scope: __a.scope } : true);"""
        raise EgoError("unknown action %r" % op)

    def _build(self, op: str, args: dict, space_id) -> str:
        payload = dict(args)
        payload["spaceId"] = space_id
        payload["spaceName"] = SPACE_NAME
        steps = payload.pop("steps", None)
        prelude = self._prelude(payload)
        body: list[str] = [prelude]
        if op == "script":
            if not isinstance(steps, list) or not steps:
                raise EgoError("script needs a non-empty steps array")
            body.append("try {")
            body.append("  const task = await __getTask();")
            body.append("  const page = await __getPage(task);")
            body.append("  const res = { ok: true, spaceId: task.spaceId, page: page.label };")
            for i, step in enumerate(steps):
                if not isinstance(step, dict):
                    raise EgoError("steps[%d] is not an object" % i)
                act = str(step.get("action", "")).strip()
                if act == "screenshot":
                    # Screenshot mid-script: keep going when it fails.
                    body.append("  try {")
                    body.append(self._action_js("screenshot", step))
                    body.append("  } catch (e) { res.step_errors = (res.step_errors||[]).concat([{step:%d, error:String(e&&e.message||e)}]); }" % i)
                    continue
                body.append("  try {")
                # A script takes ONE snapshot at the end; the per-step
                # observation is stripped so a 10-step flow does not pay for
                # 10 snapshots.
                body.append(_NO_OBSERVE.sub("", self._action_js(act, step)))
                body.append("  } catch (e) {")
                body.append("    res.ok = false;")
                body.append("    res.failed_step = %d;" % i)
                body.append("    res.error = String((e && e.message) || e);")
                body.append("    await __observe(page, res, __a.snapshot === false ? null : true);")
                body.append("    __emit(res);")
                body.append("    throw new Error(\"stop after failed step\");")
                body.append("  }")
            if args.get("snapshot", True):
                body.append("  await __observe(page, res, true);")
            body.append("  __emit(res);")
            body.append("} catch (e) { if (!__emitted) __emit({ ok: false, error: String((e && e.message) || e) }); }")
        else:
            body.append("try {")
            body.append("  const task = await __getTask();")
            body.append("  const page = await __getPage(task);")
            body.append("  const res = { ok: true, spaceId: task.spaceId, page: page.label };")
            body.append(self._action_js(op, args))
            body.append("  __emit(res);")
            body.append("} catch (e) {")
            body.append("  if (!__emitted) __emit({ ok: false, error: String((e && e.message) || e) });")
            body.append("}")
        return "\n".join(body)

    # -- execution -----------------------------------------------------------

    def run(self, op: str, args: dict, timeout_s: int = DEFAULT_TIMEOUT_S) -> dict:
        with self.lock:
            space_id = self.state.get("spaceId")
            attempt = 0
            while True:
                attempt += 1
                script = self._build(op, args, space_id)
                try:
                    out = self._invoke(script, timeout_s)
                except EgoError:
                    raise
                result, new_space = self._parse(out)
                if new_space is not None and new_space != self.state.get("spaceId"):
                    self.state["spaceId"] = new_space
                    save_state(self.state)
                # A stale space id makes every call fail the same way; drop it
                # once and retry with a fresh space.
                if (
                    attempt == 1
                    and not result.get("ok")
                    and space_id is not None
                    and _looks_like_dead_space(result.get("error", ""))
                ):
                    log("space %s looks gone (%s) — retrying with a new space"
                        % (space_id, result.get("error")))
                    space_id = None
                    self.state.pop("spaceId", None)
                    save_state(self.state)
                    continue
                if result.get("ok"):
                    self.last_ok_at = time.time()
                    self.last_error = None
                else:
                    self.last_error = str(result.get("error") or "ego call failed")
                    log("ego call failed: %s" % self.last_error)
                return result

    def _invoke(self, script: str, timeout_s: int) -> str:
        binary = ego_binary()
        try:
            proc = subprocess.run(
                [binary, "nodejs"],
                input=script.encode("utf-8"),
                capture_output=True,
                timeout=timeout_s,
            )
        except subprocess.TimeoutExpired:
            raise EgoError("ego call timed out after %ss" % timeout_s)
        except OSError as exc:
            raise EgoError("could not start ego-browser: %s" % exc)
        stdout = proc.stdout.decode("utf-8", "replace")
        stderr = proc.stderr.decode("utf-8", "replace")
        if proc.returncode != 0 and "__EGO_RESULT__" not in (stdout + stderr):
            detail = (stderr or stdout).strip().splitlines()[-3:]
            raise EgoError("ego exited %d: %s" % (proc.returncode, " / ".join(detail)))
        # ego's embedded Node runtime writes console.log to stderr, not stdout
        # (verified on 0.6.3.17); parse both so a version change cannot break it.
        return stdout + "\n" + stderr

    @staticmethod
    def _parse(stdout: str) -> tuple[dict, int | None]:
        payload = None
        for line in stdout.splitlines():
            marker = line.find("__EGO_RESULT__")
            if marker >= 0:
                try:
                    payload = json.loads(line[marker + len("__EGO_RESULT__"):])
                except ValueError:
                    continue
        if not isinstance(payload, dict):
            tail = stdout.strip().splitlines()[-5:]
            raise EgoError("no result from ego: %s" % " / ".join(tail))
        space = payload.get("spaceId")
        return payload, (int(space) if isinstance(space, (int, float)) else None)


def _looks_like_dead_space(error: str) -> bool:
    e = (error or "").lower()
    return "space" in e and any(k in e for k in ("not found", "closed", "gone", "no longer"))


# ─────────────────────────────────────────────────────────────────────────────
# MCP surface
# ─────────────────────────────────────────────────────────────────────────────

def tool_defs() -> list[dict]:
    target = {"type": "string",
              "description": "A snapshot ref such as @21, or a selector "
                             "(text=, loc=role:button[name='Sign in'], css, xpath=...)."}

    def t(name, desc, props, required):
        return {"name": name, "description": desc,
                "inputSchema": {"type": "object", "properties": props, "required": required}}

    return [
        t("browser_open", "Open a URL in the PC browser and return the page snapshot.",
          {"url": {"type": "string"}, "snapshot": {"type": "boolean", "default": True}}, ["url"]),
        t("browser_snapshot",
          "Read the current page as an accessibility snapshot. Prefer this over screenshots: "
          "it gives you refs (@N) to act on.",
          {"scope": {"type": "string", "enum": ["viewport", "full_page"]}}, []),
        t("browser_click", "Click an element, then return the updated snapshot.",
          {"target": target, "label": {"type": "string", "description": "3-6 word action label"},
           "snapshot": {"type": "boolean", "default": True}}, ["target"]),
        t("browser_fill", "Type text into an input or textarea.",
          {"target": target, "text": {"type": "string"},
           "snapshot": {"type": "boolean", "default": False}}, ["target", "text"]),
        t("browser_press", "Press a key or chord (Enter, Escape, ControlOrMeta+A).",
          {"key": {"type": "string"}, "snapshot": {"type": "boolean", "default": True}}, ["key"]),
        t("browser_select", "Choose an option in a <select>.",
          {"target": target, "value": {"type": "string"},
           "label": {"type": "string", "description": "visible option text"}}, ["target"]),
        t("browser_eval", "Run JavaScript in the page and return the value.",
          {"script": {"type": "string"}, "snapshot": {"type": "boolean", "default": False}}, ["script"]),
        t("browser_script",
          "Run several actions in one call and return one final snapshot. Use this for "
          "multi-step flows: open -> click -> fill -> click. Stops at the first failed step.",
          {"steps": {"type": "array", "items": {"type": "object"},
                     "description": "Each step: {action: open|click|fill|press|select|eval|wait|wait_for|screenshot, ...args}"},
           "snapshot": {"type": "boolean", "default": True}}, ["steps"]),
        t("browser_screenshot",
          "Save a PNG of the page and return its path (for the user, not for you).",
          {"path": {"type": "string", "description": "absolute path; a temp path is used if omitted"}}, []),
        t("browser_tabs", "List the tabs in this browser space.", {}, []),
        t("browser_finish",
          "Close the browser space when the task is done (closes its tabs).",
          {"keep": {"type": "array", "items": {"type": "string"},
                    "description": "page labels to keep open (rare)"}}, []),
    ]


def call_tool(runner: EgoRunner, name: str, args: dict) -> dict:
    try:
        if name == "browser_open":
            result = runner.run("open", {"url": args.get("url", ""),
                                         "snapshot": args.get("snapshot", True)})
        elif name == "browser_snapshot":
            result = _snapshot(runner, args)
        elif name == "browser_click":
            result = runner.run("click", {**args})
        elif name == "browser_fill":
            result = runner.run("fill", {**args})
        elif name == "browser_press":
            result = runner.run("press", {**args})
        elif name == "browser_select":
            result = runner.run("select", {**args})
        elif name == "browser_eval":
            result = runner.run("eval", {**args})
        elif name == "browser_script":
            result = runner.run("script", {**args}, timeout_s=SCREENSHOT_TIMEOUT_S)
        elif name == "browser_screenshot":
            path = args.get("path") or str(HERE / ("shot-%d.png" % int(time.time())))
            result = runner.run("screenshot", {"path": path}, timeout_s=SCREENSHOT_TIMEOUT_S)
        elif name == "browser_tabs":
            result = runner.run("tabs", {})
        elif name == "browser_finish":
            result = runner.run("finish", {"keep": args.get("keep") or []})
        else:
            return _text("unknown tool %r" % name, is_error=True)
    except EgoError as exc:
        return _text(str(exc), is_error=True)
    except Exception as exc:  # never let a tool crash the server
        log("tool %s crashed: %s\n%s" % (name, exc, traceback.format_exc()))
        return _text("bridge error: %s" % exc, is_error=True)

    if not result.get("ok"):
        return _text(str(result.get("error") or "failed"), is_error=True, extra=result)
    return _text(_render(result), is_error=False, extra=result)


def _snapshot(runner: EgoRunner, args: dict) -> dict:
    # A snapshot-only call: run the script op with an empty step list and force
    # the final observation.
    return runner.run("snapshot", {"scope": args.get("scope")})


def _render(result: dict) -> str:
    lines = []
    if result.get("url"):
        lines.append("URL: %s" % result["url"])
    if result.get("title"):
        lines.append("Title: %s" % result["title"])
    if result.get("screenshot"):
        lines.append("Screenshot: %s" % result["screenshot"])
    if result.get("value") is not None:
        lines.append("Value: %s" % json.dumps(result["value"], ensure_ascii=False))
    if result.get("filled"):
        lines.append("Filled.")
    if result.get("finished"):
        lines.append("Space closed.")
    if result.get("tabs"):
        lines.append("Tabs: %s" % json.dumps(result["tabs"], ensure_ascii=False))
    if result.get("failed_step") is not None:
        lines.append("Failed at step %s." % result["failed_step"])
    snap = result.get("snapshot")
    if snap:
        lines.append("")
        lines.append(snap)
    elif result.get("snapshot_error"):
        lines.append("Snapshot failed: %s" % result["snapshot_error"])
    return "\n".join(lines) if lines else json.dumps(result, ensure_ascii=False)


def _text(text: str, is_error: bool = False, extra: dict | None = None) -> dict:
    out = {"content": [{"type": "text", "text": text}], "isError": is_error}
    if extra:
        # Keep a structured echo for tooling; minis-mcp-cli prints the whole
        # result, so this must stay small.
        slim = {k: v for k, v in extra.items() if k != "snapshot"}
        out["structuredContent"] = slim
    return out


# ─────────────────────────────────────────────────────────────────────────────
# HTTP server
# ─────────────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "EgoBridge/" + SERVER_VERSION
    protocol_version = "HTTP/1.1"

    runner: EgoRunner
    token: str

    # -- helpers -------------------------------------------------------------

    def _send(self, code: int, payload, extra_headers: dict | None = None) -> None:
        body = b""
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        if payload is not None:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _authed(self) -> bool:
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return False
        got = header[len(prefix):].strip()
        return hmac.compare_digest(got, self.token)

    def _read_body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0:
            return b""
        if length > MAX_BODY:
            raise ValueError("body too large")
        return self.rfile.read(length)

    def log_message(self, fmt, *args):  # noqa: A003 - stdlib signature
        return  # the access log is noise; log() covers what matters

    # -- routes --------------------------------------------------------------

    def do_GET(self):  # noqa: N802 - stdlib signature
        if self.path.split("?")[0] in ("/health", "/healthz"):
            self._send(200, {
                "ok": True,
                "ego": ego_available(),
                "spaceId": self.runner.state.get("spaceId"),
                "lastOkAt": self.runner.last_ok_at or None,
                "lastError": self.runner.last_error,
            })
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802 - stdlib signature
        path = self.path.split("?")[0]
        # Drain the body BEFORE any early return. With HTTP/1.1 keep-alive a
        # 401/404 that leaves the body unread desyncs the connection: the next
        # request on it is parsed starting inside this body. Behind a reverse
        # proxy (`tailscale serve` reuses backend connections) that surfaced as
        # `501 Unsupported method ('{...json...}POST')`.
        try:
            raw = self._read_body()
        except ValueError as exc:
            self.close_connection = True
            self._send(413, {"error": str(exc)}, {"Connection": "close"})
            return
        if path not in ("/mcp", "/"):
            self._send(404, {"error": "not found"})
            return
        if not self._authed():
            self._send(401, {"error": "unauthorized"})
            return
        try:
            rpc = json.loads(raw.decode("utf-8"))
        except Exception:
            self._send(400, {"error": "invalid JSON"})
            return
        if not isinstance(rpc, dict):
            self._send(400, {"error": "expected a JSON-RPC object"})
            return

        method = rpc.get("method", "")
        rpc_id = rpc.get("id", None)
        params = rpc.get("params") or {}
        is_notification = rpc_id is None

        if method == "notifications/initialized" or is_notification:
            self._send(202, None)
            return

        if method == "initialize":
            self._send(200, {"jsonrpc": "2.0", "id": rpc_id, "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            }})
            return

        if method == "ping":
            self._send(200, {"jsonrpc": "2.0", "id": rpc_id, "result": {}})
            return

        if method == "tools/list":
            self._send(200, {"jsonrpc": "2.0", "id": rpc_id,
                             "result": {"tools": tool_defs()}})
            return

        if method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments") or {}
            if not isinstance(args, dict):
                self._send(200, {"jsonrpc": "2.0", "id": rpc_id, "error": {
                    "code": -32602, "message": "arguments must be an object"}})
                return
            t0 = time.time()
            result = call_tool(self.runner, name, args)
            log("tool %s %.0fms ok=%s" % (
                name, (time.time() - t0) * 1000, not result.get("isError")))
            self._send(200, {"jsonrpc": "2.0", "id": rpc_id, "result": result})
            return

        self._send(200, {"jsonrpc": "2.0", "id": rpc_id, "error": {
            "code": -32601, "message": "method not found: %s" % method}})


def ego_available() -> bool:
    try:
        ego_binary()
        return True
    except EgoError:
        return False


# ─────────────────────────────────────────────────────────────────────────────
# self test + main
# ─────────────────────────────────────────────────────────────────────────────

def self_test() -> int:
    runner = EgoRunner()
    print("ego-browser:", ego_binary())
    result = runner.run("script", {
        "steps": [{"action": "open", "url": "https://example.com"}],
        "snapshot": True,
    })
    print(json.dumps(result, ensure_ascii=False, indent=1)[:2000])
    return 0 if result.get("ok") else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="Ego Bridge — MCP browser endpoint for MiniPilot")
    ap.add_argument("--host", default="127.0.0.1", help="bind address (keep it loopback)")
    ap.add_argument("--port", type=int, default=8788)
    ap.add_argument("--token", default="", help="bearer token; otherwise token.txt is used")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if args.self_test:
        return self_test()

    token = args.token.strip()
    if not token:
        if TOKEN_FILE.exists():
            token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if not token:
            import secrets
            token = secrets.token_urlsafe(24)
            TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
            log("generated a new token in %s" % TOKEN_FILE)
    if not token:
        print("no token", file=sys.stderr)
        return 2

    Handler.runner = EgoRunner()
    Handler.token = token

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    log("ego bridge on http://%s:%d/mcp (ego=%s, space=%s)" % (
        args.host, args.port, ego_available(), Handler.runner.state.get("spaceId")))
    print("token: %s" % token, flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
