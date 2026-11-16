package com.transvibe.app.data.asr

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SenseVoiceModelFile(
    val name: String,
    val url: String,
    val expectedBytes: Long,
    val sha256: String
)

internal object SenseVoiceSmallManifest {
    private const val ROOT = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    val files = listOf(
        SenseVoiceModelFile(
            "model.int8.onnx",
            "$ROOT/model.int8.onnx?download=true",
            239_233_841L,
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        ),
        SenseVoiceModelFile(
            "tokens.txt",
            "$ROOT/tokens.txt?download=true",
            315_894L,
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        )
    )
}

class SenseVoiceModelStore(private val context: Context) {
    private val modelDir = File(context.filesDir, "models/sensevoice/small-int8")
    private val marker = File(modelDir, ".verified-v1")
    val modelFile: File get() = File(modelDir, "model.int8.onnx")
    val tokensFile: File get() = File(modelDir, "tokens.txt")

    fun ensureDirectory(): File = modelDir.apply { mkdirs() }
    fun quickInstalled(): Boolean = marker.isFile &&
        modelFile.length() == 239_233_841L && tokensFile.length() == 315_894L

    fun verifyInstalled(): Boolean {
        if (modelFile.length() != 239_233_841L || tokensFile.length() != 315_894L) return false
        if (sha256(modelFile) != SenseVoiceSmallManifest.files[0].sha256) return false
        if (sha256(tokensFile) != SenseVoiceSmallManifest.files[1].sha256) return false
        markVerified()
        return true
    }

    fun importBundle(sourceUri: String) {
        val staging = File(context.cacheDir, "sensevoice-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        if (!entry.isDirectory && safeName in setOf("model.int8.onnx", "tokens.txt")) {
                            File(staging, safeName).outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("تعذر فتح ملف SenseVoice من ذاكرة الهاتف.")
            val model = File(staging, "model.int8.onnx")
            val tokens = File(staging, "tokens.txt")
            check(model.length() == 239_233_841L && sha256(model) == SenseVoiceSmallManifest.files[0].sha256) { "ملف model.int8.onnx غير صحيح." }
            check(tokens.length() == 315_894L && sha256(tokens) == SenseVoiceSmallManifest.files[1].sha256) { "ملف tokens.txt غير صحيح." }
            deleteAll()
            ensureDirectory()
            model.copyTo(modelFile, overwrite = true)
            tokens.copyTo(tokensFile, overwrite = true)
            markVerified()
        } finally {
            staging.deleteRecursively()
        }
    }

    fun markVerified() { ensureDirectory(); marker.writeText("sensevoice-small-int8-v1\n") }
    fun clearMarker() { marker.delete() }

    fun exportBundle(destinationUri: String) {
        check(quickInstalled() || verifyInstalled()) { "نموذج SenseVoice غير مثبت أو غير صالح." }
        context.contentResolver.openOutputStream(Uri.parse(destinationUri), "w")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("model.int8.onnx"))
                modelFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("tokens.txt"))
                tokensFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        } ?: error("تعذر فتح وجهة تصدير نموذج SenseVoice.")
    }

    fun deleteAll() { if (modelDir.exists()) modelDir.deleteRecursively() }
    internal fun target(file: SenseVoiceModelFile): File = File(ensureDirectory(), file.name)
    internal fun partial(file: SenseVoiceModelFile): File = File(ensureDirectory(), file.name + ".tmp")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
