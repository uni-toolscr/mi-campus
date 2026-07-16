package cr.micampus.app.feature

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportViewModel
import cr.micampus.app.feature.importer.ImporterScreen
import cr.micampus.app.feature.importer.ImporterUiState
import cr.micampus.app.feature.importer.ImporterViewModel
import cr.micampus.app.feature.importer.ImportStage
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScreenBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarInstitutionAndCategoryFiltersUpdateState() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(state, viewModel, expanded = false)
            }
        }

        compose.onNodeWithText("UCR").performClick().assertIsSelected()
        compose.onNodeWithText("Examen").performClick().assertIsSelected()
    }

    @Test fun transportInstitutionAndDirectionAreSelectable() {
        val viewModel = TransportViewModel(app().container.transport)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MiCampusTheme(dynamicColor = false) {
                TransportScreen(state, viewModel::selectInstitution, viewModel::selectDirection, viewModel::selectDate)
            }
        }

        compose.onNodeWithText("UNA").performClick().assertIsSelected()
        compose.onNodeWithText("Omar Dengo → Benjamín Núñez").performClick().assertIsSelected()
        compose.onNodeWithText("Información verificada").assertTextContains("Información verificada")
    }

    @Test fun importedDraftCanBeOpenedEditedAndSaved() {
        val container = app().container
        val viewModel = ImporterViewModel(
            container.documents,
            container.events,
            container.settings,
            container.reminders,
            container.cloud,
        )
        val draft = CalendarEventDraft(
            id = "review-test",
            title = "Examen final",
            category = EventCategory.EXAM,
            institution = Institution.UCR,
            date = LocalDate.of(2026, 8, 3),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(10, 0),
            location = "Aula 1",
            course = null,
            sourcePage = 2,
            evidence = null,
        )
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ImporterScreen(ImporterUiState(ImportStage.REVIEW, listOf(draft)), viewModel, {})
            }
        }

        compose.onNodeWithText("Editar").performClick()
        compose.onNodeWithText("Editar borrador").assertExists()
        compose.onNodeWithText("Guardar borrador").performClick()
        compose.onNodeWithText("Editar borrador").assertDoesNotExist()
    }

    private fun app(): MiCampusApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiCampusApplication
}
