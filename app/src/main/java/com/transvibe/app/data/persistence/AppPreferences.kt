package com.transvibe.app.data.persistence

import com.transvibe.app.domain.asr.OfficialFastAsrModels

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.transVibePreferences by preferencesDataStore(name = "transvibe_preferences")
private val MANUAL_SOURCE_LANGUAGES = setOf("ar", "en", "fr", "ru", "de", "es")

class AppPreferencesRepository(private val context: Context) : SettingsRepository {
    private object Keys {
        val keepScreenOn = booleanPreferencesKey("keep_screen_on_playback")
        val exportFormat = stringPreferencesKey("preferred_export_format")
        val processingProfile = stringPreferencesKey("processing_profile")
        val applicationLanguage = stringPreferencesKey("application_language")
        val interfaceTextScale = floatPreferencesKey("interface_text_scale")
        val videoSpeechLanguage = stringPreferencesKey("video_speech_language")
        val subtitleTargetLanguage = stringPreferencesKey("subtitle_target_language")
        val wifiOnlyTranslationDownloads = booleanPreferencesKey("wifi_only_translation_downloads")
        val preferredAsrModelId = stringPreferencesKey("preferred_asr_model_id")
        val defaultProcessingModelId = stringPreferencesKey("default_processing_model_id")
        val legacyAutoLanguage = booleanPreferencesKey("automatic_language_detection")
        val legacyManualLanguage = stringPreferencesKey("manual_language_code")
        val subtitleScale = floatPreferencesKey("subtitle_text_scale")
        val subtitleDisplayMode = stringPreferencesKey("subtitle_display_mode")
        val subtitleBackgroundOpacity = floatPreferencesKey("subtitle_background_opacity")
        val subtitleBackgroundStyle = stringPreferencesKey("subtitle_background_style")
        val subtitleTextColor = stringPreferencesKey("subtitle_text_color")
        val subtitleVerticalPosition = floatPreferencesKey("subtitle_vertical_position")
        val subtitleAlignment = stringPreferencesKey("subtitle_alignment")
        val themeMode = stringPreferencesKey("theme_mode")
        val subtitleOutputTreeUri = stringPreferencesKey("subtitle_output_tree_uri")
    }

    override val preferences: Flow<AppPreferences> = context.transVibePreferences.data.map { p ->
        val migratedSpeechLanguage = p[Keys.videoSpeechLanguage] ?: run {
            if (p[Keys.legacyAutoLanguage] != false) "auto"
            else p[Keys.legacyManualLanguage]?.takeIf { it in MANUAL_SOURCE_LANGUAGES } ?: "en"
        }
        AppPreferences(
            keepScreenOnDuringPlayback = p[Keys.keepScreenOn] ?: false,
            preferredExportFormat = p[Keys.exportFormat]?.takeIf { it == "srt" || it == "vtt" } ?: "srt",
            processingProfile = when (p[Keys.processingProfile]) {
                "FAST" -> "FAST"
                "PRESERVE_ORIGINAL" -> "PRESERVE_ORIGINAL"
                // Migrate the former UI power-save choice to BALANCED. Thermal protection is now automatic.
                else -> "BALANCED"
            },
            applicationLanguage = p[Keys.applicationLanguage]?.takeIf { it in setOf("ar", "en") } ?: "ar",
            interfaceTextScale = (p[Keys.interfaceTextScale] ?: 1f).coerceIn(0.85f, 1.40f),
            videoSpeechLanguage = when {
                migratedSpeechLanguage in setOf("auto_video", "auto_adaptive") -> migratedSpeechLanguage
                migratedSpeechLanguage.startsWith("manual:") && migratedSpeechLanguage.substringAfter(':') in MANUAL_SOURCE_LANGUAGES -> migratedSpeechLanguage
                migratedSpeechLanguage == "auto" -> "auto_video"
                migratedSpeechLanguage in MANUAL_SOURCE_LANGUAGES -> "manual:$migratedSpeechLanguage"
                else -> "auto_video"
            },
            subtitleTargetLanguage = p[Keys.subtitleTargetLanguage]?.takeIf { it in setOf("ar", "en") } ?: "ar",
            wifiOnlyTranslationDownloads = p[Keys.wifiOnlyTranslationDownloads] ?: true,
            preferredAsrModelId = p[Keys.preferredAsrModelId]?.takeIf { it in setOf("auto", "whisper", OfficialFastAsrModels.moonshineTinyEnglish.id, "moonshine-base-ar", OfficialFastAsrModels.senseVoiceSmall.id) } ?: "auto",
            defaultProcessingModelId = p[Keys.defaultProcessingModelId].orEmpty(),
            subtitleTextScale = (p[Keys.subtitleScale] ?: 1.0f).coerceIn(0.85f, 1.80f),
            subtitleDisplayMode = p[Keys.subtitleDisplayMode]?.takeIf { it in setOf("original", "translated", "both") } ?: "translated",
            subtitleBackgroundOpacity = (p[Keys.subtitleBackgroundOpacity] ?: 0.75f).coerceIn(0f, 1f),
            subtitleBackgroundStyle = p[Keys.subtitleBackgroundStyle]?.takeIf { it in setOf("compact", "band", "shadow", "none") } ?: "compact",
            subtitleTextColor = p[Keys.subtitleTextColor]?.takeIf { it in setOf("white", "yellow", "cyan", "green") } ?: "white",
            subtitleVerticalPosition = (p[Keys.subtitleVerticalPosition] ?: 0.90f).coerceIn(0.10f, 0.95f),
            subtitleAlignment = p[Keys.subtitleAlignment]?.takeIf { it in setOf("start", "center", "end") } ?: "center",
            themeMode = p[Keys.themeMode]?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
            subtitleOutputTreeUri = p[Keys.subtitleOutputTreeUri].orEmpty()
        )
    }

