package dev.simone.watranscriber.data

import android.content.Context

enum class Language(val code: String, val label: String) {
    ITALIAN("it", "Italian"),
    ENGLISH("en", "English"),
    AUTO("auto", "Detect the language"),
}

private const val WHISPER_CPP = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

enum class Model(val file: String, val label: String, val size: String, val url: String) {
    TURBO(
        "ggml-large-v3-turbo-q5_0.bin", "large-v3-turbo", "570 MB",
        "$WHISPER_CPP/ggml-large-v3-turbo-q5_0.bin",
    ),
    WHISTLE(
        "whistle.cact", "Whistle", "17 MB",
        "https://huggingface.co/Cactus-Compute/whistle/resolve/" +
            "b358ddadd89b7a713b5aa131f23032d3cca1b251/whistle.cact",
    ),
}

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var language: Language
        get() {
            val code = prefs.getString("language", null)
            return Language.entries.firstOrNull { it.code == code } ?: Language.ITALIAN
        }
        set(value) = prefs.edit().putString("language", value.code).apply()

    var model: Model
        get() {
            val file = prefs.getString("model", null)
            return Model.entries.firstOrNull { it.file == file } ?: Model.TURBO
        }
        set(value) = prefs.edit().putString("model", value.file).apply()
}
