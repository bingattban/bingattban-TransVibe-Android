#!/usr/bin/env python3
"""Regression checks: CI must actually launch an R8-optimized Release APK.

This is a source-level guard; the Android emulator jobs perform the runtime check.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent


class ReleaseStartupGateContractTest(unittest.TestCase):
    def test_pull_request_emulator_builds_and_launches_real_release_variant(self):
        workflow = (ROOT / ".github/workflows/free-android-emulator.yml").read_text()
        smoke = (ROOT / "ci/free_emulator_smoke.sh").read_text()
        self.assertIn(":app:testDebugUnitTest :app:lintDebug", workflow)
        self.assertIn(":app:assembleRelease", workflow)
        self.assertLess(workflow.index(":app:testDebugUnitTest :app:lintDebug"),
                        workflow.index(":app:assembleRelease"))
        self.assertIn("ANDROID_KEYSTORE_PATH", workflow)
        self.assertIn("androiddebugkey", workflow)
        self.assertNotIn("secrets.ANDROID_KEY", workflow)
        self.assertIn("ci/release_apk_smoke.sh", smoke)
        self.assertIn("app/build/outputs/apk/release/app-release.apk", smoke)

    def test_official_signed_release_launches_before_publishing(self):
        workflow = (ROOT / ".github/workflows/signed-android-release.yml").read_text()
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        self.assertIn("ci/release_apk_smoke.sh", workflow)
        self.assertLess(workflow.index("ci/release_apk_smoke.sh"),
                        workflow.index("name: Publish signed RELEASE APK"))
        self.assertIn("if: always()", workflow)
        self.assertIn("build/emulator-diagnostics/", workflow)

    def test_emulator_smoke_detects_real_startup_crashes(self):
        smoke = (ROOT / "ci/release_apk_smoke.sh").read_text()
        for required in ("adb install", "adb logcat -c", "am start -W",
                         "pidof", "FATAL EXCEPTION", "Process:", "exit 1"):
            self.assertIn(required, smoke)


if __name__ == "__main__":
    unittest.main(verbosity=2)
