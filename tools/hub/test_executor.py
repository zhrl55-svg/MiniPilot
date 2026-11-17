"""Regression for the executor's shell and path handling.

Three defects lived here, and all three cost a debugging session each:

  * a background service started through `shell` inherited the pipes of the
    shell that started it, so the executor waited for an EOF that never came
    and the call timed out although the service was up;
  * every file action was locked inside `device.root`, so the phone could not
    write to the mailbox or hand a document over;
  * commands fell back to cmd.exe, where `&`, `|` and quotes mean something
    else — and `%VAR%` swallowed URL-encoded text.

    python test_executor.py
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import time

import executor
import hubkit
from hubkit import ActionError

passed: list[str] = []


def ok(name: str) -> None:
    passed.append(name)
    print(f"  ok  {name}")


def make_cfg(tmp: str, **device) -> hubkit.Config:
    path = os.path.join(tmp, "config.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "relay": "ws://127.0.0.1:8799/v1/hub",
                "api_key": "nm_test",
                "device": {
                    "id": "pc-test",
                    "name": "TEST",
                    "kind": "computer",
                    "root": "root",
                    **device,
                },
                "actions": ["info", "shell", "file.get", "file.put"],
                "policy": {"default": "ask", "mode": "smart"},
                "shell": {"program": "", "args": []},
                "journal": "journal.jsonl",
            },
            fh,
        )
    return hubkit.load_config(path)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="hub-exec-")
    os.makedirs(os.path.join(tmp, "root"), exist_ok=True)
    os.makedirs(os.path.join(tmp, "extra"), exist_ok=True)

    # ---- paths: one extra root opens, everything else stays shut -----------
    cfg = make_cfg(tmp, roots=["extra"])
    ex = executor.Executor(cfg)
    assert ex._path("a.txt").endswith(os.path.join("root", "a.txt"))
    ok("a relative path lands inside the root")
    extra_file = os.path.join(tmp, "extra", "b.txt")
    assert ex._path(extra_file) == os.path.abspath(extra_file)
    ok("a path inside an extra root is allowed")
    try:
        ex._path(os.path.join(tmp, "outside", "c.txt"))
    except ActionError as exc:
        assert "outside this executor's root" in exc.message, exc.message
        ok("a path outside every root is refused with a clear reason")
    else:
        raise AssertionError("a path outside every root must be refused")

    locked = executor.Executor(make_cfg(tmp))
    try:
        locked._path(extra_file)
    except ActionError:
        ok("without `roots` the old single-root lock still applies")
    else:
        raise AssertionError("without `roots` only device.root is writable")

    # ---- shell: Git Bash, not cmd.exe --------------------------------------
    program = executor._shell_program(cfg)
    if os.name == "nt":
        assert program.lower().endswith("bash.exe"), program
        assert "git" in program.lower() or "msys" in program.lower(), program
        ok(f"Windows commands run under Git Bash ({program})")
    else:
        ok(f"commands run under {program}")
    pinned = executor.Executor(make_cfg(tmp, roots=["extra"]))
    pinned.cfg.shell["program"] = r"C:\custom\bash.exe"
    assert executor._shell_program(pinned.cfg) == r"C:\custom\bash.exe"
    ok("an explicit shell.program wins over the probe")

    # ---- detach: a service starts and the call returns at once -------------
    marker = os.path.join(tmp, "detached.txt")
    sleeper = os.path.join(tmp, "sleeper.py")
    with open(sleeper, "w", encoding="utf-8") as fh:
        fh.write("import time\ntime.sleep(2)\nopen(r'%s', 'w').write('ok')\n" % marker)
    started = time.time()
    body = asyncio.run(ex._do_shell({"command": f'"{sys.executable}" "{sleeper}"', "detach": True}))
    elapsed = (time.time() - started) * 1000
    assert body.get("detached") is True, body
    assert isinstance(body.get("pid"), int), body
    assert elapsed < 300, f"detach took {elapsed:.0f}ms"
    ok(f"detach returns in {elapsed:.0f}ms with pid {body['pid']}")
    time.sleep(3.0)
    assert os.path.isfile(marker), "the detached child did not survive the call"
    ok("the detached child keeps running after the call returns")

    # ---- info tells the caller which shell actually ran --------------------
    info = asyncio.run(ex._do_info({}))
    assert info.get("shell_program") == program, info
    assert info.get("roots"), info
    ok("info reports the shell program and the allowed roots")

    print(f"\nPASS - {len(passed)} checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
