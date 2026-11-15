package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTimelineTest {
    private val timeline = SubtitleTimeline(
        listOf(
            SubtitleSegment(0, 1_000, 2_000, "a", translatedText = "أ"),
            SubtitleSegment(1, 3_000, 4_500, "b", translatedText = "ب"),
            SubtitleSegment(2, 5_000, 6_000, "c", translatedText = "ج")
        )
    )

    @Test fun findsActiveSegment() {
        assertEquals(1L, timeline.at(3_750)?.id)
    }

    @Test fun returnsNullInsideGap() {
        assertNull(timeline.at(2_500))
    }

    @Test fun endTimestampIsExclusive() {
        assertNull(timeline.at(4_500))
    }
}
