package cr.micampus.app.core.designsystem

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val costaRica = Locale.forLanguageTag("es-CR")

fun timeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "h:mm a" else "HH:mm", costaRica)

fun eventDateTimeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "EEE d MMM · h:mm a" else "EEE d MMM · HH:mm", costaRica)

fun widgetDateTimeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "EEE d MMM, h:mm a" else "EEE d MMM, HH:mm", costaRica)

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
    val meridiem = DateTimeFormatter.ofPattern("a", costaRica)
    val startMeridiem = start.format(meridiem)
    return if (startMeridiem == end.format(meridiem)) {
        "${startText.removeSuffix(startMeridiem).trim()} – $endText"
    } else {
        "$startText – $endText"
    }
}
