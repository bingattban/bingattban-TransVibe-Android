package com.transvibe.app.data.asr

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Isolated storage for additional Moonshine variants.
 * Does not change the installed Tiny English v2 directory.
 * Only verified model files are committed from a staging directory.
 */
internal class AdditionalMoonshineModelStore(
    private val context: Context,
    private val modelId: String,
    private val manifest: List<MoonshineModelFile>
) {
    init {
        require(modelId.matches(Regex("[a-z0-9-]+"))) { "Invalid model ID" }
        require(manifest.size == 3 && manifest.map { it.name }.toSet() ==
            setOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"))
        require(manifest.all { spec ->
            spec.url.startsWith("https://") &&
                (spec.expectedBytes == null || spec.expectedBytes > 0L) &&
                (spec.sha256 == null || spec.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        }) { "Invalid model manifest" }
    }

    private val directory = File(context.filesDir, "models/moonshine/$modelId")
    private val tokenFingerprintFile = File(directory, ".tokens-sha256")
    private val maxImportBytes = 512L * 1024L * 1024L
    private val maxBundleBytes = 768L * 1024L * 1024L
    val encoderFile: File get() = File(directory, "encoder_model.ort")
    val decoderFile: File get() = File(directory, "decoder_model_merged.ort")
    val tokensFile: File get() = File(directory, "tokens.txt")

    // Cache expensive SHA-256 checks while the installed files remain unchanged.
    // File size and modification time are checked on every call; writes through
    // this store invalidate the cache explicitly after install or deletion.
    private var verifiedSignature: List<Pair<Long, Long>>? = null
    private fun signature(): List<Pair<Long, Long>> = manifest.map { spec ->
        val file = File(directory, spec.name)
        if (file.isFile) file.length() to file.lastModified() else -1L to -1L
    }

    @Synchronized
    fun isInstalled(): Boolean = runCatching {
        val current = signature()
        if (current == verifiedSignature && recordedTokenFingerprintMatches()) return@runCatching true
        val verified = manifest.all { spec -> verifyFile(File(directory, spec.name), spec) } &&
            recordedTokenFingerprintMatches()
        if (verified && !tokenFingerprintFile.isFile) {
            tokenFingerprintFile.writeText(MoonshineTokenIntegrity.sha256(tokensFile) + "\n")
        }
        verifiedSignature = if (verified) current else null
        verified
    }.getOrElse {
        verifiedSignature = null
        false
    }

    /** Downloads the verified model into an isolated staging directory. Call off the main thread. */
    @Synchronized
    fun downloadVerified(onProgress: (Long) -> Unit = {}) {
        check(directory.parentFile?.isDirectory == true || directory.parentFile?.mkdirs() == true) { "تعذر إنشاء مجلد النماذج." }
        val staging = File(directory.parentFile, ".download-${modelId}-${System.nanoTime()}")
        check(staging.mkdirs()) { "تعذر إنشاء مجلد مؤقت للنموذج." }
        try {
            var totalDownloaded = 0L
            manifest.forEach { spec ->
                val connection = java.net.URL(spec.url).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
                connection.getInputStream().buffered().use { input ->
                    File(staging, spec.name).outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("تم إلغاء تنزيل النموذج.")
                            total += n
                            totalDownloaded += n
                            onProgress(totalDownloaded)
                            check(totalDownloaded <= maxBundleBytes) { "إجمالي حجم النموذج يتجاوز الحد المسموح." }
                            check(total <= (spec.expectedBytes ?: maxImportBytes)) {
                                "حجم ملف النموذج يتجاوز الحد المسموح: ${spec.name}"
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                check(verifyFile(File(staging, spec.name), spec)) {
                    "فشل التحقق من ملف النموذج: ${spec.name}"
                }
            }
            installVerifiedStaging(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun importBundle(uri: String) {
        check(directory.parentFile?.isDirectory == true || directory.parentFile?.mkdirs() == true) { "تعذر إنشاء مجلد النماذج." }
        val staging = File(directory.parentFile, ".import-${modelId}-${System.nanoTime()}")
        val seen = mutableSetOf<String>()
        var totalUncompressed = 0L
        check(staging.mkdirs()) { "تعذر إنشاء مجلد مؤقت للنموذج." }
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        if (!entry.isDirectory && manifest.any { it.name == name }) {
                            check(seen.add(name)) { "ملف مكرر في حزمة النموذج: $name" }
                            val expected = manifest.first { it.name == name }.expectedBytes
                            var written = 0L
                            File(staging, name).outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("تم إلغاء استيراد النموذج.")
                                    written += n
                                    totalUncompressed += n
                                    check(totalUncompressed <= maxBundleBytes) { "حزمة النموذج تتجاوز الحد المسموح." }
                                    check(written <= (expected ?: maxImportBytes)) { "حجم ملف النموذج غير صالح: $name" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("تعذر فتح ملف النموذج.")
            check(seen.size == manifest.size && manifest.all { verifyFile(File(staging, it.name), it) }) {
                "ملفات النموذج غير مكتملة أو فشلت في التحقق."
            }
            // Verify everything before touching the installed directory.
            installVerifiedStaging(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Shared transactional install for both import and download. */
    private fun installVerifiedStaging(staging: File) {
        check(manifest.all { verifyFile(File(staging, it.name), it) }) { "ملفات النموذج غير صالحة." }
        val backup = File(directory.parentFile, ".backup-${modelId}-${System.nanoTime()}")
        val hadPrevious = directory.exists()
        if (hadPrevious) check(directory.renameTo(backup)) { "تعذر حفظ النموذج السابق." }
        try {
            check(staging.renameTo(directory)) { "تعذر تثبيت النموذج." }
            tokenFingerprintFile.writeText(MoonshineTokenIntegrity.sha256(tokensFile) + "\n")
            verifiedSignature = null
            if (backup.exists()) backup.deleteRecursively()
        } catch (failure: Throwable) {
            verifiedSignature = null
            if (directory.exists()) directory.deleteRecursively()
            if (hadPrevious && backup.exists()) {
                check(backup.renameTo(directory)) { "فشل استرجاع النموذج السابق بعد خطأ التثبيت." }
            }
            throw failure
        }
    }

    @Synchronized
    fun exportBundle(uri: String) {
        check(isInstalled()) { "النموذج غير مثبت أو غير مكتمل." }
        context.contentResolver.openOutputStream(Uri.parse(uri))?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                manifest.forEach { spec ->
                    zip.putNextEntry(java.util.zip.ZipEntry(spec.name))
                    File(directory, spec.name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("تعذر فتح وجهة التصدير.")
    }

    @Synchronized
    fun delete() {
        verifiedSignature = null
        check(!directory.exists() || directory.deleteRecursively()) { "تعذر حذف النموذج." }
    }

    private fun recordedTokenFingerprintMatches(): Boolean {
        if (!tokenFingerprintFile.isFile) return true // First verification of existing legacy install.
        val expected = runCatching { tokenFingerprintFile.readText().trim() }.getOrNull() ?: return false
        return MoonshineTokenIntegrity.matchesRecorded(tokensFile, expected)
    }

    private fun verifyFile(file: File, spec: MoonshineModelFile): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (spec.expectedBytes != null && file.length() != spec.expectedBytes) return false
        if (spec.sha256 == null) {
            return spec.name == "tokens.txt" && MoonshineTokenIntegrity.plausible(file)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
            .equals(spec.sha256, ignoreCase = true)
    }
}
