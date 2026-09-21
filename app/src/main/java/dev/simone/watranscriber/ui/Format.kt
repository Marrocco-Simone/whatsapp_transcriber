package dev.simone.watranscriber.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

fun formatMessageTime(millis: Long): String {
    val moment = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = moment.format(timeFormat)
    return when (moment.toLocalDate()) {
        today -> time
        today.minusDays(1) -> "yesterday $time"
        else -> "${moment.format(dateFormat)} $time"
    }
}

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
