package cr.micampus.app.data.ai

import com.google.gson.JsonParser
import cr.micampus.app.data.diagnostics.DiagnosticsExport
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GeminiCloudEngineTest {
    private class Keys(private val value: String?) : ApiKeyProvider { override fun read() = value }
    private data class Call(val model: String, val endpoint: String, val body: String)
    private class Transport(private val responder: (Call, Int) -> GeminiHttpResponse) : GeminiTransport {
        val calls = mutableListOf<Call>()
        override fun execute(model: String, endpoint: String, key: String, requestBody: String): GeminiHttpResponse {
            val call = Call(model, endpoint, requestBody)
            calls += call
            return responder(call, calls.size)
        }
    }
    private class Diagnostics : ImportDiagnosticsRecorder {
        override val available = true
        val events = mutableListOf<ImportDiagnosticEvent>()
        override suspend fun beginAttempt(localEnabled: Boolean, cloudEnabled: Boolean) = "trace"
        override suspend fun record(traceId: String?, event: ImportDiagnosticEvent) { events += event }
        override suspend fun finish(traceId: String?, outcome: String, backend: String?, model: String?, draftCount: Int) = Unit
        override suspend fun export(): DiagnosticsExport? = null
        override suspend fun clear() { events.clear() }
    }

    @Test fun requestUsesStableModelAndHeader() {
        val client = GeminiClient("secret")
        assertTrue(client.endpoint.contains("gemini-3.5-flash"))
        assertEquals("x-goog-api-key", client.authorizationHeader().first)
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash"), GeminiCloudEngine.MODELS)
    }

    @Test fun requestPrefersJsonSchemaStructuredOutput() {
        val body = JsonParser.parseString(GeminiClient("secret").requestBody("texto")).asJsonObject
        val config = body.getAsJsonObject("generationConfig")
        assertEquals("application/json", config.get("responseMimeType").asString)
        assertEquals("object", config.getAsJsonObject("responseJsonSchema").get("type").asString)
        assertEquals("LOW", config.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
        val oldModelConfig = JsonParser.parseString(GeminiClient("secret", "gemini-2.5-flash").requestBody("texto")).asJsonObject.getAsJsonObject("generationConfig")
        assertEquals(0, oldModelConfig.getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)
    }

    @Test fun structuredSchemaCoversSyllabusSections() {
        val body = JsonParser.parseString(GeminiClient("secret").requestBody("texto")).asJsonObject
        val properties = body.getAsJsonObject("generationConfig").getAsJsonObject("responseJsonSchema").getAsJsonObject("properties")
        for (section in listOf("course", "groups", "weeks", "holidays", "events")) assertTrue(section, properties.has(section))
    }

    @Test fun chatProfileDoesNotLeakImporterPromptOrSchema() {
        val body = JsonParser.parseString(
            GeminiClient("secret").requestBody("¿Qué debo estudiar?", AiGenerationProfile.CHAT_ANSWER),
        ).asJsonObject
        val text = body.getAsJsonArray("contents").first().asJsonObject
            .getAsJsonArray("parts").first().asJsonObject.get("text").asString
        val config = body.getAsJsonObject("generationConfig")

        assertEquals("¿Qué debo estudiar?", text)
        assertFalse(text.contains("CONTRATO JSON"))
        assertFalse(config.has("responseMimeType"))
        assertFalse(config.has("responseJsonSchema"))
        assertEquals(CHAT_MAX_OUTPUT_TOKENS, config.get("maxOutputTokens").asInt)
        assertEquals("LOW", config.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
    }

    @Test fun importerProfileKeepsStrictSchemaAndItsOutputLimit() {
        val body = JsonParser.parseString(
            GeminiClient("secret").requestBody("documento", AiGenerationProfile.SYLLABUS_IMPORT),
        ).asJsonObject
        val config = body.getAsJsonObject("generationConfig")

        assertEquals("application/json", config.get("responseMimeType").asString)
        assertTrue(config.has("responseJsonSchema"))
        assertEquals(IMPORT_MAX_OUTPUT_TOKENS, config.get("maxOutputTokens").asInt)
    }

    @Test fun eventProfileUsesTheSmallBudgetAndItsOwnSchema() {
        val body = JsonParser.parseString(
            GeminiClient("secret").requestBody("tengo examen mañana", AiGenerationProfile.CHAT_EVENT),
        ).asJsonObject
        val config = body.getAsJsonObject("generationConfig")

        assertEquals(EVENT_MAX_OUTPUT_TOKENS, config.get("maxOutputTokens").asInt)
        val properties = config.getAsJsonObject("responseJsonSchema").getAsJsonObject("properties")
        assertTrue(properties.has("title"))
        assertTrue(properties.has("date"))
        assertFalse(properties.has("course"))
        assertFalse(properties.has("events"))
    }

    @Test fun generateChatSendsOnlyTheChatPrompt() = runBlocking {
        val transport = Transport { _, _ -> response() }

        engine("k", transport).generateChat("Ayúdame con mi horario")

        val body = JsonParser.parseString(transport.calls.single().body).asJsonObject
        val text = body.getAsJsonArray("contents").first().asJsonObject
            .getAsJsonArray("parts").first().asJsonObject.get("text").asString
        assertEquals("Ayúdame con mi horario", text)
        assertFalse(body.getAsJsonObject("generationConfig").has("responseJsonSchema"))
    }

    @Test fun missingAndInvalidKeysDoNotFailOver() = runBlocking {
        val unused = Transport { _, _ -> response() }
        assertEquals(CloudFailure.MISSING_KEY, (engine(null, unused).generate("x") as CloudResult.Failure).kind)
        assertTrue(unused.calls.isEmpty())
        val invalid = Transport { _, _ -> GeminiHttpResponse(401) }
        assertEquals(CloudFailure.INVALID_KEY, (engine("k", invalid).generate("x") as CloudResult.Failure).kind)
        assertEquals(1, invalid.calls.size)
    }

    @Test fun rateLimitRetriesThenFallsBackAndSticks() = runBlocking {
        val diagnostics = Diagnostics()
        val transport = Transport { call, _ -> if (call.model == "gemini-3.5-flash") GeminiHttpResponse(429) else response() }
        val session = engine("k", transport, diagnostics).newSession { "trace" }
        val first = session.generate("uno") as CloudResult.Success
        val second = session.generate("dos") as CloudResult.Success
        assertEquals("gemini-3.1-flash-lite", first.model)
        assertEquals("gemini-3.1-flash-lite", second.model)
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-3.1-flash-lite"), transport.calls.map(Call::model))
        assertEquals(setOf("gemini-3.1-flash-lite"), session.modelsUsed)
        assertEquals(listOf(429, 429, 200, 200), diagnostics.events.mapNotNull(ImportDiagnosticEvent::httpStatus))
        assertEquals("gemini-3.1-flash-lite", diagnostics.events.last { it.phase == "cloud_model_success" }.model)
    }

    @Test fun rateLimitedModelIsReportedAsFinalOnlyWhenItsRetrySucceeds() = runBlocking {
        val diagnostics = Diagnostics()
        val transport = Transport { _, call -> if (call == 1) GeminiHttpResponse(429) else response() }
        val result = engine("k", transport, diagnostics).newSession { "trace" }.generate("x") as CloudResult.Success

        assertEquals("gemini-3.5-flash", result.model)
        assertEquals(listOf(429, 200), diagnostics.events.mapNotNull(ImportDiagnosticEvent::httpStatus))
        assertEquals("gemini-3.5-flash", diagnostics.events.last { it.phase == "cloud_model_success" }.model)
    }

    @Test fun retryAfterIsCappedAtTwoSeconds() = runBlocking {
        val waits = mutableListOf<Long>()
        val transport = Transport { _, call -> if (call == 1) GeminiHttpResponse(429, retryAfterMillis = 30_000) else response() }
        val result = GeminiCloudEngine(Keys("k"), transport, sleeper = { waits += it }, jitterMillis = { 0 }).newSession().generate("x")
        assertTrue(result is CloudResult.Success)
        assertEquals(listOf(2_000L), waits)
    }

    @Test fun timeoutAndModelNotFoundAdvanceToNextModel() = runBlocking {
        var timeoutCalls = 0
        val timeout = object : GeminiTransport {
            override fun execute(model: String, endpoint: String, key: String, requestBody: String): GeminiHttpResponse {
                if (model == "gemini-3.5-flash") { timeoutCalls++; throw SocketTimeoutException() }
                return response()
            }
        }
        assertEquals("gemini-3.1-flash-lite", (engine("k", timeout).newSession().generate("x") as CloudResult.Success).model)
        assertEquals(2, timeoutCalls)

        val missing = Transport { call, _ -> if (call.model == "gemini-3.5-flash") GeminiHttpResponse(404) else response() }
        assertEquals("gemini-3.1-flash-lite", (engine("k", missing).newSession().generate("x") as CloudResult.Success).model)
    }

    @Test fun offlineAndOrdinaryClientErrorsDoNotFailOver() = runBlocking {
        val offline = object : GeminiTransport {
            var calls = 0
            override fun execute(model: String, endpoint: String, key: String, requestBody: String): GeminiHttpResponse { calls++; throw IOException("offline") }
        }
        assertEquals(CloudFailure.OFFLINE, (engine("k", offline).generate("x") as CloudResult.Failure).kind)
        assertEquals(1, offline.calls)
        val clientError = Transport { _, _ -> GeminiHttpResponse(422) }
        assertEquals(CloudFailure.SERVER, (engine("k", clientError).generate("x") as CloudResult.Failure).kind)
        assertEquals(1, clientError.calls.size)
    }

    @Test fun allModelsExhaustToTypedFailure() = runBlocking {
        val quota = Transport { _, _ -> GeminiHttpResponse(429) }
        assertEquals(CloudFailure.QUOTA, (engine("k", quota).newSession().generate("x") as CloudResult.Failure).kind)
        assertEquals(6, quota.calls.size)
        val server = Transport { _, _ -> GeminiHttpResponse(503) }
        assertEquals(CloudFailure.SERVER, (engine("k", server).newSession().generate("x") as CloudResult.Failure).kind)
    }

    @Test fun malformedAndOversizedResponsesAreInvalid() = runBlocking {
        val malformed = Transport { _, _ -> GeminiHttpResponse(200, "bad") }
        assertEquals(CloudFailure.INVALID_RESPONSE, (engine("k", malformed).generate("x") as CloudResult.Failure).kind)
        val oversized = Transport { _, _ -> GeminiHttpResponse(200, "x".repeat(1_000_001)) }
        assertEquals(CloudFailure.INVALID_RESPONSE, (engine("k", oversized).generate("x") as CloudResult.Failure).kind)
    }

    @Test fun unsupportedStructuredRequestFallsBackOnSameModel() = runBlocking {
        val transport = Transport { _, call -> if (call == 1) GeminiHttpResponse(400) else response() }
        val result = engine("k", transport).generate("x") as CloudResult.Success
        assertEquals("gemini-3.5-flash", result.model)
        assertEquals(2, transport.calls.size)
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.5-flash"), transport.calls.map(Call::model))
        val fallbackConfig = JsonParser.parseString(transport.calls.last().body).asJsonObject.getAsJsonObject("generationConfig")
        assertFalse(fallbackConfig.has("responseJsonSchema"))
    }

    private fun engine(
        key: String?,
        transport: GeminiTransport,
        diagnostics: ImportDiagnosticsRecorder = cr.micampus.app.data.diagnostics.NoOpImportDiagnostics,
    ) = GeminiCloudEngine(Keys(key), transport, sleeper = {}, jitterMillis = { 0 }, diagnostics = diagnostics)
    private fun response() = GeminiHttpResponse(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"events\\\":[]}\"}]}}]}")
}
