package com.transvibe.app.data.download

import android.content.Context
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.WhisperModel

enum class ModelTransferOperation { DOWNLOAD, IMPORT }

enum class ModelTransferTerminalEvent(
    val operation: ModelTransferOperation,
    val progressStatus: DownloadProgress.Status,
    val persistentCode: String
) {
    DOWNLOAD_SUCCEEDED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.INSTALLED, "download_succeeded"),
    DOWNLOAD_CANCELLED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.CANCELLED, "download_cancelled"),
    DOWNLOAD_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "download_failed"),
    IMPORT_SUCCEEDED(ModelTransferOperation.IMPORT, DownloadProgress.Status.INSTALLED, "import_succeeded"),
    IMPORT_CANCELLED(ModelTransferOperation.IMPORT, DownloadProgress.Status.CANCELLED, "import_cancelled"),
    IMPORT_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_failed"),
    VERIFICATION_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "verification_failed"),
    IMPORT_VERIFICATION_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_verification_failed"),
    INSTALLATION_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "installation_failed"),
    IMPORT_INSTALLATION_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_installation_failed")
}

data class ModelTransferRecord(
    val modelId: String,
    val operation: ModelTransferOperation,
    val state: DownloadProgress.Status,
    val downloadedBytes: Long,
    val expectedBytes: Long,
    val retryCount: Int,
    val temporaryFile: String?,
    val updatedTimestamp: Long,
    val errorCode: String? = null,
    val message: String? = null
)

/** Durable source of truth for transfer state. Coroutine Jobs are execution handles only. */
class ModelTransferOutcomeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun persist(record: ModelTransferRecord) {
        val p = prefix(record.modelId)
        prefs.edit()
            .putString("${p}operation", record.operation.name)
            .putString("${p}state", record.state.name)
            .putLong("${p}downloaded", record.downloadedBytes)
            .putLong("${p}expected", record.expectedBytes)
            .putInt("${p}retry", record.retryCount)
            .putString("${p}temp", record.temporaryFile)
            .putLong("${p}updated", record.updatedTimestamp)
            .putString("${p}error", record.errorCode)
            .putString("${p}message", record.message)
            .apply()
    }

    fun read(model: WhisperModel): ModelTransferRecord? {
        val p = prefix(model.id)
        val state = prefs.getString("${p}state", null)?.let { runCatching { DownloadProgress.Status.valueOf(it) }.getOrNull() } ?: return null
        val op = prefs.getString("${p}operation", null)?.let { runCatching { ModelTransferOperation.valueOf(it) }.getOrNull() }
            ?: ModelTransferOperation.DOWNLOAD
        return ModelTransferRecord(
            modelId = model.id,
            operation = op,
            state = state,
            downloadedBytes = prefs.getLong("${p}downloaded", 0L),
            expectedBytes = prefs.getLong("${p}expected", model.fileSizeBytes),
            retryCount = prefs.getInt("${p}retry", 0),
            temporaryFile = prefs.getString("${p}temp", null),
            updatedTimestamp = prefs.getLong("${p}updated", 0L),
            errorCode = prefs.getString("${p}error", null),
            message = prefs.getString("${p}message", null)
        )
    }

    fun record(model: WhisperModel, event: ModelTransferTerminalEvent, message: String?) {
        persist(
            ModelTransferRecord(
                modelId = model.id,
                operation = event.operation,
                state = event.progressStatus,
                downloadedBytes = if (event.progressStatus == DownloadProgress.Status.INSTALLED) model.fileSizeBytes else 0L,
                expectedBytes = model.fileSizeBytes,
                retryCount = 0,
                temporaryFile = null,
                updatedTimestamp = System.currentTimeMillis(),
                errorCode = event.persistentCode,
                message = message
            )
        )
    }

    fun restoredProgress(model: WhisperModel): DownloadProgress? = read(model)?.let { record ->
        DownloadProgress(
            modelId = model.id,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.expectedBytes,
            percent = if (record.expectedBytes > 0) ((record.downloadedBytes * 100 / record.expectedBytes).coerceIn(0, 100)).toInt() else 0,
            status = when (record.state) {
                DownloadProgress.Status.DOWNLOADING, DownloadProgress.Status.RETRYING, DownloadProgress.Status.VERIFYING -> DownloadProgress.Status.PAUSED
                else -> record.state
            },
            message = record.message
        )
    }

    fun clear(model: WhisperModel) {
        val p = prefix(model.id)
        val keys = prefs.all.keys.filter { it.startsWith(p) }
        prefs.edit().also { e -> keys.forEach { key -> e.remove(key) } }.apply()
    }

    private fun prefix(modelId: String) = "$modelId."

    private companion object { const val PREFS_NAME = "model_transfer_records_v2" }
}
