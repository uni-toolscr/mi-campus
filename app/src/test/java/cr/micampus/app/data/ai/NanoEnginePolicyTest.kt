package cr.micampus.app.data.ai

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NanoEnginePolicyTest {
    @Test fun busyRetriesUseTheRequiredBackoffSequence() = runBlocking {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val result = retryNanoBusy(
            isBusy = { it is BusyFailure },
            sleeper = { sleeps += it },
        ) {
            calls++
            if (calls < 4) throw BusyFailure()
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(4, calls)
        assertEquals(listOf(500L, 1_000L, 2_000L), sleeps)
    }

    @Test fun cancellationIsNeverRetriedOrConverted() = runBlocking {
        var busyChecks = 0
        val thrown = try {
            retryNanoBusy(isBusy = { busyChecks++; true }) { throw CancellationException("left foreground") }
            null
        } catch (error: CancellationException) {
            error
        }

        assertTrue(thrown is CancellationException)
        assertEquals(0, busyChecks)
    }

    @Test fun officialErrorCodesMapToActionableFailures() {
        assertEquals(NanoFailureKind.BUSY, nanoFailureForErrorCode(GenAiException.ErrorCode.BUSY))
        assertEquals(NanoFailureKind.BACKGROUND_BLOCKED, nanoFailureForErrorCode(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED))
        assertEquals(NanoFailureKind.BATTERY_QUOTA, nanoFailureForErrorCode(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED))
        assertEquals(NanoFailureKind.NOT_ENOUGH_STORAGE, nanoFailureForErrorCode(GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE))
        assertEquals(NanoFailureKind.SYSTEM_UPDATE_REQUIRED, nanoFailureForErrorCode(GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE))
        assertEquals(NanoFailureKind.AICORE_INCOMPATIBLE, nanoFailureForErrorCode(GenAiException.ErrorCode.AICORE_INCOMPATIBLE))
        assertEquals(NanoFailureKind.NOT_AVAILABLE, nanoFailureForErrorCode(GenAiException.ErrorCode.NOT_AVAILABLE))
        assertEquals(NanoFailureKind.REQUEST_REJECTED, nanoFailureForErrorCode(GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR))
        assertEquals(NanoFailureKind.RESPONSE_REJECTED, nanoFailureForErrorCode(GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR))
        assertEquals(NanoFailureKind.CANCELLED, nanoFailureForErrorCode(GenAiException.ErrorCode.CANCELLED))
        assertEquals(NanoFailureKind.UNKNOWN, nanoFailureForErrorCode(GenAiException.ErrorCode.UNKNOWN))
    }

    @Test fun successfulTinyProbeClassifiesTheFullRequestAsTooLarge() {
        assertEquals(UnexpectedTokenCountResolution.TOO_LARGE, resolveUnexpectedTokenCountFailure(tinyProbeSucceeded = true))
    }

    @Test fun failedTinyProbeClassifiesTheFailureAsSdkFailure() {
        assertEquals(UnexpectedTokenCountResolution.SDK_FAILURE, resolveUnexpectedTokenCountFailure(tinyProbeSucceeded = false))
    }

    @Test fun explicitRetryRecreatesTheModelClient() = runBlocking {
        var clients = 0
        val engine = MlKitNanoEngine(modelFactory = {
            clients++
            modelProxy { method -> if (method == "close") Unit else error("Unexpected model operation: $method") }
        })

        engine.resetForRetry()

        assertEquals(2, clients)
        engine.close()
    }

    // Pins the SDK output budget: genai-prompt 1.0.0-beta2 capped maxOutputTokens at 256 and threw
    // IllegalArgumentException for the production budget, breaking every local import; beta3 allows 4 096.
    @Test fun productionRequestBudgetIsAcceptedByTheSdkBuilder() {
        val request = com.google.mlkit.genai.prompt.generateContentRequest(
            com.google.mlkit.genai.prompt.TextPart("hola"),
        ) {
            candidateCount = 1
            maxOutputTokens = 4_096
            seed = 7
            temperature = 0f
        }
        assertEquals(4_096, request.maxOutputTokens)
    }

    @Test fun productionBudgetsFitTheNanoTokenWindow() {
        // Worst-case preflight input (3 999 < 4 000) plus the full output budget must fit tokenLimit 8 192.
        assertTrue(NanoPromptPreflight.fits(inputTokens = 3_999, maxOutputTokens = 4_096, tokenLimit = 8_192))
        assertTrue(!NanoPromptPreflight.fits(inputTokens = 4_000, maxOutputTokens = 4_096, tokenLimit = 8_192))
    }

    @Test fun exceptionCategoriesAreStrictlyAllowlisted() {
        assertEquals("IllegalArgumentException", diagnosticExceptionType(IllegalArgumentException("private")))
        assertEquals("IllegalStateException", diagnosticExceptionType(IllegalStateException("private")))
        assertEquals("SecurityException", diagnosticExceptionType(SecurityException("private")))
        assertEquals("IOException", diagnosticExceptionType(java.io.IOException("private")))
        assertEquals("OtherException", diagnosticExceptionType(UnsupportedOperationException("private")))
    }

    private class BusyFailure : Exception()

    private fun modelProxy(handler: (String) -> Any?): GenerativeModel = Proxy.newProxyInstance(
        GenerativeModel::class.java.classLoader,
        arrayOf(GenerativeModel::class.java),
    ) { _, method, _ -> handler(method.name) } as GenerativeModel

}
