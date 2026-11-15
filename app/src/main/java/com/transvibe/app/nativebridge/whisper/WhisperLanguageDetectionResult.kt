package com.transvibe.app.nativebridge.whisper

import androidx.annotation.Keep

@Keep
data class WhisperLanguageDetectionResult(
    val language: String,
    /** Probability returned by whisper_lang_auto_detect(); null only when unavailable. */
    val confidence: Float?
)
