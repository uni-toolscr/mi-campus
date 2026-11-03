package cr.micampus.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "cr.micampus.app"

private const val TIMEOUT_MS = 5_000L

/** Visits every tab and the calendar views, scrolling each list so its item composables get profiled. */
fun MacrobenchmarkScope.browseTabs() {
    listOf("Calendario", "Contenidos", "Transporte", "Inicio").forEach { tab ->
        device.wait(Until.findObject(By.desc(tab)), TIMEOUT_MS)?.click()
        device.waitForIdle()
        if (tab == "Calendario") {
            listOf("Semana", "Agenda", "Horario").forEach { view ->
                device.wait(Until.findObject(By.text(view)), TIMEOUT_MS)?.click()
                device.waitForIdle()
                scrollContent()
            }
        } else {
            scrollContent()
        }
    }
}

private fun MacrobenchmarkScope.scrollContent() {
    listOf(Direction.DOWN, Direction.UP).forEach { direction ->
        // Looked up again for each fling: scrolling recomposes the screen and invalidates the old node.
        val list = device.findObject(By.scrollable(true)) ?: return
        try {
            // Keep the gesture away from the system navigation area.
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(direction)
        } catch (_: StaleObjectException) {
            return
        }
        device.waitForIdle()
    }
}
