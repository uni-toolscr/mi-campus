package cr.micampus.app.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImporterConsentStateTest {
    @Test fun acceptingConsentImmediatelyShowsLoadingAndCannotTransitionTwice() {
        val waiting = ImporterUiState(stage = ImportStage.NEEDS_CONSENT)
        val loading = requireNotNull(waiting.beginCloudDecision(granted = true))

        assertEquals(ImportStage.EXTRACTING, loading.stage)
        assertEquals("Procesando con Gemini…", loading.message)
        assertNull(loading.beginCloudDecision(granted = true))
    }

    @Test fun decliningConsentImmediatelyShowsProgressAndCannotTransitionTwice() {
        val waiting = ImporterUiState(stage = ImportStage.NEEDS_CONSENT)
        val loading = requireNotNull(waiting.beginCloudDecision(granted = false))

        assertEquals(ImportStage.EXTRACTING, loading.stage)
        assertEquals("Continuando sin nube…", loading.message)
        assertNull(loading.beginCloudDecision(granted = false))
    }

    @Test fun aLaterBatchCanRequestConsentAgain() {
        val nextBatch = ImporterUiState(stage = ImportStage.NEEDS_CONSENT, importId = "next")
        assertNotNull(nextBatch.beginCloudDecision(granted = true))
    }
}
