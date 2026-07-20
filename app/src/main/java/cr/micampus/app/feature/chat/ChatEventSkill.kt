package cr.micampus.app.feature.chat

import com.google.gson.JsonParser
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A model-produced proposal is inert until the student confirms it in the chat UI. */
data class ChatEventProposal(
    val id: String = "chat-${UUID.randomUUID()}",
    val title: String? = null,
    val institution: Institution? = null,
    val kind: EventKind = EventKind.ACTIVITY,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: String = "",
    val notes: String = "",
    val inferredEnd: Boolean = false,
    val duplicate: Boolean = false,
    val sourceText: String = "",
) {
    val missingFields: List<String> get() = buildList {
        if (title.isNullOrBlank()) add("el título")
        if (date == null) add("la fecha")
        if (startTime == null) add("la hora de inicio")
        if (institution == null) add("la institución")
    }

    fun toCampusEvent(): CampusEvent? {
        val resolvedTitle = title?.trim()?.takeIf(String::isNotBlank) ?: return null
        val resolvedInstitution = institution ?: return null
        val resolvedDate = date ?: return null
        val resolvedStart = startTime ?: return null
        val start = resolvedDate.atTime(resolvedStart)
        val end = resolvedDate.atTime(endTime ?: resolvedStart.plusHours(1)).let {
            if (it.isAfter(start)) it else it.plusDays(1)
        }
        return CampusEvent(
            id = id,
            title = resolvedTitle,
            institution = resolvedInstitution,
            kind = kind,
            start = start,
            end = end,
            location = location.trim(),
            notes = notes.trim(),
            source = "chat",
        )
    }

    companion object {
        fun fromEvent(event: CampusEvent, previous: ChatEventProposal): ChatEventProposal = previous.copy(
            title = event.title,
            institution = event.institution,
            kind = event.kind,
            date = event.start.toLocalDate(),
            startTime = event.start.toLocalTime(),
            endTime = event.end.toLocalTime(),
            location = event.location,
            notes = event.notes,
            inferredEnd = false,
        )
    }
}

