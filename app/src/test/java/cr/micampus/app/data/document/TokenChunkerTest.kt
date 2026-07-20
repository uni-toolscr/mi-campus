package cr.micampus.app.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenChunkerTest {
    @Test fun serializedChunksIncludeLabelsInsideWordBudget() {
        val pages = listOf(
            PageText(1, (1..100).joinToString(" ") { "a$it" }),
            PageText(2, (1..100).joinToString(" ") { "b$it" }),
        )
        val chunks = TokenChunker(maxWords = 80, overlapWords = 5).chunk(pages)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { wordCount(it) <= 80 })
        assertTrue(chunks.first().startsWith("[Página 1]"))
        assertTrue(chunks.any { "[Página 2]" in it })
    }

    @Test fun overlapIsPreservedWhenSinglePageSplits() {
        val chunks = TokenChunker(maxWords = 12, overlapWords = 2)
            .chunk(listOf(PageText(4, (1..25).joinToString(" ") { "w$it" })))

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { wordCount(it) <= 12 })
        assertTrue(chunks.all { it.startsWith("[Página 4]") })
        val firstWords = chunks[0].substringAfter("] ").split(" ")
        val secondWords = chunks[1].substringAfter("] ").split(" ")
        assertEquals(firstWords.takeLast(2), secondWords.take(2))
    }

    @Test fun former3800WordInputIsRechunkedWithFinalDefaultBudget() {
        val chunks = TokenChunker().chunk(
            listOf(PageText(7, (1..3_800).joinToString(" ") { "palabra$it" })),
        )

        assertTrue(chunks.size >= 4)
        assertTrue(chunks.all { wordCount(it) <= 1_200 })
    }

    @Test fun emptyInputProducesNoChunks() {
        assertTrue(TokenChunker().chunk(emptyList()).isEmpty())
    }

    private fun wordCount(value: String) = value.split(Regex("\\s+")).count(String::isNotBlank)
}
