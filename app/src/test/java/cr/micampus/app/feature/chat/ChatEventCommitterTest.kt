package cr.micampus.app.feature.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEventCommitterTest {
    @Test fun noSideEffectsRunWhenPersistenceFails() = runBlocking {
        var scheduled = 0
        var refreshed = 0

        val result = commitReviewedChatEvent(
            save = { error("database") },
            schedule = { scheduled++ },
            refresh = { refreshed++ },
        )

        assertTrue(result.isFailure)
        assertEquals(0, scheduled)
        assertEquals(0, refreshed)
    }

    @Test fun persistedEventRemainsSuccessfulWhenFollowUpIntegrationsFail() = runBlocking {
        var saved = 0
        var scheduled = 0
        var refreshed = 0

        val result = commitReviewedChatEvent(
            save = { saved++ },
            schedule = { scheduled++; error("scheduler") },
            refresh = { refreshed++; error("widget") },
        )

        assertTrue(result.isSuccess)
        assertEquals(1, saved)
        assertEquals(1, scheduled)
        assertEquals(1, refreshed)
    }
}
