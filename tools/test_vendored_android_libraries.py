#!/usr/bin/env python3
"""Pinned provenance tests for the real, committed Android binaries.

The native ELF is built from the repository's pinned Whisper sources, not copied
from the user-supplied legacy Vulkan library with a missing JNI registration.
"""
from __future__ import annotations

import hashlib
import pathlib
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "ci/PREBUILT_SHA256SUMS"
PINNED = {
    "app/libs/ffmpeg-kit-min-gpl-6.0-2.aar":
        "11c1fd0ef76edb91fb3b7a1d6d0619914c914126896397367813f87805644c82",
    "app/libs/sherpa-onnx-1.13.5.aar":
        "6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b",
    "app/libs/onnxruntime-android-1.27.0.aar":
        "077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb",
    "app/libs/smart-exception-java-0.2.1.jar":
        "5b96aaa5f191dedbef72fb0c38f1a2b01807920afc0d92a75a2acd6e0cc7703c",
    "app/libs/smart-exception-common-0.2.1.jar":
        "1cad0fb4dfa01755a014331b5ed199281d2c3fab5aca5c9d7abd0b41d0ec3f7b",
}
NATIVE = "app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so"
EXPECTED_PATHS = frozenset((*PINNED, NATIVE))
REQUIRED_NATIVE_AAR = {
    "app/libs/ffmpeg-kit-min-gpl-6.0-2.aar": "libffmpegkit.so",
    "app/libs/sherpa-onnx-1.13.5.aar": "libsherpa-onnx-jni.so",
    "app/libs/onnxruntime-android-1.27.0.aar": "libonnxruntime.so",
}

def manifest_records():
    if not MANIFEST.is_file():
        raise AssertionError("Missing pinned binary SHA256 manifest")
    entries = {}
    for line in MANIFEST.read_text(encoding="ascii").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        digest, sep, path = line.partition("  ")
        if not sep or len(digest) != 64 or path in entries:
            raise AssertionError("Invalid SHA256SUMS entry")
        entries[path] = digest.lower()
    return entries

class VendoredAndroidLibrariesTest(unittest.TestCase):
    def test_manifest_contains_only_approved_binaries(self):
        entries = manifest_records()
        self.assertEqual(EXPECTED_PATHS, frozenset(entries))
        for path, expected in PINNED.items():
            self.assertEqual(expected, entries[path], path)

    def test_every_vendored_binary_matches_pinned_sha256(self):
        for path, expected in manifest_records().items():
            binary = ROOT / path
            self.assertTrue(binary.is_file(), path)
            with binary.open("rb") as handle:
                digest = hashlib.file_digest(handle, "sha256").hexdigest()
            self.assertEqual(expected, digest, path)
            self.assertLess(binary.stat().st_size, 100 * 1024 * 1024, path)

    def test_required_aars_contain_real_arm64_libraries(self):
        for path, library in REQUIRED_NATIVE_AAR.items():
            with zipfile.ZipFile(ROOT / path) as aar:
                self.assertIsNone(aar.testzip(), path)
                self.assertIn("AndroidManifest.xml", aar.namelist())
                self.assertIn("jni/arm64-v8a/" + library, aar.namelist())

    def test_common_jar_contains_ffmpeg_runtime_loader(self):
        with zipfile.ZipFile(ROOT / "app/libs/smart-exception-common-0.2.1.jar") as jar:
            self.assertIsNone(jar.testzip())
            self.assertIn("com/arthenica/smartexception/PackageLoader.class", jar.namelist())

    def test_smart_exception_jar_contains_classes(self):
        with zipfile.ZipFile(ROOT / "app/libs/smart-exception-java-0.2.1.jar") as jar:
            self.assertIsNone(jar.testzip())
            self.assertTrue(any(name.endswith(".class") for name in jar.namelist()))

    def test_sherpa_bundles_onnx_and_gradle_avoids_duplicate_packaging(self):
        with zipfile.ZipFile(ROOT / "app/libs/sherpa-onnx-1.13.5.aar") as aar:
            self.assertIn("jni/arm64-v8a/libonnxruntime.so", aar.namelist())
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("if (useStandaloneOnnxRuntime)", gradle)

if __name__ == "__main__":
    unittest.main(verbosity=2)
