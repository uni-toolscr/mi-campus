package cr.micampus.app.platform.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.local.EventRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The two stable identities make cancellation independent of changing reminder policies. */
enum class ReminderType { PRIMARY, THIRTY_MINUTES }

data class ReminderRequest(
    val eventId: String,
    val title: String,
    val kind: EventKind,
    val type: ReminderType,
    val triggerAt: Instant,
    val eventStart: Instant,
) {
    val uniqueName: String get() = "reminder-$eventId-${type.name.lowercase()}"
}

object ReminderPlanner {
    fun primaryOffset(eventKind: EventKind): Duration? = when (eventKind) {
        EventKind.EXAM -> Duration.ofDays(7)
        EventKind.QUIZ, EventKind.TAREA -> Duration.ofDays(2)
        EventKind.ACTIVITY -> Duration.ofDays(1)
        EventKind.CLASS, EventKind.TRANSIT -> null
    }

    fun plan(
        event: CampusEvent,
        clock: Clock = Clock.systemDefaultZone(),
        zone: ZoneId = clock.zone,
    ): List<ReminderRequest> {
        val now = clock.instant()
        val eventStart = event.start.atZone(zone).toInstant()
        if (!eventStart.isAfter(now)) return emptyList()
        val candidates = buildList {
            if (event.allDay) {
                primaryOffset(event.kind)?.let { offset ->
                    val atNine = event.start.toLocalDate().minusDays(offset.toDays()).atTime(9, 0).atZone(zone).toInstant()
                    add(request(event, ReminderType.PRIMARY, atNine, eventStart))
                }
            } else {
                primaryOffset(event.kind)?.let { offset ->
                    add(request(event, ReminderType.PRIMARY, eventStart.minus(offset), eventStart))
                }
                if (event.notifyThirtyMinutesBefore && primaryOffset(event.kind) != null) {
                    add(request(event, ReminderType.THIRTY_MINUTES, eventStart.minus(Duration.ofMinutes(30)), eventStart))
                }
            }
        }
        val future = candidates.filter { it.triggerAt.isAfter(now) }
        val past = candidates.filterNot { it.triggerAt.isAfter(now) }
        // An event can still be useful when its advance reminder has elapsed. One immediate
        // notification avoids losing it or producing a burst from every elapsed candidate.
        return buildList {
            if (past.isNotEmpty()) add(request(event, ReminderType.PRIMARY, now, eventStart))
            addAll(future.filterNot { it.type == ReminderType.PRIMARY && past.isNotEmpty() })
        }
    }

    private fun request(event: CampusEvent, type: ReminderType, triggerAt: Instant, eventStart: Instant) =
        ReminderRequest(event.id, event.title, event.kind, type, triggerAt, eventStart)
}

enum class ReminderDelivery { EXACT, INEXACT, SKIPPED_PERMISSION, SKIPPED_PAST, SKIPPED_DISABLED }
data class ReminderScheduleResult(val request: ReminderRequest?, val delivery: ReminderDelivery)

interface ReminderScheduling {
    fun schedule(event: CampusEvent, enabled: Boolean, exactRequested: Boolean): List<ReminderScheduleResult>
    fun cancel(eventId: String)
}

class ReminderScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ReminderScheduling {
    fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    override fun schedule(event: CampusEvent, enabled: Boolean, exactRequested: Boolean): List<ReminderScheduleResult> {
        cancel(event.id)
        if (!enabled) return listOf(ReminderScheduleResult(null, ReminderDelivery.SKIPPED_DISABLED))
        if (!canPostNotifications()) return listOf(ReminderScheduleResult(null, ReminderDelivery.SKIPPED_PERMISSION))
        val requests = ReminderPlanner.plan(event, clock)
        if (requests.isEmpty()) return listOf(ReminderScheduleResult(null, ReminderDelivery.SKIPPED_PAST))
        return requests.map { request ->
            if (exactRequested && canScheduleExact()) {
                try {
                    scheduleExact(request)
                    ReminderScheduleResult(request, ReminderDelivery.EXACT)
                } catch (_: SecurityException) {
                    // Special access can be revoked between canScheduleExactAlarms() and this call.
                    scheduleInexact(request)
                    ReminderScheduleResult(request, ReminderDelivery.INEXACT)
                }
            } else {
                scheduleInexact(request)
                ReminderScheduleResult(request, ReminderDelivery.INEXACT)
            }
        }
    }

