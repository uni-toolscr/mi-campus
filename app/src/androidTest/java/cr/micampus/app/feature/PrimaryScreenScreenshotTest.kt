package cr.micampus.app.feature

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.designsystem.AppBackgroundSurface
import cr.micampus.app.core.designsystem.FloatingNavToolbar
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.designsystem.ShellTab
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.AcademicProgress
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.core.model.UnfinishedCoursePolicy
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.TransportService
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarPresentation
import cr.micampus.app.feature.calendar.CalendarUiState
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.calendar.DateField
import cr.micampus.app.feature.calendar.EventEditorDialog
import cr.micampus.app.feature.calendar.TimeField
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeUiState
import cr.micampus.app.feature.chat.AssetChatKnowledgeStore
import cr.micampus.app.feature.chat.ChatCitation
import cr.micampus.app.feature.chat.ChatEventProposal
import cr.micampus.app.feature.chat.ChatMessage
import cr.micampus.app.feature.chat.ChatMessageRole
import cr.micampus.app.feature.chat.ChatScreen
import cr.micampus.app.feature.chat.ChatUiState
import cr.micampus.app.feature.chat.ChatViewModel
import cr.micampus.app.feature.chat.LocalChatEventCommitter
import cr.micampus.app.feature.contents.ContentCourseUi
import cr.micampus.app.feature.contents.ContentFileUi
import cr.micampus.app.feature.contents.ContentResourceUi
import cr.micampus.app.feature.contents.ContentSectionUi
import cr.micampus.app.feature.contents.ContentsScreen
import cr.micampus.app.feature.contents.ContentsUiState
import cr.micampus.app.core.model.ResourceKind
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsUiState
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.update.UpdateViewModel
import cr.micampus.app.BuildConfig
import cr.micampus.app.feature.settings.LocalAiAvailability
import cr.micampus.app.feature.settings.AcademicProgressUiState
import cr.micampus.app.feature.settings.MoodleSettingsUiState
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PrimaryScreenScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appBackgroundProvidesDarkContentColor() {
        assertAppBackgroundContentColor(ThemeMode.DARK)
    }

    @Test fun appBackgroundProvidesLightContentColor() {
        assertAppBackgroundContentColor(ThemeMode.LIGHT)
    }

    @Test fun homeDarkThemeScreenshotSmoke() {
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                AppBackgroundSurface { HomeScreen(HomeUiState(loading = false), {}) }
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun homeLargeFontScreenshotSmoke() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MiCampusTheme(dynamicColor = false) {
                    AppBackgroundSurface { HomeScreen(HomeUiState(loading = false), {}) }
                }
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun contentsDarkThemeScreenshotSmoke() {
        val state = ContentsUiState(
            connected = true,
            courses = listOf(
                ContentCourseUi(
                    7,
                    "Estructuras discretas",
                    "EIF-203",
                    listOf(
                        ContentSectionUi(
                            70,
                            "Materiales y recursos",
                            listOf(
                                ContentResourceUi(
                                    101,
                                    "Bibliografía",
                                    ResourceKind.FOLDER,
                                    null,
                                    true,
                                    null,
                                    listOf(ContentFileUi("file", "Guía.pdf", "application/pdf", 2_048, true, 2_048, starred = true)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                AppBackgroundSurface {
                    ContentsScreen(state, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun compactFourTabToolbarShowsSelectedLabelScreenshotAndSemanticsSmoke() {
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp)) {
                    FloatingNavToolbar(
                        selected = ShellTab.CONTENTS,
                        onSelect = {},
                        onOpenChat = {},
                        aiEnabled = true,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Inicio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Calendario").assertIsDisplayed()
        compose.onNodeWithContentDescription("Contenidos").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Transporte").assertIsDisplayed()
        compose.onNodeWithText("Inicio").assertDoesNotExist()
        compose.onNodeWithText("Calendario").assertDoesNotExist()
        compose.onNodeWithText("Contenidos").assertIsDisplayed()
        compose.onNodeWithText("Transporte").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat con tus documentos").assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun compactToolbarSelectionAnimationFullyRemovesOutgoingLabel() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var selected by remember { mutableStateOf(ShellTab.HOME) }
            MiCampusTheme(dynamicColor = false) {
                FloatingNavToolbar(
                    selected = selected,
                    onSelect = { selected = it },
                    onOpenChat = {},
                    aiEnabled = true,
                )
            }
        }

        compose.onNodeWithContentDescription("Calendario").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("Inicio").assertDoesNotExist()
        compose.onNodeWithText("Calendario").assertIsDisplayed()
    }

    @Test fun toolbarChatVisibilityTracksAiState() {
        var aiEnabled by mutableStateOf(false)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                FloatingNavToolbar(
                    selected = ShellTab.HOME,
                    onSelect = {},
                    onOpenChat = {},
                    aiEnabled = aiEnabled,
                )
            }
        }

        compose.onNodeWithContentDescription("Chat con tus documentos").assertDoesNotExist()
        compose.runOnIdle { aiEnabled = true }
        compose.onNodeWithContentDescription("Chat con tus documentos").assertIsDisplayed()
    }

    @Test fun transportExpiredStateScreenshotSmoke() {
        val date = LocalDate.of(2027, 1, 1)
        val state = TransportUiState(
            institution = Institution.UNA,
            date = date,
            service = TransportService(ServiceStatus.EXPIRED, "UNA-STI-CIRC-002-2026", date, emptyList()),
        )
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                AppBackgroundSurface { TransportScreen(state, {}, {}, {}, onOpenSettings = {}) }
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun calendarCompactScreenshotSmoke() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(CalendarUiState(loading = false, presentation = CalendarPresentation.AGENDA), viewModel, expanded = false, onOpenSettings = {})
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun populatedCalendarBehindFloatingDockDarkScreenshotSmoke() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        val start = LocalDateTime.of(2026, 7, 18, 22, 16)
        val events = List(8) { index ->
            CampusEvent(
                id = "dock-screenshot-$index",
                title = "Evento de prueba ${index + 1}",
                institution = Institution.UNA,
                kind = EventKind.EXAM,
                start = start.plusDays(index.toLong()),
                end = start.plusDays(index.toLong()).plusHours(1),
            )
        }
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
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
                            .padding(bottom = 16.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Evento de prueba 8").performScrollTo().assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun calendarExpandedScreenshotSmoke() {
        val container = app().container
        val viewModel = CalendarViewModel(container.events, container.calendar, container.reminders, container.settings)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                CalendarScreen(CalendarUiState(loading = false), viewModel, expanded = true, onOpenSettings = {})
            }
        }
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun settingsCompatibleScreenshotSmoke() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        settings = AppSettings(localAiEnabled = true, cloudAiEnabled = true),
                        localAiAvailability = LocalAiAvailability.Available("modelo-runtime-prueba"),
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                )
            }
        }
        compose.onNodeWithText("IA y privacidad").performScrollTo()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun settingsDownloadableScreenshotSmoke() {
        settingsScreenshot(SettingsUiState(localAiAvailability = LocalAiAvailability.Downloadable))
    }

    @Test fun settingsUnavailableScreenshotSmoke() {
        settingsScreenshot(
            SettingsUiState(
                settings = AppSettings(localAiEnabled = true),
                localAiAvailability = LocalAiAvailability.Unavailable,
            ),
        )
    }

    @Test fun settingsDarkThemeScreenshotSmoke() {
        settingsScreenshot(
            SettingsUiState(localAiAvailability = LocalAiAvailability.Available("otro-modelo-runtime")),
            theme = ThemeMode.DARK,
        )
    }

    @Test fun settingsAcademicProgressPartialDarkScreenshotSmoke() {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                SettingsScreen(
                    SettingsUiState(
                        settings = AppSettings(
                            academicProgressFilter = AcademicProgressFilter(2025, AcademicCycle.I, UnfinishedCoursePolicy.AS_PASSED),
                        ),
                        moodle = MoodleSettingsUiState(connected = true, displayName = "Estudiante UNA"),
                        progress = AcademicProgressUiState.Available(
                            AcademicProgress(
                                attemptedCredits = 35.0,
                                approvedCredits = 26.0,
                                unclassifiedCredits = 3.0,
                                unclassifiedResults = 1,
                                percentage = 74.286,
                                updatedAtEpoch = 1_800_000_000_000,
                                unfinishedCredits = 2.0,
                            ),
                        ),
                        progressTerms = listOf(
                            AcademicTermProgress("202501", 2025, AcademicCycle.I, 24.0, 9.0, 2.0, 3.0, 1, 1_800_000_000_000),
                        ),
                    ),
                    viewModel,
                    updateVm(),
                    onInstallUpdate = {},
                )
            }
        }

        compose.onNodeWithText("Progreso académico").performScrollTo().assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun settingsLargeFontScreenshotSmoke() {
        settingsScreenshot(
            SettingsUiState(localAiAvailability = LocalAiAvailability.Downloadable),
            fontScale = 2f,
        )
    }

    @Test fun chatDarkThemeWithOfficialSourceScreenshotSmoke() {
        val state = ChatUiState(
            selectedInstitutions = listOf(Institution.UNA),
            messages = listOf(
                ChatMessage(ChatMessageRole.USER, "¿Cómo solicito una beca?"),
                ChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "Consulta la convocatoria vigente; más detalles en [este enlace](https://www.una.ac.cr/becas).",
                    citations = listOf(
                        ChatCitation(
                            id = "doc-becas-3",
                            label = "Becas.pdf, pág. 3",
                            institution = Institution.UNA,
                            documentId = "becas",
                            page = 3,
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                ChatScreen(state, chatViewModel(), onBack = {}, onOpenFiles = {})
            }
        }

        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun chatEventReviewForcedDarkLargeFontScreenshotSmoke() {
        val state = ChatUiState(
            selectedInstitutions = listOf(Institution.UNA, Institution.UCR),
            pendingEvent = ChatEventProposal(
                title = "Examen final",
                institution = Institution.UNA,
                date = LocalDate.of(2027, 8, 3),
                startTime = LocalTime.of(17, 0),
                endTime = LocalTime.of(18, 0),
                inferredEnd = true,
            ),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                // Keep the app dark even if the test device itself is using a light theme.
                MiCampusTheme(ThemeMode.DARK, dynamicColor = false) { ChatScreen(state, chatViewModel(), onBack = {}, onOpenFiles = {}) }
            }
        }

        compose.onNodeWithText("Propuesta de evento").performScrollTo()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun chatOpenKeyboardScreenshotSmoke() {
        val state = ChatUiState(hasDocuments = false, selectedInstitutions = listOf(Institution.UNA))
        var imeBottom = 0
        compose.setContent {
            val density = LocalDensity.current
            val currentImeBottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeBottom = currentImeBottom }
            MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                ChatScreen(state, chatViewModel(), onBack = {}, onOpenFiles = {})
            }
        }

        compose.onNodeWithText("Escribe tu pregunta…").performClick().performTextInput("¿Cómo me matriculo?")
        compose.waitUntil(timeoutMillis = 5_000) { imeBottom > 0 }
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar").assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun eventEditorForcedDarkLargeFontScreenshotSmoke() {
        val event = screenshotEvent()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                    EventEditorDialog(
                        event = event,
                        enabledInstitutions = listOf(Institution.UNA, Institution.UCR),
                        use12h = false,
                        onDismiss = {},
                        onSave = {},
                        onDelete = null,
                    )
                }
            }
        }

        compose.onNodeWithText("Crear evento").assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun datePickerForcedDarkLargeFontScreenshotSmoke() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                    Surface { DateField("Fecha de inicio", LocalDate.of(2027, 8, 3), {}) }
                }
            }
        }

        compose.onNodeWithContentDescription("Fecha de inicio:", substring = true).performClick()
        compose.onNodeWithText("Aceptar").assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    @Test fun timePickerForcedDarkLargeFontScreenshotSmoke() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MiCampusTheme(ThemeMode.DARK, dynamicColor = false) {
                    Surface { TimeField("Inicio", LocalTime.of(17, 0), use12h = false, onChange = {}) }
                }
            }
        }

        compose.onNodeWithContentDescription("Inicio:", substring = true).performClick()
        compose.onNodeWithText("Aceptar").assertIsDisplayed()
        assertNonEmpty(compose.onRoot().captureToImage())
    }

    private fun settingsScreenshot(
        state: SettingsUiState,
        theme: ThemeMode = ThemeMode.SYSTEM,
        fontScale: Float = 1f,
    ) {
        val container = app().container
        val viewModel = SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders, container.moodle)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MiCampusTheme(theme, dynamicColor = false) { SettingsScreen(state, viewModel, updateVm(), onInstallUpdate = {}) }
            }
        }
        compose.onNodeWithText("IA y privacidad").performScrollTo()
        assertNonEmpty(compose.onRoot().captureToImage())
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

    private fun screenshotEvent(): CampusEvent {
        val start = LocalDateTime.of(2027, 8, 3, 17, 0)
        return CampusEvent(
            id = "theme-event",
            title = "Examen final",
            institution = Institution.UNA,
            kind = EventKind.EXAM,
            start = start,
            end = start.plusHours(1),
        )
    }

    private fun assertAppBackgroundContentColor(theme: ThemeMode) {
        var actual: Color? = null
        var expected: Color? = null
        compose.setContent {
            MiCampusTheme(theme, dynamicColor = false) {
                expected = MaterialTheme.colorScheme.onBackground
                AppBackgroundSurface { actual = LocalContentColor.current }
            }
        }
        compose.runOnIdle { assertEquals(expected, actual) }
    }

    private fun assertNonEmpty(image: ImageBitmap) {
        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
    }

    private fun updateVm(): UpdateViewModel {
        val container = app().container
        return UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
    }

    private fun app(): MiCampusApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiCampusApplication
}
