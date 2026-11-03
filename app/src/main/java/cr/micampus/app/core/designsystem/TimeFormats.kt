package cr.micampus.app.core.designsystem

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val costaRica = Locale.forLanguageTag("es-CR")

// Formatters are immutable and thread-safe; build each pattern once instead of on every list item.
private val time12 = DateTimeFormatter.ofPattern("h:mm a", costaRica)
private val time24 = DateTimeFormatter.ofPattern("HH:mm", costaRica)
private val eventDateTime12 = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", costaRica)
private val eventDateTime24 = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", costaRica)
private val widgetDateTime12 = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", costaRica)
private val widgetDateTime24 = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", costaRica)
private val meridiem = DateTimeFormatter.ofPattern("a", costaRica)

fun timeFormatter(use12h: Boolean): DateTimeFormatter = if (use12h) time12 else time24

fun eventDateTimeFormatter(use12h: Boolean): DateTimeFormatter = if (use12h) eventDateTime12 else eventDateTime24

fun widgetDateTimeFormatter(use12h: Boolean): DateTimeFormatter = if (use12h) widgetDateTime12 else widgetDateTime24

/**
 * Compact start–end range for same-day display, e.g. "13:00 – 14:50" or "9:00 – 10:50 a. m.".
 * In 12h mode the meridiem is collapsed when both endpoints share it; when the range crosses
 * noon both are kept: "11:30 a. m. – 1:00 p. m.".
 */
fun formatTimeRange(start: LocalDateTime, end: LocalDateTime, use12h: Boolean): String {
    val formatter = timeFormatter(use12h)
    val startText = start.format(formatter)
    val endText = end.format(formatter)
    if (!use12h) return "$startText – $endText"
    val startMeridiem = start.format(meridiem)
    return if (startMeridiem == end.format(meridiem)) {
        "${startText.removeSuffix(startMeridiem).trim()} – $endText"
    } else {
        "$startText – $endText"
    }
}
