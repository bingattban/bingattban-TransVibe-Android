# Code On The Go — TransVibe offline Android libraries

Copy these **real** binary files into this exact folder (`app/libs`) on the phone:

- `sherpa-onnx-1.13.5.aar`
- `onnxruntime-android-1.27.0.aar` (used only if Sherpa AAR does not bundle `libonnxruntime.so`)
- `ffmpeg-kit-min-gpl-6.0-2.aar` **OR** `ffmpeg-kit-free81-8.1.9-arm64-v8a-x86_64.aar`
- `smart-exception-java-0.2.1.jar` (optional when Maven Central is reachable)

These archives are not in this source ZIP because they were not uploaded. **Do not rename arbitrary files to `.aar`.**

After copying, refresh/sync Gradle and build `:app:assembleDebug`. In Code On The Go,
if an old `Null extracted folder` error persists, stop Gradle, clear the project-only
`.gradle/` and `app/build/` caches (DO NOT touch `app/libs` or application data), then retry.

`settings.gradle.kts` validates the ZIP structure (`AndroidManifest.xml` and
`classes.jar`, if present) and stages local Maven copies under `.gradle/transvibe-local-aars`.
This bypasses the direct local-file artifact variant. If there is still an extraction
error, provide `--stacktrace` and the AAR file for verification; this workaround
cannot repair a damaged or ABI-incompatible binary.

**No model weights are bundled**: users install model files after app installation.

**Licensing**: If choosing FFmpegKit min-gpl, check GPL obligations before distribution.
