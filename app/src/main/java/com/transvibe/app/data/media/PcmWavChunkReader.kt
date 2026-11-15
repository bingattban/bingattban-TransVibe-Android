package com.transvibe.app.data.media

import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.media.decoder.AudioDecoder
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Reads FFmpeg's 16 kHz, mono, signed-16-bit RIFF/WAV output without loading it all into RAM. */
internal class PcmWavChunkReader {
    suspend fun decodeChunks(
        wav: File,
        chunkSeconds: Int,
        overlapMs: Int,
        startMs: Long = 0,
        onProgress: suspend (processedUs: Long, totalUs: Long) -> Unit,
        onChunk: suspend (AudioDecoder.AudioChunk) -> Unit
    ) = withContext(AppDispatchers.io) {
        require(chunkSeconds in 10..60 && overlapMs in 0..2000 && startMs >= 0L)
        RandomAccessFile(wav, "r").use { file ->
            val (dataOffset, dataLength) = findPcmData(file)
            val totalSamples = dataLength / 2L
            val totalUs = totalSamples * 1_000_000L / SAMPLE_RATE
            var startSample = (startMs * SAMPLE_RATE / 1_000L).coerceIn(0L, totalSamples)
            val maxSamples = chunkSeconds * SAMPLE_RATE
            val overlapSamples = overlapMs * SAMPLE_RATE / 1000
            val buffer = ByteArray(maxSamples * 2)
            onProgress(startSample * 1_000_000L / SAMPLE_RATE, totalUs)
            while (startSample < totalSamples) {
                coroutineContext.ensureActive()
                val count = minOf(maxSamples.toLong(), totalSamples - startSample).toInt()
                file.seek(dataOffset + startSample * 2L)
                file.readFully(buffer, 0, count * 2)
                val samples = FloatArray(count)
                for (index in 0 until count) {
                    val lo = buffer[index * 2].toInt() and 0xff
                    val hi = buffer[index * 2 + 1].toInt()
                    samples[index] = ((hi shl 8) or lo).toShort().toInt() / 32768f
                }
                val endSample = startSample + count
                onChunk(AudioDecoder.AudioChunk(samples, startSample * 1_000L / SAMPLE_RATE, endSample * 1_000L / SAMPLE_RATE))
                onProgress(endSample * 1_000_000L / SAMPLE_RATE, totalUs)
                if (endSample == totalSamples) break
                startSample += (count - overlapSamples).coerceAtLeast(1)
            }
        }
    }

    /** RIFF may have LIST/JUNK and variable-length fmt chunks ahead of data. */
    private fun findPcmData(file: RandomAccessFile): Pair<Long, Long> {
        require(file.length() >= 44L && readFourCc(file) == "RIFF") { "ملف WAV ليس بتنسيق RIFF." }
        file.skipBytes(4) // RIFF size
        require(readFourCc(file) == "WAVE") { "ملف WAV غير صالح." }
        var formatOk = false
        var dataOffset = -1L
        var dataLength = 0L
        while (file.filePointer + 8 <= file.length()) {
            val id = readFourCc(file)
            val size = readUInt32(file)
            val start = file.filePointer
            val next = (start + size + (size and 1L)).coerceAtMost(file.length())
            require(next >= start) { "طول مقطع WAV غير صالح." }
            if (id == "fmt ") {
                require(size >= 16L && start + 16L <= file.length()) { "ترويسة WAV ناقصة." }
                val format = readUInt16(file)
                val channels = readUInt16(file)
                val rate = readUInt32(file)
                file.skipBytes(6) // byte rate (4) and block align (2)
                val bits = readUInt16(file)
                formatOk = format == 1 && channels == 1 && rate == SAMPLE_RATE.toLong() && bits == 16
            } else if (id == "data") {
                dataOffset = start
                dataLength = minOf(size, file.length() - start)
            }
            if (formatOk && dataOffset >= 0L) break
            file.seek(next)
        }
        require(formatOk && dataOffset >= 0L && dataLength >= 2L) {
            "صيغة WAV يجب أن تكون PCM ‏16-bit / Mono / 16kHz."
        }
        return dataOffset to dataLength
    }

    private fun readFourCc(f: RandomAccessFile) = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
    private fun readUInt16(f: RandomAccessFile): Int {
        val low = f.readUnsignedByte(); val high = f.readUnsignedByte()
        return low or (high shl 8)
    }
    private fun readUInt32(f: RandomAccessFile): Long =
        (readUInt16(f).toLong() or (readUInt16(f).toLong() shl 16))

    private companion object { const val SAMPLE_RATE = 16_000 }
}