object ChatEventIntentDetector {
    private val commands = listOf("/crear-evento", "/create-event")
    private val eventWords = setOf(
        "actividad", "cita", "clase", "entrega", "examen", "parcial", "prueba", "quiz", "reunion", "tarea",
        "appointment", "assignment", "class", "deadline", "exam", "meeting", "quiz", "test",
    )
    private val ownership = listOf(
        "tengo ", "tendre ", "me toca ", "debo entregar", "agend", "agenda ", "crear evento", "crea un evento", "recuerdame",
        "i have ", "i've got ", "schedule ", "add an event", "create an event", "remind me",
    )
    private val temporal = listOf(
        "hoy", "manana", "pasado manana", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo",
        "today", "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )
    private val questionPrefixes = listOf(
        "cuando ", "donde ", "que ", "cual ", "como ", "por que ", "when ", "where ", "what ", "which ", "how ", "why ",
    )
    private val numericDateOrTime = Regex("(?:\\b\\d{1,2}[:/]\\d{1,2}(?::\\d{2})?\\b|\\b\\d{1,2}\\s*(?:am|pm|a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)\\b)")

    fun isEventIntent(message: String): Boolean {
        val normalized = normalize(message)
        if (commands.any(normalized::startsWith)) return true
        val explicitCommand = ownership.any(normalized::contains)
        if (!explicitCommand && (message.contains('?') || questionPrefixes.any(normalized::startsWith))) return false
        val hasEvent = eventWords.any { Regex("(^|\\s)${Regex.escape(it)}(\\s|$)").containsMatchIn(normalized) }
        val hasWhen = temporal.any(normalized::contains) || numericDateOrTime.containsMatchIn(normalized)
        return explicitCommand && hasEvent && hasWhen
    }

    fun stripCommand(message: String): String {
        val trimmed = message.trim()
        val command = commands.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return trimmed
        return trimmed.substring(command.length).trim()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Date and time facts that can be established without asking the model to do calendar arithmetic. */
data class ResolvedEventTemporalInput(
    val resolvedDate: LocalDate? = null,
    val resolvedStartTime: LocalTime? = null,
    val resolvedEndTime: LocalTime? = null,
    val hasExplicitDate: Boolean = false,
    val hasExplicitTime: Boolean = false,
    val hasExplicitEndTime: Boolean = false,
    val invalidExplicitDate: Boolean = false,
)

/** Resolves common student date phrases against one immutable Costa Rica timestamp. */
object EventTemporalResolver {
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
    private val numericDate = Regex("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})(?!\\d)")
    private val meridiemTime = Regex("(?<!\\d)(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*(a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)(?![\\p{L}\\d])")
    private val twentyFourHourTime = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val contextualHour = Regex("(?:\\ba\\s+las?\\s+|\\bat\\s+)([01]?\\d|2[0-3])(?:\\s+horas?)?\\b")
    private val anyTime = Regex(
        "(?:\\b(?:a\\s+las?|at)\\s+\\d{1,2}(?::\\d{2})?|\\b\\d{1,2}:\\d{2}|\\b\\d{1,2}\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?))",
    )
    private val explicitEnd = Regex(
        "(?:\\bhasta(?:\\s+las?)?\\s+|\\buntil\\s+|\\bto\\s+)\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)?",
    )
    private val spanishWeekdays = mapOf(
        "lunes" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )
    private val englishWeekdays = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    fun resolve(message: String, now: ZonedDateTime): ResolvedEventTemporalInput {
        val normalized = normalize(message)
        val dateResolution = resolveDate(normalized, now.toLocalDate())
        val timeFacts = resolveTimes(normalized)
        return ResolvedEventTemporalInput(
            resolvedDate = dateResolution.date,
            resolvedStartTime = timeFacts.first,
            resolvedEndTime = timeFacts.second,
            hasExplicitDate = dateResolution.date != null || dateResolution.invalid,
            hasExplicitTime = anyTime.containsMatchIn(normalized),
            hasExplicitEndTime = explicitEnd.containsMatchIn(normalized),
            invalidExplicitDate = dateResolution.invalid,
        )
    }

    private fun resolveDate(message: String, today: LocalDate): DateResolution {
        isoDate.find(message)?.destructured?.let { (year, month, day) ->
            return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }
                .fold({ DateResolution(it) }, { DateResolution(invalid = true) })
        }
        numericDate.find(message)?.destructured?.let { (day, month, rawYear) ->
            val year = rawYear.toInt().let { if (rawYear.length == 2) 2_000 + it else it }
            return runCatching { LocalDate.of(year, month.toInt(), day.toInt()) }
                .fold({ DateResolution(it) }, { DateResolution(invalid = true) })
        }
        if (Regex("\\b(?:pasado\\s+manana|day\\s+after\\s+tomorrow)\\b").containsMatchIn(message)) return DateResolution(today.plusDays(2))
        if (Regex("\\b(?:manana|tomorrow)\\b").containsMatchIn(message)) return DateResolution(today.plusDays(1))
        if (Regex("\\b(?:hoy|today)\\b").containsMatchIn(message)) return DateResolution(today)

        val target = (spanishWeekdays + englishWeekdays).entries.firstOrNull { (name, _) ->
            Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(message)
        }?.value ?: return DateResolution()
        val daysAhead = (target.value - today.dayOfWeek.value + 7) % 7
        return DateResolution(today.plusDays(if (daysAhead == 0) 7 else daysAhead.toLong()))
    }

    private fun resolveTimes(message: String): Pair<LocalTime?, LocalTime?> {
        val meridiemMatches = meridiemTime.findAll(message).toList()
        val matches = buildList<Pair<Int, LocalTime>> {
            meridiemMatches.forEach { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].takeIf(String::isNotBlank)?.toInt() ?: 0
                val isPm = match.groupValues[3].startsWith("p")
                add(match.range.first to LocalTime.of((hour % 12) + if (isPm) 12 else 0, minute))
            }
            twentyFourHourTime.findAll(message).forEach { match ->
                if (meridiemMatches.none { match.range.first in it.range }) {
                    add(match.range.first to LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()))
                }
            }
        }.sortedBy { it.first }.map { it.second }.distinct()
        if (matches.isNotEmpty()) {
            return matches.first() to matches.getOrNull(1).takeIf { explicitEnd.containsMatchIn(message) }
        }
        val contextual = contextualHour.find(message)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it >= 13 }
            ?.let { LocalTime.of(it, 0) }
        return contextual to null
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private data class DateResolution(val date: LocalDate? = null, val invalid: Boolean = false)
}

