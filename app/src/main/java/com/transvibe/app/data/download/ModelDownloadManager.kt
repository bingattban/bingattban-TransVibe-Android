package com.transvibe.app.data.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.data.model.ModelStore
import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.repository.WhisperModelBinaryValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadManager(
    private val context: Context,
    private val store: ModelStore,
    private val resources: AiResourceManager,
    private val network: NetworkMonitor,
    private val binaryValidator: WhisperModelBinaryValidator,
    private val scope: CoroutineScope
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadProgress>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val outcomes = ModelTransferOutcomeStore(context)

    fun state(model: WhisperModel): StateFlow<DownloadProgress> = states.getOrPut(model.id) {
        val partial = store.partial(model)
        val partialBytes = partial.takeIf { it.isFile }?.length() ?: 0L
        val record = outcomes.read(model)
        val restored = when {
            partialBytes > 0L -> DownloadProgress(
                modelId = model.id,
                downloadedBytes = partialBytes,
                totalBytes = record?.expectedBytes?.takeIf { it > 0L } ?: model.fileSizeBytes,
                percent = percent(partialBytes, record?.expectedBytes?.takeIf { it > 0L } ?: model.fileSizeBytes),
                status = DownloadProgress.Status.PAUSED,
                message = record?.message
            )
            else -> outcomes.restoredProgress(model)
        }
        MutableStateFlow(restored ?: DownloadProgress(modelId = model.id, totalBytes = model.fileSizeBytes))
    }

    fun download(model: WhisperModel) {
        paused.remove(model.id)
        launchOwned(model.id) {
            val state = states.getOrPut(model.id) { MutableStateFlow(DownloadProgress(model.id)) }
            try {
                if (!network.isOnline()) {
                    resources.markNetworkUnavailable()
                    error(OFFLINE_MESSAGE)
                }
                checkStorage(model)
                val hadValidInstalledModel = store.validate(model)
                resources.markModel(
                    model,
                    if (hadValidInstalledModel) ModelIntegrityState.READY else ModelIntegrityState.MISSING
                )
                performWithRecovery(model, state, hadValidInstalledModel)
            } catch (ce: CancellationException) {
                if (paused.contains(model.id)) {
                    reconcilePaused(model, state)
                } else {
                    finishTransfer(model, state, ModelTransferTerminalEvent.DOWNLOAD_CANCELLED)
                }
                throw ce
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                val domainError = ProcessingErrorMapper.fromDownloadThrowable(t)
                val event = when (t) {
                    is VerificationFailure -> ModelTransferTerminalEvent.VERIFICATION_FAILED
                    is InstallationFailure -> ModelTransferTerminalEvent.INSTALLATION_FAILED
                    else -> ModelTransferTerminalEvent.DOWNLOAD_FAILED
                }
                finishTransfer(model, state, event, message = null, error = domainError)
            } finally {
                connections.remove(model.id)?.disconnect()
            }
        }
    }

    fun retry(model: WhisperModel) = download(model)

    fun importModel(model: WhisperModel, uri: Uri) {
        paused.remove(model.id)
        launchOwned(model.id) {
            val state = states.getOrPut(model.id) { MutableStateFlow(DownloadProgress(model.id)) }
            val tmp = store.importPartial(model)
            try {
                tmp.delete()
                val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    ?.takeIf { it > 0 } ?: model.fileSizeBytes
                checkStorageForBytes((length.coerceAtLeast(model.fileSizeBytes)) + SAFETY_MARGIN_BYTES)
                var copied = 0L
                state.value = DownloadProgress(model.id, 0, length, 0, DownloadProgress.Status.IMPORTING)
                persistTransfer(model, state.value, ModelTransferOperation.IMPORT, 0, tmp)
                resources.markDownloading(model, 0)
                var lastPersistedImportBytes = 0L
                var lastPersistedImportAt = System.currentTimeMillis()
                context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                    FileOutputStream(tmp).use { fileOut ->
                        fileOut.buffered().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                copied += n
                                state.value = DownloadProgress(model.id, copied, length, percent(copied, length), DownloadProgress.Status.IMPORTING)
                                val now = System.currentTimeMillis()
                                if (copied - lastPersistedImportBytes >= PERSIST_STEP_BYTES || now - lastPersistedImportAt >= PERSIST_INTERVAL_MS) {
                                    persistTransfer(model, state.value, ModelTransferOperation.IMPORT, 0, tmp)
                                    lastPersistedImportBytes = copied
                                    lastPersistedImportAt = now
                                }
                            }
                            output.flush()
                            fileOut.fd.sync()
                        }
                    }
                } ?: error("تعذر فتح ملف النموذج المحدد")
                currentCoroutineContext().ensureActive()
                state.value = state.value.copy(status = DownloadProgress.Status.VERIFYING, percent = 100)
                persistTransfer(model, state.value, ModelTransferOperation.IMPORT, 0, tmp)
                resources.markModel(model, ModelIntegrityState.VERIFYING)
                if (!store.validate(model, tmp)) {
                    throw VerificationFailure("الملف المحدد لا يطابق النموذج أو فشل تحقق SHA-256.")
                }
                currentCoroutineContext().ensureActive()
                if (!binaryValidator.validate(tmp.absolutePath)) {
                    throw VerificationFailure(binaryValidator.validationFailureMessage() ?: "فشل التحقق الأصلي من نموذج Whisper.")
                }
                try {
                    store.installVerified(model, tmp)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    throw InstallationFailure(t)
                }
                finishTransfer(model, state, ModelTransferTerminalEvent.IMPORT_SUCCEEDED)
            } catch (ce: CancellationException) {
                finishTransfer(model, state, ModelTransferTerminalEvent.IMPORT_CANCELLED)
                throw ce
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                val domainError = ProcessingErrorMapper.fromDownloadThrowable(t)
                val event = when (t) {
                    is VerificationFailure -> ModelTransferTerminalEvent.IMPORT_VERIFICATION_FAILED
                    is InstallationFailure -> ModelTransferTerminalEvent.IMPORT_INSTALLATION_FAILED
                    else -> ModelTransferTerminalEvent.IMPORT_FAILED
                }
                finishTransfer(model, state, event, message = null, error = domainError)
            }
        }
    }


    suspend fun exportModel(model: WhisperModel, destinationUri: Uri) = withContext(Dispatchers.IO) {
        val source = store.file(model)
        check(source.isFile && source.canRead()) { "النموذج غير مثبت أو غير قابل للقراءة." }
        context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output, BUFFER_BYTES) }
        } ?: error("تعذر فتح وجهة تصدير النموذج.")
    }

    private fun launchOwned(modelId: String, block: suspend () -> Unit) {
        synchronized(jobs) {
            if (jobs[modelId]?.isCompleted == false) return
            lateinit var ownedJob: Job
            ownedJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    jobs.remove(modelId, ownedJob)
                }
            }
            jobs[modelId] = ownedJob
            ownedJob.start()
        }
    }

    private suspend fun performWithRecovery(model: WhisperModel, state: MutableStateFlow<DownloadProgress>, hadValidInstalledModel: Boolean) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                checkStorage(model)
                performDownloadOnce(model, state)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isRecoverable(t) || attempt >= MAX_AUTOMATIC_RETRIES) throw t
                attempt++
                val partial = store.partial(model)
                val downloaded = partial.takeIf { it.isFile }?.length() ?: 0L
                state.value = DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = downloaded,
                    totalBytes = model.fileSizeBytes,
                    percent = percent(downloaded, model.fileSizeBytes),
                    status = DownloadProgress.Status.RETRYING,
                    message = if (!network.isOnline()) "انقطع الاتصال. سيتم استئناف التنزيل تلقائياً عند عودة الإنترنت." else "تعذر الاتصال مؤقتاً. جارٍ إعادة المحاولة…"
                )
                persistTransfer(model, state.value, ModelTransferOperation.DOWNLOAD, attempt, store.partial(model), "retryable_transport")
                resources.markModel(model, if (hadValidInstalledModel) ModelIntegrityState.READY else ModelIntegrityState.MISSING)
                if (!network.isOnline()) network.online.first { it }
                else delay(RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)])
            }
        }
    }

    private fun checkStorage(model: WhisperModel) {
        val partial = store.partial(model)
        val existing = partial.takeIf {
            it.isFile && it.length() in 1..model.fileSizeBytes && store.partialMetadataMatches(model)
        }?.length() ?: 0L
        val need = (model.fileSizeBytes - existing).coerceAtLeast(0L) + SAFETY_MARGIN_BYTES
        checkStorageForBytes(need)
    }

    private fun checkStorageForBytes(required: Long) {
        val free = StatFs(store.whisperDir.absolutePath).availableBytes
        if (free < required) error("مساحة التخزين غير كافية لتنزيل النموذج.")
    }

    private suspend fun performDownloadOnce(model: WhisperModel, state: MutableStateFlow<DownloadProgress>) {
        val tmp = store.partial(model)
        sanitizePartial(model, tmp)
        store.preparePartialMetadata(model)
        var existing = tmp.takeIf { it.exists() }?.length() ?: 0L
        if (existing == model.fileSizeBytes) {
            verifyAndInstall(model, state, tmp)
            return
        }
        var conn = open(model, existing)
        connections[model.id] = conn

        if (existing > 0L) {
            val decision = try {
                ModelDownloadProtocol.decideResume(existing, conn.responseCode, conn.getHeaderField("Content-Range"), model.fileSizeBytes)
            } catch (invalidRange: IOException) {
                conn.disconnect()
                throw invalidRange
            }
            if (decision.restartFromZero) {
                conn.disconnect()
                tmp.delete()
                existing = 0L
                conn = open(model, 0L)
                connections[model.id] = conn
            }
        }

        val code = conn.responseCode
        if (code !in 200..299) throw HttpStatusFailure(code)
        rejectUnexpectedContent(conn)

        val resumed = if (code == HttpURLConnection.HTTP_PARTIAL) existing else 0L
        val responseLength = conn.contentLengthLong.takeIf { it >= 0L }
        val declaredTotal = if (code == HttpURLConnection.HTTP_PARTIAL) ModelDownloadProtocol.parseContentRange(conn.getHeaderField("Content-Range"))?.total else responseLength
        if (declaredTotal != null && model.fileSizeBytes > 0L && declaredTotal != model.fileSizeBytes) {
            throw IOException("حجم الاستجابة لا يطابق حجم النموذج المتوقع.")
        }
        val total = declaredTotal ?: model.fileSizeBytes
        var downloaded = resumed
        state.value = DownloadProgress(model.id, downloaded, total, percent(downloaded, total), DownloadProgress.Status.DOWNLOADING)
        persistTransfer(model, state.value, ModelTransferOperation.DOWNLOAD, outcomes.read(model)?.retryCount ?: 0, tmp)
        resources.markDownloading(model, state.value.percent)
        var lastPersistedBytes = downloaded
        var lastPersistedAt = System.currentTimeMillis()

        try {
            conn.inputStream.buffered().use { input ->
                FileOutputStream(tmp, resumed > 0).use { fileOut ->
                    fileOut.buffered().use { output ->
                        val buf = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            try {
                                output.write(buf, 0, n)
                            } catch (io: IOException) {
                                throw IOException("تعذر كتابة ملف النموذج على وحدة التخزين.", io)
                            }
                            downloaded += n
                            if (downloaded > model.fileSizeBytes) throw IOException("استجابة الخادم أكبر من حجم النموذج المتوقع.")
                            state.value = DownloadProgress(model.id, downloaded, total, percent(downloaded, total), DownloadProgress.Status.DOWNLOADING)
                            val now = System.currentTimeMillis()
                            if (downloaded - lastPersistedBytes >= PERSIST_STEP_BYTES || now - lastPersistedAt >= PERSIST_INTERVAL_MS) {
                                persistTransfer(model, state.value, ModelTransferOperation.DOWNLOAD, outcomes.read(model)?.retryCount ?: 0, tmp)
                                lastPersistedBytes = downloaded
                                lastPersistedAt = now
                            }
                            resources.markDownloading(model, state.value.percent)
                        }
                        output.flush()
                        fileOut.fd.sync()
                    }
                }
            }
        } finally {
            connections.remove(model.id, conn)
            conn.disconnect()
        }

        currentCoroutineContext().ensureActive()
        if (downloaded != model.fileSizeBytes || tmp.length() != model.fileSizeBytes) {
            throw EOFException("انتهى الاتصال قبل اكتمال تنزيل النموذج.")
        }

        verifyAndInstall(model, state, tmp)
    }

    private suspend fun verifyAndInstall(model: WhisperModel, state: MutableStateFlow<DownloadProgress>, tmp: File) {
        currentCoroutineContext().ensureActive()
        state.value = state.value.copy(status = DownloadProgress.Status.VERIFYING, percent = 100, message = null)
        persistTransfer(model, state.value, ModelTransferOperation.DOWNLOAD, outcomes.read(model)?.retryCount ?: 0, tmp)
        resources.markModel(model, ModelIntegrityState.VERIFYING)
        if (!store.validate(model, tmp)) {
            throw VerificationFailure("فشل التحقق من حجم النموذج أو SHA-256. لم يتم استبدال النموذج المثبت.")
        }
        currentCoroutineContext().ensureActive()
        if (!binaryValidator.validate(tmp.absolutePath)) {
            throw VerificationFailure(binaryValidator.validationFailureMessage() ?: "فشل التحقق الأصلي من نموذج Whisper. لم يتم تثبيت الملف.")
        }
        currentCoroutineContext().ensureActive()
        try {
            store.installVerified(model, tmp)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw InstallationFailure(t)
        }
        finishTransfer(model, state, ModelTransferTerminalEvent.DOWNLOAD_SUCCEEDED)
    }

    private suspend fun finishTransfer(
        model: WhisperModel,
        state: MutableStateFlow<DownloadProgress>,
        event: ModelTransferTerminalEvent,
        message: String? = null,
        error: com.transvibe.app.domain.error.ProcessingError? = null
    ) = withContext(NonCancellable + Dispatchers.IO) {
        connections.remove(model.id)?.disconnect()
        val keepResumablePartial = event == ModelTransferTerminalEvent.DOWNLOAD_FAILED &&
            store.partial(model).isFile && store.partial(model).length() in 1 until model.fileSizeBytes
        if (!keepResumablePartial) store.clearTransferArtifacts(model)
        val diskState = resources.refreshModel(model)
        val finalFile = store.file(model)
        val partialBytes = if (keepResumablePartial) store.partial(model).length() else 0L
        state.value = DownloadProgress(
            modelId = model.id,
            downloadedBytes = when {
                diskState == ModelIntegrityState.READY -> finalFile.length()
                keepResumablePartial -> partialBytes
                else -> 0L
            },
            totalBytes = if (diskState == ModelIntegrityState.READY) finalFile.length() else model.fileSizeBytes,
            percent = when {
                diskState == ModelIntegrityState.READY -> 100
                keepResumablePartial -> percent(partialBytes, model.fileSizeBytes)
                else -> 0
            },
            status = event.progressStatus,
            message = message,
            error = error
        )
        if (keepResumablePartial) {
            persistTransfer(
                model = model,
                progress = state.value,
                operation = ModelTransferOperation.DOWNLOAD,
                retryCount = outcomes.read(model)?.retryCount ?: MAX_AUTOMATIC_RETRIES,
                temporaryFile = store.partial(model),
                errorCode = event.persistentCode
            )
        } else {
            outcomes.record(model, event, message)
        }
        if (error != null) {
            AppLogger.error(
                "ModelTransfer",
                error,
                "transfer_terminal",
                AppLogger.Fields(modelId = model.id, transferState = event.name)
            )
        } else {
            AppLogger.event("ModelTransfer", "transfer_terminal", AppLogger.Fields(modelId = model.id, transferState = event.name))
        }
    }

    private suspend fun reconcilePaused(model: WhisperModel, state: MutableStateFlow<DownloadProgress>) =
        withContext(NonCancellable + Dispatchers.IO) {
            connections.remove(model.id)?.disconnect()
            val diskState = resources.refreshModel(model)
            val partialBytes = store.partial(model).takeIf { it.isFile }?.length() ?: 0L
            state.value = DownloadProgress(
                modelId = model.id,
                downloadedBytes = partialBytes,
                totalBytes = model.fileSizeBytes,
                percent = percent(partialBytes, model.fileSizeBytes),
                status = if (partialBytes > 0L) DownloadProgress.Status.PAUSED
                else if (diskState == ModelIntegrityState.READY) DownloadProgress.Status.INSTALLED
                else DownloadProgress.Status.IDLE
            )
            if (partialBytes > 0L) persistTransfer(model, state.value, ModelTransferOperation.DOWNLOAD, outcomes.read(model)?.retryCount ?: 0, store.partial(model))
        }

    private fun sanitizePartial(model: WhisperModel, tmp: File) {
        if (!tmp.exists()) {
            store.clearPartialMetadata(model)
            return
        }
        val size = tmp.length()
        if (size <= 0L || size > model.fileSizeBytes || !store.partialMetadataMatches(model)) {
            tmp.delete()
            store.clearPartialMetadata(model)
        }
    }

    private fun open(model: WhisperModel, from: Long): HttpURLConnection {
        val sourceUrl = model.downloadUrl ?: throw IOException("النموذج المستورد محلياً لا يملك مصدر تنزيل رسمي.")
        var url = URL(sourceUrl)
        repeat(MAX_HTTPS_REDIRECTS + 1) { hop ->
            require(url.protocol.equals("https", ignoreCase = true)) { "Model downloads require HTTPS." }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TransVibe-Android/1")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
                connect()
            }
            if (conn.responseCode in REDIRECT_CODES) {
                if (hop >= MAX_HTTPS_REDIRECTS) {
                    conn.disconnect()
                    throw IOException("تجاوز خادم النموذج الحد المسموح للتحويلات الآمنة.")
                }
                val location = conn.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                    ?: run { conn.disconnect(); throw IOException("تحويل تنزيل النموذج لا يحتوي على وجهة صالحة.") }
                val next = ModelDownloadProtocol.resolveRedirect(url, location)
                conn.disconnect()
                url = next
            } else {
                return conn
            }
        }
        throw IOException("تعذر فتح اتصال تنزيل آمن.")
    }

    private fun rejectUnexpectedContent(conn: HttpURLConnection) {
        val type = conn.contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (type == "text/html" || type == "text/plain" || type == "application/json") {
            throw IOException("أعاد الخادم محتوى غير متوقع بدلاً من ملف النموذج.")
        }
    }


    fun pause(model: WhisperModel) {
        paused.add(model.id)
        val job = jobs[model.id]
        if (job?.isActive == true) {
            job.cancel()
            connections.remove(model.id)?.disconnect()
        } else {
            scope.launch(Dispatchers.IO) {
                reconcilePaused(model, states.getOrPut(model.id) { MutableStateFlow(DownloadProgress(model.id)) })
            }
        }
    }

    fun cancel(model: WhisperModel) {
        paused.remove(model.id)
        val job = jobs[model.id]
        if (job?.isActive == true) {
            job.cancel()
            connections.remove(model.id)?.disconnect()
        } else {
            scope.launch(Dispatchers.IO) {
                finishTransfer(
                    model,
                    states.getOrPut(model.id) { MutableStateFlow(DownloadProgress(model.id)) },
                    ModelTransferTerminalEvent.DOWNLOAD_CANCELLED
                )
            }
        }
    }

    fun delete(model: WhisperModel) {
        paused.remove(model.id)
        connections.remove(model.id)?.disconnect()
        jobs[model.id]?.cancel()
        store.delete(model)
        outcomes.clear(model)
        resources.markModel(model, ModelIntegrityState.MISSING)
        states.getOrPut(model.id) { MutableStateFlow(DownloadProgress(model.id)) }.value = DownloadProgress(model.id)
    }

    private fun persistTransfer(
        model: WhisperModel,
        progress: DownloadProgress,
        operation: ModelTransferOperation,
        retryCount: Int,
        temporaryFile: File?,
        errorCode: String? = null
    ) {
        AppLogger.event(
            "ModelTransfer",
            "transfer_state",
            AppLogger.Fields(modelId = model.id, transferState = progress.status.name, errorCode = errorCode)
        )
        outcomes.persist(
            ModelTransferRecord(
                modelId = model.id,
                operation = operation,
                state = progress.status,
                downloadedBytes = progress.downloadedBytes,
                expectedBytes = progress.totalBytes.takeIf { it > 0L } ?: model.fileSizeBytes,
                retryCount = retryCount,
                temporaryFile = temporaryFile?.absolutePath,
                updatedTimestamp = System.currentTimeMillis(),
                errorCode = errorCode,
                message = progress.message
            )
        )
    }

    private fun isRecoverable(t: Throwable): Boolean = ModelDownloadProtocol.isRecoverableTransportFailure(t)


    private fun percent(done: Long, total: Long) = if (total <= 0) 0 else ((done * 100 / total).coerceIn(0, 100)).toInt()

    private class VerificationFailure(message: String) : IOException(message)
    private class InstallationFailure(cause: Throwable) : IOException("فشل تثبيت نموذج Whisper مع الحفاظ على النموذج السابق إن وجد.", cause)

    private companion object {
        const val OFFLINE_MESSAGE = "لا يوجد اتصال بالإنترنت. يمكنك الاستمرار في استخدام التطبيق بدون إنترنت، لكن تنزيل النماذج يحتاج إلى اتصال."
        const val BUFFER_BYTES = 256 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_AUTOMATIC_RETRIES = 4
        const val MAX_HTTPS_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L
        const val PERSIST_STEP_BYTES = 1L * 1024L * 1024L
        const val PERSIST_INTERVAL_MS = 1_000L
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L)
    }
}
