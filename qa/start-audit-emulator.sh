#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.cache/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.cache/roadlink-audit013/avd}"
mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -f "$ANDROID_AVD_HOME/roadlink-audit013.ini" ]]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name roadlink-audit013 --package 'system-images;android-35;google_apis_playstore;x86_64' --device pixel_6 <<< 'no'
fi
exec "$ANDROID_HOME/emulator/emulator" -avd roadlink-audit013 -no-window -no-audio -no-boot-anim -no-snapshot -skip-adb-auth -gpu swiftshader -memory 1536 -cores 2 -port 5586 -camera-back none -camera-front none
