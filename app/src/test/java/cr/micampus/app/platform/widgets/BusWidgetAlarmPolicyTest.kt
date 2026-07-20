package cr.micampus.app.platform.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class BusWidgetAlarmPolicyTest {
    @Test fun exactAlarmIsUsedBeforeApi31() {
        assertEquals(BusWidgetAlarmPrecision.EXACT, busWidgetAlarmPrecision(apiLevel = 30, canScheduleExact = false))
    }

    @Test fun exactAlarmIsUsedWhenAccessIsAlreadyAvailable() {
        assertEquals(BusWidgetAlarmPrecision.EXACT, busWidgetAlarmPrecision(apiLevel = 31, canScheduleExact = true))
    }

    @Test fun inexactAlarmIsTheNoPromptFallback() {
        assertEquals(BusWidgetAlarmPrecision.INEXACT, busWidgetAlarmPrecision(apiLevel = 31, canScheduleExact = false))
    }
}
