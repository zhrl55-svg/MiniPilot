"""Phase 2, step 3: A/B a candidate rule on the real device.

For each candidate produced by `improve_propose.py`:

  1. run the probe task on the phone with no overlay          (baseline)
  2. run the same probe with the rule injected at the system
     prompt level (`systemPromptAppend`, zero rebuild)          (overlay)
  3. let an independent judge compare the two answers

The judge is a separate headless `claude -p` call that sees the rule and
both answers but not which one is which side, and it is told to call
"no-diff" when the answers are substantively the same. That matters: the
first candidate we tested (search-error diagnosis) was already handled by
SOUL.md, so the honest verdict was "no diff" and the rule was dropped.

Verdicts:
  adopt    - the overlay answer follows the rule and the baseline did not
  no-diff  - both answers behave the same; the rule adds nothing
  regress  - the overlay answer is worse than the baseline

    python tools/probe/improve_ab.py --candidates <proposals>/candidates --only C06,C07
"""

from __future__ import annotations

import argparse
import json
import pathlib
import random
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

JUDGE_SYSTEM = """你是 A/B 裁判。你会看到一条「候选行为规则」和两份对同一探针问题的回答（标注为回答 1 和回答 2）。

判断这条规则是否真的改变了行为：
- adopt：只有一份回答体现了规则要求的内容，另一份没有；且两份没有实质矛盾。
- no-diff：两份回答实质相同（说法不同也算相同），或者两份都体现了规则、或者两份都没体现——规则没有带来可观察的行为变化。
- regress：体现规则的那一份更差（更啰嗦、跑题、给出错误结论、或违反现有规则）。

注意：
- 回答 1 和回答 2 的先后顺序是随机的，与哪份更优无关。
- 不要因为规则存在就偏向某一侧；先判断两份回答实质是否不同。
- 措辞更啰嗦不算改进；只把「结论正确性/是否满足规则要求」算改进。

只输出 JSON：
{"verdict":"adopt|no-diff|regress","reason":"一句话","follows_1":true,"follows_2":false}
"""


def run_probe(*, prompt: str, label: str, runs_dir: pathlib.Path, overlay: str | None,
              model_entry: str | None, max_s: int, serial: str | None) -> dict:
    cmd = [sys.executable, str(HERE / "run_device.py"),
           "--prompt", prompt, "--label", label,
           "--runs-dir", str(runs_dir), "--max-s", str(max_s)]
    if model_entry:
        cmd += ["--model-entry", model_entry]
    if overlay:
        cmd += ["--system-overlay", overlay]
    if serial:
        cmd += ["--serial", serial]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    # run_device.py nests a timestamped directory under --runs-dir, so the
    # transcript is not directly in runs_dir.
    matches = sorted(runs_dir.rglob(f"{label}__baseline.messages.json"))
    messages = matches[-1] if matches else None
    answer = ""
    if messages is not None and messages.is_file():
        data = json.loads(messages.read_text(encoding="utf-8"))
        assistants = [m for m in data if m.get("role") == "assistant"]
        if assistants:
            answer = "".join(
                p.get("value", "") for p in assistants[-1].get("parts", [])
                if p.get("type") == "text")
    return {
        "label": label,
        "answer": answer,
        "exit_code": proc.returncode,
        "stderr": (proc.stderr or "")[-400:],
        "run_dir": str(runs_dir),
        "transcript": str(messages) if messages else None,
    }


def judge(rule_text: str, probe_task: str, answer_a: str, answer_b: str,
          timeout_s: int, model: str | None) -> tuple[dict | None, bool, str]:
    """Judge the two answers. The order shown to the judge is randomised, and
    the returned verdict is mapped back to (baseline, overlay) — otherwise the
    judge learns that "answer 2" is always the one with the rule and starts
    favouring it."""
    swapped = random.random() < 0.5
    first, second = (answer_b, answer_a) if swapped else (answer_a, answer_b)
    body = [
        JUDGE_SYSTEM, "",
        "## 候选规则", "", rule_text, "",
        "## 探针问题", "", probe_task, "",
        "## 回答 1", "", first or "(空)", "",
        "## 回答 2", "", second or "(空)", "",
    ]
    prompt = "\n".join(body)
    scratch = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "judge-scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    cmd = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--allowedTools", "Read", "--strict-mcp-config", "--max-turns", "3"]
    if model:
        cmd += ["--model", model]
    proc = subprocess.run(cmd, cwd=str(scratch), capture_output=True, text=True,
                          encoding="utf-8", timeout=timeout_s + 60)
    texts = []
    for line in (proc.stdout or "").splitlines():
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
    if not texts:
        return None, swapped, (proc.stdout or "")[-1500:]
    blob = texts[-1]
    start, end = blob.find("{"), blob.rfind("}")
    if start < 0 or end < start:
        return None, swapped, blob[-1500:]
    try:
        verdict = json.loads(blob[start:end + 1])
    except json.JSONDecodeError:
        return None, swapped, blob[-1500:]
    follows_first = bool(verdict.get("follows_1"))
    follows_second = bool(verdict.get("follows_2"))
    verdict["baseline_follows_rule"] = follows_second if swapped else follows_first
    verdict["overlay_follows_rule"] = follows_first if swapped else follows_second
    return verdict, swapped, blob[-1500:]


