package cr.micampus.app.core.designsystem

import java.time.format.DateTimeFormatter
import java.util.Locale

private val costaRica = Locale.forLanguageTag("es-CR")

fun timeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "h:mm a" else "HH:mm", costaRica)

fun eventDateTimeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "EEE d MMM · h:mm a" else "EEE d MMM · HH:mm", costaRica)

fun widgetDateTimeFormatter(use12h: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use12h) "EEE d MMM, h:mm a" else "EEE d MMM, HH:mm", costaRica)
