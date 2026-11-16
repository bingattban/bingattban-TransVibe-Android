package com.transvibe.app.data.asr

/**
 * Verified upstream artifact metadata for future per-model Moonshine installation.
 * This catalog does not enable a model in the UI until the repository/runtime can
 * select an isolated model directory. Keep the existing Tiny English path intact.
 */
internal object MoonshineAdditionalManifests {
    val baseArabic = listOf(
        MoonshineModelFile(
            name = "encoder_model.ort",
            url = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-ar-quantized-2026-02-27/resolve/main/encoder_model.ort?download=true",
            expectedBytes = 31_326_824L,
            sha256 = "68e50ebe0317ce909f098044a5dda2a76e6b86dc882829a317771fbafc5826ae"
        ),
        MoonshineModelFile(
            name = "decoder_model_merged.ort",
            url = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-ar-quantized-2026-02-27/resolve/main/decoder_model_merged.ort?download=true",
            expectedBytes = 109_424_552L,
            sha256 = "8f272cb50818e28ad86bbffc21e1450a4d57155e95f099ca6a236f38f4d9eafb"
        ),
        MoonshineModelFile(
            name = "tokens.txt",
            url = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-ar-quantized-2026-02-27/resolve/main/tokens.txt?download=true",
            expectedBytes = null,
            sha256 = null
        )
    )
}
