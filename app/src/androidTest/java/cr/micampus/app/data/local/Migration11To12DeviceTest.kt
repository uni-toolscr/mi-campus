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
class Migration11To12DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-11-12-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsOfflineMoodleCatalogTables() {
        createVersion11Database()

        val database = Room.databaseBuilder(context, MiCampusDatabase::class.java, name)
            .addMigrations(MIGRATION_11_12)
            .build()
        val db = database.openHelper.writableDatabase
        runBlocking {
            database.moodleContentDao().insertCourses(
                listOf(MoodleCourseEntity("una:42", 7, "Programación", "EIF-203", 1)),
            )
        }

        db.query("SELECT title, shortName FROM moodle_courses WHERE accountId = 'una:42' AND courseId = 7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Programación", cursor.getString(0))
            assertEquals("EIF-203", cursor.getString(1))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_moodle_files_accountId_resourceId'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        database.close()
    }

    private fun createVersion11Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
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
                        db.execSQL("CREATE TABLE document_transcriptions (documentId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(documentId,chunkIndex))")
                        db.execSQL("CREATE INDEX index_document_transcriptions_documentId ON document_transcriptions (documentId)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase
        helper.close()
    }
}
