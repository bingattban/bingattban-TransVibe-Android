package com.transvibe.app.data.processing

import android.net.Uri
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.RecoveryAction
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.ModelCapability
import com.transvibe.app.domain.language.SourceLanguageMode
import com.transvibe.app.domain.language.SourceLanguagePolicy
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.domain.repository.ProcessingPreflight
import kotlinx.coroutines.CancellationException

/** Enforces preflight against the capabilities required by the user's requested pipeline. */
class ProcessVideoPreflight(
    private val resources: AiResourceManager,
    private val sourceInspector: MediaSourceInspector,
    private val hasFfmpegFallback: () -> Boolean = { false }
) : ProcessingPreflight {
    override suspend fun validate(
        video: SelectedVideo?,
        model: WhisperModel,
        request: ProcessingResourceRequest
    ): ProcessingError? {
        if (video == null || video.uriString.isBlank()) {
            return ProcessingError.SourceUnavailable(retryable = false)
        }
        val uri = runCatching { Uri.parse(video.uriString) }.getOrNull()
            ?: return ProcessingError.SourceUnavailable()

        val source = SourceLanguagePolicy.parse(request.sourceLanguage)
        if (source.mode != SourceLanguageMode.MANUAL && ModelCapability.LANGUAGE_DETECTION !in model.capabilities) {
            return ProcessingError.ModelLoadFailure(
                cause = IllegalStateException("automatic_language_detection_not_supported:${model.id}"),
                retryable = false,
                recovery = RecoveryAction.OPEN_AI_RESOURCES
            )
        }
        if (ModelCapability.MULTILINGUAL_RECOGNITION !in model.capabilities) {
            if (source.mode != SourceLanguageMode.MANUAL || source.manualLanguage != "en") {
                return ProcessingError.ModelLoadFailure(
                    cause = IllegalStateException("multilingual_recognition_not_supported:${model.id}"),
                    retryable = false,
                    recovery = RecoveryAction.OPEN_AI_RESOURCES
                )
            }
        }

        val resourceState = resources.checkPipeline(model, request)
        if (resourceState != AiResourceState.Ready) return ProcessingErrorMapper.fromResource(resourceState)

        return try {
            sourceInspector.ensureReadable(uri)
            try {
                sourceInspector.inspectAudio(uri)
            } catch (inspection: MediaSourceInspector.InspectionException) {
                // MediaExtractor may not recognize formats that FFmpeg can decode.
                // Authorization problems must never be suppressed.
                if (inspection is MediaSourceInspector.InspectionException.PermissionDenied ||
                    !hasFfmpegFallback()) throw inspection
            }
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ProcessingErrorMapper.fromThrowable(t)
        }
    }
}
