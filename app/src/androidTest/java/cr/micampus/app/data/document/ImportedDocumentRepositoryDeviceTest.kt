package cr.micampus.app.data.document

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.data.local.DraftEventEntity
import cr.micampus.app.data.local.MiCampusDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportedDocumentRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MiCampusDatabase
    private lateinit var repository: ImportedDocumentRepository
    private lateinit var source: File

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MiCampusDatabase::class.java).allowMainThreadQueries().build()
        repository = ImportedDocumentRepository(context, database.importedDocumentDao(), database.eventDao())
        source = File(context.cacheDir, "retained-document-test.pdf").apply { writeBytes("%PDF retained test".toByteArray()) }
    }

    @After fun tearDown() {
        source.delete()
        database.close()
    }

    @Test fun duplicateBytesReuseFileButCreateNewAttempts() = runBlocking {
        val stored = repository.storeBatch(listOf(Uri.fromFile(source), Uri.fromFile(source))).stored
        assertEquals(2, stored.size)
        assertFalse(stored.first().reused)
        assertTrue(stored.last().reused)
        assertEquals(stored.first().document.id, stored.last().document.id)
        assertNotNull(repository.file(stored.first().document.id))
        assertNotNull(repository.contentUri(stored.first().document.id))

        val firstAttempt = repository.startAttempt(stored.first().document.id, "batch-a")
        val secondAttempt = repository.startAttempt(stored.first().document.id, "batch-b")
        assertNotEquals(firstAttempt, secondAttempt)
        repository.finishAttempt(secondAttempt, DocumentStatus.COMPLETED, draftCount = 2, modelsUsed = listOf("gemini-3.1-flash-lite"))
        val model = repository.documents.first().single()
        assertEquals(DocumentStatus.COMPLETED, model.status)
        assertEquals(2, model.draftCount)

        repository.startAttempt(stored.first().document.id, "batch-c")
        val processing = repository.documents.first().single()
        assertEquals(DocumentStatus.PROCESSING, processing.status)
        assertTrue(processing.modelsUsed.isEmpty())
    }

    @Test fun deletingPdfPreservesDraftAndClearsProvenance() = runBlocking {
        val document = repository.storeBatch(listOf(Uri.fromFile(source))).stored.single().document
        database.eventDao().upsertDraft(
            DraftEventEntity(
                id = "draft", title = "Evento", rawText = null, confidence = 1f,
                createdEpoch = 1, sourceDocumentId = document.id,
            ),
        )
        repository.delete(document.id)
        assertNull(repository.document(document.id))
        assertNull(repository.file(document.id))
        assertNull(database.eventDao().draft("draft")?.sourceDocumentId)
    }
}
