package dev.simone.watranscriber.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val RATE = 16_000
private const val WINDOW = 30 * RATE
private const val SEARCH = 5 * RATE
private const val FRAME = RATE / 10

/**
 * Holds the Whistle model of the needle engine. The engine keeps one model for the whole
 * process and has no call that frees it.
 */
object Whistle {

    init {
        System.loadLibrary("watranscriber")
    }

    private val mutex = Mutex()
    private var loaded: String? = null

    /**
     * [language] is "it" or "en", or null to detect it. [onProgress] reports 0 to 100 as
     * each window of at most 30 s finishes.
     */
    suspend fun transcribe(
        model: File,
        samples: FloatArray,
        language: String?,
        onProgress: (Int) -> Unit,
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            loadLocked(model)
            if (samples.isEmpty()) return@withContext ""
            val windows = windows(samples)
            windows.mapIndexed { index, window ->
                val json = String(nativeTranscribe(window, language))
                onProgress(100 * (index + 1) / windows.size)
                JSONObject(json).getString("text").trim()
            }.filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    suspend fun load(model: File) = mutex.withLock {
        withContext(Dispatchers.Default) { loadLocked(model) }
    }

    private fun loadLocked(model: File) {
        if (loaded != model.absolutePath) {
            nativeLoad(model.absolutePath)
            loaded = model.absolutePath
        }
    }

    /** Whistle reads at most 30 s, so each window ends at the quietest 100 ms of its last 5 s. */
    private fun windows(samples: FloatArray): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        var start = 0
        while (samples.size - start > WINDOW) {
            val from = start + WINDOW - SEARCH
            val quietest = (from until start + WINDOW - FRAME step FRAME / 2)
                .minBy { energy(samples, it) }
            val cut = quietest + FRAME / 2
            out += samples.copyOfRange(start, cut)
            start = cut
        }
        out += samples.copyOfRange(start, samples.size)
        return out
    }

    private fun energy(samples: FloatArray, from: Int): Double =
        (from until from + FRAME).sumOf { (samples[it] * samples[it]).toDouble() }

    private external fun nativeLoad(modelPath: String)
    private external fun nativeTranscribe(samples: FloatArray, language: String?): ByteArray
}
