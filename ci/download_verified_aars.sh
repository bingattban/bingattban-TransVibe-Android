#!/usr/bin/env bash
# Downloads only authentic AARs from URLs whose SHA256 is pinned.
set -euo pipefail
mkdir -p app/libs
get_aar() {
  local name="$1" url="$2" hash="$3" library="$4" dest="app/libs/$1"
  if [[ -z "$url" || -z "$hash" ]]; then
    echo "::error::Missing verified URL/SHA256 for $name. Never substitute another library."
    exit 2
  fi
  if [[ ! "$hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "::error::Invalid 64-char SHA256 for $name"
    exit 2
  fi
  if [[ -f "$dest" ]]; then
    echo "Checking preinstalled $name"
  else
    if ! curl -fLSs --retry 3 --connect-timeout 20 --max-time 900 "$url" -o "${dest}.partial"; then
      rm -f "${dest}.partial"
      echo "::error::Failed to download $name from $url" >&2
      exit 2
    fi
    mv "${dest}.partial" "$dest"
  fi
  python3 tools/verify_ci_aar.py "$dest" --sha256 "$hash" --native-lib "$library"
}

# Upstream Sherpa-ONNX v1.13.5 binary; hash from release mirror, verified after download.
get_aar 'sherpa-onnx-1.13.5.aar' \
  "${SHERPA_AAR_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar}" \
  "${SHERPA_AAR_SHA256:-6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b}" \
  'libsherpa-onnx-jni.so'

# AAR supplied by the owner on 2026-11-15: digest confirmed locally.
# Historical third-party Maven mirror; SHA-256 must match original uploaded bytes.
# Do not replace with a different FFmpegKit release. GPL-3.0 licensing applies.
get_aar 'ffmpeg-kit-min-gpl-6.0-2.aar' \
  "${FFMPEG_AAR_URL:-https://artifactory.appodeal.com/appodeal-public/com/arthenica/ffmpeg-kit-min-gpl/6.0-2/ffmpeg-kit-min-gpl-6.0-2.aar}" \
  "${FFMPEG_AAR_SHA256:-11c1fd0ef76edb91fb3b7a1d6d0619914c914126896397367813f87805644c82}" \
  'libffmpegkit.so'
