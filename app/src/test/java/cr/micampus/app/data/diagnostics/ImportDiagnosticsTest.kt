package cr.micampus.app.data.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ImportDiagnosticsTest {
    @Test fun retentionKeepsTheLatestTenCompletedAttempts() {
        val buffer = DiagnosticBuffer(maxTraces = 10)
        repeat(12) { index ->
            val id = "trace-$index"
            buffer.begin(id, index.toLong(), localEnabled = true, cloudEnabled = false)
            buffer.complete(id, ImportDiagnosticEvent(timestampEpoch = index + 1L, phase = "attempt_finished", outcome = "completed"))
        }

        val traces = buffer.snapshot()
        assertEquals(10, traces.size)
        assertEquals((2..11).map { "trace-$it" }.toSet(), traces.map(DiagnosticTrace::traceId).toSet())
    }

    @Test fun eventSchemaCannotContainDocumentContentOrSecrets() {
        val json = Gson().toJson(
            ImportDiagnosticEvent(
                phase = "nano_operation",
                operation = "count_tokens",
                backend = "local",
                inputCharacters = 8_000,
                inputWords = 1_700,
                exceptionType = "IllegalStateException",
                genAiErrorCode = 14,
            ),
        )

        val keys = JsonParser.parseString(json).asJsonObject.keySet().map(String::lowercase).toSet()
        for (forbidden in listOf("prompt", "response", "apiKey", "documentName", "path", "sha256", "evidence", "exceptionMessage", "stackTrace")) {
            assertFalse(forbidden, forbidden.lowercase() in keys)
        }
        assertTrue(json.contains("count_tokens"))
        assertTrue(json.contains("IllegalStateException"))
        assertTrue(json.contains("8000"))
    }

    @Test fun eventLimitPreservesAttemptSettingsAndNewestEvents() {
        val buffer = DiagnosticBuffer(maxEventsPerTrace = 5)
        buffer.begin("trace", 1, localEnabled = true, cloudEnabled = false)
        repeat(10) { buffer.append("trace", ImportDiagnosticEvent(timestampEpoch = it + 2L, phase = "event-$it")) }

        val events = buffer.snapshot().single().events
        assertEquals(5, events.size)
        assertEquals("attempt_started", events.first().phase)
        assertEquals("event-9", events.last().phase)
        assertEquals(true, events.first().localEnabled)
    }

    @Test fun diagnosticCategoriesAndOperationsAreSanitizedBeforePersistence() {
        val event = ImportDiagnosticEvent(
            phase = "nano_operation",
            operation = "document text must not survive",
            exceptionType = "PrivateException: secret message",
            inputCharacters = -12,
            inputWords = -2,
        ).sanitized()

        assertEquals("other", event.operation)
        assertEquals("OtherException", event.exceptionType)
        assertEquals(0, event.inputCharacters)
        assertEquals(0, event.inputWords)
    }

    @Test fun archiveContainsSummaryEventsAndPrivacyNotice() {
        val file = File.createTempFile("micampus-diagnostics", ".zip")
        try {
            writeDiagnosticsArchive(file, "summary", "{\"phase\":\"test\"}\n", "privacy")
            ZipFile(file).use { zip ->
                assertEquals(setOf("summary.txt", "events.jsonl", "privacy.txt"), zip.entries().asSequence().map { it.name }.toSet())
                assertEquals("summary", zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText())
                assertTrue(zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().readText().contains("test"))
            }
        } finally {
            file.delete()
        }
    }
}
