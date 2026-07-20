package cr.micampus.app.platform.reminders

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("America/Costa_Rica")
    private val now = Instant.parse("2026-07-16T12:00:00Z") // 06:00 in Costa Rica
    private val clock = Clock.fixed(now, zone)

    @Test fun primaryPolicyUsesTheRequiredAdvanceForEachKind() {
        assertEquals(7L, ReminderPlanner.primaryOffset(EventKind.EXAM)?.toDays())
        assertEquals(2L, ReminderPlanner.primaryOffset(EventKind.QUIZ)?.toDays())
        assertEquals(2L, ReminderPlanner.primaryOffset(EventKind.TAREA)?.toDays())
        assertEquals(1L, ReminderPlanner.primaryOffset(EventKind.ACTIVITY)?.toDays())
        assertEquals(null, ReminderPlanner.primaryOffset(EventKind.CLASS))
        assertEquals(null, ReminderPlanner.primaryOffset(EventKind.TRANSIT))
    }

    @Test fun primaryReminderIsSevenDaysBeforeAnExam() {
        val requests = ReminderPlanner.plan(event(EventKind.EXAM, LocalDateTime.of(2026, 7, 24, 9, 0)), clock, zone)

        assertEquals(1, requests.size)
        assertEquals(ReminderType.PRIMARY, requests.single().type)
        assertEquals(Instant.parse("2026-07-17T15:00:00Z"), requests.single().triggerAt)
    }

    @Test fun classAndTransitHaveNoRemindersEvenWhenThirtyMinuteOptionIsEnabled() {
        val classEvent = event(EventKind.CLASS, LocalDateTime.of(2026, 7, 20, 9, 0), notifyThirty = true)
        val transitEvent = event(EventKind.TRANSIT, LocalDateTime.of(2026, 7, 20, 9, 0), notifyThirty = true)

        assertTrue(ReminderPlanner.plan(classEvent, clock, zone).isEmpty())
        assertTrue(ReminderPlanner.plan(transitEvent, clock, zone).isEmpty())
    }

    @Test fun classAndTransitHaveNoPrimaryReminder() {
        assertTrue(ReminderPlanner.plan(event(EventKind.CLASS, LocalDateTime.of(2026, 7, 20, 9, 0)), clock, zone).isEmpty())
        assertTrue(ReminderPlanner.plan(event(EventKind.TRANSIT, LocalDateTime.of(2026, 7, 20, 9, 0)), clock, zone).isEmpty())
    }

    @Test fun optionalThirtyMinuteReminderIsScheduledForEligibleTimedEvent() {
        val requests = ReminderPlanner.plan(event(EventKind.QUIZ, LocalDateTime.of(2026, 7, 19, 7, 0), notifyThirty = true), clock, zone)

        assertEquals(listOf(ReminderType.PRIMARY, ReminderType.THIRTY_MINUTES), requests.map(ReminderRequest::type))
        assertEquals(Instant.parse("2026-07-17T13:00:00Z"), requests[0].triggerAt)
        assertEquals(Instant.parse("2026-07-19T12:30:00Z"), requests[1].triggerAt)
    }

    @Test fun allDayEventUsesKindAdvanceAtNineAmAndSuppressesOptionalThirtyMinuteReminder() {
        val requests = ReminderPlanner.plan(
            event(EventKind.EXAM, LocalDateTime.of(2026, 7, 24, 0, 0), allDay = true, notifyThirty = true),
            clock,
            zone,
        )

        assertEquals(listOf(ReminderType.PRIMARY), requests.map(ReminderRequest::type))
        assertEquals(Instant.parse("2026-07-17T15:00:00Z"), requests.single().triggerAt)
    }

    @Test fun allDayClassAndTransitDoNotReceiveReminders() {
        assertTrue(ReminderPlanner.plan(event(EventKind.CLASS, LocalDateTime.of(2026, 7, 20, 0, 0), allDay = true), clock, zone).isEmpty())
        assertTrue(ReminderPlanner.plan(event(EventKind.TRANSIT, LocalDateTime.of(2026, 7, 20, 0, 0), allDay = true), clock, zone).isEmpty())
    }

    @Test fun elapsedCandidatesCollapseToOneImmediateReminderWhileFutureCandidateIsPreserved() {
        val requests = ReminderPlanner.plan(event(EventKind.EXAM, LocalDateTime.of(2026, 7, 16, 7, 0), notifyThirty = true), clock, zone)

        assertEquals(listOf(ReminderType.PRIMARY, ReminderType.THIRTY_MINUTES), requests.map(ReminderRequest::type))
        assertEquals(now, requests[0].triggerAt)
        assertEquals(Instant.parse("2026-07-16T12:30:00Z"), requests[1].triggerAt)
    }

    @Test fun pastEventDoesNotProduceAnImmediateReminder() {
        assertTrue(ReminderPlanner.plan(event(EventKind.EXAM, LocalDateTime.of(2026, 7, 16, 5, 0)), clock, zone).isEmpty())
    }

    @Test fun identityIsStablePerReminderTypeRatherThanOffset() {
        val event = event(EventKind.EXAM, LocalDateTime.of(2026, 7, 24, 9, 0), notifyThirty = true)
        val requests = ReminderPlanner.plan(event, clock, zone)

        assertEquals(2, requests.map(ReminderRequest::uniqueName).distinct().size)
        assertTrue(requests.any { it.uniqueName.endsWith("primary") })
        assertTrue(requests.any { it.uniqueName.endsWith("thirty_minutes") })
    }

    @Test fun legacyWorkIdentityRemainsAvailableForUpgradeCleanup() {
        assertEquals("reminder-e1-1440", ReminderScheduler.legacyWorkName("e1", 1_440L))
        assertEquals("reminder-e1-60", ReminderScheduler.legacyWorkName("e1", 60L))
    }

    private fun event(
        kind: EventKind,
        start: LocalDateTime,
        allDay: Boolean = false,
        notifyThirty: Boolean = false,
    ) = CampusEvent(
        id = "e1",
        title = "Examen",
        institution = Institution.UCR,
        kind = kind,
        start = start,
        end = start.plusHours(2),
        allDay = allDay,
        notifyThirtyMinutesBefore = notifyThirty,
    )
}
