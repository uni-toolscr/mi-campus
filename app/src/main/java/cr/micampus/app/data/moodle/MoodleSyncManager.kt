package cr.micampus.app.data.moodle

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.data.local.MoodleEventStore
import cr.micampus.app.data.local.MoodleSyncSettings
import cr.micampus.app.platform.reminders.ReminderScheduling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class MoodleSyncReport(
    val imported: Int,
    val changed: Int,
    val removed: Int,
    val syncedAtEpoch: Long,
)

class MoodleSyncManager(
    private val client: MoodleClient,
    private val accounts: MoodleAccountStorage,
    private val events: MoodleEventStore,
    private val settings: MoodleSyncSettings,
    private val reminders: ReminderScheduling,
    private val scheduler: MoodleSyncScheduling,
    private val contents: MoodleContentSyncStore? = null,
    private val onDataChanged: suspend () -> Unit = {},
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val lifecycleMutex = Mutex()

    fun account(): MoodleAccount? = accounts.read()

    suspend fun connect(username: String, password: CharArray): MoodleSyncReport = lifecycleMutex.withLock {
        val previous = accounts.read()
        val account = client.authenticate(username, password)
        if (previous != null && previous.userId != account.userId) {
            contents?.clearAccount(previous)
            events.clearRemoteSource(MoodleClient.SOURCE).forEach { reminders.cancel(it.id) }
        }
        accounts.save(account)
        scheduler.schedule()
        syncLocked(account)
    }

    suspend fun sync(): MoodleSyncReport = lifecycleMutex.withLock {
        val account = accounts.read()
            ?: throw MoodleException(MoodleFailureKind.NOT_CONNECTED, "Aula Virtual is not connected")
        syncLocked(account)
    }

    suspend fun refreshContents() = lifecycleMutex.withLock {
        val account = accounts.read()
            ?: throw MoodleException(MoodleFailureKind.NOT_CONNECTED, "Aula Virtual is not connected")
        refreshContentsLocked(account, force = true)
    }

    suspend fun disconnect() = lifecycleMutex.withLock {
        val account = accounts.read()
        if (account != null) contents?.clearAccount(account)
        val removed = events.clearRemoteSource(MoodleClient.SOURCE)
        removed.forEach { reminders.cancel(it.id) }
        accounts.clear()
        scheduler.cancel()
        onDataChanged()
    }

    /**
     * Handles a confirmed dead session. Unlike [disconnect], cached events, reminders, the content
     * catalog, and downloads are kept: the data was valid when synced and the app is offline-first.
     * Everything local is removed only when the user disconnects explicitly or connects a
     * different account.
     */
    suspend fun invalidateCredentials() = lifecycleMutex.withLock {
        accounts.clear()
        scheduler.cancel()
        onDataChanged()
    }

    /**
     * Distinguishes a genuinely expired token from a transient `invalidtoken` answer (UNA's proxy
     * returns those under load). Only a corroborated expiry clears the session.
     * Returns true when the session was invalidated.
     */
    suspend fun handleInvalidToken(): Boolean {
        val account = accounts.read() ?: return true
        val confirmed = try {
            client.verifyTokenInvalid(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!confirmed) return false
        return lifecycleMutex.withLock {
            val current = accounts.read()
            if (current == null || current.token != account.token || current.userId != account.userId) return@withLock false
            accounts.clear()
            scheduler.cancel()
            onDataChanged()
            true
        }
    }

    private suspend fun syncLocked(account: MoodleAccount): MoodleSyncReport {
        val now = clock.instant()
        val from = now.minus(LOOKBACK)
        val to = now.plus(LOOKAHEAD)
        val session = client.fetchSnapshotSession(account, from, to)
        val snapshot = session.snapshot
        val changes = events.replaceRemoteWindow(MoodleClient.SOURCE, from, to, snapshot.events)
        val appSettings = settings.current()
        changes.removed.forEach { reminders.cancel(it.id) }
        changes.changed.forEach { event ->
            reminders.cancel(event.id)
            if (appSettings.remindersEnabled && event.end.atZone(clock.zone).toInstant().isAfter(now)) {
                reminders.schedule(event, enabled = true, exactRequested = appSettings.exactReminders)
            }
        }
        val syncedAt = clock.millis()
        refreshContentsLocked(snapshot.account, force = false, preloaded = session.preloaded)
        accounts.save(snapshot.account.copy(lastSyncEpoch = syncedAt))
        onDataChanged()
        return MoodleSyncReport(
            imported = snapshot.events.size,
            changed = changes.changed.size,
            removed = changes.removed.size,
            syncedAtEpoch = syncedAt,
        )
    }

    private suspend fun refreshContentsLocked(
        account: MoodleAccount,
        force: Boolean,
        preloaded: MoodlePreloadedSession? = null,
    ) {
        val repository = contents ?: return
        if (!force) {
            val state = repository.syncState(account).first()
            val now = clock.millis()
            if (listOfNotNull(state?.lastSuccessfulSyncEpoch, state?.lastAttemptEpoch)
                    .any { now - it < CONTENT_MIN_INTERVAL.toMillis() }
            ) return
        }
        try {
            when (val catalog = client.fetchContentCatalog(account, preloaded)) {
                is MoodleContentCatalog.Available -> repository.replaceCatalog(catalog)
                is MoodleContentCatalog.Unsupported -> repository.markUnsupported(catalog.account)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MoodleException) {
            if (error.kind == MoodleFailureKind.INVALID_TOKEN) throw error
            repository.markRefreshFailure(account, contentFailureMessage(error))
        } catch (_: Exception) {
            repository.markRefreshFailure(account, "No se pudieron actualizar los contenidos de Aula Virtual")
        }
    }

    private fun contentFailureMessage(error: MoodleException): String {
        val base = "No se pudieron actualizar los contenidos de Aula Virtual"
        return error.errorCode?.let { "$base ($it)" } ?: base
    }

    private companion object {
        val LOOKBACK: Duration = Duration.ofDays(1)
        val LOOKAHEAD: Duration = Duration.ofDays(180)
        val CONTENT_MIN_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}

interface MoodleSyncScheduling {
    fun schedule()
    fun cancel()
}

class MoodleSyncScheduler(private val context: Context) : MoodleSyncScheduling {
    override fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<MoodleSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "una-moodle-sync"
    }
}

class MoodleSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MiCampusApplication ?: return Result.failure()
        return try {
            if (app.container.moodle.account() == null) Result.success()
            else {
                app.container.moodle.sync()
                Result.success()
            }
        } catch (error: MoodleException) {
            if (error.kind == MoodleFailureKind.INVALID_TOKEN) {
                val invalidated = try {
                    app.container.moodle.handleInvalidToken()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                return if (invalidated) Result.failure() else Result.retry()
            }
            if (error.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
