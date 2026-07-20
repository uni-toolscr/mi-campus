package cr.micampus.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "confirmed_events")
data class ConfirmedEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val institution: String,
    val kind: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val location: String,
    val notes: String,
    val source: String,
    val courseCode: String? = null,
    val sourcePage: Int? = null,
    val allDay: Boolean = false,
    val externalId: String? = null,
    val externalUrl: String? = null,
    val externalModifiedEpoch: Long? = null,
    val sourceDocumentId: String? = null,
    val notifyThirtyMinutesBefore: Boolean = false,
)
@Entity(tableName = "draft_events")
data class DraftEventEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val rawText: String?,
    val confidence: Float,
    val createdEpoch: Long,
    val status: String = "DRAFT",
    val dateIso: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val institution: String? = null,
    val issues: String? = null,
    val evidence: String? = null,
    val sourcePage: Int? = null,
    val category: String? = null,
    val location: String? = null,
    val courseCode: String? = null,
    val originalDateText: String? = null,
    val description: String? = null,
    val sourceDocumentId: String? = null,
)
@Entity(tableName = "export_records", primaryKeys = ["eventId", "calendarId"])
data class ExportRecordEntity(
    val eventId: String,
    val calendarId: String,
    val providerEventId: Long,
    val exportedAtEpoch: Long,
    val contentHash: String = "",
)
@Entity(tableName = "course_styles")
data class CourseStyleEntity(
    @PrimaryKey val courseKey: String,
    val colorIndex: Int,
    val emoji: String?,
)

@Entity(tableName = "imported_documents", indices = [Index(value = ["sha256"], unique = true)])
data class ImportedDocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sha256: String,
    val byteSize: Long,
    val localFileName: String,
    val importedAtEpoch: Long,
    val lastProcessedAtEpoch: Long? = null,
    val latestStatus: String = "STORED",
    val latestError: String? = null,
    val latestDraftCount: Int = 0,
    val latestModels: String? = null,
)

@Entity(
    tableName = "document_import_attempts",
    indices = [Index("documentId"), Index("batchId")],
)
data class DocumentImportAttemptEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val batchId: String,
    val startedAtEpoch: Long,
    val finishedAtEpoch: Long? = null,
    val status: String = "PROCESSING",
    val error: String? = null,
    val draftCount: Int = 0,
    val modelsUsed: String? = null,
)

@Entity(
    tableName = "document_transcriptions",
    primaryKeys = ["documentId", "chunkIndex"],
    indices = [Index("documentId")],
)
data class DocumentTranscriptionEntity(
    val documentId: String,
    val chunkIndex: Int,
    val page: Int,
    val text: String,
)

@Entity(tableName = "syllabus_topics")
data class SyllabusTopicEntity(
    @PrimaryKey val id: String,
    val courseCode: String? = null,
    val courseName: String? = null,
    val institution: String? = null,
    val groupLabel: String,
    val days: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val weekIndex: Int? = null,
    val fromIso: String? = null,
    val toIso: String? = null,
    val topic: String,
    val excludedDates: String,
    val conflictingExtraction: Boolean,
    val sourceDocumentId: String? = null,
    val status: String,
)

/** A token-free, account-scoped snapshot of the Moodle content catalogue. */
@Entity(tableName = "moodle_courses", primaryKeys = ["accountId", "courseId"])
data class MoodleCourseEntity(
    val accountId: String,
    val courseId: Long,
    val title: String,
    val shortName: String?,
    val remoteOrder: Int,
)

@Entity(tableName = "moodle_sections", primaryKeys = ["accountId", "courseId", "sectionId"], indices = [Index(value = ["accountId", "courseId"])])
data class MoodleSectionEntity(
    val accountId: String,
    val courseId: Long,
    val sectionId: Long,
    val title: String,
    val remoteOrder: Int,
)

