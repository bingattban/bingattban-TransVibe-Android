package com.transvibe.app.presentation

import android.content.res.Configuration
import android.os.LocaleList
import android.test.InstrumentationTestCase
import com.transvibe.app.R
import java.util.Locale

@Suppress("DEPRECATION")
class UiConfigurationInstrumentationTest : InstrumentationTestCase() {
    fun testArabicRtlResourcesResolveAtLargeFontScale() {
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale("ar")))
            fontScale = 2.0f
        }
        val context = base.createConfigurationContext(config)
        assertEquals(android.util.LayoutDirection.RTL, context.resources.configuration.layoutDirection)
        assertTrue(context.getString(R.string.start_processing).isNotBlank())
        assertTrue(context.getString(R.string.error_generic_explanation).isNotBlank())
        assertEquals(2.0f, context.resources.configuration.fontScale)
    }

    fun testEnglishLtrResourcesResolveAtLargeFontScale() {
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.ENGLISH))
            fontScale = 2.0f
        }
        val context = base.createConfigurationContext(config)
        assertEquals(android.util.LayoutDirection.LTR, context.resources.configuration.layoutDirection)
        assertTrue(context.getString(R.string.start_processing).isNotBlank())
        assertTrue(context.getString(R.string.error_generic_explanation).isNotBlank())
        assertEquals(2.0f, context.resources.configuration.fontScale)
    }
}
