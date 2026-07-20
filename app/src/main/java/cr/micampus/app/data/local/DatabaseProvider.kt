package cr.micampus.app.data.local

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var instance: MiCampusDatabase? = null
    fun get(context: Context): MiCampusDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, MiCampusDatabase::class.java, "micampus.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
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
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS course_styles (courseKey TEXT NOT NULL PRIMARY KEY, colorIndex INTEGER NOT NULL, emoji TEXT)")
        // Defensive: only add the column if some prior path (e.g. a database that skipped 4->5) lacks it.
        val hasDescription = db.query("PRAGMA table_info(draft_events)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == "description" }
        }
        if (!hasDescription) {
            db.execSQL("ALTER TABLE draft_events ADD COLUMN description TEXT")
        }
    }
}
val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN externalId TEXT")
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN externalUrl TEXT")
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN externalModifiedEpoch INTEGER")
    }
}
val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN sourceDocumentId TEXT")
        db.execSQL("ALTER TABLE draft_events ADD COLUMN sourceDocumentId TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_documents (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, sha256 TEXT NOT NULL, byteSize INTEGER NOT NULL, localFileName TEXT NOT NULL, importedAtEpoch INTEGER NOT NULL, lastProcessedAtEpoch INTEGER, latestStatus TEXT NOT NULL, latestError TEXT, latestDraftCount INTEGER NOT NULL, latestModels TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_imported_documents_sha256 ON imported_documents (sha256)")
        db.execSQL("CREATE TABLE IF NOT EXISTS document_import_attempts (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, batchId TEXT NOT NULL, startedAtEpoch INTEGER NOT NULL, finishedAtEpoch INTEGER, status TEXT NOT NULL, error TEXT, draftCount INTEGER NOT NULL, modelsUsed TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_document_import_attempts_documentId ON document_import_attempts (documentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_document_import_attempts_batchId ON document_import_attempts (batchId)")
    }
}
val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS syllabus_topics (" +
                "id TEXT NOT NULL PRIMARY KEY, courseCode TEXT, courseName TEXT, institution TEXT, " +
                "groupLabel TEXT NOT NULL, days TEXT NOT NULL, startTime TEXT, endTime TEXT, " +
                "weekIndex INTEGER, fromIso TEXT, toIso TEXT, topic TEXT NOT NULL, " +
                "excludedDates TEXT NOT NULL, conflictingExtraction INTEGER NOT NULL, " +
                "sourceDocumentId TEXT, status TEXT NOT NULL)",
        )
    }
}
val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE confirmed_events ADD COLUMN notifyThirtyMinutesBefore INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS document_transcriptions (documentId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(documentId, chunkIndex))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_document_transcriptions_documentId ON document_transcriptions (documentId)")
    }
}
val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_courses (accountId TEXT NOT NULL, courseId INTEGER NOT NULL, title TEXT NOT NULL, shortName TEXT, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, courseId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_sections (accountId TEXT NOT NULL, courseId INTEGER NOT NULL, sectionId INTEGER NOT NULL, title TEXT NOT NULL, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, courseId, sectionId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_moodle_sections_accountId_courseId ON moodle_sections(accountId, courseId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_resources (accountId TEXT NOT NULL, resourceId INTEGER NOT NULL, courseId INTEGER NOT NULL, sectionId INTEGER NOT NULL, moduleName TEXT NOT NULL, title TEXT NOT NULL, url TEXT, visible INTEGER NOT NULL, availability TEXT, remoteOrder INTEGER NOT NULL, PRIMARY KEY(accountId, resourceId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_moodle_resources_accountId_courseId_sectionId ON moodle_resources(accountId, courseId, sectionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_files (accountId TEXT NOT NULL, fileId TEXT NOT NULL, resourceId INTEGER NOT NULL, fileName TEXT NOT NULL, remotePath TEXT NOT NULL, url TEXT NOT NULL, mimeType TEXT, byteSize INTEGER NOT NULL, modifiedEpoch INTEGER, localFileName TEXT, downloadedBytes INTEGER NOT NULL, downloadedAtEpoch INTEGER, PRIMARY KEY(accountId, fileId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_moodle_files_accountId_resourceId ON moodle_files(accountId, resourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_content_sync_state (accountId TEXT NOT NULL PRIMARY KEY, lastSuccessfulSyncEpoch INTEGER, lastAttemptEpoch INTEGER, lastError TEXT, contentsSupported INTEGER)")
    }
}
val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS moodle_starred_files (accountId TEXT NOT NULL, fileId TEXT NOT NULL, PRIMARY KEY(accountId, fileId))")
    }
}
