package dev.simone.watranscriber.whisper

import android.content.Context
import dev.simone.watranscriber.data.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object WhisperModel {

    fun file(context: Context, model: Model): File = File(context.filesDir, model.file)

    fun isReady(context: Context, model: Model): Boolean = file(context, model).length() > 0

    fun delete(context: Context, model: Model): Boolean = file(context, model).delete()

    /** Deletes every model file except the one of [keep], also files of models no longer offered. */
    fun deleteOthers(context: Context, keep: Model) {
        context.filesDir.listFiles { file ->
            val name = file.name.removeSuffix(".part")
            name != keep.file &&
                (name.startsWith("ggml-") && name.endsWith(".bin") || name.endsWith(".cact"))
        }?.forEach { it.delete() }
    }

    /** Downloads the model once. [onProgress] reports 0f to 1f, or -1f when the size is unknown. */
    suspend fun download(context: Context, model: Model, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val target = file(context, model)
            val partial = File(target.parentFile, "${model.file}.part")
            val connection = URL(model.url).openConnection() as HttpURLConnection
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
            require(partial.renameTo(target)) { "could not store ${model.file}" }
        }
    }
}
