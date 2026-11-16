package com.transvibe.app.domain.asr

import com.transvibe.app.domain.model.DownloadProgress
import kotlinx.coroutines.flow.StateFlow

data class FastAsrModelInfo(
    val id: String,
    val displayName: String,
    val supportedLanguages: Set<String>,
    val totalBytes: Long,
    val estimatedRamBytes: Long,
    val descriptionAr: String,
    val importHintAr: String
)

interface FastAsrModelRepository {
    val models: List<FastAsrModelInfo>
    fun state(modelId: String): StateFlow<DownloadProgress>
    fun isInstalled(modelId: String): Boolean
    fun download(modelId: String)
    fun importModel(modelId: String, sourceUri: String)
    fun exportModel(modelId: String, destinationUri: String)
    fun delete(modelId: String)
    fun refresh(modelId: String? = null)
}

interface SingleFastAsrModelRepository {
    val model: FastAsrModelInfo
    val state: StateFlow<DownloadProgress>
    fun isInstalled(): Boolean
    fun download()
    fun importModel(sourceUri: String)
    fun exportModel(destinationUri: String)
    fun delete()
    fun refresh()
}

object OfficialFastAsrModels {
    val moonshineTinyEnglish = FastAsrModelInfo(
        id = "moonshine-tiny-en-v2",
        displayName = "Moonshine Tiny English v2",
        supportedLanguages = setOf("en"),
        totalBytes = 44_250_000L,
        estimatedRamBytes = 220L * 1024L * 1024L,
        descriptionAr = "محرك تعرف سريع جداً للإنجليزية يعمل محلياً، مع الرجوع إلى Whisper تلقائياً عند عدم توافق اللغة.",
        importHintAr = "يمكن استيراده كملف ZIP يحتوي encoder_model.ort و decoder_model_merged.ort و tokens.txt."
    )

    val senseVoiceSmall = FastAsrModelInfo(
        id = "sensevoice-small-int8",
        displayName = "SenseVoice-Small INT8",
        supportedLanguages = setOf("zh", "yue", "en", "ja", "ko"),
        totalBytes = 239_549_735L,
        estimatedRamBytes = 520L * 1024L * 1024L,
        descriptionAr = "محرك ONNX سريع غير تسلسلي يدعم الصينية والكانتونية والإنجليزية واليابانية والكورية، ويعمل محلياً عبر sherpa-onnx.",
        importHintAr = "يمكن استيراده كملف ZIP يحتوي model.int8.onnx و tokens.txt."
    )

    val all = listOf(moonshineTinyEnglish, senseVoiceSmall)
}
