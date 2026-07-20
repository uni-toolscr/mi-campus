package cr.micampus.app.platform.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.data.local.ExportRecordEntity
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId

enum class ExportAction { INSERT, UNCHANGED, UPDATE_REQUIRES_CONFIRMATION }

data class ExportPlan(
    val action: ExportAction,
    val contentHash: String,
    val previous: ExportRecordEntity? = null,
)

object ExportPlanner {
    fun contentHash(event: CampusEvent, calendarId: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val canonical = listOf(
            event.id,
            calendarId.toString(),
            event.title,
            event.institution.name,
            event.kind.name,
            event.start.atZone(zone).toInstant().toEpochMilli().toString(),
            event.end.atZone(zone).toInstant().toEpochMilli().toString(),
            event.location,
            event.notes,
            event.allDay.toString(),
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun plan(event: CampusEvent, calendarId: Long, previous: ExportRecordEntity?): ExportPlan {
        val hash = contentHash(event, calendarId)
        return when {
            previous == null -> ExportPlan(ExportAction.INSERT, hash)
            previous.contentHash == hash -> ExportPlan(ExportAction.UNCHANGED, hash, previous)
            else -> ExportPlan(ExportAction.UPDATE_REQUIRES_CONFIRMATION, hash, previous)
        }
    }
}

sealed interface ExportResult {
    data class Exported(val record: ExportRecordEntity, val updated: Boolean) : ExportResult
    data object Unchanged : ExportResult
    data object PermissionRequired : ExportResult
    data object ConfirmationRequired : ExportResult
    data class Failed(val message: String) : ExportResult
}

data class CalendarChoice(val id: Long, val name: String, val account: String)

class CalendarExporter(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun writableCalendars(): List<CalendarChoice> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CalendarChoice(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
            }
        }.orEmpty()
    }

    fun export(
        event: CampusEvent,
        calendarId: Long,
        previous: ExportRecordEntity?,
        confirmUpdate: Boolean = false,
    ): ExportResult {
        if (!hasPermission()) return ExportResult.PermissionRequired
        val plan = ExportPlanner.plan(event, calendarId, previous)
        if (plan.action == ExportAction.UNCHANGED) return ExportResult.Unchanged
        if (plan.action == ExportAction.UPDATE_REQUIRES_CONFIRMATION && !confirmUpdate) {
            return ExportResult.ConfirmationRequired
        }
        val values = values(event, calendarId)
        return runCatching {
            val providerId: Long
            val updated: Boolean
            if (plan.action == ExportAction.INSERT) {
                providerId = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?.lastPathSegment?.toLongOrNull() ?: error("No se pudo crear el evento")
                updated = false
            } else {
                providerId = requireNotNull(previous).providerEventId
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerId)
                check(context.contentResolver.update(uri, values, null, null) > 0) { "El evento externo ya no existe" }
                updated = true
            }
            ExportResult.Exported(
                ExportRecordEntity(
                    eventId = event.id,
                    calendarId = calendarId.toString(),
                    providerEventId = providerId,
                    exportedAtEpoch = clock.millis(),
                    contentHash = plan.contentHash,
                ),
                updated,
            )
        }.getOrElse { ExportResult.Failed(it.message ?: "Error de calendario") }
    }

    private fun values(event: CampusEvent, calendarId: Long): ContentValues {
        val zone = ZoneId.systemDefault()
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.notes)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, event.start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, event.end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, zone.id)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
        }
    }
}
