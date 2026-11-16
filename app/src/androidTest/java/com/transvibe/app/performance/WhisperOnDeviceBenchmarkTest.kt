package com.transvibe.app.performance

import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transvibe.app.nativebridge.whisper.WhisperNative
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Explicit, opt-in physical device benchmark. Requires files already accessible to the target
 * app: a real Whisper model and 16kHz/mono/signed 16-bit LE raw PCM audio (max 45 seconds).
 * Never stores the source audio, transcript, model path or reference text in the report.
 * The first inference is a warm-up. The following 3 runs are timed on the same exact input.
 * No speed claim is possible without a separate baseline using the same device and input.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOnDeviceBenchmarkTest {
    @Test
    fun benchmarkInstalledModelOnlyWhenExplicitlyConfigured() {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("transvibeBenchModel").orEmpty()
        val pcmPath = args.getString("transvibeBenchAudioPcm16").orEmpty()
        assumeTrue("Explicit transvibeBenchModel and transvibeBenchAudioPcm16 required",
            modelPath.isNotBlank() && pcmPath.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(modelPath)
        val pcm = File(pcmPath)
        assertTrue("A readable installed model is required", model.isFile && model.canRead())
        assertTrue("A readable PCM16 input is required", pcm.isFile && pcm.canRead())
        assertTrue("PCM16 input must be non-empty, even-sized and <= 45 seconds",
            pcm.length() in 2L..(45L * 16_000L * 2L) && pcm.length() % 2L == 0L)

        val threads = args.getString("transvibeBenchThreads")?.toIntOrNull() ?: 2
        assertTrue("Thread count must be 1..8", threads in 1..8)
        val expectedFile = args.getString("transvibeBenchReferenceText")
            ?.takeIf { it.isNotBlank() }?.let(::File)
        if (expectedFile != null) assertTrue("Reference transcript file required", expectedFile.isFile)
        val expectedText = expectedFile?.readText(Charsets.UTF_8)
        val modelSha = sha256(model)
        val audioSha = sha256(pcm)
        val pcmBytes = pcm.readBytes()
        val samples = FloatArray(pcmBytes.size / 2) { i ->
            val low = pcmBytes[2 * i].toInt() and 0xff
            val high = pcmBytes[2 * i + 1].toInt()
            ((high shl 8) or low).toShort().toFloat() / 32768f
        }
        val audioMs = samples.size.toLong() * 1000L / 16_000L
        val modelId = args.getString("transvibeBenchModelId")?.takeIf(String::isNotBlank) ?: "manual-model"
        val sampleId = args.getString("transvibeBenchSampleId")?.takeIf(String::isNotBlank) ?: "reference-pcm"
        val output = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "benchmarks/whisper-device-${System.currentTimeMillis()}.jsonl")
        output.parentFile?.mkdirs()
        val power = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java) else null
        fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 6 else 6

        val handle = WhisperNative.createContext(model.absolutePath)
        try {
            val backend = WhisperNative.runtimeBackend(handle)
            assertTrue("Backend must be verified", backend.startsWith("CPU|") || backend.startsWith("GPU|"))
            for (index in -1..2) {
                val startThermal = thermal()
                val startNs = SystemClock.elapsedRealtimeNanos()
                val segments = WhisperNative.transcribeDetailed(
                    handle, samples, language = "en", translate = false, threads = threads)
                val elapsed = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L).coerceAtLeast(1L)
                if (index < 0) continue // warm-up never included in performance statistics
                val recognized = segments.joinToString(" ") { it.segment.originalText }
                val wer = expectedText?.let { wordErrorRate(it, recognized) }
                val record = JSONObject().apply {
                    put("schemaVersion", 1)
                    put("device", DeviceBenchmarkProbe.deviceLabel(context))
                    put("modelId", modelId)
                    put("modelSha256", modelSha)
                    put("sampleId", sampleId)
                    put("sampleSha256", audioSha)
                    put("backend", backend)
                    put("threads", threads)
                    put("audioDurationMs", audioMs)
                    put("recognitionMs", elapsed)
                    put("totalProcessingMs", elapsed) // Isolated ASR benchmark, not full video pipeline
                    put("thermalStatusStart", startThermal)
                    put("thermalStatusEnd", thermal())
                    put("peakPssKb", Debug.getPss().toLong())
                    put("crashedOrOom", false)
                    put("wer", wer ?: JSONObject.NULL)
                    put("repeatIndex", index)
                }
                output.appendText(record.toString() + "\n", Charsets.UTF_8)
            }
        } finally {
            WhisperNative.releaseContext(handle)
        }
        assertTrue("Expected 3 benchmark measurements", output.readLines().size == 3)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun wordErrorRate(reference: String, recognized: String): Double {
        fun words(text: String): List<String> = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
        val expected = words(reference)
        val actual = words(recognized)
        if (expected.isEmpty()) return if (actual.isEmpty()) 0.0 else 1.0
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { i, token ->
            val next = IntArray(actual.size + 1)
            next[0] = i + 1
            actual.forEachIndexed { j, candidate ->
                next[j + 1] = minOf(previous[j + 1] + 1, next[j] + 1,
                    previous[j] + if (token == candidate) 0 else 1)
            }
            previous = next
        }
        return previous.last().toDouble() / expected.size.toDouble()
    }
}
