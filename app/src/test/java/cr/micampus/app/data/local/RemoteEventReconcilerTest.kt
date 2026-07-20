package cr.micampus.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteEventReconcilerTest {
    @Test
    fun reportsChangedAndRemovedRowsWithoutTouchingUnchangedRows() {
        val unchanged = event("same", "Same")
        val previousChanged = event("changed", "Old title")
        val removed = event("removed", "Removed")
        val replacement = event("changed", "New title")
        val added = event("added", "Added")

        val result = reconcileRemoteEntities(
            existing = listOf(unchanged, previousChanged, removed),
            incoming = listOf(unchanged, replacement, added),
        )

        assertEquals(listOf("changed", "added"), result.changed.map { it.id })
        assertEquals(listOf("removed"), result.removed.map { it.id })
    }

    @Test
    fun preservesLocalReminderPreferenceWhenRemoteEventUpdates() {
        val stored = event("updated", "Old title").copy(notifyThirtyMinutesBefore = true)
        val remoteUpdate = event("updated", "New title")

        val result = reconcileRemoteEntities(existing = listOf(stored), incoming = listOf(remoteUpdate))

        assertEquals(listOf("updated"), result.changed.map { it.id })
        assertEquals(true, result.changed.single().notifyThirtyMinutesBefore)
        assertEquals(true, result.merged.single().notifyThirtyMinutesBefore)
    }

    private fun event(id: String, title: String) = ConfirmedEventEntity(
        id = id,
        title = title,
        institution = "UNA",
        kind = "TAREA",
        startEpoch = 1_000,
        endEpoch = 2_000,
        location = "",
        notes = "",
        source = "una-moodle",
        externalId = id,
    )
}
