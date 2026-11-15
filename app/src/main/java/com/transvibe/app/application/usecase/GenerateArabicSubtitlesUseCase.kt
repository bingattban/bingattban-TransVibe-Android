package com.transvibe.app.application.usecase

import android.net.Uri
import com.transvibe.app.data.media.FfmpegAudioExtractor
import com.transvibe.app.data.media.PcmWavChunkReader
import com.transvibe.app.data.subtitle.SubtitleExporter
import com.transvibe.app.domain.asr.MoonshineSubtitleSegmenter
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.media.vad.VoiceActivityDetector
import com.transvibe.app.nativebridge.sherpa.SherpaModelFiles
import com.transvibe.app.nativebridge.sherpa.SherpaOfflineAsrRepository
import com.transvibe.app.translation.ArabicSubtitlePostProcessor
import com.transvibe.app.translation.TranslationEngineFactory
import com.transvibe.app.translation.TranslationEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Explicit all-local FFmpeg -> 16 kHz WAV -> sherpa-onnx -> on-device ML Kit -> SRT/VTT flow.
 * This entry point is optional; the established Whisper/MediaCodec pipeline remains unchanged.
 * The target translation pack must have been installed beforehand (no automatic network use).
 */
internal class GenerateArabicSubtitlesUseCase(
    private val extractor: FfmpegAudioExtractor,
    private val asr: SherpaOfflineAsrRepository,
    private val translationEngines: TranslationEngineFactory,
    private val vad: VoiceActivityDetector
) {
    data class Output(val srtFile: File, val vttFile: File, val subtitleCount: Int)

    suspend fun execute(
        videoUri: String,
        model: SherpaModelFiles,
        outputDirectory: File,
        sourceLanguage: String = "ar",
        onProgress: (suspend (Int) -> Unit)? = null
    ): Output {
        val source = LanguageCode.of(sourceLanguage)
        require(source != LanguageCode.AUTO) { "حدد لغة صوت فعلية لنموذج الترجمة." }
        check(extractor.isAvailable()) { "مكتبة FFmpegKit غير مثبتة في app/libs." }
        val session = asr.open(model, sourceLanguage = source.value)
        var translator: TranslationEngine? = null
        try {
            if (source != LanguageCode.ARABIC) {
                translator = translationEngines.create(source, LanguageCode.ARABIC)
            }
            // Offline only: never let ML Kit download weights during this operation.
            check(translator?.isReady() != false) { "حزمة الترجمة المحلية إلى العربية غير مثبتة." }
            val localTranslator = translator
            return extractor.withPcm16kMonoWav(Uri.parse(videoUri)) { wav ->
                val temporarySrt = File(outputDirectory, ".arabic-${System.nanoTime()}.srt")
                val temporaryVtt = File(outputDirectory, ".arabic-${System.nanoTime()}.vtt")
                check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "تعذر إنشاء مجلد الترجمة." }
                var count = 0
                try {
                    // Stream subtitles directly to disk. No unbounded list in RAM.
                    temporarySrt.bufferedWriter(Charsets.UTF_8).use { srt ->
                        temporaryVtt.bufferedWriter(Charsets.UTF_8).use { vtt ->
                            vtt.append("WEBVTT\n\n")
                            PcmWavChunkReader().decodeChunks(
                                wav = wav, chunkSeconds = 12, overlapMs = 0,
                                onProgress = { position, total ->
                                    if (total > 0L) onProgress?.invoke((100L * position / total).toInt().coerceIn(0, 99))
                                },
                                onChunk = { chunk ->
                                    currentCoroutineContext().ensureActive()
                                    val speechRegions = vad.detectSpeechRegions(chunk.samples, 16_000)
                                    for (region in speechRegions) {
                                        currentCoroutineContext().ensureActive()
                                        val speech = chunk.samples.copyOfRange(region.startSample, region.endSample)
                                        val transcript = session.recognize(speech)
                                        if (transcript.isBlank()) continue
                                        val arabic = if (localTranslator != null) localTranslator.translate(transcript) else transcript
                                        val cleaned = ArabicSubtitlePostProcessor.process(arabic)
                                        if (cleaned.isBlank()) continue
                                        val start = chunk.startMs + region.startSample * 1000L / 16_000
                                        val end = chunk.startMs + region.endSample * 1000L / 16_000
                                        val subtitles = MoonshineSubtitleSegmenter.segment(
                                            cleaned, start, end.coerceAtLeast(start + 1), targetIsEnglish = false
                                        )
                                        for (candidate in subtitles) {
                                            val segment = candidate.copy(id = (++count).toLong(), translatedText = candidate.originalText)
                                            srt.append(SubtitleExporter.formatSrt(listOf(segment)).replaceFirst("1\r\n", "$count\r\n"))
                                            vtt.append(SubtitleExporter.formatVtt(listOf(segment)).removePrefix("WEBVTT\r\n\r\n"))
                                        }
                                    }
                                }
                            )
                        }
                    }
                    check(count > 0) { "لم يُكتشف كلام قابل للتفريغ من الفيديو." }
                    val outputId = java.util.UUID.randomUUID().toString().take(8)
                    val finalSrt = File(outputDirectory, "arabic-subtitles-$outputId.srt")
                    val finalVtt = File(outputDirectory, "arabic-subtitles-$outputId.vtt")
                    // Treat both files as one output: rollback a partial commit on error.
                    var committed = false
                    try {
                        currentCoroutineContext().ensureActive()
                        check(temporarySrt.renameTo(finalSrt)) { "تعذر حفظ SRT." }
                        check(temporaryVtt.renameTo(finalVtt)) { "تعذر حفظ VTT." }
                        onProgress?.invoke(100)
                        committed = true
                        Output(finalSrt, finalVtt, count)
                    } finally {
                        if (!committed) {
                            finalSrt.delete()
                            finalVtt.delete()
                        }
                    }
                } finally {
                    temporarySrt.delete()
                    temporaryVtt.delete()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            withContext(NonCancellable) {
                try { translator?.close() } finally { session.release() }
            }
        }
    }
}
