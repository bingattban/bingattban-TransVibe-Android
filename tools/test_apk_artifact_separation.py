#!/usr/bin/env python3
"""Tests-first two-artifact output contract for TransVibe on GitHub.

Testing must install independently of the owner's signed release, without
access to private keystore secrets and without shipping instrumentation APK.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent

class IndependentApkArtifactsTest(unittest.TestCase):
    def test_testing_variant_is_a_separate_installable_application(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('create("preview")', gradle)
        self.assertIn('applicationIdSuffix = ".preview"', gradle)
        self.assertIn('matchingFallbacks.add("debug")', gradle)
        self.assertIn('initWith(getByName("debug"))', gradle)
        self.assertIn('applicationId = "com.transvibe.app"', gradle)
        self.assertIn('signingConfigs.getByName("transvibeRelease")', gradle)
        self.assertTrue((ROOT / "app/src/preview/res/values/strings.xml").is_file())
        self.assertIn("TransVibe Test", (ROOT / "app/src/preview/res/values/strings.xml").read_text())

    def test_tests_first_then_test_and_unsigned_release_are_checked(self):
        ci = (ROOT / ".github/workflows/android-ci.yml").read_text()
        self.assertLess(ci.index(":app:testDebugUnitTest :app:lintDebug"), ci.index(":app:assemblePreview"))
        self.assertIn(":app:assembleDebugAndroidTest", ci)
        self.assertIn(":app:assembleRelease", ci)
        self.assertIn("app/build/outputs/apk/preview/app-preview.apk", ci)

    def test_signed_workflow_publishes_exactly_two_separate_apks(self):
        workflow = (ROOT / ".github/workflows/signed-android-release.yml").read_text()
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn(":app:assemblePreview", workflow)
        self.assertIn(":app:assembleRelease", workflow)
        self.assertLess(workflow.index(":app:assemblePreview"), workflow.index(":app:assembleRelease"))
        self.assertIn("dist/test/TransVibe-v1.0.3-TEST.apk", workflow)
        self.assertIn("dist/release/TransVibe-v1.0.3-RELEASE.apk", workflow)
        self.assertIn("name: transvibe-v1.0.3-TEST", workflow)
        self.assertIn("name: transvibe-v1.0.3-RELEASE", workflow)
        self.assertIn('com.transvibe.app.preview', workflow)
        self.assertIn("com.transvibe.app", workflow)
        self.assertNotIn('create("testing")', gradle)
        self.assertIn("apksigner", workflow)
        self.assertIn("actions/upload-artifact@", workflow)
        self.assertIn("workflow_run:", workflow)
        self.assertIn("workflow_run.conclusion == 'success'", workflow)
        self.assertNotIn("pull_request:", workflow)

    def test_preview_installed_from_actual_apk_in_free_emulator(self):
        smoke = (ROOT / "ci/free_emulator_smoke.sh").read_text()
        workflow = (ROOT / ".github/workflows/free-android-emulator.yml").read_text()
        self.assertIn(":app:assemblePreview", workflow)
        self.assertIn("app/build/outputs/apk/preview/app-preview.apk", smoke)
        self.assertIn("com.transvibe.app.preview", smoke)
        self.assertIn("adb shell am start -W", smoke)

    def test_testing_apk_build_does_not_receive_owner_signing_secrets(self):
        workflow = (ROOT / ".github/workflows/signed-android-release.yml").read_text()
        build_testing = workflow[workflow.index("name: Build separate testing APK"):workflow.index("name: Restore owner's signing keystore")]
        self.assertIn(":app:assemblePreview", build_testing)
        self.assertNotIn("secrets.ANDROID_", build_testing)
        self.assertNotIn("ANDROID_KEYSTORE_PATH", build_testing)

if __name__ == "__main__":
    unittest.main(verbosity=2)
