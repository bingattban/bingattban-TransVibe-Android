package com.transvibe.app.data.asr

import com.transvibe.app.domain.asr.FastAsrModelInfo
import com.transvibe.app.domain.asr.SingleFastAsrModelRepository
import com.transvibe.app.domain.model.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Optional adapter for additional Moonshine models; does not alter existing model routing. */
internal class AdditionalMoonshineModelRepository(
    override val model: FastAsrModelInfo,
    private val store: AdditionalMoonshineModelStore,
    private val scope: CoroutineScope
) : SingleFastAsrModelRepository {
    private val mutableState = MutableStateFlow(snapshot())
    override val state: StateFlow<DownloadProgress> = mutableState.asStateFlow()
    private var job: Job? = null
    private var refreshJob: Job? = null

    override fun isInstalled(): Boolean = store.isInstalled()

    override fun refresh() {
        if (job?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            val next = snapshot()
            if (job?.isActive != true) mutableState.value = next
        }
    }

    override fun download() = launchOperation(DownloadProgress.Status.DOWNLOADING) {
        store.downloadVerified { downloaded ->
            val total = model.totalBytes
            mutableState.value = DownloadProgress(
                modelId = model.id,
                downloadedBytes = downloaded,
                totalBytes = total,
                percent = if (total > 0L) ((downloaded * 100L / total).coerceIn(0L, 99L)).toInt() else 0,
                status = DownloadProgress.Status.DOWNLOADING
            )
        }
    }

    override fun importModel(sourceUri: String) = launchOperation(DownloadProgress.Status.IMPORTING) {
        store.importBundle(sourceUri)
    }

    override fun exportModel(destinationUri: String) = launchOperation(null) {
        store.exportBundle(destinationUri)
    }

    override fun delete() {
        if (job?.isActive == true) return
        refreshJob?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                store.delete()
                mutableState.value = snapshot()
            } catch (e: Exception) {
                mutableState.value = failure(e)
            }
        }
    }

    private fun launchOperation(status: DownloadProgress.Status?, operation: () -> Unit) {
        if (job?.isActive == true) return
        refreshJob?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                if (status != null) mutableState.value = DownloadProgress(
                    modelId = model.id, totalBytes = model.totalBytes, status = status
                )
                operation()
                mutableState.value = snapshot()
            } catch (cancel: CancellationException) {
                mutableState.value = mutableState.value.copy(status = DownloadProgress.Status.CANCELLED)
                throw cancel
            } catch (e: Exception) {
                mutableState.value = failure(e)
            }
        }
    }

    private fun failure(e: Exception): DownloadProgress = DownloadProgress(
        modelId = model.id, totalBytes = model.totalBytes,
        status = DownloadProgress.Status.FAILED,
        message = e.message ?: "تعذرت إدارة نموذج Moonshine."
    )

    private fun snapshot(): DownloadProgress {
        val installed = store.isInstalled()
        return DownloadProgress(
            modelId = model.id,
            downloadedBytes = if (installed) model.totalBytes else 0L,
            totalBytes = model.totalBytes,
            percent = if (installed) 100 else 0,
            status = if (installed) DownloadProgress.Status.INSTALLED else DownloadProgress.Status.IDLE
        )
    }
}
