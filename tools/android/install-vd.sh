#!/usr/bin/env bash
# install-vd.sh — 把虚拟屏工具（vd.sh + classes.dex）推到设备
#
# 用法：
#   tools/android/install-vd.sh            # 用 adb 默认设备
#   ANDROID_SERIAL=xxx tools/android/install-vd.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# Git Bash 会把 /data/... 改写成 Windows 路径，必须关掉
export MSYS_NO_PATHCONV=1

# adb 是 Windows 原生程序，本地路径要转成 Windows 形式
to_native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

"${ADB[@]}" shell "mkdir -p /data/local/tmp/vd"
"${ADB[@]}" push "$(to_native_path "$DIR/vd.sh")" /data/local/tmp/vd/vd.sh
"${ADB[@]}" push "$(to_native_path "$DIR/classes.dex")" /data/local/tmp/vd/classes.dex
"${ADB[@]}" shell "chmod 755 /data/local/tmp/vd/vd.sh && ls -la /data/local/tmp/vd/vd.sh"
"${ADB[@]}" shell "/data/local/tmp/vd/vd.sh status"
