package cr.micampus.app.data.document

import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PdfDocumentExtractorDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun malformedPdfIsTyped() = runBlocking {
        val file = File(context.cacheDir, "malformed.pdf").apply { writeText("not a PDF") }
        val error = PdfDocumentExtractor(context).extract(Uri.fromFile(file)).exceptionOrNull() as PdfExtractionException
        assertEquals(ImportError.MALFORMED, error.kind)
    }

    @Test fun passwordProtectedPdfIsTyped() = runBlocking {
        val bytes = Base64.decode(ENCRYPTED_EMPTY_PDF, Base64.DEFAULT)
        val file = File(context.cacheDir, "encrypted.pdf").apply { writeBytes(bytes) }
        val error = PdfDocumentExtractor(context).extract(Uri.fromFile(file)).exceptionOrNull() as PdfExtractionException
        assertEquals(ImportError.ENCRYPTED, error.kind)
    }

    private companion object {
        const val ENCRYPTED_EMPTY_PDF =
            "JVBERi0xLjcKJb/3ov4KMSAwIG9iago8PCAvRXh0ZW5zaW9ucyA8PCAvQURCRSA8PCAvQmFzZVZlcnNpb24gLzEuNyAvRXh0ZW5zaW9uTGV2ZWwgOCA+PiA+PiAvUGFnZXMgMiAwIFIgL1R5cGUgL0NhdGFsb2cgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL0NvdW50IDAgL0tpZHMgWyBdIC9UeXBlIC9QYWdlcyA+PgplbmRvYmoKMyAwIG9iago8PCAvQ0YgPDwgL1N0ZENGIDw8IC9BdXRoRXZlbnQgL0RvY09wZW4gL0NGTSAvQUVTVjMgL0xlbmd0aCAzMiA+PiA+PiAvRmlsdGVyIC9TdGFuZGFyZCAvTGVuZ3RoIDI1NiAvTyA8MDBmOWU5ODhjZjcxZjdmYjlmZDRlOGIyZWE3YjhkZjJkMGMyMGE0MWYwMDkwYjBiMjYwZThiNzUxNDBkOTBjZmE2ZTgxNTI1OWJkMjVlYTQ2NGFmYjAzYTI3MWM1OWRjPiAvT0UgPGMyNmM1YmU2ZTBkMGIzY2UzMWZlOWZmNmJkYzU1ODgwZDA5MDJmMTUxOGRhZWI0OTNlNTIzZWU5NmExODYyODk+IC9QIC00IC9QZXJtcyA8NTE4NjIzM2FiMTJlYTgyYzkxOTNlZjc3ODJhNjRlMGE+IC9SIDYgL1N0bUYgL1N0ZENGIC9TdHJGIC9TdGRDRiAvVSA8ZjI5ZDhkZjE4YmRiZTcwYjZiMGM1ZGY2ZmMxODNjOTkxYTI3MDMyMzcwYWZhZTAxZmZjZmMyY2RlYzE1ZmVkMDE0NTM2ZmE0M2MyYWFjMjcwOWQ3YmMyYmQ3NWM1NTJhPiAvVUUgPDJiOTM0NjAwYWQzMmJiYWI4NGVlYTA3M2RmOGQ1ZjA3OGY2NTZlNmM0NjEzMDg1YzhhMDliYjgwNWQ5NDBhNmE+IC9WIDUgPj4KZW5kb2JqCnhyZWYKMCA0CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAxNSAwMDAwMCBuIAowMDAwMDAwMTMwIDAwMDAwIG4gCjAwMDAwMDAxODMgMDAwMDAgbiAKdHJhaWxlciA8PCAvUm9vdCAxIDAgUiAvU2l6ZSA0IC9JRCBbPDE5YmI4MDk2NjYyODFmZmI5Y2ZmYTdkZWYzZTM2YmExPjwxOWJiODA5NjY2MjgxZmZiOWNmZmE3ZGVmM2UzNmJhMT5dIC9FbmNyeXB0IDMgMCBSID4+CnN0YXJ0eHJlZgo3MzAKJSVFT0YK"
    }
}
