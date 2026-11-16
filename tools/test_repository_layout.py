#!/usr/bin/env python3
"""Reject out-of-structure tracked files and generated artifacts, without touching untracked files."""
from __future__ import annotations

import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
ALLOWED_ROOT = frozenset({
    ".gitignore", "README.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat",
})
ALLOWED_DIRS = frozenset({".github", "app", "ci", "gradle", "tools"})
GENERATED_DIRS = frozenset({
    ".gradle", ".cxx", ".idea", ".externalNativeBuild",
    "build", "__pycache__", "node_modules", ".pytest_cache",
})
GENERATED_SUFFIXES = (".apk", ".aab", ".log", ".tmp", ".bak", ".orig", ".swp", ".pyc", ".class")
REQUIRED_VENDOR_ZIP = "ci/vendor-whisper-source-1.9.4.zip"


def find_strays(paths: list[str]) -> list[str]:
    problems: list[str] = []
    for path in paths:
        parts = pathlib.PurePosixPath(path).parts
        if not path or path.startswith("/") or ".." in parts:
            problems.append(f"Unsafe tracked path: {path!r}")
            continue
        if any(part in GENERATED_DIRS for part in parts):
            problems.append(f"Generated directory in Git: {path}")
            continue
        if len(parts) == 1 and path not in ALLOWED_ROOT:
            problems.append(f"Unexpected root file: {path}")
        elif len(parts) > 1 and parts[0] not in ALLOWED_DIRS:
            problems.append(f"Unexpected top-level directory: {path}")
        if path.endswith(GENERATED_SUFFIXES):
            problems.append(f"Generated output in Git: {path}")
        if path.endswith(".zip") and path != REQUIRED_VENDOR_ZIP:
            problems.append(f"Unapproved ZIP snapshot: {path}")
    return sorted(set(problems))


def tracked_files() -> list[str]:
    output = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"], check=True, capture_output=True
    ).stdout
    return [item.decode("utf-8") for item in output.split(b"\0") if item]


class ProjectLayoutTests(unittest.TestCase):
    def test_only_project_files_are_tracked(self):
        self.assertEqual([], find_strays(tracked_files()))

    def test_real_project_assets_are_accepted(self):
        self.assertEqual([], find_strays([
            "README.md", ".github/workflows/android-ci.yml",
            "app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml",
            "app/libs/README.md", "app/src/androidTest/assets/fixture.ogg",
            "gradle/wrapper/gradle-wrapper.jar", REQUIRED_VENDOR_ZIP,
            "tools/benchmark_compare.py", "ci/PERFORMANCE_BENCHMARKING.md",
        ]))

    def test_old_reports_and_archives_are_rejected(self):
        self.assertGreaterEqual(len(find_strays([
            "REPAIR_LOG_AR.md", "README_INSTALL_AR.txt",
            "TransVibe_2026-11-15.zip", "app/build/outputs/app.apk",
            "tools/__pycache__/test.pyc",
        ])), 5)

    def test_unrelated_archive_is_rejected_even_inside_ci(self):
        self.assertEqual(1, len(find_strays(["ci/transvibe-old.zip"])))


if __name__ == "__main__":
    unittest.main(verbosity=2)
