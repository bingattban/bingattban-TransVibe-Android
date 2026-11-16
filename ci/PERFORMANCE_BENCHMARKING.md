# TransVibe benchmark protocol — 2026-11-16

This repository already records per-job timings and supports device-specific calibration. This addition measures **actual** Whisper inference on Android with repeatable inputs and compares separate CPU builds safely. It does not claim a speed gain before on-device testing.

## Current documented baseline (from November 15 device logs)

A 42.2-second English sample had approximately **47.5 s ASR for Base**, and **240.4 s ASR for Small** (CPU). These are historical observations, not comparable records produced by the new harness. Do not use them as a formal regression gate.

## Real-device test

An Android test named `WhisperOnDeviceBenchmarkTest` is compiled in `app-debug-androidTest.apk`. It is explicitly opt-in and **skips** when either required file argument is missing. To run a measurement, place a Whisper model (e.g., English Tiny/Base) and a known 16kHz, mono, signed PCM16 **little-endian** audio sample (maximum 45 s) in paths readable by the app. No user videos, model weights, transcript, paths or user names are uploaded to GitHub.

Example (replace the file paths with accessible locations):

```bash
adb shell am instrument -w \
  -e class com.transvibe.app.performance.WhisperOnDeviceBenchmarkTest \
  -e transvibeBenchModel /data/user/0/com.transvibe.app/files/models/example.bin \
  -e transvibeBenchAudioPcm16 /data/user/0/com.transvibe.app/files/bench-fixtures/english.pcm16 \
  -e transvibeBenchReferenceText /data/user/0/com.transvibe.app/files/bench-fixtures/english.txt \
  -e transvibeBenchModelId base.en \
  -e transvibeBenchSampleId english-reference-v1 \
  -e transvibeBenchThreads 2 \
  com.transvibe.app.test/androidx.test.runner.AndroidJUnitRunner
```

The paths shown are **examples only**, not actual locations on the device. Android storage permission must permit the test to read all three files. Convert an authorized clean fixture to PCM16LE with `ffmpeg -i input.wav -ac 1 -ar 16000 -f s16le english.pcm16`. Use a public-domain or self-recorded fixed spoken sample and a checked reference transcript. Avoid putting personal voice recordings or protected/private transcripts in repository fixtures.

The test performs one untimed warm-up and **three** measured Whisper recognitions using the *same* audio and already loaded model. The output is private-app storage `Android/data/com.transvibe.app/files/benchmarks/whisper-device-*.jsonl` or the app's internal files directory if external app storage is unavailable. It contains hashes, technical timings, WER (if reference provided), PSS, thermal status and backend label; no recognized text. CPU reference and experimental builds must be installed **one at a time** with the same application, model, audio fixture, thread count and device. Avoid changing the Android system/build between runs.

On Android SDK <29 thermal status is unavailable and the test emits `6` (unknown); such results intentionally cannot be treated as qualified comparison. Invalid results (thermal status above MODERATE, crash, fewer than 3 repeats, missing WER, different sample or model) remain **inconclusive**.

## Compare JSONL files, without any model or media uploads

```bash
python3 tools/benchmark_compare.py --baseline cpu.jsonl --candidate dotprod.jsonl --output report.json
```

Exit `0`: no measured regression / sufficient quality evidence; `2`: inconclusive; `3`: regression. An improvement is only reported when inference RTF improves by at least 10% and the WER degradation does not exceed 0.02. This is an engineering acceptance tolerance, **not** an assertion that small transcript changes are harmless. Human review is still required for Arabic translation quality and subtitle timing.

## ARM64 variants

* `transvibe-native-arm64-cpu`: reference binary built for broad ARM64 compatibility; used in app-debug APK.
* `transvibe-native-arm64-dotprod-experimental`: opt-in ARMv8.2 `dotprod`/`fp16` instruction build, **NOT** included in APK automatically. CPU capability must be checked on the specific device before installing it. JNI/ELF checks alone cannot establish runtime compatibility.
* Vulkan: **not enabled or validated** by this change. Upstream ggml Vulkan on Android additionally needs a host `glslc`, `vulkan.hpp`, Vulkan headers and SPIR-V dependencies. Do not label experimental CPU builds as GPU-enabled or claim accelerated speed until a real Vulkan binary passes device tests.

Use no fake libraries, tests before APK, and never merge a failing workflow. No automatic Firebase billable tests.
