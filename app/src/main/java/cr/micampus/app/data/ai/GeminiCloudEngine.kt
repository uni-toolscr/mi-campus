package cr.micampus.app.data.ai

import com.google.gson.JsonParser
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import cr.micampus.app.data.diagnostics.NoOpImportDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.math.min
import kotlin.random.Random

data class GeminiHttpResponse(
    val code: Int,
    val body: String = "",
    val retryAfterMillis: Long? = null,
)

interface GeminiTransport {
    fun execute(model: String, endpoint: String, key: String, requestBody: String): GeminiHttpResponse
}

class HttpUrlConnectionGeminiTransport : GeminiTransport {
    override fun execute(model: String, endpoint: String, key: String, requestBody: String): GeminiHttpResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        return try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (out.length <= MAX_RESPONSE_CHARS) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    out.append(buffer, 0, count)
                    if (out.length > MAX_RESPONSE_CHARS) break
                }
                out.toString()
            }.orEmpty()
            val retryAfterMillis = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1_000)
            GeminiHttpResponse(code, body, retryAfterMillis)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_RESPONSE_CHARS = 1_000_000 }
}

class GeminiCloudEngine(
    private val keyProvider: ApiKeyProvider,
    private val transport: GeminiTransport = HttpUrlConnectionGeminiTransport(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val jitterMillis: () -> Long = { Random.nextLong(0, 251) },
    private val diagnostics: ImportDiagnosticsRecorder = NoOpImportDiagnostics,
) {
    fun newSession(
        profile: AiGenerationProfile = AiGenerationProfile.SYLLABUS_IMPORT,
        traceIdProvider: () -> String? = { null },
    ): CloudEventEngine = Session(traceIdProvider, profile)

    suspend fun generate(
        prompt: String,
        profile: AiGenerationProfile = AiGenerationProfile.SYLLABUS_IMPORT,
    ): CloudResult = newSession(profile = profile).generate(prompt)

    suspend fun generateChat(prompt: String): CloudResult = generate(prompt, AiGenerationProfile.CHAT_ANSWER)

    suspend fun generateEvent(prompt: String): CloudResult = generate(prompt, AiGenerationProfile.CHAT_EVENT)

    private inner class Session(
        private val traceIdProvider: () -> String?,
        private val profile: AiGenerationProfile,
    ) : CloudEventEngine {
        private var modelIndex = 0
        private val used = linkedSetOf<String>()
        override val modelsUsed: Set<String> get() = used.toSet()

        override suspend fun generate(prompt: String): CloudResult = withContext(Dispatchers.IO) {
            val key = keyProvider.read() ?: return@withContext CloudResult.Failure(CloudFailure.MISSING_KEY)
            var lastFailure = CloudFailure.SERVER
            for (index in modelIndex until MODELS.size) {
                val model = MODELS[index]
                var retry = 0
                while (retry <= MAX_RETRIES_PER_MODEL) {
                    diagnostics.record(
                        traceIdProvider(),
                        ImportDiagnosticEvent(phase = "cloud_model_attempt", backend = "cloud", model = model, retryIndex = retry),
                    )
                    val response = try {
                        executeModel(key, model, prompt, profile)
                    } catch (error: SocketTimeoutException) {
                        diagnostics.record(
                            traceIdProvider(),
                            ImportDiagnosticEvent(phase = "cloud_transport_failure", backend = "cloud", model = model, failure = "timeout", retryIndex = retry),
                        )
                        if (retry < MAX_RETRIES_PER_MODEL) {
                            sleeper(backoffMillis(retry, null))
                            retry++
                            continue
                        }
                        lastFailure = CloudFailure.SERVER
                        null
                    } catch (_: IOException) {
                        diagnostics.record(
                            traceIdProvider(),
                            ImportDiagnosticEvent(phase = "cloud_transport_failure", backend = "cloud", model = model, failure = "offline", retryIndex = retry),
                        )
                        return@withContext CloudResult.Failure(CloudFailure.OFFLINE)
                    } catch (_: Exception) {
                        diagnostics.record(
                            traceIdProvider(),
                            ImportDiagnosticEvent(phase = "cloud_transport_failure", backend = "cloud", model = model, failure = "invalid_response", retryIndex = retry),
                        )
                        return@withContext CloudResult.Failure(CloudFailure.INVALID_RESPONSE)
                    }

                    if (response == null) break
                    diagnostics.record(
                        traceIdProvider(),
                        ImportDiagnosticEvent(phase = "cloud_http_response", backend = "cloud", model = model, httpStatus = response.code, retryIndex = retry),
                    )
                    val classified = classify(response)
                    if (classified is Classified.Success) {
                        modelIndex = index
                        used += model
                        diagnostics.record(
                            traceIdProvider(),
                            ImportDiagnosticEvent(phase = "cloud_model_success", backend = "cloud", model = model, outcome = "success", retryIndex = retry),
                        )
                        return@withContext CloudResult.Success(classified.text, model)
                    }
                    if (classified is Classified.Terminal) {
                        diagnostics.record(
                            traceIdProvider(),
                            ImportDiagnosticEvent(phase = "cloud_model_failure", backend = "cloud", model = model, failure = classified.failure.name.lowercase(), outcome = "terminal", retryIndex = retry),
                        )
                        return@withContext CloudResult.Failure(classified.failure)
                    }
                    classified as Classified.Transient
                    lastFailure = classified.failure
                    diagnostics.record(
                        traceIdProvider(),
                        ImportDiagnosticEvent(phase = "cloud_model_failure", backend = "cloud", model = model, failure = classified.failure.name.lowercase(), outcome = "transient", retryIndex = retry),
                    )
                    if (retry < MAX_RETRIES_PER_MODEL) {
                        sleeper(backoffMillis(retry, response.retryAfterMillis))
                        retry++
                    } else break
                }
                modelIndex = index + 1
            }
            CloudResult.Failure(lastFailure)
        }
    }

    private fun executeModel(
        key: String,
        model: String,
        prompt: String,
        profile: AiGenerationProfile,
    ): GeminiHttpResponse {
        val client = GeminiClient(key, model)
        val first = transport.execute(model, client.endpoint, key, client.requestBody(prompt, profile))
        return if (profile.structuredOutput && first.code == 400 && !first.body.contains("API_KEY_INVALID", ignoreCase = true)) {
            transport.execute(model, client.endpoint, key, client.requestBody(prompt, profile, structured = false))
        } else first
    }

    private fun classify(response: GeminiHttpResponse): Classified = when {
        response.code == 401 || response.code == 403 ||
            (response.code == 400 && response.body.contains("API_KEY_INVALID", ignoreCase = true)) -> Classified.Terminal(CloudFailure.INVALID_KEY)
        response.code == 408 || response.code == 404 || response.code == 429 || response.code >= 500 ->
            Classified.Transient(if (response.code == 429) CloudFailure.QUOTA else CloudFailure.SERVER)
        response.code !in 200..299 -> Classified.Terminal(CloudFailure.SERVER)
        response.body.length > MAX_RESPONSE_CHARS -> Classified.Terminal(CloudFailure.INVALID_RESPONSE)
        else -> runCatching {
            JsonParser.parseString(response.body).asJsonObject
                .getAsJsonArray("candidates").first().asJsonObject
                .getAsJsonObject("content").getAsJsonArray("parts").first().asJsonObject
                .get("text").asString
        }.fold(
            onSuccess = { Classified.Success(it) },
            onFailure = { Classified.Terminal(CloudFailure.INVALID_RESPONSE) },
        )
    }

    private fun backoffMillis(retry: Int, retryAfterMillis: Long?): Long {
        val exponential = 500L * (1L shl retry)
        return min(MAX_BACKOFF_MILLIS, retryAfterMillis ?: (exponential + jitterMillis()))
    }

    private sealed interface Classified {
        data class Success(val text: String) : Classified
        data class Transient(val failure: CloudFailure) : Classified
        data class Terminal(val failure: CloudFailure) : Classified
    }

    companion object {
        val MODELS = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash")
        private const val MAX_RETRIES_PER_MODEL = 1
        private const val MAX_BACKOFF_MILLIS = 2_000L
        private const val MAX_RESPONSE_CHARS = 1_000_000
    }
}
