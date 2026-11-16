#!/usr/bin/env python3
"""Safety and release-update contract for TransVibe's owner-managed Android key.

No keystore passwords or private keys are read or printed by these tests.
The signed workflow must be gated by successful trusted-main Android CI.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app/build.gradle.kts"
CI = ROOT / ".github/workflows/android-ci.yml"
SIGNED = ROOT / ".github/workflows/signed-android-release.yml"


class SignedReleaseContractTest(unittest.TestCase):
    def test_package_id_stays_stable_and_version_advances(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('applicationId = "com.transvibe.app"', gradle)
        version_code = re.search(r"\bversionCode\s*=\s*(\d+)", gradle)
        self.assertIsNotNone(version_code)
        self.assertGreaterEqual(int(version_code.group(1)), 2)
        self.assertRegex(gradle, r'versionName\s*=\s*"1\.0\.1"')

    def test_secret_passwords_never_embedded_in_gradle(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        for name in ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD",
                     "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
            self.assertIn(name, gradle)
        self.assertIn("signingConfigs", gradle)
        self.assertIn('create("transvibeRelease")', gradle)
        self.assertIn('signingConfigs.getByName("transvibeRelease")', gradle)
        self.assertNotIn('storePassword = "', gradle)
        self.assertNotIn('keyPassword = "', gradle)

    def test_secrets_are_only_accessed_by_trusted_main_success(self):
        release = SIGNED.read_text(encoding="utf-8")
        self.assertIn("workflow_run:", release)
        self.assertIn("Android Tests First and ARM64 APK", release)
        self.assertNotIn("pull_request:", release)
        self.assertNotIn("workflow_dispatch:", release)
        for gate in ("workflow_run.conclusion == 'success'",
                     "workflow_run.event == 'push'",
                     "workflow_run.head_branch == 'main'",
                     "workflow_run.head_repository.full_name == github.repository"):
            self.assertIn(gate, release)
        self.assertIn("github.event.workflow_run.head_sha", release)
        self.assertIn('git rev-parse FETCH_HEAD', release)
        self.assertIn('git rev-parse HEAD', release)

    def test_release_key_is_private_and_signature_verified(self):
        release = SIGNED.read_text(encoding="utf-8")
        for secret in ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
                       "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"):
            self.assertIn("secrets." + secret, release)
        self.assertIn("base64 --decode", release)
        self.assertIn("umask 077", release)
        self.assertIn("RUNNER_TEMP", release)
        self.assertIn("keytool -exportcert", release)
        self.assertIn("apksigner", release)
        self.assertIn("verify --verbose", release)
        self.assertIn("actions/upload-artifact@", release)
        self.assertIn("if: always()", release)
        self.assertNotIn("signing.jks", "\n".join(str(p.relative_to(ROOT))
            for p in ROOT.rglob("*.jks")))

    def test_release_is_built_after_automated_tests(self):
        ci = CI.read_text(encoding="utf-8")
        self.assertLess(ci.index(":app:testDebugUnitTest :app:lintDebug"),
                        ci.index(":app:assembleDebug"))
        self.assertLess(ci.index(":app:assembleDebug"),
                        ci.index(":app:assembleRelease"))
        release = SIGNED.read_text(encoding="utf-8")
        self.assertIn(":app:assembleRelease", release)
        self.assertIn("sha256sum -c ci/PREBUILT_SHA256SUMS", release)

    def test_no_private_signing_files_tracked(self):
        tracked = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT,
                                 check=True, stdout=subprocess.PIPE).stdout
        paths = [p.decode("utf-8") for p in tracked.split(b"\0") if p]
        self.assertFalse([p for p in paths if p.endswith((".jks", ".keystore", ".p12", ".pfx"))])


if __name__ == "__main__":
    unittest.main(verbosity=2)
