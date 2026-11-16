#!/usr/bin/env python3
"""Regression test: FFmpegKit's two Smart Exception JARs are both present.

A local FFmpegKit AAR does not import the Maven transitive dependencies.
Only including smart-exception-java is insufficient: PackageLoader belongs to
smart-exception-common and Class.forName(FFmpegKitConfig) fails at runtime.
"""
from pathlib import Path
from zipfile import ZipFile
import unittest

ROOT = Path(__file__).resolve().parent.parent
COMMON = ROOT / "app/libs/smart-exception-common-0.2.1.jar"
JAVA = ROOT / "app/libs/smart-exception-java-0.2.1.jar"
GRADLE = ROOT / "app/build.gradle.kts"

class FfmpegKitSmartExceptionDependencyTest(unittest.TestCase):
    def test_common_jar_includes_package_loader(self):
        self.assertTrue(COMMON.is_file(), "Vendor the upstream common 0.2.1 JAR")
        with ZipFile(COMMON) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn("com/arthenica/smartexception/PackageLoader.class", archive.namelist())

    def test_java_jar_includes_exception_formatter(self):
        with ZipFile(JAVA) as archive:
            self.assertIn("com/arthenica/smartexception/java/Exceptions.class", archive.namelist())

    def test_both_jars_are_linked_in_gradle(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn("libs/smart-exception-common-0.2.1.jar", gradle)
        self.assertIn("implementation(files(localSmartExceptionCommon))", gradle)
        self.assertIn("implementation(files(localSmartException))", gradle)

if __name__ == "__main__":
    unittest.main(verbosity=2)
