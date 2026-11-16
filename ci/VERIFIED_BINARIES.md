# Verified prebuilt binary inventory (2026-11-16)

The owner authorized including the inspected `libs.zip` dependencies in the
**public** Git repository. Each committed AAR/JAR matches the **exact SHA-256**
of the owner's archive; the native JNI ELF does **not** come from the incompatible
Vulkan binary in that archive.

| File | SHA-256 |
| --- | --- |
| `app/libs/ffmpeg-kit-min-gpl-6.0-2.aar` | `11c1fd0ef76edb91fb3b7a1d6d0619914c914126896397367813f87805644c82` |
| `app/libs/sherpa-onnx-1.13.5.aar` | `6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b` |
| `app/libs/onnxruntime-android-1.27.0.aar` | `077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb` |
| `app/libs/smart-exception-java-0.2.1.jar` | `5b96aaa5f191dedbef72fb0c38f1a2b01807920afc0d92a75a2acd6e0cc7703c` |
| `app/src/main/jniLibs/arm64-v8a/libtransvibe_native.so` | `b80f23ea9a322c7e0f2e4de7508c882103d6a43333e7050417d8c4b02cce8691` |

The machine-readable authority is `ci/PREBUILT_SHA256SUMS`. The verified CPU
library was produced by a successful Android GitHub Actions run
[43907371011](https://github.com/bingattban/bingattban-TransVibe-Android/actions/runs/43907371011)
from the checked-in `whisper.cpp` 1.9.4 source and NDK 28.2.13676358.
Its ELF/JNI symbols were independently checked before committing.

The original attached Vulkan ELF had SHA-256
`883ba32b764037277cd4f2bc5ce32ce29b7aa504f13a7439f9c61f92ab2bf648`
and did not contain `nativeBackendForContext`. Do not substitute it.
The CPU ELF preserves broad ARM64 compatibility; neither Vulkan nor
the experimental DotProd binary is packaged by default.

## Dependency sources and redistribution

- FFmpegKit 6.0-2 `min-gpl` was matched to the user archive using the
  historical Maven mirror URL in `ci/download_verified_aars.sh`.
  The AAR includes `res/raw/license.txt` (GPL v3) and
  `res/raw/source.txt` (corresponding-source information):
  https://github.com/arthenica/ffmpeg-kit/wiki/Source
- Sherpa-ONNX 1.13.5 originated from the upstream GitHub release
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.5
- ONNX Runtime 1.27.0 and Smart Exception 0.2.1 are byte-matched to
  the original user archives and to Maven Central coordinates.
- Review all component licenses, corresponding sources, and their notices
  before publishing a distributed APK. Simply committing an AAR does not
  discharge source-distribution and attribution obligations.

Standalone ONNX Runtime is kept at the owner's request for reproducibility
and fallback, but is **excluded from Gradle packaging** as long as the
Sherpa AAR already contains `libonnxruntime.so`.

The source-control policy is pinned: unapproved extra `.aar`, `.jar`,
and `.so` blobs fail `tools/test_repository_layout.py`; any change to
one of these versions requires updated tests, manifest and full Android CI.
