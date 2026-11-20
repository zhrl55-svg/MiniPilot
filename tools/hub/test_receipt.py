"""Regression for the machine-readable receipt schema.

    python test_receipt.py
"""

from __future__ import annotations

import json
import os
import tempfile

import receipt

passed: list[str] = []


def ok(name: str) -> None:
    passed.append(name)
    print(f"  ok  {name}")


def main() -> int:
    good = receipt.build(
        re="m-20261120-003",
        status="done",
        summary="wired the working frame",
        artifacts=[{"path": "D:/minis-src/x.md", "sha256": "a" * 64, "lines": 12}],
        needs=["mirror the schema in the phone's notify"],
    )
    assert receipt.validate(good) == [], receipt.validate(good)
    ok("a built receipt validates")

    problems = receipt.validate(dict(good, status="finished"))
    assert any("status" in problem for problem in problems), problems
    ok("an unknown status is refused")

    problems = receipt.validate(dict(good, re=""))
    assert any("re" in problem for problem in problems), problems
    ok("a missing task id is refused")

    problems = receipt.validate(dict(good, artifacts=[{"sha256": "a" * 64}]))
    assert any("path" in problem for problem in problems), problems
    ok("an artifact without a path is refused")

    problems = receipt.validate(dict(good, artifacts=[{"path": "x", "sha256": "nope"}]))
    assert any("sha256" in problem for problem in problems), problems
    ok("a digest that is not a sha256 is refused")

    problems = receipt.validate(dict(good, artifacts=[{"path": "x", "lines": "12"}]))
    assert any("lines" in problem for problem in problems), problems
    ok("a line count that is not a number is refused")

    problems = receipt.validate(dict(good, needs=[1]))
    assert any("needs" in problem for problem in problems), problems
    ok("a non-string need is refused")

    assert receipt.validate(dict(good, future_field=1)) == []
    ok("an unknown field does not make a receipt invalid")

    # The envelope keeps the old fields and carries the new ones: the human
    # text stays, the schema is an addition.
    line = receipt.envelope(
        good,
        receipt_id="c-1",
        ts="2026-11-20T12:00:00+08:00",
        message="人话版回执",
    )
    assert line["message"] == "人话版回执", line
    assert line["re"] == "m-20261120-003", line
    assert line["status"] == "done", line
    assert line["summary"] == good["summary"], line
    assert line["artifacts"] == good["artifacts"], line
    assert line["needs"] == good["needs"], line
    ok("the envelope keeps the human text and carries the schema")

    artifact = receipt.parse_artifact(r"D:\minis-src\a.md|" + "b" * 64 + "|7")
    assert artifact["path"] == r"D:\minis-src\a.md", artifact
    assert artifact["sha256"] == "b" * 64, artifact
    assert artifact["lines"] == 7, artifact
    ok("an artifact path with a colon in it survives")

    tmp = tempfile.mkdtemp(prefix="hub-receipt-")
    path = os.path.join(tmp, "receipts.jsonl")
    receipt.append_line(path, good)
    receipt.append_line(path, receipt.build(re="m-2", status="working"))
    with open(path, "r", encoding="utf-8") as fh:
        lines = [json.loads(line) for line in fh if line.strip()]
    assert len(lines) == 2, lines
    assert lines[0]["re"] == "m-20261120-003", lines
    assert lines[1]["status"] == "working", lines
    ok("receipts land in the file one JSON line each")

    print(f"\nPASS - {len(passed)} checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