@Entity(tableName = "moodle_resources", primaryKeys = ["accountId", "resourceId"], indices = [Index(value = ["accountId", "courseId", "sectionId"])])
data class MoodleResourceEntity(
    val accountId: String,
    val resourceId: Long,
    val courseId: Long,
    val sectionId: Long,
    val moduleName: String,
    val title: String,
    /** Canonical activity URL. Never contains wstoken/token. */
    val url: String?,
    val visible: Boolean,
    val availability: String?,
    val remoteOrder: Int,
)

@Entity(tableName = "moodle_files", primaryKeys = ["accountId", "fileId"], indices = [Index(value = ["accountId", "resourceId"])])
data class MoodleFileEntity(
    val accountId: String,
    val fileId: String,
    val resourceId: Long,
    val fileName: String,
    /** Moodle path, retained to make remote file identities deterministic. */
    val remotePath: String,
    /** Canonical pluginfile URL. Never contains wstoken/token. */
    val url: String,
    val mimeType: String?,
    val byteSize: Long,
    val modifiedEpoch: Long?,
    val localFileName: String? = null,
    val downloadedBytes: Long = 0,
    val downloadedAtEpoch: Long? = null,
)

/** Account-private user preference. A star is valid only while its file is present in the catalogue. */
@Entity(tableName = "moodle_starred_files", primaryKeys = ["accountId", "fileId"])
data class MoodleStarredFileEntity(
    val accountId: String,
    val fileId: String,
)

@Entity(tableName = "moodle_content_sync_state")
data class MoodleContentSyncStateEntity(
    @PrimaryKey val accountId: String,
    val lastSuccessfulSyncEpoch: Long? = null,
    val lastAttemptEpoch: Long? = null,
    val lastError: String? = null,
    val contentsSupported: Boolean? = null,
)

data class RemoteEventEntityChanges(
    val changed: List<ConfirmedEventEntity>,
    val removed: List<ConfirmedEventEntity>,
    /** Full incoming set after preserving locally managed fields. */
    val merged: List<ConfirmedEventEntity>,
)

fun reconcileRemoteEntities(
    existing: List<ConfirmedEventEntity>,
    incoming: List<ConfirmedEventEntity>,
): RemoteEventEntityChanges {
    val existingById = existing.associateBy(ConfirmedEventEntity::id)
    val merged = incoming.map { row ->
        row.copy(notifyThirtyMinutesBefore = existingById[row.id]?.notifyThirtyMinutesBefore ?: row.notifyThirtyMinutesBefore)
    }
    val incomingIds = merged.mapTo(mutableSetOf(), ConfirmedEventEntity::id)
    return RemoteEventEntityChanges(
        changed = merged.filter { existingById[it.id] != it },
        removed = existing.filter { it.id !in incomingIds },
        merged = merged,
    )
}

