"""Phase 2, step 2: turn issue-card clusters into candidate harness rules.

`improve.py` decides *what* to fix (clustering + ranking, deterministic).
This step decides *what to write* — a candidate rule for the runtime rules
file — using a headless `claude -p` run per cluster (the same isolation
pattern as `run.py`: scratch outside the repo, no MCP, minimal tools).

The output is a proposal, not a change: it lands next to the evidence in the
temp proposals directory. Nothing is written into the device's rules.md until
the proposal has survived an A/B run (`run_device.py --system-overlay`).

    python tools/probe/improve_propose.py --proposals <temp>/minis-self-improve/proposals
    python tools/probe/improve_propose.py --proposals ... --export tools/probe/variants/self-improve
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_device import DEFAULT_PORT, DEFAULT_SERIAL, Device, forward  # noqa: E402

SYSTEM_PROMPT = """你是 MiniPilot 行为规则的作者。

MiniPilot 是一个手机上的 agent 应用。它的「行为规则」放在运行时文件
/var/minis/harness/rules.md 里（Android/iOS 共用），内容是中文祈使句，
每条 1-3 行，规定 agent「应该怎么做」，不是解释原理。

你会拿到一组失败证据（issue card），它们来自真实会话的工具轨迹。请判断
这些失败里，哪些**可以靠一条行为规则避免**，哪些必须改代码/工具描述/
技能文档（这类不要硬凑成规则）。

判断标准：
- 规则能解决的是「agent 不知道/没被要求做某件事」，例如：工具返回值语义
  容易被误读、异步操作缺少完成判据、命令写法在沙箱里不可用、产出落在
  别人看不到的目录。
- 规则解决不了的是「工具本身有能力缺陷」「产品逻辑 bug」——这些要写进
  `code_fix` 而不是 `rule_text`。

要求：
1. rule_text 只写给 agent 看的行为约束，不写原因分析、不写具体会话 id、
   不写用户原话、不写日期。要可执行、可检查。
2. 规则不能与已有规则重复（会给你现有 rules.md 全文）。
3. 最多 2 条规则，每条单独一行，句末不加句号以外的标点。
4. probe_task 写一个能检出这条规则是否生效的探针任务。**优先执行型**：agent
   要真的做一件事并报告可核对的观测（例如「写入 /tmp/x.txt 后如何确认它还在」
   「读完页面后用什么核对实际位置」）。知识问答题模型光靠已有知识就能答对，
   测不出规则生效与否，A/B 只会得到假的 no-diff。任务要无副作用（不装卸应用、
   不改设置、不发消息、不删用户数据），30 字以内。
   probe_kind 取 "execute"（真的动手做）或 "read"（只读回答）。
5. rule_text 和 probe_task 一律用**中文**（rules.md 是中文规则集）。
6. 只输出 JSON，不要 markdown 代码块围栏，不要多余文字。

输出格式：
{"title":"8字以内的标题","verdict":"rule|code","hypothesis":"根因一句话",
 "rule_text":["规则1","规则2"],"probe_task":"探针任务","probe_kind":"execute|read",
 "why_not_rule":"若 verdict=code，说明为什么规则解决不了"}
