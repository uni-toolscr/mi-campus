package cr.micampus.app.data.update

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

interface UpdateTransport {
    fun get(url: String): String
}

class HttpUrlConnectionUpdateTransport : UpdateTransport {
    override fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MiCampus Android/1.0")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readLimited) ?: ByteArray(0)
            if (status !in 200..299) throw IOException("GitHub API request failed with status $status")
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Update check response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

class UpdateChecker(
    private val transport: UpdateTransport = HttpUrlConnectionUpdateTransport(),
    private val repo: String = "uni-toolscr/mi-campus",
) {
    suspend fun checkForUpdate(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val body = transport.get("https://api.github.com/repos/$repo/releases/latest")
            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return@withContext null
            val version = tagName.trimStart('v', 'V')
            val assets = json.getAsJsonArray("assets") ?: return@withContext null
            val apkAsset = assets.firstOrNull { asset ->
                asset.asJsonObject.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true
            }?.asJsonObject ?: return@withContext null
            val downloadUrl = apkAsset.get("browser_download_url")?.asString ?: return@withContext null
            if (!downloadUrl.startsWith("https://", ignoreCase = true)) return@withContext null
            if (!isNewer(version, currentVersion)) return@withContext null
            AppUpdate(
                version = version,
                downloadUrl = downloadUrl,
                releaseUrl = json.get("html_url")?.asString ?: "",
                notes = json.get("body")?.takeIf { !it.isJsonNull }?.asString ?: "",
            )
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val remoteSegments = remote.split(".")
        val currentSegments = current.split(".")
        val size = maxOf(remoteSegments.size, currentSegments.size)
        for (index in 0 until size) {
            val remotePart = remoteSegments.getOrElse(index) { "0" }
            val currentPart = currentSegments.getOrElse(index) { "0" }
            val remoteInt = parseSegment(remotePart)
            val currentInt = parseSegment(currentPart)
            if (remoteInt == null || currentInt == null) return remote.compareTo(current) > 0
            if (remoteInt != currentInt) return remoteInt > currentInt
        }
        return false
    }

    private fun parseSegment(segment: String): Int? {
        val digits = segment.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }
}
