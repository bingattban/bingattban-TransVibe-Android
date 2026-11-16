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
            val scheme = video.scheme
            require(scheme == null || scheme == "file" || scheme == "content") {
                "مصدر الفيديو غير مدعوم دون اتصال: $scheme"
            }

            // SAF hands FFmpegKit a readable content:// descriptor without duplicating the video.
            // Reflective access keeps FFmpegKit optional for Code On The Go installations.
            val saf = if (scheme == "content") safParameterForRead(video) else null
            var inputArgument = FfmpegInputPolicy.directInput(scheme, video.path, saf)
            var inputMode = if (scheme == "content" && inputArgument != null) "saf" else
                if (scheme == "content") "content_copy" else "local_file"
            var inputCopyMs = 0L
            var sourceBytes = -1L

            if (FfmpegInputPolicy.needsContentCopy(scheme, inputArgument)) {
                val startedNs = System.nanoTime()
                val copied = copyContentVideo(video, jobDirectory)
                inputArgument = copied.absolutePath
                sourceBytes = copied.length()
                inputCopyMs = (System.nanoTime() - startedNs) / 1_000_000L
            } else if (scheme == null || scheme == "file") {
                val local = File(requireNotNull(inputArgument) { "مسار الفيديو غير متاح." })
                require(local.isFile && local.length() > 0L) { "ملف الفيديو فارغ أو غير قابل للقراءة." }
                inputArgument = local.absolutePath
                sourceBytes = local.length()
            }

            val wav = File(jobDirectory, "mono16k.wav")
            val extractionStartedNs = System.nanoTime()
            try {
                runFfmpeg(ffmpegArguments(requireNotNull(inputArgument), wav))
            } catch (failure: Throwable) {
                // A provider may reject SAF even if FFmpegKit supports the protocol.
                // A single streamed-copy retry preserves the previous extractor behavior.
                // Cancellation must win over retry and no copy may start after cancellation.
                currentCoroutineContext().ensureActive()
                if (!FfmpegInputPolicy.retryWithCopy(scheme, inputMode == "saf")) throw failure
                AppLogger.d("AudioPipeline", "ffmpeg_saf_failed_retrying_streamed_copy")
                val startedNs = System.nanoTime()
                val copied = copyContentVideo(video, jobDirectory)
                inputCopyMs = (System.nanoTime() - startedNs) / 1_000_000L
                sourceBytes = copied.length()
                inputMode = "saf_copy_fallback"
                runFfmpeg(ffmpegArguments(copied.absolutePath, wav))
            }

            check(wav.isFile && wav.length() > 44L) { "تعذر استخراج صوت PCM من الفيديو." }
            AppLogger.d("AudioDecodeTiming", "engine=ffmpeg input_mode=$inputMode input_copy_ms=$inputCopyMs " +
                "extraction_ms=${(System.nanoTime() - extractionStartedNs) / 1_000_000L} " +
                "wav_bytes=${wav.length()} source_bytes=$sourceBytes")
            block(wav)
        } finally {
            jobDirectory.deleteRecursively()
        }
    }

    private fun safParameterForRead(video: Uri): String? = runCatching {
        val config = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
        config.getMethod("getSafParameterForRead", Context::class.java, Uri::class.java)
            .invoke(null, context, video) as? String
    }.getOrNull()

    private suspend fun copyContentVideo(video: Uri, directory: File): File {
        val target = File(directory, "source-video.bin")
        val source = context.contentResolver.openInputStream(video)
            ?: error("تعذر فتح الفيديو المحدد.")
        source.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        require(target.isFile && target.length() > 0L) { "ملف الفيديو فارغ أو غير قابل للقراءة." }
        return target
    }

    private fun ffmpegArguments(input: String, wav: File): Array<String> = arrayOf(
        "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-i", input, "-map", "0:a:0", "-vn", "-sn", "-dn",
        "-af", "aresample=async=1:first_pts=0",
        "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav", wav.absolutePath
    )

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
