#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./gradlew :app:assembleDebug

echo
echo "ARDA_RN2_COMPANION_BUILD=PASS"
echo "APK=$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
