package cr.micampus.app.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticModelLabelTest {
    @Test fun localAndCloudModelsHaveExplicitBackendLabels() {
        assertEquals("Local: Gemini Nano", diagnosticModelLabel("gemini-nano"))
        assertEquals("Local: Gemini Nano · runtime-model-test", diagnosticModelLabel("gemini-nano/runtime-model-test"))
        assertEquals("Nube: gemini-3.5-flash", diagnosticModelLabel("gemini-3.5-flash"))
    }
}
