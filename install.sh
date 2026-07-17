#!/bin/bash
# Reinstall both Quest apps (Horizon OS has been seen culling sideloaded apps).
# Works over USB or the Wi-Fi relay (see CLAUDE.md for relay setup).
set -e
cd "$(dirname "$0")"
DEV=${1:-$(adb devices | awk 'NR==2{print $1}')}
[ -z "$DEV" ] && { echo "no adb device"; exit 1; }
echo "installing to $DEV"
adb -s "$DEV" install -r quest3-spatial/app/build/outputs/apk/debug/app-debug.apk
adb -s "$DEV" install -r quest3-app/app/build/outputs/apk/debug/app-debug.apk
echo "done. launch: adb -s $DEV shell am start -n org.ohack.flirone.spatial/.ImmersiveActivity"
