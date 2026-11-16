package com.transvibe.app.data.media

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmWavChunkReaderTest {
    @Test fun `PCM reader emits bounded timestamped chunks`() = runBlocking {
        val wav = File.createTempFile("transvibe", ".wav")
        try {
            // 25 seconds of silence; include JUNK chunk to validate real RIFF parsing.
            writeWav(wav, frames = 25 * 16000, withJunk = true)
            val bounds = mutableListOf<Pair<Long, Long>>()
            val progress = mutableListOf<Long>()
            PcmWavChunkReader().decodeChunks(wav, chunkSeconds = 10, overlapMs = 0,
                onProgress = { read, _ -> progress += read },
                onChunk = { bounds += it.startMs to it.endMs; assertTrue(it.samples.size <= 10 * 16000) })
            assertEquals(listOf(0L to 10000L, 10000L to 20000L, 20000L to 25000L), bounds)
            assertEquals(25_000_000L, progress.last())
        } finally { wav.delete() }
    }

    @Test fun `seek respects resume timestamp without dropping output`() = runBlocking {
        val wav = File.createTempFile("transvibe", ".wav")
        try {
            writeWav(wav, frames = 25 * 16000, withJunk = false)
            val starts = mutableListOf<Long>()
            PcmWavChunkReader().decodeChunks(wav, 10, 500, startMs = 12_000,
                onProgress = { _, _ -> }, onChunk = { starts += it.startMs })
            assertEquals(12_000L, starts.first())
            assertTrue(starts.size >= 2)
        } finally { wav.delete() }
    }

    private fun writeWav(file: File, frames: Int, withJunk: Boolean) {
        val size = frames * 2
        RandomAccessFile(file, "rw").use { out ->
            fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun writeU16(v: Int) { out.writeByte(v); out.writeByte(v shr 8) }
            fun writeU32(v: Int) { writeU16(v); writeU16(v shr 16) }
            writeString("RIFF"); writeU32(36 + size + if (withJunk) 12 else 0); writeString("WAVE")
            if (withJunk) { writeString("JUNK"); writeU32(4); writeU32(0) }
            writeString("fmt "); writeU32(16)
            writeU16(1); writeU16(1); writeU32(16000); writeU32(32000); writeU16(2); writeU16(16)
            writeString("data"); writeU32(size)
            out.write(ByteArray(size))
        }
    }
}
