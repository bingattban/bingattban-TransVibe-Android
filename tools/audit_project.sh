#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
fail=0
check_absent() {
  local pattern="$1"; shift
  if grep -RInE "$pattern" "$@" >/tmp/transvibe-audit-hit 2>/dev/null; then
    echo "FAIL: forbidden pattern: $pattern"
    cat /tmp/transvibe-audit-hit
    fail=1
  else
    echo "PASS: absent: $pattern"
  fi
}
check_absent '\b(TODO|FIXME)\b' app/src/main/java app/src/main/cpp/native-lib.cpp app/src/main/cpp/jni_utf.h app/src/main/res
check_absent '\b(SessionStore|ProcessBus)\b' app/src/main/java
check_absent '(^|[^[:alnum:]_])Thread[[:space:]]*\(|Executors\.|newFixedThreadPool|newSingleThreadExecutor' app/src/main/java
check_absent 'WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml
# READ_EXTERNAL_STORAGE remains necessary on Android 12 and lower, but must be
# scoped to those platform versions. Do not flag that legitimate legacy permission.
if grep -q 'android.permission.READ_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml; then
  if grep -A1 'android.permission.READ_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml | grep -q 'android:maxSdkVersion="32"'; then
    echo 'PASS: legacy READ_EXTERNAL_STORAGE is limited to Android 12L and lower'
  else
    echo 'FAIL: READ_EXTERNAL_STORAGE missing maxSdkVersion=32'; fail=1
  fi
fi
check_absent '⌂|⇩|◷|⚙' app/src/main/java app/src/main/res
if grep -q 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml; then echo 'PASS: cleartext disabled'; else echo 'FAIL: cleartext setting'; fail=1; fi
if grep -A4 'SubtitleProcessingService' app/src/main/AndroidManifest.xml | grep -q 'android:exported="false"'; then echo 'PASS: processing service non-exported'; else echo 'FAIL: service export'; fail=1; fi
if grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml; then echo 'PASS: network security cleartext disabled'; else echo 'FAIL: network security'; fail=1; fi
if grep -RIn 'JSONObject\|JSONArray' app/src/main/java/com/transvibe/app/nativebridge app/src/main/cpp/native-lib.cpp >/tmp/transvibe-jni-json 2>/dev/null; then
  echo 'FAIL: JSON bridge marker in JNI boundary'; cat /tmp/transvibe-jni-json; fail=1
else
  echo 'PASS: no JSON JNI bridge'
fi
# Release hardening and dependency hygiene.
if grep -q 'isMinifyEnabled = true' app/build.gradle.kts && grep -q 'isShrinkResources = true' app/build.gradle.kts; then
  echo 'PASS: release minification/resource shrinking enabled'
else
  echo 'FAIL: release optimization disabled'; fail=1
fi
if grep -q 'keepDebugSymbols' app/build.gradle.kts; then
  echo 'FAIL: native debug symbols forced into packaged builds'; fail=1
else
  echo 'PASS: native symbols can be stripped from release packaging'
fi
check_absent 'allowBackup="true"' app/src/main/AndroidManifest.xml
check_absent 'usesCleartextTraffic="true"' app/src/main/AndroidManifest.xml
exit "$fail"
