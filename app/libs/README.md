# TransVibe — verified Android binaries included in this repository

The user-approved binaries are committed to `app/libs/` and are shipped with the
source checkout. Do not copy libraries manually to build the Android app.

| Path | Role |
| --- | --- |
| `ffmpeg-kit-min-gpl-6.0-2.aar` | Optional FFmpeg audio-extraction fallback |
| `sherpa-onnx-1.13.5.aar` | Offline fast-ASR recognizers, bundles `libonnxruntime.so` |
| `onnxruntime-android-1.27.0.aar` | Pinned standby dependency; **not linked** while Sherpa bundles ONNX |
| `smart-exception-java-0.2.1.jar` | FFmpegKit helper used in offline builds |
| `smart-exception-common-0.2.1.jar` | FFmpegKit runtime PackageLoader required alongside Java helper |

**Whisper JNI is NOT an AAR and must not be placed in this directory.**
The corrected, verified ARM64 CPU library is committed at
`app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so`.
The older owner-supplied Vulkan ELF was deliberately rejected because it lacks
the current `nativeBackendForContext` JNI method. This does **not** enable Vulkan.

Use `ci/PREBUILT_SHA256SUMS` and `python3 tools/test_vendored_android_libraries.py`
for exact SHA-256, ZIP/AAR structure and dependency checks. Native ELF and JNI
bindings are validated by `tools/verify_native_jni_contract.py`.

Gradle reads pinned AARs as `local.transvibe` Maven coordinates and stores
generated temporary copies under ignored `.gradle/transvibe-local-aars/`.
No model weights are bundled. Users add models to the installed app separately.

On the phone, use Code On The Go to run `:app:assembleDebug`. The native
library is **precompiled**; no Android NDK or CMake rebuild is performed by
the normal application Gradle build. First-time Gradle/Android dependencies
may still require the Internet even with the bundled AAR/JARs.

**Licensing:** FFmpegKit `min-gpl` contains GPL-3.0 license and source notices.
Before redistributing APKs, review these obligations and verify corresponding
sources and license text are made available. See `ci/VERIFIED_BINARIES.md`.
