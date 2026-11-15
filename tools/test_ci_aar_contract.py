"""CI tests of the FFmpegKit AAR hash/ABI guard; test fixtures are never packaged."""
import hashlib
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

PROJECT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = PROJECT / "tools" / "verify_ci_aar.py"
SCRIPT = PROJECT / "ci" / "download_verified_aars.sh"
FFMPEG_SHA256 = "11c1fd0ef76edb91fb3b7a1d6d0619914c914126896397367813f87805644c82"

def test_aar(include_native=True):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", "<manifest/>")
        archive.writestr("classes.jar", b"PK\x03\x04")
        if include_native:
            elf = bytearray(64)
            elf[:4] = b"\x7fELF"
            elf[4] = 2
            elf[5] = 1
            elf[16:18] = (3).to_bytes(2, "little")
            elf[18:20] = (183).to_bytes(2, "little")
            archive.writestr("jni/arm64-v8a/libffmpegkit.so", elf)
    return buffer.getvalue()

class CiAarContractTest(unittest.TestCase):
    def test_pinned_uploaded_ffmpeg_hash(self):
        self.assertIn(FFMPEG_SHA256, SCRIPT.read_text(encoding="utf-8"))

    def test_same_ffmpeg_version_from_mirror(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("/com/arthenica/ffmpeg-kit-min-gpl/6.0-2/ffmpeg-kit-min-gpl-6.0-2.aar", script)
        self.assertIn("python3 tools/verify_ci_aar.py", script)

    def call_verifier(self, content, digest=None):
        with tempfile.TemporaryDirectory() as temp_dir:
            file = pathlib.Path(temp_dir) / "fixture.aar"
            file.write_bytes(content)
            return subprocess.run(
                [sys.executable, str(VERIFIER), str(file),
                 "--sha256", digest or hashlib.sha256(content).hexdigest(),
                 "--native-lib", "libffmpegkit.so"],
                capture_output=True, text=True,
            )

    def test_arm64_aar_passes(self):
        result = self.call_verifier(test_aar())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_native_library_fails(self):
        self.assertNotEqual(self.call_verifier(test_aar(False)).returncode, 0)

    def test_wrong_digest_fails(self):
        self.assertNotEqual(self.call_verifier(test_aar(), "0" * 64).returncode, 0)

    def test_corrupted_archive_fails(self):
        self.assertNotEqual(self.call_verifier(b"not-a-zip-file").returncode, 0)

if __name__ == "__main__":
    unittest.main()
