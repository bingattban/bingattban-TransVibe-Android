#!/usr/bin/env bash
# Downloads only authentic AARs from URLs whose SHA256 is pinned.
set -euo pipefail
mkdir -p app/libs
get_aar() {
  local name="$1" url="$2" hash="$3" dest="app/libs/$1"
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
    curl -fL --retry 3 --connect-timeout 20 --max-time 900 "$url" -o "$dest"
  fi
  printf '%s  %s\n' "$hash" "$dest" | sha256sum --check --status || {
    echo "::error::SHA256 mismatch for $name; refusing to build."; exit 3;
  }
  python3 - "$dest" <<'PYCODE'
import sys,zipfile
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
    assert z.getinfo('AndroidManifest.xml'), 'Not a valid Android AAR'
print('Verified:', p)
PYCODE
}

# Upstream Sherpa-ONNX v1.13.5 binary; hash from release mirror, verified after download.
get_aar 'sherpa-onnx-1.13.5.aar' \
  "${SHERPA_AAR_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar}" \
  "${SHERPA_AAR_SHA256:-6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b}"

# This exact FFmpegKit binary was used by the owner's Code On The Go build.
# Its provenance and SHA256 have NOT been provided. Do not replace silently.
get_aar 'ffmpeg-kit-min-gpl-6.0-2.aar' \
  "${FFMPEG_AAR_URL:-}" "${FFMPEG_AAR_SHA256:-}"
