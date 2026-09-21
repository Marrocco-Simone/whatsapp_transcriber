package dev.simone.watranscriber.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val WHISPER_SAMPLE_RATE = 16000

private const val DEQUEUE_TIMEOUT_US = 10_000L

object AudioDecoder {

    /** Decodes any WhatsApp audio file into the mono 16 kHz float samples whisper expects. */
    fun decode(path: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            error("no audio track in $path")
        }

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                            val chunk = ByteArray(bufferInfo.size)
                            buffer.position(bufferInfo.offset)
                            buffer.get(chunk)
                            pcm.write(chunk)
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        return resample(toMonoFloat(pcm.toByteArray(), channels), sampleRate)
    }

    fun durationMillis(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (error: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun toMonoFloat(bytes: ByteArray, channels: Int): FloatArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.limit() / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += shorts.get(frame * channels + channel) / 32768f
            }
            mono[frame] = sum / channels
        }
        return mono
    }

    private fun resample(samples: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate == WHISPER_SAMPLE_RATE || samples.isEmpty()) return samples
        val ratio = sampleRate.toDouble() / WHISPER_SAMPLE_RATE
        val outputSize = (samples.size / ratio).toInt()
        val output = FloatArray(outputSize)
        for (index in 0 until outputSize) {
            val position = index * ratio
            val left = position.toInt()
            val right = minOf(left + 1, samples.size - 1)
            val fraction = (position - left).toFloat()
            output[index] = samples[left] * (1 - fraction) + samples[right] * fraction
        }
        return output
    }
}
