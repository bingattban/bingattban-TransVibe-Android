#!/usr/bin/env bash
# Reproducible CPU-only ARM64 reference build. Does not replace the bundled .so.
# Requires a host-compatible Android NDK, CMake, Ninja, and sha256sum.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
OUT="${1:-$ROOT/build/native-arm64-cpu-reference}"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "ERROR: Set ANDROID_NDK_HOME to a complete, host-compatible Android NDK." >&2
  exit 2
fi
for tool in cmake ninja sha256sum python3; do
  command -v "$tool" >/dev/null || { echo "ERROR: missing tool: $tool" >&2; exit 2; }
done
mkdir -p "$OUT"
cmake -S "$ROOT/app/src/main/cpp" -B "$OUT" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_VULKAN=OFF \
  -DGGML_OPENMP=OFF \
  -DTRANSVIBE_ARMV82_DOTPROD_FP16=OFF
cmake --build "$OUT" --target transvibe_native --parallel 2
SO="$OUT/libtransvibe_native.so"
[[ -s "$SO" ]] || { echo "ERROR: did not produce $SO" >&2; exit 1; }
python3 "$ROOT/tools/verify_native_jni_contract.py" --project "$ROOT" --library "$SO"
{
  echo 'build=CPU_ARM64_reference'
  echo 'android_abi=arm64-v8a'
  echo 'android_api=26'
  echo 'ggml_vulkan=OFF'
  echo 'ggml_openmp=OFF'
  echo 'armv82_dotprod_fp16=OFF'
  echo 'whisper_upstream_version=v1.9.4'
  echo 'whisper_upstream_commit_declared=927cfce34f31707e17f2bff35c349632fb9e2c3a'
  echo "ndk_path=$NDK"
  echo "ndk_version=$(cat "$NDK/source.properties" 2>/dev/null | grep '^Pkg.Revision' || true)"
  echo "cmake=$(cmake --version | head -1)"
  echo "native_sha256=$(sha256sum "$SO" | cut -d' ' -f1)"
  echo "source_sha256=$(sha256sum "$ROOT/app/src/main/cpp/native-lib.cpp" | cut -d' ' -f1)"
  echo "vendor_whisper_sha256=$(sha256sum "$ROOT/app/src/main/cpp/third_party/whisper.cpp/src/whisper.cpp" | cut -d' ' -f1)"
  echo "cpu_reference_binary=$SO"
} > "$OUT/transvibe_native_provenance.txt"
echo "PASS (static): $SO"
echo "NEXT: Android device JNI smoke and 43s benchmark before replacing the installed library."
