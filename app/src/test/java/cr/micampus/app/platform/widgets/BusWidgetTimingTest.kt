package cr.micampus.app.platform.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class BusWidgetTimingTest {
    private val departure = LocalDateTime.of(2026, 7, 10, 14, 15)

    @Test fun presentationUsesScheduledOutsideTheThirtyMinuteWindow() {
        assertEquals(
            BusCountdownPresentation.Scheduled,
            busCountdownPresentation(departure, departure.minusMinutes(31)),
        )
    }

    @Test fun presentationUsesCeilingRoundedWholeMinutesInsideTheWindow() {
        assertEquals(BusCountdownPresentation.Minutes(30), busCountdownPresentation(departure, departure.minusMinutes(30)))
        assertEquals(BusCountdownPresentation.Minutes(1), busCountdownPresentation(departure, departure.minusSeconds(59)))
        assertEquals(BusCountdownPresentation.Minutes(1), busCountdownPresentation(departure, departure.minusSeconds(1)))
        assertEquals(BusCountdownPresentation.Minutes(2), busCountdownPresentation(departure, departure.minusMinutes(1).minusSeconds(1)))
        assertEquals(BusCountdownPresentation.Minutes(2), busCountdownPresentation(departure, departure.minusSeconds(60).minusNanos(1)))
    }

    @Test fun presentationShowsDepartingNowThenExpiresAtTwoSixteen() {
        assertEquals(BusCountdownPresentation.DepartingNow, busCountdownPresentation(departure, departure))
        assertEquals(BusCountdownPresentation.DepartingNow, busCountdownPresentation(departure, departure.plusSeconds(59)))
        assertNull(busCountdownPresentation(departure, departure.plusMinutes(1)))
    }

    @Test fun planTransitionsAtCountdownStartMinuteBoundaryAndExpiry() {
        assertEquals(
            BusWidgetRefreshPlan.RefreshAt(departure.minusMinutes(30)),
            busWidgetRefreshPlan(departure, departure.minusMinutes(31)),
        )
        assertEquals(
            BusWidgetRefreshPlan.RefreshAt(departure.minusMinutes(29)),
            busWidgetRefreshPlan(departure, departure.minusMinutes(30)),
        )
        assertEquals(
            BusWidgetRefreshPlan.RefreshAt(departure),
            busWidgetRefreshPlan(departure, departure.minusSeconds(1)),
        )
        assertEquals(
            BusWidgetRefreshPlan.RefreshAt(departure.plusMinutes(1)),
            busWidgetRefreshPlan(departure, departure),
        )
        assertEquals(BusWidgetRefreshPlan.NoRefresh, busWidgetRefreshPlan(departure, departure.plusMinutes(1)))
    }

    @Test fun earliestPlanCombinesWidgetsAndHandlesNoInstalledWidgets() {
        val early = BusWidgetRefreshPlan.RefreshAt(departure.minusMinutes(20))
        val late = BusWidgetRefreshPlan.RefreshAt(departure.minusMinutes(10))
        assertEquals(early, earliestBusWidgetRefresh(listOf(late, BusWidgetRefreshPlan.NoRefresh, early)))
        assertEquals(BusWidgetRefreshPlan.NoRefresh, earliestBusWidgetRefresh(emptyList()))
    }
}