object ChatEventPrompt {
    fun build(
        rawMessage: String,
        institutionShortNames: List<String>,
        now: ZonedDateTime,
        temporalInput: ResolvedEventTemporalInput = EventTemporalResolver.resolve(rawMessage, now),
        previous: ChatEventProposal? = null,
    ): String = buildString {
        append("Extrae una sola propuesta de evento de calendario. No inventes datos ausentes. ")
        append("Devuelve únicamente JSON compacto con las claves opcionales title, institution, kind, date, start, end, location y notes. ")
        append("institution solo puede ser una de ").append(institutionShortNames.joinToString()).append(". ")
        append("kind solo puede ser CLASS, EXAM, QUIZ, TAREA, ACTIVITY o TRANSIT. ")
        append("Usa fecha AAAA-MM-DD y horas HH:MM.\n")
        append(hiddenCurrentDateTime(now))
        temporalInput.resolvedDate?.let {
            append("FECHA_RESUELTA_POR_APP=").append(it).append('\n')
            append("NO_REINTERPRETAR_FECHA_RESUELTA=true\n")
        }
        if (!temporalInput.hasExplicitTime) {
            append("HORA_PROPORCIONADA=false\nNo devuelvas start ni end: la app pedirá la hora que falta.\n")
        }
        previous?.let { append("Propuesta parcial anterior: ").append(it.toPromptJson()).append("\n") }
        append(hiddenInstitutionMessage(rawMessage, institutionShortNames))
    }
}

object ChatEventParser {
    fun parse(
        raw: String,
        sourceText: String,
        selectedInstitutions: Set<Institution>,
        now: ZonedDateTime,
        temporalInput: ResolvedEventTemporalInput = EventTemporalResolver.resolve(sourceText, now),
        previous: ChatEventProposal? = null,
    ): Result<ChatEventProposal> = runCatching {
        require(!temporalInput.invalidExplicitDate) { "invalid_date" }
        val root = JsonParser.parseString(normalizeJson(raw)).asJsonObject
        fun string(name: String): String? = root.get(name)?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf(String::isNotBlank)
        val title = string("title") ?: previous?.title
        val modelDate = string("date")?.let(LocalDate::parse)
        val modelStart = string("start")?.let(LocalTime::parse)
        val modelEnd = string("end")?.let(LocalTime::parse)
        val date = temporalInput.resolvedDate ?: previous?.date ?: modelDate
        val start = temporalInput.resolvedStartTime
            ?: previous?.startTime
            ?: modelStart.takeIf { temporalInput.hasExplicitTime }
        val acceptedEnd = temporalInput.resolvedEndTime
            ?: modelEnd.takeIf { temporalInput.hasExplicitEndTime }
        val end = when {
            temporalInput.hasExplicitTime -> acceptedEnd ?: start?.plusHours(1)
            previous?.endTime != null -> previous.endTime
            previous?.startTime != null -> previous.startTime.plusHours(1)
            else -> null
        }
        val institution = string("institution")
            ?.let { value -> selectedInstitutions.firstOrNull { it.name.equals(value, true) } }
            ?: previous?.institution
            ?: selectedInstitutions.singleOrNull()
        val kind = string("kind")?.let { runCatching { EventKind.valueOf(it.uppercase()) }.getOrNull() }
            ?: previous?.kind ?: inferKind(sourceText)
        val proposal = ChatEventProposal(
            id = previous?.id ?: "chat-${UUID.randomUUID()}",
            title = title,
            institution = institution,
            kind = kind,
            date = date,
            startTime = start,
            endTime = end,
            location = string("location") ?: previous?.location.orEmpty(),
            notes = string("notes") ?: previous?.notes.orEmpty(),
            inferredEnd = when {
                start == null -> false
                temporalInput.hasExplicitTime -> acceptedEnd == null
                else -> previous?.inferredEnd == true || previous?.endTime == null
            },
            sourceText = sourceText,
        )
        val startDateTime = proposal.date?.let { day -> proposal.startTime?.let(day::atTime) }
        require(proposal.date == null || !proposal.date.isBefore(now.toLocalDate())) { "past_event" }
        require(startDateTime == null || startDateTime.isAfter(now.toLocalDateTime())) { "past_event" }
        proposal
    }

