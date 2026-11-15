package com.transvibe.app.presentation

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.transvibe.app.R
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiConfigurationInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test
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

    @Test
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
