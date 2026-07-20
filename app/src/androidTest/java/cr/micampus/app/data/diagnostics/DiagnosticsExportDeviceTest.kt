package cr.micampus.app.data.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticsExportDeviceTest {
    @Test fun exportUsesFileProviderAndContainsOnlyExpectedEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = FileImportDiagnostics(context)
        diagnostics.clear()
        try {
            val trace = diagnostics.beginAttempt(localEnabled = true, cloudEnabled = false)
            diagnostics.record(trace, ImportDiagnosticEvent(phase = "nano_capability", backend = "local", capability = "available"))
            diagnostics.finish(trace, outcome = "completed", backend = "local", model = "gemini-nano/runtime-model-test", draftCount = 1)

            val export = diagnostics.export()
            assertNotNull(export)
            val entries = mutableSetOf<String>()
            context.contentResolver.openInputStream(requireNotNull(export).uri).use { input ->
                ZipInputStream(requireNotNull(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries += entry.name
                    }
                }
            }
            assertEquals(setOf("summary.txt", "events.jsonl", "privacy.txt"), entries)
        } finally {
            diagnostics.clear()
        }
    }
}
