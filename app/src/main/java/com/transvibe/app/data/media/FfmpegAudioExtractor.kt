package com.transvibe.app.data.media

import android.content.Context
import android.net.Uri
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.concurrency.AppDispatchers
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Optional FFmpegKit fallback. It is only entered if a local FFmpegKit AAR was
 * supplied. All temporary files belong to one invocation and are deleted even
 * when the caller cancels or an extractor throws.
 */
internal class FfmpegAudioExtractor(private val context: Context) {
    fun isAvailable(): Boolean = runCatching { Class.forName(FFMPEG_KIT_CLASS) }.isSuccess

    suspend fun <T> withPcm16kMonoWav(video: Uri, block: suspend (File) -> T): T = withContext(AppDispatchers.io) {
        check(isAvailable()) { "مكتبة FFmpegKit غير مضافة إلى app/libs." }
        val jobDirectory = File(context.cacheDir, "transvibe-ffmpeg-${UUID.randomUUID()}")
        check(jobDirectory.mkdir()) { "تعذر إنشاء مساحة صوت مؤقتة." }
        try {
            val startedAtNs = System.nanoTime()
            // FFmpegKit accepts filesystem paths, not Android content:// URIs.
            // The content copy is streamed, never accumulated in memory.
            val inputFile = if (video.scheme == null || video.scheme == "file") {
                File(requireNotNull(video.path))
            } else {
                require(video.scheme == "content") { "مصدر الفيديو غير مدعوم دون اتصال: ${video.scheme}" }
                File(jobDirectory, "source-video.bin").also { target ->
                    val source = context.contentResolver.openInputStream(video)
                        ?: error("تعذر فتح الفيديو المحدد.")
                    source.use { input -> target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    } }
                }
            }
            require(inputFile.isFile && inputFile.length() > 0L) { "ملف الفيديو فارغ أو غير قابل للقراءة." }
            val wav = File(jobDirectory, "mono16k.wav")
            val preparationMs = (System.nanoTime() - startedAtNs) / 1_000_000L
            val extractionStartedNs = System.nanoTime()
            runFfmpeg(arrayOf(
                "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", inputFile.absolutePath, "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-af", "aresample=async=1:first_pts=0",
                "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav", wav.absolutePath
            ))
            check(wav.isFile && wav.length() > 44L) { "تعذر استخراج صوت PCM من الفيديو." }
            AppLogger.d("AudioDecodeTiming", "engine=ffmpeg input_copy_ms=$preparationMs " +
                "extraction_ms=${(System.nanoTime() - extractionStartedNs) / 1_000_000L} " +
                "wav_bytes=${wav.length()} source_bytes=${inputFile.length()}")
            block(wav)
        } finally {
            jobDirectory.deleteRecursively()
        }
    }

    /** Use FFmpegKit's asynchronous API so coroutine cancellation cancels ONLY this session. */
    private suspend fun runFfmpeg(arguments: Array<String>) = suspendCancellableCoroutine<Unit> { continuation ->
        var session: Any? = null
        try {
            val kit = Class.forName(FFMPEG_KIT_CLASS)
            val callbackType = Class.forName("com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback")
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
                if (method.name == "apply") {
                    val finished = args?.firstOrNull()
                    val code = finished?.javaClass?.getMethod("getReturnCode")?.invoke(finished)?.toString()
                    if (continuation.isActive) {
                        if (code == "0") continuation.resumeWith(Result.success(Unit))
                        else continuation.resumeWith(Result.failure(IllegalStateException("فشل استخراج الصوت بواسطة FFmpeg (رمز $code).")))
                    }
                }
                null
            }
            val async = kit.methods.firstOrNull {
                it.name == "executeWithArgumentsAsync" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].isArray && it.parameterTypes[1] == callbackType
            } ?: error("إصدار FFmpegKit لا يدعم واجهة التنفيذ غير المتزامن المطلوبة.")
            session = async.invoke(null, arguments, callback)
            continuation.invokeOnCancellation {
                runCatching {
                    val id = session?.javaClass?.getMethod("getSessionId")?.invoke(session)
                    val cancel = kit.methods.firstOrNull { it.name == "cancel" && it.parameterTypes.size == 1 }
                    if (id != null && cancel != null) cancel.invoke(null, id)
                }
            }
        } catch (failure: Throwable) {
            val unwrapped = (failure as? InvocationTargetException)?.targetException ?: failure
            if (continuation.isActive) continuation.resumeWith(Result.failure(unwrapped))
        }
    }

    private companion object { const val FFMPEG_KIT_CLASS = "com.arthenica.ffmpegkit.FFmpegKit" }
}
