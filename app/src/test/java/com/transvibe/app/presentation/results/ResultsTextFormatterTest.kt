package com.transvibe.app.presentation.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsTextFormatterTest {
    @Test fun cpuBackendDoesNotRepeatTheCpuLabel() {
        assertEquals("المعالج CPU", ResultsTextFormatter.backend("CPU|CPU", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("المعالج CPU", ResultsTextFormatter.backend("CPU|cpu", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("المعالج CPU", ResultsTextFormatter.backend("CPU", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("المعالج CPU · 4 threads", ResultsTextFormatter.backend("CPU|4 threads", "المعالج CPU", "GPU مستخدم", "غير معروف"))
    }

    @Test fun gpuLabelAndDiagnosticsRemainAccurate() {
        assertEquals("GPU مستخدم · Adreno", ResultsTextFormatter.backend("GPU|Adreno", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("GPU مستخدم", ResultsTextFormatter.backend("GPU|gpu", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("غير معروف", ResultsTextFormatter.backend("UNVERIFIED_REBUILD_GPU_LIBRARY", "المعالج CPU", "GPU مستخدم", "غير معروف"))
        assertEquals("غير معروف", ResultsTextFormatter.backend("", "المعالج CPU", "GPU مستخدم", "غير معروف"))
    }

    @Test fun longVideoNameKeepsBothIdentityAndExtensionWithoutRenamingTheFile() {
        val video = "english_online_lab_3995495596367738706_437521_60596.mp4"
        val rendered = ResultsTextFormatter.compactVideoName(video)
        assertTrue(rendered.codePointCount(0, rendered.length) <= 44)
        assertTrue(rendered.startsWith("english_online_lab"))
        assertTrue(rendered.endsWith("60596.mp4"))
        assertTrue(rendered.contains('…'))
        assertEquals("sample.mp4", ResultsTextFormatter.compactVideoName("sample.mp4"))
        assertEquals(video, "english_online_lab_3995495596367738706_437521_60596.mp4")
    }

    @Test fun compactNameNeverSplitsUnicodeSurrogatePairs() {
        val video = "الترجمة_".repeat(8) + "🎞️_درس_الفيديو.mp4"
        val shortened = ResultsTextFormatter.compactVideoName(video)
        assertTrue(shortened.codePointCount(0, shortened.length) <= 44)
        assertTrue(shortened.endsWith("الفيديو.mp4"))
        for (i in shortened.indices) {
            val c = shortened[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < shortened.length && Character.isLowSurrogate(shortened[i + 1]))
            }
            if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(shortened[i - 1]))
            }
        }
    }
}
