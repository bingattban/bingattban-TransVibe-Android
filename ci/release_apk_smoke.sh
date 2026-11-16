#!/usr/bin/env bash
# Install and launch a REAL minified Release APK on an Android emulator.
# The APK is never rebuilt or re-signed here. CI catches startup regressions
# that unit tests, lint, aapt, and apksigner cannot detect.
set -Eeuo pipefail

apk="${1:?Usage: release_apk_smoke.sh path/to/app-release.apk application.id}"
package_name="${2:?Missing application id}"
test -s "$apk"
test "$package_name" = "com.transvibe.app"

mkdir -p build/emulator-diagnostics
log_dir="build/emulator-diagnostics"

capture_failure() {
  code=$?
  if ((code != 0)); then
    echo "ERROR: Release APK did not survive its Android cold start."
    adb logcat -d -v threadtime > "$log_dir/release-failed-logcat.txt" 2>/dev/null || true
    adb shell dumpsys activity activities > "$log_dir/release-failed-activities.txt" 2>/dev/null || true
    if grep -q -F "Process: $package_name," "$log_dir/release-crashes.txt" 2>/dev/null; then
      echo "Release process crash captured:"
      grep -A 28 -B 3 -F "Process: $package_name," "$log_dir/release-crashes.txt" || true
    fi
  fi
}
trap capture_failure EXIT

adb wait-for-device
adb install -r "$apk"
adb shell pm path "$package_name" | grep -q '^package:'
# Clear only the emulator log buffers; this never touches user devices.
adb logcat -c
adb shell am force-stop "$package_name"
adb shell am start -W -n "$package_name/com.transvibe.app.MainActivity" |
  tee "$log_dir/release-launch.txt"
grep -q 'Status: ok' "$log_dir/release-launch.txt"

# am start -W can return success before onCreate/Compose finishes or crashes.
sleep 8
adb logcat -d -b crash -v threadtime > "$log_dir/release-crashes.txt" 2>/dev/null || true
if grep -q -F "Process: $package_name," "$log_dir/release-crashes.txt" &&
   grep -q -F "FATAL EXCEPTION" "$log_dir/release-crashes.txt"; then
  echo "ERROR: AndroidRuntime recorded a fatal Release crash." >&2
  exit 1
fi
if ! adb shell pidof "$package_name" >/dev/null; then
  echo "ERROR: Release process exited after launch." >&2
  exit 1
fi

echo "PASS: real R8-minified Release APK installed, launched and survived cold start."
