"""Device probe runner: drive the real Minis/MiniPilot app on the phone.

Same idea as run.py (Harness² style A/B on tool-call trajectories), but the
executor is the real app on the real device over the debug JSON-RPC:

    chat.prompt        -> start a turn in a fresh session (optionally pinned
                          to a model entry + thinking level)
    chat.session.status-> poll until the agent is idle
    chat.messages.list -> pull the transcript including toolCalls

The trajectory it produces is the real thing: Minis's own system prompt, its
own tool schemas, its own shell on the phone.

Safety: the device agent has a real shell (shell_execute) and Minis has no
switch to disable it, so only run tasks that do not mutate the phone. Probe
runs write nothing to the device beyond the debug sessions they create.

    python tools/probe/run_device.py --only-task m-20261120-001 \
        --model-entry 380b0b7c-0275-40c8-9294-681cbcc9b68d/workbuddy/deepseek-v4.1-flash
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run import diff_trajectories, load_tasks  # noqa: E402

DEFAULT_PORT = 5321
DEFAULT_SERIAL = "MDX0220A21035172"


class Device:
    """Minimal JSON-RPC client over the device debug server."""

    def __init__(self, host: str = "127.0.0.1", port: int = DEFAULT_PORT, timeout: int = 60):
        self.url = f"http://{host}:{port}"
        self.timeout = timeout
        self._id = 0

    def call(self, method: str, params: dict | None = None, timeout: int | None = None) -> dict:
        self._id += 1
        body = json.dumps(
            {"jsonrpc": "2.0", "id": self._id, "method": method, "params": params or {}},
            ensure_ascii=False,
        ).encode("utf-8")
        req = urllib.request.Request(self.url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"{method}: HTTP {exc.code}") from exc
        if "error" in payload:
            raise RuntimeError(f"{method}: {payload['error']}")
        return payload.get("result") or {}

    # --- convenience wrappers -------------------------------------------------

    def prompt(self, text: str, *, session_id: str | None = None, model_entry: str | None = None,
               thinking: str | None = None, wait: bool = False, wait_timeout: int = 600,
               system_prompt_append: str | None = None) -> dict:
        params: dict = {"prompt": text}
        if session_id:
            params["sessionId"] = session_id
        if model_entry:
            params["modelEntryId"] = model_entry
        if thinking:
            params["thinkingLevel"] = thinking
        if system_prompt_append:
            params["systemPromptAppend"] = system_prompt_append
        if wait:
            params["wait"] = True
            params["waitTimeout"] = wait_timeout
        return self.call("chat.prompt", params, timeout=wait_timeout + 120 if wait else None)

    def wait_idle(self, session_id: str, poll_s: float = 2.0, max_s: int = 900) -> dict:
        """Poll until the agent is idle.

        `isRunning` comes from an activity tracker that only flips to true once
        the agent actually starts, so a naive poll right after chat.prompt sees
        "idle" for a session that has not begun yet. Require either having seen
        it running, or the session already carrying an assistant message,
        before accepting "idle" as final.
        """
        deadline = time.time() + max_s
        last: dict = {}
        saw_running = False
        while time.time() < deadline:
            last = self.call("chat.session.status", {"sessionId": session_id})
            if last.get("isRunning"):
                saw_running = True
            elif saw_running or self._has_assistant_message(session_id):
                return last
            time.sleep(poll_s)
        return {**last, "timedOut": True}

    def _has_assistant_message(self, session_id: str) -> bool:
        try:
            msgs = self.messages(session_id, limit=20)
        except RuntimeError:
            return False
        return any(m.get("role") == "assistant" for m in msgs)

    def messages(self, session_id: str, limit: int = 1000) -> list[dict]:
        result = self.call("chat.messages.list",
                           {"sessionId": session_id, "limit": limit, "includeTools": True})
        return result.get("messages") or []


def _parse_input(raw):
    """tool input arrives as a JSON string on Android and as an object on iOS."""
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"_raw": raw[:500]}
    return {}


def normalize(messages: list[dict]) -> list[dict]:
    """Turn a debug transcript into the same tool-call shape run.py emits.

    Android serializes a turn as `parts` entries `{type: "toolUse", value:
    {toolUseId, name, input}}`; iOS and the published API use a `toolCalls`
    array on assistant messages. Read both so one normalizer serves both
    platforms.
    """
    calls: list[dict] = []
    for msg in messages:
        for tc in msg.get("toolCalls") or []:
            payload = tc.get("input") or {}
            calls.append({
                "tool": tc.get("name"),
                "args_hash": hashlib.sha256(
                    json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")
                ).hexdigest()[:12],
                "input": payload,
                "status": tc.get("status"),
            })
        for part in msg.get("parts") or []:
            if not isinstance(part, dict) or part.get("type") not in ("toolUse", "tool_use"):
                continue
            value = part.get("value") or {}
            payload = _parse_input(value.get("input"))
            calls.append({
                "tool": value.get("name"),
                "args_hash": hashlib.sha256(
                    json.dumps(payload, sort_keys=True, ensure_ascii=False).encode("utf-8")
                ).hexdigest()[:12],
                "input": payload,
                "status": None,
            })
    return calls


def last_answer_text(messages: list[dict]) -> str:
    """Final assistant text, from `content` or from text parts."""
    for msg in reversed(messages):
        if msg.get("role") != "assistant":
            continue
        content = (msg.get("content") or "").strip()
        if content:
            return content
        texts = [p.get("value") for p in (msg.get("parts") or [])
                 if isinstance(p, dict) and p.get("type") == "text" and p.get("value")]
        if texts:
            return "\n".join(texts).strip()
    return ""


def build_prompt(task: dict, overlay: str) -> str:
    """The measured turn's text. The overlay is a candidate harness rule.

    Both variants travel the same channel (a delimited preamble in the user
    message) so the comparison isolates the rule's wording, not the channel.
    """
    if not overlay:
        return task["prompt"]
    return (f"[本轮附加指令 · 由 probe runner 注入]\n{overlay}\n\n"
            f"[任务原文]\n{task['prompt']}")


def forward(serial: str, port: int = DEFAULT_PORT) -> None:
    subprocess.run(["adb", "-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"],
                   check=True, capture_output=True)


def emit_task(run_dir: pathlib.Path, task: dict, variant: str, messages: list[dict],
              session_id: str, elapsed: float, timed_out: bool,
              report: list[str]) -> int:
    """Write the per-task artifacts and append its report section."""
    calls = normalize(messages)
    (run_dir / f"{task['task_id']}__{variant}.messages.json").write_text(
        json.dumps(messages, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / f"{task['task_id']}__{variant}.tools.jsonl").write_text(
        "".join(json.dumps(c, ensure_ascii=False) + "\n" for c in calls), encoding="utf-8")
    report.append(f"\n## {task['task_id']}  [{', '.join(task.get('tags') or []) or '-'}]  "
                  f"sev={task.get('severity', 'info')}\n")
    report.append(f"*{task.get('summary', '')}*\n")
    report.append(f"- session: `{session_id}`  elapsed={elapsed}s  "
                  f"timedOut={timed_out}  tools={len(calls)}\n")
    report.append(f"- tool sequence: {[c['tool'] for c in calls]}\n")
    answer = last_answer_text(messages)
    if answer:
        report.append(f"- answer: {answer.replace(chr(10), ' ')[:220]}\n")
    return len(calls)


def main() -> int:
    p = argparse.ArgumentParser(description="device probe runner (real app on the phone)")
    p.add_argument("--tasks", type=pathlib.Path, default=HERE / "tasks.jsonl")
    p.add_argument("--only-task", default="", help="comma-separated task ids")
    p.add_argument("--prompt", default="",
                   help="run this text instead of a corpus task (plumbing test); "
                        "use --label to name the artifacts")
    p.add_argument("--serial", default=DEFAULT_SERIAL, help="adb serial of the phone")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--endpoint", default="",
                   help="host:port of an already-reachable debug server (e.g. an iOS "
                        "device over an SSH tunnel). Skips the adb forward step.")
    p.add_argument("--model-entry", default=None, help="modelEntryId to pin (determinism)")
    p.add_argument("--thinking", default="high", help="thinkingLevel to pin")
    p.add_argument("--overlay", default="", help="candidate harness rule prepended to the user turn")
    p.add_argument("--system-overlay", default="",
                   help="candidate harness text passed as chat.prompt systemPromptAppend "
                        "(true system-prompt-level channel; requires the build with T-probe-overlay)")
    p.add_argument("--variant", default="baseline", help="variant label for the artifacts")
    p.add_argument("--label", default="", help="task id label for an ad-hoc prompt")
    p.add_argument("--run-id", default=None)
    p.add_argument("--runs-dir", type=pathlib.Path, default=HERE / "runs")
    p.add_argument("--max-s", type=int, default=900, help="per-task wall clock cap")
    p.add_argument("--include-unsafe", action="store_true",
                   help="bulk-run tasks whose device_safe=false (build/install/hub); "
                        "explicit --only-task bypasses the filter anyway")
    p.add_argument("--reprocess", action="store_true",
                   help="re-derive tools/report from saved transcripts; no device needed")
    args = p.parse_args()

    tasks = load_tasks(args.tasks, skip_protocol=True)
    if args.prompt:
        tasks = [{"task_id": args.label or "adhoc", "summary": args.prompt[:80],
                  "tags": ["adhoc"], "severity": "info", "prompt": args.prompt}]
    elif args.only_task:
        wanted = [w.strip() for w in args.only_task.split(",") if w.strip()]
        by_id = {t["task_id"]: t for t in tasks}
        missing = [w for w in wanted if w not in by_id]
        if missing:
            print(f"unknown task ids: {missing}", file=sys.stderr)
            return 2
        tasks = [by_id[w] for w in wanted]
    elif not args.include_unsafe:
        # An explicit --only-task overrides the safety filter; a bulk run does
        # not. The phone agent has a real shell and there is no switch to turn
        # it off, so bulk runs stay on the read-only/report subset.
        tasks = [t for t in tasks if t.get("device_safe", False)]

    run_id = args.run_id or time.strftime("%Y%m%d-%H%M%S")
    run_dir = args.runs_dir / f"{run_id}-device"
    run_dir.mkdir(parents=True, exist_ok=True)

    if args.reprocess:
        # Re-derive artifacts from saved transcripts (no device). Useful when
        # the normalizer or the report gained new fields after a run.
        manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
        variant = manifest.get("variant", args.variant)
        by_id = {t["task_id"]: t for t in tasks}
        report = [f"# device probe run {run_id} (reprocessed)\n",
                  f"variant={variant}  model={manifest.get('model_entry')}  "
                  f"thinking={manifest.get('thinking')}\n"]
        for tid in manifest.get("task_ids") or []:
            path = run_dir / f"{tid}__{variant}.messages.json"
            if not path.exists():
                report.append(f"\n## {tid}\n- ERROR: missing transcript\n")
                continue
            messages = json.loads(path.read_text(encoding="utf-8"))
            task = by_id.get(tid) or {"task_id": tid, "tags": [], "severity": "info", "summary": ""}
            emit_task(run_dir, task, variant, messages, "<reprocessed>", 0, False, report)
        (run_dir / "report.md").write_text("\n".join(report), encoding="utf-8")
        print(f"reprocessed -> {run_dir}/report.md", file=sys.stderr)
        return 0

    if args.endpoint:
        host, _, port_s = args.endpoint.partition(":")
        dev = Device(host=host, port=int(port_s or args.port))
    else:
        forward(args.serial, args.port)
        dev = Device(port=args.port)

    manifest = {
        "run_id": run_id,
        "executor": "device",
        "serial": args.serial,
        "port": args.port,
        "model_entry": args.model_entry,
        "thinking": args.thinking,
        "overlay": args.overlay,
        "overlay_sha256": hashlib.sha256(args.overlay.encode("utf-8")).hexdigest(),
        "system_overlay": args.system_overlay,
        "system_overlay_sha256": hashlib.sha256(args.system_overlay.encode("utf-8")).hexdigest(),
        "variant": args.variant,
        "task_ids": [t["task_id"] for t in tasks],
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    (run_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    report = [f"# device probe run {run_id}\n",
              f"variant={args.variant}  model={args.model_entry}  thinking={args.thinking}\n"]

    for task in tasks:
        text = build_prompt(task, args.overlay)
        sys.stderr.write(f"  running {task['task_id']} x {args.variant} on device ...\n")
        started = time.time()
        try:
            result = dev.prompt(text, model_entry=args.model_entry, thinking=args.thinking,
                                wait=True, wait_timeout=args.max_s,
                                system_prompt_append=args.system_overlay or None)
            session_id = result["sessionId"]
            status = result
            if result.get("status") in ("Running", "Timeout", None):
                status = dev.wait_idle(session_id, max_s=args.max_s)
            messages = dev.messages(session_id)
        except Exception as exc:  # noqa: BLE001 - report and keep going
            report.append(f"\n## {task['task_id']}\n- ERROR: {exc}\n")
            continue
        elapsed = round(time.time() - started, 1)
        emit_task(run_dir, task, args.variant, messages, session_id,
                  elapsed, bool(status.get("timedOut")), report)

    (run_dir / "report.md").write_text("\n".join(report), encoding="utf-8")
    print(f"\nwrote {run_dir}/report.md", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
