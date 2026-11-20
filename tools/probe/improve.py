"""Phase 2 of the self-improvement loop: cards -> clusters -> proposal worksheets.

Input: issue cards pulled off the device by `pull_cards.py`.
Output: a ranked dossier + one proposal worksheet per cluster.

The clustering is deliberately deterministic — no model in the loop — so the
same card set always yields the same proposals and the ranking can be
audited. The *rule text* is left blank in the worksheets: that is the part a
model (or a human) has to write, and the part that then gets A/B-tested with
`run_device.py --system-overlay`.

Severity weights: blocker=5, warn=2, info=0.5.
Score per cluster = sum of member weights; ties break on count, then on the
worst single severity.

    python tools/probe/improve.py --cards <temp>/minis-self-improve/cards
    python tools/probe/improve.py --cards <dir> --out <dir> --top 3
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tempfile
from collections import defaultdict

SEVERITY_WEIGHT = {"blocker": 5.0, "warn": 2.0, "info": 0.5}
SEVERITY_ORDER = {"blocker": 0, "warn": 1, "info": 2}

# IDF-weighted token overlap needed to merge two groups into one proposal.
MERGE_THRESHOLD = 0.45

# Which lever can plausibly fix a cluster of this surface. The consumer does
# not decide the fix; it decides *where a fix could live*, so the worksheet
# tells the writer which file to open.
SURFACE_LEVERS = {
    "system_prompt": ("rules", "/var/minis/harness/rules.md",
                      "追加一段行为规则（可 A/B）"),
    "tool_desc": ("tool", "agent tool schema/description in source",
                  "改工具描述/返回值语义（代码改；也可先用规则层约束行为）"),
    "skill": ("skill", "/var/minis/skills/<name>/SKILL.md（设备上）",
              "改技能文档里的命令/步骤"),
    "guardrail": ("code", "session store / observability",
                  "补可观测性或护栏（代码改）"),
    "threshold": ("code", "agent loop / config",
                  "调阈值（代码或设置）"),
}

CJK = r"一-鿿"
TOKEN_RE = re.compile(rf"[{CJK}]+|[A-Za-z_][A-Za-z0-9_]*")


def tokens(text: str) -> set[str]:
    """CJK bigrams + latin words — enough to fingerprint a symptom."""
    out: set[str] = set()
    for raw in TOKEN_RE.findall(text or ""):
        if re.match(rf"[{CJK}]+$", raw):
            if len(raw) == 1:
                out.add(raw)
            for i in range(len(raw) - 1):
                out.add(raw[i:i + 2])
        else:
            out.add(raw.lower())
    return out


def load_cards(card_dir: pathlib.Path) -> tuple[list[dict], dict | None]:
    cards, summary = [], None
    for path in sorted(card_dir.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        if "date" in data and "symptom" not in data:
            summary = data
            continue
        if "symptom" in data:
            cards.append({**data, "_file": path.name})
    return cards, summary


def validate(cards: list[dict]) -> list[str]:
    """Return schema problems (a card that does not fit is still counted, but
    the gap is reported so the skill can be tightened)."""
    problems = []
    for card in cards:
        missing = [f for f in ("id", "ts", "symptom", "severity", "surface") if not card.get(f)]
        if missing:
            problems.append(f"{card.get('_file')}: missing {','.join(missing)}")
        if card.get("severity") and card["severity"] not in SEVERITY_WEIGHT:
            problems.append(f"{card.get('_file')}: unknown severity {card['severity']!r}")
        evidence = card.get("evidence") or {}
        if not evidence.get("excerpt"):
            problems.append(f"{card.get('_file')}: evidence.excerpt empty")
    return problems


def cluster(cards: list[dict]) -> list[dict]:
    """Group cards by surface+tool, then merge groups whose symptoms overlap.

    The overlap test is IDF-weighted: a token that appears in only one card
    (a distinctive marker such as `arg_key` or `workspace`) is strong
    evidence of a shared root cause, while a token every card uses
    ("失败", "命令") carries almost none. Plain token Jaccard merged
    unrelated symptoms and produced one proposal that really needed three
    separate fixes.
    """
    buckets: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for card in cards:
        tool = (card.get("evidence") or {}).get("tool") or "unknown"
        buckets[(card.get("surface") or "unknown", tool)].append(card)

    df: dict[str, int] = defaultdict(int)
    for card in cards:
        for token in tokens(card.get("symptom", "")):
            df[token] += 1
    total = max(len(cards), 1)

    def idf(token: str) -> float:
        import math
        return math.log((total + 1) / (df.get(token, 0) + 1)) + 1.0

    def weight(bag: set[str]) -> float:
        return sum(idf(t) for t in bag)

    # One group per card. Bucketing by (surface, tool) first was wrong: three
    # unrelated skill cards all used `shell_execute` and landed in one bucket,
    # so the proposal asked for three different fixes at once.
    groups = []
    for card in cards:
        tool = (card.get("evidence") or {}).get("tool") or "unknown"
        groups.append({"surface": card.get("surface") or "unknown", "tool": tool,
                       "cards": [card], "tokens": tokens(card.get("symptom", ""))})

    merged, used = [], set()
    for i, group in enumerate(groups):
        if i in used:
            continue
        current = dict(group)
        for j in range(i + 1, len(groups)):
            if j in used:
                continue
            other = groups[j]
            if other["surface"] != current["surface"]:
                continue
            inter = current["tokens"] & other["tokens"]
            union = current["tokens"] | other["tokens"]
            if union and weight(inter) / weight(union) >= MERGE_THRESHOLD:
                current["cards"] += other["cards"]
                current["tokens"] = union
                used.add(j)
        merged.append(current)
    used.add(i)

    for group in merged:
        group["count"] = len(group["cards"])
        group["severities"] = [c.get("severity", "info") for c in group["cards"]]
        group["score"] = sum(SEVERITY_WEIGHT.get(s, 0.5) for s in group["severities"])
        group["worst"] = sorted(group["severities"], key=lambda s: SEVERITY_ORDER.get(s, 3))[0]
        group["sessions"] = sorted({(c.get("evidence") or {}).get("session", "?")[:8]
                                     for c in group["cards"]})
        group.pop("tokens", None)
    merged.sort(key=lambda g: (-g["score"], -g["count"], SEVERITY_ORDER.get(g["worst"], 3)))
    for idx, group in enumerate(merged, 1):
        group["id"] = f"C{idx:02d}"
    return merged


def worksheet(group: dict) -> str:
    lever, target, action = SURFACE_LEVERS.get(
        group["surface"], ("code", "unknown", "需要人判断落点"))
    lines = [
        f"# {group['id']} · {group['surface']} / {group['tool']}",
        "",
        f"- 命中问题单：{group['count']} 张",
        f"- 级别分布：{', '.join(f'{s}×{group['severities'].count(s)}' for s in sorted(set(group['severities']), key=lambda s: SEVERITY_ORDER.get(s, 3)))}",
        f"- 评分：{group['score']:.1f}（最严重 {group['worst']}）",
        f"- 涉及会话：{', '.join(group['sessions'])}",
        f"- 落点建议：**{lever}** → {target}",
        f"- 动作：{action}",
        "",
        "## 证据",
        "",
    ]
    for card in group["cards"]:
        excerpt = ((card.get("evidence") or {}).get("excerpt") or "").strip()
        lines += [
            f"### {card.get('id')} · {card.get('severity')}",
            "",
            f"- 症状：{card.get('symptom')}",
            f"- 复现：{card.get('repro')}",
            f"- 原文摘录：{excerpt[:600]}",
            "",
        ]
    lines += [
        "## 候选规则文本（由人或模型填写）",
        "",
        "> 留空表示还没写。填完后用下面的 A/B 命令验证，"
        "行为变好且没有回归才写进 rules.md。",
        "",
        "```text",
        "",
        "```",
        "",
        "## A/B 验证",
        "",
        "```bash",
        f"python tools/probe/run_device.py --only-task <probe-task-id> \\",
        f"    --system-overlay <overlay-file> --label {group['id'].lower()}",
        "python tools/probe/compare_runs.py <baseline-run> <overlay-run>",
        "```",
        "",
    ]
    return "\n".join(lines)


def dossier(groups: list[dict], cards: list[dict], summary: dict | None,
             problems: list[str]) -> str:
    lines = [
        "# 自我改进 · 问题单档案",
        "",
        f"- 问题单：{len(cards)} 张",
        f"- 归并后：{len(groups)} 组",
        f"- 总评分：{sum(g['score'] for g in groups):.1f}",
        "",
    ]
    if summary:
        lines += [
            "## 当天覆盖情况",
            "",
            f"- 审计会话：{', '.join(summary.get('covered_sessions_with_messages', []))}",
            f"- 跳过：{json.dumps(summary.get('skipped', {}), ensure_ascii=False)}",
            "",
        ]
        blind = summary.get("blind_spots") or []
        if blind:
            lines += ["## 盲区", ""] + [f"- {b}" for b in blind] + [""]
    if problems:
        lines += ["## 问题单格式问题", ""] + [f"- {p}" for p in problems] + [""]
    lines += ["## 排名", "", "| 组 | 落点 | 张数 | 级别 | 评分 | 主题 |", "|---|---|---|---|---|---|"]
    for group in groups:
        lever = SURFACE_LEVERS.get(group["surface"], ("?",))[0]
        title = group["cards"][0].get("symptom", "")[:48].replace("|", "／")
        lines.append(f"| {group['id']} | {lever} | {group['count']} | {group['worst']} | "
                     f"{group['score']:.1f} | {title} |")
    lines += ["", "## 分组明细", ""]
    for group in groups:
        lines += [
            f"### {group['id']} · {group['surface']} / {group['tool']} "
            f"（{group['count']} 张，{group['score']:.1f} 分）",
            "",
        ]
        for card in group["cards"]:
            lines.append(f"- **{card.get('id')}** [{card.get('severity')}] "
                         f"{card.get('symptom')}")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    default_cards = pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "cards"
    ap = argparse.ArgumentParser(description="cluster self-improve cards into proposals")
    ap.add_argument("--cards", default=str(default_cards), help="directory with pulled cards")
    ap.add_argument("--out", default=None, help="output directory (default: <temp>/minis-self-improve/<date>)")
    ap.add_argument("--top", type=int, default=0, help="only write worksheets for the top N clusters")
    ap.add_argument("--allow-in-repo", action="store_true")
    args = ap.parse_args()

    card_dir = pathlib.Path(args.cards).resolve()
    if not card_dir.is_dir():
        print(f"no card directory: {card_dir}")
        return 1

    cards, summary = load_cards(card_dir)
    if not cards:
        print(f"no issue cards in {card_dir}")
        return 1

    problems = validate(cards)
    groups = cluster(cards)

    out_dir = pathlib.Path(args.out).resolve() if args.out else \
        pathlib.Path(tempfile.gettempdir()) / "minis-self-improve" / "proposals"
    repo_root = pathlib.Path(__file__).resolve().parent.parent.parent
    if repo_root in out_dir.parents and not args.allow_in_repo:
        print(f"refusing to write session-derived dossier into the repo: {out_dir}")
        return 2
    (out_dir / "worksheets").mkdir(parents=True, exist_ok=True)

    (out_dir / "dossier.md").write_text(
        dossier(groups, cards, summary, problems), encoding="utf-8")
    (out_dir / "clusters.json").write_text(
        json.dumps({"cards": len(cards), "clusters": groups, "schema_problems": problems},
                   ensure_ascii=False, indent=2), encoding="utf-8")

    for group in groups[: args.top] if args.top else groups:
        (out_dir / "worksheets" / f"{group['id']}.md").write_text(
            worksheet(group), encoding="utf-8")

    for group in groups:
        lever = SURFACE_LEVERS.get(group["surface"], ("?",))[0]
        print(f"{group['id']}  score={group['score']:.1f}  n={group['count']}  "
              f"worst={group['worst']}  lever={lever}  {group['surface']}/{group['tool']}")
    print(f"-> {out_dir}/dossier.md, {out_dir}/clusters.json, "
          f"{out_dir}/worksheets/*.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
