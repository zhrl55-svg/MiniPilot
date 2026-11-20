# Build tools/probe/tasks.jsonl from the Minis<->Claude mailbox.
#
# Each mailbox message (m-YYYYMMDD-NNN) already carries four semantic fields:
#   summary  - one-line Chinese summary of the task
#   evidence - file:line observations Minis gathered before asking
#   ask      - the actual instruction handed to Minis (the probe prompt)
#
# Run: python tools/probe/build_tasks.py
# Output is deterministic; tasks are sorted by mailbox id.

import json
import pathlib
import sys

MAILBOX = pathlib.Path("D:/minis-src/.claude/bridge/minis-to-claude.jsonl")
OUT = pathlib.Path("D:/minis-src/tools/probe/tasks.jsonl")

# Keyword -> tag. First match wins, so order matters: put the more specific
# keyword first.
TAG_RULES = [
    ("approval", ["审批", "横幅", "banner", "UserAskCenter", "HubApproval"]),
    ("hub", ["hub", "Hub", "relay", "中继"]),
    ("android", ["Android", "android", "MainActivity", "tailscale", "Kotlin", "composable", "Composable"]),
    ("ios", ["iOS", "Swift", "swift", "AIChatViewModel", "SystemPrompt"]),
    ("voice", ["语音", "播报", "speech", "Speech", "SSML"]),
    ("build", ["compile", "编译", "assembleGithubPerf", "gradlew", "装机", "versionCode"]),
    ("go", ["Go ", "golang", ".go:", "FIX-2"]),
    ("memory", ["memory", "记忆", "MEMORY"]),
    ("tailscale", ["tailscale", "Tailscale"]),
]


def tags_for(text: str) -> list[str]:
    tags = []
    for tag, keywords in TAG_RULES:
        if any(k in text for k in keywords):
            tags.append(tag)
    return tags


# --- curation ---------------------------------------------------------------
#
# kind separates the three things the mailbox actually carries:
#   protocol     - the channel-format conventions (receipts, markers); not work
#   verification - "验收/复核/确认" reports about work already done; not work
#   engineering  - an actual task worth probing
#
# device_safe marks the subset that may run on a real phone unattended: the
# device agent has a real shell, Minis has no switch to disable shell_execute,
# and hub tasks stop at a PC approval sheet a probe cannot tap.

VERIFICATION_WORDS = ["验收", "核实", "复核", "确认", "核对", "更正", "已通过", "报告",
                      "无需动作", "已真机验证", "已齐"]
UNSAFE_WORDS = ["编译", "装机", "安装", "卸载", "重启", "删除", "rm -", "gradlew",
                "assembleGithubPerf", "git push", "git commit", "adb "]


def classify(prompt: str, tags: list[str]) -> tuple[str, bool]:
    if prompt.startswith("[FROM: MINIS]") or ("chat.prompt" in prompt and "投递" in prompt):
        return "protocol", False
    if any(w in prompt for w in VERIFICATION_WORDS):
        return "verification", False
    # approval tasks block on a human tap on the phone; hub tasks likewise
    if "approval" in tags or "hub" in tags:
        return "engineering", False
    safe = not any(w in prompt for w in UNSAFE_WORDS)
    return "engineering", safe


def main() -> int:
    if not MAILBOX.exists():
        print(f"mailbox not found: {MAILBOX}", file=sys.stderr)
        return 1
    OUT.parent.mkdir(parents=True, exist_ok=True)

    seen: set[str] = set()
    tasks: list[dict] = []
    with MAILBOX.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            msg = json.loads(line)
            mid = msg.get("id", "")
            if not mid or mid in seen or mid.startswith("m-test-"):
                continue
            if msg.get("from") != "minis":
                continue
            ask = (msg.get("ask") or "").strip()
            summary = (msg.get("summary") or "").strip()
            if not ask:
                continue  # pure report, no actionable instruction
            seen.add(mid)
            blob = summary + "\n" + ask
            tags = tags_for(blob)
            kind, device_safe = classify(ask, tags)
            tasks.append({
                "task_id": mid,
                "source": "mailbox",
                "ts": msg.get("ts", ""),
                "severity": msg.get("severity", "info"),
                "summary": summary,
                "tags": tags,
                "kind": kind,
                "device_safe": device_safe,
                "prompt": ask,
            })

    tasks.sort(key=lambda t: t["task_id"])
    with OUT.open("w", encoding="utf-8", newline="\n") as fh:
        for t in tasks:
            fh.write(json.dumps(t, ensure_ascii=False) + "\n")

    print(f"wrote {len(tasks)} tasks -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
