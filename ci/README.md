# Android CI — verified dependencies and tests-first builds

Source repository: `bingattban/bingattban-TransVibe-Android`.
The separate `bingattban/TransVibe-Native-Builder` repository can be used
for native research; Android CI is self-contained.

## Required checks

1. Source, JNI, repository-layout and benchmark-comparison regression tests.
2. Reproducible ARM64 **CPU reference** JNI build using Whisper.cpp 1.9.4,
   Android NDK 28.2.13676358, API 26.
3. **Separate non-shipping** ARMv8.2 DotProd/FP16 JNI experimental build and
   ELF/JNI verification.
4. Install verified dependency AARs; run Gradle Kotlin tests and Android Lint
   before building debug and androidTest APKs.

All required jobs must pass on the **same commit** before merging.
The test APK is built in CI but device instrumentation tests are not claimed to
have run until an Android device has actually executed them.
No billable Firebase Test Lab session is launched by this workflow.

## Original AAR provenance

Owner-supplied `ffmpeg-kit-min-gpl-6.0-2.aar` (2026-11-15):
36,190,865 bytes, SHA-256
`11c1fd0ef76edb91fb3b7a1d6d0619914c914126896397367813f87805644c82`.

CI downloads the exact historical artifact and checks SHA-256 before Gradle.
The same mirrored package passed this check in GitHub Actions run
[43903062476](https://github.com/bingattban/bingattban-TransVibe-Android/actions/runs/43903062476).
If its source becomes unavailable, set `FFMPEG_AAR_URL` in GitHub Actions
Variables to another authentic location while retaining the pinned checksum.
Never replace the file silently or remove checksum verification.

Sherpa-ONNX 1.13.5 AAR is downloaded from its upstream release and verified
against SHA-256 `6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b`.
Optional URL/SHA overrides are `SHERPA_AAR_URL` and `SHERPA_AAR_SHA256`;
an override must point to an inspected authentic matching file.

The binary dependencies are intentionally not committed to this public source
tree. FFmpegKit `min-gpl` carries GPLv3 distribution obligations and includes
license/source notice assets; consult these before public APK distribution.

## Files that must remain

- `ci/vendor-whisper-source-1.9.4.zip`: pinned Whisper source needed for
  every native build (not an obsolete snapshot).
- `app/src/debug/AndroidManifest.xml`: Code On The Go-specific debug provider
  guard retained for compatibility. The GitHub workflow excludes it while
  running ordinary Android Gradle builds.
- `gradle/wrapper/gradle-wrapper.jar`: required Gradle wrapper bootstrap.
- `app/src/androidTest/assets/`: reference audio fixtures used by tests.
- `tools/` and `app/src/test/`: CI checks and regression coverage.

The tracked-layout guard rejects historical root repair reports, unapproved
ZIP snapshots and generated APK/build artifacts. Do not remove JNI source,
native tests, Gradle files, app resources, or the benchmark toolkit as
"unused" without a proven replacement and passing CI.

## Hardware performance measurements

See [PERFORMANCE_BENCHMARKING.md](PERFORMANCE_BENCHMARKING.md).
The experimental DotProd binary is **not** the one bundled into the standard
APK. No quantified speed improvement or Vulkan enablement is claimed.
