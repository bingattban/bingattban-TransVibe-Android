package com.transvibe.app.domain.subtitle

/** Derives both subtitle extensions from the original video's displayed name. */
object SubtitleFileNames {
    fun baseName(videoName: String): String {
        val leaf = videoName.substringAfterLast('/').substringAfterLast('\\').trim()
        val withoutExtension = leaf.substringBeforeLast('.', leaf)
        return withoutExtension.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trimEnd('.').take(150).ifBlank { "TransVibe" }
    }
    fun fileName(videoName: String, webVtt: Boolean): String =
        baseName(videoName) + if (webVtt) ".vtt" else ".srt"
}
