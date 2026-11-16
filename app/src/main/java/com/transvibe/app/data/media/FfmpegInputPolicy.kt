package com.transvibe.app.data.media

/**
 * FFmpegKit's registered SAF protocol avoids copying a whole content:// video
 * into application cache before extracting its audio.
 *
 * This policy never treats a content provider's private path as a regular file.
 * Unsupported or unavailable SAF input must retain the legacy streamed-copy path.
 */
internal object FfmpegInputPolicy {
    fun directInput(scheme: String?, localPath: String?, safParameter: String?): String? = when (scheme) {
        null, "file" -> localPath?.takeIf { it.isNotBlank() }
        "content" -> safParameter?.takeIf {
            it.startsWith("saf:") && it.length > 4 &&
                it.none { character -> character == '\n' || character == '\r' || character == '\u0000' }
        }
        else -> null
    }

    fun needsContentCopy(scheme: String?, directInput: String?): Boolean =
        scheme == "content" && directInput == null

    /** Retry at most once; only a first failed SAF attempt permits a local-copy retry. */
    fun retryWithCopy(scheme: String?, usedSaf: Boolean): Boolean =
        scheme == "content" && usedSaf
}
