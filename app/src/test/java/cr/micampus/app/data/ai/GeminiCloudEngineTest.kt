package cr.micampus.app.data.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeminiCloudEngineTest {
    private class Keys(private val value: String?) : ApiKeyProvider { override fun read() = value }
    private class Transport(private val response: GeminiHttpResponse) : GeminiTransport { var calls = 0; override fun execute(key: String, requestBody: String): GeminiHttpResponse { calls++; return response } }
    private class Offline : GeminiTransport { override fun execute(key: String, requestBody: String): GeminiHttpResponse = throw java.io.IOException("offline") }
    @Test fun requestUsesStableModelAndHeader() { val client = GeminiClient("secret"); assertTrue(client.endpoint.contains("gemini-3.5-flash")); assertEquals("x-goog-api-key", client.authorizationHeader().first) }
    @Test fun requestPrefersJsonSchemaStructuredOutput() {
        val body = JsonParser.parseString(GeminiClient("secret").requestBody("texto")).asJsonObject
        val config = body.getAsJsonObject("generationConfig")
        assertEquals("application/json", config.get("responseMimeType").asString)
        assertEquals("object", config.getAsJsonObject("responseJsonSchema").get("type").asString)
        assertEquals("LOW", config.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
    }
    @Test fun structuredSchemaCoversSyllabusSections() {
        val body = JsonParser.parseString(GeminiClient("secret").requestBody("texto")).asJsonObject
        val schema = body.getAsJsonObject("generationConfig").getAsJsonObject("responseJsonSchema")
        val properties = schema.getAsJsonObject("properties")
        for (section in listOf("course", "groups", "weeks", "holidays", "events")) assertTrue(section, properties.has(section))
        val prompt = body.getAsJsonArray("contents").first().asJsonObject.getAsJsonArray("parts").first().asJsonObject.get("text").asString
        assertTrue(prompt.contains("cronograma"))
        assertTrue(prompt.contains("QUIZ"))
        assertTrue(prompt.contains("Nunca inventes valores"))
    }
    @Test fun missingKeyIsTyped() = runBlocking { assertEquals(CloudFailure.MISSING_KEY, (GeminiCloudEngine(Keys(null), Transport(GeminiHttpResponse(200))).generate("x") as CloudResult.Failure).kind) }
    @Test fun invalidQuotaAndServerAreTyped() = runBlocking { assertEquals(CloudFailure.INVALID_KEY, (GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(401))).generate("x") as CloudResult.Failure).kind); assertEquals(CloudFailure.QUOTA, (GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(429))).generate("x") as CloudResult.Failure).kind); assertEquals(CloudFailure.SERVER, (GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(503))).generate("x") as CloudResult.Failure).kind) }
    @Test fun apiKeyInvalidBodyIsTypedWithoutSchemaRetry() = runBlocking {
        val transport = Transport(GeminiHttpResponse(400, "API_KEY_INVALID"))
        assertEquals(CloudFailure.INVALID_KEY, (GeminiCloudEngine(Keys("k"), transport).generate("x") as CloudResult.Failure).kind)
        assertEquals(1, transport.calls)
    }
    @Test fun malformedAndOversizedResponsesAreInvalid() = runBlocking { val malformed = GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(200, "bad"))).generate("x") as CloudResult.Failure; assertEquals(CloudFailure.INVALID_RESPONSE, malformed.kind); val oversized = GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(200, "x".repeat(1_000_001)))).generate("x") as CloudResult.Failure; assertEquals(CloudFailure.INVALID_RESPONSE, oversized.kind) }
    @Test fun successReturnsStructuredText() = runBlocking { val body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"events\\\":[]}\"}]}}]}"; assertTrue(GeminiCloudEngine(Keys("k"), Transport(GeminiHttpResponse(200, body))).generate("x") is CloudResult.Success) }
    @Test fun ioExceptionMapsOffline() = runBlocking { assertEquals(CloudFailure.OFFLINE, (GeminiCloudEngine(Keys("k"), Offline()).generate("x") as CloudResult.Failure).kind) }

    @Test fun unsupportedStructuredRequestFallsBackToStrictJson() = runBlocking {
        val success = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"events\\\":[]}\"}]}}]}"
        val bodies = mutableListOf<String>()
        val transport = object : GeminiTransport {
            override fun execute(key: String, requestBody: String): GeminiHttpResponse {
                bodies += requestBody
                return if (bodies.size == 1) GeminiHttpResponse(400) else GeminiHttpResponse(200, success)
            }
        }

        assertTrue(GeminiCloudEngine(Keys("k"), transport).generate("x") is CloudResult.Success)
        assertEquals(2, bodies.size)
        val fallbackConfig = JsonParser.parseString(bodies.last()).asJsonObject.getAsJsonObject("generationConfig")
        assertFalse(fallbackConfig.has("responseJsonSchema"))
        assertEquals("LOW", fallbackConfig.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
    }
}
