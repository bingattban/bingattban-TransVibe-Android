package com.transvibe.app.data.asr

import android.content.Context
import com.transvibe.app.domain.asr.FastAsrModelInfo

/** Available only for explicitly wired future model repositories; does not alter existing UI. */
internal object AdditionalMoonshineCatalog {
    const val BASE_ARABIC_ID = "base-ar"

    /** Metadata for repository registration once the runtime supports selecting this variant. */
    val baseArabicInfo = FastAsrModelInfo(
        id = "moonshine-base-ar",
        displayName = "Moonshine Base Arabic",
        supportedLanguages = setOf("ar"),
        totalBytes = MoonshineAdditionalManifests.baseArabic.sumOf { it.expectedBytes ?: 0L },
        estimatedRamBytes = 650L * 1024L * 1024L,
        descriptionAr = "نموذج Moonshine مخصص للتعرف على الكلام العربي محلياً.",
        importHintAr = "ملف ZIP يحتوي encoder_model.ort و decoder_model_merged.ort و tokens.txt."
    )

    fun baseArabicStore(context: Context): AdditionalMoonshineModelStore =
        AdditionalMoonshineModelStore(context, BASE_ARABIC_ID, MoonshineAdditionalManifests.baseArabic)
}
