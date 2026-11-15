#!/usr/bin/env python3
"""Reject wrong, damaged or non-ARM64 Android AARs before Gradle packaging."""
import argparse
import hashlib
import pathlib
import sys
import zipfile

def verify(path: pathlib.Path, sha: str, lib: str) -> None:
    if not path.is_file():
        raise ValueError(f"Missing AAR: {path}")
    if len(sha) != 64 or any(c not in "0123456789abcdefABCDEF" for c in sha):
        raise ValueError("Expected SHA-256 must have exactly 64 hexadecimal characters")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest.lower() != sha.lower():
        raise ValueError(f"SHA-256 mismatch for {path.name}: got {digest}, expected {sha}")
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("AAR contains corrupted ZIP members")
        names = set(archive.namelist())
        native_path = f"jni/arm64-v8a/{lib}"
        for required in ("AndroidManifest.xml", "classes.jar", native_path):
            if required not in names:
                raise ValueError(f"Missing AAR entry: {required}")
        with archive.open(native_path) as stream:
            elf = stream.read(20)
        if len(elf) < 20 or elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
            raise ValueError(f"{lib}: expected ELF64 little-endian native code")
        if int.from_bytes(elf[18:20], "little") != 183:
            raise ValueError(f"{lib}: native code is not AArch64")
    print(f"VERIFIED: {path.name}, SHA256={digest}, arm64-v8a/{lib}")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=pathlib.Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--native-lib", required=True)
    args = parser.parse_args()
    try:
        verify(args.aar, args.sha256, args.native_lib)
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
