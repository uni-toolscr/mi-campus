package cr.micampus.app.data.moodle

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.CourseSection
import cr.micampus.app.core.model.LearningCourse
import cr.micampus.app.core.model.LearningResource
import cr.micampus.app.core.model.ResourceFile
import cr.micampus.app.core.model.ResourceKind
import cr.micampus.app.core.model.isDecorativeImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

enum class MoodleFailureKind {
    INVALID_CREDENTIALS,
    SERVICE_UNAVAILABLE,
    INVALID_TOKEN,
    UNSUPPORTED,
    NETWORK,
    SERVER,
    INVALID_RESPONSE,
    NOT_CONNECTED,
}

class MoodleException(
    val kind: MoodleFailureKind,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
    /** Raw Moodle errorcode, preserved for diagnostics. */
    val errorCode: String? = null,
) : Exception(message, cause)

data class MoodleHttpResponse(val status: Int, val body: String)

interface MoodleTransport {
    fun post(url: String, form: Map<String, String>): MoodleHttpResponse
}

class HttpUrlConnectionMoodleTransport : MoodleTransport {
    override fun post(url: String, form: Map<String, String>): MoodleHttpResponse {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MiCampus Android/1.0")
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readLimited) ?: ByteArray(0)
            MoodleHttpResponse(status, String(bytes, StandardCharsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Moodle response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 5_000_000
    }
}

/** Site info + course list from an already-completed handshake, reusable to avoid repeat calls. */
internal data class MoodlePreloadedSession(
    val site: MoodleSiteInfoDto,
    val courses: List<MoodleCourseDto>,
)

data class MoodleSnapshot(
    val account: MoodleAccount,
    val events: List<CampusEvent>,
    val from: Instant,
    val to: Instant,
)

/** Snapshot plus the handshake data that produced it, so a follow-up catalog fetch can reuse it. */
internal data class MoodleSnapshotSession(
    val snapshot: MoodleSnapshot,
    val preloaded: MoodlePreloadedSession,
)

/** Result is typed so a caller can keep calendar sync working when catalog access is unavailable. */
sealed interface MoodleContentCatalog {
    val account: MoodleAccount

    data class Available(
        override val account: MoodleAccount,
        val courses: List<LearningCourse>,
    ) : MoodleContentCatalog

    data class Unsupported(
        override val account: MoodleAccount,
    ) : MoodleContentCatalog
}

class MoodleClient(
    private val transport: MoodleTransport = HttpUrlConnectionMoodleTransport(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = BASE_URL,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    /** Diagnostics sink; Android wiring points this at logcat. */
    private val logger: (String) -> Unit = {},
) {
    private val contentFetchMutex = Mutex()

    suspend fun authenticate(username: String, password: CharArray): MoodleAccount = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isEmpty()) {
            throw MoodleException(MoodleFailureKind.INVALID_CREDENTIALS, "Username and password are required")
        }
        val response = post(
            "$baseUrl/login/token.php",
            linkedMapOf(
                "username" to username.trim(),
                "password" to String(password),
                "service" to MOBILE_SERVICE,
            ),
        )
        val json = responseObject(response, tokenRequest = true)
        val token = json.string("token")
            ?: throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Moodle did not return an access token")
        val site = siteInfo(token)
        validateCapabilities(site)
        MoodleAccount(
            token = token,
            userId = site.userid ?: throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Moodle did not return a user id"),
            displayName = site.fullname?.takeIf(String::isNotBlank) ?: username.trim(),
        )
    }

    suspend fun fetchSnapshot(account: MoodleAccount, from: Instant, to: Instant): MoodleSnapshot =
        fetchSnapshotSession(account, from, to).snapshot

    internal suspend fun fetchSnapshotSession(account: MoodleAccount, from: Instant, to: Instant): MoodleSnapshotSession = withContext(Dispatchers.IO) {
        require(to.isAfter(from))
        val site = siteInfo(account.token)
        validateCapabilities(site)
        val currentAccount = account.copy(
            userId = site.userid ?: account.userId,
            displayName = site.fullname?.takeIf(String::isNotBlank) ?: account.displayName,
        )
        val courseList = courses(account.token, currentAccount.userId)
        val courses = courseList.associateBy { it.id ?: 0L }
        val events = actionEvents(account.token, from, to)
            .mapNotNull { MoodleEventMapper.toCampusEvent(it, courses, zoneId) }
            .distinctBy(CampusEvent::id)
            .sortedBy(CampusEvent::start)
        MoodleSnapshotSession(
            MoodleSnapshot(currentAccount, events, from, to),
            MoodlePreloadedSession(site, courseList),
        )
    }

