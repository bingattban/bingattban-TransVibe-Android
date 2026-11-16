# Free Android emulator smoke tests in GitHub Actions

This lane uses **only standard GitHub-hosted Ubuntu runners** and the open-source
`reactivecircus/android-emulator-runner@v2` action. For the **public** TransVibe
repository, standard GitHub-hosted runner use is free. No paid device farm,
billing-enabled Google Cloud project, service-account key or private media is needed.
No user model, real microphone recording, uploaded video or personally identified
reference transcript is needed to run these smoke tests.

## How to run

- On pull requests against `main`, the existing tests-first CI runs, and the
  independent **Free Android emulator smoke** workflow also runs.
- The workflow also supports **manual** invocation using GitHub Actions >
  Free Android emulator smoke > Run workflow.
- The workflow first checks the pinned AAR/JAR/JNI hashes, runs Gradle unit
  tests and Lint, and only afterwards builds the actual debug APK and test APK.
- It boots an API 35 Google APIs **x86_64** emulator on a free Ubuntu runner,
  installs the APKs, and executes three test classes:
  `AndroidEmulatorSmokeTest` (app launch and typed Whisper JNI failure),
  `PerformanceBenchmarkContractTest` (RTF calculation), and
  `FfmpegSafContractInstrumentationTest` (FFmpegKit SAF read API).
- The native part of TransVibe ships as **arm64-v8a**. This test attempts
  Android API 35's ARM translation support on a hosted x86_64 emulator.
  Not every emulator image supplies it. An incompatibility **fails visibly**;
  the workflow does not silently skip an unavailable ABI or claim success.
- On failure, inspect the uploaded `transvibe-free-emulator-smoke-logs` artifact.
  The workflow does not upload a user's model, private videos or transcripts.

## What a green result actually proves

The installed APK can launch; FFmpegKit's class is present; JNI loads and
rejects a missing model without crashing; and deterministic RTF arithmetic
remains correct. This is **not a phone-speed benchmark**. Emulated ARM64
execution time and startup time must **never** be used to claim that Whisper
or Arabic translation became faster on an ARM64 smartphone.

The actual opt-in `WhisperOnDeviceBenchmarkTest` is still available for a
physical device with explicit readable model+PCM16 fixtures. It runs a warm-up
plus three repeated measurements, records technical metrics and optional WER,
and can be compared using `tools/benchmark_compare.py`.
Consult [PERFORMANCE_BENCHMARKING.md](PERFORMANCE_BENCHMARKING.md).

## Security and compatibility

- No new public app networking or telemetry is enabled.
- No changes to the production `arm64-v8a` APK filters or checked-in binaries.
- No paid Firebase Test Lab or Google Cloud Device Run calls.
- If the emulator lacks ARM translation, this workflow should be fixed by
  adding a **separately built and verified x86_64 test-only JNI** rather than
  silently changing the production binary or deleting native smoke tests.

## FFmpegKit runtime issue discovered by emulator

The initial installed-app smoke run caught
`NoClassDefFoundError: com/arthenica/smartexception/PackageLoader`.
The existing `smart-exception-java-0.2.1.jar` contains
`java/Exceptions.class` but **not** `PackageLoader`.
The missing class lives in the authentic upstream
`smart-exception-common-0.2.1.jar`, now pinned and committed.
Both JARs are required because a local FFmpegKit AAR does not automatically
carry Maven's transitive dependencies.
This demonstrates why passing build-only tests is insufficient.

## Independently installable preview TEST APK

The free emulator also builds `app-testing.apk` **after** tests, installs
`com.transvibe.app.preview` alongside the original app, starts its MainActivity
and verifies that its process remains alive. Its launcher label is
`TransVibe Test`. The `.preview` package identity deliberately avoids
`com.transvibe.app.test`, reserved for Android instrumentation in this project.
The private owner release-signing key is never used for preview.
