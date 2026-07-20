package cr.micampus.app.feature.chat

import cr.micampus.app.data.ai.AiGenerationProfile
import cr.micampus.app.data.ai.CHAT_MAX_OUTPUT_TOKENS
import cr.micampus.app.data.ai.EVENT_MAX_OUTPUT_TOKENS
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.ai.LocalPromptEngine
import cr.micampus.app.data.ai.LocalGenerationResult
import cr.micampus.app.data.ai.NanoCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ChatAnswerEngineTest {
    private class Local(
        private val answers: ArrayDeque<LocalGenerationResult>,
        private val capability: NanoCapability = NanoCapability.AVAILABLE,
    ) : LocalPromptEngine {
        val prompts = mutableListOf<String>()
        val outputLimits = mutableListOf<Int>()
        override suspend fun checkCapability() = capability
        override suspend fun generate(prompt: String): LocalGenerationResult = answers.removeFirst().also { prompts += prompt }
        override suspend fun generate(prompt: String, maxOutputTokens: Int): LocalGenerationResult =
            answers.removeFirst().also { prompts += prompt; outputLimits += maxOutputTokens }
    }

    private class Cloud(private val response: Result<String> = Result.success("nube")) : ChatCloudEngine {
        var calls = 0
        var lastProfile: AiGenerationProfile? = null
        override suspend fun generate(prompt: String, profile: AiGenerationProfile) = response.also { calls++; lastProfile = profile }
    }

    @Test fun localSuccessDoesNotNeedConsentAndUsesChatBudget() = runBlocking {
        val local = Local(ArrayDeque(listOf(LocalGenerationResult.Success("local"))))
        val result = engine(local).generate("local", "retry", "cloud", AiGenerationProfile.CHAT_ANSWER, true, true)

        assertEquals(ChatAnswerEngine.Answer.Success("local", false), result)
        assertEquals(listOf(CHAT_MAX_OUTPUT_TOKENS), local.outputLimits)
    }

    @Test fun tooLargeRetriesSmallerThenAwaitsConsent() = runBlocking {
        val local = Local(ArrayDeque(listOf(LocalGenerationResult.TooLarge, LocalGenerationResult.TooLarge)))
        val result = engine(local).generate("large", "small", "cloud", AiGenerationProfile.CHAT_ANSWER, true, true)

        assertEquals(listOf("large", "small"), local.prompts)
        assertTrue(result is ChatAnswerEngine.Answer.AwaitingConsent)
    }

    @Test fun eventExtractionUsesTheSmallerStructuredOutputBudget() = runBlocking {
        val local = Local(ArrayDeque(listOf(LocalGenerationResult.Success("{}"))))

        engine(local).generate("event", "retry", "cloud", AiGenerationProfile.CHAT_EVENT, true, false)

        assertEquals(listOf(EVENT_MAX_OUTPUT_TOKENS), local.outputLimits)
    }

    @Test fun cloudRequiresFreshConsentAndPreservesTaskKind() = runBlocking {
        val cloud = Cloud()
        val engine = engine(Local(ArrayDeque(emptyList()), NanoCapability.UNAVAILABLE), cloud)
        val pending = engine.generate("local", "retry", "cloud", AiGenerationProfile.CHAT_EVENT, true, true)
            as ChatAnswerEngine.Answer.AwaitingConsent

        assertEquals(AiGenerationProfile.CHAT_EVENT, pending.profile)
        assertEquals(0, cloud.calls)
        assertEquals(
            ChatAnswerEngine.Answer.Error(ChatAnswerEngine.CONSENT_REQUIRED_MESSAGE),
            engine.generateFromCloud(pending.cloudPrompt, pending.profile, explicitConsent = false),
        )
        assertEquals(ChatAnswerEngine.Answer.Success("nube", true), engine.generateFromCloud(pending.cloudPrompt, pending.profile, true))
        assertEquals(AiGenerationProfile.CHAT_EVENT, cloud.lastProfile)
    }

    @Test fun disabledEnginesReturnSettingsMessage() = runBlocking {
        val result = engine(Local(ArrayDeque(emptyList())))
            .generate("local", "retry", "cloud", AiGenerationProfile.CHAT_ANSWER, false, false)
        assertEquals(ChatAnswerEngine.Answer.Error(ChatAnswerEngine.DISABLED_MESSAGE), result)
    }

    @Test fun hiddenInstitutionContextDoesNotModifyVisibleMessage() {
        val raw = "como me registro para x"
        val visible = ChatMessage(ChatMessageRole.USER, raw)
        val prompt = ChatPromptBuilder.answer(
            ChatRequestContext(
                rawMessage = raw,
                selectedInstitutions = linkedSetOf(Institution.UCR, Institution.UNA),
                currentDateTime = NOW,
                grounding = emptyList(),
                history = emptyList(),
            ),
        )

        assertEquals(raw, visible.text)
        assertTrue(prompt.contains("Estudiante de: UNA, UCR"))
        assertTrue(prompt.contains("FECHA=2026-07-18"))
        assertTrue(prompt.contains("DIA=sábado"))
        assertTrue(prompt.contains("HORA=12:34:56"))
        assertTrue(prompt.contains("ZONA=America/Costa_Rica"))
        assertTrue(prompt.endsWith(raw))
        assertFalse(prompt.contains("CONTRATO JSON"))
    }

    @Test fun missingInstitutionCoverageIsExplicitAndFutureCodesAreDeduplicated() {
        val prompt = ChatPromptBuilder.answer(
            ChatRequestContext(
                rawMessage = "¿Cómo me registro?",
                selectedInstitutions = setOf(Institution.UCR),
                currentDateTime = NOW,
                grounding = emptyList(),
                history = emptyList(),
            ),
        )

        assertTrue(prompt.contains("No hay una fuente institucional pertinente para: UCR"))
        assertEquals(
            "## CONTEXTO INSTITUCIONAL\nEstudiante de: UNA, UCR, TEC\n## MENSAJE\nhola",
            hiddenInstitutionMessage("hola", listOf("UNA", "TEC", "UCR", "TEC")),
        )
    }

    private fun engine(local: Local, cloud: Cloud = Cloud()) = ChatAnswerEngine(local, cloud) { true }

    companion object {
        private val NOW = ZonedDateTime.of(2026, 7, 18, 12, 34, 56, 0, COSTA_RICA_ZONE)
    }
}
