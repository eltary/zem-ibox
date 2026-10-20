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
# Only this project-owned emulator is modified. Physical phones are never used.
$adb -s "$serial" shell am force-stop "$package"
$adb -s "$serial" uninstall "$package.test" >/dev/null 2>&1 || true
$adb -s "$serial" uninstall "$package" >/dev/null 2>&1 || true
$adb -s "$serial" install -r app/build/outputs/apk/release/app-release.apk
$adb -s "$serial" shell am start -W -n "$package/.MainActivity" | tee qa/migration-release-launch.log
grep -q 'Status: ok' qa/migration-release-launch.log
$adb -s "$serial" shell am force-stop "$package"
$adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
$adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$adb -s "$serial" shell am instrument -w "$package.test/io.zem.ibox.SmokeTest" | tee qa/migration-instrumentation.log
grep -q 'PASS: .* Android integration assertions' qa/migration-instrumentation.log
$adb -s "$serial" shell am force-stop "$package"
$adb -s "$serial" install -r app/build/outputs/apk/release/app-release.apk
$adb -s "$serial" shell am start -W -n "$package/.MainActivity" | tee qa/migration-release-return.log
grep -q 'Status: ok' qa/migration-release-return.log
$adb -s "$serial" shell am force-stop "$package"
echo 'PASS: release -> debug integration checks -> release, same shared Zem certificate'
