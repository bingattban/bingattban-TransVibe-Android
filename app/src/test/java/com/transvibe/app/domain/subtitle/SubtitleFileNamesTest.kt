package com.transvibe.app.domain.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFileNamesTest {
    @Test fun originalVideoNameIsUsedForBothFormats() {
        assertEquals("film.srt", SubtitleFileNames.fileName("film.mp4", false))
        assertEquals("film.vtt", SubtitleFileNames.fileName("film.mp4", true))
        assertEquals("مقطع تجريبي.srt", SubtitleFileNames.fileName("مقطع تجريبي.mkv", false))
        assertEquals("my.family.video.vtt", SubtitleFileNames.fileName("my.family.video.mov", true))
    }
    @Test fun pathAndInvalidCharactersAreSanitized() {
        assertEquals("short.srt", SubtitleFileNames.fileName(" /movies/short.mp4 ", false))
        assertEquals("bad_video_.vtt", SubtitleFileNames.fileName("bad:video?.avi", true))
        assertEquals("TransVibe.srt", SubtitleFileNames.fileName(".mp4", false))
    }
}
