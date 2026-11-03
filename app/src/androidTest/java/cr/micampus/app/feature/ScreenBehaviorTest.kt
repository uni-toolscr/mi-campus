package cr.micampus.app.feature

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.BuildConfig
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.designsystem.AppBackgroundSurface
import cr.micampus.app.core.designsystem.FloatingNavToolbar
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.designsystem.ShellTab
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgress
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.UnfinishedCoursePolicy
import cr.micampus.app.data.document.DocumentStatus
import cr.micampus.app.data.document.ImportedDocument
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.feature.calendar.CalendarPresentation
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarUiState
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.chat.AssetChatKnowledgeStore
import cr.micampus.app.feature.chat.ChatScreen
import cr.micampus.app.feature.chat.ChatUiState
import cr.micampus.app.feature.chat.ChatViewModel
import cr.micampus.app.feature.chat.LocalChatEventCommitter
import cr.micampus.app.feature.importer.ImportStage
import cr.micampus.app.feature.importer.ImporterScreen
import cr.micampus.app.feature.importer.ImporterUiState
import cr.micampus.app.feature.importer.ImporterViewModel
import cr.micampus.app.feature.settings.AcademicProgressUiState
import cr.micampus.app.feature.settings.LocalAiAvailability
import cr.micampus.app.feature.settings.MoodleSettingsUiState
import cr.micampus.app.feature.settings.SettingsPage
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsUiState
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportViewModel
import cr.micampus.app.feature.update.UpdateViewModel
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarAgendaSearchAndFiltersAreAlwaysAvailable() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(state, viewModel, expanded = false, onOpenSettings = {})
            }
        }

        compose.onNodeWithText("Agenda").performClick()
        compose.onNodeWithTag("agenda-search").assertIsDisplayed()
        compose.onNodeWithText("Buscar curso, tarea o nota").assertExists()
        compose.onNodeWithText("Pendientes").assertIsDisplayed()
    }

    @Test fun calendarContentExtendsBehindFloatingActions() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        // Upcoming dates: the agenda folds past events away.
        val start = LocalDate.now().plusDays(1).atTime(8, 0)
        val events = List(8) { index ->
            CampusEvent(
                id = "dock-event-$index",
                title = "Evento $index",
                institution = Institution.UNA,
                kind = EventKind.EXAM,
                start = start.plusDays(index.toLong()),
                end = start.plusDays(index.toLong()).plusHours(1),
            )
        }
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                AppBackgroundSurface {
                    CalendarScreen(
                        CalendarUiState(
                            loading = false,
                            presentation = CalendarPresentation.AGENDA,
                            events = events,
                            agendaEvents = events,
                        ),
                        viewModel,
                        expanded = false,
                        onOpenSettings = {},
                    )
                    FloatingNavToolbar(
                        selected = ShellTab.CALENDAR,
                        onSelect = {},
                        onOpenChat = {},
                        aiEnabled = false,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .padding(bottom = 16.dp)
                            .testTag("shared-floating-dock"),
                    )
                }
            }
        }

        val createBounds = compose.onNodeWithTag("calendar-create-action").assertIsDisplayed().getUnclippedBoundsInRoot()
        val contentBounds = compose.onNodeWithTag("calendar-scroll-content").assertIsDisplayed().getUnclippedBoundsInRoot()
        val dockBounds = compose.onNodeWithTag("shared-floating-dock").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Crear debe quedar por encima del dock", createBounds.bottom <= dockBounds.top)
        assertTrue("La agenda debe extenderse detrás de las acciones flotantes", contentBounds.bottom > createBounds.top)
        assertTrue("La agenda debe extenderse detrás del dock", contentBounds.bottom > dockBounds.top)
        compose.onNodeWithContentDescription("Agregar").performClick()
        compose.onNodeWithTag("calendar-import-action").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cerrar menú").performClick()
        compose.onNodeWithTag("calendar-overflow-action").performClick()
        compose.onNodeWithTag("calendar-export-action").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancelar").performClick()
        val finalEvent = compose.scrollListTo("calendar-scroll-content", hasText("Evento 7")).assertIsDisplayed()
        val finalEventBounds = finalEvent.getUnclippedBoundsInRoot()
        assertTrue("El último evento debe desplazarse por encima del dock", finalEventBounds.bottom <= dockBounds.top)
    }

    @Test fun chatComposerWorksWithoutImportedDocuments() {
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ChatScreen(
                    state = ChatUiState(hasDocuments = false, selectedInstitutions = listOf(Institution.UNA)),
                    viewModel = chatViewModel(),
                    onBack = {},
                    onOpenFiles = {},
                )
            }
        }

        compose.onNodeWithText("Escribe tu pregunta…").assertIsEnabled()
            .performClick().performTextInput("Hola")
        compose.onNodeWithContentDescription("Enviar").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithText("Conocimiento incluido: UNA").assertExists()
    }

    @Test fun calendarAgendaLongPressEntersSelectionMode() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        val start = LocalDate.now().plusDays(1).atTime(8, 0)
        val event = CampusEvent("select-event", "Examen de selección", Institution.UCR, EventKind.EXAM, start, start.plusHours(1))
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(
                    CalendarUiState(loading = false, presentation = CalendarPresentation.AGENDA, events = listOf(event), agendaEvents = listOf(event)),
                    viewModel,
                    expanded = false,
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Examen de selección").performTouchInput { longClick() }
        compose.onNodeWithText("1 seleccionados").assertExists()
        compose.onNodeWithContentDescription("Eliminar eventos seleccionados").assertExists()
        compose.onNodeWithTag("calendar-floating-actions").assertDoesNotExist()
    }

    @Test fun transportInstitutionAndDirectionAreSelectable() {
        val viewModel = TransportViewModel(app().container.transport, app().container.settings)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MiCampusTheme(dynamicColor = false) {
                TransportScreen(state, viewModel::selectInstitution, viewModel::selectDirection, viewModel::selectDate, onOpenSettings = {})
            }
        }

        compose.onNodeWithText("UNA").performClick().assertIsSelected()
        compose.onNodeWithText("Omar Dengo → Benjamín Núñez").performClick().assertIsSelected()
        compose.onNodeWithTag("transport-list").performScrollToNode(hasTestTag("transport-dataset"))
        compose.onNodeWithTag("transport-dataset").performClick()
        compose.onNodeWithText("Información verificada").assertIsDisplayed()
    }

    @Test fun importedDraftCanBeOpenedEditedAndSaved() {
        val container = app().container
        val viewModel = ImporterViewModel(
            container.documents,
            container.importedDocuments,
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

    @Test fun importedDraftLongPressEntersSelectionMode() {
        val container = app().container
        val viewModel = ImporterViewModel(container.documents, container.importedDocuments, container.events, container.settings, container.reminders, container.cloud)
        val draft = CalendarEventDraft(
            id = "review-select",
            title = "Quiz seleccionado",
            category = EventCategory.QUIZ,
            institution = Institution.UCR,
            date = LocalDate.of(2026, 8, 3),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 0),
            location = null,
            course = null,
            sourcePage = null,
            evidence = null,
        )
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ImporterScreen(ImporterUiState(ImportStage.REVIEW, listOf(draft)), viewModel, {})
            }
        }

        compose.onNodeWithText("Quiz seleccionado").performTouchInput { longClick() }
        compose.onNodeWithText("1 seleccionados").assertExists()
        compose.onNodeWithContentDescription("Confirmar borradores seleccionados").assertExists()
        compose.onNodeWithContentDescription("Descartar borradores seleccionados").assertExists()
    }

    @Test fun retainedPdfLibraryShowsAccessibleActions() {
        val container = app().container
        val viewModel = ImporterViewModel(container.documents, container.importedDocuments, container.events, container.settings, container.reminders, container.cloud)
        val document = ImportedDocument("doc", "Programa.pdf", "abc", 2048, 1, 2, DocumentStatus.COMPLETED, null, 3, listOf("gemini-3.1-flash-lite"))
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ImporterScreen(ImporterUiState(documents = listOf(document)), viewModel, {})
            }
        }
        compose.onNodeWithText("Archivos guardados").assertExists()
        compose.onNodeWithText("Programa.pdf").assertExists()
        compose.onNodeWithText("Nube: gemini-3.1-flash-lite").assertExists()
    }

    @Test fun batchConsentNamesSelectedDocuments() {
        val container = app().container
        val viewModel = ImporterViewModel(container.documents, container.importedDocuments, container.events, container.settings, container.reminders, container.cloud)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ImporterScreen(ImporterUiState(stage = ImportStage.NEEDS_CONSENT, consentDocumentNames = listOf("Curso A.pdf", "Curso B.pdf")), viewModel, {})
            }
        }
        compose.onNodeWithText("Procesamiento opcional en la nube").assertExists()
        compose.onNodeWithText("Acepto para este lote").assertExists()
        compose.onNodeWithText("• Curso A.pdf\n• Curso B.pdf", substring = true).assertExists()
    }

    @Test fun localAiSwitchIsDisabledAndExplainedWhenUnavailable() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        settings = AppSettings(localAiEnabled = true),
                        localAiAvailability = LocalAiAvailability.Unavailable,
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                    initialPage = SettingsPage.AI,
                )
            }
        }

        compose.scrollSettingsToTag("local-ai-switch").assertIsNotEnabled()
        compose.onNodeWithText("No compatible o AICore aún no está disponible").assertExists()
        compose.onNodeWithText("Modelo local (Gemini Nano)").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "No compatible o AICore aún no está disponible",
            ),
        )
        compose.onNodeWithText("Volver a comprobar").assertExists()
    }

    @Test fun academicProgressCardIsConnectedOnlyAndDisclosesExcludedResults() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        settings = AppSettings(
                            academicProgressFilter = AcademicProgressFilter(2025, AcademicCycle.I, UnfinishedCoursePolicy.AS_PASSED),
                        ),
                        moodle = MoodleSettingsUiState(connected = true),
                        progress = AcademicProgressUiState.Available(
                            AcademicProgress(10.0, 7.0, 2.0, 1, 70.0, 100L),
                        ),
                        progressTerms = listOf(
                            AcademicTermProgress("202501", 2025, AcademicCycle.I, 7.0, 3.0, 2.0, 2.0, 1, 100L),
                        ),
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                    initialPage = SettingsPage.ACCOUNTS,
                )
            }
        }

        compose.scrollSettingsToTag("academic-progress-card").assertIsDisplayed()
        compose.onNodeWithText("7.0 de 10.0 créditos aprobados").assertExists()
        compose.onNodeWithText("1 resultado con calificación sin clasificar fue excluido").assertExists()
        compose.onNodeWithText("2025 · Ciclo I · Sin nota: aprobados").assertExists()
        compose.onNodeWithText("Actualizar progreso").assertIsEnabled()
        compose.onNodeWithText("Filtrar").performClick()
        compose.onNodeWithText("Filtrar progreso académico").assertIsDisplayed()
        compose.onNodeWithText("Contar como reprobados").performClick()
        compose.onNodeWithText("Aplicar").performClick()
    }

    @Test fun academicProgressFilterShowsSpecificEmptyPeriodState() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        settings = AppSettings(academicProgressFilter = AcademicProgressFilter(2024, AcademicCycle.II)),
                        moodle = MoodleSettingsUiState(connected = true),
                        progress = AcademicProgressUiState.Available(
                            AcademicProgress(0.0, 0.0, 0.0, 0, null, 100L),
                        ),
                        progressTerms = listOf(
                            AcademicTermProgress("202501", 2025, AcademicCycle.I, 7.0, 3.0, 0.0, 0.0, 0, 100L),
                        ),
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                    initialPage = SettingsPage.ACCOUNTS,
                )
            }
        }

        compose.scrollSettingsToText("No hay datos para este período").assertIsDisplayed()
        compose.onNodeWithText("Filtrar").assertIsEnabled()
    }

    @Test fun academicProgressUnavailableStateOffersRetry() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        moodle = MoodleSettingsUiState(connected = true),
                        progress = AcademicProgressUiState.Unavailable("Progreso no disponible"),
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                    initialPage = SettingsPage.ACCOUNTS,
                )
            }
        }

        compose.scrollSettingsToText("Progreso no disponible").assertIsDisplayed()
        compose.onNodeWithText("Actualizar progreso").assertIsEnabled()
    }

    @Test fun localAiSwitchIsAccessibleForCompatibleStates() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(localAiAvailability = LocalAiAvailability.Downloadable),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                    initialPage = SettingsPage.AI,
                )
            }
        }

        compose.scrollSettingsToTag("local-ai-switch").assertIsEnabled()
        compose.onNodeWithText("Compatible; el modelo se descargará con tu confirmación").assertExists()
        compose.onNodeWithTag("cloud-ai-switch").assertIsEnabled()
    }

    @Test fun nanoDownloadDoesNotOfferCloudWhenCloudAiIsDisabled() {
        val container = app().container
        val viewModel = ImporterViewModel(container.documents, container.importedDocuments, container.events, container.settings, container.reminders, container.cloud)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ImporterScreen(
                    ImporterUiState(stage = ImportStage.NEEDS_NANO, cloudAiEnabled = false),
                    viewModel,
                    {},
                )
            }
        }

        compose.onNodeWithText("Descargar en el dispositivo", substring = true).assertExists()
        compose.onNodeWithText("Usar nube para este lote").assertDoesNotExist()
    }

    @Test fun debugDiagnosticsControlsAreVisibleOnlyWhenRecorderIsAvailable() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        val update = UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        localAiAvailability = LocalAiAvailability.Unavailable,
                        diagnosticsAvailable = true,
                    ),
                    viewModel,
                    update,
                    onInstallUpdate = {},
                )
            }
        }

        compose.scrollSettingsToTag("settings-page-advanced").performClick()
        compose.scrollSettingsToText("Diagnóstico de importación").assertExists()
        compose.onNodeWithText("Probar Gemini Nano").assertExists()
        compose.onNodeWithTag("nano-self-test").assertIsEnabled()
        compose.onNodeWithText("Exportar diagnóstico").assertExists()
        compose.onNodeWithText("Borrar diagnóstico").assertExists()
    }

    @Test fun debugDiagnosticsControlsAreAbsentWhenRecorderIsUnavailable() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        val update = UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(SettingsUiState(diagnosticsAvailable = false), viewModel, update, onInstallUpdate = {})
            }
        }

        compose.onNodeWithTag("settings-page-advanced").assertDoesNotExist()
        compose.onNodeWithText("Probar Gemini Nano").assertDoesNotExist()
        compose.onNodeWithText("Exportar diagnóstico").assertDoesNotExist()
    }

    @Test fun settingsFrontPageSummarizesAndOpensSubPages() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        var closed = false
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(SettingsUiState(), viewModel, updateVm(), onInstallUpdate = {}, onBack = { closed = true })
            }
        }

        compose.onNodeWithText("UCR y UNA").assertExists()
        compose.onNodeWithText("Aula Virtual UNA: sin conectar").assertExists()
        compose.scrollSettingsToText("Tema del sistema · 24 horas").assertExists()
        compose.scrollSettingsToText("Desactivada · nada sale del teléfono").assertExists()
        compose.scrollSettingsToTag("settings-page-accounts").performClick()
        compose.onNodeWithText("Conectar Aula Virtual").assertIsDisplayed()
        compose.onNodeWithContentDescription("Atrás").performClick()
        compose.onNodeWithTag("settings-page-accounts").assertIsDisplayed()
        assertFalse(closed)
        compose.onNodeWithContentDescription("Atrás").performClick()
        assertTrue(closed)
    }

    private fun chatViewModel(): ChatViewModel {
        val container = app().container
        return ChatViewModel(
            library = container.importedDocuments,
            transcriptions = container.transcriptions,
            settings = container.settings,
            keyStore = container.keyStore,
            cloud = container.cloud,
            knowledge = AssetChatKnowledgeStore(container.knowledge),
            eventCommitter = LocalChatEventCommitter(
                events = container.events,
                reminders = container.reminders,
                settings = container.settings,
                onDataChanged = container.widgets::refreshAll,
            ),
        )
    }

    private fun updateVm(): UpdateViewModel {
        val container = app().container
        return UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
    }

    private fun app(): MiCampusApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiCampusApplication
}
