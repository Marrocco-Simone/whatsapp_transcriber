package dev.simone.watranscriber.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLabel(val chat: String, val sender: String?)

private const val VOICE_WINDOW_MS = 120_000L
private const val ANY_WINDOW_MS = 25_000L

class Store private constructor(context: Context) :
    SQLiteOpenHelper(context, "watranscriber.db", null, 1) {

    companion object {
        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notif (" +
                "postedAt INTEGER NOT NULL, chat TEXT NOT NULL, sender TEXT, isVoice INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_notif_time ON notif (postedAt)")
        db.execSQL(
            "CREATE TABLE audio (" +
                "path TEXT PRIMARY KEY, chat TEXT, sender TEXT, transcript TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addNotification(postedAt: Long, chat: String, sender: String?, isVoice: Boolean) {
        val values = ContentValues().apply {
            put("postedAt", postedAt)
            put("chat", chat)
            put("sender", sender)
            put("isVoice", if (isVoice) 1 else 0)
        }
        writableDatabase.insert("notif", null, values)
    }

    /**
     * A voice notification within two minutes of the file wins. A notification of any kind
     * counts only within 25 seconds, because a text message that arrives at the same time
     * would otherwise take the label.
     */
    fun matchNotification(fileTimeMillis: Long): ChatLabel? =
        nearestNotification(fileTimeMillis, VOICE_WINDOW_MS, voiceOnly = true)
            ?: nearestNotification(fileTimeMillis, ANY_WINDOW_MS, voiceOnly = false)

    private fun nearestNotification(
        fileTimeMillis: Long,
        windowMillis: Long,
        voiceOnly: Boolean,
    ): ChatLabel? {
        val where = StringBuilder("ABS(postedAt - ?) <= ?")
        if (voiceOnly) where.append(" AND isVoice = 1")
        readableDatabase.query(
            "notif",
            arrayOf("chat", "sender"),
            where.toString(),
            arrayOf(fileTimeMillis.toString(), windowMillis.toString()),
            null,
            null,
            "ABS(postedAt - $fileTimeMillis) ASC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val sender = if (cursor.isNull(1)) null else cursor.getString(1)
            return ChatLabel(cursor.getString(0), sender)
        }
    }

    fun audioRows(): Map<String, AudioRow> {
        val rows = HashMap<String, AudioRow>()
        readableDatabase.query("audio", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(cursor.getColumnIndexOrThrow("path"))
                rows[path] = AudioRow(
                    chat = cursor.getStringOrNull("chat"),
                    sender = cursor.getStringOrNull("sender"),
                    transcript = cursor.getStringOrNull("transcript"),
                )
            }
        }
        return rows
    }

    fun saveLabel(path: String, label: ChatLabel) {
        writableDatabase.execSQL(
            "INSERT INTO audio (path, chat, sender) VALUES (?, ?, ?) " +
                "ON CONFLICT(path) DO UPDATE SET chat = excluded.chat, sender = excluded.sender",
            arrayOf(path, label.chat, label.sender),
        )
    }

    fun saveTranscript(path: String, text: String) {
        writableDatabase.execSQL(
            "INSERT INTO audio (path, transcript) VALUES (?, ?) " +
                "ON CONFLICT(path) DO UPDATE SET transcript = excluded.transcript",
            arrayOf(path, text),
        )
    }

    /** Clears every stored notification, label and transcript. Audio files are never touched. */
    fun wipe() {
        writableDatabase.execSQL("DELETE FROM notif")
        writableDatabase.execSQL("DELETE FROM audio")
    }
}

data class AudioRow(val chat: String?, val sender: String?, val transcript: String?)

private fun android.database.Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}
