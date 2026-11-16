# TransVibe Android

TransVibe is an offline-first Android app for video speech recognition, Arabic
subtitle translation, and SRT/VTT export. Kotlin, Jetpack Compose, Room,
Whisper.cpp/JNI and Sherpa-ONNX power the app. Supported native target:
**arm64-v8a**; Android **minSdk 26**, **targetSdk 36**.

## Repository structure

| Path | Purpose |
| --- | --- |
| `app/src/main/` | Android application, C++ JNI sources, UI and required assets |
| `app/src/test/` | Kotlin unit and regression tests |
| `app/src/androidTest/` | Device integration, JNI and opt-in performance tests |
| `app/libs/README.md` | Local AAR dependency instructions |
| `ci/` | Dependency verification, required vendored Whisper source and benchmark documentation |
| `tools/` | Source guards, native builders and performance comparison |
| `.github/workflows/android-ci.yml` | Tests-first CI workflow |
| `gradle/`, `gradlew*`, `*.gradle.kts` | Reproducible Gradle project and wrapper |

Historical single-use ZIP installation instructions and repair logs are
preserved in Git history, not in the current project tree.

## CI — tests before APK

All pull requests to `main` run source/JNI regression checks, the ARM64
reference CPU build, and the isolated **DotProd/FP16 experiment**. Once the
reference JNI binary is verified, Gradle runs Kotlin tests and Android Lint;
only then does CI produce a debug APK and an instrumentation-test APK.

The DotProd/FP16 binary is **not bundled** into the app APK. It requires a
supported ARMv8.2 device and real-device performance/correctness testing before
considering deployment. Vulkan acceleration is **not** enabled.

Dependency AARs for FFmpegKit 6.0-2 and Sherpa-ONNX 1.13.5 are downloaded at
CI time, pinned to the authentic SHA-256 checksums. They are not silently
substituted or committed here. See [CI setup](ci/README.md).

The Whisper 1.9.4 source archive at
`ci/vendor-whisper-source-1.9.4.zip` is **required** to rebuild JNI;
it is not a disposable export ZIP.

Download debug builds from successful [GitHub Actions runs](../../actions).
Running JNI, cancellation, SRT/VTT and speed benchmarks on a *real Android
device* remains a separate acceptance requirement; successful CI compilation
alone does not prove faster transcription.

Benchmark instructions and comparison rules:
[performance benchmarking](ci/PERFORMANCE_BENCHMARKING.md).

## Privacy and licensing

Keep downloaded model weights, private videos, user databases, signing keys and
local benchmark media out of this public repository. No user transcripts are
needed for the CI benchmarks. The FFmpegKit `min-gpl` distribution has GPLv3
obligations; review them before distributing an APK.
