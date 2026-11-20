"""Compare two device runs of the same task set (Harness² A/B summary).

    python tools/probe/compare_runs.py tools/probe/runs/baseline-10-device tools/probe/runs/overlay-10-device

Each run dir was produced by run_device.py with one variant. This tool emits:
- per task: tool-sequence diff (as run.diff_trajectories) + answer metrics
  (char counts, length ratio, text similarity) and a side-by-side verdict;
- a summary table at the end.

For answer-only probes (no tool calls) the answer text IS the behavior, so the
text metrics are the primary signal; for tool-heavy probes the trajectory is.
"""

from __future__ import annotations

import argparse
import difflib
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run import diff_trajectories  # noqa: E402


def load_tools(run_dir: pathlib.Path, task_id: str, variant: str) -> list[dict] | None:
    path = run_dir / f"{task_id}__{variant}.tools.jsonl"
    if not path.exists():
        return None
    return [json.loads(l) for l in path.open(encoding="utf-8") if l.strip()]


def load_answer(run_dir: pathlib.Path, task_id: str, variant: str) -> str | None:
    path = run_dir / f"{task_id}__{variant}.messages.json"
    if not path.exists():
        return None
    messages = json.loads(path.read_text(encoding="utf-8"))
    answers = [m.get("content", "") for m in messages if m.get("role") == "assistant"]
    return answers[-1].strip() if answers else ""


def main() -> int:
    p = argparse.ArgumentParser(description="compare two device probe runs")
    p.add_argument("run_a", type=pathlib.Path)
    p.add_argument("run_b", type=pathlib.Path)
    p.add_argument("--variant-a", default="baseline")
    p.add_argument("--variant-b", default="overlay")
    p.add_argument("--out", type=pathlib.Path, default=None,
                   help="defaults to <run_b>/compare-vs-<variant_a>.md")
    args = p.parse_args()

    manifest_b = json.loads((args.run_b / "manifest.json").read_text(encoding="utf-8"))
    task_ids = manifest_b.get("task_ids") or []

    lines = [f"# A/B compare: {args.run_a.name} ({args.variant_a}) vs "
             f"{args.run_b.name} ({args.variant_b})\n"]
    rows = []
    for tid in task_ids:
        ta = load_tools(args.run_a, tid, args.variant_a)
        tb = load_tools(args.run_b, tid, args.variant_b)
        aa = load_answer(args.run_a, tid, args.variant_a)
        ab = load_answer(args.run_b, tid, args.variant_b)
        if aa is None or ab is None:
            lines.append(f"\n## {tid}\n- missing artifact (a={aa is not None}, b={ab is not None})\n")
            continue

        diff = diff_trajectories(
            {"tool_calls": ta or [], "n_tool_calls": len(ta or []), "duration_s": 1, "exit_code": 0},
            {"tool_calls": tb or [], "n_tool_calls": len(tb or []), "duration_s": 1, "exit_code": 0},
        )
        ratio = round(difflib.SequenceMatcher(None, aa, ab).ratio(), 3)
        len_a, len_b = len(aa), len(ab)
        len_ratio = round(len_b / len_a, 2) if len_a else None

        lines.append(f"\n## {tid}\n")
        lines.append(f"- tool seq A: {diff['a_tool_sequence']}\n")
        lines.append(f"- tool seq B: {diff['b_tool_sequence']}\n")
        lines.append(f"- answer chars: A={len_a}  B={len_b}  ratio={len_ratio}\n")
        lines.append(f"- answer similarity: {ratio}\n")
        lines.append(f"- answer B head: {ab[:180].replace(chr(10), ' ')}\n")
        rows.append((tid, len_a, len_b, len_ratio, ratio, diff["n_tool_calls"]))

    lines.append("\n## Summary\n\n| task | chars A | chars B | len ratio | similarity | tools A/B |\n")
    lines.append("|---|---|---|---|---|---|\n")
    for tid, la, lb, lr, sim, tc in rows:
        lines.append(f"| {tid} | {la} | {lb} | {lr} | {sim} | {tc[0]}/{tc[1]} |\n")

    out = args.out or (args.run_b / f"compare-vs-{args.variant_a}.md")
    out.write_text("".join(lines), encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
