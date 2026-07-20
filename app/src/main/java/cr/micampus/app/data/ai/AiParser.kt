package cr.micampus.app.data.ai

import com.google.gson.*
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import cr.micampus.app.core.model.*
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import cr.micampus.app.data.diagnostics.NoOpImportDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime

data class AiParseResult(val events: List<CampusEvent>, val warnings: List<String>, val usedCloud: Boolean, val drafts: List<CalendarEventDraft> = emptyList())
interface AiScheduleParser { fun parseStrictJson(json: String): AiParseResult }

data class SyllabusParse(val syllabus: ExtractedSyllabus, val drafts: List<CalendarEventDraft>)

/** Deliberately modest task limits; import needs room for JSON while chat stays concise. */
const val IMPORT_MAX_OUTPUT_TOKENS = 1_024
const val CHAT_MAX_OUTPUT_TOKENS = 512
const val EVENT_MAX_OUTPUT_TOKENS = 256

object SyllabusPrompt {
    const val RULES =
        "## TAREA\nAnaliza un documento universitario (carta al estudiante, programa o cronograma). Extrae: " +
            "(1) course: nombre, código (p. ej. EIF200), institution (UCR o UNA) y period; " +
            "(2) groups: cada grupo de la tabla de horario del curso, con label, days como nombres completos en mayúsculas (LUNES, MARTES, MIERCOLES, JUEVES, VIERNES, SABADO, DOMINGO), start y end en formato HH:MM de 24 horas, e instructor; " +
            "(3) weeks: cada fila del cronograma semanal es UN elemento con week, from y to (AAAA-MM-DD, rango inclusivo) y topic con solo el contenido académico que se enseña esa semana; " +
            "(4) holidays: feriados y días sin lecciones, un elemento por fecha (incluye cada día de Semana Santa). " +
            "Si el tema de una semana menciona un feriado en una fecha (p. ej. \"Viernes 11 de abril: Feriado\"), agrega esa fecha aquí y deja el topic solo con el contenido académico. Cada holiday con date y title; " +
            "(5) events: SOLO eventos con fecha propia: exámenes, pruebas, quices, tareas, entregas o actividades, con category (EXAM, QUIZ, TAREA, ACTIVITY u OTHER). Nunca incluyas ocurrencias de clases regulares en events; pertenecen únicamente a weeks. Para cada evento usa title corto y description con el detalle si existe.\n" +
            "## REGLAS\nNunca inventes valores; cuando falte un dato anidado omite su clave. Usa fechas AAAA-MM-DD y horas HH:MM. " +
            "Conserva el texto original de fechas ambiguas y evidencia breve con su página. " +
            "Las cinco claves raíz siempre deben existir; course puede ser null y los arreglos sin resultados deben ser []. En objetos anidados omite las claves desconocidas; no uses null allí. No uses Markdown ni texto fuera del JSON. " +
            "Responde con JSON compacto, sin espacios ni saltos de línea innecesarios."
    const val SHAPE =
        "## CONTRATO JSON\nDevuelve SOLO un objeto JSON válido. Las claves raíz obligatorias son exactamente \"course\", \"groups\", \"weeks\", \"holidays\", \"events\". " +
            "Plantilla válida: {\"course\":{\"name\":\"Programación I\",\"code\":\"EIF203\",\"institution\":\"UCR\",\"period\":\"I ciclo 2026\"},\"groups\":[{\"label\":\"01\",\"days\":[\"LUNES\"],\"start\":\"08:00\",\"end\":\"09:40\",\"instructor\":\"Ana Pérez\"}],\"weeks\":[{\"week\":1,\"from\":\"2026-02-16\",\"to\":\"2026-02-22\",\"topic\":\"Introducción\"}],\"holidays\":[{\"date\":\"2026-04-11\",\"title\":\"Feriado\"}],\"events\":[{\"date\":\"2026-03-10\",\"title\":\"Quiz 1\",\"category\":\"QUIZ\"}]}. " +
            "Ejemplo vacío válido: {\"course\":null,\"groups\":[],\"weeks\":[],\"holidays\":[],\"events\":[]}.\n"
    fun institutionLine(institution: Institution?): String =
        institution?.let { "El estudiante solo pertenece a la institución ${it.name}; asume que todo el documento corresponde a ${it.name}.\n" }.orEmpty()

    fun localRequest(documentChunk: String): String =
        SHAPE + RULES + "\n## DOCUMENTO\n" + documentChunk
}

internal fun normalizeAiJson(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstBreak = trimmed.indexOf('\n')
    require(firstBreak > 0 && trimmed.substring(0, firstBreak).lowercase() in setOf("```", "```json"))
    require(trimmed.endsWith("```"))
    val body = trimmed.substring(firstBreak + 1, trimmed.length - 3).trim()
    require("```" !in body)
    return body
}

class StrictJsonAiParser : AiScheduleParser {
    override fun parseStrictJson(json: String): AiParseResult = runCatching {
        val drafts = parseDrafts(json)
        AiParseResult(emptyList(), listOf("Borrador estructurado pendiente de confirmación"), false, drafts)
    }.getOrElse { AiParseResult(emptyList(), listOf("Respuesta de IA inválida"), false) }

    fun parseSyllabus(json: String, now: LocalDate = LocalDate.now()): SyllabusParse {
        val normalized = normalizeAiJson(json)
        val root = JsonParser.parseString(normalized).asJsonObject
        fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun JsonObject.date(name: String): LocalDate? = string(name)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun JsonObject.time(name: String): LocalTime? = string(name)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val course = root.get("course")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            Course(code = it.string("code"), name = it.string("name"), instructor = null).takeIf { c -> c.code != null || c.name != null }
        }
        val institution = root.get("course")?.takeIf { it.isJsonObject }?.asJsonObject?.string("institution")
            ?.let { runCatching { Institution.valueOf(it.uppercase()) }.getOrNull() }
        val groups = root.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val label = obj.string("label") ?: return@mapNotNull null
            val days = obj.get("days")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty()
                .mapNotNull { day -> day.takeIf { it.isJsonPrimitive }?.asString?.let(::parseSpanishDay) }.toSet()
            CourseGroup(label = label, days = days, startTime = obj.time("start"), endTime = obj.time("end"), instructor = obj.string("instructor"))
        }
        val weeks = root.get("weeks")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val index = obj.get("week")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            SyllabusWeek(index = index, from = obj.date("from"), to = obj.date("to"), topic = obj.string("topic"))
                .takeIf { it.topic != null || it.from != null }
        }
        val holidays = root.get("holidays")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.date("date")
        }
        val syllabus = ExtractedSyllabus(course = course, institution = institution, groups = groups, weeks = weeks, holidays = holidays)
        return SyllabusParse(syllabus, parseDrafts(normalized, now))
    }

    private fun parseSpanishDay(value: String): java.time.DayOfWeek? = when (value.trim().uppercase().replace('Á', 'A').replace('É', 'E').replace('Í', 'I')) {
        "LUNES" -> java.time.DayOfWeek.MONDAY
        "MARTES" -> java.time.DayOfWeek.TUESDAY
        "MIERCOLES" -> java.time.DayOfWeek.WEDNESDAY
        "JUEVES" -> java.time.DayOfWeek.THURSDAY
        "VIERNES" -> java.time.DayOfWeek.FRIDAY
        "SABADO" -> java.time.DayOfWeek.SATURDAY
        "DOMINGO" -> java.time.DayOfWeek.SUNDAY
        else -> null
    }

    private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()

    fun parseDrafts(json: String, now: LocalDate = LocalDate.now()): List<CalendarEventDraft> {
        val root = JsonParser.parseString(normalizeAiJson(json)).asJsonObject
        val events = root.getAsJsonArray("events") ?: throw IllegalArgumentException("events missing")
        return events.map { element ->
            val objectValue = element.asJsonObject
            fun string(name: String): String? = objectValue.get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val title = string("title")
            val originalDate = string("originalDateText") ?: string("date")
            val dateText = string("date")
            val date = dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val start = string("start")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val end = string("end")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val sourcePage = objectValue.get("sourcePage")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            val inferredYear = objectValue.get("inferredYear")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
            val ambiguousDate = objectValue.get("ambiguousDate")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
            val issues = buildSet {
                if (date == null) add(ImportIssue.MISSING_DATE)
                if (dateText != null && date == null) add(ImportIssue.AMBIGUOUS_DATE)
                if (start == null) add(ImportIssue.MISSING_TIME)
                if (inferredYear) add(ImportIssue.INFERRED_YEAR)
                if (ambiguousDate) add(ImportIssue.AMBIGUOUS_DATE)
                if (date != null && date.isBefore(now)) add(ImportIssue.PAST)
                if (start != null && end != null && !end.isAfter(start)) add(ImportIssue.INVALID_RANGE)
            }
            val courseHint = string("courseHint")
            CalendarEventDraft(
                id = stableId(title, originalDate, start?.toString(), courseHint), title = title,
                category = string("category")?.let { runCatching { EventCategory.valueOf(it.uppercase()) }.getOrNull() },
                institution = string("institution")?.let { runCatching { Institution.valueOf(it.uppercase()) }.getOrNull() },
                date = date, startTime = start, endTime = end, location = string("location"),
                course = courseHint?.let { Course(it, null) }, sourcePage = sourcePage,
                evidence = Evidence(sourcePage, string("evidence")), issues = issues, originalDateText = originalDate,
                description = string("description"),
            )
        }
    }

    private fun stableId(vararg values: String?): String = stableDraftId(*values)
}

