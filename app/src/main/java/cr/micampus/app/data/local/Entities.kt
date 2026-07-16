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
)
@Entity(tableName = "export_records", primaryKeys = ["eventId", "calendarId"])
data class ExportRecordEntity(
    val eventId: String,
    val calendarId: String,
    val providerEventId: Long,
    val exportedAtEpoch: Long,
    val contentHash: String = "",
)

@Dao interface EventDao {
    @Query("SELECT * FROM confirmed_events ORDER BY startEpoch") fun confirmed(): Flow<List<ConfirmedEventEntity>>
    @Query("SELECT * FROM confirmed_events WHERE startEpoch >= :from ORDER BY startEpoch") fun upcoming(from: Long): Flow<List<ConfirmedEventEntity>>
    @Query("SELECT * FROM confirmed_events WHERE endEpoch >= :from ORDER BY startEpoch") suspend fun confirmedNow(from: Long): List<ConfirmedEventEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(event: ConfirmedEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(events: List<ConfirmedEventEntity>)
    @Delete suspend fun delete(event: ConfirmedEventEntity)
    @Query("SELECT * FROM draft_events ORDER BY createdEpoch DESC") fun drafts(): Flow<List<DraftEventEntity>>
    @Query("SELECT * FROM draft_events WHERE id = :id LIMIT 1") suspend fun draft(id: String): DraftEventEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDraft(event: DraftEventEntity)
    @Query("DELETE FROM draft_events WHERE id = :id") suspend fun deleteDraft(id: String)
    @Query("SELECT COUNT(*) FROM confirmed_events WHERE title = :title AND startEpoch = :start") suspend fun duplicateCount(title: String, start: Long): Int
    @Query("SELECT * FROM export_records WHERE eventId = :eventId AND calendarId = :calendarId LIMIT 1") suspend fun exportRecord(eventId: String, calendarId: String): ExportRecordEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExportRecord(record: ExportRecordEntity)
}

@Database(entities = [ConfirmedEventEntity::class, DraftEventEntity::class, ExportRecordEntity::class], version = 4, exportSchema = false)
abstract class MiCampusDatabase : RoomDatabase() { abstract fun eventDao(): EventDao }
