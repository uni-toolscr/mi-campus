package cr.micampus.app.core.model

import java.time.LocalDate
import java.time.LocalDateTime

enum class Institution { UCR, UNA }
enum class EventKind { CLASS, EXAM, ACTIVITY, TRANSIT }
enum class EventCategory { CLASS, EXAM, ACTIVITY, TRANSIT, OTHER }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ImportIssue { AMBIGUOUS, AMBIGUOUS_DATE, INFERRED_YEAR, MISSING_DATE, MISSING_TIME, INVALID_RANGE, PAST, DUPLICATE }
data class Evidence(val sourcePage: Int?, val excerpt: String?)
data class Course(val code: String?, val name: String?, val instructor: String? = null)
data class CalendarEventDraft(val id: String, val title: String?, val category: EventCategory?, val institution: Institution?, val date: LocalDate?, val startTime: java.time.LocalTime?, val endTime: java.time.LocalTime?, val location: String?, val course: Course?, val sourcePage: Int?, val evidence: Evidence?, val issues: Set<ImportIssue> = emptySet(), val originalDateText: String? = null)
data class ReminderSettings(val enabled: Boolean = true, val offsetsMinutes: List<Long> = listOf(1440L, 60L), val allDayOffsetsMinutes: List<Long> = listOf(1440L))
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
)
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
