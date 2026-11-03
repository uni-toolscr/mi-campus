package cr.micampus.app.platform.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.data.institution.directionKey
import cr.micampus.app.data.institution.upcomingDepartures
import cr.micampus.app.data.local.EventRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BusWidgetAlarmPrecision { EXACT, INEXACT }

fun busWidgetAlarmPrecision(apiLevel: Int, canScheduleExact: Boolean): BusWidgetAlarmPrecision =
    if (apiLevel < Build.VERSION_CODES.S || canScheduleExact) {
        BusWidgetAlarmPrecision.EXACT
    } else {
        BusWidgetAlarmPrecision.INEXACT
    }

interface BusWidgetAlarm {
    fun replace(atMillis: Long, precision: BusWidgetAlarmPrecision)
    fun cancel()
}

internal class AndroidBusWidgetAlarm(private val context: Context) : BusWidgetAlarm {
    private val manager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun replace(atMillis: Long, precision: BusWidgetAlarmPrecision) {
        val operation = pendingIntent(context)
        manager.cancel(operation)
        if (precision == BusWidgetAlarmPrecision.EXACT) {
            try {
                manager.setExact(AlarmManager.RTC, atMillis, operation)
                return
            } catch (_: SecurityException) {
                // Exact access can be revoked after canScheduleExactAlarms() is checked.
            }
        }
        manager.set(AlarmManager.RTC, atMillis, operation)
    }

    override fun cancel() {
        manager.cancel(pendingIntent(context))
    }

    companion object {
        private const val ACTION_REFRESH = "cr.micampus.app.widget.BUS_REFRESH"

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BusWidgetAlarmReceiver::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/**
 * Owns the single, replaceable local alarm behind every minute countdown on the home screen: bus
 * departures and the class widget's start/end countdown. The alarm is deliberately non-wakeup: a
 * sleeping device does not need to redraw a launcher that is not visible.
 */
class BusWidgetRefreshCoordinator(
    private val context: Context,
    private val transport: AssetTransportRepository,
    private val events: EventRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val alarm: BusWidgetAlarm = AndroidBusWidgetAlarm(context),
) {
    private val synchronizationMutex = Mutex()

    suspend fun synchronize() = synchronizationMutex.withLock {
        synchronizeLocked()
    }

    private suspend fun synchronizeLocked() {
        val manager = GlanceAppWidgetManager(context)
        val widget = BusesWidget()
        val ids = manager.getGlanceIds(BusesWidget::class.java)
        val classIds = manager.getGlanceIds(ClassWidget::class.java)
        if (ids.isEmpty() && classIds.isEmpty()) {
            alarm.cancel()
            return
        }

        val now = LocalDateTime.now(clock)
        val busPlans = ids.mapNotNull { id ->
            val prefs = runCatching {
                widget.getAppWidgetState<Preferences>(context, id)
            }.getOrNull() ?: return@mapNotNull null
            val filter = prefs[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val directionIds = parseDirectionFilter(prefs[BUS_WIDGET_DIRECTIONS_KEY])
            nextDepartureAcrossServiceDays(institutionsFor(filter), directionIds, now)
                ?.let { busWidgetRefreshPlan(it.departure, now) }
        }

        val combinedPlan = earliestBusWidgetRefresh(busPlans + classPlans(classIds.isNotEmpty(), now))
        val nextAt = (combinedPlan as? BusWidgetRefreshPlan.RefreshAt)?.at
        if (nextAt == null) {
            alarm.cancel()
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val precision = busWidgetAlarmPrecision(Build.VERSION.SDK_INT, canScheduleExact)
        alarm.replace(nextAt.atZone(clock.zone).toInstant().toEpochMilli(), precision)
    }

    // Institution filters only narrow which class leads, so planning for the earliest class overall is
    // at worst an extra refresh, never a missed one.
    private suspend fun classPlans(hasClassWidgets: Boolean, now: LocalDateTime): List<BusWidgetRefreshPlan> {
        if (!hasClassWidgets) return emptyList()
        val classes = runCatching { upcomingClasses(events.allEntities().map { EventRepository.toDomain(it) }, now) }.getOrDefault(emptyList())
        return listOfNotNull(classes.firstOrNull()?.let { classWidgetRefreshPlan(it, now) })
    }

    suspend fun refreshAndSynchronize(includeEvents: Boolean = false) {
        runCatching { BusesWidget().updateAll(context) }
        runCatching { ClassWidget().updateAll(context) }
        // Relative day labels ("Mañana") only change with the date, not with the minute alarm.
        if (includeEvents) runCatching { EventsWidget().updateAll(context) }
        synchronize()
    }

    private fun nextDepartureAcrossServiceDays(
        institutions: Collection<Institution>,
        directionIds: Set<String>,
        now: LocalDateTime,
    ): UpcomingDeparture? {
        val today = transport.upcomingDepartures(
            institutions = institutions,
            now = now,
            limit = Int.MAX_VALUE,
            directionIds = directionIds,
            departureGrace = busWidgetDepartureGrace,
        ).firstOrNull()
        if (today != null) return today

        return institutions.flatMap { institution ->
            transport.directions(institution)
                .filter { directionIds.isEmpty() || directionKey(institution, it.id) in directionIds }
                .mapNotNull { direction ->
                    nextFutureDeparture(institution, direction.id, direction.from, direction.to, now.toLocalDate())
                }
        }.minByOrNull(UpcomingDeparture::departure)
    }

    private fun nextFutureDeparture(
        institution: Institution,
        directionId: String,
        from: String,
        to: String,
        today: LocalDate,
    ): UpcomingDeparture? {
        val date = transport.nextValidDate(institution, directionId, today.plusDays(1)) ?: return null
        val service = transport.service(institution, directionId, date)
        if (service.status != ServiceStatus.VERIFIED) return null
        val departure = service.departures.asSequence()
            .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
            .minOrNull()
            ?: return null
        return UpcomingDeparture(institution, directionId, from, to, LocalDateTime.of(date, departure))
    }
}

class BusWidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runBusWidgetReceiverWork(context) { it.refreshAndSynchronize() }
    }
}

class BusWidgetSystemChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in supportedActions) return
        runBusWidgetReceiverWork(context) { it.refreshAndSynchronize(includeEvents = true) }
    }

    companion object {
        private val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )

        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

internal fun BroadcastReceiver.runBusWidgetReceiverWork(
    context: Context,
    work: suspend (BusWidgetRefreshCoordinator) -> Unit,
) {
    val pendingResult = goAsync()
    val app = context.applicationContext as MiCampusApplication
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            work(app.container.busWidgetRefresh)
        } finally {
            pendingResult.finish()
        }
    }
}

internal fun scheduleBusWidgetSynchronization(context: Context) {
    val request = OneTimeWorkRequestBuilder<BusWidgetSynchronizationWorker>().build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        BUS_WIDGET_SYNCHRONIZATION_WORK,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

class BusWidgetSynchronizationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MiCampusApplication
        return runCatching {
            app.container.busWidgetRefresh.synchronize()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}

private const val BUS_WIDGET_SYNCHRONIZATION_WORK = "bus-widget-synchronization"
