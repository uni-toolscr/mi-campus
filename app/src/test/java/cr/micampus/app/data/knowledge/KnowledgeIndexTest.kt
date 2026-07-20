package cr.micampus.app.data.knowledge

import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeIndexTest {
    @Test
    fun realBundledCorpusParsesHeadingsUrlsAndStableWordBoundaries() {
        val corpus = KnowledgeMarkdownParser.parse(assetText())

        assertEquals(Institution.UNA, corpus.institution)
        assertEquals("2026-07-18", corpus.compiledOn)
        assertTrue(corpus.freshnessPolicy.orEmpty().contains("volatile"))
        assertTrue(corpus.officialStatus.orEmpty().contains("Unofficial reference"))
        assertTrue(corpus.chunks.any { it.heading.contains("2. Canonical identity") })
        assertTrue(corpus.chunks.all { it.text.split(Regex("\\s+")).size <= 400 })
        assertTrue(corpus.chunks.any { it.text.contains("#### Matrícula") })
        assertTrue(corpus.chunks.all { it.compiledOn == "2026-07-18" })
        assertTrue(corpus.chunks.flatMap { it.urls }.contains("https://www.una.ac.cr/"))
        assertTrue(corpus.chunks.any { it.volatile })
    }

    @Test
    fun oversizedSectionsUseFortyWordOverlap() {
        val words = (1..450).joinToString(" ") { "palabra$it" }
        val corpus = KnowledgeMarkdownParser.parse(
            """
            ---
            title: "Fixture"
            institution_acronym: "UNA"
            ---
            ## Sección extensa
            $words
            """.trimIndent(),
        )

        assertEquals(2, corpus.chunks.size)
        val first = corpus.chunks[0].text.split(" ")
        val second = corpus.chunks[1].text.split(" ")
        assertEquals(300, first.size)
        assertEquals(190, second.size)
        assertEquals(first.takeLast(40), second.take(40))
    }

    @Test
    fun shortSiblingSectionsAreCoalescedAndKeepTheirHeadings() {
        val corpus = KnowledgeMarkdownParser.parse(
            """
            ---
            title: "Fixture"
            institution_acronym: "UNA"
            ---
            ## Servicios
            ### Becas
            Información breve de becas.
            ### Residencias
            Información breve de residencias.
            """.trimIndent(),
        )

        assertEquals(1, corpus.chunks.size)
        assertTrue(corpus.chunks.single().text.contains("### Becas"))
        assertTrue(corpus.chunks.single().text.contains("### Residencias"))
    }

    @Test
    fun lexicalSearchFiltersInstitutionBoostsHeadingsAndPacksCharacters() {
        val unaHeading = chunk(
            id = "una-heading",
            institution = Institution.UNA,
            heading = "Matrícula UNA",
            text = "Información general de matrícula para estudiantes.",
            urls = listOf("https://www.una.ac.cr/matricula"),
        )
        val unaText = chunk(
            id = "una-text",
            institution = Institution.UNA,
            heading = "Servicios estudiantiles",
            text = "La matrícula se confirma en el sistema institucional.",
        )
        val ucr = chunk(
            id = "ucr-only",
            institution = Institution.UCR,
            heading = "Matrícula UCR",
            text = "Contenido exclusivo UCR.",
        )
        val index = KnowledgeIndex(listOf(unaText, ucr, unaHeading))

        val unaResults = index.search("MATRICULA UNA", setOf(Institution.UNA), maxChars = 500)
        assertEquals(listOf("una-heading", "una-text"), unaResults.map { it.id })
        assertTrue(index.search("matrícula", setOf(Institution.UCR), maxChars = 500).all { it.institution == Institution.UCR })
        assertTrue(index.search("matrícula", setOf(Institution.UCR), maxChars = 500).none { it.id.startsWith("una") })
        assertEquals(
            listOf("una-heading"),
            index.search("matrícula", setOf(Institution.UNA), unaHeading.text.length).map { it.id },
        )
    }

    @Test
    fun linkAllowlistAcceptsOnlyUrlsExtractedFromCorpusChunks() {
        val corpus = KnowledgeMarkdownParser.parse(assetText())
        val index = KnowledgeIndex(corpus.chunks)

        assertTrue(index.isAllowedUrl("https://www.una.ac.cr/"))
        assertFalse(index.isAllowedUrl("https://www.una.ac.cr.evil.example/"))
    }

    private fun chunk(
        id: String,
        institution: Institution,
        heading: String,
        text: String,
        urls: List<String> = emptyList(),
    ) = KnowledgeChunk(id, institution, heading, text, urls, volatile = false)

    private fun assetText(): String = File("src/main/assets/${AssetKnowledgeRepository.UNA_CORPUS_ASSET}").readText()
}
