#!/usr/bin/env python3
"""Check that real R8 mapping retains reflective ML Kit registrar constructors.

Firebase ComponentDiscovery reads registrar *names* from AndroidManifest metadata
then invokes a public no-argument constructor through reflection. R8 can
otherwise remove the constructor while leaving the class itself present.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

REGISTRARS = (
    "com.google.mlkit.common.internal.CommonComponentRegistrar",
    "com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar",
)


def main() -> int:
    mapping = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/build/outputs/mapping/release/mapping.txt")
    if not mapping.is_file():
        print(f"ERROR: missing R8 mapping: {mapping}", file=sys.stderr)
        return 1

    # Streaming scan avoids loading an approximately 65 MB mapping into RAM.
    found = {name: False for name in REGISTRARS}
    active = None
    with mapping.open(encoding="utf-8") as source:
        for line in source:
            if not line.startswith((" ", "#")) and " -> " in line and line.endswith(":\n"):
                name = line.split(" -> ", 1)[0].strip()
                active = name if name in found else None
            elif active and "<init>()" in line and "-> <init>" in line:
                found[active] = True
    for registrar, retained in found.items():
        print(f"{'PASS' if retained else 'FAIL'} R8 registrar no-arg constructor: {registrar}")
    if not all(found.values()):
        print("ERROR: ML Kit reflective registrars are missing public no-argument constructors. "
              "Preserve ComponentRegistrar.<init>() in app/proguard-rules.pro.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
