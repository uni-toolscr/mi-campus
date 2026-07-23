package cr.micampus.app.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Renders assistant answers with clickable link embeds. Supports Markdown links `[texto](url)` and
 * bare `http(s)://` URLs, styled in [linkColor] and underlined. Only safe web URLs are linked; the
 * rest of the text is preserved verbatim. Links open via the ambient `LocalUriHandler`.
 */
fun buildAssistantText(raw: String, linkColor: Color): AnnotatedString {
    val tokens = linkTokens(raw)
    if (tokens.isEmpty()) return AnnotatedString(raw)
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var index = 0
        for (token in tokens) {
            if (token.start > index) append(raw.substring(index, token.start))
            withLink(LinkAnnotation.Url(token.url, styles)) { append(token.label) }
            index = token.end
        }
        if (index < raw.length) append(raw.substring(index))
    }
}

private data class LinkToken(val start: Int, val end: Int, val label: String, val url: String)

private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
private val BARE_URL = Regex("""(?<![\w@])https?://[^\s<>()\[\]]+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}\"'"

private fun linkTokens(raw: String): List<LinkToken> {
    val tokens = mutableListOf<LinkToken>()
    MARKDOWN_LINK.findAll(raw).forEach { match ->
        val url = match.groupValues[2]
        if (isSafeWebUrl(url)) {
            tokens += LinkToken(match.range.first, match.range.last + 1, match.groupValues[1], url)
        }
    }
    BARE_URL.findAll(raw).forEach { match ->
        // Skip URLs that fall inside a Markdown link already captured above.
        if (tokens.any { match.range.first < it.end && it.start <= match.range.last }) return@forEach
        var url = match.value
        var end = match.range.last + 1
        while (url.isNotEmpty() && url.last() in TRAILING_PUNCTUATION) {
            url = url.dropLast(1)
            end--
        }
        if (url.isNotEmpty() && isSafeWebUrl(url)) tokens += LinkToken(match.range.first, end, url, url)
    }
    return tokens.sortedBy { it.start }
}
