package cr.micampus.app.data.ai

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.MainActivity
import cr.micampus.app.data.diagnostics.DiagnosticsExport
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in smoke test for any device exposing the ML Kit Prompt API. It never starts a model download.
 *
 * Run with:
 * `-Pandroid.testInstrumentationRunnerArguments.runNanoDeviceTest=true`
 */
@RunWith(AndroidJUnit4::class)
class MlKitNanoEngineDeviceTest {
    @Test
    fun compatibleDeviceReportsRuntimeModelAndGeneratesOnlyWhileActivityIsResumed() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in device test", arguments.getString("runNanoDeviceTest") == "true")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val diagnostics = RecordingDiagnostics()
            val engine = MlKitNanoEngine(diagnostics = diagnostics)
            engine.setDiagnosticTrace("device-self-test")
            try {
                val capability = runBlocking { engine.checkCapability() }
                assumeTrue("Prompt API is unavailable on this device", capability != NanoCapability.UNAVAILABLE)

                if (capability == NanoCapability.AVAILABLE) {
                    val result = runBlocking { engine.selfTest() }
                    assertTrue("Foreground Nano self-test failed: $result", result is NanoSelfTestResult.Success)
                    result as NanoSelfTestResult.Success
                    assertTrue("AICore must report a non-empty runtime model identifier", !result.modelName.isNullOrBlank())
                    assertTrue("The runtime token limit must be positive", result.tokenLimit > 0)
                    assertTrue("The tiny request must have a positive token count", result.inputTokens > 0)
                    val operations = diagnostics.events
                        .filter { it.phase == "nano_operation" && it.outcome == "started" }
                        .mapNotNull(ImportDiagnosticEvent::operation)
                    assertOrdered(
                        operations,
                        "self_test_get_token_limit",
                        "self_test_count_tokens",
                        "self_test_generate_content",
                        "self_test_process_response",
                    )
                } else {
                    assertTrue(capability == NanoCapability.DOWNLOADABLE || capability == NanoCapability.DOWNLOADING)
                }
            } finally {
                engine.close()
            }
        }
    }

    private fun assertOrdered(actual: List<String>, vararg expected: String) {
        var previous = -1
        expected.forEach { operation ->
            val index = actual.indexOf(operation)
            assertTrue("Missing or out-of-order operation $operation in $actual", index > previous)
            previous = index
        }
    }

    private class RecordingDiagnostics : ImportDiagnosticsRecorder {
        val events = mutableListOf<ImportDiagnosticEvent>()
        override val available = true
        override suspend fun beginAttempt(localEnabled: Boolean, cloudEnabled: Boolean) = "device-self-test"
        override suspend fun record(traceId: String?, event: ImportDiagnosticEvent) { events += event }
        override suspend fun finish(traceId: String?, outcome: String, backend: String?, model: String?, draftCount: Int) = Unit
        override suspend fun export(): DiagnosticsExport? = null
        override suspend fun clear() = Unit
    }
}
