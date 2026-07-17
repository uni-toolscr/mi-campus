package cr.micampus.app.data.local

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var instance: MiCampusDatabase? = null
    fun get(context: Context): MiCampusDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, MiCampusDatabase::class.java, "micampus.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
            .also { instance = it }
    }
}
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE confirmed_events ADD COLUMN courseCode TEXT"); db.execSQL("ALTER TABLE confirmed_events ADD COLUMN sourcePage INTEGER"); db.execSQL("ALTER TABLE draft_events ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT'"); db.execSQL("ALTER TABLE draft_events ADD COLUMN dateIso TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN startTime TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN endTime TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN institution TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN issues TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN evidence TEXT"); db.execSQL("ALTER TABLE draft_events ADD COLUMN sourcePage INTEGER") } }
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN allDay INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE export_records ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
    }
}
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE draft_events ADD COLUMN category TEXT")
        db.execSQL("ALTER TABLE draft_events ADD COLUMN location TEXT")
        db.execSQL("ALTER TABLE draft_events ADD COLUMN courseCode TEXT")
        db.execSQL("ALTER TABLE draft_events ADD COLUMN originalDateText TEXT")
    }
}
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE draft_events ADD COLUMN description TEXT")
    }
}
