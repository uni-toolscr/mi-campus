package cr.micampus.app.data.document

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExtractionCoordinatorTest {
    private val recognizer = object : OcrPageRecognizer {
        override suspend fun recognize(bitmap: Bitmap) = error("fake source handles OCR")
    }

    @Test fun selectableTextIsPreferredAndNormalized() = runBlocking {
        val source = FakeSource(embedded = listOf("  Clase\n\n  martes  "))
        val document = PdfExtractionCoordinator(recognizer).extract(source).getOrThrow()
        assertEquals("Clase martes", document.pages.single().text)
        assertEquals(0, source.ocrCalls)
    }

    @Test fun scannedPageUsesOcrFallback() = runBlocking {
        val source = FakeSource(embedded = listOf(null), ocr = listOf("Examen  final"))
        val document = PdfExtractionCoordinator(recognizer).extract(source).getOrThrow()
        assertEquals("Examen final", document.pages.single().text)
        assertEquals(1, source.ocrCalls)
    }

    @Test fun tabularPagesPreservePageEvidence() = runBlocking {
        val source = FakeSource(embedded = listOf("Fecha | Curso", "01/08 | MAT-1"))
        val pages = PdfExtractionCoordinator(recognizer).extract(source).getOrThrow().pages
        assertEquals(listOf(1, 2), pages.map { it.page })
        assertTrue(pages[1].text.contains("MAT-1"))
    }

    @Test fun failedOcrIsTyped() = runBlocking {
        val source = FakeSource(embedded = listOf(null), ocrError = IllegalStateException("ocr"))
        val error = PdfExtractionCoordinator(recognizer).extract(source).exceptionOrNull() as PdfExtractionException
        assertEquals(ImportError.OCR_FAILED, error.kind)
    }

    @Test fun emptyDocumentIsTyped() = runBlocking {
        val source = FakeSource(embedded = listOf(""), ocr = listOf(""))
        val error = PdfExtractionCoordinator(recognizer).extract(source).exceptionOrNull() as PdfExtractionException
        assertEquals(ImportError.EMPTY, error.kind)
    }

    private class FakeSource(
        private val embedded: List<String?>,
        private val ocr: List<String> = List(embedded.size) { "" },
        private val ocrError: Throwable? = null,
    ) : PdfPageSource {
        var ocrCalls = 0
        override val pageCount = embedded.size
        override fun embeddedText(page: Int) = embedded[page]
        override fun render(page: Int, maxPixels: Int): Bitmap = error("not used")
        override suspend fun ocrText(page: Int, recognizer: OcrPageRecognizer): String {
            ocrCalls++
            ocrError?.let { throw it }
            return ocr[page]
        }
    }
}
