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
class Migration13To14DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-13-14-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsCompletedFlagDefaultingToPending() {
        val version13 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE confirmed_events (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, institution TEXT NOT NULL, kind TEXT NOT NULL, startEpoch INTEGER NOT NULL, endEpoch INTEGER NOT NULL, location TEXT NOT NULL, notes TEXT NOT NULL, source TEXT NOT NULL, courseCode TEXT, sourcePage INTEGER, allDay INTEGER NOT NULL DEFAULT 0, externalId TEXT, externalUrl TEXT, externalModifiedEpoch INTEGER, sourceDocumentId TEXT, notifyThirtyMinutesBefore INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("INSERT INTO confirmed_events(id, title, institution, kind, startEpoch, endEpoch, location, notes, source) VALUES ('t1', 'Tarea 4', 'UCR', 'TAREA', 0, 1, '', '', 'local')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        version13.writableDatabase
        version13.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        MIGRATION_13_14.migrate(db)
        db.query("SELECT completed FROM confirmed_events WHERE id = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        helper.close()
    }
}
