"""Harvest a real engineering-task corpus from MiniPilot's own chat sessions.

The mailbox corpus was mostly protocol/verification text, so it cannot feed a
device probe (`device_safe=0`). This script reads the phone's actual sessions
over the debug RPC and extracts *real user tasks*:

    chat.sessions.list  -> sessions (skip source=debug: those are our probes)
    chat.messages.list  -> first user message of each session = the task seed

Filtering:
- keep messages that look like engineering work (code words, debugging,
  implementation, files/scripts), length >= 30 chars;
- drop greetings / "继续" / one-liners;
- dedupe by a normalized prefix;
- `device_safe` is false when the task mentions build/install/device/hub/
  approval operations (the phone agent has a real shell; these must not run
  unattended).

Output: tools/probe/real_tasks.jsonl (git-ignored: contains personal chat text)

    python tools/probe/harvest_tasks.py --limit 200
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_device import DEFAULT_PORT, DEFAULT_SERIAL, Device, forward  # noqa: E402
from build_tasks import tags_for  # noqa: E402

OUT = HERE / "real_tasks.jsonl"

ENGINEERING_WORDS = [
    "修复", "实现", "定位", "排查", "重构", "报错", "崩溃", "异常", "bug", "Bug",
    "代码", "函数", "文件", "仓库", "commit", "编译", "测试", "日志", "脚本",
    "Kotlin", "Swift", "Python", "gradle", "接口", "API", "配置", "依赖",
    "检查", "分析", "为什么", "怎么", "如何", "帮我", "给我", "改成", "调整",
]
CHITCHAT = {"继续", "好", "好的", "嗯", "ok", "OK", "谢谢", "你好", "在吗", "test", "测试"}
UNSAFE_WORDS = ["编译", "装机", "安装", "卸载", "重启", "删除", "rm -", "gradlew",
                "assembleGithubPerf", "git push", "git commit", "adb ", "hub",
                "审批", "批准", "推送", "提交"]


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text)[:80]


def is_engineering(text: str) -> bool:
    if len(text) < 30:
        return False
    if text.lstrip().startswith("<"):
        return False  # system markers (e.g. <scheduled_task ...>), not user asks
    if text.strip() in CHITCHAT:
        return False
    return any(w in text for w in ENGINEERING_WORDS)


def device_safe(text: str) -> bool:
    return not any(w in text for w in UNSAFE_WORDS)


def main() -> int:
    p = argparse.ArgumentParser(description="harvest real tasks from device sessions")
    p.add_argument("--limit", type=int, default=200, help="sessions to scan (1-500)")
    p.add_argument("--serial", default=DEFAULT_SERIAL)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--endpoint", default="", help="host:port, skip adb forward")
    p.add_argument("--out", type=pathlib.Path, default=OUT)
    p.add_argument("--max-prompt", type=int, default=1500)
    args = p.parse_args()

    if args.endpoint:
        host, _, port_s = args.endpoint.partition(":")
        dev = Device(host=host, port=int(port_s or args.port))
    else:
        forward(args.serial, args.port)
        dev = Device(port=args.port)

    sessions = dev.call("chat.sessions.list",
                        {"limit": max(1, min(args.limit, 500)), "includeEmpty": False})
    items = sessions.get("sessions") or []

    seen: set[str] = set()
    tasks: list[dict] = []
    skipped_debug = 0
    for s in items:
        if (s.get("source") or "") == "debug":
            skipped_debug += 1
            continue
        sid = s.get("id")
        if not sid:
            continue
        try:
            result = dev.call("chat.messages.list",
                              {"sessionId": sid, "limit": 50, "roles": ["user"]})
        except RuntimeError:
            continue
        msgs = result.get("messages") or []
        # Every qualifying user message is a candidate task: in a long thread
        # the follow-ups ("改哪了？""为什么卡死") are often the real asks, not
        # the first seed. Cap per session to keep the corpus balanced.
        picked = 0
        for idx, m in enumerate(msgs):
            text = (m.get("content") or "").strip()
            if not text or not is_engineering(text):
                continue
            prompt = text[: args.max_prompt]
            key = normalize(prompt)
            if key in seen:
                continue
            seen.add(key)
            ts = s.get("createdAt")
            if isinstance(ts, (int, float)):
                ts = time.strftime("%Y-%m-%d %H:%M", time.localtime(ts / 1000))
            tasks.append({
                "task_id": f"sess-{sid[:8]}-{idx}",
                "source": "session",
                "session_id": sid,
                "ts": ts or "",
                "severity": "info",
                "summary": (s.get("title") or prompt[:60]).strip(),
                "tags": tags_for(prompt),
                "kind": "engineering",
                "device_safe": device_safe(prompt),
                "prompt": prompt,
            })
            picked += 1
            if picked >= 3:
                break

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8", newline="\n") as fh:
        for t in tasks:
            fh.write(json.dumps(t, ensure_ascii=False) + "\n")

    safe = sum(1 for t in tasks if t["device_safe"])
    print(f"scanned {len(items)} sessions ({skipped_debug} debug skipped) -> "
          f"{len(tasks)} tasks ({safe} device_safe) -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
