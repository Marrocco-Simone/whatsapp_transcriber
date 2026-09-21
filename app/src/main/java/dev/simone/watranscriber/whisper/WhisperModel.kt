package dev.simone.watranscriber.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_FILE = "ggml-large-v3-turbo-q5_0.bin"
private const val MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE"

object WhisperModel {

    fun file(context: Context): File = File(context.filesDir, MODEL_FILE)

    fun isReady(context: Context): Boolean = file(context).length() > 0

    fun delete(context: Context): Boolean = file(context).delete()

    /** Downloads the model once. [onProgress] reports 0f to 1f, or -1f when the size is unknown. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val target = file(context)
            val partial = File(target.parentFile, "$MODEL_FILE.part")
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            try {
                connection.connect()
                require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "the server answered HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input ->
                    val total = connection.contentLengthLong
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(if (total > 0) written.toFloat() / total else -1f)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            require(partial.renameTo(target)) { "could not store $MODEL_FILE" }
        }
    }
}
