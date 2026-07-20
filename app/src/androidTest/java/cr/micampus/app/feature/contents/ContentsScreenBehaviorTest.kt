package cr.micampus.app.feature.contents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.model.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentsScreenBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun courseSectionsExpandAndFileActionIsAccessible() {
        var openedFile: String? = null
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(
                        connected = true,
                        courses = listOf(
                            ContentCourseUi(
                                id = 7,
                                name = "Estructuras discretas",
                                code = "EIF-203",
                                sections = listOf(
                                    ContentSectionUi(
                                        id = 70,
                                        name = "Semana 1",
                                        resources = listOf(
                                            ContentResourceUi(
                                                id = 101,
                                                name = "Bibliografía",
                                                kind = ResourceKind.FOLDER,
                                                url = null,
                                                enabled = true,
                                                availability = null,
                                                files = listOf(ContentFileUi("file-1", "Guía.pdf", "application/pdf", 2_048, false, 0)),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                    onOpenFile = { id, _, _ -> openedFile = id },
                    onDeleteDownload = {},
                    onSetFileStarred = { _, _ -> },
                    onDeleteAllDownloads = {},
                    onOpenFallback = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Semana 1").performClick()
        compose.onNodeWithText("Guía.pdf").assertIsDisplayed().performClick()
        assertEquals("file-1", openedFile)
        compose.onNodeWithContentDescription("Actualizar contenidos").assertIsDisplayed()
    }

    @Test fun starredFolderShowsGuidanceAndFileStarToggles() {
        var starredChange: Pair<String, Boolean>? = null
        var openedFile: String? = null
        val course = ContentCourseUi(
            id = 7,
            name = "Estructuras discretas",
            code = "EIF-203",
            sections = listOf(
                ContentSectionUi(
                    id = 70,
                    name = "Semana 1",
                    resources = listOf(
                        ContentResourceUi(
                            id = 101,
                            name = "Bibliografía",
                            kind = ResourceKind.FOLDER,
                            url = null,
                            enabled = true,
                            availability = null,
                            files = listOf(ContentFileUi("file-1", "Guía.pdf", "application/pdf", 2_048, false, 0)),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(connected = true, courses = listOf(course)),
                    onRefresh = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                    onOpenFile = { id, _, _ -> openedFile = id },
                    onDeleteDownload = {},
                    onSetFileStarred = { id, starred -> starredChange = id to starred },
                    onDeleteAllDownloads = {},
                    onOpenFallback = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Destacados").performClick()
        compose.onNodeWithText("Marca la estrella de un archivo para encontrarlo rápidamente aquí.").assertIsDisplayed()
        compose.onNodeWithText("Semana 1").performClick()
        val star = compose.onNodeWithContentDescription("Agregar Guía.pdf a Destacados").assertIsDisplayed()
        val starBounds = star.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(starBounds.width >= 48f * density && starBounds.height >= 48f * density)
        star.performClick()
        assertEquals("file-1" to true, starredChange)
        assertEquals(null, openedFile)
    }

    @Test fun starredFilesAreGroupedWithSourceContextAndCanBeRemoved() {
        var starredChange: Pair<String, Boolean>? = null
        val starred = ContentFileUi("file-1", "Guía.pdf", "application/pdf", 2_048, false, 0, starred = true)
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(
                        connected = true,
                        courses = listOf(
                            ContentCourseUi(
                                7,
                                "Estructuras discretas",
                                "EIF-203",
                                listOf(ContentSectionUi(70, "Semana 1", listOf(ContentResourceUi(101, "Bibliografía", ResourceKind.FOLDER, null, true, null, listOf(starred))))),
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                    onOpenFile = { _, _, _ -> },
                    onDeleteDownload = {},
                    onSetFileStarred = { id, value -> starredChange = id to value },
                    onDeleteAllDownloads = {},
                    onOpenFallback = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Destacados").performClick()
        compose.onNodeWithText("Semana 1 · Bibliografía").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitar Guía.pdf de Destacados").assertIsDisplayed().performClick()
        assertEquals("file-1" to false, starredChange)
    }

    @Test fun courseCollapseHasNoResidualSpacingSnapAtCompletion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(
                        connected = true,
                        courses = listOf(
                            ContentCourseUi(
                                7,
                                "Estructuras discretas",
                                "EIF-203",
                                listOf(ContentSectionUi(70, "Semana 1", listOf(ContentResourceUi(101, "Bibliografía", ResourceKind.FOLDER, null, true, null, emptyList())))),
                            ),
                        ),
                    ),
                    onRefresh = {}, onOpenSettings = {}, onOpenResource = {}, onOpenFile = { _, _, _ -> },
                    onDeleteDownload = {}, onSetFileStarred = { _, _ -> }, onDeleteAllDownloads = {},
                    onOpenFallback = {}, onClearMessage = {},
                )
            }
        }

        val card = compose.onNodeWithTag("course-folder-7")
        val expandedHeight = card.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("Estructuras discretas").performClick()
        compose.mainClock.advanceTimeBy(199)
        compose.waitForIdle()
        val nearEndHeight = card.fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(40)
        compose.waitForIdle()
        val finalHeight = card.fetchSemanticsNode().boundsInRoot.height
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

        assertTrue("The card should shrink monotonically", expandedHeight >= nearEndHeight && nearEndHeight >= finalHeight)
        assertTrue("The final removal must not snap away a fixed gap", nearEndHeight - finalHeight < 4f * density)
    }

    @Test fun disconnectedStateLinksToSettings() {
        var openedSettings = false
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(),
                    onRefresh = {},
                    onOpenSettings = { openedSettings = true },
                    onOpenResource = {},
                    onOpenFile = { _, _, _ -> },
                    onDeleteDownload = {},
                    onSetFileStarred = { _, _ -> },
                    onDeleteAllDownloads = {},
                    onOpenFallback = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Ir a Ajustes").performClick()
        assertEquals(true, openedSettings)
    }

    @Test fun courseDownloadsRequireConfirmationAndKeepCourseScope() {
        var deletedCourse: Long? = null
        compose.setContent {
            MiCampusTheme(dynamicColor = false) {
                ContentsScreen(
                    state = ContentsUiState(
                        connected = true,
                        courses = listOf(
                            ContentCourseUi(
                                7,
                                "Estructuras discretas",
                                "EIF-203",
                                listOf(
                                    ContentSectionUi(
                                        70,
                                        "Semana 1",
                                        listOf(
                                            ContentResourceUi(
                                                101,
                                                "Guía",
                                                ResourceKind.FILE,
                                                null,
                                                true,
                                                null,
                                                listOf(ContentFileUi("file-1", "Guía.pdf", "application/pdf", 2_048, true, 2_048)),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                    onOpenFile = { _, _, _ -> },
                    onDeleteDownload = {},
                    onSetFileStarred = { _, _ -> },
                    onDeleteAllDownloads = { deletedCourse = it },
                    onOpenFallback = {},
                    onClearMessage = {},
                )
            }
        }

        compose.onNodeWithText("Eliminar descargas del curso").performClick()
        compose.onNodeWithText("Se eliminarán todos los archivos descargados de Estructuras discretas. Los contenidos seguirán apareciendo en la lista.").assertIsDisplayed()
        compose.onNodeWithText("Eliminar").performClick()
        assertEquals(7L, deletedCourse)
    }
}