@Dao interface EventDao {
    @Query("SELECT * FROM confirmed_events ORDER BY startEpoch") fun confirmed(): Flow<List<ConfirmedEventEntity>>
    @Query("SELECT * FROM confirmed_events WHERE startEpoch >= :from ORDER BY startEpoch") fun upcoming(from: Long): Flow<List<ConfirmedEventEntity>>
    @Query("SELECT * FROM confirmed_events WHERE endEpoch >= :from ORDER BY startEpoch") suspend fun confirmedNow(from: Long): List<ConfirmedEventEntity>
    @Query("SELECT * FROM confirmed_events ORDER BY startEpoch") suspend fun confirmedAll(): List<ConfirmedEventEntity>
    @Query("SELECT * FROM confirmed_events WHERE id = :id LIMIT 1") suspend fun confirmedById(id: String): ConfirmedEventEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(event: ConfirmedEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(events: List<ConfirmedEventEntity>)
    @Delete suspend fun delete(event: ConfirmedEventEntity)
    @Delete suspend fun deleteAll(events: List<ConfirmedEventEntity>)
    @Query("SELECT * FROM draft_events ORDER BY createdEpoch DESC") fun drafts(): Flow<List<DraftEventEntity>>
    @Query("SELECT * FROM draft_events WHERE id = :id LIMIT 1") suspend fun draft(id: String): DraftEventEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDraft(event: DraftEventEntity)
    @Query("DELETE FROM draft_events WHERE id = :id") suspend fun deleteDraft(id: String)
    @Query("DELETE FROM draft_events WHERE id IN (:ids)") suspend fun deleteDrafts(ids: Set<String>)
    @Query("SELECT COUNT(*) FROM confirmed_events WHERE title = :title AND startEpoch = :start") suspend fun duplicateCount(title: String, start: Long): Int
    @Query("SELECT * FROM export_records WHERE eventId = :eventId AND calendarId = :calendarId LIMIT 1") suspend fun exportRecord(eventId: String, calendarId: String): ExportRecordEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExportRecord(record: ExportRecordEntity)
    @Query("SELECT * FROM course_styles") fun courseStyles(): Flow<List<CourseStyleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCourseStyle(style: CourseStyleEntity)
    @Query("SELECT * FROM confirmed_events WHERE source = :source AND startEpoch >= :from AND startEpoch <= :to") suspend fun sourceEventsInWindow(source: String, from: Long, to: Long): List<ConfirmedEventEntity>
    @Query("SELECT * FROM confirmed_events WHERE source = :source") suspend fun sourceEvents(source: String): List<ConfirmedEventEntity>
    @Query("DELETE FROM confirmed_events WHERE source = :source AND startEpoch >= :from AND startEpoch <= :to") suspend fun deleteSourceWindow(source: String, from: Long, to: Long)
    @Query("DELETE FROM confirmed_events WHERE source = :source AND startEpoch >= :from AND startEpoch <= :to AND id NOT IN (:ids)") suspend fun deleteSourceWindowExcept(source: String, from: Long, to: Long, ids: List<String>)
    @Query("DELETE FROM confirmed_events WHERE source = :source") suspend fun deleteSource(source: String)
    @Query("UPDATE confirmed_events SET sourceDocumentId = NULL WHERE sourceDocumentId = :documentId") suspend fun clearConfirmedDocument(documentId: String)
    @Query("UPDATE draft_events SET sourceDocumentId = NULL WHERE sourceDocumentId = :documentId") suspend fun clearDraftDocument(documentId: String)

    @Transaction
    suspend fun replaceSourceWindow(source: String, from: Long, to: Long, incoming: List<ConfirmedEventEntity>): RemoteEventEntityChanges {
        val existing = sourceEventsInWindow(source, from, to)
        val changes = reconcileRemoteEntities(existing, incoming)
        upsertAll(changes.merged)
        if (incoming.isEmpty()) deleteSourceWindow(source, from, to)
        else deleteSourceWindowExcept(source, from, to, incoming.map(ConfirmedEventEntity::id))
        return changes
    }

    @Transaction
    suspend fun clearSource(source: String): List<ConfirmedEventEntity> {
        val existing = sourceEvents(source)
        deleteSource(source)
        return existing
    }
}

@Dao
interface ImportedDocumentDao {
    @Query("SELECT * FROM imported_documents ORDER BY importedAtEpoch DESC") fun documents(): Flow<List<ImportedDocumentEntity>>
    @Query("SELECT * FROM imported_documents WHERE id = :id LIMIT 1") suspend fun document(id: String): ImportedDocumentEntity?
    @Query("SELECT * FROM imported_documents WHERE sha256 = :sha256 LIMIT 1") suspend fun documentByHash(sha256: String): ImportedDocumentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDocument(document: ImportedDocumentEntity)
    @Query("DELETE FROM imported_documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttempt(attempt: DocumentImportAttemptEntity)
    @Query("SELECT * FROM document_import_attempts WHERE id = :id LIMIT 1") suspend fun attempt(id: String): DocumentImportAttemptEntity?
    @Query("DELETE FROM document_import_attempts WHERE documentId = :documentId") suspend fun deleteAttempts(documentId: String)
    @Query("UPDATE document_import_attempts SET status = 'INTERRUPTED', finishedAtEpoch = :finishedAt WHERE status = 'PROCESSING'") suspend fun interruptProcessing(finishedAt: Long)
    @Query("UPDATE imported_documents SET latestStatus = 'INTERRUPTED', lastProcessedAtEpoch = :finishedAt WHERE latestStatus = 'PROCESSING'") suspend fun interruptDocuments(finishedAt: Long)
}

@Dao
interface DocumentTranscriptionDao {
    @Query("SELECT * FROM document_transcriptions WHERE documentId = :documentId ORDER BY chunkIndex")
    suspend fun forDocument(documentId: String): List<DocumentTranscriptionEntity>
    @Query("SELECT * FROM document_transcriptions ORDER BY documentId, chunkIndex")
    suspend fun all(): List<DocumentTranscriptionEntity>
    @Query("SELECT DISTINCT documentId FROM document_transcriptions")
    suspend fun transcribedDocumentIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chunks: List<DocumentTranscriptionEntity>)
    @Query("DELETE FROM document_transcriptions WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: String)
}

@Dao
interface MoodleContentDao {
    @Query("SELECT * FROM moodle_courses WHERE accountId = :accountId ORDER BY remoteOrder")
    fun courses(accountId: String): Flow<List<MoodleCourseEntity>>
    @Query("SELECT * FROM moodle_sections WHERE accountId = :accountId ORDER BY courseId, remoteOrder")
    fun sections(accountId: String): Flow<List<MoodleSectionEntity>>
    @Query("SELECT * FROM moodle_resources WHERE accountId = :accountId ORDER BY courseId, sectionId, remoteOrder")
    fun resources(accountId: String): Flow<List<MoodleResourceEntity>>
    @Query("SELECT * FROM moodle_files WHERE accountId = :accountId ORDER BY resourceId, fileName")
    fun files(accountId: String): Flow<List<MoodleFileEntity>>
    @Query("SELECT fileId FROM moodle_starred_files WHERE accountId = :accountId")
    fun starredFileIds(accountId: String): Flow<List<String>>
    @Query("SELECT * FROM moodle_files WHERE accountId = :accountId AND fileId = :fileId LIMIT 1")
    suspend fun file(accountId: String, fileId: String): MoodleFileEntity?
    @Query("SELECT * FROM moodle_files WHERE accountId = :accountId")
    suspend fun filesNow(accountId: String): List<MoodleFileEntity>
    @Query("SELECT * FROM moodle_resources WHERE accountId = :accountId")
    suspend fun resourcesNow(accountId: String): List<MoodleResourceEntity>
    @Query("SELECT * FROM moodle_content_sync_state WHERE accountId = :accountId LIMIT 1")
    fun syncState(accountId: String): Flow<MoodleContentSyncStateEntity?>
    @Query("SELECT * FROM moodle_content_sync_state WHERE accountId = :accountId LIMIT 1")
    suspend fun syncStateNow(accountId: String): MoodleContentSyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFiles(items: List<MoodleFileEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSyncState(state: MoodleContentSyncStateEntity)
    @Query("INSERT OR IGNORE INTO moodle_starred_files(accountId, fileId) SELECT :accountId, :fileId WHERE EXISTS (SELECT 1 FROM moodle_files WHERE accountId = :accountId AND fileId = :fileId)")
    suspend fun insertStarIfFileExists(accountId: String, fileId: String)
    @Query("DELETE FROM moodle_starred_files WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun deleteStar(accountId: String, fileId: String)
    @Query("DELETE FROM moodle_starred_files WHERE accountId = :accountId AND fileId NOT IN (SELECT fileId FROM moodle_files WHERE accountId = :accountId)")
    suspend fun pruneMissingStars(accountId: String)
    @Query("DELETE FROM moodle_courses WHERE accountId = :accountId") suspend fun deleteCourses(accountId: String)
    @Query("DELETE FROM moodle_sections WHERE accountId = :accountId") suspend fun deleteSections(accountId: String)
    @Query("DELETE FROM moodle_resources WHERE accountId = :accountId") suspend fun deleteResources(accountId: String)
    @Query("DELETE FROM moodle_files WHERE accountId = :accountId") suspend fun deleteFiles(accountId: String)
    @Query("DELETE FROM moodle_content_sync_state WHERE accountId = :accountId") suspend fun deleteSyncState(accountId: String)
    @Query("UPDATE moodle_files SET localFileName = NULL, downloadedBytes = 0, downloadedAtEpoch = NULL WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun clearDownload(accountId: String, fileId: String)

    @Transaction
    suspend fun replaceCatalog(
        accountId: String,
        courses: List<MoodleCourseEntity>,
        sections: List<MoodleSectionEntity>,
        resources: List<MoodleResourceEntity>,
        files: List<MoodleFileEntity>,
        syncState: MoodleContentSyncStateEntity,
    ): List<MoodleFileEntity> {
        val oldFiles = filesNow(accountId).associateBy(MoodleFileEntity::fileId)
        val reconciledFiles = files.map { incoming ->
            val old = oldFiles[incoming.fileId]
            if (old != null && old.url == incoming.url && old.byteSize == incoming.byteSize && old.modifiedEpoch == incoming.modifiedEpoch) {
                incoming.copy(localFileName = old.localFileName, downloadedBytes = old.downloadedBytes, downloadedAtEpoch = old.downloadedAtEpoch)
            } else incoming
        }
        val retained = reconciledFiles.mapNotNull { it.localFileName?.let { _ -> it.fileId } }.toSet()
        val obsoleteDownloads = oldFiles.values.filter { it.localFileName != null && it.fileId !in retained }
        deleteResources(accountId); deleteSections(accountId); deleteCourses(accountId); deleteFiles(accountId)
        if (courses.isNotEmpty()) insertCourses(courses)
        if (sections.isNotEmpty()) insertSections(sections)
        if (resources.isNotEmpty()) insertResources(resources)
        if (reconciledFiles.isNotEmpty()) upsertFiles(reconciledFiles)
        pruneMissingStars(accountId)
        upsertSyncState(syncState)
        return obsoleteDownloads
    }

    @Transaction
    suspend fun clearAccount(accountId: String): List<MoodleFileEntity> {
        val downloaded = filesNow(accountId).filter { it.localFileName != null }
        deleteResources(accountId); deleteSections(accountId); deleteCourses(accountId); deleteFiles(accountId); deleteStarredFiles(accountId); deleteSyncState(accountId)
        return downloaded
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCourses(items: List<MoodleCourseEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSections(items: List<MoodleSectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertResources(items: List<MoodleResourceEntity>)
    @Query("DELETE FROM moodle_starred_files WHERE accountId = :accountId") suspend fun deleteStarredFiles(accountId: String)
}

@Database(entities = [ConfirmedEventEntity::class, DraftEventEntity::class, ExportRecordEntity::class, CourseStyleEntity::class, ImportedDocumentEntity::class, DocumentImportAttemptEntity::class, SyllabusTopicEntity::class, DocumentTranscriptionEntity::class, MoodleCourseEntity::class, MoodleSectionEntity::class, MoodleResourceEntity::class, MoodleFileEntity::class, MoodleStarredFileEntity::class, MoodleContentSyncStateEntity::class], version = 13, exportSchema = false)
abstract class MiCampusDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun importedDocumentDao(): ImportedDocumentDao
    abstract fun documentTranscriptionDao(): DocumentTranscriptionDao
    abstract fun moodleContentDao(): MoodleContentDao
}