    override suspend fun setKeepScreenOnDuringPlayback(enabled: Boolean) {
        context.transVibePreferences.edit { it[Keys.keepScreenOn] = enabled }
    }

    override suspend fun setPreferredExportFormat(format: String) {
        require(format == "srt" || format == "vtt")
        context.transVibePreferences.edit { it[Keys.exportFormat] = format }
    }

    override suspend fun setProcessingProfile(profile: String) {
        require(profile in setOf("FAST", "BALANCED", "PRESERVE_ORIGINAL"))
        context.transVibePreferences.edit { it[Keys.processingProfile] = profile }
    }

    override suspend fun setApplicationLanguage(code: String) {
        require(code in setOf("ar", "en"))
        context.transVibePreferences.edit { it[Keys.applicationLanguage] = code }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = LocaleList.forLanguageTags(code)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        }
    }

    override suspend fun setInterfaceTextScale(scale: Float) {
        context.transVibePreferences.edit { it[Keys.interfaceTextScale] = scale.coerceIn(0.85f, 1.40f) }
    }

    override suspend fun setVideoSpeechLanguage(code: String) {
        require(code in setOf("auto_video", "auto_adaptive") ||
            (code.startsWith("manual:") && code.substringAfter(':') in MANUAL_SOURCE_LANGUAGES))
        context.transVibePreferences.edit { it[Keys.videoSpeechLanguage] = code }
    }

    override suspend fun setSubtitleTargetLanguage(code: String) {
        require(code in setOf("ar", "en"))
        context.transVibePreferences.edit { it[Keys.subtitleTargetLanguage] = code }
    }

    override suspend fun setWifiOnlyTranslationDownloads(enabled: Boolean) {
        context.transVibePreferences.edit { it[Keys.wifiOnlyTranslationDownloads] = enabled }
    }

    override suspend fun setPreferredAsrModelId(id: String) {
        require(id in setOf("auto", "whisper", OfficialFastAsrModels.moonshineTinyEnglish.id, "moonshine-base-ar", OfficialFastAsrModels.senseVoiceSmall.id))
        context.transVibePreferences.edit { it[Keys.preferredAsrModelId] = id }
    }

    override suspend fun setDefaultProcessingModelId(id: String) {
        require(id.isNotBlank())
        context.transVibePreferences.edit { it[Keys.defaultProcessingModelId] = id }
    }

    override suspend fun setSubtitleTextScale(scale: Float) {
        context.transVibePreferences.edit { it[Keys.subtitleScale] = scale.coerceIn(0.85f, 1.80f) }
    }

    override suspend fun setSubtitleDisplayMode(mode: String) {
        require(mode in setOf("original", "translated", "both"))
        context.transVibePreferences.edit { it[Keys.subtitleDisplayMode] = mode }
    }

    override suspend fun setSubtitleBackgroundOpacity(opacity: Float) {
        context.transVibePreferences.edit { it[Keys.subtitleBackgroundOpacity] = opacity.coerceIn(0f, 1f) }
    }

    override suspend fun setSubtitleBackgroundStyle(style: String) {
        require(style in setOf("compact", "band", "shadow", "none"))
        context.transVibePreferences.edit { it[Keys.subtitleBackgroundStyle] = style }
    }

    override suspend fun setSubtitleTextColor(color: String) {
        require(color in setOf("white", "yellow", "cyan", "green"))
        context.transVibePreferences.edit { it[Keys.subtitleTextColor] = color }
    }

    override suspend fun setSubtitleVerticalPosition(position: Float) {
        context.transVibePreferences.edit { it[Keys.subtitleVerticalPosition] = position.coerceIn(0.10f, 0.95f) }
    }

    override suspend fun setSubtitleAlignment(alignment: String) {
        require(alignment in setOf("start", "center", "end"))
        context.transVibePreferences.edit { it[Keys.subtitleAlignment] = alignment }
    }

    override suspend fun setThemeMode(mode: String) {
        require(mode in setOf("system", "light", "dark"))
        context.transVibePreferences.edit { it[Keys.themeMode] = mode }
    }

    override suspend fun setSubtitleOutputTreeUri(uri: String?) {
        context.transVibePreferences.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(Keys.subtitleOutputTreeUri) else prefs[Keys.subtitleOutputTreeUri] = uri
        }
    }
}
