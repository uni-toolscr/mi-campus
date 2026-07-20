package cr.micampus.app.data.moodle

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.data.local.MoodleContentSyncStateEntity
import cr.micampus.app.data.local.MoodleEventStore
import cr.micampus.app.data.local.MoodleSyncSettings
import cr.micampus.app.data.local.RemoteSyncChanges
import cr.micampus.app.platform.reminders.ReminderScheduleResult
import cr.micampus.app.platform.reminders.ReminderScheduling
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodleSyncManagerTest {
    @Test fun successfulInvalidTokenProbeKeepsAccount() = runBlocking {
        val account = MoodleAccount("token-a", 42, "Estudiante")
        val accounts = FakeAccounts(account)
        val manager = manager(accounts, response = { _, _ -> MoodleHttpResponse(200, siteInfoJson()) })

        assertFalse(manager.handleInvalidToken())
        assertEquals(account, accounts.read())
    }

    @Test fun confirmedInvalidTokenClearsAccountButKeepsContents() = runBlocking {
        val account = MoodleAccount("token-a", 42, "Estudiante")
        val accounts = FakeAccounts(account)
        val contents = FakeContents()
        val manager = manager(
            accounts = accounts,
            contents = contents,
            response = { _, _ -> MoodleHttpResponse(200, "{\"errorcode\":\"invalidtoken\",\"message\":\"Token inválido\"}") },
        )

        assertTrue(manager.handleInvalidToken())
        assertNull(accounts.read())
        assertEquals(0, contents.clearCalls)
    }

    @Test fun accountReplacedDuringProbeIsNotCleared() = runBlocking {
        val original = MoodleAccount("token-a", 42, "Estudiante")
        val replacement = original.copy(token = "token-b")
        val accounts = FakeAccounts(original)
        val manager = manager(
            accounts = accounts,
            response = { _, _ ->
                accounts.save(replacement)
                MoodleHttpResponse(200, "{\"errorcode\":\"invalidtoken\",\"message\":\"Token inválido\"}")
            },
        )

        assertFalse(manager.handleInvalidToken())
        assertEquals(replacement, accounts.read())
    }

    @Test fun syncDoesNotRefreshContentsWithinAttemptInterval() = runBlocking {
        val now = 1_700_000_000_000L
        val account = MoodleAccount("token-a", 42, "Estudiante")
        val contents = FakeContents(lastAttemptEpoch = now - 1_000)
        val calls = mutableListOf<String>()
        val manager = manager(
            accounts = FakeAccounts(account),
            contents = contents,
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            response = { _, form ->
                calls += form.getValue("wsfunction")
                when (form.getValue("wsfunction")) {
                    MoodleClient.SITE_INFO -> MoodleHttpResponse(200, siteInfoJson(includeContents = true))
                    MoodleClient.USERS_COURSES -> MoodleHttpResponse(200, "[]")
                    MoodleClient.ACTION_EVENTS -> MoodleHttpResponse(200, "{\"events\":[]}")
                    MoodleClient.CONTENTS -> error("Content catalog should be throttled")
                    else -> error("Unexpected request")
                }
            },
        )

        manager.sync()

        assertFalse(calls.contains(MoodleClient.CONTENTS))
        assertEquals(0, contents.replaceCalls)
    }

    private fun manager(
        accounts: FakeAccounts,
        contents: FakeContents? = null,
        clock: Clock = Clock.systemUTC(),
        response: (String, Map<String, String>) -> MoodleHttpResponse = { _, _ -> MoodleHttpResponse(200, siteInfoJson()) },
    ) = MoodleSyncManager(
        client = MoodleClient(object : MoodleTransport {
            override fun post(url: String, form: Map<String, String>) = response(url, form)
        }, baseUrl = "https://moodle.test", zoneId = ZoneOffset.UTC),
        accounts = accounts,
        events = FakeEvents(),
        settings = FakeSettings(),
        reminders = FakeReminders(),
        scheduler = FakeScheduler(),
        contents = contents,
        clock = clock,
    )

    private class FakeAccounts(private var account: MoodleAccount?) : MoodleAccountStorage {
        override fun read() = account
        override fun save(account: MoodleAccount) { this.account = account }
        override fun clear() { account = null }
    }

    private class FakeEvents : MoodleEventStore {
        override suspend fun replaceRemoteWindow(source: String, from: Instant, to: Instant, events: List<CampusEvent>) =
            RemoteSyncChanges(emptyList(), emptyList())
        override suspend fun clearRemoteSource(source: String) = emptyList<CampusEvent>()
    }

    private class FakeSettings : MoodleSyncSettings {
        override suspend fun current() = AppSettings()
    }

    private class FakeReminders : ReminderScheduling {
        override fun schedule(event: CampusEvent, enabled: Boolean, exactRequested: Boolean) = emptyList<ReminderScheduleResult>()
        override fun cancel(eventId: String) = Unit
    }

    private class FakeScheduler : MoodleSyncScheduling {
        override fun schedule() = Unit
        override fun cancel() = Unit
    }

    private class FakeContents(lastAttemptEpoch: Long? = null) : MoodleContentSyncStore {
        var clearCalls = 0
        var replaceCalls = 0
        private val state = MoodleContentSyncStateEntity("una:42", lastAttemptEpoch = lastAttemptEpoch)

        override fun syncState(account: MoodleAccount): Flow<MoodleContentSyncStateEntity?> = flowOf(state)
        override suspend fun replaceCatalog(catalog: MoodleContentCatalog.Available) { replaceCalls++ }
        override suspend fun markUnsupported(account: MoodleAccount) = Unit
        override suspend fun markRefreshFailure(account: MoodleAccount, message: String) = Unit
        override suspend fun clearAccount(account: MoodleAccount): Boolean { clearCalls++; return true }
    }

    private fun siteInfoJson(includeContents: Boolean = false) = """
        {"userid":42,"fullname":"Estudiante","functions":[
          {"name":"${MoodleClient.SITE_INFO}"},
          {"name":"${MoodleClient.USERS_COURSES}"},
          {"name":"${MoodleClient.ACTION_EVENTS}"}${if (includeContents) ",{\"name\":\"${MoodleClient.CONTENTS}\"}" else ""}
        ]}
    """.trimIndent()
}
