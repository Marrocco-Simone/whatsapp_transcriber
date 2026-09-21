package dev.simone.watranscriber.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
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

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val pcm = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
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
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
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
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        return resample(toMonoFloat(pcm.toByteArray(), channels, pcmEncoding), sampleRate)
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

    /** Most decoders return 16 bit samples, a few return floats. */
    private fun toMonoFloat(bytes: ByteArray, channels: Int, pcmEncoding: Int): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                downmix(floats.limit() / channels, channels) { floats.get(it) }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                downmix(shorts.limit() / channels, channels) { shorts.get(it) / 32768f }
            }
        }
    }

    /**
     * The Android opus decoder returns 48 kHz, so the mean of every three samples removes
     * most of the content above 8 kHz that would otherwise alias into the speech band.
     * limit: a box filter, not a windowed sinc. Replace it if the accuracy is not enough.
     */
    private fun decimate(samples: FloatArray, sampleRate: Int): FloatArray {
        val factor = sampleRate / WHISPER_SAMPLE_RATE
        val output = FloatArray(samples.size / factor)
        for (index in output.indices) {
            var sum = 0f
            for (offset in 0 until factor) {
                sum += samples[index * factor + offset]
            }
            output[index] = sum / factor
        }
        return output
    }

    private inline fun downmix(frames: Int, channels: Int, sample: (Int) -> Float): FloatArray {
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += sample(frame * channels + channel)
            }
            mono[frame] = sum / channels
        }
        return mono
    }

    private fun resample(samples: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate == WHISPER_SAMPLE_RATE || samples.isEmpty()) return samples
        if (sampleRate % WHISPER_SAMPLE_RATE == 0) return decimate(samples, sampleRate)
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
