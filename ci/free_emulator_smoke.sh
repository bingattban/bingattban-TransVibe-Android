#!/usr/bin/env bash
# Runs explicit Android instrumentation tests on a *free hosted emulator*.
# It does not process any model weights or claim representative ARM64 phone speed.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p build/emulator-diagnostics

collect_diagnostics() {
  code=$?
  if ((code != 0)); then
    echo "Emulator smoke failed: collecting local diagnostics (no user media)."
    adb logcat -d -t 1200 > build/emulator-diagnostics/logcat.txt 2>/dev/null || true
    adb shell getprop ro.product.cpu.abilist > build/emulator-diagnostics/abis.txt 2>/dev/null || true
  fi
}
trap collect_diagnostics EXIT

adb wait-for-device
echo "Emulator ABIs: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
echo "Native bridge: $(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
echo "Installing the actual release-equivalent debug APK, not a test-only stub."
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

classes="com.transvibe.app.performance.AndroidEmulatorSmokeTest,com.transvibe.app.performance.PerformanceBenchmarkContractTest,com.transvibe.app.media.FfmpegSafContractInstrumentationTest"
adb shell am instrument -w -r \
  -e class "$classes" \
  com.transvibe.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee build/emulator-diagnostics/instrumentation.log

# 'adb shell am instrument' can exit 0 even when JUnit fails; enforce JUnit
# success and a successful instrumentation exit separately.
if ! grep -Eq '^OK \([0-9]+ tests?\)' build/emulator-diagnostics/instrumentation.log; then
  echo "ERROR: AndroidJUnitRunner did not report a successful test suite." >&2
  exit 1
fi
if ! grep -q '^INSTRUMENTATION_CODE: -1' build/emulator-diagnostics/instrumentation.log; then
  echo "ERROR: Android instrumentation did not finish successfully." >&2
  exit 1
fi
# Preview must be independent from the official app and instrumentation APK.
test -s app/build/outputs/apk/testing/app-testing.apk
adb install -r app/build/outputs/apk/testing/app-testing.apk
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.transvibe.app.preview'
adb shell am force-stop com.transvibe.app || true
adb shell am start -W -n com.transvibe.app.preview/com.transvibe.app.MainActivity |
  tee build/emulator-diagnostics/preview-launch.log
grep -q 'Status: ok' build/emulator-diagnostics/preview-launch.log
sleep 2
adb shell pidof com.transvibe.app.preview >/dev/null
echo "PASS: installed preview TEST launches independently of the debug instrumentation runner."

echo "PASS: real APK launched and verified in the free emulator."
echo "No model inference performance claim: this is not a phone-speed benchmark."
