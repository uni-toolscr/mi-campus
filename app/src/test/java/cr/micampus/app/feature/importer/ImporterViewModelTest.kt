package cr.micampus.app.feature.importer

import cr.micampus.app.data.document.DocumentTranscriptionSaver
import cr.micampus.app.data.document.PageText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ImporterViewModelTest {
    private class RecordingDocumentTranscriptionRepository(
        private val failure: Throwable? = null,
    ) : DocumentTranscriptionSaver {
        val saved = mutableListOf<Pair<String, List<PageText>>>()

        override suspend fun save(documentId: String, pages: List<PageText>) {
            saved += documentId to pages
            failure?.let { throw it }
        }
    }

    @Test fun successfulExtractionSavesTranscriptionPages() = runBlocking {
        val repository = RecordingDocumentTranscriptionRepository()
        val pages = listOf(PageText(1, "Contenido extraído"))

        saveTranscriptionAfterExtraction(repository, "documento-1", pages)

        assertEquals(listOf("documento-1" to pages), repository.saved)
    }

    @Test fun transcriptionSaveFailureDoesNotInterruptImport() = runBlocking {
        val repository = RecordingDocumentTranscriptionRepository(IllegalStateException("disk"))

        saveTranscriptionAfterExtraction(repository, "documento-1", listOf(PageText(1, "Contenido extraído")))

        assertEquals(1, repository.saved.size)
    }
}
