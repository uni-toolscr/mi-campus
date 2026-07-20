package cr.micampus.app.data.knowledge

import cr.micampus.app.core.model.Institution

/** Parses the intentionally small Markdown subset used by bundled institutional knowledge. */
object KnowledgeMarkdownParser {
    private val headingPattern = Regex("^(#{2,4})\\s+(.+?)\\s*$")
    private val urlPattern = Regex("https?://[^\\s)\\]>\\\"]+")
    private val volatileTerms = setOf(
        "fecha", "fechas", "plazo", "plazos", "deadline", "deadlines", "arancel", "aranceles",
        "tarifa", "tarifas", "costo", "costos", "fee", "fees", "horario", "horarios",
        "schedule", "schedules", "calendario", "calendar", "beca", "becas", "scholarship",
        "scholarships", "contacto", "contactos", "contact", "contacts", "actual", "current",
        "vigente", "verify", "verificar",
    )

    fun parse(markdown: String): KnowledgeCorpus {
        val (frontMatter, body) = splitFrontMatter(markdown)
        val institution = Institution.valueOf(required(frontMatter, "institution_acronym").uppercase())
        val title = frontMatter["title"] ?: "${institution.name} Knowledge Base"
        val sections = mutableListOf<Section>()
        val headingStack = mutableMapOf<Int, String>()
        var currentHeading: String? = null
        var currentBody = mutableListOf<String>()

        fun flush() {
            val heading = currentHeading ?: return
            val text = currentBody.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                val fullHeading = headingStack.toSortedMap().values.joinToString(" › ")
                sections += Section(fullHeading.ifBlank { heading }, text)
            }
            currentBody = mutableListOf()
        }

        body.lineSequence().forEach { line ->
            val match = headingPattern.matchEntire(line)
            if (match == null) {
                if (currentHeading != null) currentBody += line
            } else {
                val level = match.groupValues[1].length
                val heading = match.groupValues[2]
                // H4 is supporting detail for its H2/H3 section. Keeping the Markdown heading in
                // the body preserves that local structure without creating tiny orphan chunks.
                if (level == 4 && currentHeading != null) {
                    currentBody += line
                    return@forEach
                }
                flush()
                headingStack.keys.filter { it >= level }.toList().forEach(headingStack::remove)
                headingStack[level] = heading
                currentHeading = heading
            }
        }
        flush()

        val chunks = coalesceShortSiblings(sections).flatMapIndexed { index, section ->
            splitOversizedSection(
                idPrefix = "${institution.name.lowercase()}-${(index + 1).toString().padStart(4, '0')}",
                institution = institution,
                heading = section.heading,
                text = section.text,
                frontMatter = frontMatter,
            )
        }

        return KnowledgeCorpus(
            title = title,
            institution = institution,
            acronym = required(frontMatter, "institution_acronym"),
            compiledOn = frontMatter["compiled_on"],
            freshnessPolicy = frontMatter["freshness_policy"],
            officialStatus = frontMatter["official_status"],
            chunks = chunks,
        )
    }

    /** Coalesces adjacent tiny H3 sections under one H2 while keeping their local headings. */
    private fun coalesceShortSiblings(sections: List<Section>): List<Section> {
        val output = mutableListOf<Section>()
        var pending: Section? = null

        fun flushPending() {
            pending?.let(output::add)
            pending = null
        }

        sections.forEach { section ->
            val current = pending
            val canMerge = current != null &&
                current.topHeading == section.topHeading &&
                current.wordCount < SHORT_SECTION_WORDS &&
                section.wordCount < SHORT_SECTION_WORDS &&
                current.wordCount + section.wordCount <= CHUNK_WORDS
            when {
                canMerge -> pending = Section(
                    heading = "${current!!.heading} + ${section.leafHeading}",
                    text = "${current.text}\n\n### ${section.leafHeading}\n${section.text}",
                )
                section.wordCount < SHORT_SECTION_WORDS -> {
                    flushPending()
                    pending = section.copy(text = "### ${section.leafHeading}\n${section.text}")
                }
                else -> {
                    flushPending()
                    output += section
                }
            }
        }
        flushPending()
        return output
    }

    private fun splitOversizedSection(
        idPrefix: String,
        institution: Institution,
        heading: String,
        text: String,
        frontMatter: Map<String, String>,
    ): List<KnowledgeChunk> {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val windows = if (words.size <= SPLIT_THRESHOLD_WORDS) listOf(words) else buildList {
            var start = 0
            while (start < words.size) {
                val end = minOf(start + CHUNK_WORDS, words.size)
                add(words.subList(start, end))
                if (end == words.size) break
                start = end - OVERLAP_WORDS
            }
        }
        return windows.mapIndexed { index, wordsInChunk ->
            val chunkText = wordsInChunk.joinToString(" ")
            KnowledgeChunk(
                id = if (windows.size == 1) idPrefix else "$idPrefix-${index + 1}",
                institution = institution,
                heading = heading,
                text = chunkText,
                urls = extractUrls(chunkText),
                volatile = isVolatile(heading, chunkText),
                compiledOn = frontMatter["compiled_on"],
                freshnessPolicy = frontMatter["freshness_policy"],
                officialStatus = frontMatter["official_status"],
            )
        }
    }

    private fun splitFrontMatter(markdown: String): Pair<Map<String, String>, String> {
        val lines = markdown.lines()
        require(lines.firstOrNull()?.trim() == "---") { "Knowledge corpus must begin with YAML front matter" }
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        require(endIndex >= 0) { "Knowledge corpus front matter is not terminated" }
        val closingIndex = endIndex + 1
        val frontMatter = lines.subList(1, closingIndex)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"')
            }
            .toMap()
        return frontMatter to lines.drop(closingIndex + 1).joinToString("\n")
    }

    private fun extractUrls(text: String): List<String> = urlPattern.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':', '`', '\'', '}', ']') }
        .distinct()
        .toList()

    private fun isVolatile(heading: String, text: String): Boolean =
        KnowledgeIndex.tokens(KnowledgeIndex.normalize("$heading $text")).any { it in volatileTerms }

    private fun required(values: Map<String, String>, key: String): String =
        requireNotNull(values[key]) { "Knowledge corpus is missing '$key' front matter" }

    private data class Section(val heading: String, val text: String) {
        val topHeading: String get() = heading.substringBefore(" › ")
        val leafHeading: String get() = heading.substringAfterLast(" › ")
        val wordCount: Int get() = text.split(Regex("\\s+")).count(String::isNotBlank)
    }

    private const val SHORT_SECTION_WORDS = 80
    private const val SPLIT_THRESHOLD_WORDS = 400
    private const val CHUNK_WORDS = 300
    private const val OVERLAP_WORDS = 40
}
