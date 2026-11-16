#!/usr/bin/env bash
# Experimental ARMv8.2 dotprod + FP16; NOT safe for arbitrary arm64 devices.
# Never installs the resulting binary in the shipping app by default.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
OUT="${1:-$ROOT/build/native-arm64-dotprod-experimental}"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo 'ERROR: ANDROID_NDK_HOME is required' >&2
  exit 2
fi
for tool in cmake ninja python3 sha256sum; do
  command -v "$tool" >/dev/null || { echo "ERROR: missing $tool" >&2; exit 2; }
done
if [[ "$OUT" == "$ROOT/build/native-arm64-cpu-reference" ]]; then
  echo 'ERROR: experiment must never overwrite reference build' >&2
  exit 2
fi
mkdir -p "$OUT"
cmake -S "$ROOT/app/src/main/cpp" -B "$OUT" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF \
  -DGGML_VULKAN=OFF \
  -DGGML_OPENMP=OFF \
  -DTRANSVIBE_ARMV82_DOTPROD_FP16=ON
cmake --build "$OUT" --target transvibe_native --parallel 2
SO="$OUT/libtransvibe_native.so"
[[ -s "$SO" ]] || { echo "ERROR: missing $SO" >&2; exit 1; }
python3 "$ROOT/tools/verify_native_jni_contract.py" --project "$ROOT" --library "$SO"
{
  echo 'variant=ARMv8.2_dotprod_fp16_experimental'
  echo 'android_abi=arm64-v8a'
  echo 'android_api=26'
  echo 'ggml_vulkan=OFF'
  echo 'armv82_dotprod_fp16=ON'
  echo 'source=whisper.cpp v1.9.4; same source as CPU reference'
  echo 'distribution=DO_NOT_SHIP_WITHOUT_CPU_FEATURE_DETECTION_AND_DEVICE_BENCHMARK'
  echo "native_sha256=$(sha256sum "$SO" | cut -d' ' -f1)"
} > "$OUT/transvibe_native_provenance.txt"
echo 'PASS: ELF/JNI verified; phone CPU feature and accuracy/performance NOT verified.'
