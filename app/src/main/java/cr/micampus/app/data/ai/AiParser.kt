package cr.micampus.app.data.ai

import com.google.gson.*
import cr.micampus.app.core.model.*
import kotlinx.coroutines.Dispatchers
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

object SyllabusPrompt {
    const val RULES =
        "Analiza el documento de un curso universitario (carta al estudiante, programa o cronograma). Extrae: " +
            "(1) course: nombre, código (p. ej. EIF200), institution (UCR o UNA) y period; " +
            "(2) groups: cada grupo de la tabla de horario del curso, con label, days como nombres completos en mayúsculas (LUNES, MARTES, MIERCOLES, JUEVES, VIERNES, SABADO, DOMINGO), start y end en formato HH:MM de 24 horas, e instructor; " +
            "(3) weeks: cada fila del cronograma semanal con week, from y to (AAAA-MM-DD) y topic con el contenido o tema que se enseña esa semana; " +
            "(4) holidays: feriados y días sin lecciones, un elemento por fecha (incluye cada día de Semana Santa). " +
            "Si el tema de una semana menciona un feriado en una fecha (p. ej. \"Viernes 11 de abril: Feriado\"), agrega esa fecha aquí y deja el topic solo con el contenido académico. Cada holiday con date y title; " +
            "(5) events: SOLO eventos con fecha propia: exámenes, pruebas, quices, tareas y entregas, con category (CLASS, EXAM, QUIZ, TAREA, ACTIVITY u OTHER). No incluyas las clases regulares en events. Para cada evento usa title corto (nombre breve del evento) y description con el detalle si existe. " +
            "Nunca inventes valores; usa null cuando falten. Conserva el texto original de fechas ambiguas y evidencia breve con su página."
    const val SHAPE =
        "Devuelve SOLO JSON con la forma {course:{name,code,institution,period}," +
            "groups:[{label,days,start,end,instructor}],weeks:[{week,from,to,topic}],holidays:[{date,title}]," +
            "events:[{date,start,end,originalDateText,title,description,category,institution,location,courseHint,sourcePage,evidence,ambiguousDate,inferredYear}]}. "
    fun institutionLine(institution: Institution?): String =
        institution?.let { "El estudiante solo pertenece a la institución ${it.name}; asume que todo el documento corresponde a ${it.name}.\n" }.orEmpty()
}

class StrictJsonAiParser : AiScheduleParser {
    override fun parseStrictJson(json: String): AiParseResult = runCatching {
        val drafts = parseDrafts(json)
        AiParseResult(emptyList(), listOf("Borrador estructurado pendiente de confirmación"), false, drafts)
    }.getOrElse { AiParseResult(emptyList(), listOf("Respuesta de IA inválida"), false) }