    override fun cancel(eventId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(eventTag(eventId))
        ReminderType.entries.forEach { type ->
            alarmManager.cancel(reminderPendingIntent(context, eventId, type))
        }
        // Compatibility cleanup for reminders scheduled by versions that used raw offsets as
        // identities. Keep this until installations from that scheme have had time to upgrade.
        LEGACY_OFFSETS_MINUTES.forEach { offset ->
            workManager.cancelUniqueWork(legacyWorkName(eventId, offset))
            val pendingIntent = legacyReminderPendingIntent(context, eventId, offset)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun scheduleExact(request: ReminderRequest) {
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            request.triggerAt.toEpochMilli(),
            reminderPendingIntent(context, request),
        )
    }

    private fun scheduleInexact(request: ReminderRequest) {
        val delay = Duration.between(clock.instant(), request.triggerAt).toMillis().coerceAtLeast(0)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .addTag(TAG_REMINDER)
            .addTag(eventTag(request.eventId))
            .setInputData(request.workData())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(request.uniqueName, ExistingWorkPolicy.REPLACE, work)
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_TITLE = "title"
        const val KEY_CATEGORY = "category"
        const val KEY_START_MILLIS = "start_millis"
        const val KEY_REMINDER_TYPE = "reminder_type"
        const val CHANNEL_ID = "campus_reminders"
        const val TAG_REMINDER = "campus-reminder"
        private val LEGACY_OFFSETS_MINUTES = listOf(1_440L, 60L)

        private fun eventTag(eventId: String) = "$TAG_REMINDER-event-$eventId"

        internal fun legacyWorkName(eventId: String, offsetMinutes: Long) = "reminder-$eventId-$offsetMinutes"

        private fun requestCode(eventId: String, type: ReminderType): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest("$eventId:${type.name}".toByteArray())
            return java.nio.ByteBuffer.wrap(digest).int and Int.MAX_VALUE
        }

        private fun legacyRequestCode(eventId: String, offsetMinutes: Long): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest("$eventId:$offsetMinutes".toByteArray())
            return java.nio.ByteBuffer.wrap(digest).int and Int.MAX_VALUE
        }

        private fun legacyReminderPendingIntent(context: Context, eventId: String, offsetMinutes: Long): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(KEY_EVENT_ID, eventId)
            return PendingIntent.getBroadcast(
                context,
                legacyRequestCode(eventId, offsetMinutes),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun reminderPendingIntent(context: Context, request: ReminderRequest): PendingIntent =
            reminderPendingIntent(context, request.eventId, request.type, request)

        private fun reminderPendingIntent(context: Context, eventId: String, type: ReminderType, request: ReminderRequest? = null): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction("$CHANNEL_ID.${type.name}")
                .putExtra(KEY_EVENT_ID, eventId)
            request?.let {
                intent.putExtra(KEY_TITLE, it.title)
                    .putExtra(KEY_CATEGORY, categoryName(it.kind))
                    .putExtra(KEY_START_MILLIS, it.eventStart.toEpochMilli())
                    .putExtra(KEY_REMINDER_TYPE, it.type.name)
            }
            return PendingIntent.getBroadcast(context, requestCode(eventId, type), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun ReminderRequest.workData() = workDataOf(
            KEY_EVENT_ID to eventId,
            KEY_TITLE to title,
            KEY_CATEGORY to categoryName(kind),
            KEY_START_MILLIS to eventStart.toEpochMilli(),
            KEY_REMINDER_TYPE to type.name,
        )

        private fun categoryName(kind: EventKind): String = when (kind) {
            EventKind.EXAM -> "Examen"
            EventKind.QUIZ -> "Prueba corta"
            EventKind.TAREA -> "Tarea"
            EventKind.ACTIVITY -> "Actividad"
            EventKind.CLASS -> "Clase"
            EventKind.TRANSIT -> "Transporte"
        }

        fun postNotification(context: Context, eventId: String, title: String, category: String, startMillis: Long, type: String) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordatorios académicos", NotificationManager.IMPORTANCE_HIGH))
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentIntent = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val time = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.forLanguageTag("es-CR")))
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$category: $title")
                .setContentText("Programado para $time")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify("$eventId:$type".hashCode(), notification)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.postNotification(context, intent.getStringExtra(ReminderScheduler.KEY_EVENT_ID).orEmpty(), intent.getStringExtra(ReminderScheduler.KEY_TITLE) ?: "Evento próximo", intent.getStringExtra(ReminderScheduler.KEY_CATEGORY) ?: "Recordatorio", intent.getLongExtra(ReminderScheduler.KEY_START_MILLIS, 0), intent.getStringExtra(ReminderScheduler.KEY_REMINDER_TYPE) ?: ReminderType.PRIMARY.name)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReminderScheduler.postNotification(applicationContext, inputData.getString(ReminderScheduler.KEY_EVENT_ID).orEmpty(), inputData.getString(ReminderScheduler.KEY_TITLE) ?: "Evento próximo", inputData.getString(ReminderScheduler.KEY_CATEGORY) ?: "Recordatorio", inputData.getLong(ReminderScheduler.KEY_START_MILLIS, 0), inputData.getString(ReminderScheduler.KEY_REMINDER_TYPE) ?: ReminderType.PRIMARY.name)
        return Result.success()
    }
}

class ReminderRescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MiCampusApplication ?: return Result.failure()
        val settings = app.container.settings.current()
        if (!settings.remindersEnabled) return Result.success()
        val scheduler = ReminderScheduler(applicationContext)
        app.container.events.futureEntities().map(EventRepository::toDomain).forEach { event ->
            scheduler.schedule(event, enabled = true, exactRequested = settings.exactReminders)
        }
        return Result.success()
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val work = OneTimeWorkRequestBuilder<ReminderRescheduleWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("reschedule-reminders", ExistingWorkPolicy.REPLACE, work)
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
    }
}
