package com.transvibe.app.data.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.transvibe.app.domain.model.ModelCapability
import com.transvibe.app.domain.model.ModelOrigin
import com.transvibe.app.domain.model.ModelPerformanceTier
import com.transvibe.app.domain.model.NativeValidationState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType
import com.transvibe.app.domain.registry.AiModelRegistry
import com.transvibe.app.nativebridge.whisper.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.UUID

/** Implements the controlled user-import flow. The calculated SHA-256 is an integrity fingerprint only. */
class UserWhisperModelImporter(
    private val context: Context,
    private val store: ModelStore,
    private val registry: AiModelRegistry
) {
    suspend fun import(sourceUri: Uri, requestedDisplayName: String? = null): WhisperModel = withContext(Dispatchers.IO) {
        store.cleanupOrphanUserImportTemps()
        val token = UUID.randomUUID().toString()
        val temp = store.userImportTemp(token)
        try {
            temp.parentFile?.mkdirs()
            temp.delete()
            val declaredLength = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
            if (declaredLength > 0) ensureStorage(declaredLength + SAFETY_MARGIN_BYTES)

            var copied = 0L
            context.contentResolver.openInputStream(sourceUri)?.buffered()?.use { input ->
                FileOutputStream(temp).use { fileOut ->
                    val output = fileOut.buffered()
                    output.use {
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                        }
                        output.flush()
                        fileOut.fd.sync()
                    }
                }
            } ?: error("تعذر فتح ملف نموذج Whisper المحدد")
            require(copied >= MIN_MODEL_BYTES) { "الملف المحدد أصغر من أن يكون نموذج Whisper صالحاً" }

            // Semantic validation occurs before trusting or registering any descriptor.
            val inspection = WhisperNative.inspectModel(temp.absolutePath)
            currentCoroutineContext().ensureActive()

            // Local fingerprint: detects later corruption, but does not authenticate the publisher.
            val fingerprint = store.sha256(temp)
            currentCoroutineContext().ensureActive()

            val display = requestedDisplayName?.trim()?.takeIf { it.isNotBlank() }
                ?: sourceUri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "Whisper Imported"
            val id = "imported-${fingerprint.take(16)}"
            val finalFileName = "$id.bin"
            val multilingual = inspection.multilingual
            val capabilities = buildSet {
                add(ModelCapability.ENGLISH_RECOGNITION)
                add(ModelCapability.PROMPT_CONTEXT)
                if (multilingual) {
                    add(ModelCapability.MULTILINGUAL_RECOGNITION)
                    add(ModelCapability.SPEECH_TO_ENGLISH_TRANSLATION)
                    add(ModelCapability.LANGUAGE_DETECTION)
                }
            }
            val tier = when {
                copied < 200L * 1024L * 1024L -> ModelPerformanceTier.LIGHT
                copied < 800L * 1024L * 1024L -> ModelPerformanceTier.BALANCED
                copied < 2_000L * 1024L * 1024L -> ModelPerformanceTier.HEAVY
                else -> ModelPerformanceTier.VERY_HEAVY
            }
            val ramGuidance = when (tier) {
                ModelPerformanceTier.LIGHT -> 512L * 1024L * 1024L
                ModelPerformanceTier.BALANCED -> 1_200L * 1024L * 1024L
                ModelPerformanceTier.HEAVY -> 3_000L * 1024L * 1024L
                ModelPerformanceTier.VERY_HEAVY -> 5_000L * 1024L * 1024L
            }
            val descriptor = WhisperModel(
                id = id,
                displayName = display,
                fileName = finalFileName,
                downloadUrl = null,
                sha256 = fingerprint,
                fileSizeBytes = copied,
                estimatedRamBytes = ramGuidance,
                type = WhisperModelType.CUSTOM,
                descriptionAr = "نموذج Whisper مستورد محلياً؛ البصمة المحسوبة تتحقق من سلامة الملف ولا تثبت هوية الناشر.",
                englishOnly = !multilingual,
                variant = inspection.modelType.ifBlank { "custom" },
                capabilities = capabilities,
                performanceTier = tier,
                origin = ModelOrigin.USER_IMPORTED,
                importTimestampEpochMs = System.currentTimeMillis(),
                nativeValidationState = NativeValidationState.VALID
            )

            // Install into app-private storage only after semantic validation + fingerprint calculation.
            store.installVerified(descriptor, temp)
            currentCoroutineContext().ensureActive()
            // Persist descriptor and register only after the private install is complete.
            try {
                registry.registerImported(descriptor)
            } catch (t: Throwable) {
                store.delete(descriptor)
                throw t
            }
            descriptor
        } finally {
            temp.delete()
        }
    }

    private fun ensureStorage(required: Long) {
        val available = StatFs(store.whisperDir.absolutePath).availableBytes
        require(available >= required) { "مساحة التخزين غير كافية لاستيراد نموذج Whisper" }
    }

    private companion object {
        const val MIN_MODEL_BYTES = 1L * 1024L * 1024L
        const val SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L
    }
}
