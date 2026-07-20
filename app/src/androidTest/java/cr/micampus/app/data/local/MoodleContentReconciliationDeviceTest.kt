package cr.micampus.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.data.moodle.MoodleAccount
import cr.micampus.app.data.moodle.MoodleContentRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoodleContentReconciliationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, MiCampusDatabase::class.java).build()
    private val dao = database.moodleContentDao()

    @After fun close() = database.close()

    @Test fun replacementRemovesCoursesInvalidatesChangedFilesAndRetainsUnchangedDownloads() = runBlocking {
        val account = "una:42"
        val courses = listOf(
            MoodleCourseEntity(account, 1, "Curso uno", "C1", 0),
            MoodleCourseEntity(account, 2, "Curso eliminado", "C2", 1),
        )
        val sections = listOf(
            MoodleSectionEntity(account, 1, 10, "Semana 1", 0),
            MoodleSectionEntity(account, 2, 20, "Semana 2", 0),
        )
        val resources = listOf(
            MoodleResourceEntity(account, 11, 1, 10, "FILE", "A", null, true, null, 0),
            MoodleResourceEntity(account, 12, 1, 10, "FILE", "B", null, true, null, 1),
            MoodleResourceEntity(account, 21, 2, 20, "FILE", "C", null, true, null, 0),
        )
        fun file(id: String, resourceId: Long, modified: Long) = MoodleFileEntity(
            account,
            id,
            resourceId,
            "$id.pdf",
            "/",
            "https://aulavirtual.una.ac.cr/pluginfile.php/1/$id.pdf",
            "application/pdf",
            100,
            modified,
        )
        val originalFiles = listOf(file("a", 11, 1), file("b", 12, 1), file("c", 21, 1))
        dao.replaceCatalog(account, courses, sections, resources, originalFiles, MoodleContentSyncStateEntity(account, 1, 1, contentsSupported = true))
        dao.insertStarIfFileExists(account, "a")
        dao.insertStarIfFileExists(account, "c")
        dao.upsertFiles(originalFiles.map { it.copy(localFileName = "/private/${it.fileId}.pdf", downloadedBytes = 100, downloadedAtEpoch = 2) })

        val changedB = file("b", 12, 2)
        val obsolete = dao.replaceCatalog(
            account,
            courses.take(1),
            sections.take(1),
            resources.take(2),
            listOf(originalFiles[0], changedB),
            MoodleContentSyncStateEntity(account, 3, 3, contentsSupported = true),
        )

        assertEquals(listOf(1L), dao.courses(account).first().map(MoodleCourseEntity::courseId))
        assertEquals("/private/a.pdf", dao.file(account, "a")?.localFileName)
        assertNull(dao.file(account, "b")?.localFileName)
        assertEquals(setOf("b", "c"), obsolete.map(MoodleFileEntity::fileId).toSet())
        assertEquals(setOf("a"), dao.starredFileIds(account).first().toSet())
    }

    @Test fun accountCleanupDeletesCatalogAndPrivateDownloadedBytes() = runBlocking {
        val account = MoodleAccount("memory-only-token", 42, "Estudiante")
        val accountId = MoodleContentRepository.accountId(account)
        val downloaded = File(context.noBackupFilesDir, "moodle-cleanup-test.pdf").apply { writeText("private") }
        dao.insertCourses(listOf(MoodleCourseEntity(accountId, 1, "Curso", null, 0)))
        dao.upsertFiles(
            listOf(
                MoodleFileEntity(
                    accountId,
                    "file",
                    11,
                    "file.pdf",
                    "/",
                    "https://aulavirtual.una.ac.cr/pluginfile.php/1/file.pdf",
                    "application/pdf",
                    downloaded.length(),
                    1,
                    downloaded.absolutePath,
                    downloaded.length(),
                    2,
                ),
            ),
        )
        dao.insertStarIfFileExists(accountId, "file")

        MoodleContentRepository(context, dao).clearAccount(account)

        assertEquals(emptyList<MoodleCourseEntity>(), dao.courses(accountId).first())
        assertEquals(emptyList<MoodleFileEntity>(), dao.files(accountId).first())
        assertEquals(emptyList<String>(), dao.starredFileIds(accountId).first())
        assertEquals(false, downloaded.exists())
    }

    @Test fun starsAreAccountScopedAndUnknownFilesCannotBeStarred() = runBlocking {
        val account = MoodleAccount("memory-only-token", 42, "Estudiante")
        val otherAccount = MoodleAccount("other-token", 43, "Otra estudiante")
        val accountId = MoodleContentRepository.accountId(account)
        dao.upsertFiles(
            listOf(
                MoodleFileEntity(
                    accountId, "known", 11, "known.pdf", "/",
                    "https://aulavirtual.una.ac.cr/pluginfile.php/1/known.pdf", "application/pdf", 1, 1,
                ),
            ),
        )
        val repository = MoodleContentRepository(context, dao)

        repository.setFileStarred(account, "known", starred = true)
        repository.setFileStarred(account, "missing", starred = true)
        repository.setFileStarred(otherAccount, "known", starred = true)

        assertEquals(setOf("known"), dao.starredFileIds(accountId).first().toSet())
        assertEquals(setOf("known"), repository.catalog(account).first().starredFileIds)
        assertEquals(emptyList<String>(), dao.starredFileIds(MoodleContentRepository.accountId(otherAccount)).first())
        repository.setFileStarred(account, "known", starred = false)
        assertEquals(emptyList<String>(), dao.starredFileIds(accountId).first())
    }

}
