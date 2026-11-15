# TransVibe Android — Continuous Testing (ARM64)

Offline Android video transcription and subtitle translation (Kotlin, Jetpack
Compose, Room, Whisper.cpp/JNI, Sherpa-ONNX, ML Kit, MediaCodec). Exports SRT/VTT.

This repository tracks the user's app source and tests, not personal videos,
installed model weights, databases, signing credentials or arbitrary AARs.

## CI

`.github/workflows/android-ci.yml` first validates source and JNI contract,
then builds ARM64 CPU JNI using NDK, then runs Android unit tests and lint before
packaging Debug APK/test APK. A real-device run is a separate acceptance gate.

**Important:** The original FFmpegKit AAR used on the phone is not available in
this repository. Until its authentic download URL and checksum are configured,
`android-tests-then-apk` deliberately fails with an actionable error.
Read [CI setup](ci/README.md).

Native source is restored from `ci/vendor-whisper-source-1.9.4.zip`, generated
from the user-provided TransVibe source bundle, rather than silently replacing
its custom Whisper fork. The resulting `.so` is a CPU **reference** build, not
a proven speed improvement.

GitHub Actions: [workflows](../../actions)
Native reference repository: [TransVibe-Native-Builder](https://github.com/bingattban/TransVibe-Native-Builder)

The core app still targets `arm64-v8a`, Android minSdk 26, targetSdk 36.
