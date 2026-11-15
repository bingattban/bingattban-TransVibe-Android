#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/app/src/main/assets/vad/silero_vad.onnx"
URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx'
mkdir -p "$(dirname "$DST")"
curl --fail --location --retry 3 --silent --show-error "$URL" -o "$DST.tmp"
BYTES="$(wc -c < "$DST.tmp")"
if [ "$BYTES" -lt 100000 ]; then
  rm -f "$DST.tmp"
  echo 'ERROR: Download too small; no model installed' >&2
  exit 1
fi
mv "$DST.tmp" "$DST"
echo "Ready: $DST ($BYTES bytes)"
