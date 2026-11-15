package com.transvibe.app.data.download

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

internal data class ParsedContentRange(val start: Long, val end: Long, val total: Long?)

internal data class ResumeResponseDecision(
    val appendFromBytes: Long,
    val restartFromZero: Boolean,
    val expectedTotalBytes: Long?
)

/** Pure HTTP protocol rules for model transfer; intentionally testable without Android/network I/O. */
internal object ModelDownloadProtocol {
    fun parseContentRange(value: String?): ParsedContentRange? {
        if (value.isNullOrBlank()) return null
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start) return null
        return ParsedContentRange(start, end, total)
    }

    fun decideResume(existingBytes: Long, responseCode: Int, contentRange: String?, expectedBytes: Long): ResumeResponseDecision {
        if (existingBytes <= 0L) {
            return ResumeResponseDecision(0L, false, expectedBytes.takeIf { it > 0L })
        }
        if (responseCode != 206) {
            return ResumeResponseDecision(0L, true, expectedBytes.takeIf { it > 0L })
        }
        val range = parseContentRange(contentRange)
            ?: throw IOException("Invalid Content-Range for resumed model transfer")
        if (range.start != existingBytes) throw IOException("Content-Range start does not match partial file")
        if (range.total != null && expectedBytes > 0L && range.total != expectedBytes) {
            throw IOException("Content-Range total does not match model descriptor")
        }
        return ResumeResponseDecision(existingBytes, false, range.total ?: expectedBytes.takeIf { it > 0L })
    }

    fun resolveRedirect(current: URL, location: String): URL {
        require(location.isNotBlank()) { "Redirect location is empty" }
        val next = URL(current, location)
        if (!next.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("HTTPS downgrade rejected")
        }
        return next
    }

    fun isRecoverableTransportFailure(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException, is UnknownHostException, is EOFException -> true
        is HttpStatusFailure -> t.code == 408 || t.code == 425 || t.code == 429 || t.code in 500..599
        is IOException -> {
            val message = t.message.orEmpty()
            !message.contains("storage", ignoreCase = true) &&
                !message.contains("مساحة التخزين", ignoreCase = true) &&
                !message.contains("unexpected", ignoreCase = true) &&
                !message.contains("غير متوقع", ignoreCase = true) &&
                !message.contains("larger", ignoreCase = true) &&
                !message.contains("أكبر من حجم", ignoreCase = true)
        }
        else -> false
    }
}

internal class HttpStatusFailure(val code: Int) : IOException("HTTP $code")