"""

JSON_RE = re.compile(r"\{.*\}", re.S)


def parse_json_blob(text: str) -> dict | None:
    if not text:
        return None
    match = JSON_RE.search(text)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return None


def run_claude(prompt: str, scratch: pathlib.Path, timeout_s: int,
               model: str | None) -> tuple[str, int]:
    """Headless `claude -p`; returns (last assistant text, exit code)."""
    scratch.mkdir(parents=True, exist_ok=True)
    cmd = [
        "claude", "-p", prompt,
        "--output-format", "stream-json",
        "--verbose",
        "--allowedTools", "Read",
        "--strict-mcp-config",
        "--max-turns", "4",
    ]
    if model:
        cmd.extend(["--model", model])

    proc = subprocess.Popen(cmd, cwd=str(scratch), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, encoding="utf-8")
    try:
        stdout, stderr = proc.communicate(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        proc.kill()
        stdout, stderr = proc.communicate()

    texts: list[str] = []
    for line in (stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "assistant":
            for block in (event.get("message") or {}).get("content", []):
                if block.get("type") == "text" and block.get("text"):
                    texts.append(block["text"])
        elif event.get("type") == "result" and event.get("result"):
            texts.append(event["result"])
    if not texts and stderr:
        return stderr.strip()[:500], proc.returncode
    return texts[-1], proc.returncode


def fetch_rules(dev: Device | None) -> str:
    if dev is None:
        return ""
    try:
        out = dev.call("debug.shellExecute",
                       {"command": "cat /var/minis/harness/rules.md 2>/dev/null",
                        "timeout": 30}, timeout=60).get("output", "")
        return out or ""
    except Exception:  # noqa: BLE001
        return ""


def main() -> int:
    default_prop = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "proposals"
    ap = argparse.ArgumentParser(description="propose harness rules from issue-card clusters")
    ap.add_argument("--proposals", default=str(default_prop), help="directory with clusters.json + worksheets")
    ap.add_argument("--export", default=None,
                    help="directory to export the rule texts into (e.g. tools/probe/variants/self-improve)")
    ap.add_argument("--top", type=int, default=3, help="how many clusters to run the proposer on")
    ap.add_argument("--only", default=None,
                    help="comma-separated cluster ids to run instead of --top (e.g. C05,C08)")
    ap.add_argument("--timeout", type=int, default=300, help="per-cluster headless timeout")
    ap.add_argument("--model", default=None, help="model id for the headless claude")
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--no-rules", action="store_true", help="do not fetch the live rules.md as style context")
    ap.add_argument("--allow-in-repo", action="store_true")
    args = ap.parse_args()

    prop_dir = pathlib.Path(args.proposals).resolve()
    clusters_path = prop_dir / "clusters.json"
    if not clusters_path.is_file():
        print(f"no clusters.json in {prop_dir}")
        return 1

    payload = json.loads(clusters_path.read_text(encoding="utf-8"))
    clusters = payload.get("clusters") or []

    rules = ""
    if not args.no_rules:
        try:
            forward(args.serial, args.port)
            rules = fetch_rules(Device(port=args.port))
        except Exception as exc:  # noqa: BLE001
            print(f"(no live rules.md context: {exc})")
    if rules:
        print(f"using live rules.md as context ({len(rules)} chars)")

    scratch = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "propose-scratch"
    out_dir = prop_dir / "candidates"
    out_dir.mkdir(parents=True, exist_ok=True)

    results = []
    if args.only:
        wanted = {c.strip().upper() for c in args.only.split(",") if c.strip()}
        selected = [c for c in clusters if c["id"].upper() in wanted]
    else:
        selected = clusters[: args.top]
    for cluster in selected:
        worksheet = (prop_dir / "worksheets" / f"{cluster['id']}.md").read_text(encoding="utf-8")
        body = [SYSTEM_PROMPT, "", "## 现有 rules.md（避免重复）", "", rules or "（未取到）", "",
                f"## 待分析的一组失败证据 {cluster['id']}", "", worksheet]
        prompt = "\n".join(body)

        started = time.time()
        text, code = "", 1
        parsed = None
        for attempt in range(2):
            text, code = run_claude(prompt, scratch / cluster["id"], args.timeout, args.model)
            parsed = parse_json_blob(text)
            if parsed is not None:
                break
            print(f"{cluster['id']}: attempt {attempt + 1} produced no JSON (exit {code}), retrying")
        (out_dir / f"{cluster['id']}.raw.txt").write_text(text or "", encoding="utf-8")
        if parsed is None:
            print(f"{cluster['id']}: proposer returned no JSON (exit {code}); raw saved to "
                  f"{out_dir / (cluster['id'] + '.raw.txt')}")
            continue

        parsed["cluster"] = cluster["id"]
        parsed["surface"] = cluster["surface"]
        parsed["tool"] = cluster["tool"]
        parsed["score"] = cluster["score"]
        parsed["count"] = cluster["count"]
        parsed["worst"] = cluster["worst"]
        parsed["duration_s"] = round(time.time() - started, 1)
        parsed["exit_code"] = code
        (out_dir / f"{cluster['id']}.json").write_text(
            json.dumps(parsed, ensure_ascii=False, indent=2), encoding="utf-8")

        rules_text = parsed.get("rule_text") or []
        if isinstance(rules_text, str):
            rules_text = [rules_text]
        parsed["rule_text"] = [r for r in rules_text if r]
        overlay = "\n".join(parsed["rule_text"])
        if overlay:
            (out_dir / f"{cluster['id']}.md").write_text(overlay + "\n", encoding="utf-8")
        results.append(parsed)
        print(f"{cluster['id']}: verdict={parsed.get('verdict')} "
              f"rules={len(parsed['rule_text'])} {parsed.get('title', '')} "
              f"({parsed['duration_s']}s)")

    (prop_dir / "candidates.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.export:
        export = pathlib.Path(args.export).resolve()
        repo_root = HERE.parent.parent.resolve()
        if repo_root in export.parents and not args.allow_in_repo:
            print(f"refusing to export into the repo: {export}")
            return 2
        export.mkdir(parents=True, exist_ok=True)
        for item in results:
            overlay = "\n".join(item.get("rule_text") or [])
            if overlay:
                (export / f"{item['cluster']}.md").write_text(overlay + "\n", encoding="utf-8")
        print(f"exported {len(results)} candidate rule file(s) -> {export}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
