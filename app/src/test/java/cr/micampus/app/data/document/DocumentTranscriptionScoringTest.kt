package cr.micampus.app.data.document

import cr.micampus.app.data.local.DocumentTranscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTranscriptionScoringTest {
    private fun chunk(id: String, index: Int, text: String) = DocumentTranscriptionEntity(id, index, 1, text)

    @Test fun accentsAndSpanishStopwordsDoNotBlockRetrieval() {
        val result = DocumentTranscriptionRepository.scoreChunks("¿Cuál es la evaluación?", listOf(chunk("a", 0, "La evaluacion final vale 40 por ciento.")), 200)
        assertEquals(1, result.size)
    }

    @Test fun skipsChunksThatDoNotFitBudget() {
        val result = DocumentTranscriptionRepository.scoreChunks("examen", listOf(chunk("a", 0, "examen largo"), chunk("a", 1, "examen")), 8)
        assertEquals(listOf(1), result.map { it.chunkIndex })
    }

    @Test fun selectedChunksAreOrderedByDocumentAndIndex() {
        val result = DocumentTranscriptionRepository.scoreChunks("curso", listOf(chunk("b", 2, "curso b"), chunk("a", 4, "curso a"), chunk("a", 1, "curso primero")), 100)
        assertEquals(listOf("a:1", "a:4", "b:2"), result.map { "${it.documentId}:${it.chunkIndex}" })
    }

    @Test fun emptyQuestionFallsBackToSourceOrderWithinBudget() {
        val result = DocumentTranscriptionRepository.scoreChunks("de la y", listOf(chunk("b", 0, "uno"), chunk("a", 2, "dos")), 100)
        assertEquals(listOf("a", "b"), result.map { it.documentId })
        assertTrue(result.isNotEmpty())
    }
}
