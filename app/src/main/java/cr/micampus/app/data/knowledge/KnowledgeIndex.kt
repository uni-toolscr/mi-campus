package cr.micampus.app.data.knowledge

import cr.micampus.app.core.model.Institution
import java.text.Normalizer

/** A stable, citeable passage from an institution's bundled knowledge corpus. */
data class KnowledgeChunk(
    val id: String,
    val institution: Institution,
    val heading: String,
    val text: String,
    val urls: List<String>,
    val volatile: Boolean,
    val compiledOn: String? = null,
    val freshnessPolicy: String? = null,
    val officialStatus: String? = null,
) {
    /** Alias for consumers that present the section heading as a citation title. */
    val title: String get() = heading
}

data class KnowledgeCorpus(
    val title: String,
    val institution: Institution,
    val acronym: String,
    val compiledOn: String?,
    val freshnessPolicy: String?,
    val officialStatus: String?,
    val chunks: List<KnowledgeChunk>,
)

/**
 * Small offline lexical index. It deliberately has no Android or persistence dependency so a
 * caller can construct it from an asset, test fixture, or future bundled institution corpus.
 */
class KnowledgeIndex(private val chunks: List<KnowledgeChunk>) {
    val allowedUrls: Set<String> = chunks.flatMapTo(linkedSetOf()) { it.urls }

    fun isAllowedUrl(url: String): Boolean = url in allowedUrls

    fun search(query: String, institutions: Set<Institution>, maxChars: Int): List<KnowledgeChunk> {
        if (query.isBlank() || institutions.isEmpty() || maxChars <= 0) return emptyList()
        val normalizedQuery = normalize(query)
        val queryTerms = tokens(normalizedQuery).distinct()
        if (queryTerms.isEmpty()) return emptyList()

        return chunks.asSequence()
            // This filter is applied before scoring so UCR-only callers never receive UNA text.
            .filter { it.institution in institutions }
            .map { it to score(it, normalizedQuery, queryTerms) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<KnowledgeChunk, Int>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .fold(PackedChunks(maxChars)) { packed, chunk -> packed.add(chunk) }
            .items
    }

    private fun score(chunk: KnowledgeChunk, query: String, queryTerms: List<String>): Int {
        val heading = normalize(chunk.heading)
        val text = normalize(chunk.text)
        var score = 0
        if (heading.contains(query)) score += HEADING_PHRASE_BOOST
        if (text.contains(query)) score += TEXT_PHRASE_BOOST
        if (chunk.urls.isNotEmpty()) score += AUTHORITY_URL_BOOST
        val acronym = chunk.institution.name.lowercase()
        queryTerms.forEach { term ->
            if (heading.contains(term)) score += HEADING_TERM_BOOST
            if (text.contains(term)) score += occurrenceCount(text, term)
            if (term == acronym) score += INSTITUTION_ACRONYM_BOOST
            if (headingAcronyms(chunk.heading).any { it == term }) score += HEADING_ACRONYM_BOOST
        }
        return score
    }

    private class PackedChunks(private val maxChars: Int, val items: MutableList<KnowledgeChunk> = mutableListOf()) {
        private var usedChars = 0

        fun add(chunk: KnowledgeChunk): PackedChunks {
            val separatorChars = if (items.isEmpty()) 0 else 2
            if (usedChars + separatorChars + chunk.text.length <= maxChars) {
                usedChars += separatorChars + chunk.text.length
                items += chunk
            }
            return this
        }
    }

    companion object {
        private const val HEADING_PHRASE_BOOST = 16
        private const val TEXT_PHRASE_BOOST = 5
        private const val HEADING_TERM_BOOST = 5
        private const val INSTITUTION_ACRONYM_BOOST = 10
        private const val HEADING_ACRONYM_BOOST = 8
        private const val AUTHORITY_URL_BOOST = 2

        internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase()

        internal fun tokens(value: String): List<String> = "[\\p{L}\\p{N}]+".toRegex().findAll(value)
            .map { it.value }
            .toList()

        private fun occurrenceCount(text: String, term: String): Int = "(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])"
            .toRegex()
            .findAll(text)
            .count()

        private fun headingAcronyms(heading: String): List<String> = "\\b[A-ZÁÉÍÓÚÑ]{2,}\\b".toRegex()
            .findAll(heading)
            .map { normalize(it.value) }
            .toList()
    }
}
