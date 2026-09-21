package dev.simone.watranscriber.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val LANGUAGE_AUTO = "auto"

/**
 * Holds one whisper.cpp context. The model needs about 600 MB of memory, so the context is
 * released as soon as the screen stops.
 */
object Whisper {

    init {
        System.loadLibrary("watranscriber")
    }

    private val mutex = Mutex()
    private var handle = 0L

    private val threads = minOf(4, Runtime.getRuntime().availableProcessors())

    suspend fun transcribe(model: File, samples: FloatArray): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (handle == 0L) {
                handle = nativeInit(model.absolutePath)
                require(handle != 0L) { "whisper could not load ${model.name}" }
            }
            String(nativeTranscribe(handle, samples, LANGUAGE_AUTO, threads)).trim()
        }
    }

    suspend fun release() = mutex.withLock {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
    ): ByteArray
    private external fun nativeFree(handle: Long)
}