    private fun inferKind(text: String): EventKind {
        val normalized = text.lowercase()
        return when {
            listOf("examen", "parcial", "prueba", "exam", "test").any(normalized::contains) -> EventKind.EXAM
            listOf("quiz", "cuestionario").any(normalized::contains) -> EventKind.QUIZ
            listOf("tarea", "entrega", "assignment", "deadline").any(normalized::contains) -> EventKind.TAREA
            listOf("clase", "class").any(normalized::contains) -> EventKind.CLASS
            else -> EventKind.ACTIVITY
        }
    }

    private fun normalizeJson(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstBreak = trimmed.indexOf('\n')
        require(firstBreak > 0 && trimmed.endsWith("```"))
        return trimmed.substring(firstBreak + 1, trimmed.length - 3).trim()
    }
}

internal fun hiddenCurrentDateTime(now: ZonedDateTime): String = buildString {
    append("## FECHA_HORA_ACTUAL\n")
    append("FECHA=").append(now.toLocalDate()).append('\n')
    append("DIA=").append(SPANISH_WEEKDAYS[now.dayOfWeek]).append('\n')
    append("HORA=").append(now.toLocalTime().format(CURRENT_TIME_FORMAT)).append('\n')
    append("ZONA=").append(now.zone.id).append('\n')
}

internal fun hiddenInstitutionMessage(rawMessage: String, institutionShortNames: List<String>): String = buildString {
    append("## CONTEXTO INSTITUCIONAL\nEstudiante de: ")
    // Reverse lexical order preserves the documented UNA, UCR context while remaining stable
    // and automatically accommodating future short names.
    append(institutionShortNames.distinct().sortedDescending().joinToString(", "))
    append("\n## MENSAJE\n")
    append(rawMessage)
}

private fun ChatEventProposal.toPromptJson(): String = buildString {
    append('{')
    listOfNotNull(
        title?.let { "\"title\":\"${it.jsonEscape()}\"" },
        institution?.let { "\"institution\":\"${it.name}\"" },
        "\"kind\":\"${kind.name}\"",
        date?.let { "\"date\":\"$it\"" },
        startTime?.let { "\"start\":\"$it\"" },
        endTime?.let { "\"end\":\"$it\"" },
        location.takeIf(String::isNotBlank)?.let { "\"location\":\"${it.jsonEscape()}\"" },
        notes.takeIf(String::isNotBlank)?.let { "\"notes\":\"${it.jsonEscape()}\"" },
    ).joinTo(this, ",")
    append('}')
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

internal fun institutionShortNames(institutions: Set<Institution>): List<String> =
    institutions.map(Institution::llmShortName).distinct().sortedDescending()

internal val COSTA_RICA_ZONE: ZoneId = ZoneId.of("America/Costa_Rica")

private val CURRENT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SPANISH_WEEKDAYS = mapOf(
    DayOfWeek.MONDAY to "lunes",
    DayOfWeek.TUESDAY to "martes",
    DayOfWeek.WEDNESDAY to "miércoles",
    DayOfWeek.THURSDAY to "jueves",
    DayOfWeek.FRIDAY to "viernes",
    DayOfWeek.SATURDAY to "sábado",
    DayOfWeek.SUNDAY to "domingo",
)
