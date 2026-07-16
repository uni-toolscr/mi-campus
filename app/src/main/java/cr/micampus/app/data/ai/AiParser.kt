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

class StrictJsonAiParser : AiScheduleParser {
    override fun parseStrictJson(json: String): AiParseResult = runCatching {
        val drafts = parseDrafts(json)
        AiParseResult(emptyList(), listOf("Borrador estructurado pendiente de confirmación"), false, drafts)
    }.getOrElse { AiParseResult(emptyList(), listOf("Respuesta de IA inválida"), false) }

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
                evidence = Evidence(sourcePage, string("evidence")), issues = issues, originalDateText = originalDate
            )
        }
    }

    private fun stableId(vararg values: String?): String = MessageDigest.getInstance("SHA-256").digest(values.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
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
        "Devuelve SOLO JSON con la forma " +
            "{events:[{date,start,end,originalDateText,title,category,institution,location,courseHint,sourcePage,evidence,ambiguousDate,inferredYear}]}. " +
            "Extrae únicamente eventos académicos. Nunca inventes valores; usa null cuando falten. " +
            "Conserva el texto original de fechas ambiguas y evidencia breve con su página.\n$documentChunk"

    private companion object {
        const val MAX_OUTPUT_TOKENS = 1_024
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
sealed class ExtractionOutcome { data class Drafts(val drafts: List<CalendarEventDraft>) : ExtractionOutcome(); data class Manual(val reason: String) : ExtractionOutcome(); data class NeedsDownload(val capability: NanoCapability) : ExtractionOutcome(); data class NeedsConsent(val importId: String) : ExtractionOutcome(); data class CloudFailed(val failure: CloudFailure) : ExtractionOutcome() }

class EventExtractionEngine(private val parser: StrictJsonAiParser = StrictJsonAiParser(), private val local: LocalEventEngine? = null, private val cloud: CloudEventEngine? = null, private val consent: CloudConsent? = null) {
    suspend fun extract(importId: String, chunks: List<String>, existing: List<CalendarEventDraft> = emptyList()): ExtractionOutcome {
        val engine = local
        if (engine != null) {
            when (val capability = engine.checkCapability()) {
                NanoCapability.DOWNLOADABLE, NanoCapability.DOWNLOADING -> return ExtractionOutcome.NeedsDownload(capability)
                NanoCapability.AVAILABLE -> {
                    extractLocally(chunks, engine)?.takeIf { it.isNotEmpty() }?.let { drafts ->
                        return ExtractionOutcome.Drafts(DuplicateDetector.mark(drafts, existing))
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
        val results = chunks.map { cloudEngine.generate(it) }
        results.filterIsInstance<CloudResult.Failure>().firstOrNull()?.let { return ExtractionOutcome.CloudFailed(it.kind) }
        val drafts = results.filterIsInstance<CloudResult.Success>().map { result ->
            runCatching { parser.parseDrafts(result.text) }.getOrNull()
                ?: return ExtractionOutcome.Manual("Respuesta estructurada inválida")
        }.flatten()
        return if (drafts.isEmpty()) ExtractionOutcome.Manual("Respuesta estructurada inválida") else ExtractionOutcome.Drafts(DuplicateDetector.mark(drafts, existing))
    }

    private suspend fun extractLocally(chunks: List<String>, engine: LocalEventEngine): List<CalendarEventDraft>? {
        val drafts = mutableListOf<CalendarEventDraft>()
        for (chunk in chunks) {
            val responses = generateLocalParts(chunk, engine) ?: return null
            for (response in responses) {
                val parsed = runCatching { parser.parseDrafts(response) }.getOrNull() ?: return null
                drafts += parsed
            }
        }
        return drafts
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
                        addProperty(
                            "text",
                            "Extrae únicamente eventos académicos. Nunca inventes valores; usa null cuando falten. " +
                                "Conserva el texto original de fechas ambiguas y evidencia breve con su página.\n$prompt",
                        )
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
                "Devuelve SOLO JSON con la forma {events:[{date,start,end,originalDateText,title,category,institution,location,courseHint,sourcePage,evidence,ambiguousDate,inferredYear}]}. " +
                    "Nunca inventes valores; usa null cuando falten.\n$prompt",
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
            add("category", nullable("string"))
            add("institution", nullable("string"))
            add("location", nullable("string"))
            add("courseHint", nullable("string"))
            add("sourcePage", nullable("integer"))
            add("evidence", nullable("string"))
            add("ambiguousDate", JsonObject().apply { addProperty("type", "boolean") })
            add("inferredYear", JsonObject().apply { addProperty("type", "boolean") })
        }
        val required = properties.keySet()
        return JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply {
                add("events", JsonObject().apply {
                    addProperty("type", "array")
                    add("items", JsonObject().apply {
                        addProperty("type", "object")
                        addProperty("additionalProperties", false)
                        add("properties", properties)
                        add("required", JsonArray().apply { required.forEach(::add) })
                    })
                })
            })
            add("required", JsonArray().apply { add("events") })
        }
    }

    companion object {
        const val CLOUD_MODEL = "gemini-3.5-flash"
    }
}