internal fun stableDraftId(vararg values: String?): String = MessageDigest.getInstance("SHA-256").digest(values.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

object ClassSessionExpander {
    // Whole-week breaks that cancel classes for the entire week (not single-day feriados,
    // which are handled by the holidays set). A cronograma cell is a break when its meaningful
    // content is just one of these markers.
    private val weekBreakMarkers = listOf("semana santa", "receso", "vacaciones")
    // Trailing announcements a cronograma cell often appends after the real topic: a single-day
    // feriado, an exam/quiz announcement, or a "<weekday> <day> de <month>" date clause. These are
    // captured elsewhere (holidays[] / events[]); strip them so they don't pollute the class title.
    private val trailingAnnouncement = Regex(
        "(?i)\\s*(" +
            "(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\s+\\d.*" +
            "|feriado.*" +
            "|(prueba de ejecuci[oó]n|examen|quiz|semana santa).*" +
            ")$",
    )

    fun expand(syllabus: ExtractedSyllabus, group: CourseGroup, now: LocalDate = LocalDate.now()): List<CalendarEventDraft> {
        val holidays = syllabus.holidays.toSet()
        return syllabus.weeks.flatMap { week ->
            val from = week.from ?: return@flatMap emptyList()
            val to = week.to ?: return@flatMap emptyList()
            val rawTopic = week.topic?.trim()?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
            val topic = cleanAcademicTopic(rawTopic) ?: return@flatMap emptyList()
            val courseDisplayName = listOfNotNull(syllabus.course?.name, syllabus.course?.code)
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            val courseTitle = courseDisplayName.ifBlank { topic }
            val description = topic.takeIf { courseDisplayName.isNotBlank() }
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
                .filter { it.dayOfWeek in group.days && it !in holidays }
                .map { date ->
                    CalendarEventDraft(
                        id = stableDraftId(topic, date.toString(), group.startTime?.toString(), syllabus.course?.code, group.label),
                        title = courseTitle,
                        description = description,
                        category = EventCategory.CLASS,
                        institution = syllabus.institution,
                        date = date,
                        startTime = group.startTime,
                        endTime = group.endTime,
                        location = null,
                        course = syllabus.course,
                        sourcePage = null,
                        evidence = null,
                        issues = buildSet {
                            if (group.startTime == null) add(ImportIssue.MISSING_TIME)
                            if (date.isBefore(now)) add(ImportIssue.PAST)
                        },
                    )
                }.toList()
        }
    }

    // A cell is a whole-week break when, ignoring parentheticals and punctuation, its core text is
    // essentially one of the break markers (allowing a few trailing words like "no hay lecciones").
    internal fun cleanAcademicTopic(topic: String?): String? {
        val raw = topic?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (isWeekBreak(raw)) return null
        return cleanTitle(raw).takeIf(String::isNotBlank)
    }

    private fun isWeekBreak(topic: String): Boolean {
        val core = topic.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^a-záéíóúñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return weekBreakMarkers.any { core == it || core.startsWith("$it ") } && core.split(" ").size <= 5
    }

    // Strip announcements captured as holidays/events; an announcement-only cell has no class topic.
    private fun cleanTitle(topic: String): String =
        topic.replace(trailingAnnouncement, "").trim().trimEnd('.', ',', ';', ':').trim()
}

/** Links a syllabus topic to the already-confirmed schedule; the schedule remains authoritative. */
object SyllabusClassLinker {
    data class Result(val matchingGroups: List<CourseGroup>, val drafts: List<CalendarEventDraft>) {
        val uniqueGroup: CourseGroup? get() = matchingGroups.singleOrNull()
    }

    fun link(syllabus: ExtractedSyllabus, existing: List<CampusEvent>, selectedGroup: CourseGroup? = null, now: LocalDate = LocalDate.now()): Result {
        val candidates = existing.filter { event ->
            event.kind == EventKind.CLASS && courseMatches(syllabus, event) && syllabus.weeks.any { it.covers(event.start.toLocalDate()) }
        }
        val groups = syllabus.groups.filter { group ->
            (selectedGroup == null || selectedGroup == group) && candidates.any { occurrenceMatches(it, group) }
        }
        val chosen = selectedGroup?.takeIf { it in groups } ?: groups.singleOrNull()
        val drafts = chosen?.let { group -> candidates.filter { occurrenceMatches(it, group) }.mapNotNull { event ->
            val week = syllabus.weeks.firstOrNull { it.covers(event.start.toLocalDate()) } ?: return@mapNotNull null
            val topic = ClassSessionExpander.cleanAcademicTopic(week.topic) ?: return@mapNotNull null
            CalendarEventDraft(
                id = event.id, title = event.title, category = EventCategory.CLASS, institution = event.institution,
                date = event.start.toLocalDate(), startTime = event.start.toLocalTime(), endTime = event.end.toLocalTime(),
                location = event.location, course = Course(event.courseCode ?: syllabus.course?.code, syllabus.course?.name),
                sourcePage = null, evidence = null,
                issues = if (event.start.toLocalDate().isBefore(now)) setOf(ImportIssue.PAST) else emptySet(),
                description = appendTopic(event.notes, topic), sourceDocumentId = event.sourceDocumentId,
            )
        } }.orEmpty()
        return Result(groups, drafts)
    }

    private fun occurrenceMatches(event: CampusEvent, group: CourseGroup) =
        event.start.dayOfWeek in group.days && group.startTime == event.start.toLocalTime() && group.endTime == event.end.toLocalTime()

    private fun courseMatches(syllabus: ExtractedSyllabus, event: CampusEvent): Boolean {
        if (syllabus.institution != null && syllabus.institution != event.institution) return false
        val syllabusCode = normalizedCode(syllabus.course?.code)
        val eventCode = normalizedCode(event.courseCode)
        if (syllabusCode.isNotBlank() && eventCode.isNotBlank()) return syllabusCode == eventCode
        if (syllabusCode.length >= 4 && titleContainsCode(event.title, syllabus.course?.code)) return true
        val syllabusName = normalizedTitle(syllabus.course?.name)
        val eventName = normalizedTitle(event.title)
        if (syllabusName.isBlank() || eventName.isBlank()) return false
        if (syllabusName == eventName) return true
        val shorter = if (syllabusName.length < eventName.length) syllabusName else eventName
        val longer = if (syllabusName.length < eventName.length) eventName else syllabusName
        return shorter.split(' ').size >= 2 && " $longer ".contains(" $shorter ")
    }

    private fun appendTopic(notes: String, topic: String): String {
        val line = "Tema: $topic"
        return if (notes.lineSequence().any { it.trim() == line }) notes else listOf(notes.trim(), line).filter(String::isNotBlank).joinToString("\n")
    }
    private fun normalizedTitle(value: String?) = value.orEmpty().lowercase().trim()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
        .replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun normalizedCode(value: String?) = normalizedTitle(value).replace(" ", "")
    private fun titleContainsCode(title: String, code: String?): Boolean {
        val codeParts = alphanumericParts(code)
        if (codeParts.isEmpty()) return false
        return alphanumericParts(title).windowed(codeParts.size).any { it == codeParts }
    }
    private fun alphanumericParts(value: String?): List<String> =
        Regex("[a-z]+|[0-9]+").findAll(normalizedTitle(value)).map { it.value }.toList()
}

private fun SyllabusWeek.covers(date: LocalDate): Boolean {
    val from = from ?: return false
    val to = to ?: return false
    return !to.isBefore(from) && !date.isBefore(from) && !date.isAfter(to)
}

object ScheduleTimeAutofill {
    fun fill(drafts: List<CalendarEventDraft>, schedule: List<CalendarEventDraft>): List<CalendarEventDraft> =
        fill(drafts, schedule, emptyList(), null)

    fun fill(drafts: List<CalendarEventDraft>, groups: List<CourseGroup>, course: Course?): List<CalendarEventDraft> =
        fill(drafts, emptyList(), groups, course)

    fun fill(
        drafts: List<CalendarEventDraft>,
        schedule: List<CalendarEventDraft>,
        groups: List<CourseGroup>,
        course: Course?,
    ): List<CalendarEventDraft> = drafts.map { draft ->
        val scheduleSlots = schedule.filter { entry ->
            entry.startTime != null && entry.endTime != null &&
                entry.date?.dayOfWeek == draft.date?.dayOfWeek && matches(draft, entry.title, entry.course)
        }.map { it.startTime!! to it.endTime!! }
        val groupSlots = groups.filter { group ->
            group.startTime != null && group.endTime != null &&
                draft.date?.dayOfWeek in group.days && matches(draft, courseDisplayName(course), course)
        }.map { it.startTime!! to it.endTime!! }
        fillIfUnique(draft, (scheduleSlots + groupSlots).distinct())
    }

    private fun fillIfUnique(draft: CalendarEventDraft, slots: List<Pair<LocalTime, LocalTime>>): CalendarEventDraft {
        if (ImportIssue.MISSING_TIME !in draft.issues || draft.date == null || slots.size != 1) return draft
        val (start, end) = slots.single()
        val issues = draft.issues - ImportIssue.MISSING_TIME
        return draft.copy(
            startTime = start,
            endTime = end,
            issues = (if (end.isAfter(start)) issues - ImportIssue.INVALID_RANGE else issues) + ImportIssue.INFERRED_TIME,
        )
    }

    private fun matches(draft: CalendarEventDraft, entryTitle: String?, entryCourse: Course?): Boolean {
        val draftCode = draft.course?.code?.trim()?.takeIf(String::isNotBlank)
        val entryCode = entryCourse?.code?.trim()?.takeIf(String::isNotBlank)
        if (draftCode != null && entryCode != null) return draftCode.equals(entryCode, ignoreCase = true)
        val draftNames = listOf(draft.title, draft.course?.name)
        val entryNames = listOf(entryTitle, entryCourse?.code, entryCourse?.name)
        return draftNames.any { left -> entryNames.any { right -> containsEither(left, right) } }
    }

    private fun courseDisplayName(course: Course?): String? =
        listOfNotNull(course?.name, course?.code).map(String::trim).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)

    private fun containsEither(left: String?, right: String?): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        return normalizedLeft.isNotBlank() && normalizedRight.isNotBlank() &&
            (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft))
    }

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
}

