"""probe-runner: A/B two harness variants against the mailbox task set.

For each task x variant, run `claude -p` headless in an isolated scratch dir,
capture the tool-call trajectory (stream-json), then emit a diff report.

Usage:
    python tools/probe/run.py --tasks tools/probe/tasks.jsonl --limit 2
    python tools/probe/run.py --tasks tools/probe/tasks.jsonl \
        --variants tools/probe/variants/baseline.json,tools/probe/variants/verbose_tools.json \
        --runs-dir tools/probe/runs

The stub relay on port 8790 (start_relay.py) is NOT started here; tasks that
would hit the real hub are tagged 'hub' and skipped unless --include-hub is set
(and even then they only run against the stub relay -- never prod).

Output per run:
    <runs-dir>/<run-id>/<task_id>__<variant>.json    raw stream-json events
    <runs-dir>/<run-id>/<task_id>__<variant>.tools.jsonl   normalized tool calls
    <runs-dir>/<run-id>/report.md                    per-task A/B trajectory diff
    <runs-dir>/<run-id>/manifest.json                config + sha256 of inputs
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

#: Commands a probe run must never execute: device control, network egress and
#: build/install pipelines all reach outside the scratch dir. Deny rules beat
#: allow rules, including the permissive ones in the user's global settings.
DEFAULT_DENY_TOOLS = [
    "Bash(adb:*)",
    "Bash(curl:*)",
    "Bash(ssh:*)",
    "Bash(scp:*)",
    "Bash(gradlew:*)",
    "Bash(./gradlew:*)",
    "Bash(gradle:*)",
    "Bash(git push:*)",
    "Bash(git commit:*)",
    "Bash(git checkout:*)",
    "Bash(echo:*)",
    "Bash(sed:*)",
    "Bash(tee:*)",
    "PowerShell(adb:*)",
    "PowerShell(curl:*)",
    "PowerShell(ssh:*)",
    "PowerShell(Invoke-WebRequest:*)",
    "PowerShell(Invoke-RestMethod:*)",
    "PowerShell(Set-Content:*)",
    "PowerShell(Add-Content:*)",
    "PowerShell(Out-File:*)",
    "PowerShell(New-Item:*)",
    "PowerShell(Remove-Item:*)",
    "PowerShell(Move-Item:*)",
    "PowerShell(Copy-Item:*)",
    "PowerShell(Rename-Item:*)",
    "PowerShell(Set-Item:*)",
]

#: File-mutating tools. Denied by default for every probe run: the child shares
#: the host filesystem and its Read access is global, so an unattended agent
#: given a "change this code" task could edit the real repo. Pass --allow-writes
#: only when running in a disposable VM/account.
WRITE_TOOLS = ["Edit", "Write", "NotebookEdit"]


# --- task loading ------------------------------------------------------------


def load_tasks(path: pathlib.Path, skip_protocol: bool = True) -> list[dict]:
    """Load tasks.jsonl; optionally skip mailbox-protocol meta-tasks.

    A protocol task is one whose prompt starts with "[FROM: MINIS]" -- these
    define the mailbox channel format, not an engineering job.
    """
    tasks: list[dict] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            t = json.loads(line)
            if skip_protocol and t["prompt"].startswith("[FROM: MINIS]"):
                continue
            tasks.append(t)
    return tasks


# --- variant loading ---------------------------------------------------------


def load_variant(path: pathlib.Path) -> dict:
    """A variant JSON: {"name": str, "system_prompt_append": str, "env": {...}}."""
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("name", "system_prompt_append"):
        if key not in data:
            raise ValueError(f"{path}: missing '{key}'")
    return data


# --- scratch setup -----------------------------------------------------------


def setup_scratch(scratch: pathlib.Path, task: dict) -> None:
    """Reset scratch dir for one task run. Empty by default; copies a stub
    README describing the task so `claude -p` has a target to work on.
    """
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir(parents=True)
    (scratch / "TASK.md").write_text(
        f"# Task {task['task_id']}\n\n{task['summary']}\n\n"
        f"## Instruction\n\n{task['prompt']}\n",
        encoding="utf-8",
    )


# --- trajectory capture ------------------------------------------------------


def run_claude(
    *,
    variant: dict,
    scratch: pathlib.Path,
    out_jsonl: pathlib.Path,
    timeout_s: int,
    model: str | None = None,
) -> dict:
    """Run `claude -p` headless in `scratch`, write stream-json to `out_jsonl`.

    The session is rooted at `scratch` (cwd) so it cannot touch the repo.
    Permission is restricted to a read/write whitelist; headless mode rejects
    any tool outside it instead of prompting.

    Returns {"exit_code": int, "duration_s": float, "tool_calls": [...]}.
    On timeout, partial stdout/stderr is still written to `out_jsonl`.
    """
    cmd = [
        "claude",
        "-p",
        f"Work on TASK.md in the current directory. Finish the task or state "
        f"clearly what is blocking you. If a tool you need is not available "
        f"or an action is denied, STOP and report the blocker in one short "
        f"message -- do not repeat the attempt. Do not ask for confirmation.",
        "--output-format", "stream-json",
        "--verbose",
        "--append-system-prompt", variant["system_prompt_append"],
        "--allowedTools", "Read,Write,Edit,Bash(git status),Bash(ls),Bash(cat)",
        # No MCP from configs/plugins: the probe should see a vanilla tool
        # surface, and claude-mem's search tools would leak host state.
        "--strict-mcp-config",
        "--max-turns", "20",
    ]
    deny = variant["disallow_tools"] if "disallow_tools" in variant else DEFAULT_DENY_TOOLS
    if deny:
        cmd.extend(["--disallowedTools", ",".join(deny)])
    if variant.get("bypass_permissions"):
        cmd.append("--permission-mode")
        cmd.append("bypassPermissions")
    if model or variant.get("model"):
        # The parent session may run a model-group name (e.g. "group/smart-split[1m]")
        # that the headless CLI cannot resolve; pin the subprocess explicitly.
        cmd.extend(["--model", model or variant["model"]])
    env = {**dict(__import__("os").environ), **variant.get("env", {})}
    start = time.time()
    proc = subprocess.Popen(
        cmd,
        cwd=str(scratch),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    timed_out = False
    try:
        stdout, stderr = proc.communicate(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        stdout, stderr = proc.communicate()
    duration = time.time() - start

    out_jsonl.write_text(stdout or "", encoding="utf-8")

    tool_calls = []
    for line in (stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") != "assistant":
            continue
        msg = event.get("message", {})
        for block in msg.get("content", []):
            if block.get("type") == "tool_use":
                tool_calls.append({
                    "tool": block.get("name"),
                    "args_hash": hashlib.sha256(
                        json.dumps(block.get("input", {}), sort_keys=True).encode()
                    ).hexdigest()[:12],
                    "input": block.get("input", {}),
                })

    return {
        "exit_code": (-1 if timed_out else proc.returncode),
        "stderr_tail": (stderr or "")[-800:],
        "duration_s": round(duration, 2),
        "tool_calls": tool_calls,
        "n_tool_calls": len(tool_calls),
    }


# --- diff --------------------------------------------------------------------


def diff_trajectories(a: dict, b: dict) -> dict:
    """Compare two runs' tool-call sequences. Returns a compact diff dict."""
    a_tools = [tc["tool"] for tc in a["tool_calls"]]
    b_tools = [tc["tool"] for tc in b["tool_calls"]]
    a_set, b_set = set(a_tools), set(b_tools)
    return {
        "a_tool_sequence": a_tools,
        "b_tool_sequence": b_tools,
        "a_only_tools": sorted(a_set - b_set),
        "b_only_tools": sorted(b_set - a_set),
        "common_tool_counts": {
            t: (a_tools.count(t), b_tools.count(t))
            for t in sorted(a_set & b_set)
        },
        "duration_ratio": (
            round(b["duration_s"] / max(a["duration_s"], 0.01), 2)
            if a["duration_s"] and b["duration_s"] else None
        ),
        "n_tool_calls": (a["n_tool_calls"], b["n_tool_calls"]),
        "exit_codes": (a["exit_code"], b["exit_code"]),
    }


