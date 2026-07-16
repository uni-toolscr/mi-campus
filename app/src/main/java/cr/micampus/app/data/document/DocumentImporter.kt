package cr.micampus.app.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import cr.micampus.app.core.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImportResult(val drafts: List<CalendarEventDraft>, val warnings: List<String> = emptyList(), val error: ImportError? = null)
enum class ImportError { SELECTABLE, SCANNED, ENCRYPTED, MALFORMED, EMPTY, UNSUPPORTED, OCR_FAILED, PERMISSION_DENIED }
class PdfExtractionException(val kind: ImportError, cause: Throwable? = null) : Exception(kind.name, cause)
data class PageText(val page: Int, val text: String)
data class ExtractedDocument(val pages: List<PageText>) { val text: String get() = pages.joinToString("\n") { it.text } }
interface PdfPageSource {
    val pageCount: Int
    fun embeddedText(page: Int): String?
    fun render(page: Int, maxPixels: Int = 4_000_000): Bitmap
    suspend fun ocrText(page: Int, recognizer: OcrPageRecognizer): String {
        val bitmap = render(page)
        return try { recognizer.recognize(bitmap) } finally { bitmap.recycle() }
    }
}
interface OcrPageRecognizer { suspend fun recognize(bitmap: Bitmap): String }
class MlKitLatinRecognizer : OcrPageRecognizer, java.io.Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation -> recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) } }
    override fun close() = recognizer.close()
}
class PdfExtractionCoordinator(private val ocr: OcrPageRecognizer) {
    suspend fun extract(source: PdfPageSource): Result<ExtractedDocument> = runCatching {
        val pages = buildList {
            for (page in 0 until source.pageCount) {
                val embedded = source.embeddedText(page)?.normalize()
                if (!embedded.isNullOrBlank()) {
                    add(PageText(page + 1, embedded))
                } else {
                    val text = runCatching { source.ocrText(page, ocr).normalize() }
                        .getOrElse { throw PdfExtractionException(ImportError.OCR_FAILED, it) }
                    if (text.isNotBlank()) add(PageText(page + 1, text))
                }
            }
        }
        if (pages.isEmpty()) throw PdfExtractionException(ImportError.EMPTY)
        ExtractedDocument(pages)
    }
    fun close() { (ocr as? java.io.Closeable)?.close() }
    private fun String.normalize() = replace("\u0000", "").replace(Regex("\\s+"), " ").trim()
}
class PdfDocumentExtractor(private val context: Context) {
    suspend fun extract(uri: Uri): Result<ExtractedDocument> {
        val resolver = context.contentResolver
        val type = resolver.getType(uri)
        if (type != null && type != "application/pdf") return Result.failure(PdfExtractionException(ImportError.UNSUPPORTED))
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r") ?: return Result.failure(PdfExtractionException(ImportError.MALFORMED))
        } catch (error: SecurityException) {
            return Result.failure(PdfExtractionException(ImportError.PERMISSION_DENIED, error))
        } catch (error: java.io.IOException) {
            return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        }
        val recognizer = MlKitLatinRecognizer()
        val coordinator = PdfExtractionCoordinator(recognizer)
        return try {
            descriptor.use { pfd ->
                val renderer = try { PdfRenderer(pfd) } catch (error: SecurityException) {
                    return Result.failure(PdfExtractionException(ImportError.ENCRYPTED, error))
                }
                renderer.use { coordinator.extract(AndroidPdfPageSource(it)) }
            }
        } catch (error: java.io.IOException) {
            Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        } catch (error: Exception) {
            if (error is PdfExtractionException) Result.failure(error)
            else Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        } finally {
            coordinator.close()
        }
    }
}
private class AndroidPdfPageSource(private val renderer: PdfRenderer) : PdfPageSource {
    override val pageCount get() = renderer.pageCount
    override fun embeddedText(page: Int): String? = if (Build.VERSION.SDK_INT >= 35) embeddedTextApi35(page) else null
    @RequiresApi(35)
    private fun embeddedTextApi35(index: Int): String? {
        val page = renderer.openPage(index)
        return try { page.textContents.joinToString(" ") { it.text } } finally { page.close() }
    }
    override fun render(page: Int, maxPixels: Int): Bitmap { val p = renderer.openPage(page); return try { val scale = minOf(1f, kotlin.math.sqrt(maxPixels.toDouble() / (p.width * p.height).coerceAtLeast(1)).toFloat()); val bitmap = createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888); p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap } finally { p.close() } }
}
class DocumentImporter(private val extractor: PdfDocumentExtractor) {
    suspend fun extract(uri: Uri): Result<ExtractedDocument> = extractor.extract(uri)
}
class TokenChunker(
    private val maxWords: Int = 2_400,
    private val overlapWords: Int = 120,
) {
    init {
        require(maxWords >= PAGE_LABEL_WORDS + 1)
        require(overlapWords in 0 until maxWords - PAGE_LABEL_WORDS)
    }

    fun chunk(pages: List<PageText>): List<String> {
        val words = pages.flatMap { page ->
            page.text.split(Regex("\\s+")).filter(String::isNotBlank).map { Word(page.page, it) }
        }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            var end = start
            var serializedWords = 0
            var currentPage: Int? = null
            while (end < words.size) {
                val word = words[end]
                val addedWords = 1 + if (word.page != currentPage) PAGE_LABEL_WORDS else 0
                if (serializedWords + addedWords > maxWords) break
                serializedWords += addedWords
                currentPage = word.page
                end++
            }
            check(end > start) { "Chunk budget cannot fit a page label and one word" }
            chunks += serialize(words.subList(start, end))
            if (end == words.size) break
            start = (end - overlapWords).coerceAtLeast(start + 1)
        }
        return chunks
    }

    private fun serialize(words: List<Word>): String = buildString {
        var currentPage: Int? = null
        words.forEach { word ->
            if (word.page != currentPage) {
                if (isNotEmpty()) append('\n')
                append("[Página ${word.page}]")
                currentPage = word.page
            }
            append(' ').append(word.value)
        }
    }

    private data class Word(val page: Int, val value: String)

    private companion object {
        const val PAGE_LABEL_WORDS = 2
    }
}