object DuplicateDetector {
    fun mark(drafts: List<CalendarEventDraft>, existing: List<CalendarEventDraft> = emptyList()): List<CalendarEventDraft> {
        val all = drafts + existing
        return drafts.map { draft -> if (all.count { key(it) == key(draft) } > 1) draft.copy(issues = draft.issues + ImportIssue.DUPLICATE) else draft }
    }
    private fun key(draft: CalendarEventDraft) = listOf(draft.title?.trim()?.lowercase(), draft.date, draft.startTime, draft.course?.code?.trim()?.lowercase()).joinToString("|")
}

enum class ConsentDecision { PENDING, GRANTED, DENIED }
interface CloudConsent { fun decisionForImport(importId: String): ConsentDecision }
enum class NanoCapability { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }
enum class NanoFailureKind {
    BUSY,
    BACKGROUND_BLOCKED,
    BATTERY_QUOTA,
    NOT_ENOUGH_STORAGE,
    SYSTEM_UPDATE_REQUIRED,
    AICORE_INCOMPATIBLE,
    NOT_AVAILABLE,
    REQUEST_REJECTED,
    RESPONSE_REJECTED,
    SDK_FAILURE,
    CANCELLED,
    UNKNOWN,
}
sealed interface NanoDownloadState {
    data class Started(val totalBytes: Long) : NanoDownloadState
    data class Progress(val downloadedBytes: Long, val totalBytes: Long?) : NanoDownloadState
    data object Completed : NanoDownloadState
    data class Failed(val failure: NanoFailureKind) : NanoDownloadState
}
sealed interface LocalGenerationResult {
    data class Success(val text: String, val modelName: String? = null) : LocalGenerationResult
    data object TooLarge : LocalGenerationResult
    data class Retryable(val failure: NanoFailureKind) : LocalGenerationResult
    data class Failed(val failure: NanoFailureKind) : LocalGenerationResult
    /** Kept for simple fakes and legacy callers; production code returns a typed failure. */
    data object Failure : LocalGenerationResult
}
sealed interface NanoSelfTestResult {
    data class Success(val modelName: String?, val tokenLimit: Int, val inputTokens: Int) : NanoSelfTestResult
    data class RequiresDownload(val capability: NanoCapability) : NanoSelfTestResult
    data object Unavailable : NanoSelfTestResult
    data class Failure(val failure: NanoFailureKind) : NanoSelfTestResult
}

@Generable("Una propuesta de evento de calendario universitario")
data class NanoEventOutput(
    @param:Guide(description = "Título corto del evento")
    val title: String?,
    @param:Guide(description = "Sigla institucional indicada en el mensaje")
    val institution: String?,
    @param:Guide(
        description = "Categoría del evento",
        enumValues = ["CLASS", "EXAM", "QUIZ", "TAREA", "ACTIVITY", "TRANSIT"],
    )
    val kind: String?,
    @param:Guide(description = "Fecha ISO AAAA-MM-DD")
    val date: String?,
    @param:Guide(description = "Hora de inicio HH:MM")
    val start: String?,
    @param:Guide(description = "Hora de fin HH:MM")
    val end: String?,
    @param:Guide(description = "Lugar mencionado")
    val location: String?,
    @param:Guide(description = "Notas mencionadas")
    val notes: String?,
)

interface LocalPromptEngine {
    suspend fun checkCapability(): NanoCapability
    suspend fun generate(prompt: String): LocalGenerationResult
    /**
     * Raw generation entry point for callers with task-specific output needs.  The one-argument
     * method remains the compatibility surface for simple fakes and existing callers.
     */
    suspend fun generate(prompt: String, maxOutputTokens: Int): LocalGenerationResult = generate(prompt)
    /** Uses typed output when the runtime supports it; raw constrained JSON is the fallback. */
    suspend fun generateEvent(prompt: String, maxOutputTokens: Int): LocalGenerationResult =
        generate(prompt, maxOutputTokens)
    suspend fun baseModelName(): String? = null
    suspend fun selfTest(): NanoSelfTestResult = NanoSelfTestResult.Failure(NanoFailureKind.SDK_FAILURE)
    suspend fun resetForRetry() = Unit
    fun setDiagnosticTrace(traceId: String?) = Unit
    fun close() = Unit
}

