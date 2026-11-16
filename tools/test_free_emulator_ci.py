#!/usr/bin/env python3
"""Source guard for the free, real-APK emulator smoke lane (not a device benchmark)."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent

class FreeEmulatorCiContractTest(unittest.TestCase):
    def test_runner_is_standard_github_public_ubuntu_not_paid_device_farm(self):
        workflow = (ROOT / ".github/workflows/free-android-emulator.yml").read_text()
        self.assertIn("runs-on: ubuntu-24.04", workflow)
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        self.assertIn("api-level: 35", workflow)
        self.assertIn("arch: x86_64", workflow)
        for billed in ("firebase", "devicefarm", "test-lab", "google cloud"):
            self.assertNotIn(billed, workflow.lower())

    def test_unit_and_lint_run_before_apk_then_instrumentation(self):
        workflow = (ROOT / ".github/workflows/free-android-emulator.yml").read_text()
        steps = [
            ":app:testDebugUnitTest :app:lintDebug",
            ":app:assembleDebug :app:assembleDebugAndroidTest",
            "bash ci/free_emulator_smoke.sh",
        ]
        offsets = [workflow.index(step) for step in steps]
        self.assertEqual(sorted(offsets), offsets)

    def test_smoke_script_has_strict_instrumentation_result_checks(self):
        script = (ROOT / "ci/free_emulator_smoke.sh").read_text()
        self.assertIn("INSTRUMENTATION_CODE: -1", script)
        self.assertIn("OK \\(", script)
        self.assertIn("AndroidEmulatorSmokeTest", script)
        self.assertIn("PerformanceBenchmarkContractTest", script)
        self.assertIn("FfmpegSafContractInstrumentationTest", script)
        self.assertIn("pipefail", script)

    def test_no_perf_claim_on_emulated_arm_runtime(self):
        docs = (ROOT / "ci/FREE_EMULATOR_TESTING.md").read_text()
        self.assertIn("not a phone-speed benchmark", docs.lower())
        self.assertIn("WhisperOnDeviceBenchmarkTest", docs)
        self.assertIn("manual", docs.lower())
        self.assertIn("no user model", docs.lower())

if __name__ == "__main__":
    unittest.main(verbosity=2)
