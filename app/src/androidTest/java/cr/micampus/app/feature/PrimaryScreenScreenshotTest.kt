package cr.micampus.app.feature

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.TransportService
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarUiState
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeUiState
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsUiState
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class PrimaryScreenScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeDarkThemeScreenshotSmoke() {
        compose.setContent { MiCampusTheme(ThemeMode.DARK, dynamicColor = false) { HomeScreen(HomeUiState(loading = false), {}) } }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun homeLargeFontScreenshotSmoke() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MiCampusTheme(dynamicColor = false) { HomeScreen(HomeUiState(loading = false), {}) }
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun transportExpiredStateScreenshotSmoke() {
        val date = LocalDate.of(2027, 1, 1)
        val state = TransportUiState(
            institution = Institution.UNA,
            date = date,
            service = TransportService(ServiceStatus.EXPIRED, "UNA-STI-CIRC-002-2026", date, emptyList()),
        )
        compose.setContent { MiCampusTheme(dynamicColor = false) { TransportScreen(state, {}, {}, {}) } }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun calendarCompactScreenshotSmoke() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(CalendarUiState(loading = false), viewModel, expanded = false)
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun calendarExpandedScreenshotSmoke() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(CalendarUiState(loading = false), viewModel, expanded = true)
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun settingsScreenshotSmoke() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(SettingsUiState(), viewModel)
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    private fun assertNonEmpty(image: ImageBitmap) {
        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
    }

    private fun app(): MiCampusApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiCampusApplication
}
