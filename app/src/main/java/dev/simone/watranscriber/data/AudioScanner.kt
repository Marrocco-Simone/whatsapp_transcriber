package dev.simone.watranscriber.data

import android.os.Environment
import java.io.File

private val MEDIA_ROOTS = listOf(
    "Android/media/com.whatsapp/WhatsApp/Media",
    "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
    "WhatsApp/Media",
)
private val MEDIA_FOLDERS = listOf("WhatsApp Voice Notes", "WhatsApp Audio")
private val AUDIO_EXTENSIONS = setOf("opus", "ogg", "mp3", "m4a", "aac", "wav", "amr", "mpga")

data class AudioFile(val path: String, val name: String, val timeMillis: Long)

object AudioScanner {

    fun scan(): List<AudioFile> {
        val external = Environment.getExternalStorageDirectory()
        return MEDIA_ROOTS
            .flatMap { root -> MEDIA_FOLDERS.map { folder -> File(external, "$root/$folder") } }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter(::isAudio).toList() }
            .map { AudioFile(it.absolutePath, it.name, it.lastModified()) }
            .sortedByDescending { it.timeMillis }
    }

    private fun isAudio(file: File): Boolean =
        file.isFile && file.length() > 0 && file.extension.lowercase() in AUDIO_EXTENSIONS
}
