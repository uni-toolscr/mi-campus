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
import cr.micampus.app.core.model.ReminderSettings
import cr.micampus.app.data.local.EventRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class ReminderRequest(
    val eventId: String,
    val title: String,
    val offsetMinutes: Long,
    val triggerAt: Instant,
) {
    val uniqueName: String get() = "reminder-$eventId-$offsetMinutes"
}

object ReminderPlanner {
    fun defaultOffsets(allDay: Boolean): List<Long> = if (allDay) listOf(1_440L) else listOf(1_440L, 60L)

    fun plan(
        event: CampusEvent,
        offsetsMinutes: List<Long> = defaultOffsets(event.allDay),
        clock: Clock = Clock.systemDefaultZone(),
        zone: ZoneId = clock.zone,
    ): List<ReminderRequest> {
        val now = clock.instant()
        val start = event.start.atZone(zone).toInstant()
        return offsetsMinutes.distinct().filter { it >= 0 }.map { offset ->
            ReminderRequest(event.id, event.title, offset, start.minus(Duration.ofMinutes(offset)))
        }.filter { it.triggerAt.isAfter(now) }
    }
}

enum class ReminderDelivery { EXACT, INEXACT, SKIPPED_PERMISSION, SKIPPED_PAST }
data class ReminderScheduleResult(val request: ReminderRequest?, val delivery: ReminderDelivery)

class ReminderScheduler(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun defaultOffsets(allDay: Boolean = false) = ReminderPlanner.defaultOffsets(allDay)

    fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun schedule(event: CampusEvent, settings: ReminderSettings, exactRequested: Boolean): List<ReminderScheduleResult> {
        if (!settings.enabled || !canPostNotifications()) {
            return listOf(ReminderScheduleResult(null, ReminderDelivery.SKIPPED_PERMISSION))
        }
        val offsets = if (event.allDay) settings.allDayOffsetsMinutes else settings.offsetsMinutes
        val requests = ReminderPlanner.plan(event, offsets, clock)
        if (requests.isEmpty()) return listOf(ReminderScheduleResult(null, ReminderDelivery.SKIPPED_PAST))
        return requests.map { request ->
            if (exactRequested && canScheduleExact()) {
                scheduleExact(request)
                ReminderScheduleResult(request, ReminderDelivery.EXACT)
            } else {
                scheduleInexact(request)
                ReminderScheduleResult(request, ReminderDelivery.INEXACT)
            }
        }
    }

    fun cancel(eventId: String, offsetsMinutes: List<Long> = listOf(1_440L, 60L)) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        offsetsMinutes.distinct().forEach { offset ->
            WorkManager.getInstance(context).cancelUniqueWork("reminder-$eventId-$offset")
            val request = ReminderRequest(eventId, "", offset, Instant.EPOCH)
            alarmManager.cancel(reminderPendingIntent(context, request))
        }
    }

    private fun scheduleExact(request: ReminderRequest) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            request.triggerAt.toEpochMilli(),
            reminderPendingIntent(context, request),
        )
    }

    private fun scheduleInexact(request: ReminderRequest) {
        val delay = Duration.between(clock.instant(), request.triggerAt).toMillis().coerceAtLeast(0)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .addTag(TAG_REMINDER)
            .setInputData(workDataOf(KEY_EVENT_ID to request.eventId, KEY_TITLE to request.title))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(request.uniqueName, ExistingWorkPolicy.REPLACE, work)
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_TITLE = "title"
        const val CHANNEL_ID = "campus_reminders"
        const val TAG_REMINDER = "campus-reminder"

        private fun requestCode(eventId: String, offset: Long): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest("$eventId:$offset".toByteArray())
            return java.nio.ByteBuffer.wrap(digest).int and Int.MAX_VALUE
        }

        fun reminderPendingIntent(context: Context, request: ReminderRequest): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(KEY_EVENT_ID, request.eventId)
                .putExtra(KEY_TITLE, request.title)
            return PendingIntent.getBroadcast(
                context,
                requestCode(request.eventId, request.offsetMinutes),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun postNotification(context: Context, eventId: String, title: String) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordatorios académicos", NotificationManager.IMPORTANCE_HIGH))
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentIntent = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Mi Campus")
                .setContentText(title)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(eventId.hashCode(), notification)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.postNotification(
            context,
            intent.getStringExtra(ReminderScheduler.KEY_EVENT_ID).orEmpty(),
            intent.getStringExtra(ReminderScheduler.KEY_TITLE) ?: "Tienes un evento próximo",
        )
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReminderScheduler.postNotification(
            applicationContext,
            inputData.getString(ReminderScheduler.KEY_EVENT_ID).orEmpty(),
            inputData.getString(ReminderScheduler.KEY_TITLE) ?: "Tienes un evento próximo",
        )
        return Result.success()
    }
}

class ReminderRescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MiCampusApplication ?: return Result.failure()
        val scheduler = ReminderScheduler(applicationContext)
        val settings = app.container.settings.current()
        if (!settings.remindersEnabled) return Result.success()
        app.container.events.futureEntities().map(EventRepository::toDomain).forEach { event ->
            scheduler.schedule(event, ReminderSettings(enabled = true), settings.exactReminders)
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
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
