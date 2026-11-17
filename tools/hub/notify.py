"""Show a Windows desktop notification for the hub's `notify` action.

The executor runs this as a shell command:

    notify.py "<title>" "<body>"

The shell is cmd.exe and the executor builds the command with json.dumps,
so what lands in sys.argv is not two clean arguments: the quote characters
stay attached to the text and an argument that contains a space arrives in
several pieces. `_read_text` puts the two JSON strings back together
before anything is shown.

The notification must not hold the hub up, so the popup runs in a detached
child and this script returns as soon as the child has started. The child's
output goes to the null device on purpose: a child that keeps the
executor's pipes open would make the executor wait for it.

The child is started with CREATE_NO_WINDOW, not DETACHED_PROCESS: a
detached process shows no toast on this Windows build.
"""

import base64
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
POPUP = os.path.join(HERE, "notify_popup.ps1")
SYSTEM_ROOT = os.environ.get("SystemRoot", r"C:\Windows")
POWERSHELL = os.path.join(SYSTEM_ROOT, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")


def _read_text(parts: list[str]) -> tuple[str, str]:
    """Recover the title and the body from the pieces of the command line."""
    joined = " ".join(parts)
    decoder = json.JSONDecoder()
    values: list[str] = []
    index = 0
    while index < len(joined):
        while index < len(joined) and joined[index] in " \t":
            index += 1
        if index >= len(joined):
            break
        try:
            value, index = decoder.raw_decode(joined, index)
        except ValueError:
            break
        values.append(value)
    if len(values) >= 2:
        return values[0], values[1]
    if len(values) == 1:
        return values[0], " ".join(parts[1:])
    if not parts:
        return "", ""
    return parts[0], " ".join(parts[1:])


def main(argv: list[str]) -> int:
    title, body = _read_text(argv[1:])
    if not title:
        title = "minis"
    child = [
        POWERSHELL if os.path.exists(POWERSHELL) else "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", POPUP,
        "-TitleB64", base64.b64encode(title.encode("utf-8")).decode("ascii"),
        "-BodyB64", base64.b64encode(body.encode("utf-8")).decode("ascii"),
    ]
    flags = 0
    for name in ("CREATE_NO_WINDOW", "CREATE_NEW_PROCESS_GROUP"):
        flags |= getattr(subprocess, name, 0)
    with open(os.devnull, "wb") as null:
        subprocess.Popen(
            child,
            stdin=null,
            stdout=null,
            stderr=null,
            creationflags=flags,
            close_fds=True,
        )
    print(json.dumps({"queued": True, "title": title}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
