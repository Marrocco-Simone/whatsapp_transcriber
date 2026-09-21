package dev.simone.watranscriber.data

import android.content.Context

enum class Language(val code: String, val label: String) {
    ITALIAN("it", "Italian"),
    ENGLISH("en", "English"),
    AUTO("auto", "Detect the language"),
}

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var language: Language
        get() {
            val code = prefs.getString("language", null)
            return Language.entries.firstOrNull { it.code == code } ?: Language.ITALIAN
        }
        set(value) = prefs.edit().putString("language", value.code).apply()
}
