"""看门狗：ego 服务不在监听就把它拉起来。

计划任务 MinisEgoWatch 每分钟跑一次。用 pythonw 跑（GUI 子系统），所以既
不闪控制台窗口，也不用什么技巧去藏窗口 —— 根本没有控制台。

    python ego_watchdog.py

判断顺序：
  1. pid 文件里的进程还活着 → 什么都不做；
  2. 端口有人在听 → 什么都不做（服务活着，只是 pid 文件丢了）；
  3. 都没有 → 起一个新的 ego_srv.py。
"""

from __future__ import annotations

import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
PID_PATH = os.path.join(ROOT, "ego_srv.pid")
LOG_PATH = os.path.join(ROOT, "ego_srv.log")
CREATE_NO_WINDOW = 0x08000000


def log(message: str) -> None:
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write("[%s] watchdog %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), message))
            fh.flush()
    except OSError:
        pass


def pid_alive(pid: int) -> bool:
    try:
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))
        if handle:
            ctypes.windll.kernel32.CloseHandle(handle)
            return True
    except Exception:  # noqa: BLE001
        pass
    return False


def read_pid() -> int:
    try:
        with open(PID_PATH, "r", encoding="utf-8") as fh:
            return int((fh.read() or "0").strip() or 0)
    except (OSError, ValueError):
        return 0


def port_served(host: str = "127.0.0.1", port: int = 8799) -> bool:
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex((host, port)) == 0


def main() -> int:
    pid = read_pid()
    if pid and pid_alive(pid):
        return 0
    if port_served():
        return 0
    log("ego service is not running - starting it")
    try:
        subprocess.Popen(
            [sys.executable, os.path.join(ROOT, "ego_srv.py")],
            cwd=ROOT,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=CREATE_NO_WINDOW,
        )
    except Exception as exc:  # noqa: BLE001
        log("failed to start ego service: %s: %s" % (type(exc).__name__, exc))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
