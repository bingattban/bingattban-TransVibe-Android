#!/usr/bin/env bash
# Emit a focused R8 mapping excerpt so an obfuscated cold-start failure
# can be tied back to original Java/Kotlin symbols. No keystore or secrets.
set -Eeuo pipefail
map_file="app/build/outputs/mapping/release/mapping.txt"
test -s "$map_file"
echo "R8 mapping identity:"
grep -m 1 -E 'pg_map_id|com.android.tools.r8.mapping' "$map_file" || true
echo "R8 class-name diagnostics for the reproduced cold-start stack:"
# These obfuscated names were observed in GitHub emulator smoke run 43937740037.
# Class names are diagnostic hints only: the full mapping is also preserved.
awk '
  /^[^[:space:]].* -> (ot3|dh|wj3|s05|g00|b20):$/ {
    found=1; remaining=90; print; next
  }
  /^[^[:space:]]/ { found=0 }
  found && remaining-->0 { print }
' "$map_file"
