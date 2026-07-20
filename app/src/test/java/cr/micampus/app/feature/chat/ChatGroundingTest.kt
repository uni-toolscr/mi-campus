package cr.micampus.app.feature.chat

import cr.micampus.app.data.document.ChatTranscriptionStore
import cr.micampus.app.data.local.DocumentTranscriptionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cr.micampus.app.core.model.Institution

class ChatGroundingTest {
    @Test fun pdfInstitutionInferenceUsesExplicitAcronymsWithoutTreatingSpanishUnaAsAnAcronym() {
        val both = setOf(Institution.UNA, Institution.UCR)
        assertEquals(Institution.UCR, inferPdfInstitution("Reglamento oficial UCR", both))
        assertEquals(Institution.UNA, inferPdfInstitution("Documento oficial UNA", both))
        assertEquals(Institution.UNA, inferPdfInstitution("Universidad Nacional de Costa Rica", both))
        assertEquals(null, inferPdfInstitution("Esta es una guía general", both))
        assertEquals(Institution.UCR, inferPdfInstitution("Guía general", setOf(Institution.UCR)))
        assertTrue(!isPdfEligibleForInstitutions("Guía sin institución", both))
        assertTrue(!isPdfEligibleForInstitutions("Documento oficial UNA", setOf(Institution.UCR)))
        assertTrue(isPdfEligibleForInstitutions("Documento oficial UCR", both))
    }
    private class Store(
        private val chunks: List<DocumentTranscriptionEntity>,
        private val failures: Set<String> = emptySet(),
    ) : ChatTranscriptionStore {
        override suspend fun ensureTranscribed(documentId: String): Result<Unit> =
            if (documentId in failures) Result.failure(IllegalStateException("broken")) else Result.success(Unit)

        override suspend fun allChunks(): List<DocumentTranscriptionEntity> = chunks
    }

    @Test fun failedBackfillBlocksUngroundedGeneration() = runBlocking {
        val result = loadChatGrounding(listOf(ChatSourceDocument("broken", "broken.pdf")), Store(emptyList(), setOf("broken")))

        assertTrue(result.isFailure)
    }

    @Test fun noDocumentsAllowsBundledKnowledgeOnlyChat() = runBlocking {
        val result = loadChatGrounding(emptyList(), Store(emptyList()))

        assertEquals(emptyList<DocumentTranscriptionEntity>(), result.getOrThrow())
    }

    @Test fun emptyTranscriptionBlocksUngroundedGeneration() = runBlocking {
        val result = loadChatGrounding(listOf(ChatSourceDocument("doc", "doc.pdf")), Store(emptyList()))

        assertTrue(result.isFailure)
    }

    @Test fun groundingExcludesRowsOutsideCurrentLibrary() = runBlocking {
        val current = DocumentTranscriptionEntity("doc", 0, 1, "contenido")
        val stale = DocumentTranscriptionEntity("deleted", 0, 1, "obsoleto")

        val result = loadChatGrounding(listOf(ChatSourceDocument("doc", "doc.pdf")), Store(listOf(current, stale)))

        assertEquals(listOf(current), result.getOrThrow())
    }
}