class MlKitNanoEngine(
    private val diagnostics: ImportDiagnosticsRecorder = NoOpImportDiagnostics,
    private val modelFactory: () -> com.google.mlkit.genai.prompt.GenerativeModel = {
        com.google.mlkit.genai.prompt.Generation.getClient()
    },
) : LocalPromptEngine {
    private var model = modelFactory()
    private var capability: NanoCapability = NanoCapability.UNAVAILABLE
    private var warmedUp = false
    private var tokenLimit: Int? = null
    private var diagnosticTraceId: String? = null

    override fun setDiagnosticTrace(traceId: String?) {
        diagnosticTraceId = traceId
    }

    override suspend fun checkCapability(): NanoCapability {
        val status = runOperation(OP_CHECK_STATUS) { model.checkStatus() }
        capability = when (status) {
            com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> NanoCapability.AVAILABLE
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> NanoCapability.DOWNLOADABLE
            com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> NanoCapability.DOWNLOADING
            else -> NanoCapability.UNAVAILABLE
        }
        val modelName = if (capability == NanoCapability.AVAILABLE) {
            try {
                runOperation(OP_BASE_MODEL) { model.getBaseModelName() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else null
        diagnostics.record(
            diagnosticTraceId,
            ImportDiagnosticEvent(phase = "nano_capability", backend = "local", capability = capability.name.lowercase(), model = modelName),
        )
        return capability
    }

    suspend fun download(onState: (NanoDownloadState) -> Unit = {}): NanoCapability {
        var totalBytes: Long? = null
        runOperation(OP_DOWNLOAD) {
            model.download().collect { status ->
                when (status) {
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> {
                        capability = NanoCapability.DOWNLOADING
                        totalBytes = status.bytesToDownload
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_download_started", operation = OP_DOWNLOAD, backend = "local", outcome = "started"))
                        onState(NanoDownloadState.Started(status.bytesToDownload))
                    }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> {
                        capability = NanoCapability.DOWNLOADING
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_download_progress", operation = OP_DOWNLOAD, backend = "local", outcome = "progress"))
                        onState(NanoDownloadState.Progress(status.totalBytesDownloaded, totalBytes))
                    }
                    com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted -> {
                        capability = NanoCapability.AVAILABLE
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_download_finished", operation = OP_DOWNLOAD, backend = "local", outcome = "success"))
                        onState(NanoDownloadState.Completed)
                    }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> {
                        capability = NanoCapability.UNAVAILABLE
                        val failure = status.e.toNanoFailure()
                        diagnostics.record(
                            diagnosticTraceId,
                            ImportDiagnosticEvent(
                                phase = "nano_download_finished",
                                operation = OP_DOWNLOAD,
                                backend = "local",
                                outcome = "failure",
                                failure = failure.name.lowercase(),
                                exceptionType = diagnosticExceptionType(status.e),
                                genAiErrorCode = status.e.errorCode,
                            ),
                        )
                        onState(NanoDownloadState.Failed(failure))
                        throw status.e
                    }
                }
            }
        }
        return capability
    }

    override suspend fun generate(prompt: String): LocalGenerationResult =
        generate(prompt, IMPORT_MAX_OUTPUT_TOKENS)

    override suspend fun generate(prompt: String, maxOutputTokens: Int): LocalGenerationResult {
        val started = System.nanoTime()
        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_started", backend = "local"))
        if (capability != NanoCapability.AVAILABLE && checkCapability() != NanoCapability.AVAILABLE) {
            diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "failure", failure = "not_available"))
            return LocalGenerationResult.Failed(NanoFailureKind.NOT_AVAILABLE)
        }
        return try {
            ensureWarmup()
            // A rejected build is a deterministic limit/validation error; retrying is useless.
            val request = try {
                buildRequest(prompt, maxOutputTokens, OP_BUILD_REQUEST)
            } catch (invalid: IllegalArgumentException) {
                diagnostics.record(
                    diagnosticTraceId,
                    ImportDiagnosticEvent(
                        phase = "nano_generation_finished",
                        backend = "local",
                        outcome = "failure",
                        failure = NanoFailureKind.REQUEST_REJECTED.name.lowercase(),
                        exceptionType = diagnosticExceptionType(invalid),
                        elapsedMillis = elapsedMillis(started),
                    ),
                )
                return LocalGenerationResult.Failed(NanoFailureKind.REQUEST_REJECTED)
            }
            val tokenLimit = getTokenLimit(OP_TOKEN_LIMIT)
            val tokenCount = countTokensWithProbe(request)
            if (tokenCount == TokenCountOutcome.TooLarge) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "too_large", elapsedMillis = elapsedMillis(started)))
                return LocalGenerationResult.TooLarge
            }
            if (tokenCount == TokenCountOutcome.SdkFailure) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "failure", failure = "sdk_failure", elapsedMillis = elapsedMillis(started)))
                return LocalGenerationResult.Failed(NanoFailureKind.SDK_FAILURE)
            }
            val inputTokens = (tokenCount as TokenCountOutcome.Counted).tokens
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(
                    phase = "nano_token_preflight",
                    backend = "local",
                    inputTokens = inputTokens,
                    maxOutputTokens = request.maxOutputTokens,
                    tokenLimit = tokenLimit,
                ),
            )
            if (!NanoPromptPreflight.fits(inputTokens, request.maxOutputTokens, tokenLimit)) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "too_large", elapsedMillis = elapsedMillis(started)))
                LocalGenerationResult.TooLarge
            } else {
                val response = runOperation(OP_GENERATE) { generateWithBusyRetry(request) }
                val candidate = runOperation(OP_PROCESS_RESPONSE) { response.candidates.firstOrNull() }
                when {
                    candidate?.finishReason == com.google.mlkit.genai.prompt.Candidate.FinishReason.MAX_TOKENS -> {
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "too_large", elapsedMillis = elapsedMillis(started)))
                        LocalGenerationResult.TooLarge
                    }
                    candidate?.text.isNullOrBlank() || candidate?.finishReason == com.google.mlkit.genai.prompt.Candidate.FinishReason.OTHER -> {
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "failure", failure = "response_rejected", elapsedMillis = elapsedMillis(started)))
                        LocalGenerationResult.Failed(NanoFailureKind.RESPONSE_REJECTED)
                    }
                    else -> {
                        val modelName = baseModelName()
                        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "success", model = modelName, elapsedMillis = elapsedMillis(started)))
                        LocalGenerationResult.Success(candidate.text, modelName)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: com.google.mlkit.genai.common.GenAiException) {
            if (error.errorCode == com.google.mlkit.genai.common.GenAiException.ErrorCode.REQUEST_TOO_LARGE) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_generation_finished", backend = "local", outcome = "too_large", elapsedMillis = elapsedMillis(started)))
                return LocalGenerationResult.TooLarge
            }
            val failure = error.toNanoFailure()
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(
                    phase = "nano_generation_finished",
                    backend = "local",
                    outcome = "failure",
                    failure = failure.name.lowercase(),
                    exceptionType = diagnosticExceptionType(error),
                    genAiErrorCode = error.errorCode,
                    elapsedMillis = elapsedMillis(started),
                ),
            )
            if (failure == NanoFailureKind.BUSY || failure == NanoFailureKind.BACKGROUND_BLOCKED) {
                LocalGenerationResult.Retryable(failure)
            } else {
                LocalGenerationResult.Failed(failure)
            }
        } catch (error: Exception) {
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(
                    phase = "nano_generation_finished",
                    backend = "local",
                    outcome = "failure",
                    failure = "sdk_failure",
                    exceptionType = diagnosticExceptionType(error),
                    elapsedMillis = elapsedMillis(started),
                ),
            )
            LocalGenerationResult.Failed(NanoFailureKind.SDK_FAILURE)
        }
    }

    override suspend fun generateEvent(prompt: String, maxOutputTokens: Int): LocalGenerationResult {
        if (capability != NanoCapability.AVAILABLE && checkCapability() != NanoCapability.AVAILABLE) {
            return LocalGenerationResult.Failed(NanoFailureKind.NOT_AVAILABLE)
        }
        val structuredAvailable = try {
            runOperation(OP_STRUCTURED_AVAILABLE) { model.isStructuredOutputFeatureAvailable() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!structuredAvailable) return generate(prompt, maxOutputTokens)

        return try {
            ensureWarmup()
            val baseRequest = buildRequest(prompt, maxOutputTokens, OP_STRUCTURED_BUILD_REQUEST)
            val request = com.google.mlkit.genai.prompt.generateTypedContentRequest(
                generateContentRequest = baseRequest,
                outputClass = NanoEventOutput::class,
                includeSchemaInPrompt = true,
            )
            val tokenLimit = getTokenLimit(OP_TOKEN_LIMIT)
            val inputTokens = runOperation(OP_STRUCTURED_COUNT_TOKENS) { model.countTokens(request).totalTokens }
            if (!NanoPromptPreflight.fits(inputTokens, maxOutputTokens, tokenLimit)) {
                return LocalGenerationResult.TooLarge
            }
            val response = runOperation(OP_STRUCTURED_GENERATE) { model.generateContent(request) }
            val event = response.candidates.firstOrNull()?.response
                ?: return LocalGenerationResult.Failed(NanoFailureKind.RESPONSE_REJECTED)
            LocalGenerationResult.Success(
                text = Gson().toJson(event),
                modelName = baseModelName(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Alpha structured output may be unavailable on an otherwise Prompt-capable runtime.
            // The same strict prompt and downstream validator make raw JSON a safe fallback.
            generate(prompt, maxOutputTokens)
        }
    }

    override suspend fun baseModelName(): String? {
        if (capability != NanoCapability.AVAILABLE) return null
        return try {
            runOperation(OP_BASE_MODEL) { model.getBaseModelName() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun selfTest(): NanoSelfTestResult {
        return try {
            when (val current = checkCapability()) {
                NanoCapability.DOWNLOADABLE, NanoCapability.DOWNLOADING -> NanoSelfTestResult.RequiresDownload(current)
                NanoCapability.UNAVAILABLE -> NanoSelfTestResult.Unavailable
                NanoCapability.AVAILABLE -> {
                    ensureWarmup()
                    val modelName = baseModelName()
                    val request = buildRequest(SELF_TEST_PROMPT, SELF_TEST_OUTPUT_TOKENS, OP_SELF_TEST_BUILD_REQUEST)
                    val tokenLimit = getTokenLimit(OP_SELF_TEST_TOKEN_LIMIT)
                    val inputTokens = runOperation(OP_SELF_TEST_COUNT_TOKENS) { model.countTokens(request).totalTokens }
                    if (!NanoPromptPreflight.fits(inputTokens, request.maxOutputTokens, tokenLimit)) {
                        NanoSelfTestResult.Failure(NanoFailureKind.REQUEST_REJECTED)
                    } else {
                        val response = runOperation(OP_SELF_TEST_GENERATE) { generateWithBusyRetry(request) }
                        val candidate = runOperation(OP_SELF_TEST_PROCESS_RESPONSE) { response.candidates.firstOrNull() }
                        if (candidate?.text.isNullOrBlank()) NanoSelfTestResult.Failure(NanoFailureKind.RESPONSE_REJECTED)
                        else NanoSelfTestResult.Success(modelName, tokenLimit, inputTokens)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: com.google.mlkit.genai.common.GenAiException) {
            NanoSelfTestResult.Failure(error.toNanoFailure())
        } catch (_: Exception) {
            NanoSelfTestResult.Failure(NanoFailureKind.SDK_FAILURE)
        }
    }

    override suspend fun resetForRetry() {
        try {
            runOperation(OP_RECREATE_CLIENT) {
                runCatching { model.close() }
                model = modelFactory()
                capability = NanoCapability.UNAVAILABLE
                warmedUp = false
                tokenLimit = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            capability = NanoCapability.UNAVAILABLE
            warmedUp = false
            tokenLimit = null
        }
    }

    override fun close() {
        runCatching { model.close() }
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private suspend fun ensureWarmup() {
        if (warmedUp) return
        runOperation(OP_WARMUP) { model.warmup() }
        warmedUp = true
        diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "nano_warmup", operation = OP_WARMUP, backend = "local", outcome = "success"))
    }

    private suspend fun buildRequest(
        prompt: String,
        maxOutputTokens: Int,
        operation: String,
    ): com.google.mlkit.genai.prompt.GenerateContentRequest = runOperation(
        operation = operation,
        inputCharacters = prompt.length,
        inputWords = prompt.countWords(),
    ) {
        com.google.mlkit.genai.prompt.generateContentRequest(
            com.google.mlkit.genai.prompt.TextPart(prompt),
        ) {
            candidateCount = 1
            this.maxOutputTokens = maxOutputTokens
            seed = STABLE_SEED
            temperature = 0f
        }
    }

    // Serve from cache when possible but keep the diagnostic operation stream identical.
    private suspend fun getTokenLimit(operation: String): Int = runOperation(operation) {
        tokenLimit ?: model.getTokenLimit().also { tokenLimit = it }
    }

    private suspend fun countTokensWithProbe(
        request: com.google.mlkit.genai.prompt.GenerateContentRequest,
    ): TokenCountOutcome = try {
        TokenCountOutcome.Counted(runOperation(OP_COUNT_TOKENS) { model.countTokens(request).totalTokens })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: com.google.mlkit.genai.common.GenAiException) {
        throw error
    } catch (_: Exception) {
        when (resolveUnexpectedTokenCountFailure(tinyTokenProbeSucceeds())) {
            UnexpectedTokenCountResolution.TOO_LARGE -> TokenCountOutcome.TooLarge
            UnexpectedTokenCountResolution.SDK_FAILURE -> TokenCountOutcome.SdkFailure
        }
    }

    private suspend fun tinyTokenProbeSucceeds(): Boolean = try {
        val request = buildRequest(TOKEN_PROBE_PROMPT, TOKEN_PROBE_OUTPUT_TOKENS, OP_TOKEN_PROBE_BUILD_REQUEST)
        runOperation(OP_TOKEN_PROBE_COUNT_TOKENS) { model.countTokens(request).totalTokens }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun <T> runOperation(
        operation: String,
        inputCharacters: Int? = null,
        inputWords: Int? = null,
        block: suspend () -> T,
    ): T {
        diagnostics.record(
            diagnosticTraceId,
            ImportDiagnosticEvent(
                phase = "nano_operation",
                operation = operation,
                backend = "local",
                outcome = "started",
                inputCharacters = inputCharacters,
                inputWords = inputWords,
            ),
        )
        return try {
            val result = block()
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(phase = "nano_operation", operation = operation, backend = "local", outcome = "success"),
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(
                    phase = "nano_operation",
                    operation = operation,
                    backend = "local",
                    outcome = "failure",
                    exceptionType = diagnosticExceptionType(error),
                    genAiErrorCode = (error as? com.google.mlkit.genai.common.GenAiException)?.errorCode,
                ),
            )
            throw error
        }
    }

    private suspend fun generateWithBusyRetry(request: com.google.mlkit.genai.prompt.GenerateContentRequest): com.google.mlkit.genai.prompt.GenerateContentResponse {
        return retryNanoBusy(
            isBusy = { error ->
                error is com.google.mlkit.genai.common.GenAiException &&
                    error.errorCode == com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY
            },
            onRetry = { retry, _ ->
                diagnostics.record(
                    diagnosticTraceId,
                    ImportDiagnosticEvent(phase = "nano_busy_retry", backend = "local", failure = "busy", retryIndex = retry),
                )
            },
        ) { model.generateContent(request) }
    }

    private companion object {
        const val STABLE_SEED = 7
        const val SELF_TEST_OUTPUT_TOKENS = 16
        const val TOKEN_PROBE_OUTPUT_TOKENS = 8
        const val SELF_TEST_PROMPT = "Responde únicamente con la palabra OK."
        const val TOKEN_PROBE_PROMPT = "OK"
        const val OP_CHECK_STATUS = "check_status"
        const val OP_BASE_MODEL = "get_base_model_name"
        const val OP_DOWNLOAD = "download"
        const val OP_WARMUP = "warmup"
        const val OP_BUILD_REQUEST = "build_request"
        const val OP_TOKEN_LIMIT = "get_token_limit"
        const val OP_COUNT_TOKENS = "count_tokens"
        const val OP_GENERATE = "generate_content"
        const val OP_PROCESS_RESPONSE = "process_response"
        const val OP_STRUCTURED_AVAILABLE = "structured_output_available"
        const val OP_STRUCTURED_BUILD_REQUEST = "structured_output_build_request"
        const val OP_STRUCTURED_COUNT_TOKENS = "structured_output_count_tokens"
        const val OP_STRUCTURED_GENERATE = "structured_output_generate"
        const val OP_TOKEN_PROBE_BUILD_REQUEST = "token_probe_build_request"
        const val OP_TOKEN_PROBE_COUNT_TOKENS = "token_probe_count_tokens"
        const val OP_SELF_TEST_BUILD_REQUEST = "self_test_build_request"
        const val OP_SELF_TEST_TOKEN_LIMIT = "self_test_get_token_limit"
        const val OP_SELF_TEST_COUNT_TOKENS = "self_test_count_tokens"
        const val OP_SELF_TEST_GENERATE = "self_test_generate_content"
        const val OP_SELF_TEST_PROCESS_RESPONSE = "self_test_process_response"
        const val OP_RECREATE_CLIENT = "recreate_client"
    }

    private sealed interface TokenCountOutcome {
        data class Counted(val tokens: Int) : TokenCountOutcome
        data object TooLarge : TokenCountOutcome
        data object SdkFailure : TokenCountOutcome
    }
}

internal fun diagnosticExceptionType(error: Throwable): String = when (error) {
    is com.google.mlkit.genai.common.GenAiException -> "GenAiException"
    is IllegalArgumentException -> "IllegalArgumentException"
    is IllegalStateException -> "IllegalStateException"
    is SecurityException -> "SecurityException"
    is java.io.IOException -> "IOException"
    else -> "OtherException"
}

internal enum class UnexpectedTokenCountResolution { TOO_LARGE, SDK_FAILURE }

internal fun resolveUnexpectedTokenCountFailure(tinyProbeSucceeded: Boolean): UnexpectedTokenCountResolution =
    if (tinyProbeSucceeded) UnexpectedTokenCountResolution.TOO_LARGE else UnexpectedTokenCountResolution.SDK_FAILURE

private fun String.countWords(): Int = trim().takeIf(String::isNotEmpty)?.split(Regex("\\s+")).orEmpty().size

internal val NANO_BUSY_RETRY_DELAYS_MS = longArrayOf(500, 1_000, 2_000)

internal suspend fun <T> retryNanoBusy(
    delaysMs: LongArray = NANO_BUSY_RETRY_DELAYS_MS,
    isBusy: (Exception) -> Boolean,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    onRetry: suspend (retryIndex: Int, delayMillis: Long) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    var retry = 0
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isBusy(error) || retry >= delaysMs.size) throw error
            val delayMillis = delaysMs[retry]
            retry++
            onRetry(retry, delayMillis)
            sleeper(delayMillis)
        }
    }
}

internal fun com.google.mlkit.genai.common.GenAiException.toNanoFailure(): NanoFailureKind = nanoFailureForErrorCode(errorCode)

internal fun nanoFailureForErrorCode(errorCode: Int): NanoFailureKind = when (errorCode) {
    com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY -> NanoFailureKind.BUSY
    com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> NanoFailureKind.BACKGROUND_BLOCKED
    com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> NanoFailureKind.BATTERY_QUOTA
    com.google.mlkit.genai.common.GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> NanoFailureKind.NOT_ENOUGH_STORAGE
    com.google.mlkit.genai.common.GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> NanoFailureKind.SYSTEM_UPDATE_REQUIRED
    com.google.mlkit.genai.common.GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> NanoFailureKind.AICORE_INCOMPATIBLE
    com.google.mlkit.genai.common.GenAiException.ErrorCode.NOT_AVAILABLE -> NanoFailureKind.NOT_AVAILABLE
    com.google.mlkit.genai.common.GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
    com.google.mlkit.genai.common.GenAiException.ErrorCode.REQUEST_TOO_LARGE,
    com.google.mlkit.genai.common.GenAiException.ErrorCode.REQUEST_TOO_SMALL,
    com.google.mlkit.genai.common.GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> NanoFailureKind.REQUEST_REJECTED
    com.google.mlkit.genai.common.GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
    com.google.mlkit.genai.common.GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
    com.google.mlkit.genai.common.GenAiException.ErrorCode.CACHE_PROCESSING_ERROR -> NanoFailureKind.RESPONSE_REJECTED
    com.google.mlkit.genai.common.GenAiException.ErrorCode.CANCELLED -> NanoFailureKind.CANCELLED
    else -> NanoFailureKind.UNKNOWN
}

object NanoPromptPreflight {
    fun fits(inputTokens: Int, maxOutputTokens: Int, tokenLimit: Int, inputLimit: Int = 4_000): Boolean =
        inputTokens in 0 until inputLimit && maxOutputTokens > 0 && tokenLimit > 0 && inputTokens + maxOutputTokens <= tokenLimit
}

object LocalPromptSplitter {
    private val pageMarker = Regex("\\[Página (\\d+)]")

    fun split(prompt: String, overlapWords: Int = 80, minimumWords: Int = 64): List<String>? {
        val words = prompt.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.size < minimumWords * 2) return null

        val midpoint = words.size / 2
        val pageBoundaries = words.indices.filter { words[it] == "[Página" }
        val boundary = pageBoundaries.minByOrNull { kotlin.math.abs(it - midpoint) }
            ?.takeIf { it >= minimumWords && words.size - it >= minimumWords }
        val splitAt = boundary ?: midpoint
        val leftEnd = (splitAt + overlapWords / 2).coerceAtMost(words.size)
        val rightStart = (splitAt - overlapWords / 2).coerceAtLeast(0)
        if (leftEnd >= words.size || rightStart <= 0) return null

        val left = words.subList(0, leftEnd).joinToString(" ")
        var right = words.subList(rightStart, words.size).joinToString(" ")
        if (!right.startsWith("[Página ")) {
            pageMarker.findAll(words.subList(0, rightStart).joinToString(" ")).lastOrNull()?.groupValues?.get(1)?.let { page ->
                right = "[Página $page] $right"
            }
        }
        return listOf(left, right).takeIf { parts -> parts.all { it.isNotBlank() && it.length < prompt.length } }
    }
}

sealed class CloudResult { data class Success(val text: String, val model: String? = null) : CloudResult(); data class Failure(val kind: CloudFailure) : CloudResult() }
enum class CloudFailure { MISSING_KEY, INVALID_KEY, QUOTA, OFFLINE, SERVER, INVALID_RESPONSE }
enum class AiGenerationProfile(
    val maxOutputTokens: Int,
    val structuredOutput: Boolean,
) {
    /** Syllabus import: strict JSON plus the complete syllabus schema. */
    SYLLABUS_IMPORT(maxOutputTokens = IMPORT_MAX_OUTPUT_TOKENS, structuredOutput = true),
    /** Short conversational reply; deliberately carries no importer instructions or schema. */
    CHAT_ANSWER(maxOutputTokens = CHAT_MAX_OUTPUT_TOKENS, structuredOutput = false),
    /** Short event-oriented reply for callers that do not need a syllabus document schema. */
    CHAT_EVENT(maxOutputTokens = EVENT_MAX_OUTPUT_TOKENS, structuredOutput = true),
}

interface CloudEventEngine {
    val modelsUsed: Set<String> get() = emptySet()
    suspend fun generate(prompt: String): CloudResult
}
sealed class ExtractionOutcome {
    data class Drafts(val drafts: List<CalendarEventDraft>, val syllabus: ExtractedSyllabus? = null, val modelsUsed: Set<String> = emptySet()) : ExtractionOutcome()
    data class Manual(val reason: String) : ExtractionOutcome()
    data class NeedsDownload(val capability: NanoCapability) : ExtractionOutcome()
    data class NeedsConsent(val importId: String) : ExtractionOutcome()
    data class LocalFailed(val failure: NanoFailureKind, val retryable: Boolean) : ExtractionOutcome()
    data class CloudFailed(val failure: CloudFailure) : ExtractionOutcome()
}

class EventExtractionEngine(
    private val parser: StrictJsonAiParser = StrictJsonAiParser(),
    private val local: LocalPromptEngine? = null,
    private val cloud: CloudEventEngine? = null,
    private val consent: CloudConsent? = null,
    private val diagnostics: ImportDiagnosticsRecorder = NoOpImportDiagnostics,
    private val diagnosticTraceId: String? = null,
) {
    suspend fun extract(
        importId: String,
        chunks: List<String>,
        existing: List<CalendarEventDraft> = emptyList(),
        assumedInstitution: Institution? = null,
        onProgress: (partsDone: Int, partsTotal: Int) -> Unit = { _, _ -> },
    ): ExtractionOutcome {
        val rawChunks = chunks.map { SyllabusPrompt.institutionLine(assumedInstitution) + it }
        onProgress(0, rawChunks.size)
        val engine = local
        if (engine != null) {
            val capability = try {
                engine.checkCapability()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.google.mlkit.genai.common.GenAiException) {
                val failure = error.toNanoFailure()
                diagnostics.record(
                    diagnosticTraceId,
                    ImportDiagnosticEvent(phase = "nano_capability", backend = "local", outcome = "failure", failure = failure.name.lowercase()),
                )
                localFallback(importId, failure, retryable = failure == NanoFailureKind.BUSY)?.let { return it }
                NanoCapability.UNAVAILABLE
            } catch (_: Exception) {
                diagnostics.record(
                    diagnosticTraceId,
                    ImportDiagnosticEvent(phase = "nano_capability", backend = "local", outcome = "failure", failure = "sdk_failure"),
                )
                localFallback(importId, NanoFailureKind.SDK_FAILURE, retryable = true)?.let { return it }
                NanoCapability.UNAVAILABLE
            }
            when (capability) {
                NanoCapability.DOWNLOADABLE, NanoCapability.DOWNLOADING -> return ExtractionOutcome.NeedsDownload(capability)
                NanoCapability.AVAILABLE -> {
                    when (val localResult = extractLocally(rawChunks, engine, ProgressCounter(rawChunks.size, onProgress))) {
                        is LocalExtraction.Success -> if (localResult.parse.drafts.isNotEmpty() || localResult.parse.syllabus.canExpandClasses) {
                            return outcome(localResult.parse, existing, assumedInstitution, localResult.modelsUsed)
                        } else {
                            localFallback(importId, NanoFailureKind.RESPONSE_REJECTED, retryable = false)?.let { return it }
                        }
                        is LocalExtraction.Failed -> {
                            // Once the user already granted cloud consent, retry the complete set
                            // of chunks in the cloud so partial local output is never mixed in.
                            localFallback(importId, localResult.failure, localResult.retryable)?.let { return it }
                        }
                    }
                }
                NanoCapability.UNAVAILABLE -> Unit
            }
        }
        if (cloud == null) return ExtractionOutcome.Manual("No hay un procesador de IA habilitado para continuar")
        when (consent?.decisionForImport(importId) ?: ConsentDecision.PENDING) {
            ConsentDecision.PENDING -> return ExtractionOutcome.NeedsConsent(importId)
            ConsentDecision.DENIED -> return ExtractionOutcome.Manual("Cloud consent cancelled")
            ConsentDecision.GRANTED -> Unit
        }
        val cloudEngine = cloud
        onProgress(0, rawChunks.size)
        val results = mutableListOf<CloudResult>()
        rawChunks.forEachIndexed { index, rawChunk ->
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(phase = "cloud_chunk_started", backend = "cloud", chunkIndex = index + 1, chunkCount = rawChunks.size),
            )
            // Apply the importer contract here, not inside a backend. This keeps local generation
            // raw and guarantees every cloud chunk receives the same contract.
            results += cloudEngine.generate(SyllabusPrompt.localRequest(rawChunk))
            onProgress(index + 1, rawChunks.size)
        }
        results.filterIsInstance<CloudResult.Failure>().firstOrNull()?.let { return ExtractionOutcome.CloudFailed(it.kind) }
        val parses = results.filterIsInstance<CloudResult.Success>().map { result ->
            runCatching { parser.parseSyllabus(result.text) }.getOrNull()
                ?: return ExtractionOutcome.Manual("Respuesta estructurada inválida")
        }
        val merged = merge(parses)
        val modelsUsed = results.filterIsInstance<CloudResult.Success>().mapNotNull(CloudResult.Success::model).toSet()
        return if (merged.drafts.isEmpty() && !merged.syllabus.canExpandClasses) ExtractionOutcome.Manual("Respuesta estructurada inválida") else outcome(merged, existing, assumedInstitution, modelsUsed)
    }

    private fun localFallback(importId: String, failure: NanoFailureKind, retryable: Boolean): ExtractionOutcome? {
        if (cloud == null) return ExtractionOutcome.LocalFailed(failure, retryable)
        return when (consent?.decisionForImport(importId) ?: ConsentDecision.PENDING) {
            ConsentDecision.PENDING -> ExtractionOutcome.NeedsConsent(importId)
            ConsentDecision.DENIED -> ExtractionOutcome.Manual("Cloud consent cancelled")
            ConsentDecision.GRANTED -> null
        }
    }

    private fun outcome(parse: SyllabusParse, existing: List<CalendarEventDraft>, assumedInstitution: Institution?, modelsUsed: Set<String> = emptySet()): ExtractionOutcome.Drafts {
        val syllabus = if (assumedInstitution != null) parse.syllabus.copy(institution = assumedInstitution) else parse.syllabus
        val drafts = parse.drafts.map { draft -> if (assumedInstitution != null) draft.copy(institution = assumedInstitution) else draft }
        return ExtractionOutcome.Drafts(DuplicateDetector.mark(drafts, existing), syllabus, modelsUsed)
    }

    private fun merge(parses: List<SyllabusParse>): SyllabusParse = SyllabusParse(
        syllabus = ExtractedSyllabus(
            course = parses.firstNotNullOfOrNull { it.syllabus.course },
            institution = parses.firstNotNullOfOrNull { it.syllabus.institution },
            groups = parses.flatMap { it.syllabus.groups }.distinctBy { it.label.trim().lowercase() },
            // Chunks overlap, so the same week is re-extracted with the same index; collapse those.
            // Weeks that carry no index can't be safely deduped by content, so keep only exact copies.
            weeks = parses.flatMap { it.syllabus.weeks }.let { all ->
                val (indexed, unindexed) = all.partition { it.index != null }
                indexed.distinctBy { it.index } + unindexed.distinct()
            },
            holidays = parses.flatMap { it.syllabus.holidays }.distinct(),
        ),
        drafts = parses.flatMap { it.drafts }.distinctBy { it.id },
    )

    private class ProgressCounter(
        private var partsTotal: Int,
        private val onProgress: (partsDone: Int, partsTotal: Int) -> Unit,
    ) {
        private var partsDone = 0

        fun split() {
            partsTotal++
            onProgress(partsDone, partsTotal)
        }

        fun complete() {
            partsDone++
            onProgress(partsDone, partsTotal)
        }
    }

    private sealed interface LocalExtraction {
        data class Success(val parse: SyllabusParse, val modelsUsed: Set<String>) : LocalExtraction
        data class Failed(val failure: NanoFailureKind, val retryable: Boolean) : LocalExtraction
    }

    private sealed interface LocalParts {
        data class Success(val responses: List<LocalGenerationResult.Success>) : LocalParts
        data class Failed(val failure: NanoFailureKind, val retryable: Boolean) : LocalParts
    }

    private suspend fun extractLocally(
        chunks: List<String>,
        engine: LocalPromptEngine,
        progress: ProgressCounter,
    ): LocalExtraction {
        val parses = mutableListOf<SyllabusParse>()
        val models = mutableSetOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(phase = "local_chunk_started", backend = "local", chunkIndex = index + 1, chunkCount = chunks.size),
            )
            val responses = when (val parts = generateLocalParts(chunk, engine, progress, chunkIndex = index + 1, chunkCount = chunks.size)) {
                is LocalParts.Success -> parts.responses
                is LocalParts.Failed -> return LocalExtraction.Failed(parts.failure, parts.retryable)
            }
            for (response in responses) {
                val parsed = runCatching { parser.parseSyllabus(response.text) }.getOrNull()
                    ?: return LocalExtraction.Failed(NanoFailureKind.RESPONSE_REJECTED, retryable = false)
                parses += parsed
                models += response.modelName?.let { "gemini-nano/$it" } ?: "gemini-nano"
            }
        }
        return LocalExtraction.Success(merge(parses), models)
    }

    private suspend fun generateLocalParts(
        prompt: String,
        engine: LocalPromptEngine,
        progress: ProgressCounter,
        depth: Int = 0,
        chunkIndex: Int? = null,
        chunkCount: Int? = null,
    ): LocalParts {
        // `prompt` is always a raw document chunk. Rebuild the full contract for every split
        // retry so neither backend can accidentally inherit a truncated instruction prefix.
        return when (val result = engine.generate(SyllabusPrompt.localRequest(prompt), IMPORT_MAX_OUTPUT_TOKENS)) {
            is LocalGenerationResult.Success -> {
                progress.complete()
                LocalParts.Success(listOf(result))
            }
            is LocalGenerationResult.Retryable -> LocalParts.Failed(result.failure, retryable = true)
            is LocalGenerationResult.Failed -> LocalParts.Failed(result.failure, retryable = false)
            LocalGenerationResult.Failure -> LocalParts.Failed(NanoFailureKind.UNKNOWN, retryable = false)
            LocalGenerationResult.TooLarge -> {
                if (depth >= MAX_SPLIT_DEPTH) return LocalParts.Failed(NanoFailureKind.REQUEST_REJECTED, retryable = false)
                val parts = LocalPromptSplitter.split(prompt)
                    ?: return LocalParts.Failed(NanoFailureKind.REQUEST_REJECTED, retryable = false)
                diagnostics.record(
                    diagnosticTraceId,
                    ImportDiagnosticEvent(
                        phase = "local_chunk_split",
                        backend = "local",
                        chunkIndex = chunkIndex,
                        chunkCount = chunkCount,
                        splitDepth = depth + 1,
                    ),
                )
                progress.split()
                val responses = mutableListOf<LocalGenerationResult.Success>()
                for (part in parts) {
                    when (val nested = generateLocalParts(part, engine, progress, depth + 1, chunkIndex, chunkCount)) {
                        is LocalParts.Success -> responses += nested.responses
                        is LocalParts.Failed -> return nested
                    }
                }
                LocalParts.Success(responses)
            }
        }
    }

    private companion object {
        const val MAX_SPLIT_DEPTH = 8
    }
}

class GeminiClient(private val apiKey: String, val model: String = CLOUD_MODEL) {
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    fun requestBody(
        prompt: String,
        profile: AiGenerationProfile = AiGenerationProfile.SYLLABUS_IMPORT,
        structured: Boolean = profile.structuredOutput,
    ): String = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", prompt)
                    })
                })
            })
        })
        add("generationConfig", JsonObject().apply {
            add("thinkingConfig", JsonObject().apply {
                if (model.startsWith("gemini-2.5")) addProperty("thinkingBudget", 0)
                else addProperty("thinkingLevel", "LOW")
            })
            addProperty("maxOutputTokens", profile.maxOutputTokens)
            if (structured) {
                addProperty("responseMimeType", "application/json")
                add(
                    "responseJsonSchema",
                    when (profile) {
                        AiGenerationProfile.SYLLABUS_IMPORT -> eventResponseSchema()
                        AiGenerationProfile.CHAT_EVENT -> chatEventResponseSchema()
                        AiGenerationProfile.CHAT_ANSWER -> error("Chat answers are not structured")
                    },
                )
            }
        })
    }.toString()

    fun authorizationHeader() = "x-goog-api-key" to apiKey

    internal fun eventResponseSchema(): JsonObject {
        // Nested values are optional by omission; only root course is explicitly nullable.
        fun optional(type: String, format: String? = null) = JsonObject().apply {
            addProperty("type", type)
            format?.let { addProperty("format", it) }
        }
        val properties = JsonObject().apply {
            add("date", optional("string", "date"))
            add("start", optional("string", "time"))
            add("end", optional("string", "time"))
            add("originalDateText", optional("string"))
            add("title", optional("string"))
            add("description", optional("string"))
            add("category", optional("string").apply {
                add("enum", JsonArray().apply { listOf("EXAM", "QUIZ", "TAREA", "ACTIVITY", "OTHER").forEach(::add) })
            })
            add("institution", optional("string"))
            add("location", optional("string"))
            add("courseHint", optional("string"))
            add("sourcePage", optional("integer"))
            add("evidence", optional("string"))
            add("ambiguousDate", JsonObject().apply { addProperty("type", "boolean") })
            add("inferredYear", JsonObject().apply { addProperty("type", "boolean") })
        }
        fun objectSchema(fields: JsonObject): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", fields)
        }
        fun arraySchema(items: JsonObject): JsonObject = JsonObject().apply {
            addProperty("type", "array")
            add("items", items)
        }
        val courseSchema = objectSchema(JsonObject().apply {
            add("name", optional("string"))
            add("code", optional("string"))
            add("institution", optional("string"))
            add("period", optional("string"))
        })
        val groupSchema = objectSchema(JsonObject().apply {
            add("label", optional("string"))
            add("days", arraySchema(JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO").forEach(::add) })
            }))
            add("start", optional("string", "time"))
            add("end", optional("string", "time"))
            add("instructor", optional("string"))
        })
        val weekSchema = objectSchema(JsonObject().apply {
            add("week", optional("integer"))
            add("from", optional("string", "date"))
            add("to", optional("string", "date"))
            add("topic", optional("string"))
        })
        val holidaySchema = objectSchema(JsonObject().apply {
            add("date", optional("string", "date"))
            add("title", optional("string"))
        })
        return JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply {
                add("course", JsonObject().apply {
                    add("anyOf", JsonArray().apply { add(courseSchema); add(JsonObject().apply { addProperty("type", "null") }) })
                })
                add("groups", arraySchema(groupSchema))
                add("weeks", arraySchema(weekSchema))
                add("holidays", arraySchema(holidaySchema))
                add("events", arraySchema(objectSchema(properties)))
            })
            add("required", JsonArray().apply { listOf("course", "groups", "weeks", "holidays", "events").forEach(::add) })
        }
    }

    internal fun chatEventResponseSchema(): JsonObject {
        fun string(format: String? = null) = JsonObject().apply {
            addProperty("type", "string")
            format?.let { addProperty("format", it) }
        }
        return JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply {
                add("title", string())
                add("institution", string())
                add("kind", string().apply {
                    add("enum", JsonArray().apply {
                        listOf("CLASS", "EXAM", "QUIZ", "TAREA", "ACTIVITY", "TRANSIT").forEach(::add)
                    })
                })
                add("date", string("date"))
                add("start", string("time"))
                add("end", string("time"))
                add("location", string())
                add("notes", string())
            })
        }
    }

    companion object {
        const val CLOUD_MODEL = "gemini-3.5-flash"
    }
}
