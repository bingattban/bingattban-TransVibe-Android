#!/usr/bin/env python3
"""Strict, read-only prebuilt Whisper JNI source/Kotlin/ELF contract check.

This is a build-gate diagnostic, not a substitute for Android instrumentation.
It deliberately fails for the known legacy prebuilt missing nativeBackendForContext.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import struct
import sys

BRIDGE_CLASS = 'com/transvibe/app/nativebridge/whisper/WhisperNative'
REQUIRED = {
    'nativeCreateContext': '(Ljava/lang/String;)J',
    'nativeBackendForContext': '(J)Ljava/lang/String;',
    'nativeDetectLanguageDetailed': '(J[FIII)Lcom/transvibe/app/nativebridge/whisper/WhisperLanguageDetectionResult;',
    'nativeIsMultilingual': '(J)Z',
    'nativeModelTypeReadable': '(J)Ljava/lang/String;',
    'nativeTranscribeStructured': '(J[FIILjava/lang/String;ZILjava/lang/String;)[Lcom/transvibe/app/nativebridge/whisper/WhisperSegmentResult;',
    'nativeBridgeSelfTest': '(Ljava/lang/String;)Lcom/transvibe/app/nativebridge/whisper/WhisperSegmentResult;',
    'nativeCancel': '(J)V',
    'nativeReleaseContext': '(J)V',
}


def check_elf_arm64(blob: bytes) -> list[str]:
    faults=[]
    if len(blob) < 64 or blob[:4] != b'\x7fELF':
        faults.append('File is not ELF');return faults
    if blob[4] != 2 or blob[5] != 1:
        faults.append('Expected ELF64 little-endian')
    elf_type, machine = struct.unpack_from('<HH',blob,16)
    if elf_type != 3 or machine != 183:
        faults.append(f'Expected ET_DYN AArch64 (type={elf_type}, machine={machine})')
    return faults


def registrations(cpp: str) -> dict[str,str]:
    match=re.search(r'static\s+const\s+JNINativeMethod\s+methods\s*\[\s*\]\s*=\s*\{(.*?)\n\s*\};',cpp,re.S)
    if not match: raise ValueError('Cannot find JNINativeMethod registration table')
    expression=re.compile(r'\{\s*const_cast<char\s*\*>\("(native\w+)"\)\s*,\s*const_cast<char\s*\*>\("([^"]+)"\)\s*,\s*reinterpret_cast<void\s*\*>\(\s*(native\w+)\s*\)\s*\}')
    found={}
    for name,descriptor,symbol in expression.findall(match.group(1)):
        if name != symbol: raise ValueError(f'{name} points to unexpected C++ function {symbol}')
        if name in found: raise ValueError(f'Duplicate registration: {name}')
        found[name]=descriptor
    return found


def missing_registration_names(blob: bytes, expected: dict[str,str]) -> list[str]:
    return [name for name in expected if name.encode('ascii') + b'\x00' not in blob]


def inspect(root:Path, so:Path) -> tuple[list[str],list[str]]:
    cpp_path=root/'app/src/main/cpp/native-lib.cpp'
    kotlin_path=root/'app/src/main/java/com/transvibe/app/nativebridge/whisper/WhisperNative.kt'
    faults=[]; output=[]
    cpp=cpp_path.read_text(encoding='utf-8')
    kt=kotlin_path.read_text(encoding='utf-8')
    symbols=registrations(cpp)
    for name, descriptor in REQUIRED.items():
        if symbols.get(name) != descriptor:
            faults.append(f'Source registration mismatch: {name}: expected {descriptor}, found {symbols.get(name)}')
        if not re.search(r'\bexternal\s+fun\s+' + re.escape(name) + r'\s*\(',kt):
            faults.append(f'Missing Kotlin external method: {name}')
    for name in symbols.keys() - REQUIRED.keys():
        faults.append(f'Unexpected JNI registration: {name}')
    if not so.is_file():return faults+[f'Missing real prebuilt library: {so}'],output
    blob=so.read_bytes()
    faults+=check_elf_arm64(blob)
    output.append('size_bytes='+str(len(blob)))
    output.append('sha256='+hashlib.sha256(blob).hexdigest())
    missing=set(missing_registration_names(blob,REQUIRED))
    for name in REQUIRED:
        # NUL-terminated registration names in the binary; cannot prove function pointers or behavior.
        matches=blob.count(name.encode('ascii')+b'\x00')
        output.append(f'{name}: '+('PRESENT' if matches else 'MISSING')+f' (NUL-terminated matches={matches})')
        if name in missing: faults.append(f'Missing prebuilt JNI registration-name string: {name}')
    if b'JNI_OnLoad\x00' not in blob:
        faults.append('Cannot find JNI_OnLoad name in prebuilt library')
    return faults,output


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--library',type=Path)
    opts=parser.parse_args()
    root=opts.project.resolve()
    so=opts.library or (root/'app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so')
    try: faults, lines=inspect(root,so)
    except (OSError,ValueError) as e: print('FAIL:',e);return 2
    print(f'JNI class: {BRIDGE_CLASS}; required methods: {len(REQUIRED)}')
    print('\n'.join(lines))
    if faults:
        print('FAIL: '+str(len(faults))+' issue(s):')
        for err in faults:print(' - '+err)
        print('This check is a strict stage gate; DO NOT patch the legacy ELF in place.')
        return 1
    print('PASS (static contract only; still run on-device JNI smoke tests)')
    return 0

if __name__=='__main__':sys.exit(main())
