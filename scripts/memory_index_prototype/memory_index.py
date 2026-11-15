#!/usr/bin/env python3
"""
Phase 1 memory index prototype (read-only, no model).

Parses the Markdown memory directory (GLOBAL.md, USER.md, YYYY-MM-DD.md),
builds an in-memory BM25 + CJK-bigram + tag index, and compares it with an
entry-level approximation of the current `memory_get` keyword scan.

No app code is touched. No on-device model is used.

Usage:
  python memory_index.py --memory-dir DIR query "magpie 控制台 401" --top 5
  python memory_index.py --memory-dir DIR eval --queries queries.json
  python memory_index.py --memory-dir DIR dump
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

CJK_RE = re.compile(r"[㐀-䶿一-鿿豈-﫿぀-ヿ가-힯]+")
LATIN_RE = re.compile(r"[A-Za-z0-9_]+")
COMMENT_RE = re.compile(r"^<!--\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*-->\s*$")
DATE_FILE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.md$")

# Canonical entity -> aliases. The keys are used as tags.
ENTITY_ALIASES = {
    "magpie": ["magpie", "玛格派"],
    "buntu": ["buntu"],
    "asus": ["asus", "华硕"],
    "tailscale": ["tailscale", "tailnet"],
    "freeform": ["freeform", "小窗", "浮窗"],
    "crlf": ["crlf", "回车换行"],
    "mcp": ["mcp", "minis-mcp-cli"],
    "wechat": ["微信", "wechat"],
    "morning": ["早安", "问候"],
    "stock": ["a股", "股票", "行情"],
    "music": ["音乐", "网易云", "netease"],
    "amap": ["高德", "百度地图", "amap", "导航"],
    "weather": ["天气", "weather"],
    "voice": ["语音", "唤醒", "asr", "sensevoice", "tts", "播报"],
    "guard": ["guard", "审计", "audit", "风险"],
    "skillstore": ["skillstore", "技能轨迹", "轨迹"],
    "virtualscreen": ["虚拟屏", "virtual display", "vdctl"],
    "debug": ["调试", "debugserver", "minis-debug", "debug."],
    "schedule": ["定时任务", "scheduled", "签到", "cron"],
    "ssh": ["ssh", "密钥"],
    "wol": ["wol", "远程唤醒", "开机"],
    "network": ["ping", "icmp", "nc ", "端口", "网络"],
    "input": ["input", "按键", "tab", "dpad", "keyevent"],
    "compose": ["compose", "表单", "输入框"],
    "webview": ["webview", "浏览器", "chrome", "chromium"],
    "google": ["google", "谷歌"],
    "hub": ["hub_call", "hub", "relay"],
    "build": ["构建", "编译", "assemble", "gradle"],
    "memory": ["记忆", "global", "注入", "检索"],
    "phone": ["手机号", "号码", "workbuddy"],
    "candor": ["直言", "附和", "讨好"],
    "confirm": ["确认卡", "确认", "破坏性"],
}

SECTION_IMPORTANCE = {"global": 1.15, "user": 1.10, "daily": 1.00}

# Pure question/function words. Content words (服务/构建/端口/命令/规则/
# 版本/失败/记录…) stay, so "buntu 服务" and "构建 命令" keep their meaning.
STOPWORDS = {
    "怎么", "怎么修", "怎么用", "怎么填", "怎么走", "怎么连", "怎么看",
    "怎么取", "怎么发", "怎么开", "什么", "是什么", "为什么", "在哪",
    "哪些", "哪个", "能不能", "多少", "吗", "呢", "的", "了", "要",
    "有", "我的", "我", "上", "下", "里", "中", "和", "与", "及", "或",
    "是", "不", "会", "就", "把", "被", "在", "到", "从", "对", "为",
    "这个", "那个", "一下", "现在", "目前", "需要", "可以", "应该",
    "什么时候", "时候", "谁", "放在哪", "弹出", "弹", "用什么", "用什么查",
    "找谁", "怎么进",
}

# Suffixes that appear glued to a content word inside one whitespace token.
QUERY_SUFFIXES = [
    "上有哪些", "有哪些", "有什么", "是什么", "为什么", "怎么进", "怎么修",
    "怎么用", "怎么填", "怎么走", "怎么连", "怎么看", "怎么取", "怎么发",
    "怎么开", "在哪里", "放在哪", "什么时候", "了多少", "了什么", "多少行",
    "用什么查", "用什么", "找谁", "了吗",
]

# Small synonym expansion for known terms. This is deliberately manual: it is
# cheaper and more predictable than asking Pocket-0.3B to rewrite queries.
QUERY_EXPANSIONS = {
    "歌源": ["音乐源", "网易云"],
    "小窗": ["freeform", "浮窗"],
    "401": ["magpie", "控制台"],
    "手机号": ["身份", "workbuddy"],
    "验证码": ["短信", "登录"],
    "唤醒词": ["唤醒"],
}


def clean_query(query: str) -> str:
    words: list[str] = []
    for word in query.split():
        if word in STOPWORDS:
            continue
        for suffix in sorted(QUERY_SUFFIXES, key=len, reverse=True):
            if suffix in word:
                word = word.replace(suffix, " ")
        for part in word.split():
            if part and part not in STOPWORDS:
                words.append(part)
    if not words:
        return query
    expanded = list(words)
    for word in words:
        expanded.extend(QUERY_EXPANSIONS.get(word, []))
    return " ".join(expanded)




def tokenize(text: str) -> list[str]:
    """CJK bigrams + Latin/digit words. No external tokenizer."""
    text = text.lower()
    out: list[str] = []
    for m in CJK_RE.finditer(text):
        run = m.group(0)
        if len(run) == 1:
            out.append(run)
        else:
            out.extend(run[i:i + 2] for i in range(len(run) - 1))
    for m in LATIN_RE.finditer(text):
        tok = m.group(0)
        out.append(tok)
        if "_" in tok:
            out.extend(p for p in tok.split("_") if p)
    return out


def tags_for(text: str) -> set[str]:
    low = text.lower()
    out: set[str] = set()
    for canon, aliases in ENTITY_ALIASES.items():
        if any(a.lower() in low for a in aliases):
            out.add(canon)
    return out


def jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    if inter == 0:
        return 0.0
    return inter / (len(a) + len(b) - inter)


def recency_for(kind: str, date_str: str | None) -> float:
    if kind != "daily" or not date_str:
        return 0.9
    try:
        d = date.fromisoformat(date_str)
    except ValueError:
        return 0.5
    days = max(0, (date.today() - d).days)
    return math.exp(-days / 14.0)


@dataclass
class Entry:
    id: str
    file: str
    title: str
    text: str
    kind: str
    date: str | None = None
    tokens: list[str] = field(default_factory=list)
    tf: Counter = field(default_factory=Counter)
    tset: set[str] = field(default_factory=set)
    heading_tset: set[str] = field(default_factory=set)
    tags: set[str] = field(default_factory=set)
    recency: float = 1.0
    importance: float = 1.0

    @property
    def full_text(self) -> str:
        return f"{self.title}\n{self.text}"


def make_entry(file: str, title: str, text: str, kind: str, idx: int,
               date_str: str | None = None) -> Entry:
    full = f"{title}\n{text}"
    toks = tokenize(full)
    heading_text = "\n".join(line[4:] for line in text.splitlines() if line.startswith("### "))
    return Entry(
        id=f"{file}#{idx}",
        file=file,
        title=title.strip() or "（无标题）",
        text=text.strip(),
        kind=kind,
        date=date_str,
        tokens=toks,
        tf=Counter(toks),
        tset=set(toks),
        heading_tset=set(tokenize(heading_text)),
        tags=tags_for(full),
        recency=recency_for(kind, date_str),
        importance=SECTION_IMPORTANCE.get(kind, 1.0),
    )


def parse_sections(path: Path, kind: str) -> list[Entry]:
    raw = path.read_text(encoding="utf-8")
    lines = raw.splitlines()
    out: list[Entry] = []
    cur_title: str | None = None
    cur_body: list[str] = []

    def flush() -> None:
        nonlocal cur_title, cur_body
        title = cur_title if cur_title is not None else "（前言）"
        body = "\n".join(cur_body).strip()
        if title != "（前言）" or body:
            out.append(make_entry(path.name, title, body, kind, len(out)))
        cur_title = None
        cur_body = []

    for line in lines:
        if line.startswith("## "):
            flush()
            cur_title = line[3:].strip()
        else:
            cur_body.append(line)
    flush()
    return out


def parse_daily(path: Path) -> list[Entry]:
    raw = path.read_text(encoding="utf-8")
    date_str = DATE_FILE_RE.match(path.name).group(1) if DATE_FILE_RE.match(path.name) else None
    blocks: list[tuple[str | None, list[str]]] = []
    cur: list[str] = []
    ts: str | None = None
    for line in raw.splitlines():
        m = COMMENT_RE.match(line)
        if m:
            if any(x.strip() for x in cur):
                blocks.append((ts, cur))
            ts = m.group(1)
            cur = []
        else:
            cur.append(line)
    if any(x.strip() for x in cur):
        blocks.append((ts, cur))

    out: list[Entry] = []
    for stamp, block in blocks:
        title = "（无标题）"
        body_lines: list[str] = []
        title_taken = False
        for line in block:
            if not title_taken and line.startswith("## "):
                title = line[3:].strip()
                title_taken = True
            else:
                body_lines.append(line)
        out.append(make_entry(path.name, title, "\n".join(body_lines), "daily", len(out), date_str))
    return out


def load_entries(memory_dir: Path) -> list[Entry]:
    entries: list[Entry] = []
    global_file = memory_dir / "GLOBAL.md"
    if global_file.is_file():
        entries.extend(parse_sections(global_file, "global"))
    user_file = memory_dir / "USER.md"
    if user_file.is_file():
        entries.extend(parse_sections(user_file, "user"))

    daily_files = sorted(
        (p for p in memory_dir.iterdir() if DATE_FILE_RE.match(p.name) and p.is_file()),
        key=lambda p: p.name,
        reverse=True,
    )
    for p in daily_files:
        entries.extend(parse_daily(p))
    return entries


class Index:
    def __init__(self, entries: list[Entry]):
        self.entries = entries
        self.postings: dict[str, dict[int, int]] = defaultdict(dict)
        self.df: Counter = Counter()
        self.doc_len: list[int] = []
        for i, e in enumerate(entries):
            self.doc_len.append(max(1, len(e.tokens)))
            for tok, tf in e.tf.items():
                self.postings[tok][i] = tf
                self.df[tok] += 1
        self.n = len(entries)
        self.avgdl = sum(self.doc_len) / max(1, self.n)

    def bm25(self, qtokens: list[str], i: int) -> float:
        score = 0.0
        for tok in qtokens:
            tf = self.postings.get(tok, {}).get(i, 0)
            if not tf:
                continue
            df = self.df[tok]
            idf = math.log(1.0 + (self.n - df + 0.5) / (df + 0.5))
            dl = self.doc_len[i]
            score += idf * (tf * (1.2 + 1.0)) / (
                tf + 1.2 * (1.0 - 0.75 + 0.75 * dl / self.avgdl)
            )
        return score


@dataclass
class Hit:
    score: float
    entry: Entry
    bm25: float
    jaccard: float
    entity: float


def search(index: Index, query: str, top_k: int = 5) -> list[Hit]:
    q = clean_query(query)
    q_tokens = tokenize(q)
    if not q_tokens:
        return []
    q_unique = list(dict.fromkeys(q_tokens))
    q_set = set(q_tokens)
    q_tags = tags_for(q)
    raw = [index.bm25(q_unique, i) for i in range(index.n)]
    max_raw = max(raw) if raw else 0.0

    ranked: list[Hit] = []
    for i, e in enumerate(index.entries):
        bm = raw[i] / max_raw if max_raw > 0 else 0.0
        jac = jaccard(q_set, e.tset)
        ent = len(q_tags & e.tags) / len(q_tags) if q_tags else 0.0
        title_ov = len(q_set & set(tokenize(e.title))) / len(q_set) if q_set else 0.0
        heading_ov = len(q_set & e.heading_tset) / len(q_set) if q_set else 0.0
        score = (0.46 * bm + 0.18 * jac + 0.14 * ent
                 + 0.08 * title_ov + 0.10 * heading_ov
                 + 0.02 * e.recency + 0.02 * e.importance)
        if score > 0:
            ranked.append(Hit(score, e, bm, jac, ent))

    ranked.sort(key=lambda h: (-h.score, h.entry.title))
    out: list[Hit] = []
    for hit in ranked:
        if any(jaccard(hit.entry.tset, other.entry.tset) > 0.90 for other in out):
            continue
        out.append(hit)
        if len(out) >= top_k:
            break
    return out


def baseline_hits(entries: list[Entry], query: str, top_k: int = 5,
                  include_user: bool = False) -> list[Entry]:
    """Approximation of current memory_get: whitespace keywords (question
    words stripped, like an agent choosing keywords), ALL must appear in a
    line +/-2 window; file order, no ranking. USER.md is not searched by the
    current tool, so it is excluded by default; pass include_user=True to
    isolate the coverage contribution."""
    keywords = [k for k in clean_query(query).lower().split() if k]
    if not keywords:
        return []
    ordered = [e for e in entries if e.kind == "global"]
    if include_user:
        ordered += [e for e in entries if e.kind == "user"]
    ordered += [e for e in entries if e.kind == "daily"]
    out: list[Entry] = []
    for e in ordered:
        lines = e.full_text.splitlines()
        matched = False
        for i in range(len(lines)):
            window = " ".join(lines[max(0, i - 2):min(len(lines), i + 3)]).lower()
            if all(k in window for k in keywords):
                matched = True
                break
        if matched:
            out.append(e)
            if len(out) >= top_k:
                break
    return out


def norm_title(s: str) -> str:
    return re.sub(r"[\W_]+", "", s.lower())


def best_rank(expected: list[str], titles: list[str]) -> int | None:
    exp = [norm_title(x) for x in expected]
    for rank, title in enumerate(titles, 1):
        nt = norm_title(title)
        if any(x in nt for x in exp):
            return rank
    return None


def current_injection_chars(memory_dir: Path) -> int:
    """GLOBAL full + first 200 lines of the 3 newest daily logs (the current
    memory fragment policy)."""
    total = 0
    g = memory_dir / "GLOBAL.md"
    if g.is_file():
        total += len(g.read_text(encoding="utf-8"))
    daily = sorted(
        (p for p in memory_dir.iterdir() if DATE_FILE_RE.match(p.name) and p.is_file()),
        key=lambda p: p.name,
        reverse=True,
    )[:3]
    for p in daily:
        lines = p.read_text(encoding="utf-8").splitlines()[:200]
        total += len("\n".join(lines))
    return total


def cmd_query(memory_dir: Path, query: str, top_k: int) -> None:
    entries = load_entries(memory_dir)
    index = Index(entries)
    print(f"query: {query}  (clean: {clean_query(query)})")
    print(f"entries: {len(entries)}  (global={sum(e.kind=='global' for e in entries)}, "
          f"user={sum(e.kind=='user' for e in entries)}, daily={sum(e.kind=='daily' for e in entries)})")
    for rank, hit in enumerate(search(index, query, top_k), 1):
        e = hit.entry
        print(f"{rank}. [{e.kind}] {e.file} :: {e.title}")
        print(f"   score={hit.score:.3f} bm25={hit.bm25:.3f} jaccard={hit.jaccard:.3f} entity={hit.entity:.2f}")
        preview = re.sub(r"\s+", " ", e.text)[:180]
        print(f"   {preview}")
    base = baseline_hits(entries, query, top_k)
    print("baseline memory_get top:")
    for rank, e in enumerate(base, 1):
        print(f"{rank}. [{e.kind}] {e.file} :: {e.title}")


def cmd_eval(memory_dir: Path, queries_path: Path) -> None:
    entries = load_entries(memory_dir)
    index = Index(entries)
    queries = json.loads(queries_path.read_text(encoding="utf-8"))

    rows = []
    new_hits = 0
    base_hits = 0
    base_user_hits = 0
    new_mrr = 0.0
    base_mrr = 0.0
    base_user_mrr = 0.0
    new_chars = 0
    base_chars = 0

    print(f"# Memory index prototype — eval")
    print(f"\n- memory_dir: `{memory_dir}`")
    print(f"- entries: {len(entries)} "
          f"(global={sum(e.kind=='global' for e in entries)}, "
          f"user={sum(e.kind=='user' for e in entries)}, "
          f"daily={sum(e.kind=='daily' for e in entries)})")
    print(f"- queries: {len(queries)}")
    print("\n| query | expected | new rank | baseline rank | baseline+USER rank | new top-1 |")
    print("|---|---|---|---|---|---|")

    for q in queries:
        query = q["query"]
        expected = q["expected"]
        new = search(index, query, 5)
        base = baseline_hits(entries, query, 5)
        base_user = baseline_hits(entries, query, 5, include_user=True)
        new_rank = best_rank(expected, [h.entry.title for h in new])
        base_rank = best_rank(expected, [e.title for e in base])
        base_user_rank = best_rank(expected, [e.title for e in base_user])

        if new_rank:
            new_hits += 1
            new_mrr += 1.0 / new_rank
        if base_rank:
            base_hits += 1
            base_mrr += 1.0 / base_rank
        if base_user_rank:
            base_user_hits += 1
            base_user_mrr += 1.0 / base_user_rank
        new_chars += sum(len(h.entry.full_text) for h in new)
        base_chars += sum(len(e.full_text) for e in base)

        top1 = new[0].entry.title if new else "—"
        rows.append((query, expected, new_rank, base_rank, top1))
        print(f"| {query} | {' / '.join(expected)} | {new_rank or '—'} | "
              f"{base_rank or '—'} | {base_user_rank or '—'} | {top1} |")

    n = max(1, len(queries))
    inj = current_injection_chars(memory_dir)
    print("\n## Summary")
    print(f"- new: recall@5 = {new_hits}/{len(queries)} = {new_hits/n:.2%}; "
          f"MRR = {new_mrr/n:.3f}; avg top-5 chars = {new_chars/n:.0f}")
    print(f"- baseline (memory_get approximation, no USER.md): "
          f"recall@5 = {base_hits}/{len(queries)} = {base_hits/n:.2%}; "
          f"MRR = {base_mrr/n:.3f}; avg top-5 chars = {base_chars/n:.0f}")
    print(f"- baseline + USER.md coverage (same AND/line-window scan): "
          f"recall@5 = {base_user_hits}/{len(queries)} = {base_user_hits/n:.2%}; "
          f"MRR = {base_user_mrr/n:.3f}")
    print(f"- current fixed memory injection (GLOBAL + 3×200 log lines): ~{inj:,} chars")
    print(f"- new top-5 context: ~{new_chars/n:,.0f} chars per query "
          f"({new_chars/n/max(1,inj):.1%} of current fixed injection)")

    misses = [(r[0], r[1], r[4]) for r in rows if r[2] is None]
    if misses:
        print("\n## New-index misses")
        for query, expected, top1 in misses:
            print(f"- `{query}` → expected {expected}, top-1 `{top1}`")


def cmd_dump(memory_dir: Path) -> None:
    entries = load_entries(memory_dir)
    for e in entries:
        print(f"[{e.kind}] {e.file} :: {e.title}  ({len(e.tokens)} tokens, tags={sorted(e.tags)})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--memory-dir", required=True, type=Path)
    sub = ap.add_subparsers(dest="cmd", required=True)

    q = sub.add_parser("query")
    q.add_argument("query")
    q.add_argument("--top", type=int, default=5)

    ev = sub.add_parser("eval")
    ev.add_argument("--queries", required=True, type=Path)

    sub.add_parser("dump")

    args = ap.parse_args()
    if not args.memory_dir.is_dir():
        raise SystemExit(f"memory dir not found: {args.memory_dir}")

    if args.cmd == "query":
        cmd_query(args.memory_dir, args.query, args.top)
    elif args.cmd == "eval":
        cmd_eval(args.memory_dir, args.queries)
    elif args.cmd == "dump":
        cmd_dump(args.memory_dir)


if __name__ == "__main__":
    main()
