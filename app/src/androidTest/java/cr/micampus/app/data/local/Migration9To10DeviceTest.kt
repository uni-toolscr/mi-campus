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
class Migration9To10DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-9-10-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun migrationPreservesEventsAndDisablesTheNewReminderByDefault() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE confirmed_events (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT INTO confirmed_events (id) VALUES ('existing')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase

        MIGRATION_9_10.migrate(db)

        db.query("SELECT id, notifyThirtyMinutesBefore FROM confirmed_events WHERE id='existing'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("existing", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        helper.close()
    }
}
