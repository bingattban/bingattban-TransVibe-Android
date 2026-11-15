package com.transvibe.app.data.asr

import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.domain.asr.FastAsrModelInfo
import com.transvibe.app.domain.asr.OfficialFastAsrModels
import com.transvibe.app.domain.asr.SingleFastAsrModelRepository
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

class SenseVoiceModelRepository(
    private val store: SenseVoiceModelStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope
) : SingleFastAsrModelRepository {
    override val model: FastAsrModelInfo = OfficialFastAsrModels.senseVoiceSmall
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<DownloadProgress> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    override fun isInstalled(): Boolean = store.quickInstalled()
    override fun refresh() { scope.launch(Dispatchers.IO) { publishInstalled(store.quickInstalled() || store.verifyInstalled()) } }

    override fun importModel(sourceUri: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                _state.value = DownloadProgress(model.id, 0, model.totalBytes, 0, DownloadProgress.Status.IMPORTING)
                store.importBundle(sourceUri)
                check(store.verifyInstalled()) { "ملفات SenseVoice المستوردة غير صحيحة أو غير مكتملة." }
                publishInstalled(true)
            } catch (t: Throwable) {
                _state.value = DownloadProgress(model.id, 0, model.totalBytes, 0, DownloadProgress.Status.FAILED, message = t.message ?: "تعذر استيراد SenseVoice.")
            }
        }
    }

    override fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                check(network.isOnline()) { "لا يوجد اتصال بالإنترنت لتنزيل SenseVoice." }
                store.clearMarker()
                var completed = 0L
                for (entry in SenseVoiceSmallManifest.files) {
                    currentCoroutineContext().ensureActive()
                    val target = store.target(entry)
                    if (validate(target, entry)) { completed += target.length(); publish(completed); continue }
                    target.delete()
                    val tmp = store.partial(entry).also { it.delete() }
                    downloadFile(entry, tmp, completed)
                    _state.value = _state.value.copy(status = DownloadProgress.Status.VERIFYING)
                    check(validate(tmp, entry)) { "فشل التحقق من ${entry.name}." }
                    if (!tmp.renameTo(target)) {
                        FileInputStream(tmp).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                        tmp.delete()
                    }
                    completed += target.length(); publish(completed)
                }
                check(store.verifyInstalled()) { "ملفات SenseVoice غير مكتملة بعد التنزيل." }
                publishInstalled(true)
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(status = DownloadProgress.Status.CANCELLED); throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(status = DownloadProgress.Status.FAILED, message = t.message ?: "تعذر تنزيل SenseVoice.")
            } finally { connection?.disconnect(); connection = null }
        }
    }

    override fun exportModel(destinationUri: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { store.exportBundle(destinationUri) }
                .onFailure { error -> _state.value = _state.value.copy(message = error.message ?: "تعذر تصدير النموذج.") }
        }
    }

    override fun delete() {
        job?.cancel(); connection?.disconnect(); connection = null
        scope.launch(Dispatchers.IO) { store.deleteAll(); publishInstalled(false) }
    }

    private fun initialState() = DownloadProgress(modelId = model.id, downloadedBytes = if (store.quickInstalled()) model.totalBytes else 0, totalBytes = model.totalBytes, percent = if (store.quickInstalled()) 100 else 0, status = if (store.quickInstalled()) DownloadProgress.Status.INSTALLED else DownloadProgress.Status.IDLE)
    private fun publishInstalled(installed: Boolean) { _state.value = DownloadProgress(model.id, if (installed) model.totalBytes else 0, model.totalBytes, if (installed) 100 else 0, if (installed) DownloadProgress.Status.INSTALLED else DownloadProgress.Status.IDLE) }
    private fun publish(downloaded: Long) { _state.value = DownloadProgress(model.id, downloaded, model.totalBytes, ((downloaded.toDouble()/model.totalBytes)*100).roundToInt().coerceIn(0,99), DownloadProgress.Status.DOWNLOADING) }
    private fun validate(file: File, entry: SenseVoiceModelFile): Boolean = file.isFile && file.length() == entry.expectedBytes && sha256(file) == entry.sha256
    private fun sha256(file: File): String { val d=MessageDigest.getInstance("SHA-256"); FileInputStream(file).use { i -> val b=ByteArray(128*1024); while(true){val n=i.read(b); if(n<=0) break; d.update(b,0,n)} }; return d.digest().joinToString(""){"%02x".format(it)} }
    private suspend fun downloadFile(entry: SenseVoiceModelFile, tmp: File, completed: Long) {
        val c=open(entry.url); connection=c; check(c.responseCode in 200..299){"HTTP ${c.responseCode} عند تنزيل ${entry.name}."}; rejectUnexpectedContent(c)
        c.inputStream.use { input -> FileOutputStream(tmp).use { output -> val b=ByteArray(128*1024); var current=0L; while(true){ currentCoroutineContext().ensureActive(); val n=input.read(b); if(n<0) break; output.write(b,0,n); current+=n; publish(completed+current) }; output.fd.sync() } }
        c.disconnect(); connection=null
    }
    private fun open(initial: String): HttpURLConnection {
        var url = URL(initial)
        repeat(6) {
            require(url.protocol.equals("https", ignoreCase = true)) { "تم رفض رابط تنزيل غير آمن." }
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "TransVibe-Android/1")
            }
            if (c.responseCode !in setOf(301, 302, 303, 307, 308)) return c
            val location = c.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                ?: run { c.disconnect(); error("إعادة التوجيه لا تحتوي على وجهة صالحة.") }
            val next = URL(url, location)
            c.disconnect()
            require(next.protocol.equals("https", ignoreCase = true)) { "تم رفض إعادة توجيه غير آمنة." }
            url = next
        }
        error("عدد كبير من عمليات إعادة التوجيه.")
    }

    private fun rejectUnexpectedContent(connection: HttpURLConnection) {
        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        require(type !in setOf("text/html", "text/plain", "application/json")) {
            "أعاد الخادم محتوى غير متوقع بدلاً من ملف SenseVoice."
        }
    }
}
