package cr.micampus.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-10-11-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsReplacementTranscriptionsAndTheirDocumentIndex() {
        createVersion10Database()

        val database = Room.databaseBuilder(context, MiCampusDatabase::class.java, name)
            .addMigrations(MIGRATION_10_11)
            .build()
        val db = database.openHelper.writableDatabase
        runBlocking {
            database.documentTranscriptionDao().upsertAll(
                listOf(
                    DocumentTranscriptionEntity("doc", 0, 1, "anterior"),
                    DocumentTranscriptionEntity("doc", 0, 2, "reemplazo"),
                ),
            )
        }

        db.query("SELECT page, text FROM document_transcriptions WHERE documentId = 'doc' AND chunkIndex = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals("reemplazo", cursor.getString(1))
            assertEquals(1, cursor.count)
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_document_transcriptions_documentId'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        database.close()
    }

    private fun createVersion10Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE confirmed_events (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, institution TEXT NOT NULL, kind TEXT NOT NULL, startEpoch INTEGER NOT NULL, endEpoch INTEGER NOT NULL, location TEXT NOT NULL, notes TEXT NOT NULL, source TEXT NOT NULL, courseCode TEXT, sourcePage INTEGER, allDay INTEGER NOT NULL, externalId TEXT, externalUrl TEXT, externalModifiedEpoch INTEGER, sourceDocumentId TEXT, notifyThirtyMinutesBefore INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE draft_events (id TEXT NOT NULL PRIMARY KEY, title TEXT, rawText TEXT, confidence REAL NOT NULL, createdEpoch INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', dateIso TEXT, startTime TEXT, endTime TEXT, institution TEXT, issues TEXT, evidence TEXT, sourcePage INTEGER, category TEXT, location TEXT, courseCode TEXT, originalDateText TEXT, description TEXT, sourceDocumentId TEXT)")
                        db.execSQL("CREATE TABLE export_records (eventId TEXT NOT NULL, calendarId TEXT NOT NULL, providerEventId INTEGER NOT NULL, exportedAtEpoch INTEGER NOT NULL, contentHash TEXT NOT NULL DEFAULT '', PRIMARY KEY(eventId, calendarId))")
                        db.execSQL("CREATE TABLE course_styles (courseKey TEXT NOT NULL PRIMARY KEY, colorIndex INTEGER NOT NULL, emoji TEXT)")
                        db.execSQL("CREATE TABLE imported_documents (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, sha256 TEXT NOT NULL, byteSize INTEGER NOT NULL, localFileName TEXT NOT NULL, importedAtEpoch INTEGER NOT NULL, lastProcessedAtEpoch INTEGER, latestStatus TEXT NOT NULL, latestError TEXT, latestDraftCount INTEGER NOT NULL, latestModels TEXT)")
                        db.execSQL("CREATE UNIQUE INDEX index_imported_documents_sha256 ON imported_documents (sha256)")
                        db.execSQL("CREATE TABLE document_import_attempts (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, batchId TEXT NOT NULL, startedAtEpoch INTEGER NOT NULL, finishedAtEpoch INTEGER, status TEXT NOT NULL, error TEXT, draftCount INTEGER NOT NULL, modelsUsed TEXT)")
                        db.execSQL("CREATE INDEX index_document_import_attempts_documentId ON document_import_attempts (documentId)")
                        db.execSQL("CREATE INDEX index_document_import_attempts_batchId ON document_import_attempts (batchId)")
                        db.execSQL("CREATE TABLE syllabus_topics (id TEXT NOT NULL PRIMARY KEY, courseCode TEXT, courseName TEXT, institution TEXT, groupLabel TEXT NOT NULL, days TEXT NOT NULL, startTime TEXT, endTime TEXT, weekIndex INTEGER, fromIso TEXT, toIso TEXT, topic TEXT NOT NULL, excludedDates TEXT NOT NULL, conflictingExtraction INTEGER NOT NULL, sourceDocumentId TEXT, status TEXT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()
    }
}
