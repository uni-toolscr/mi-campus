package cr.micampus.app.data.document

import cr.micampus.app.data.local.DocumentTranscriptionDao
import cr.micampus.app.data.local.DocumentTranscriptionEntity
import java.text.Normalizer

interface DocumentTranscriptionSaver {
    suspend fun save(documentId: String, pages: List<PageText>)
}

interface ChatTranscriptionStore {
    suspend fun ensureTranscribed(documentId: String): Result<Unit>
    suspend fun allChunks(): List<DocumentTranscriptionEntity>
}

/** Persisted, smaller document chunks used exclusively to ground chat answers. */
class DocumentTranscriptionRepository(
    private val dao: DocumentTranscriptionDao,
    private val library: ImportedDocumentRepository,
    private val importer: DocumentImporter,
    private val chunker: TokenChunker = TokenChunker(300, 40),
) : DocumentTranscriptionSaver, ChatTranscriptionStore {
    override suspend fun save(documentId: String, pages: List<PageText>) {
        val chunks = chunker.chunk(pages).mapIndexed { index, text ->
            DocumentTranscriptionEntity(documentId, index, pageFor(text), text)
        }
        dao.deleteForDocument(documentId)
        if (chunks.isNotEmpty()) dao.upsertAll(chunks)
    }

    /** Backfills older retained PDFs only when they do not already have transcription rows. */
    override suspend fun ensureTranscribed(documentId: String): Result<Unit> = runCatching {
        if (dao.forDocument(documentId).isNotEmpty()) return@runCatching
        val file = library.file(documentId) ?: error("No se encontró la copia local del PDF.")
        val document = importer.extract(file).getOrThrow()
        save(documentId, document.pages)
    }

    override suspend fun allChunks(): List<DocumentTranscriptionEntity> = dao.all()
    suspend fun delete(documentId: String) = dao.deleteForDocument(documentId)

    companion object {
        private val pageMarker = Regex("\\[P[áa]gina (\\d+)]")
        private val word = Regex("[\\p{L}\\p{N}]+")
        private val stopwords = setOf(
            "a", "al", "algo", "ante", "antes", "con", "contra", "cual", "cuando", "de", "del", "desde", "donde",
            "el", "ella", "ellos", "en", "entre", "era", "es", "esa", "ese", "esta", "este", "ha", "hasta", "la",
            "las", "le", "les", "lo", "los", "me", "mi", "mis", "mucho", "muy", "no", "nos", "o", "para", "pero",
            "por", "que", "se", "si", "sin", "sobre", "su", "sus", "te", "tu", "tus", "un", "una", "uno", "y", "ya",
        )

        /**
         * Lightweight local retrieval. Equal scores retain source ordering before the final stable
         * document/chunk ordering makes the generated prompt readable.
         */
        fun scoreChunks(
            question: String,
            chunks: List<DocumentTranscriptionEntity>,
            maxChars: Int,
        ): List<DocumentTranscriptionEntity> {
            if (maxChars <= 0) return emptyList()
            val terms = terms(question)
            val ranked = chunks.mapIndexed { ordinal, chunk ->
                val chunkTerms = terms(chunk.text)
                val score = if (terms.isEmpty()) 0 else terms.sumOf { term -> chunkTerms.count { it == term } * 2 }
                Triple(chunk, score, ordinal)
            }.sortedWith(compareByDescending<Triple<DocumentTranscriptionEntity, Int, Int>> { it.second }.thenBy { it.third })

            val selected = mutableListOf<DocumentTranscriptionEntity>()
            var used = 0
            for ((chunk, _, _) in ranked) {
                if (chunk.text.length > maxChars - used) continue
                selected += chunk
                used += chunk.text.length
            }
            return selected.sortedWith(compareBy<DocumentTranscriptionEntity> { it.documentId }.thenBy { it.chunkIndex })
        }

        private fun pageFor(chunk: String): Int = pageMarker.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        private fun terms(text: String): List<String> = normalize(text).let { normalized ->
            word.findAll(normalized).map { it.value }.filter { it !in stopwords }.toList()
        }
        private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    }
}
