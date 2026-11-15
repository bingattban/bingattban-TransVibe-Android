package com.transvibe.app.media.decoder

import android.net.Uri
import android.test.InstrumentationTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

@Suppress("DEPRECATION")
class AudioDecoderTimelineInstrumentationTest : InstrumentationTestCase() {

    fun testAac44100MonoPreservesNonZeroMediaStartPts() = runBlocking {
        val file = copyFixture("audio_timeline/aac_44100_mono_offset.m4a")
        val chunks = decode(file)
        assertTrue("Expected decoded audio", chunks.isNotEmpty())
        // ffprobe fixture clock starts at 1.226009 s. Allow codec priming/rounding tolerance only.
        assertTrue("Expected media-clock start near 1226 ms but was ${chunks.first().startMs}", chunks.first().startMs in 1_180L..1_280L)
    }

    fun testAac48000StereoSplitsTimestampDiscontinuity() = runBlocking {
        val file = copyFixture("audio_timeline/aac_48000_stereo_gap.m4a")
        val chunks = decode(file)
        assertTrue("PTS discontinuity should produce separate continuous runs", chunks.size >= 2)
        assertTrue("First run should begin near media zero", chunks.first().startMs <= 80L)
        assertTrue(
            "Second run should retain the ~700 ms media timestamp gap, got ${chunks[1].startMs}",
            chunks[1].startMs >= 1_600L
        )
    }

    fun testOpus48000StereoPreservesOffsetWhenDecoderAvailable() = runBlocking {
        val file = copyFixture("audio_timeline/opus_48000_stereo_offset.ogg")
        val chunks = try {
            decode(file)
        } catch (_: AudioDecoder.AudioPipelineException.UnsupportedCodec) {
            return@runBlocking // Opus is explicitly "where supported".
        }
        assertTrue("Expected decoded Opus audio", chunks.isNotEmpty())
        assertTrue("Expected Opus media start near 400 ms but was ${chunks.first().startMs}", chunks.first().startMs in 330L..470L)
    }


    fun testGeneratedWav44100MonoDecodesTo16kTimeline() = runBlocking {
        val file = createPcmWav("wav_44100_mono.wav", 44_100, 1, 2_000, silence = false)
        val chunks = decode(file)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().samples.size in 31_000..33_000)
        assertTrue(chunks.first().startMs in 0L..50L)
        assertTrue(chunks.last().endMs in 1_900L..2_100L)
    }

    fun testGeneratedWav48000StereoDecodesToMono16k() = runBlocking {
        val file = createPcmWav("wav_48000_stereo.wav", 48_000, 2, 2_000, silence = false)
        val chunks = decode(file)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().samples.size in 31_000..33_000)
        assertTrue(chunks.first().samples.any { kotlin.math.abs(it) > 0.01f })
    }

    fun testSilenceRemainsSilence() = runBlocking {
        val file = createPcmWav("wav_silence.wav", 48_000, 1, 1_500, silence = true)
        val chunks = decode(file)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.flatMap { it.samples.asIterable() }.all { kotlin.math.abs(it) < 0.0001f })
    }

    fun testLongAudioIsChunkedWithoutTimelineCollapse() = runBlocking {
        val file = createPcmWav("wav_long.wav", 16_000, 1, 25_000, silence = false)
        val chunks = decode(file)
        assertTrue("Expected multiple bounded chunks", chunks.size >= 3)
        assertTrue(chunks.zipWithNext().all { (a, b) -> b.startMs >= a.startMs })
        assertTrue(chunks.last().endMs >= 24_500L)
    }

    private fun createPcmWav(name: String, sampleRate: Int, channels: Int, durationMs: Int, silence: Boolean): File {
        val file = File(instrumentation.targetContext.cacheDir, name)
        val frames = sampleRate * durationMs / 1000
        val dataBytes = frames * channels * 2
        file.outputStream().buffered().use { out ->
            fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
            fun le32(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff); out.write((v ushr 16) and 0xff); out.write((v ushr 24) and 0xff) }
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + dataBytes); out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(16); le16(1); le16(channels); le32(sampleRate)
            le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
            out.write("data".toByteArray(Charsets.US_ASCII)); le32(dataBytes)
            repeat(frames) { frame ->
                val value = if (silence) 0 else (kotlin.math.sin(2.0 * Math.PI * 440.0 * frame / sampleRate) * 12_000).toInt()
                repeat(channels) { le16(value and 0xffff) }
            }
        }
        return file
    }

    private suspend fun decode(file: File): List<AudioDecoder.AudioChunk> {
        val chunks = mutableListOf<AudioDecoder.AudioChunk>()
        AudioDecoder(instrumentation.targetContext).decodeChunks(
            uri = Uri.fromFile(file),
            chunkSeconds = 10,
            overlapMs = 0,
            onProgress = { _, _ -> },
            onChunk = { chunks += it }
        )
        return chunks
    }

    private fun copyFixture(assetPath: String): File {
        val file = File(instrumentation.targetContext.cacheDir, assetPath.substringAfterLast('/'))
        instrumentation.context.assets.open(assetPath).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
}
