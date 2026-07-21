package cr.micampus.app.data.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private class FakeTransport(private val body: String) : UpdateTransport {
        override fun get(url: String): String = body
    }

    private val checker = UpdateChecker()

    @Test
    fun isNewerComparesVersionSegments() {
        assertTrue(checker.isNewer("1.4.2", "1.4.1"))
        assertFalse(checker.isNewer("1.4.1", "1.4.2"))
        assertFalse(checker.isNewer("1.4.0", "1.4"))
        assertTrue(checker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun checkForUpdateReturnsUpdateWhenApkAssetPresentAndNewer() = runTest {
        val transport = FakeTransport(releaseJson(tag = "v0.2.0", assets = apkAssetJson()))
        val checker = UpdateChecker(transport)

        val result = checker.checkForUpdate("0.1.0")

        assertNotNull(result)
        assertEquals("0.2.0", result!!.version)
        assertEquals("https://github.com/uni-toolscr/mi-campus/releases/download/v0.2.0/micampus.apk", result.downloadUrl)
        assertEquals("https://github.com/uni-toolscr/mi-campus/releases/tag/v0.2.0", result.releaseUrl)
        assertEquals("Release notes", result.notes)
    }

    @Test
    fun checkForUpdateReturnsNullWhenNoApkAsset() = runTest {
        val transport = FakeTransport(releaseJson(tag = "v0.2.0", assets = """[{"name":"source.zip","browser_download_url":"https://example.com/source.zip"}]"""))
        val checker = UpdateChecker(transport)

        val result = checker.checkForUpdate("0.1.0")

        assertNull(result)
    }

    @Test
    fun checkForUpdateReturnsNullWhenNotNewer() = runTest {
        val transport = FakeTransport(releaseJson(tag = "v0.1.0", assets = apkAssetJson()))
        val checker = UpdateChecker(transport)

        val result = checker.checkForUpdate("0.1.0")

        assertNull(result)
    }

    @Test
    fun checkForUpdateReturnsNullWhenDownloadUrlIsNotHttps() = runTest {
        val assets = """[{"name":"micampus.apk","browser_download_url":"http://example.com/micampus.apk"}]"""
        val transport = FakeTransport(releaseJson(tag = "v0.2.0", assets = assets))
        val checker = UpdateChecker(transport)

        val result = checker.checkForUpdate("0.1.0")

        assertNull(result)
    }

    private fun apkAssetJson() = """[{"name":"micampus.apk","browser_download_url":"https://github.com/uni-toolscr/mi-campus/releases/download/v0.2.0/micampus.apk"}]"""

    private fun releaseJson(tag: String, assets: String) = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/uni-toolscr/mi-campus/releases/tag/$tag",
          "body": "Release notes",
          "assets": $assets
        }
    """.trimIndent()
}
