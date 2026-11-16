package com.transvibe.app.data.asr

import java.io.File
import java.security.MessageDigest

/**
 * Defensive validation for token files without a publisher-pinned SHA-256.
 * This is NOT publisher authenticity: a pinned upstream checksum is still needed.
 */
internal object MoonshineTokenIntegrity {
    private const val MIN_SIZE = 100_001L
    private const val MAX_SIZE = 2L * 1024L * 1024L

    fun plausible(file: File): Boolean {
        if (!file.isFile || file.length() !in MIN_SIZE..MAX_SIZE) return false
        val prefix = ByteArray(8192)
        val count = file.inputStream().use { it.read(prefix) }
        if (count < 1024 || prefix.take(count).any { it.toInt() == 0 }) return false
        val text = String(prefix, 0, count, Charsets.UTF_8)
        val head = text.trimStart().take(24).lowercase()
        if (head.startsWith("<html") || head.startsWith("<!doctype") ||
            head.startsWith("<?xml") || head.startsWith("{")) return false
        return text.count { it == '\n' } >= 10
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matchesRecorded(file: File, expectedSha256: String): Boolean =
        expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) &&
            sha256(file).equals(expectedSha256, ignoreCase = true)
}