    /**
     * Retrieves the complete enrolled-course catalog when the optional mobile capability is exposed.
     * [preloaded] lets a full sync reuse the site info and course list it already fetched, so the
     * catalog refresh only adds the per-course content calls instead of repeating the whole handshake.
     */
    internal suspend fun fetchContentCatalog(
        account: MoodleAccount,
        preloaded: MoodlePreloadedSession? = null,
    ): MoodleContentCatalog = withContext(Dispatchers.IO) {
        contentFetchMutex.withLock {
            val site = preloaded?.site ?: siteInfo(account.token).also(::validateCapabilities)
            val currentAccount = account.copy(
                userId = site.userid ?: account.userId,
                displayName = site.fullname?.takeIf(String::isNotBlank) ?: account.displayName,
            )
            val available = site.functions.orEmpty().mapNotNull(MoodleFunctionDto::name).toSet()
            if (available.isNotEmpty() && CONTENTS !in available) {
                return@withLock MoodleContentCatalog.Unsupported(currentAccount)
            }
            val catalogCourses = (preloaded?.courses ?: courses(account.token, currentAccount.userId))
                .mapIndexedNotNull { courseOrder, course ->
                    val courseId = course.id ?: return@mapIndexedNotNull null
                    MoodleContentMapper.toLearningCourse(course, courseOrder, courseContents(account.token, courseId))
                }
            MoodleContentCatalog.Available(currentAccount, catalogCourses)
        }
    }

    /**
     * Confirms a reported invalid token before the app treats the session as dead. UNA's proxy can
     * answer a burst of requests with `invalidtoken` even though the token still works, so a single
     * rejection must not log the user out. Returns true only when a fresh site-info probe, after a
     * short pause, is rejected the same way.
     */
    suspend fun verifyTokenInvalid(account: MoodleAccount): Boolean = withContext(Dispatchers.IO) {
        delay(TOKEN_VERIFY_DELAY_MILLIS)
        try {
            siteInfo(account.token)
            false
        } catch (error: MoodleException) {
            if (error.kind != MoodleFailureKind.INVALID_TOKEN) {
                logger("token verify probe failed with ${error.kind} (${error.errorCode ?: "no errorcode"}); keeping session")
            }
            error.kind == MoodleFailureKind.INVALID_TOKEN
        }
    }

