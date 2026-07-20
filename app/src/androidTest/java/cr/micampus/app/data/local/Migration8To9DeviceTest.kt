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
class Migration8To9DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-8-9-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationAddsSyllabusTopicsWithoutReplacingExistingData() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE confirmed_events (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT INTO confirmed_events (id) VALUES ('existing')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        MIGRATION_8_9.migrate(db)
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='syllabus_topics'").use { it.moveToFirst() })
        assertTrue(db.query("SELECT id FROM confirmed_events WHERE id='existing'").use { it.moveToFirst() })
        helper.close()
    }
}
