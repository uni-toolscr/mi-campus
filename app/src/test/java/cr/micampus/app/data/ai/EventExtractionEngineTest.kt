package cr.micampus.app.data.ai

import cr.micampus.app.core.model.ImportIssue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventExtractionEngineTest {
    private class Local(
        private val state: NanoCapability,
        private val response: (String) -> LocalGenerationResult = {
            LocalGenerationResult.Success("{\"events\":[{\"title\":\"Clase\"}]}")
        },
    ) : LocalPromptEngine {
        var checks = 0
        var calls = 0
        val prompts = mutableListOf<String>()
        val outputLimits = mutableListOf<Int>()
        override suspend fun checkCapability() = state.also { checks++ }
        override suspend fun generate(prompt: String) = generate(prompt, IMPORT_MAX_OUTPUT_TOKENS)
        override suspend fun generate(prompt: String, maxOutputTokens: Int) = response(prompt).also {
            calls++
            prompts += prompt
            outputLimits += maxOutputTokens
        }
    }

    private class Consent(private val state: ConsentDecision) : CloudConsent {
        override fun decisionForImport(importId: String) = state
    }

    private class Cloud(private val response: (String) -> CloudResult) : CloudEventEngine {
        var calls = 0
        constructor(json: String) : this({ CloudResult.Success(json) })
        override suspend fun generate(prompt: String) = response(prompt).also { calls++ }
    }

    @Test fun nanoAvailableIsCheckedAndUsed() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE)
        val result = EventExtractionEngine(local = local).extract("x", listOf("a"))
        assertEquals(1, local.checks)
        assertTrue(result is ExtractionOutcome.Drafts)
    }

    @Test fun downloadableReturnsAction() = runBlocking {
        assertTrue(EventExtractionEngine(local = Local(NanoCapability.DOWNLOADABLE)).extract("x", listOf("a")) is ExtractionOutcome.NeedsDownload)
    }

    @Test fun downloadingAndUnavailableUseFallback() = runBlocking {
        assertTrue(EventExtractionEngine(local = Local(NanoCapability.DOWNLOADING)).extract("x", listOf("a")) is ExtractionOutcome.NeedsDownload)
        assertTrue(EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE)).extract("x", listOf("a")) is ExtractionOutcome.Manual)
    }

    @Test fun localFailureOffersExplicitRecovery() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { LocalGenerationResult.Failure }
        assertTrue(EventExtractionEngine(local = local).extract("x", listOf("a")) is ExtractionOutcome.LocalFailed)
    }

    @Test fun localFailureRequestsCloudConsentBeforeAnyCloudCall() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { LocalGenerationResult.Failed(NanoFailureKind.RESPONSE_REJECTED) }
        val cloud = Cloud("{\"events\":[{\"title\":\"Nube\"}]}")
        val result = EventExtractionEngine(local = local, cloud = cloud, consent = Consent(ConsentDecision.PENDING))
            .extract("batch", listOf("a"))

        assertTrue(result is ExtractionOutcome.NeedsConsent)
        assertEquals(0, cloud.calls)
    }

    @Test fun localValidEmptyResultIsSavedAsSourceWithoutCloudEscalation() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { LocalGenerationResult.Success(EMPTY_CONTRACT) }
        val cloud = Cloud("{\"events\":[{\"title\":\"Nube\"}]}")
        val result = EventExtractionEngine(local = local, cloud = cloud, consent = Consent(ConsentDecision.PENDING))
            .extract("batch", listOf("a"))

        assertTrue(result is ExtractionOutcome.NoEvents)
        assertEquals(0, cloud.calls) // no escalation and no consent prompt for a valid empty document
    }

    @Test fun cloudValidEmptyResultIsSavedAsSource() = runBlocking {
        val cloud = Cloud(EMPTY_CONTRACT)
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("batch", listOf("a"))

        assertTrue(result is ExtractionOutcome.NoEvents)
    }

    @Test fun malformedLocalOutputStillFails() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { LocalGenerationResult.Success("esto no es json") }
        assertTrue(EventExtractionEngine(local = local).extract("x", listOf("a")) is ExtractionOutcome.LocalFailed)
    }

    @Test fun malformedCloudOutputStillReturnsManual() = runBlocking {
        val cloud = Cloud("esto no es json")
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("a"))
        assertTrue(result is ExtractionOutcome.Manual)
    }

    @Test fun oversizedLocalPromptIsSplitAndProcessed() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { prompt ->
            if (wordCount(prompt) > 800) LocalGenerationResult.TooLarge
            else LocalGenerationResult.Success("{\"events\":[{\"title\":\"Parte\"}]}")
        }
        val prompt = "[Página 1] " + (1..600).joinToString(" ") { "w$it" }
        val result = EventExtractionEngine(local = local).extract("x", listOf(prompt))

        assertTrue(result is ExtractionOutcome.Drafts)
        assertTrue(local.calls > 1)
    }

    @Test fun localSplitReportsExpandedProgressUntilComplete() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { prompt ->
            if (wordCount(prompt) > 800) LocalGenerationResult.TooLarge
            else LocalGenerationResult.Success("{\"events\":[{\"title\":\"Parte\"}]}")
        }
        val prompt = "[Página 1] " + (1..600).joinToString(" ") { "w$it" }
        val progress = mutableListOf<Pair<Int, Int>>()

        EventExtractionEngine(local = local).extract("x", listOf(prompt)) { done, total ->
            progress += done to total
        }

        assertTrue(progress.contains(0 to 2))
        assertEquals(2 to 2, progress.last())
    }

    @Test fun irreduciblyOversizedLocalPromptOffersRecovery() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { LocalGenerationResult.TooLarge }
        val result = EventExtractionEngine(local = local).extract("x", listOf("[Página 1] texto corto"))
        assertTrue(result is ExtractionOutcome.LocalFailed)
    }

    @Test fun former3800WordPromptDoesNotReturnManualLimitError() = runBlocking {
        val prompt = (1..3_800).joinToString(" ") { "w$it" }
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE)).extract("x", listOf(prompt))
        assertTrue(result is ExtractionOutcome.Manual)
    }

    @Test fun partialLocalFailureDiscardsLocalOutputAndUsesAllCloudChunks() = runBlocking {
        var localCall = 0
        val local = Local(NanoCapability.AVAILABLE) {
            localCall++
            if (localCall == 1) LocalGenerationResult.Success("{\"events\":[{\"title\":\"Solo local\"}]}")
            else LocalGenerationResult.Failure
        }
        val cloud = Cloud("{\"events\":[{\"title\":\"Nube\"}]}")
        val result = EventExtractionEngine(local = local, cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("primero", "segundo")) as ExtractionOutcome.Drafts

        assertEquals(2, cloud.calls)
        assertTrue(result.drafts.all { it.title == "Nube" })
    }

    @Test fun pendingAndDeniedNeverCallCloud() = runBlocking {
        val cloud = Cloud("{}")
        val pending = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.PENDING)).extract("x", listOf("a"))
        assertTrue(pending is ExtractionOutcome.NeedsConsent)
        val denied = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.DENIED)).extract("x", listOf("a"))
        assertTrue(denied is ExtractionOutcome.Manual)
        assertEquals(0, cloud.calls)
    }

    @Test fun grantedCloudCollapsesIdenticalChunkExtractions() = runBlocking {
        // TokenChunker overlaps chunks, so the same event is routinely re-extracted from adjacent
        // chunks. Identical extractions (same stable id) collapse to a single draft instead of
        // surfacing as two "possible duplicate" copies.
        val cloud = Cloud("{\"events\":[{\"title\":\"Clase\"}]}")
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED)).extract("x", listOf("a", "b"))
        assertTrue(result is ExtractionOutcome.Drafts)
        assertEquals(2, cloud.calls)
        assertEquals(1, (result as ExtractionOutcome.Drafts).drafts.size)
    }

    @Test fun cloudModelsUsedReachExtractionDiagnostics() = runBlocking {
        val cloud = Cloud { CloudResult.Success("{\"events\":[{\"title\":\"Clase\"}]}", "gemini-3.1-flash-lite") }
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("a")) as ExtractionOutcome.Drafts
        assertEquals(setOf("gemini-3.1-flash-lite"), result.modelsUsed)
    }

    @Test fun successfulLocalExtractionNeverCallsCloud() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) {
            LocalGenerationResult.Success("{\"events\":[{\"title\":\"Local\"}]}", "runtime-model-a")
        }
        val cloud = Cloud("{\"events\":[{\"title\":\"Nube\"}]}")
        val result = EventExtractionEngine(local = local, cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("a")) as ExtractionOutcome.Drafts

        assertEquals(0, cloud.calls)
        assertEquals("Local", result.drafts.single().title)
    }

    @Test fun generatedDraftMatchingExistingEventIsFlagged() = runBlocking {
        val cloud = Cloud("{\"events\":[{\"title\":\"Clase\",\"date\":\"2026-08-03\",\"start\":\"08:00\"}]}")
        val existing = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Clase\",\"date\":\"2026-08-03\",\"start\":\"08:00\"}]}")
        val result = EventExtractionEngine(
            local = Local(NanoCapability.UNAVAILABLE),
            cloud = cloud,
            consent = Consent(ConsentDecision.GRANTED),
        ).extract("x", listOf("a"), existing)

        assertTrue(ImportIssue.DUPLICATE in (result as ExtractionOutcome.Drafts).drafts.single().issues)
    }

    @Test fun syllabusPiecesMergeAcrossChunks() = runBlocking {
        val chunk1 = "{\"course\":{\"code\":\"EIF200\",\"name\":\"Fundamentos\",\"institution\":\"UNA\"},\"groups\":[{\"label\":\"01\",\"days\":[\"LUNES\",\"JUEVES\"],\"start\":\"08:00\",\"end\":\"09:40\"}],\"events\":[]}"
        val chunk2 = "{\"weeks\":[{\"week\":1,\"from\":\"2025-02-17\",\"to\":\"2025-02-23\",\"topic\":\"Introducción\"}],\"holidays\":[{\"date\":\"2025-04-11\"}],\"events\":[{\"title\":\"Prueba de ejecución 1\",\"date\":\"2025-04-06\",\"start\":\"09:00\",\"category\":\"EXAM\"}]}"
        var call = 0
        val cloud = Cloud { CloudResult.Success(if (call++ == 0) chunk1 else chunk2) }
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("a", "b")) as ExtractionOutcome.Drafts

        val syllabus = requireNotNull(result.syllabus)
        assertEquals("EIF200", syllabus.course?.code)
        assertEquals(1, syllabus.groups.size)
        assertEquals(1, syllabus.weeks.size)
        assertTrue(syllabus.canExpandClasses)
        assertEquals("Prueba de ejecución 1", result.drafts.single().title)
    }

    @Test fun assumedInstitutionOverridesAiOutputAndReachesPrompt() = runBlocking {
        val prompts = mutableListOf<String>()
        val cloud = Cloud { prompt ->
            prompts += prompt
            CloudResult.Success("{\"course\":{\"institution\":\"UCR\"},\"events\":[{\"title\":\"Examen\",\"institution\":\"UCR\"}]}")
        }
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = cloud, consent = Consent(ConsentDecision.GRANTED))
            .extract("x", listOf("a"), assumedInstitution = cr.micampus.app.core.model.Institution.UNA) as ExtractionOutcome.Drafts

        assertEquals(cr.micampus.app.core.model.Institution.UNA, result.drafts.single().institution)
        assertEquals(cr.micampus.app.core.model.Institution.UNA, result.syllabus?.institution)
        assertTrue(prompts.single().contains("UNA"))
    }

    @Test fun syllabusWithoutDatedEventsStillReturnsDrafts() = runBlocking {
        val json = "{\"groups\":[{\"label\":\"01\",\"days\":[\"LUNES\"],\"start\":\"08:00\",\"end\":\"09:40\"}],\"weeks\":[{\"week\":1,\"from\":\"2025-02-17\",\"to\":\"2025-02-23\",\"topic\":\"Intro\"}],\"events\":[]}"
        val result = EventExtractionEngine(local = Local(NanoCapability.UNAVAILABLE), cloud = Cloud(json), consent = Consent(ConsentDecision.GRANTED)).extract("x", listOf("a"))
        assertTrue(result is ExtractionOutcome.Drafts)
        assertTrue((result as ExtractionOutcome.Drafts).syllabus?.canExpandClasses == true)
    }

    @Test fun nanoPreflightReservesOutputTokens() {
        assertTrue(NanoPromptPreflight.fits(inputTokens = 2_900, maxOutputTokens = 1_024, tokenLimit = 4_000))
        assertTrue(!NanoPromptPreflight.fits(inputTokens = 3_100, maxOutputTokens = 1_024, tokenLimit = 4_000))
        assertTrue(!NanoPromptPreflight.fits(inputTokens = 4_000, maxOutputTokens = 1, tokenLimit = 8_192))
    }

    @Test fun localModelNameReachesExtractionDiagnostics() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) {
            LocalGenerationResult.Success("{\"events\":[{\"title\":\"Clase\"}]}", "runtime-model-b")
        }
        val result = EventExtractionEngine(local = local).extract("x", listOf("a")) as ExtractionOutcome.Drafts
        assertEquals(setOf("gemini-nano/runtime-model-b"), result.modelsUsed)
    }

    @Test fun cloudDisabledNeverRequestsConsent() = runBlocking {
        val result = EventExtractionEngine(local = null, cloud = null, consent = Consent(ConsentDecision.PENDING))
            .extract("x", listOf("a"))
        assertTrue(result is ExtractionOutcome.Manual)
    }

    @Test fun splitterCarriesPageMarkerIntoContinuation() {
        val prompt = "[Página 9] " + (1..300).joinToString(" ") { "w$it" }
        val parts = requireNotNull(LocalPromptSplitter.split(prompt, overlapWords = 20, minimumWords = 40))
        assertTrue(parts.all { it.startsWith("[Página 9]") })
        assertTrue(parts.all { it.length < prompt.length })
    }

    @Test fun everyLocalSplitReappliesSyllabusContractWithTaskOutputLimit() = runBlocking {
        val local = Local(NanoCapability.AVAILABLE) { prompt ->
            if (wordCount(prompt) > 800) LocalGenerationResult.TooLarge
            else LocalGenerationResult.Success("{\"events\":[{\"title\":\"Parte\"}]}")
        }
        val document = "[Página 4] " + (1..600).joinToString(" ") { "w$it" }

        val result = EventExtractionEngine(local = local).extract("import", listOf(document))

        assertTrue(result is ExtractionOutcome.Drafts)
        assertTrue(local.prompts.size > 1)
        assertTrue(local.prompts.all { it.startsWith(SyllabusPrompt.SHAPE) && it.contains(SyllabusPrompt.RULES) })
        assertTrue(local.prompts.all { it.contains("[Página 4]") })
        assertEquals(List(local.prompts.size) { IMPORT_MAX_OUTPUT_TOKENS }, local.outputLimits)
    }

    private fun wordCount(value: String) = value.split(Regex("\\s+")).count(String::isNotBlank)

    private companion object {
        const val EMPTY_CONTRACT = "{\"course\":null,\"groups\":[],\"weeks\":[],\"holidays\":[],\"events\":[]}"
    }
}
