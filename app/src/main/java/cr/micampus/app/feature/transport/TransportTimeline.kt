package cr.micampus.app.feature.transport

import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val costaRica: Locale = Locale.forLanguageTag("es-CR")
private val weekdayDayFormatter = DateTimeFormatter.ofPattern("EEEE d", costaRica)
private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", costaRica)

/** The next departure of a direction: its service day, that day's departures and its position among them. */
internal data class NextDeparture(val date: LocalDate, val departures: List<LocalTime>, val index: Int) {
    val time: LocalTime get() = departures[index]
    val dateTime: LocalDateTime get() = LocalDateTime.of(date, time)
}

/**
 * First departure strictly after [now] among the verified [serviceDays], even when it falls on
 * tomorrow or a later service day. Null when the verified schedule has nothing left.
 */
internal fun nextDeparture(serviceDays: List<TransportService>, now: LocalDateTime): NextDeparture? =
    serviceDays.asSequence()
        .filter { it.status == ServiceStatus.VERIFIED && !it.date.isBefore(now.toLocalDate()) }
        .sortedBy { it.date }
        .firstNotNullOfOrNull { service ->
            val times = service.departureTimes()
            val index = times.indexOfFirst { LocalDateTime.of(service.date, it).isAfter(now) }
            if (index >= 0) NextDeparture(service.date, times, index) else null
        }

internal fun TransportService.departureTimes(): List<LocalTime> =
    departures.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }

/** "PRÓXIMA SALIDA", or with the day appended when it isn't today: "PRÓXIMA SALIDA · MAÑANA". */
internal fun nextDepartureOverline(date: LocalDate, today: LocalDate): String =
    if (date == today) "PRÓXIMA SALIDA" else "PRÓXIMA SALIDA · ${transportDateLabel(date, today).uppercase(costaRica)}"

/**
 * Hero supporting line. For today's departure it tells how many are left; for a later day
 * it explains why today has none ("Hoy ya no hay salidas · la última fue a las 21:05").
 */
internal fun nextDepartureSupport(next: NextDeparture, todayService: TransportService?, today: LocalDate, use12h: Boolean): String? {
    if (next.date == today) {
        val remaining = next.departures.size - next.index
        return if (remaining == 1) "Última salida de hoy" else "Quedan $remaining salidas hoy"
    }
    if (todayService?.date != today) return null
    return when (todayService.status) {
        ServiceStatus.VERIFIED -> todayService.departureTimes().lastOrNull()
            ?.let { "Hoy ya no hay salidas · la última fue a las ${it.format(timeFormatter(use12h))}" }
        ServiceStatus.NO_SERVICE -> "Hoy no hay servicio"
        else -> null
    }
}

/** Departures shown under the hero before the "Ver las N salidas" row. */
internal fun followingDepartures(next: NextDeparture, limit: Int): List<LocalTime> =
    next.departures.drop(next.index + 1).take(limit)

/** Whether the hero's day has departures beyond the hero and its [limit] following ones. */
internal fun hasMoreDepartures(next: NextDeparture, limit: Int): Boolean =
    next.index > 0 || next.index + 1 + limit < next.departures.size

/** "Hoy, lunes 2", "Mañana, martes 3", otherwise "Miércoles 4". */
internal fun transportDayHeading(date: LocalDate, today: LocalDate): String {
    val day = date.format(weekdayDayFormatter)
    return when (date) {
        today -> "Hoy, $day"
        today.plusDays(1) -> "Mañana, $day"
        else -> day.replaceFirstChar { it.titlecase(costaRica) }
    }
}

/** "Ver las 19 salidas de mañana", "Ver las 19 salidas del miércoles 4". */
internal fun allDeparturesLabel(count: Int, date: LocalDate, today: LocalDate): String = "Ver las $count salidas " + when (date) {
    today -> "de hoy"
    today.plusDays(1) -> "de mañana"
    else -> "del ${date.format(weekdayDayFormatter)}"
}

/** One-line provenance: "Horario verificado · UCR 2026 · vigente hasta 31 dic". */
internal fun datasetSummary(dataset: TransportDataset, today: LocalDate): String {
    val name = "${dataset.institution.name} ${dataset.verifiedUntil.year}"
    val until = formatDayMonth(dataset.verifiedUntil)
    return if (today.isAfter(dataset.verifiedUntil)) "Horario vencido · $name · venció el $until"
    else "Horario verificado · $name · vigente hasta $until"
}

// Android's ICU data may add abbreviation dots ("31 dic."); keep the compact form.
private fun formatDayMonth(date: LocalDate): String = date.format(dayMonthFormatter).replace(".", "")
