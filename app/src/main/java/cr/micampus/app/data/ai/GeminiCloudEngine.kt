package cr.micampus.app.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class GeminiHttpResponse(val code: Int, val body: String = "")
interface GeminiTransport { fun execute(key: String, requestBody: String): GeminiHttpResponse }
class HttpUrlConnectionGeminiTransport : GeminiTransport {
    override fun execute(key: String, requestBody: String): GeminiHttpResponse {
        val connection = (URL(GeminiClient(key).endpoint).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000; requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("x-goog-api-key", key) }
        return try { connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }; val code = connection.responseCode; val stream = if (code in 200..299) connection.inputStream else connection.errorStream; val body = stream?.bufferedReader()?.use { reader -> val out = StringBuilder(); val buffer = CharArray(4096); while (out.length <= 1_000_000) { val count = reader.read(buffer); if (count < 0) break; out.append(buffer, 0, count); if (out.length > 1_000_000) break }; out.toString() } ?: ""; GeminiHttpResponse(code, body) } finally { connection.disconnect() }
    }
}
class GeminiCloudEngine(private val keyProvider: ApiKeyProvider, private val transport: GeminiTransport = HttpUrlConnectionGeminiTransport()) : CloudEventEngine {
    override suspend fun generate(prompt: String): CloudResult = withContext(Dispatchers.IO) {
        val key = keyProvider.read() ?: return@withContext CloudResult.Failure(CloudFailure.MISSING_KEY)
        runCatching {
            val client = GeminiClient(key)
            val structured = transport.execute(key, client.requestBody(prompt, structured = true))
            if (structured.code == 400 && !structured.body.contains("API_KEY_INVALID", ignoreCase = true)) {
                transport.execute(key, client.requestBody(prompt, structured = false))
            } else structured
        }.fold(onSuccess = { response ->
            when { response.code == 401 || response.code == 403 || (response.code == 400 && response.body.contains("API_KEY_INVALID", ignoreCase = true)) -> CloudResult.Failure(CloudFailure.INVALID_KEY); response.code == 429 -> CloudResult.Failure(CloudFailure.QUOTA); response.code >= 500 -> CloudResult.Failure(CloudFailure.SERVER); response.code !in 200..299 -> CloudResult.Failure(CloudFailure.SERVER); response.body.length > 1_000_000 -> CloudResult.Failure(CloudFailure.INVALID_RESPONSE); else -> runCatching { CloudResult.Success(com.google.gson.JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("candidates").first().asJsonObject.getAsJsonObject("content").getAsJsonArray("parts").first().asJsonObject.get("text").asString) }.getOrElse { CloudResult.Failure(CloudFailure.INVALID_RESPONSE) } }
        }, onFailure = { CloudResult.Failure(if (it is java.io.IOException) CloudFailure.OFFLINE else CloudFailure.INVALID_RESPONSE) })
    }
}
