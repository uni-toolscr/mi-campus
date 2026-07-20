package cr.micampus.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-7-8-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsDocumentsAttemptsAndProvenance() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE confirmed_events (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE draft_events (id TEXT NOT NULL PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        MIGRATION_7_8.migrate(db)
        assertTrue(hasColumn(db, "confirmed_events", "sourceDocumentId"))
        assertTrue(hasColumn(db, "draft_events", "sourceDocumentId"))
        assertTrue(hasTable(db, "imported_documents"))
        assertTrue(hasTable(db, "document_import_attempts"))
        helper.close()
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val index = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(index) else null }.any { it == column }
        }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
}
