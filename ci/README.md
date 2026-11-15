# CI setup (Android only, tests first)

This application repository is deliberately separate from the private
`bingattban/TransVibe-Native-Builder` used for reference JNI builds.
The project here is compiled in GitHub Actions without storing end-user models,
local databases or original APK signing keys.

## Jobs

1. `source-checks`: baseline static guards + JNI verifier tests.
2. `native-arm64`: restore exact vendored Whisper v1.9.4 source archive and
   build CPU arm64-v8a `.so` for minSdk 26 with Android NDK 28.2.13676358.
3. `android-tests-then-apk`: require real sherpa-onnx 1.13.5 and FFmpegKit 6.0-2
   AARs, run unit tests and lint **before** packaging. Generate debug + test APKs.

**BLOCKER TO APK**: The user's verified FFmpegKit AAR was installed only on
Code On The Go and was not uploaded. To enable APK CI, provide the exact
`ffmpeg-kit-min-gpl-6.0-2.aar` from the working phone installation to a
controlled download URL and record its SHA-256. Set repository Actions
variables `FFMPEG_AAR_URL` and `FFMPEG_AAR_SHA256` under
Settings > Secrets and variables > Actions > Variables.

The Sherpa 1.13.5 AAR is downloaded from the upstream GitHub release and
checked against SHA-256 `6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b`.
Override its URL/SHA via `SHERPA_AAR_URL` and `SHERPA_AAR_SHA256` if necessary.
If the Sherpa 1.13.5 release file differs, do NOT bypass SHA validation: inspect
and verify against the original upstream checksum first.

AARs are downloaded during a workflow run; do not commit them to the public
repository, do not use fake AARs, and do not replace versions silently.
The min-gpl FFmpegKit package may impose GPL distribution requirements.

## Testing on physical/virtual devices

After successful `assembleDebugAndroidTest`, download both APKs from
`transvibe-debug-apks` on GitHub Actions. They can be submitted to Firebase
Test Lab / Developer Device Platform. This CI does not automatically start
billable device sessions. Firebase Test Lab sunsets September 30, 2027.

## Important scope

- A successful Ubuntu compile does not prove Whisper GPU speed or cancel latency
  on a Samsung/Qualcomm arm64 handset.
- No Android device execution is claimed until a real device run exists.
- Native CPU only; Vulkan must be benchmarked separately.
- Do not merge to `main` when required jobs fail or are blocked.
