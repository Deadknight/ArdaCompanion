#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
APK="app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || {
  echo "APK not found. Run ./build-debug-mac.sh first." >&2
  exit 1
}

adb install -r "$APK"
echo "ARDA_RN2_COMPANION_INSTALL=PASS"
