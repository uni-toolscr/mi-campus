package cr.micampus.app.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import cr.micampus.app.core.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

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
/** Detected file family used to route extraction. Every family yields the same [ExtractedDocument]. */
internal enum class DocumentKind { PDF, IMAGE, TEXT }

private const val HEADER_BYTES = 16

/** Reads up to [HEADER_BYTES] leading bytes for magic-number sniffing without consuming the whole file. */
internal fun readHeader(input: java.io.InputStream): ByteArray {
    val buffer = ByteArray(HEADER_BYTES)
    var read = 0
    while (read < HEADER_BYTES) {
        val n = input.read(buffer, read, HEADER_BYTES - read)
        if (n < 0) break
        read += n
    }
    return if (read == HEADER_BYTES) buffer else buffer.copyOf(read)
}

/**
 * Routes by content signature first (works for the stored-file path where MIME is unknown), then
 * falls back to the [mimeHint] when present, and finally treats the file as UTF-8 text.
 */
internal fun classify(header: ByteArray, mimeHint: String?): DocumentKind {
    fun startsWith(vararg bytes: Int): Boolean =
        header.size >= bytes.size && bytes.withIndex().all { (i, b) -> header[i] == b.toByte() }
    return when {
        startsWith(0x25, 0x50, 0x44, 0x46) -> DocumentKind.PDF // "%PDF"
        startsWith(0x89, 0x50, 0x4E, 0x47) -> DocumentKind.IMAGE // PNG
        startsWith(0xFF, 0xD8, 0xFF) -> DocumentKind.IMAGE // JPEG
        startsWith(0x47, 0x49, 0x46, 0x38) -> DocumentKind.IMAGE // GIF
        header.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() -> DocumentKind.IMAGE // WEBP (RIFF….WEBP)
        mimeHint == "application/pdf" -> DocumentKind.PDF
        mimeHint?.startsWith("image/") == true -> DocumentKind.IMAGE
        else -> DocumentKind.TEXT
    }
}

class PdfDocumentExtractor(private val context: Context) {
    suspend fun extract(uri: Uri): Result<ExtractedDocument> {
        val resolver = context.contentResolver
        val header = try {
            resolver.openInputStream(uri)?.use(::readHeader)
                ?: return Result.failure(PdfExtractionException(ImportError.MALFORMED))
        } catch (error: SecurityException) {
            return Result.failure(PdfExtractionException(ImportError.PERMISSION_DENIED, error))
        } catch (error: java.io.IOException) {
            return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        }
        return when (classify(header, resolver.getType(uri))) {
            DocumentKind.PDF -> {
                val descriptor = try {
                    resolver.openFileDescriptor(uri, "r")
                        ?: return Result.failure(PdfExtractionException(ImportError.MALFORMED))
                } catch (error: SecurityException) {
                    return Result.failure(PdfExtractionException(ImportError.PERMISSION_DENIED, error))
                } catch (error: java.io.IOException) {
                    return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
                }
                extractDescriptor(descriptor)
            }
            DocumentKind.IMAGE -> extractImage { resolver.openInputStream(uri) }
            DocumentKind.TEXT -> extractText { resolver.openInputStream(uri) }
        }
    }

    suspend fun extract(file: File): Result<ExtractedDocument> {
        val header = try {
            file.inputStream().use(::readHeader)
        } catch (error: java.io.IOException) {
            return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        }
        return when (classify(header, null)) {
            DocumentKind.PDF -> {
                val descriptor = try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (error: java.io.IOException) {
                    return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
                }
                extractDescriptor(descriptor)
            }
            DocumentKind.IMAGE -> extractImage { file.inputStream() }
            DocumentKind.TEXT -> extractText { file.inputStream() }
        }
    }

    private suspend fun extractImage(open: () -> java.io.InputStream?): Result<ExtractedDocument> {
        val bitmap = try {
            open()?.use { BitmapFactory.decodeStream(it) }
        } catch (error: java.io.IOException) {
            return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        } ?: return Result.failure(PdfExtractionException(ImportError.MALFORMED))
        val recognizer = MlKitLatinRecognizer()
        return try {
            val text = runCatching { recognizer.recognize(bitmap) }
                .getOrElse { return Result.failure(PdfExtractionException(ImportError.OCR_FAILED, it)) }
                .let(::normalizeText)
            if (text.isBlank()) Result.failure(PdfExtractionException(ImportError.EMPTY))
            else Result.success(ExtractedDocument(listOf(PageText(1, text))))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun extractText(open: () -> java.io.InputStream?): Result<ExtractedDocument> {
        val raw = try {
            open()?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: java.io.IOException) {
            return Result.failure(PdfExtractionException(ImportError.MALFORMED, error))
        } ?: return Result.failure(PdfExtractionException(ImportError.MALFORMED))
        val text = normalizeText(raw)
        return if (text.isBlank()) Result.failure(PdfExtractionException(ImportError.EMPTY))
        else Result.success(ExtractedDocument(listOf(PageText(1, text))))
    }

    private fun normalizeText(value: String) = value.replace(" ", "").replace(Regex("\\s+"), " ").trim()

    private suspend fun extractDescriptor(descriptor: ParcelFileDescriptor): Result<ExtractedDocument> {
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
    suspend fun extract(file: File): Result<ExtractedDocument> = extractor.extract(file)
}
class TokenChunker(
    private val maxWords: Int = 1_200,
    private val overlapWords: Int = 80,
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
