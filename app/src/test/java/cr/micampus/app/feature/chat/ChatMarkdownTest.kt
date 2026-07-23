package cr.micampus.app.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    private fun links(value: AnnotatedString): List<Pair<String, String>> =
        value.getLinkAnnotations(0, value.length).map { range ->
            val url = (range.item as LinkAnnotation.Url).url
            value.text.substring(range.start, range.end) to url
        }

    @Test fun plainTextHasNoLinks() {
        val out = buildAssistantText("Hola, no hay enlaces aquí.", Color.Blue)
        assertEquals("Hola, no hay enlaces aquí.", out.text)
        assertTrue(links(out).isEmpty())
    }

    @Test fun markdownLinkRendersLabelAndUrl() {
        val out = buildAssistantText("Consulta [este enlace](https://una.ac.cr/becas) para más info.", Color.Blue)
        assertEquals("Consulta este enlace para más info.", out.text)
        assertEquals(listOf("este enlace" to "https://una.ac.cr/becas"), links(out))
    }

    @Test fun bareUrlIsAutolinkedWithoutTrailingPunctuation() {
        val out = buildAssistantText("Más en https://ucr.ac.cr/matricula.", Color.Blue)
        assertEquals("Más en https://ucr.ac.cr/matricula.", out.text)
        assertEquals(listOf("https://ucr.ac.cr/matricula" to "https://ucr.ac.cr/matricula"), links(out))
    }

    @Test fun unsafeSchemeIsNotLinked() {
        val out = buildAssistantText("Evita [esto](javascript:alert(1)) por seguridad.", Color.Blue)
        assertTrue(links(out).isEmpty())
        assertTrue(out.text.contains("javascript:alert(1)"))
    }

    @Test fun urlInsideMarkdownIsNotDoubleLinked() {
        val out = buildAssistantText("[sitio](https://una.ac.cr) y fin", Color.Blue)
        assertEquals("sitio y fin", out.text)
        assertEquals(listOf("sitio" to "https://una.ac.cr"), links(out))
    }

    @Test fun multipleLinksArePreservedInOrder() {
        val out = buildAssistantText("Ver [uno](https://a.cr) y luego https://b.cr aquí.", Color.Blue)
        assertEquals(
            listOf("uno" to "https://a.cr", "https://b.cr" to "https://b.cr"),
            links(out),
        )
    }
}
