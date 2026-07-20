package cr.micampus.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration12To13DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-12-13-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsAccountScopedStarredFilesTable() {
        val version12 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE moodle_courses (accountId TEXT NOT NULL, courseId INTEGER NOT NULL, title TEXT NOT NULL, shortName TEXT, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, courseId))")
                        db.execSQL("CREATE TABLE moodle_sections (accountId TEXT NOT NULL, courseId INTEGER NOT NULL, sectionId INTEGER NOT NULL, title TEXT NOT NULL, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, courseId, sectionId))")
                        db.execSQL("CREATE TABLE moodle_resources (accountId TEXT NOT NULL, resourceId INTEGER NOT NULL, courseId INTEGER NOT NULL, sectionId INTEGER NOT NULL, moduleName TEXT NOT NULL, title TEXT NOT NULL, url TEXT, visible INTEGER NOT NULL, availability TEXT, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, resourceId))")
                        db.execSQL("CREATE TABLE moodle_files (accountId TEXT NOT NULL, fileId TEXT NOT NULL, resourceId INTEGER NOT NULL, fileName TEXT NOT NULL, remotePath TEXT NOT NULL, url TEXT NOT NULL, mimeType TEXT, byteSize INTEGER NOT NULL, modifiedEpoch INTEGER, localFileName TEXT, downloadedBytes INTEGER NOT NULL, downloadedAtEpoch INTEGER, PRIMARY KEY(accountId, fileId))")
                        db.execSQL("CREATE TABLE moodle_content_sync_state (accountId TEXT NOT NULL PRIMARY KEY, lastSuccessfulSyncEpoch INTEGER, lastAttemptEpoch INTEGER, lastError TEXT, contentsSupported INTEGER)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        version12.writableDatabase
        version12.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        MIGRATION_12_13.migrate(db)
        db.execSQL("INSERT INTO moodle_starred_files(accountId, fileId) VALUES ('una:42', 'file-1')")
        db.query("SELECT fileId FROM moodle_starred_files WHERE accountId = 'una:42'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("file-1", cursor.getString(0))
        }
        helper.close()
    }
}
