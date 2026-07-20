package cr.micampus.app.feature.settings

import cr.micampus.app.data.ai.LocalPromptEngine
import cr.micampus.app.data.ai.LocalGenerationResult
import cr.micampus.app.data.ai.NanoCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiAvailabilityTest {
    @Test fun supportedAndDownloadStatesEnableTheSwitch() {
        assertTrue(LocalAiAvailability.Available("runtime-model-one").isCompatible)
        assertTrue(LocalAiAvailability.Downloadable.isCompatible)
        assertTrue(LocalAiAvailability.Downloading.isCompatible)
    }

    @Test fun unresolvedAndUnavailableStatesDisableTheSwitch() {
        assertFalse(LocalAiAvailability.Checking.isCompatible)
        assertFalse(LocalAiAvailability.Unavailable.isCompatible)
        assertFalse(LocalAiAvailability.CheckFailed.isCompatible)
    }

    @Test fun capabilityRefreshMapsEveryEngineState() = runBlocking {
        assertEquals(LocalAiAvailability.Available("runtime-model-two"), readLocalAiAvailability(FakeNano(NanoCapability.AVAILABLE, "runtime-model-two")))
        assertEquals(LocalAiAvailability.Downloadable, readLocalAiAvailability(FakeNano(NanoCapability.DOWNLOADABLE)))
        assertEquals(LocalAiAvailability.Downloading, readLocalAiAvailability(FakeNano(NanoCapability.DOWNLOADING)))
        assertEquals(LocalAiAvailability.Unavailable, readLocalAiAvailability(FakeNano(NanoCapability.UNAVAILABLE)))
    }

    @Test fun failedCapabilityRefreshHasDedicatedState() = runBlocking {
        val failing = object : LocalPromptEngine {
            override suspend fun checkCapability(): NanoCapability = error("AICore initialization failed")
            override suspend fun generate(prompt: String) = LocalGenerationResult.Failure
        }
        assertEquals(LocalAiAvailability.CheckFailed, readLocalAiAvailability(failing))
    }

    private class FakeNano(
        private val capability: NanoCapability,
        private val modelName: String? = null,
    ) : LocalPromptEngine {
        override suspend fun checkCapability() = capability
        override suspend fun baseModelName() = modelName
        override suspend fun generate(prompt: String) = LocalGenerationResult.Failure
    }
}