    fun parseSyllabus(json: String, now: LocalDate = LocalDate.now()): SyllabusParse {
        val root = JsonParser.parseString(json).asJsonObject
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
        return SyllabusParse(syllabus, parseDrafts(json, now))
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
        val root = JsonParser.parseString(json).asJsonObject
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
            if (isWeekBreak(rawTopic)) return@flatMap emptyList()
            val topic = cleanTitle(rawTopic)
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
    private fun isWeekBreak(topic: String): Boolean {
        val core = topic.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^a-záéíóúñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return weekBreakMarkers.any { core == it || core.startsWith("$it ") } && core.split(" ").size <= 5
    }

    // Strip a trailing feriado/exam/date announcement from the topic; never return blank (keeping the
    // week matters more than a perfectly clean title).
    private fun cleanTitle(topic: String): String =
        topic.replace(trailingAnnouncement, "").trim().trimEnd('.', ',', ';', ':').trim().ifBlank { topic }
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
sealed interface LocalGenerationResult {
    data class Success(val text: String) : LocalGenerationResult
    data object TooLarge : LocalGenerationResult
    data object Failure : LocalGenerationResult
}
interface LocalEventEngine {
    suspend fun checkCapability(): NanoCapability
    suspend fun generate(prompt: String): LocalGenerationResult
}

class MlKitNanoEngine(private val model: com.google.mlkit.genai.prompt.GenerativeModel = com.google.mlkit.genai.prompt.Generation.getClient()) : LocalEventEngine {
    private var capability: NanoCapability = NanoCapability.UNAVAILABLE
    override suspend fun checkCapability(): NanoCapability { capability = when (model.checkStatus()) { com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> NanoCapability.AVAILABLE; com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> NanoCapability.DOWNLOADABLE; com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> NanoCapability.DOWNLOADING; else -> NanoCapability.UNAVAILABLE }; return capability }
    suspend fun download(onProgress: (Long) -> Unit = {}): NanoCapability { model.download().collect { status -> when (status) { is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> capability = NanoCapability.DOWNLOADING; is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> { capability = NanoCapability.DOWNLOADING; onProgress(status.totalBytesDownloaded) }; com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted -> capability = NanoCapability.AVAILABLE; is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> capability = NanoCapability.UNAVAILABLE } }; return capability }
    override suspend fun generate(prompt: String): LocalGenerationResult {
        if (capability != NanoCapability.AVAILABLE && checkCapability() != NanoCapability.AVAILABLE) {
            return LocalGenerationResult.Failure
        }
        return runCatching {
            val completePrompt = localExtractionPrompt(prompt)
            val request = com.google.mlkit.genai.prompt.generateContentRequest(
                com.google.mlkit.genai.prompt.TextPart(completePrompt),
            ) {
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            val inputTokens = model.countTokens(request).totalTokens
            val tokenLimit = model.getTokenLimit()
            if (!NanoPromptPreflight.fits(inputTokens, request.maxOutputTokens, tokenLimit)) {
                LocalGenerationResult.TooLarge
            } else {
                model.generateContent(request).candidates.firstOrNull()?.text
                    ?.let(LocalGenerationResult::Success)
                    ?: LocalGenerationResult.Failure
            }
        }.getOrDefault(LocalGenerationResult.Failure)
    }
    fun close() = model.close()

    private fun localExtractionPrompt(documentChunk: String) =
        SyllabusPrompt.SHAPE + SyllabusPrompt.RULES + "\n" + documentChunk

    private companion object {
        const val MAX_OUTPUT_TOKENS = 2_048
    }
}

object NanoPromptPreflight {
    fun fits(inputTokens: Int, maxOutputTokens: Int, tokenLimit: Int): Boolean =
        inputTokens >= 0 && maxOutputTokens > 0 && tokenLimit > 0 && inputTokens + maxOutputTokens <= tokenLimit
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

sealed class CloudResult { data class Success(val text: String) : CloudResult(); data class Failure(val kind: CloudFailure) : CloudResult() }
enum class CloudFailure { MISSING_KEY, INVALID_KEY, QUOTA, OFFLINE, SERVER, INVALID_RESPONSE }
interface CloudEventEngine { suspend fun generate(prompt: String): CloudResult }
sealed class ExtractionOutcome { data class Drafts(val drafts: List<CalendarEventDraft>, val syllabus: ExtractedSyllabus? = null) : ExtractionOutcome(); data class Manual(val reason: String) : ExtractionOutcome(); data class NeedsDownload(val capability: NanoCapability) : ExtractionOutcome(); data class NeedsConsent(val importId: String) : ExtractionOutcome(); data class CloudFailed(val failure: CloudFailure) : ExtractionOutcome() }

class EventExtractionEngine(private val parser: StrictJsonAiParser = StrictJsonAiParser(), private val local: LocalEventEngine? = null, private val cloud: CloudEventEngine? = null, private val consent: CloudConsent? = null) {
    suspend fun extract(importId: String, chunks: List<String>, existing: List<CalendarEventDraft> = emptyList(), assumedInstitution: Institution? = null): ExtractionOutcome {
        val prompts = chunks.map { SyllabusPrompt.institutionLine(assumedInstitution) + it }
        val engine = local
        if (engine != null) {
            when (val capability = engine.checkCapability()) {
                NanoCapability.DOWNLOADABLE, NanoCapability.DOWNLOADING -> return ExtractionOutcome.NeedsDownload(capability)
                NanoCapability.AVAILABLE -> {
                    extractLocally(prompts, engine)?.takeIf { it.drafts.isNotEmpty() || it.syllabus.canExpandClasses }?.let { parse ->
                        return outcome(parse, existing, assumedInstitution)
                    }
                }
                NanoCapability.UNAVAILABLE -> Unit
            }
        }
        when (consent?.decisionForImport(importId) ?: ConsentDecision.PENDING) {
            ConsentDecision.PENDING -> return ExtractionOutcome.NeedsConsent(importId)
            ConsentDecision.DENIED -> return ExtractionOutcome.Manual("Cloud consent cancelled")
            ConsentDecision.GRANTED -> Unit
        }
        val cloudEngine = cloud ?: return ExtractionOutcome.Manual("Cloud engine unavailable")
        val results = prompts.map { cloudEngine.generate(it) }
        results.filterIsInstance<CloudResult.Failure>().firstOrNull()?.let { return ExtractionOutcome.CloudFailed(it.kind) }
        val parses = results.filterIsInstance<CloudResult.Success>().map { result ->
            runCatching { parser.parseSyllabus(result.text) }.getOrNull()
                ?: return ExtractionOutcome.Manual("Respuesta estructurada inválida")
        }
        val merged = merge(parses)
        return if (merged.drafts.isEmpty() && !merged.syllabus.canExpandClasses) ExtractionOutcome.Manual("Respuesta estructurada inválida") else outcome(merged, existing, assumedInstitution)
    }

    private fun outcome(parse: SyllabusParse, existing: List<CalendarEventDraft>, assumedInstitution: Institution?): ExtractionOutcome.Drafts {
        val syllabus = if (assumedInstitution != null) parse.syllabus.copy(institution = assumedInstitution) else parse.syllabus
        val drafts = parse.drafts.map { draft -> if (assumedInstitution != null) draft.copy(institution = assumedInstitution) else draft }
        return ExtractionOutcome.Drafts(DuplicateDetector.mark(drafts, existing), syllabus)
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

    private suspend fun extractLocally(chunks: List<String>, engine: LocalEventEngine): SyllabusParse? {
        val parses = mutableListOf<SyllabusParse>()
        for (chunk in chunks) {
            val responses = generateLocalParts(chunk, engine) ?: return null
            for (response in responses) {
                val parsed = runCatching { parser.parseSyllabus(response) }.getOrNull() ?: return null
                parses += parsed
            }
        }
        return merge(parses)
    }

    private suspend fun generateLocalParts(
        prompt: String,
        engine: LocalEventEngine,
        depth: Int = 0,
    ): List<String>? {
        return when (val result = engine.generate(prompt)) {
            is LocalGenerationResult.Success -> listOf(result.text)
            LocalGenerationResult.Failure -> null
            LocalGenerationResult.TooLarge -> {
                if (depth >= MAX_SPLIT_DEPTH) return null
                val parts = LocalPromptSplitter.split(prompt) ?: return null
                val responses = mutableListOf<String>()
                for (part in parts) {
                    responses += generateLocalParts(part, engine, depth + 1) ?: return null
                }
                responses
            }
        }
    }

    private companion object {
        const val MAX_SPLIT_DEPTH = 8
    }
}

class GeminiClient(private val apiKey: String, val model: String = CLOUD_MODEL) {
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    fun requestBody(prompt: String, structured: Boolean = true): String = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", SyllabusPrompt.RULES + "\n" + prompt)
                    })
                })
            })
        })
        add("generationConfig", JsonObject().apply {
            add("thinkingConfig", JsonObject().apply { addProperty("thinkingLevel", "LOW") })
            if (structured) {
                addProperty("responseMimeType", "application/json")
                add("responseJsonSchema", eventResponseSchema())
            }
        })
        if (!structured) {
            getAsJsonArray("contents").first().asJsonObject.getAsJsonArray("parts").first().asJsonObject.addProperty(
                "text",
                SyllabusPrompt.SHAPE + SyllabusPrompt.RULES + "\n" + prompt,
            )
        }
    }.toString()

    fun authorizationHeader() = "x-goog-api-key" to apiKey

    private fun eventResponseSchema(): JsonObject {
        fun nullable(type: String, format: String? = null) = JsonObject().apply {
            add("type", JsonArray().apply { add(type); add("null") })
            format?.let { addProperty("format", it) }
        }
        val properties = JsonObject().apply {
            add("date", nullable("string", "date"))
            add("start", nullable("string", "time"))
            add("end", nullable("string", "time"))
            add("originalDateText", nullable("string"))
            add("title", nullable("string"))
            add("description", nullable("string"))
            add("category", nullable("string"))
            add("institution", nullable("string"))
            add("location", nullable("string"))
            add("courseHint", nullable("string"))
            add("sourcePage", nullable("integer"))
            add("evidence", nullable("string"))
            add("ambiguousDate", JsonObject().apply { addProperty("type", "boolean") })
            add("inferredYear", JsonObject().apply { addProperty("type", "boolean") })
        }
        fun objectSchema(fields: JsonObject): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", fields)
            add("required", JsonArray().apply { fields.keySet().forEach(::add) })
        }
        fun arraySchema(items: JsonObject): JsonObject = JsonObject().apply {
            addProperty("type", "array")
            add("items", items)
        }
        val courseSchema = objectSchema(JsonObject().apply {
            add("name", nullable("string"))
            add("code", nullable("string"))
            add("institution", nullable("string"))
            add("period", nullable("string"))
        })
        val groupSchema = objectSchema(JsonObject().apply {
            add("label", nullable("string"))
            add("days", arraySchema(JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO").forEach(::add) })
            }))
            add("start", nullable("string", "time"))
            add("end", nullable("string", "time"))
            add("instructor", nullable("string"))
        })
        val weekSchema = objectSchema(JsonObject().apply {
            add("week", nullable("integer"))
            add("from", nullable("string", "date"))
            add("to", nullable("string", "date"))
            add("topic", nullable("string"))
        })
        val holidaySchema = objectSchema(JsonObject().apply {
            add("date", nullable("string", "date"))
            add("title", nullable("string"))
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

    companion object {
        const val CLOUD_MODEL = "gemini-3.5-flash"
    }
}
