package cr.micampus.app.core.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The compact, stable identifier used when an institution must be included in an LLM request.
 * Keep it here rather than duplicating prompt-specific labels across features.
 */
enum class Institution(val llmShortName: String) {
    UCR("UCR"),
    UNA("UNA"),
}
enum class EventKind { CLASS, EXAM, QUIZ, TAREA, ACTIVITY, TRANSIT }
enum class EventCategory { CLASS, EXAM, QUIZ, TAREA, ACTIVITY, TRANSIT, OTHER }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ImportIssue { AMBIGUOUS, AMBIGUOUS_DATE, INFERRED_YEAR, INFERRED_TIME, MISSING_DATE, MISSING_TIME, INVALID_RANGE, PAST, DUPLICATE }
data class Evidence(val sourcePage: Int?, val excerpt: String?)
data class Course(val code: String?, val name: String?, val instructor: String? = null)
data class CalendarEventDraft(val id: String, val title: String?, val category: EventCategory?, val institution: Institution?, val date: LocalDate?, val startTime: java.time.LocalTime?, val endTime: java.time.LocalTime?, val location: String?, val course: Course?, val sourcePage: Int?, val evidence: Evidence?, val issues: Set<ImportIssue> = emptySet(), val originalDateText: String? = null, val description: String? = null, val sourceDocumentId: String? = null)
data class CourseGroup(val label: String, val days: Set<java.time.DayOfWeek>, val startTime: java.time.LocalTime?, val endTime: java.time.LocalTime?, val instructor: String? = null)
data class SyllabusWeek(val index: Int?, val from: LocalDate?, val to: LocalDate?, val topic: String?)
data class ExtractedSyllabus(
    val course: Course? = null,
    val institution: Institution? = null,
    val groups: List<CourseGroup> = emptyList(),
    val weeks: List<SyllabusWeek> = emptyList(),
    val holidays: List<LocalDate> = emptyList(),
) {
    val canExpandClasses: Boolean get() = groups.isNotEmpty() && weeks.any { it.from != null && it.to != null }
}
enum class ServiceStatus { VERIFIED, EXPIRED, NO_SERVICE, UNKNOWN }
data class CampusEvent(
    val id: String,
    val title: String,
    val institution: Institution,
    val kind: EventKind,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String = "",
    val notes: String = "",
    val source: String = "local",
    val allDay: Boolean = false,
    val courseCode: String? = null,
    val externalId: String? = null,
    val externalUrl: String? = null,
    val externalModifiedEpoch: Long? = null,
    val sourceDocumentId: String? = null,
    val notifyThirtyMinutesBefore: Boolean = false,
)
data class CourseStyle(val courseKey: String, val colorIndex: Int, val emoji: String? = null)
data class TransportDirection(val id: String, val from: String, val to: String, val weekdays: List<String>, val saturday: List<String> = emptyList())
data class TransportDataset(
    val version: Int,
    val institution: Institution,
    val source: String,
    val sourceUrl: String?,
    val lastVerified: LocalDate,
    val verifiedFrom: LocalDate,
    val verifiedUntil: LocalDate,
    val directions: List<TransportDirection>,
    val stops: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val overrideFrom: LocalDate? = null,
    val overrideUntil: LocalDate? = null,
    val overrideDirections: List<TransportDirection> = emptyList(),
    val includedDates: Set<LocalDate> = emptySet(),
    val excludedDates: Set<LocalDate> = emptySet(),
    val auditNote: String? = null,
)
data class TransportService(val status: ServiceStatus, val source: String?, val date: LocalDate, val departures: List<String>, val nextValidDate: LocalDate? = null, val laterDepartures: List<String> = emptyList())

/** A token-free snapshot of learning material exposed by an institution platform. */
data class LearningCourse(
    val id: String,
    val remoteId: Long,
    val code: String?,
    val name: String,
    val remoteOrder: Int,
    val sections: List<CourseSection> = emptyList(),
)

data class CourseSection(
    val id: String,
    val remoteId: Long?,
    val name: String,
    val remoteOrder: Int,
    val resources: List<LearningResource> = emptyList(),
)

data class LearningResource(
    val id: String,
    val remoteId: Long,
    val name: String,
    val kind: ResourceKind,
    val remoteOrder: Int,
    /** Canonical activity URL; it never contains an access token. */
    val url: String?,
    val enabled: Boolean = true,
    val availabilityMessage: String? = null,
    val files: List<ResourceFile> = emptyList(),
)

data class ResourceFile(
    val id: String,
    val name: String,
    /** Canonical pluginfile URL; it never contains an access token. */
    val url: String,
    val path: String = "/",
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedEpochSeconds: Long? = null,
)

/** Course images are decorative assets for the Moodle web UI, never study material. */
fun isDecorativeImageFile(name: String, mimeType: String?): Boolean =
    mimeType?.lowercase()?.startsWith("image/") == true ||
        (mimeType.isNullOrBlank() && name.substringAfterLast('.', "").lowercase() in DECORATIVE_IMAGE_EXTENSIONS)

private val DECORATIVE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif")

enum class ResourceKind { FILE, FOLDER, PAGE, URL, FORUM, SCORM, LABEL, UNKNOWN }
