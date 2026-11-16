package com.transvibe.app.data.asr

import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.domain.asr.FastAsrModelInfo
import com.transvibe.app.domain.asr.SingleFastAsrModelRepository
import com.transvibe.app.domain.asr.OfficialFastAsrModels
import com.transvibe.app.domain.model.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.roundToInt

class MoonshineModelRepository(
    private val store: MoonshineModelStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope
) : SingleFastAsrModelRepository {
    override val model: FastAsrModelInfo = OfficialFastAsrModels.moonshineTinyEnglish
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<DownloadProgress> = _state.asStateFlow()
    private var downloadJob: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun isInstalled(): Boolean = store.quickInstalled()

    override fun importModel(sourceUri: String) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                _state.value = DownloadProgress(model.id, 0L, model.totalBytes, 0, DownloadProgress.Status.IMPORTING)
                store.importBundle(sourceUri)
                check(store.verifyInstalled()) { "ملفات Moonshine المستوردة غير صحيحة أو غير مكتملة." }
                store.markVerified()
                _state.value = DownloadProgress(model.id, model.totalBytes, model.totalBytes, 100, DownloadProgress.Status.INSTALLED)
            } catch (t: Throwable) {
                _state.value = DownloadProgress(model.id, 0L, model.totalBytes, 0, DownloadProgress.Status.FAILED, message = t.message ?: "تعذر استيراد Moonshine.")
            }
        }
    }

    override fun refresh() {
        scope.launch(Dispatchers.IO) {
            val installed = store.quickInstalled() || store.verifyInstalled()
            _state.value = DownloadProgress(
                modelId = model.id,
                downloadedBytes = if (installed) model.totalBytes else 0L,
                totalBytes = model.totalBytes,
                percent = if (installed) 100 else 0,
                status = if (installed) DownloadProgress.Status.INSTALLED else DownloadProgress.Status.IDLE
            )
        }
    }

    override fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                check(network.isOnline()) { "لا يوجد اتصال بالإنترنت لتنزيل Moonshine." }
                store.clearMarker()
                var completedBytes = 0L
                val knownTotal = MoonshineTinyEnglishManifest.files.sumOf { it.expectedBytes ?: 550_000L }
                for (entry in MoonshineTinyEnglishManifest.files) {
                    currentCoroutineContext().ensureActive()
                    val target = store.target(entry)
                    if (target.isFile && validateFile(target, entry)) {
                        completedBytes += target.length()
                        publish(completedBytes, knownTotal, DownloadProgress.Status.DOWNLOADING)
                        continue
                    }
                    target.delete()
                    val tmp = store.partial(entry)
                    tmp.delete()
                    downloadFile(entry, tmp, completedBytes, knownTotal)
                    currentCoroutineContext().ensureActive()
                    _state.value = _state.value.copy(status = DownloadProgress.Status.VERIFYING)
                    check(validateFile(tmp, entry)) { "فشل التحقق من ملف ${entry.name}." }
                    if (!tmp.renameTo(target)) {
                        FileInputStream(tmp).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                        tmp.delete()
                    }
                    completedBytes += target.length()
                    publish(completedBytes, knownTotal, DownloadProgress.Status.DOWNLOADING)
                }
                check(store.verifyInstalled()) { "ملفات Moonshine غير مكتملة بعد التنزيل." }
                store.markVerified()
                _state.value = DownloadProgress(
                    modelId = model.id,
                    downloadedBytes = model.totalBytes,
                    totalBytes = model.totalBytes,
                    percent = 100,
                    status = DownloadProgress.Status.INSTALLED
                )
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(status = DownloadProgress.Status.CANCELLED)
                throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    status = DownloadProgress.Status.FAILED,
                    message = t.message ?: "تعذر تنزيل Moonshine."
                )
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
            }
        }
    }

    override fun exportModel(destinationUri: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { store.exportBundle(destinationUri) }
                .onFailure { error -> _state.value = _state.value.copy(message = error.message ?: "تعذر تصدير النموذج.") }
        }
    }

    override fun delete() {
        downloadJob?.cancel()
        activeConnection?.disconnect()
        activeConnection = null
        scope.launch(Dispatchers.IO) {
            store.deleteAll()
            _state.value = DownloadProgress(modelId = model.id, totalBytes = model.totalBytes)
        }
    }

    private fun initialState(): DownloadProgress {
        val installed = store.quickInstalled()
        return DownloadProgress(
            modelId = model.id,
            downloadedBytes = if (installed) model.totalBytes else 0L,
            totalBytes = model.totalBytes,
            percent = if (installed) 100 else 0,
            status = if (installed) DownloadProgress.Status.INSTALLED else DownloadProgress.Status.IDLE
        )
    }

    private suspend fun downloadFile(entry: MoonshineModelFile, tmp: File, completedBefore: Long, knownTotal: Long) {
        val connection = openFollowingRedirects(entry.url)
        activeConnection = connection
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode} عند تنزيل ${entry.name}." }
        rejectUnexpectedContent(connection)
        val buffer = ByteArray(128 * 1024)
        var current = 0L
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    current += read
                    publish(completedBefore + current, knownTotal, DownloadProgress.Status.DOWNLOADING)
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        activeConnection = null
    }


    private fun publish(downloaded: Long, total: Long, status: DownloadProgress.Status) {
        val percent = if (total > 0L) ((downloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 99) else 0
        _state.value = DownloadProgress(model.id, downloaded, total, percent, status)
    }

    private fun validateFile(file: File, entry: MoonshineModelFile): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        entry.expectedBytes?.let { if (file.length() != it) return false }
        if (entry.name == "tokens.txt" && !MoonshineTokenIntegrity.plausible(file)) return false
        entry.sha256?.let { if (sha256(file) != it) return false }
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var url = URL(initialUrl)
        repeat(6) {
            require(url.protocol.equals("https", ignoreCase = true)) { "تم رفض رابط تنزيل غير آمن." }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TransVibe-Android/1")
            }
            val code = conn.responseCode
            if (code !in setOf(301, 302, 303, 307, 308)) return conn
            val location = conn.getHeaderField("Location") ?: return conn
            val next = URL(url, location)
            conn.disconnect()
            require(next.protocol.equals("https", ignoreCase = true)) { "تم رفض إعادة توجيه غير آمنة." }
            url = next
        }
        error("عدد كبير من عمليات إعادة التوجيه أثناء تنزيل Moonshine.")
    }

    private fun rejectUnexpectedContent(connection: HttpURLConnection) {
        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        require(type !in setOf("text/html", "text/plain", "application/json")) {
            "أعاد الخادم محتوى غير متوقع بدلاً من ملف Moonshine."
        }
    }
}
