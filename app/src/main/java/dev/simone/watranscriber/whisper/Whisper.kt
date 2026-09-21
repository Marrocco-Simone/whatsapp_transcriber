package dev.simone.watranscriber.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    @Volatile
    private var listener: ((Int) -> Unit)? = null

    private val threads = Runtime.getRuntime().availableProcessors()

    /**
     * [language] is a whisper code such as "it", or "auto", which costs a second encoder
     * pass. [onProgress] reports 0 to 100 as whisper finishes each segment of the audio.
     */
    suspend fun transcribe(
        model: File,
        samples: FloatArray,
        language: String,
        onProgress: (Int) -> Unit,
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            loadLocked(model)
            listener = onProgress
            try {
                String(nativeTranscribe(handle, samples, language, threads)).trim()
            } finally {
                listener = null
            }
        }
    }

    // whisper_jni.cpp calls this by name while whisper_full runs.
    private fun onProgress(percent: Int) {
        listener?.invoke(percent)
    }

    /** Reads the model into memory, so that the first transcription does not wait for it. */
    suspend fun load(model: File) = mutex.withLock {
        withContext(Dispatchers.Default) { loadLocked(model) }
    }

    private fun loadLocked(model: File) {
        if (handle == 0L) {
            handle = nativeInit(model.absolutePath)
            require(handle != 0L) { "whisper could not load ${model.name}" }
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