    private fun siteInfo(token: String): MoodleSiteInfoDto {
        val json = webService(token, SITE_INFO)
        return runCatching { gson.fromJson(json, MoodleSiteInfoDto::class.java) }
            .getOrElse { throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Invalid Moodle site information", cause = it) }
    }

    private fun courses(token: String, userId: Long): List<MoodleCourseDto> {
        val json = webService(token, USERS_COURSES, mapOf("userid" to userId.toString()))
        return runCatching { gson.fromJson(json, Array<MoodleCourseDto>::class.java)?.toList().orEmpty() }
            .getOrElse { throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Invalid Moodle course response", cause = it) }
    }

    private fun actionEvents(token: String, from: Instant, to: Instant): List<MoodleEventDto> {
        val output = mutableListOf<MoodleEventDto>()
        var afterEventId: Long? = null
        repeat(MAX_EVENT_PAGES) {
            val params = linkedMapOf(
                "timesortfrom" to from.epochSecond.toString(),
                "timesortto" to to.epochSecond.toString(),
                "limitnum" to EVENT_PAGE_SIZE.toString(),
                "limittononsuspendedevents" to "1",
            )
            afterEventId?.let { params["aftereventid"] = it.toString() }
            val json = webService(token, ACTION_EVENTS, params)
            val page = runCatching { gson.fromJson(json, MoodleActionEventsDto::class.java) }
                .getOrElse { throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Invalid Moodle event response", cause = it) }
            val events = page.events.orEmpty()
            output += events
            if (events.size < EVENT_PAGE_SIZE) return output
            val next = page.lastid ?: events.lastOrNull()?.id
            if (next == null || next == afterEventId) return output
            afterEventId = next
        }
        return output
    }

    private fun courseContents(token: String, courseId: Long): List<MoodleSectionDto> {
        val json = webService(token, CONTENTS, mapOf("courseid" to courseId.toString()))
        return runCatching { gson.fromJson(json, Array<MoodleSectionDto>::class.java)?.toList().orEmpty() }
            .getOrElse { throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Invalid Moodle course content response", cause = it) }
    }

    private fun validateCapabilities(site: MoodleSiteInfoDto) {
        val available = site.functions.orEmpty().mapNotNull(MoodleFunctionDto::name).toSet()
        if (available.isEmpty()) return
        val missing = REQUIRED_FUNCTIONS - available
        if (missing.isNotEmpty()) {
            throw MoodleException(
                MoodleFailureKind.UNSUPPORTED,
                "UNA Moodle does not expose the required functions: ${missing.sorted().joinToString()}",
            )
        }
    }

    private fun webService(token: String, function: String, parameters: Map<String, String> = emptyMap()): String {
        val response = post(
            "$baseUrl/webservice/rest/server.php",
            linkedMapOf(
                "wstoken" to token,
                "wsfunction" to function,
                "moodlewsrestformat" to "json",
            ) + parameters,
        )
        val parsed = parseJson(response)
        if (parsed is JsonObject) throwIfMoodleError(parsed, tokenRequest = false)
        if (response.status !in 200..299) {
            logger("Moodle error: $function returned HTTP ${response.status}")
            throw MoodleException(MoodleFailureKind.SERVER, "Moodle returned HTTP ${response.status}", retryable = response.status >= 500)
        }
        return response.body
    }

    private fun post(url: String, form: Map<String, String>): MoodleHttpResponse = try {
        transport.post(url, form)
    } catch (error: MoodleException) {
        throw error
    } catch (error: IOException) {
        throw MoodleException(MoodleFailureKind.NETWORK, "Could not reach Aula Virtual", retryable = true, cause = error)
    } catch (error: Exception) {
        throw MoodleException(MoodleFailureKind.NETWORK, "Could not reach Aula Virtual", retryable = true, cause = error)
    }

    private fun responseObject(response: MoodleHttpResponse, tokenRequest: Boolean): JsonObject {
        val parsed = parseJson(response)
        val json = parsed.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Moodle returned an unexpected response")
        throwIfMoodleError(json, tokenRequest)
        if (response.status !in 200..299) {
            throw MoodleException(MoodleFailureKind.SERVER, "Moodle returned HTTP ${response.status}", retryable = response.status >= 500)
        }
        return json
    }

    private fun parseJson(response: MoodleHttpResponse) = try {
        JsonParser.parseString(response.body)
    } catch (error: Exception) {
        if (response.status !in 200..299) {
            throw MoodleException(MoodleFailureKind.SERVER, "Moodle returned HTTP ${response.status}", retryable = response.status >= 500, cause = error)
        }
        throw MoodleException(MoodleFailureKind.INVALID_RESPONSE, "Moodle returned invalid JSON", cause = error)
    }

    private fun throwIfMoodleError(json: JsonObject, tokenRequest: Boolean) {
        val code = json.string("errorcode").orEmpty().lowercase()
        val message = json.string("message") ?: json.string("error") ?: return
        val kind = when {
            code.contains("invalidlogin") || tokenRequest && message.contains("password", ignoreCase = true) -> MoodleFailureKind.INVALID_CREDENTIALS
            code.contains("invalidtoken") -> MoodleFailureKind.INVALID_TOKEN
            code.contains("servicenotavailable") || code.contains("service") -> MoodleFailureKind.SERVICE_UNAVAILABLE
            else -> MoodleFailureKind.SERVER
        }
        logger("Moodle error: kind=$kind errorcode=${code.ifBlank { "?" }}")
        throw MoodleException(kind, message, retryable = kind == MoodleFailureKind.SERVER, errorCode = code.takeIf(String::isNotBlank))
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    companion object {
        const val BASE_URL = "https://aulavirtual.una.ac.cr"
        const val SOURCE = "una-moodle"
        const val MOBILE_SERVICE = "moodle_mobile_app"
        const val SITE_INFO = "core_webservice_get_site_info"
        const val USERS_COURSES = "core_enrol_get_users_courses"
        const val ACTION_EVENTS = "core_calendar_get_action_events_by_timesort"
        const val CONTENTS = "core_course_get_contents"
        private const val EVENT_PAGE_SIZE = 50
        private const val MAX_EVENT_PAGES = 20
        private const val TOKEN_VERIFY_DELAY_MILLIS = 2_000L
        private val REQUIRED_FUNCTIONS = setOf(SITE_INFO, USERS_COURSES, ACTION_EVENTS)
    }
}

internal object MoodleEventMapper {
    fun toCampusEvent(event: MoodleEventDto, courses: Map<Long, MoodleCourseDto>, zoneId: ZoneId): CampusEvent? {
        val remoteId = event.id ?: return null
        val dueEpoch = event.timesort?.takeIf { it > 0 } ?: event.timestart?.takeIf { it > 0 } ?: return null
        val due = Instant.ofEpochSecond(dueEpoch).atZone(zoneId).toLocalDateTime()
        val course = event.course
        val courseFallback = courses[course?.id ?: event.courseid ?: 0L]
        val moduleName = event.modulename.orEmpty().lowercase()
        val component = event.component.orEmpty().lowercase()
        val kind = when {
            moduleName == "assign" || component.contains("assign") -> EventKind.TAREA
            moduleName == "quiz" || component.contains("quiz") -> EventKind.QUIZ
            else -> EventKind.ACTIVITY
        }
        return CampusEvent(
            id = "${MoodleClient.SOURCE}:event:$remoteId",
            title = plainText(event.name).ifBlank { "Evento de Aula Virtual" },
            institution = Institution.UNA,
            kind = kind,
            start = due,
            end = due.plusMinutes(15),
            notes = plainText(event.description).take(2_000),
            source = MoodleClient.SOURCE,
            courseCode = course?.shortname?.takeIf(String::isNotBlank)
                ?: courseFallback?.shortname?.takeIf(String::isNotBlank)
                ?: course?.fullname?.takeIf(String::isNotBlank)
                ?: courseFallback?.fullname,
            externalId = "calendar-event:$remoteId",
            externalUrl = event.url ?: event.action?.url,
            externalModifiedEpoch = event.timemodified?.times(1_000),
        )
    }

    private fun plainText(value: String?): String = value.orEmpty()
        .replace(HTML_TAG, " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE, " ")
        .trim()

    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
}

/** Converts Moodle's intentionally loose course-content payload into stable domain records. */
internal object MoodleContentMapper {
    fun toLearningCourse(
        course: MoodleCourseDto,
        courseOrder: Int,
        sections: List<MoodleSectionDto>,
    ): LearningCourse? {
        val courseId = course.id ?: return null
        return LearningCourse(
            id = "${MoodleClient.SOURCE}:course:$courseId",
            remoteId = courseId,
            code = course.shortname?.takeIf(String::isNotBlank),
            name = plainText(course.fullname).ifBlank { course.shortname?.trim().orEmpty() }.ifBlank { "Curso" },
            remoteOrder = course.sortorder ?: courseOrder,
            sections = sections.mapIndexed { index, section -> toSection(courseId, section, index) },
        )
    }

    private fun toSection(courseId: Long, section: MoodleSectionDto, sectionOrder: Int): CourseSection {
        val remoteId = section.id
        val sectionKey = remoteId?.toString() ?: "number:${section.section ?: sectionOrder}"
        val name = plainText(section.name).ifBlank { "General" }
        return CourseSection(
            id = "${MoodleClient.SOURCE}:course:$courseId:section:$sectionKey",
            remoteId = remoteId,
            name = name,
            remoteOrder = section.section ?: sectionOrder,
            resources = section.modules.orEmpty().mapIndexedNotNull { moduleOrder, module ->
                toResource(courseId, module, moduleOrder)
            },
        )
    }

    private fun toResource(courseId: Long, module: MoodleModuleDto, moduleOrder: Int): LearningResource? {
        val moduleId = module.id ?: return null
        val availability = plainText(module.availabilityinfo).takeIf(String::isNotBlank)
        val visible = module.uservisible ?: module.visible?.let { it != 0 }
        // Moodle returns hidden modules in some versions. A restriction message is meaningful,
        // but a hidden module without one must not leak into the catalog.
        if (visible == false && availability == null) return null
        val kind = module.kind()
        val contents = module.contents.orEmpty()
        val files = contents.mapNotNull { content -> toFile(moduleId, content) }
        if (kind in setOf(ResourceKind.FILE, ResourceKind.FOLDER) && contents.isNotEmpty() && files.isEmpty()) return null
        return LearningResource(
            id = "${MoodleClient.SOURCE}:course:$courseId:resource:$moduleId",
            remoteId = moduleId,
            name = plainText(module.name).ifBlank { kind.displayName() },
            kind = kind,
            remoteOrder = moduleOrder,
            url = canonicalUrl(module.url),
            enabled = visible != false && availability == null,
            availabilityMessage = availability,
            files = files,
        )
    }

    private fun toFile(moduleId: Long, content: MoodleContentDto): ResourceFile? {
        val name = content.filename?.trim().orEmpty()
        if (name.isBlank() || isDecorativeImageFile(name, content.mimetype)) return null
        val url = canonicalUrl(content.fileurl) ?: return null
        val path = content.filepath?.takeIf(String::isNotBlank) ?: "/"
        return ResourceFile(
            id = "${MoodleClient.SOURCE}:file:$moduleId:$path:$name",
            name = name,
            url = url,
            path = path,
            mimeType = content.mimetype?.takeIf(String::isNotBlank),
            sizeBytes = content.filesize?.takeIf { it >= 0 },
            modifiedEpochSeconds = content.timemodified?.takeIf { it > 0 },
        )
    }

    private fun MoodleModuleDto.kind(): ResourceKind = when (modname?.lowercase()) {
        "resource" -> ResourceKind.FILE
        "folder" -> ResourceKind.FOLDER
        "page" -> ResourceKind.PAGE
        "url" -> ResourceKind.URL
        "forum" -> ResourceKind.FORUM
        "scorm" -> ResourceKind.SCORM
        "label" -> ResourceKind.LABEL
        else -> ResourceKind.UNKNOWN
    }

    private fun ResourceKind.displayName(): String = when (this) {
        ResourceKind.FILE -> "Archivo"
        ResourceKind.FOLDER -> "Carpeta"
        ResourceKind.PAGE -> "Página"
        ResourceKind.URL -> "Enlace"
        ResourceKind.FORUM -> "Foro"
        ResourceKind.SCORM -> "Paquete SCORM"
        ResourceKind.LABEL -> "Contenido"
        ResourceKind.UNKNOWN -> "Actividad"
    }

    /** Removes credentials even when a server returns tokenized pluginfile URLs. */
    private fun canonicalUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val uri = URI(raw)
            val query = uri.rawQuery
                ?.split('&')
                ?.filterNot { parameter ->
                    parameter.substringBefore('=').lowercase() in TOKEN_PARAMETERS
                }
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                append(raw.substringBefore('?').substringBefore('#'))
                query?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        }.getOrElse {
            raw.replace(TOKEN_QUERY, "?").replace(EMPTY_QUERY, "").trimEnd('?', '&')
        }
    }

    private fun plainText(value: String?): String = value.orEmpty()
        .replace(HTML_TAG, " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE, " ")
        .trim()

    private val TOKEN_PARAMETERS = setOf("token", "wstoken")
    private val TOKEN_QUERY = Regex("([?&])(token|wstoken)=[^&]*&?", RegexOption.IGNORE_CASE)
    private val EMPTY_QUERY = Regex("\\?&")
    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
}

internal data class MoodleSiteInfoDto(
    val userid: Long? = null,
    val fullname: String? = null,
    val functions: List<MoodleFunctionDto>? = null,
)

internal data class MoodleFunctionDto(val name: String? = null)
internal data class MoodleCourseDto(
    val id: Long? = null,
    val shortname: String? = null,
    val fullname: String? = null,
    val sortorder: Int? = null,
)
internal data class MoodleSectionDto(
    val id: Long? = null,
    val name: String? = null,
    val section: Int? = null,
    val modules: List<MoodleModuleDto>? = null,
)
internal data class MoodleModuleDto(
    val id: Long? = null,
    val name: String? = null,
    val modname: String? = null,
    val url: String? = null,
    val visible: Int? = null,
    val uservisible: Boolean? = null,
    val availabilityinfo: String? = null,
    val contents: List<MoodleContentDto>? = null,
)
internal data class MoodleContentDto(
    val type: String? = null,
    val filename: String? = null,
    val filepath: String? = null,
    val fileurl: String? = null,
    val mimetype: String? = null,
    val filesize: Long? = null,
    val timemodified: Long? = null,
)
internal data class MoodleActionEventsDto(
    val events: List<MoodleEventDto>? = null,
    val lastid: Long? = null,
)
internal data class MoodleEventDto(
    val id: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val component: String? = null,
    val modulename: String? = null,
    val courseid: Long? = null,
    val timestart: Long? = null,
    val timesort: Long? = null,
    val timemodified: Long? = null,
    val url: String? = null,
    val course: MoodleEventCourseDto? = null,
    val action: MoodleEventActionDto? = null,
)
internal data class MoodleEventCourseDto(
    val id: Long? = null,
    val shortname: String? = null,
    val fullname: String? = null,
)
internal data class MoodleEventActionDto(val url: String? = null)
