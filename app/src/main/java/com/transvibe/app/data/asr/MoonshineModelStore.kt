package com.transvibe.app.data.asr

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.net.Uri

internal data class MoonshineModelFile(
    val name: String,
    val url: String,
    val expectedBytes: Long?,
    val sha256: String?
)

internal object MoonshineTinyEnglishManifest {
    private const val ROOT = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/main"

    val files = listOf(
        MoonshineModelFile(
            name = "encoder_model.ort",
            url = "$ROOT/encoder_model.ort?download=true",
            expectedBytes = 13_281_600L,
            sha256 = "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"
        ),
        MoonshineModelFile(
            name = "decoder_model_merged.ort",
            url = "$ROOT/decoder_model_merged.ort?download=true",
            expectedBytes = 30_412_256L,
            sha256 = "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"
        ),
        MoonshineModelFile(
            name = "tokens.txt",
            url = "$ROOT/tokens.txt?download=true",
            expectedBytes = null,
            sha256 = null
        )
    )
}

class MoonshineModelStore(private val context: Context) {
    private val modelDir = File(context.filesDir, "models/moonshine/tiny-en-v2")
    private val marker = File(modelDir, ".verified-v2")

    val encoderFile: File get() = File(modelDir, "encoder_model.ort")
    val decoderFile: File get() = File(modelDir, "decoder_model_merged.ort")
    val tokensFile: File get() = File(modelDir, "tokens.txt")

    fun ensureDirectory(): File = modelDir.apply { mkdirs() }

    fun quickInstalled(): Boolean = runCatching {
        marker.isFile &&
            encoderFile.isFile && encoderFile.length() == 13_281_600L &&
            decoderFile.isFile && decoderFile.length() == 30_412_256L &&
            MoonshineTokenIntegrity.plausible(tokensFile) && installedTokenFingerprintMatches()
    }.getOrDefault(false)

    fun verifyInstalled(): Boolean = runCatching {
        if (!encoderFile.isFile || encoderFile.length() != 13_281_600L) return false
        if (!decoderFile.isFile || decoderFile.length() != 30_412_256L) return false
        if (!MoonshineTokenIntegrity.plausible(tokensFile) || !installedTokenFingerprintMatches()) return false
        if (sha256(encoderFile) != "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221") return false
        if (sha256(decoderFile) != "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0") return false
        writeTokenFingerprintMarker()
        true
    }.getOrDefault(false)

    fun markVerified() {
        ensureDirectory()
        writeTokenFingerprintMarker()
    }

    fun clearMarker() { marker.delete() }

    // Legacy markers are accepted, then upgraded to include a local token digest
    // by verifyInstalled(). The digest detects subsequent changes, but a trusted
    // upstream checksum is still required for publisher authenticity.
    private fun installedTokenFingerprintMatches(): Boolean {
        if (!marker.isFile) return true
        val lines = runCatching { marker.readLines() }.getOrNull() ?: return false
        val pinned = lines.firstOrNull { it.startsWith("tokens_sha256=") }
            ?.substringAfter('=') ?: return true
        return MoonshineTokenIntegrity.matchesRecorded(tokensFile, pinned)
    }

    private fun writeTokenFingerprintMarker() {
        ensureDirectory()
        val digest = MoonshineTokenIntegrity.sha256(tokensFile)
        marker.writeText("moonshine-tiny-en-v2\ntokens_sha256=$digest\n")
    }

    fun importBundle(sourceUri: String) {
        val staging = File(context.cacheDir, "moonshine-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        if (!entry.isDirectory && safeName in setOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt")) {
                            File(staging, safeName).outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("تعذر فتح ملف Moonshine من ذاكرة الهاتف.")
            val encoder = File(staging, "encoder_model.ort")
            val decoder = File(staging, "decoder_model_merged.ort")
            val tokens = File(staging, "tokens.txt")
            check(encoder.length() == 13_281_600L && sha256(encoder) == "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221") { "ملف encoder_model.ort غير صحيح." }
            check(decoder.length() == 30_412_256L && sha256(decoder) == "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0") { "ملف decoder_model_merged.ort غير صحيح." }
            check(MoonshineTokenIntegrity.plausible(tokens)) { "ملف tokens.txt غير صحيح." }
            deleteAll()
            ensureDirectory()
            encoder.copyTo(encoderFile, overwrite = true)
            decoder.copyTo(decoderFile, overwrite = true)
            tokens.copyTo(tokensFile, overwrite = true)
        } finally {
            staging.deleteRecursively()
        }
    }


    fun exportBundle(destinationUri: String) {
        check(quickInstalled() || verifyInstalled()) { "نموذج Moonshine غير مثبت أو غير صالح." }
        context.contentResolver.openOutputStream(Uri.parse(destinationUri), "w")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("encoder_model.ort"))
                encoderFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("decoder_model_merged.ort"))
                decoderFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("tokens.txt"))
                tokensFile.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        } ?: error("تعذر فتح وجهة تصدير نموذج Moonshine.")
    }

    fun deleteAll() {
        if (modelDir.exists()) modelDir.deleteRecursively()
    }

    internal fun target(file: MoonshineModelFile): File = File(ensureDirectory(), file.name)
    internal fun partial(file: MoonshineModelFile): File = File(ensureDirectory(), file.name + ".tmp")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
