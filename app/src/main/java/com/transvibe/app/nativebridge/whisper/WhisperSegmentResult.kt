package com.transvibe.app.nativebridge.whisper

import androidx.annotation.Keep

@Keep
data class WhisperSegmentResult(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Mean of whisper_full_get_token_p() over valid tokens. Null when unavailable. */
    val confidence: Float?,
    /** Not exposed by the current public whisper.cpp C API; kept nullable for API stability. */
    val avgLogProb: Float?,
    /** Direct whisper_full_get_segment_no_speech_prob() value when valid. */
    val noSpeechProbability: Float?,
    /** Number of tokens emitted for this native Whisper segment. */
    val tokenCount: Int
)
