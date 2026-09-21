package dev.simone.watranscriber.data

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
private val VOICE_MARKERS = listOf("🎤", "voice message", "messaggio vocale", "audio")

/**
 * WhatsApp names the chat and the sender only in its notifications. The audio file itself
 * carries no such name, so this service keeps a log of chat names with their times, and
 * [Store.matchNotification] pairs an audio file with the closest entry.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)
        val lastMessage = style?.messages?.lastOrNull()

        val chat = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val text = lastMessage?.text?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        val sender = lastMessage?.person?.name?.toString()
            ?.takeIf { style?.conversationTitle != null }

        val isVoice = VOICE_MARKERS.any { text.contains(it, ignoreCase = true) }
        Store.get(this).addNotification(sbn.postTime, chat, sender, isVoice)
    }
}

fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    val component = ComponentName(context, WhatsAppNotificationListener::class.java)
    return enabled.split(':').any {
        ComponentName.unflattenFromString(it) == component
    }
}
