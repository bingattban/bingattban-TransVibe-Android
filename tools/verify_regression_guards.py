#!/usr/bin/env python3
"""Source-level safety checks when Android SDK/device is unavailable.
Not a substitute for Gradle unit and instrumentation tests.
"""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
android_tests = root / 'app/src/androidTest/java/com/transvibe/app'
source = root / 'app/src/main/java/com/transvibe/app'
files = list(android_tests.rglob('*.kt'))
assert files, 'No instrumentation sources found'
for path in files:
    content = path.read_text(encoding='utf-8')
    assert 'targetContext.filesDir.resolve("models").deleteRecursively()' not in content, path
    assert 'File(context.filesDir, "sessions").deleteRecursively()' not in content, path
    assert '.debugfiles' not in content, path

coordinator = (source / 'service/ProcessingCoordinator.kt').read_text(encoding='utf-8')
assert 'bridge.prepare()' not in coordinator
assert coordinator.count('bridge.requireInstalledForProcessing()') == 2

integration = (android_tests / 'integration/ProcessingWorkflowIntegrationTest.kt').read_text(encoding='utf-8')
assert '${context.packageName}.fileprovider' in integration
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert '${applicationId}.fileprovider' in manifest

history = (source / 'data/persistence/RoomProcessingHistoryRepository.kt').read_text(encoding='utf-8')
assert 'internalFiles.deleteJobDirectory(jobId)' in history
assert 'dao.deleteJobWithSegments(jobId) == 0' in history

gradle = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
assert 'ffmpeg-kit-min-gpl-6.0-2.aar' in gradle
assert 'ffmpeg-kit-free81-8.1.9-arm64-v8a-x86_64.aar' in gradle
print('PASS: regression guard checks')
