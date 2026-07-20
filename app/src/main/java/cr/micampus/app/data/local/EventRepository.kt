package cr.micampus.app.data.local

import cr.micampus.app.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.*

interface MoodleEventStore {
    suspend fun replaceRemoteWindow(source: String, from: Instant, to: Instant, events: List<CampusEvent>): RemoteSyncChanges
    suspend fun clearRemoteSource(source: String): List<CampusEvent>
}

class EventRepository(private val dao: EventDao) : MoodleEventStore {
    val confirmed: Flow<List<ConfirmedEventEntity>> = dao.confirmed()
    val confirmedEvents: Flow<List<CampusEvent>> = confirmed.map { rows -> rows.map(::toDomain) }
    val drafts: Flow<List<DraftEventEntity>> = dao.drafts()
    val draftModels: Flow<List<CalendarEventDraft>> = drafts.map { rows -> rows.map(::toDraft) }
    val courseStyles: Flow<List<CourseStyle>> = dao.courseStyles().map { rows -> rows.map { CourseStyle(it.courseKey, it.colorIndex, it.emoji) } }
    fun upcoming(now: Instant = Instant.now()): Flow<List<ConfirmedEventEntity>> = dao.upcoming(now.toEpochMilli())
    suspend fun futureEntities(now: Instant = Instant.now()) = dao.confirmedNow(now.toEpochMilli())
    suspend fun allEntities() = dao.confirmedAll()
    suspend fun confirmedById(id: String): CampusEvent? = dao.confirmedById(id)?.let(::toDomain)
    suspend fun saveDraft(draft: DraftEventEntity) = dao.upsertDraft(draft)
    suspend fun updateDraft(draft: DraftEventEntity) = dao.upsertDraft(draft)
    suspend fun deleteDraft(id: String) = dao.deleteDraft(id)
    suspend fun deleteDrafts(ids: Set<String>) { if (ids.isNotEmpty()) dao.deleteDrafts(ids) }
    suspend fun isDuplicate(title: String, start: Instant): Boolean = dao.duplicateCount(title, start.toEpochMilli()) > 0
    suspend fun confirm(draft: DraftEventEntity, event: ConfirmedEventEntity) {
        require(draft.dateIso != null && draft.startTime != null) { "Missing date/time" }
        dao.upsert(event.copy(source = "confirmed"))
        dao.deleteDraft(draft.id)
    }
    suspend fun save(event: CampusEvent) = dao.upsert(event.toEntity())
    /** Used by linked import confirmation so entity-only metadata (currently sourcePage) survives. */
    suspend fun savePreservingExistingMetadata(event: CampusEvent) {
        val existing = dao.confirmedById(event.id)
        dao.upsert(event.toEntity().copy(sourcePage = existing?.sourcePage))
    }
    suspend fun saveAll(events: List<CampusEvent>) = dao.upsertAll(events.map { it.toEntity() })
    suspend fun delete(event: CampusEvent) = dao.delete(event.toEntity())
    suspend fun deleteAll(events: List<CampusEvent>) { if (events.isNotEmpty()) dao.deleteAll(events.map { it.toEntity() }) }
    suspend fun removeConfirmed(event: ConfirmedEventEntity) = dao.delete(event)
    suspend fun exportRecord(eventId: String, calendarId: String) = dao.exportRecord(eventId, calendarId)
    suspend fun saveExportRecord(record: ExportRecordEntity) = dao.saveExportRecord(record)
    suspend fun saveCourseStyle(style: CourseStyle) = dao.upsertCourseStyle(CourseStyleEntity(style.courseKey, style.colorIndex, style.emoji))
    override suspend fun replaceRemoteWindow(source: String, from: Instant, to: Instant, events: List<CampusEvent>): RemoteSyncChanges {
        val rows = events.map { it.copy(source = source).toEntity() }
        val changes = dao.replaceSourceWindow(source, from.toEpochMilli(), to.toEpochMilli(), rows)
        return RemoteSyncChanges(
            changed = changes.changed.map(::toDomain),
            removed = changes.removed.map(::toDomain),
        )
    }
    override suspend fun clearRemoteSource(source: String): List<CampusEvent> = dao.clearSource(source).map(::toDomain)

    companion object {
        fun toDomain(row: ConfirmedEventEntity, zone: ZoneId = ZoneId.systemDefault()) = CampusEvent(
            id = row.id,
            title = row.title,
            institution = runCatching { Institution.valueOf(row.institution) }.getOrDefault(Institution.UCR),
            kind = runCatching { EventKind.valueOf(row.kind) }.getOrDefault(EventKind.ACTIVITY),
            start = Instant.ofEpochMilli(row.startEpoch).atZone(zone).toLocalDateTime(),
            end = Instant.ofEpochMilli(row.endEpoch).atZone(zone).toLocalDateTime(),
            location = row.location,
            notes = row.notes,
            source = row.source,
            allDay = row.allDay,
            courseCode = row.courseCode,
            externalId = row.externalId,
            externalUrl = row.externalUrl,
            externalModifiedEpoch = row.externalModifiedEpoch,
            sourceDocumentId = row.sourceDocumentId,
            notifyThirtyMinutesBefore = row.notifyThirtyMinutesBefore,
        )

        fun toDraft(row: DraftEventEntity) = CalendarEventDraft(
            id = row.id,
            title = row.title,
            category = row.category?.let { runCatching { EventCategory.valueOf(it) }.getOrNull() },
            institution = row.institution?.let { runCatching { Institution.valueOf(it) }.getOrNull() },
            date = row.dateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            startTime = row.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            endTime = row.endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            location = row.location,
            course = row.courseCode?.let { Course(it, null) },
            sourcePage = row.sourcePage,
            evidence = row.evidence?.let { Evidence(row.sourcePage, it) },
            issues = row.issues.orEmpty().split(',').filter(String::isNotBlank).mapNotNull { runCatching { ImportIssue.valueOf(it) }.getOrNull() }.toSet(),
            originalDateText = row.originalDateText,
            description = row.description,
            sourceDocumentId = row.sourceDocumentId,
        )
    }
}

data class RemoteSyncChanges(
    val changed: List<CampusEvent>,
    val removed: List<CampusEvent>,
)

fun CampusEvent.toEntity(zone: ZoneId = ZoneId.systemDefault()) = ConfirmedEventEntity(
    id = id,
    title = title,
    institution = institution.name,
    kind = kind.name,
    startEpoch = start.atZone(zone).toInstant().toEpochMilli(),
    endEpoch = end.atZone(zone).toInstant().toEpochMilli(),
    location = location,
    notes = notes,
    source = source,
    allDay = allDay,
    courseCode = courseCode,
    externalId = externalId,
    externalUrl = externalUrl,
    externalModifiedEpoch = externalModifiedEpoch,
    sourceDocumentId = sourceDocumentId,
    notifyThirtyMinutesBefore = notifyThirtyMinutesBefore,
)

fun CalendarEventDraft.toEntity(now: Instant = Instant.now()) = DraftEventEntity(
    id = id,
    title = title,
    rawText = null,
    confidence = if (issues.isEmpty()) 1f else 0.5f,
    createdEpoch = now.toEpochMilli(),
    dateIso = date?.toString(),
    startTime = startTime?.toString(),
    endTime = endTime?.toString(),
    institution = institution?.name,
    issues = issues.joinToString(",") { it.name },
    evidence = evidence?.excerpt,
    sourcePage = sourcePage,
    category = category?.name,
    location = location,
    courseCode = course?.code,
    originalDateText = originalDateText,
    description = description,
    sourceDocumentId = sourceDocumentId,
)
