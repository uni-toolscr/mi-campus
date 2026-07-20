package cr.micampus.app.data.moodle

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MoodleContentRepositoryTest {
    @Test fun canonicalUrlRemovesAccessTokensButRetainsMoodleParameters() {
        val result = MoodleContentRepository.canonicalUrl(
            "https://aulavirtual.una.ac.cr/pluginfile.php/1/a.pdf?token=secret&wstoken=also-secret&forcedownload=1#fragment",
        )

        assertEquals("https://aulavirtual.una.ac.cr/pluginfile.php/1/a.pdf?forcedownload=1", result)
        assertFalse(result.contains("secret"))
    }

    @Test fun canonicalUrlRejectsInsecureUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            MoodleContentRepository.canonicalUrl("http://aulavirtual.una.ac.cr/pluginfile.php/1/a.pdf")
        }
    }

    @Test fun canonicalAndAuthenticatedUrlsPreserveEscapedPathsAndQueries() {
        val canonical = MoodleContentRepository.canonicalUrl(
            "https://aulavirtual.una.ac.cr/pluginfile.php/1/Gu%C3%ADa%20de%20clase.pdf?forcedownload=1&name=a%26b&token=old",
        )

        assertEquals(
            "https://aulavirtual.una.ac.cr/pluginfile.php/1/Gu%C3%ADa%20de%20clase.pdf?forcedownload=1&name=a%26b",
            canonical,
        )
        assertEquals(
            "https://aulavirtual.una.ac.cr/webservice/pluginfile.php/1/Gu%C3%ADa%20de%20clase.pdf?forcedownload=1&name=a%26b&token=new%20token",
            MoodleContentRepository.authenticatedDownloadUrl(canonical, "new token").toASCIIString(),
        )
    }

    @Test fun canonicalUrlEncodesUnescapedUnicodeOnlyOnce() {
        assertEquals(
            "https://aulavirtual.una.ac.cr/pluginfile.php/1/Gu%C3%ADa%20semanal.pdf",
            MoodleContentRepository.canonicalUrl("https://aulavirtual.una.ac.cr/pluginfile.php/1/Guía%20semanal.pdf"),
        )
    }

    @Test fun authenticatedDownloadRejectsLookalikeHostAndUserInfo() {
        assertThrows(IllegalArgumentException::class.java) {
            MoodleContentRepository.authenticatedDownloadUrl(
                "https://aulavirtual.una.ac.cr.evil.example/pluginfile.php/1/a.pdf",
                "token",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoodleContentRepository.authenticatedDownloadUrl(
                "https://attacker@aulavirtual.una.ac.cr/pluginfile.php/1/a.pdf",
                "token",
            )
        }
    }

    @Test fun interruptedCopyStopsBeforeWritingNextChunk() = runTest {
        val output = ByteArrayOutputStream()
        val copy = launch {
            val ownJob = currentCoroutineContext()[Job]!!
            val input = object : InputStream() {
                override fun read(): Int = error("Bulk read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    buffer[offset] = 1
                    ownJob.cancel()
                    return 1
                }
            }
            MoodleContentRepository.copyCancellable(input, output)
        }

        copy.join()

        assertTrue(copy.isCancelled)
        assertEquals(0, output.size())
    }
}
