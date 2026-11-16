#!/usr/bin/env python3
"""Regression tests for ML Kit ComponentRegistrar reflection in minified Release.

This test is deliberately expected to fail until the R8 keep fix is applied.
The real Android emulator startup test is the end-to-end acceptance gate.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent


class MlKitRegistrarReleaseRegressionTest(unittest.TestCase):
    def test_proguard_preserves_reflected_component_registrar_constructors(self):
        rules = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8")
        self.assertRegex(
            rules,
            r"-keep\s+class\s+\*\s+implements\s+com\.google\.firebase\.components\.ComponentRegistrar\s*\{\s*<init>\(\);\s*\}",
            "R8 must retain no-arg constructors instantiated by Firebase ComponentDiscovery.",
        )

    def test_release_build_inspects_actual_r8_mapping(self):
        for workflow in [
            ".github/workflows/free-android-emulator.yml",
            ".github/workflows/signed-android-release.yml",
        ]:
            content = (ROOT / workflow).read_text(encoding="utf-8")
            self.assertIn("ci/verify_mlkit_registrars.py", content)
            self.assertLess(
                content.index(":app:assembleRelease"),
                content.index("ci/verify_mlkit_registrars.py"),
            )

    def test_mapping_guard_checks_common_and_translation_components(self):
        guard = (ROOT / "ci/verify_mlkit_registrars.py").read_text(encoding="utf-8")
        self.assertIn("com.google.mlkit.common.internal.CommonComponentRegistrar", guard)
        self.assertIn("com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar", guard)
        self.assertIn("<init>()", guard)


if __name__ == "__main__":
    unittest.main(verbosity=2)
