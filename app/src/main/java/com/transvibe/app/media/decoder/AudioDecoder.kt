package com.transvibe.app.media.decoder

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.domain.runtime.AudioChunkingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

class AudioDecoder(private val context: Context) {
    data class AudioChunk(val samples: FloatArray, val startMs: Long, val endMs: Long)

    sealed class AudioPipelineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class OpenSource(cause: Throwable? = null) : AudioPipelineException("تعذر فتح ملف الفيديو.", cause)
        class NoAudioTrack : AudioPipelineException("الفيديو لا يحتوي على مسار صوتي.")
        class UnknownAudioFormat : AudioPipelineException("صيغة مسار الصوت غير معروفة.")
        class UnsupportedCodec(val mime: String, cause: Throwable? = null) :
            AudioPipelineException("ترميز الصوت غير مدعوم على هذا الجهاز: $mime", cause)
        class InvalidDecodedFormat(message: String) : AudioPipelineException(message)
        class UnsupportedPcm(val encoding: Int) : AudioPipelineException("صيغة PCM الناتجة غير مدعومة: $encoding")
        class DecodeFailure(cause: Throwable) : AudioPipelineException("تعذر فك ترميز مسار الصوت.", cause)
    }

    suspend fun decodeChunks(
        uri: Uri,
        chunkSeconds: Int = 30,
        overlapMs: Int = 500,
        chunkingPolicy: AudioChunkingPolicy? = null,
        startMs: Long = 0L,
        onProgress: suspend (processedUs: Long, totalUs: Long) -> Unit,
        onChunk: suspend (AudioChunk) -> Unit
    ) = withContext(AppDispatchers.io) {
        require(chunkSeconds in 10..60) { "طول مقطع الصوت غير صالح" }
        require(overlapMs in 0..2_000) { "قيمة تداخل المقاطع غير صالحة" }
        require(startMs >= 0L) { "بداية الاستئناف غير صالحة" }
        val decodeStartNs = System.nanoTime()
        var pcmConvertNanos = 0L
        var resampleNanos = 0L
        var firstChunkReadyMs: Long? = null
        var chunksDelivered = 0
        val effectiveChunking = chunkingPolicy ?: AudioChunkingPolicy(
            minimumSeconds = chunkSeconds,
            targetSeconds = chunkSeconds,
            maximumSeconds = chunkSeconds,
            overlapMs = overlapMs
        )

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val length = afd.length
                    if (length >= 0L) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, length)
                    else extractor.setDataSource(afd.fileDescriptor)
                } ?: throw AudioPipelineException.OpenSource()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: AudioPipelineException) {
                throw e
            } catch (t: Throwable) {
                throw AudioPipelineException.OpenSource(t)
            }

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioPipelineException.NoAudioTrack()

            extractor.selectTrack(track)
            val trackStartUs = extractor.sampleTime.takeIf { it >= 0L } ?: 0L
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw AudioPipelineException.UnknownAudioFormat()
            val requestedStartUs = startMs * 1_000L
            if (requestedStartUs > 0L) {
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val totalUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            } else -1L

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw AudioPipelineException.UnsupportedCodec(mime, t)
            }
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw AudioPipelineException.UnsupportedCodec(mime, t)
            }

            var sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)
            var pcmEncoding = inputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            validateDecodedFormat(sampleRate, channels)

            var resampler = StreamingSincResampler(TARGET_RATE)
            val minimumChunkSamples = effectiveChunking.minimumSeconds * TARGET_RATE
            val targetChunkSamples = effectiveChunking.targetSeconds * TARGET_RATE
            val maximumChunkSamples = effectiveChunking.maximumSeconds * TARGET_RATE
            val overlapSamples = effectiveChunking.overlapMs * TARGET_RATE / 1_000
            val boundaryWindowSamples = effectiveChunking.boundaryWindowMs * TARGET_RATE / 1_000
            val acc = FloatAccumulator(maximumChunkSamples + TARGET_RATE * 2)
            val boundarySelector = SilenceAwareChunkBoundarySelector()
            var accStartUs: Long? = null
            var emittedFullChunkInRun = false
            var expectedNextInputPtsUs: Long? = null
            var inputDone = false
            var outputDone = false
            var lastPts = trackStartUs
            val mediaEndUs = if (totalUs > 0L) trackStartUs + totalUs else -1L
            val info = MediaCodec.BufferInfo()

            suspend fun deliver(chunk: AudioChunk) {
                if (firstChunkReadyMs == null) {
                    firstChunkReadyMs = (System.nanoTime() - decodeStartNs) / 1_000_000L
                }
                try {
                    onChunk(chunk)
                    chunksDelivered++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    throw CallbackFailure(t)
                }
            }

            suspend fun emitReadyChunks() {
                while (acc.size >= minimumChunkSamples) {
                    coroutineContext.ensureActive()
                    val startUs = accStartUs ?: break
                    val boundary = boundarySelector.select(
                        sampleCount = acc.size,
                        minimumSamples = minimumChunkSamples,
                        targetSamples = targetChunkSamples,
                        maximumSamples = maximumChunkSamples,
                        boundaryWindowSamples = boundaryWindowSamples,
                        sampleRateHz = TARGET_RATE,
                        force = acc.size >= maximumChunkSamples,
                        rms = acc::rms
                    ) ?: break
                    val samples = acc.copyPrefix(boundary)
                    deliver(AudioChunk(samples, usToMs(startUs), usToMs(startUs + samplesToUs(samples.size.toLong()))))
                    val advanceSamples = max(1, boundary - overlapSamples)
                    acc.dropPrefix(advanceSamples)
                    accStartUs = startUs + samplesToUs(advanceSamples.toLong())
                    emittedFullChunkInRun = true
                }
            }

            suspend fun flushContinuousRun() {
                val resampleFlushNs = System.nanoTime()
                val tail = resampler.finish()
                resampleNanos += System.nanoTime() - resampleFlushNs
                if (tail.isNotEmpty()) acc.append(tail)
                emitReadyChunks()
                if (acc.size > 0 && (!emittedFullChunkInRun || acc.size > overlapSamples)) {
                    val startUs = accStartUs ?: 0L
                    val remaining = acc.copyPrefix(acc.size)
                    deliver(AudioChunk(remaining, usToMs(startUs), usToMs(startUs + samplesToUs(remaining.size.toLong()))))
                }
                acc.dropPrefix(acc.size)
                accStartUs = null
                emittedFullChunkInRun = false
                expectedNextInputPtsUs = null
                resampler = StreamingSincResampler(TARGET_RATE)
            }

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw AudioPipelineException.DecodeFailure(IllegalStateException("MediaCodec input buffer unavailable"))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        validateDecodedFormat(sampleRate, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(outputIndex)
                                    ?: throw AudioPipelineException.DecodeFailure(IllegalStateException("MediaCodec output buffer unavailable"))
                                val pcm = output.sliceRange(info.offset, info.size)
                                val pcmStartedNs = System.nanoTime()
                                val decodedMono = decodePcmToMonoFloat(pcm, pcmEncoding, channels)
                                pcmConvertNanos += System.nanoTime() - pcmStartedNs
                                val rawPtsUs = info.presentationTimeUs.coerceAtLeast(0L)
                                val rawEndUs = rawPtsUs + sourceFramesToUs(decodedMono.size.toLong(), sampleRate)

                                if (decodedMono.isNotEmpty() && (requestedStartUs == 0L || rawEndUs > requestedStartUs)) {
                                    val trimFrames = if (rawPtsUs < requestedStartUs) {
                                        (((requestedStartUs - rawPtsUs) * sampleRate + 999_999L) / 1_000_000L)
                                            .coerceIn(0L, decodedMono.size.toLong()).toInt()
                                    } else 0
                                    val mono = if (trimFrames == 0) decodedMono else decodedMono.copyOfRange(trimFrames, decodedMono.size)
                                    val ptsUs = rawPtsUs + sourceFramesToUs(trimFrames.toLong(), sampleRate)

                                    if (mono.isNotEmpty()) {
                                        val expected = expectedNextInputPtsUs
                                        if (expected != null && abs(ptsUs - expected) > PTS_DISCONTINUITY_TOLERANCE_US) {
                                            flushContinuousRun()
                                        }
                                        if (accStartUs == null) accStartUs = ptsUs
                                        val resampleStartedNs = System.nanoTime()
                                        val converted = resampler.process(mono, sampleRate)
                                        resampleNanos += System.nanoTime() - resampleStartedNs
                                        acc.append(converted)
                                        emitReadyChunks()
                                        expectedNextInputPtsUs = ptsUs + sourceFramesToUs(mono.size.toLong(), sampleRate)
                                        lastPts = max(lastPts, expectedNextInputPtsUs ?: ptsUs)
                                        try {
                                            onProgress(lastPts, mediaEndUs)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (t: Throwable) {
                                            throw CallbackFailure(t)
                                        }
                                    }
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            flushContinuousRun()
            AppLogger.d(
                "AudioDecodeTiming",
                "engine=mediacodec source_hz=$sampleRate channels=$channels " +
                    "first_chunk_ms=${firstChunkReadyMs ?: -1L} " +
                    "pcm_conversion_ms=${pcmConvertNanos / 1_000_000L} " +
                    "resample_ms=${resampleNanos / 1_000_000L} " +
                    "elapsed_ms=${(System.nanoTime() - decodeStartNs) / 1_000_000L} " +
                    "chunks=$chunksDelivered resampler=polyphase4096 " +
                    "note=elapsed_includes_chunk_consumer_backpressure"
            )
            try {
                onProgress(if (mediaEndUs > 0L) mediaEndUs else lastPts, mediaEndUs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw CallbackFailure(t)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: CallbackFailure) {
            throw (e.cause ?: e)
        } catch (e: AudioPipelineException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            throw AudioPipelineException.DecodeFailure(e)
        } catch (t: Throwable) {
            throw AudioPipelineException.DecodeFailure(t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun validateDecodedFormat(sampleRate: Int, channels: Int) {
        if (sampleRate !in 8_000..384_000) {
            throw AudioPipelineException.InvalidDecodedFormat("معدل عينة الصوت غير صالح: $sampleRate Hz")
        }
        if (channels !in 1..8) {
            throw AudioPipelineException.InvalidDecodedFormat("عدد قنوات الصوت غير مدعوم: $channels")
        }
    }

    private fun decodePcmToMonoFloat(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            PCM_24_BIT_PACKED -> 3
            PCM_32_BIT -> 4
            else -> throw AudioPipelineException.UnsupportedPcm(encoding)
        }
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        repeat(frames) { frame ->
            var mixed = 0.0
            repeat(channels) {
                mixed += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128.0
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768.0
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble().takeIf { it.isFinite() } ?: 0.0
                    PCM_24_BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt()
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        value / 8_388_608.0
                    }
                    PCM_32_BIT -> buffer.int / 2_147_483_648.0
                    else -> 0.0
                }
            }
            out[frame] = (mixed / channels.toDouble()).coerceIn(-1.0, 1.0).toFloat()
        }
        return out
    }

    private fun samplesToUs(samples: Long): Long = samples * 1_000_000L / TARGET_RATE
    private fun sourceFramesToUs(frames: Long, sampleRate: Int): Long = frames * 1_000_000L / sampleRate.toLong()
    private fun usToMs(us: Long): Long = us.coerceAtLeast(0L) / 1_000L

    private fun ByteBuffer.sliceRange(offset: Int, size: Int): ByteBuffer {
        val dup = duplicate()
        dup.position(offset.coerceAtLeast(0))
        dup.limit((offset + size).coerceAtMost(capacity()))
        return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class CallbackFailure(cause: Throwable) : Exception(cause)

    companion object {
        private const val TARGET_RATE = 16_000
        private const val CODEC_TIMEOUT_US = 25_000L
        // Larger than normal AAC/Opus packet timestamp jitter; actual gaps beyond this split the media timeline.
        private const val PTS_DISCONTINUITY_TOLERANCE_US = 50_000L
        // Stable numeric values from android.media.AudioFormat; kept local so the pipeline remains compatible with minSdk 26.
        private const val PCM_24_BIT_PACKED = 0x15
        private const val PCM_32_BIT = 0x16
    }
}

/**
 * Streaming band-limited resampler for speech. It keeps only a small rolling source window,
 * applies a Hann-windowed sinc low-pass kernel, and therefore does not grow with media duration.
 */
private class FloatAccumulator(initialCapacity: Int) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
    private var head = 0
    var size: Int = 0
        private set

    fun append(values: FloatArray) {
        if (values.isEmpty()) return
        ensureTailCapacity(values.size)
        values.copyInto(data, head + size)
        size += values.size
    }

    fun copyPrefix(count: Int): FloatArray {
        val n = count.coerceIn(0, size)
        return data.copyOfRange(head, head + n)
    }

    fun dropPrefix(count: Int) {
        val n = count.coerceIn(0, size)
        if (n <= 0) return
        head += n
        size -= n
        if (size == 0) head = 0
    }

    fun rms(start: Int, end: Int): Double {
        val safeStart = start.coerceIn(0, size)
        val safeEnd = end.coerceIn(safeStart, size)
        if (safeEnd <= safeStart) return 0.0
        var sum = 0.0
        for (relative in safeStart until safeEnd) {
            val v = data[head + relative].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / (safeEnd - safeStart))
    }

    private fun ensureTailCapacity(additional: Int) {
        val requiredEnd = head + size + additional
        if (requiredEnd <= data.size) return

        // Reuse consumed prefix before growing. This avoids shifting the retained tail after every chunk.
        if (size + additional <= data.size) {
            data.copyInto(data, 0, head, head + size)
            head = 0
            return
        }

        val required = size + additional
        val grown = FloatArray(max(required, data.size * 2))
        data.copyInto(grown, 0, head, head + size)
        data = grown
        head = 0
    }
}