def main() -> int:
    default_cand = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "proposals" / "candidates"
    ap = argparse.ArgumentParser(description="A/B candidate rules on the device")
    ap.add_argument("--candidates", default=str(default_cand))
    ap.add_argument("--candidate", default=None,
                    help="path to a single candidate JSON (human-written candidates are allowed)")
    ap.add_argument("--only", default=None, help="comma-separated cluster ids")
    ap.add_argument("--out", default=None, help="output dir (default: <proposals>/ab)")
    ap.add_argument("--model-entry", default=None, help="model entry id pinned on the device")
    ap.add_argument("--max-s", type=int, default=300)
    ap.add_argument("--serial", default=None, help="adb serial (default: run_device's own)")
    ap.add_argument("--judge-timeout", type=int, default=240)
    ap.add_argument("--judge-model", default=None)
    ap.add_argument("--rejudge", action="store_true",
                    help="reuse the device answers already stored in ab.json and only re-run the judge")
    args = ap.parse_args()

    cand_dir = pathlib.Path(args.candidates).resolve()
    out_dir = pathlib.Path(args.out).resolve() if args.out else cand_dir.parent / "ab"
    out_dir.mkdir(parents=True, exist_ok=True)

    wanted = {c.strip().upper() for c in args.only.split(",")} if args.only else None
    if args.candidate:
        paths = [pathlib.Path(args.candidate)]
    else:
        paths = sorted(cand_dir.glob("*.json"))
    reports = []
    for path in paths:
        cand = json.loads(path.read_text(encoding="utf-8"))
        cluster = cand.get("cluster") or path.stem
        if wanted and cluster.upper() not in wanted:
            continue
        rules = cand.get("rule_text") or []
        if isinstance(rules, str):
            rules = [rules]
        rules = [r for r in rules if r]
        probe = cand.get("probe_task") or ""
        if not rules or not probe:
            print(f"{cluster}: skipped (verdict={cand.get('verdict')}, "
                  f"rules={len(rules)}, probe={'yes' if probe else 'no'})")
            continue

        overlay = "\n".join(rules)
        # An execute-style probe must not carry the "read-only" prefix: the
        # rule is only observable if the agent actually acts.
        prefix = ("真实执行，用 shell_execute 做到：" if cand.get("probe_kind") == "execute"
                  else "只读回答，不要执行任何命令：")
        probe_prompt = f"{prefix}{probe}"
        cluster_dir = out_dir / cluster
        cluster_dir.mkdir(parents=True, exist_ok=True)

        started = time.time()
        previous = {}
        prev_path = cluster_dir / "ab.json"
        reuse = False
        if args.rejudge and prev_path.is_file():
            previous = json.loads(prev_path.read_text(encoding="utf-8"))
            base = previous.get("baseline") or {}
            over = previous.get("overlay") or {}
            reuse = bool(base.get("answer") and over.get("answer"))
            if not reuse:
                print(f"{cluster}: stored answers incomplete, re-running the device probes")
        if not reuse:
            base = run_probe(prompt=probe_prompt, label=f"{cluster.lower()}-base",
                             runs_dir=cluster_dir, overlay=None,
                             model_entry=args.model_entry, max_s=args.max_s,
                             serial=args.serial)
            over = run_probe(prompt=probe_prompt, label=f"{cluster.lower()}-overlay",
                             runs_dir=cluster_dir, overlay=overlay,
                             model_entry=args.model_entry, max_s=args.max_s,
                             serial=args.serial)
        verdict, swapped, judge_raw = judge(overlay, probe, base["answer"], over["answer"],
                                            args.judge_timeout, args.judge_model)
        report = {
            "cluster": cluster,
            "title": cand.get("title"),
            "rule_text": rules,
            "probe_task": probe,
            "baseline": base,
            "overlay": over,
            "judge": verdict,
            "judge_swapped": swapped,
            "judge_raw": judge_raw,
            "duration_s": round(time.time() - started, 1),
        }
        (cluster_dir / "ab.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        (cluster_dir / "ab.md").write_text(render(report), encoding="utf-8")
        reports.append(report)
        v = (verdict or {}).get("verdict", "?")
        print(f"{cluster}: verdict={v} ({report['duration_s']}s) -> {cluster_dir / 'ab.md'}")
        if not verdict:
            print(f"  judge failed: base_exit={base['exit_code']} overlay_exit={over['exit_code']}")

    (out_dir / "ab_summary.json").write_text(
        json.dumps([{"cluster": r["cluster"],
                     "verdict": (r["judge"] or {}).get("verdict"),
                     "reason": (r["judge"] or {}).get("reason")} for r in reports],
                   ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


def render(report: dict) -> str:
    verdict = report.get("judge") or {}
    lines = [
        f"# A/B · {report['cluster']} · {report.get('title') or ''}",
        "",
        f"- 判决：**{verdict.get('verdict', '?')}** — {verdict.get('reason', '')}",
        f"- 探针：{report['probe_task']}",
        "",
        "## 候选规则（注入系统提示词）",
        "",
    ]
    lines += [f"- {r}" for r in report["rule_text"]]
    lines += ["", "## baseline", "", report["baseline"]["answer"][:2000] or "(空)",
              "", "## overlay", "", report["overlay"]["answer"][:2000] or "(空)", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