# --- main --------------------------------------------------------------------


def main() -> int:
    p = argparse.ArgumentParser(description="probe-runner (Harness² style A/B)")
    p.add_argument("--tasks", type=pathlib.Path, default=HERE / "tasks.jsonl")
    p.add_argument("--variants", default="baseline",
                   help="comma-separated variant JSON paths, or 'baseline' for one no-op")
    p.add_argument("--runs-dir", type=pathlib.Path, default=HERE / "runs")
    p.add_argument("--run-id", default=None, help="defaults to a timestamp")
    p.add_argument("--limit", type=int, default=0, help="0 = all tasks")
    p.add_argument("--only-task", default="",
                   help="comma-separated task ids; runs exactly these, in order")
    p.add_argument("--include-hub", action="store_true",
                   help="include tasks tagged 'hub' (they will still be run "
                        "offline; the scratch has no access to prod relay)")
    p.add_argument("--include-protocol", dest="skip_protocol", action="store_false",
                   help="include mailbox-protocol meta tasks")
    p.set_defaults(skip_protocol=True)
    p.add_argument("--timeout-s", type=int, default=300)
    p.add_argument("--model", default=None,
                   help="model id passed to the headless `claude` (e.g. "
                        "workbuddy/deepseek-v4.1-flash); defaults to the CLI's own")
    p.add_argument("--no-deny", action="store_true",
                   help="do not pass the default disallowedTools deny list "
                        "(device/network/build commands); use only if you "
                        "accept the side-effect risk")
    p.add_argument("--allow-writes", action="store_true",
                   help="let the child use Edit/Write (default: denied). Only "
                        "safe in a disposable VM/account")
    p.add_argument("--scratch-dir", type=pathlib.Path, default=None,
                   help="scratch root (default: a temp dir OUTSIDE the repo, so "
                        "the child cannot discover project settings/AGENTS.md)")
    args = p.parse_args()

    tasks = load_tasks(args.tasks, skip_protocol=args.skip_protocol)
    if not args.include_hub:
        tasks = [t for t in tasks if "hub" not in t["tags"]]
    if args.only_task:
        wanted = [w.strip() for w in args.only_task.split(",") if w.strip()]
        by_id = {t["task_id"]: t for t in tasks}
        missing = [w for w in wanted if w not in by_id]
        if missing:
            print(f"unknown task ids after filters: {missing}", file=sys.stderr)
            return 2
        tasks = [by_id[w] for w in wanted]
    elif args.limit:
        tasks = tasks[: args.limit]

    if args.variants == "baseline":
        variants = [{"name": "baseline", "system_prompt_append": "", "env": {}}]
    else:
        variants = [load_variant(pathlib.Path(v.strip())) for v in args.variants.split(",")]
    for v in variants:
        deny = [] if args.no_deny else list(DEFAULT_DENY_TOOLS)
        if not args.allow_writes:
            deny += WRITE_TOOLS
        v["disallow_tools"] = deny

    run_id = args.run_id or time.strftime("%Y%m%d-%H%M%S")
    run_dir = args.runs_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    scratch_root = args.scratch_dir or (pathlib.Path(tempfile.gettempdir()) / "minis-probe" / run_id)
    scratch_root.mkdir(parents=True, exist_ok=True)

    print(f"run: {run_id}  tasks={len(tasks)}  variants={[v['name'] for v in variants]}", file=sys.stderr)

    manifest = {
        "run_id": run_id,
        "tasks_file": str(args.tasks),
        "tasks_file_sha256": hashlib.sha256(args.tasks.read_bytes()).hexdigest(),
        "variants": [{**v, "system_prompt_append_sha256": hashlib.sha256(
            v["system_prompt_append"].encode()).hexdigest()} for v in variants],
        "task_ids": [t["task_id"] for t in tasks],
        "include_hub": args.include_hub,
        "allow_writes": args.allow_writes,
        "model": args.model,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    (run_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    report_lines = [f"# probe run {run_id}\n",
                    f"tasks={len(tasks)}  variants={[v['name'] for v in variants]}\n"]

    for task in tasks:
        report_lines.append(f"\n## {task['task_id']}  [{', '.join(task['tags']) or '-'}]  sev={task['severity']}\n")
        report_lines.append(f"*{task['summary']}*\n")
        results: dict[str, dict] = {}
        for variant in variants:
            tag = variant["name"]
            scratch = scratch_root / f"{task['task_id']}__{tag}"
            out_jsonl = run_dir / f"{task['task_id']}__{tag}.json"
            setup_scratch(scratch, task)
            sys.stderr.write(f"  running {task['task_id']} x {tag} ...\n")
            result = run_claude(variant=variant, scratch=scratch,
                                out_jsonl=out_jsonl, timeout_s=args.timeout_s,
                                model=args.model)
            results[tag] = result
            (run_dir / f"{task['task_id']}__{tag}.tools.jsonl").write_text(
                "".join(json.dumps(tc, ensure_ascii=False) + "\n" for tc in result["tool_calls"]),
                encoding="utf-8")
            report_lines.append(
                f"- `{tag}`: exit={result['exit_code']}  tools={result['n_tool_calls']}  "
                f"duration={result['duration_s']}s")
            if result["exit_code"] != 0 or result["n_tool_calls"] == 0:
                stderr_tail = result["stderr_tail"].replace("\n", " ⏎ ")
                report_lines.append(f"-   stderr: `{stderr_tail}`")

        if len(results) == 2:
            a_name, b_name = list(results.keys())
            diff = diff_trajectories(results[a_name], results[b_name])
            (run_dir / f"{task['task_id']}__diff.json").write_text(
                json.dumps(diff, ensure_ascii=False, indent=2), encoding="utf-8")
            report_lines.append(f"- **diff**: a_only={diff['a_only_tools']}  "
                                f"b_only={diff['b_only_tools']}  "
                                f"dur_ratio={diff['duration_ratio']}")

    (run_dir / "report.md").write_text("\n".join(report_lines), encoding="utf-8")
    print(f"\nwrote {run_dir}/report.md", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
