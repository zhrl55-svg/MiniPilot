#!/usr/bin/env bash
# Build rclone as an Android .aar via gomobile.
#
# Android is the EASIER of the two platforms here: rclone ships an official
# gomobile binding (librclone/gomobile) and upstream documents this exact
# command. iOS is the one upstream has not tested.
#
# One deviation from upstream's command: their gomobile package hardcodes
# `backend/all` (all 70 backends). We bind ./gomobile instead, which re-exports
# the same API against the trimmed backend list in backends/backends.go.
#
# Output: deps/build/rclone/rclone.aar
# Consume it by copying the .so into src/android/app/src/main/jniLibs/arm64-v8a/
# (the same place libproot.so lives), or by importing the .aar directly.
#
# The same .aar carries the embedded Tailscale node (deps/rclone-mobile/tsnet)
# and the local network entry (nethub): an app can load only one gomobile
# library (one Go runtime). GO_TAGS trims Tailscale features at build time,
# with the same default as deps/build_rclone_ios.sh:
#   ts_omit_logtail    never upload logs to log.tailscale.com
#   ts_omit_portmapper no UPnP / NAT-PMP / PCP to the LAN gateway (NAT
#                      traversal then relies on STUN + DERP)
# Do NOT add ts_omit_acme: tsnet does not compile without it.
# Override with GO_TAGS="" (nothing omitted) or your own comma-separated list.
set -euo pipefail

GO_TAGS="${GO_TAGS-ts_omit_logtail,ts_omit_portmapper}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/deps/rclone-mobile"
BUILD="$ROOT/deps/build/rclone"

command -v go >/dev/null || { echo "error: go toolchain not found" >&2; exit 1; }
command -v gomobile >/dev/null || {
  echo "error: gomobile not installed. Run:" >&2
  echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  echo "  gomobile init" >&2
  exit 1
}
# gomobile generates the Java bindings and compiles them with javac, which it
# looks up on PATH - a JAVA_HOME alone is not enough (the bind died with
# "javac: executable file not found in %PATH%" on 2026-11-19).
if [ -n "${JAVA_HOME:-}" ]; then
  jbin="$JAVA_HOME/bin"
  # Git Bash needs a POSIX path in PATH: "D:/Android/jdk-17/bin" is not
  # looked up, "/d/Android/jdk-17/bin" is.
  if command -v cygpath >/dev/null 2>&1; then
    jbin="$(cygpath -u "$JAVA_HOME")/bin"
  fi
  PATH="$jbin:$PATH"
  export PATH
fi
command -v javac >/dev/null || { echo "error: javac not found (set JAVA_HOME to a JDK)" >&2; exit 1; }
[ -n "${ANDROID_NDK_HOME:-}${ANDROID_HOME:-}" ] || {
  echo "error: set ANDROID_NDK_HOME (or ANDROID_HOME) first" >&2; exit 1; }

mkdir -p "$BUILD"
cd "$SRC"

echo "==> $(go version) tags='${GO_TAGS}'"

# arm64-v8a only, matching the app's existing ndk abiFilters.
gomobile bind -v \
  -target=android/arm64 \
  -androidapi 24 \
  -javapkg=com.openminis.rclone \
  -ldflags="-s -w" \
  ${GO_TAGS:+-tags "$GO_TAGS"} \
  -o "$BUILD/rclone.aar" \
  ./gomobile

echo "==> $BUILD/rclone.aar"
du -sh "$BUILD/rclone.aar" | awk '{print "    size:", $1}'
