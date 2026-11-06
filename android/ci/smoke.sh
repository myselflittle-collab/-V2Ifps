#!/usr/bin/env bash
# Installs the release APK on the running emulator, launches it, and fails if it crashes.
set -euo pipefail
APK="$1"
PKG=com.purnananda.framex
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 8
if ! adb shell pidof "$PKG" >/dev/null; then
  echo "FrameX is not running after launch"; adb logcat -d | tail -80; exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "FrameX crashed"; adb logcat -d | grep -A40 "FATAL EXCEPTION"; exit 1
fi
adb exec-out screencap -p > smoke-screenshot.png
echo "Release APK launched and is running."
