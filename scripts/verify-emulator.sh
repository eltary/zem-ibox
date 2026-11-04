#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
adb="${ANDROID_HOME:-$HOME/.cache/android-sdk}/platform-tools/adb"
serial=emulator-5586
package=io.zem.ibox
ready=false
for attempt in {1..30}; do
    if [[ "$($adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then ready=true; break; fi
    sleep 2
done
[[ "$ready" == true ]]
# Build x86_64 test artifacts separately; the release APKs are audited separately.
mkdir -p qa/reports
./gradlew -Pzem.abis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
$adb -s "$serial" shell am force-stop "$package"
$adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
$adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$adb -s "$serial" shell am instrument -w "$package.test/io.zem.ibox.SmokeTest" | tee qa/reports/integration.log
grep -q 'PASS: .* Android integration assertions' qa/reports/integration.log
$adb -s "$serial" shell am force-stop "$package"
echo 'PASS: x86_64 Android integration checks; phone release is audited separately'
