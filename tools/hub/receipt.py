"""The machine-readable receipt schema (`minis.receipt/1`).

Two channels carry the SAME object:

  * the receipt line the PC appends to `claude-to-minis.jsonl` — the archival
    record of "which task, which state, which artifacts";
  * the `receipt` field of the hub's `notify` action — so the phone can hand
    the PC a structured handover instead of a paragraph.

The human-readable text stays where it was: the jsonl line keeps its
`message`, and `notify` keeps its title/body. The schema is an ADDITION, so
a reader that only knows the old fields still works.

    {
      "schema":    "minis.receipt/1",
      "re":        "m-20261120-003",                     # the task this answers
      "status":    "working" | "done" | "blocked",
      "summary":   "one line, meant to be read by a program",
      "artifacts": [{"path": "...", "sha256": "...", "lines": 12}],
      "needs":     ["what the other side has to do next"]
    }

`status` vocabulary: the envelope lines used to say `received`/`rejected`;
they map onto `working`/`blocked`. Nothing has used the old words yet, so
this is a note rather than a migration.

    python receipt.py --re m-20261120-003 --status done --summary "..." \\
        --artifact "D:/minis-src/x.md" --need "..." [--out claude-to-minis.jsonl]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from typing import Any

SCHEMA = "minis.receipt/1"

#: The three states a receipt can be in. A task is either still moving, done,
#: or stopped on something the other side has to clear first.
STATUSES = ("working", "done", "blocked")

#: What the envelope lines used to say. Kept so an old reader can still map
#: its own vocabulary onto the schema.
LEGACY_STATUS = {
    "received": "working",
    "working": "working",
    "done": "done",
    "rejected": "blocked",
}

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def build(
    re: str,
    status: str,
    summary: str = "",
    artifacts: list[dict[str, Any]] | None = None,
    needs: list[str] | None = None,
) -> dict[str, Any]:
    """A receipt object. Validate it with [validate] before sending it."""
    return {
        "schema": SCHEMA,
        "re": re,
        "status": status,
        "summary": summary,
        "artifacts": list(artifacts or []),
        "needs": list(needs or []),
    }


def validate(obj: Any) -> list[str]:
    """Every problem with [obj], empty list when it is a valid receipt.

    Unknown fields are ignored: the schema may grow, and an old reader must
    not reject a newer receipt over a field it does not know.
    """
    problems: list[str] = []
    if not isinstance(obj, dict):
        return ["receipt is not an object"]
    if not str(obj.get("re") or "").strip():
        problems.append("re is missing")
    status = str(obj.get("status") or "")
    if status not in STATUSES:
        problems.append(f"status must be one of {', '.join(STATUSES)}")
    if "summary" in obj and not isinstance(obj.get("summary"), str):
        problems.append("summary must be a string")
    artifacts = obj.get("artifacts")
    if artifacts is not None:
        if not isinstance(artifacts, list):
            problems.append("artifacts must be a list")
        else:
            for index, item in enumerate(artifacts):
                problems.extend(_artifact_problems(item, index))
    needs = obj.get("needs")
    if needs is not None:
        if not isinstance(needs, list):
            problems.append("needs must be a list")
        else:
            for index, item in enumerate(needs):
                if not isinstance(item, str):
                    problems.append(f"needs[{index}] must be a string")
    return problems


def _artifact_problems(item: Any, index: int) -> list[str]:
    problems: list[str] = []
    if not isinstance(item, dict):
        return [f"artifacts[{index}] is not an object"]
    if not str(item.get("path") or "").strip():
        problems.append(f"artifacts[{index}].path is missing")
    digest = item.get("sha256")
    if digest not in (None, "") and not (isinstance(digest, str) and _SHA256.match(digest)):
        problems.append(f"artifacts[{index}].sha256 is not a sha256 hex digest")
    lines = item.get("lines")
    if lines is not None and (not isinstance(lines, int) or isinstance(lines, bool) or lines < 0):
        problems.append(f"artifacts[{index}].lines must be a non-negative integer")
    return problems


def envelope(
    receipt: dict[str, Any],
    receipt_id: str = "",
    ts: str = "",
    sender: str = "claude",
    message: str = "",
) -> dict[str, Any]:
    """The jsonl line: the receipt object plus the envelope fields.

    The envelope keeps the fields the mailbox already had (`id`, `ts`,
    `from`, `re`, `status`, `message`) and carries the receipt's own fields
    next to them, so one line is both human-readable and machine-readable.
    """
    line = {
        "id": receipt_id or f"c-{int(time.time())}",
        "ts": ts or time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "from": sender,
        "re": receipt.get("re", ""),
        "status": receipt.get("status", ""),
        "summary": receipt.get("summary", ""),
        "artifacts": receipt.get("artifacts", []),
        "needs": receipt.get("needs", []),
    }
    if message:
        line["message"] = message
    return line


def append_line(path: str, line: dict[str, Any]) -> None:
    """Append one JSON line. The mailbox files are append-only."""
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False) + "\n")


def parse_artifact(text: str) -> dict[str, Any]:
    """`path`, `path|sha256` or `path|sha256|lines`.

    The separator is a pipe, not a colon: a Windows path carries colons of
    its own and must survive being pasted in.
    """
    parts = text.split("|")
    artifact: dict[str, Any] = {"path": parts[0].strip()}
    if len(parts) > 1 and parts[1].strip():
        artifact["sha256"] = parts[1].strip()
    if len(parts) > 2 and parts[2].strip():
        artifact["lines"] = int(parts[2].strip())
    return artifact


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a machine-readable receipt.")
    parser.add_argument("--re", required=True, help="the task id this answers")
    parser.add_argument("--status", required=True, choices=list(STATUSES))
    parser.add_argument("--summary", default="")
    parser.add_argument("--artifact", action="append", default=[], help="path[|sha256|lines]")
    parser.add_argument("--need", action="append", default=[], help="what the other side must do")
    parser.add_argument("--id", default="", help="the receipt id")
    parser.add_argument("--ts", default="")
    parser.add_argument("--sender", default="claude")
    parser.add_argument("--message-file", default="", help="the human-readable body")
    parser.add_argument("--out", default="", help="append the line to this jsonl file")
    args = parser.parse_args(argv)

    receipt = build(
        re=args.re,
        status=args.status,
        summary=args.summary,
        artifacts=[parse_artifact(item) for item in args.artifact],
        needs=list(args.need),
    )
    problems = validate(receipt)
    if problems:
        for problem in problems:
            print(f"invalid receipt: {problem}", file=sys.stderr)
        return 2

    message = ""
    if args.message_file:
        with open(args.message_file, "r", encoding="utf-8") as fh:
            message = fh.read()
    line = envelope(receipt, receipt_id=args.id, ts=args.ts, sender=args.sender, message=message)
    text = json.dumps(line, ensure_ascii=False)
    if args.out:
        append_line(args.out, line)
        print(f"appended {args.out}", file=sys.stderr)
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
