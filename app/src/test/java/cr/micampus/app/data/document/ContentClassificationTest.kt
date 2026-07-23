package cr.micampus.app.data.document

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentClassificationTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun pdfMagicRoutesToPdf() {
        assertEquals(DocumentKind.PDF, classify(bytes(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31), mimeHint = null))
    }

    @Test fun pngMagicRoutesToImage() {
        assertEquals(DocumentKind.IMAGE, classify(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), null))
    }

    @Test fun jpegMagicRoutesToImage() {
        assertEquals(DocumentKind.IMAGE, classify(bytes(0xFF, 0xD8, 0xFF, 0xE0), null))
    }

    @Test fun plainTextRoutesToText() {
        val header = "Horario 2026 clase".toByteArray()
        assertEquals(DocumentKind.TEXT, classify(header, mimeHint = null))
    }

    @Test fun mimeHintUsedWhenSignatureIsAmbiguous() {
        val header = "no magic here".toByteArray()
        assertEquals(DocumentKind.IMAGE, classify(header, mimeHint = "image/webp"))
        assertEquals(DocumentKind.PDF, classify(header, mimeHint = "application/pdf"))
        assertEquals(DocumentKind.TEXT, classify(header, mimeHint = "text/markdown"))
    }

    @Test fun signatureWinsOverMisleadingMimeHint() {
        // A real PDF mislabeled as text is still routed by its content signature.
        assertEquals(DocumentKind.PDF, classify(bytes(0x25, 0x50, 0x44, 0x46), mimeHint = "text/plain"))
    }

    @Test fun readHeaderReadsAtMostHeaderBytes() {
        val payload = ByteArray(64) { it.toByte() }
        val header = readHeader(ByteArrayInputStream(payload))
        assertEquals(16, header.size)
        assertEquals(0.toByte(), header[0])
        assertEquals(15.toByte(), header[15])
    }

    @Test fun readHeaderHandlesShortStreams() {
        val header = readHeader(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals(3, header.size)
    }
}
