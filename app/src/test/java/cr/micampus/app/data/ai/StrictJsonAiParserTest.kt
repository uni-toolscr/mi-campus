package cr.micampus.app.data.ai

import cr.micampus.app.core.model.ImportIssue
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StrictJsonAiParserTest {
    @Test fun nullableFieldsAreIssues() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Clase\",\"date\":null,\"start\":null}]}").single()
        assertTrue(ImportIssue.MISSING_DATE in draft.issues); assertTrue(ImportIssue.MISSING_TIME in draft.issues)
    }
    @Test fun invalidRangeAndPastAreFlagged() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"X\",\"date\":\"2020-01-01\",\"start\":\"10:00\",\"end\":\"09:00\"}]}", LocalDate.of(2026, 1, 1)).single()
        assertTrue(ImportIssue.PAST in draft.issues); assertTrue(ImportIssue.INVALID_RANGE in draft.issues)
    }
    @Test fun fullContractAndWrongTypesAreSafe() {
        val draft = StrictJsonAiParser().parseDrafts("{\"events\":[{\"title\":\"Clase\",\"date\":\"2026-08-01\",\"originalDateText\":\"viernes 1\",\"start\":\"08:00\",\"end\":\"09:00\",\"location\":null,\"courseHint\":\"MAT-1\",\"sourcePage\":2,\"evidence\":\"página\"}]}", LocalDate.of(2026, 1, 1)).single()
        assertEquals("viernes 1", draft.originalDateText); assertEquals(2, draft.sourcePage); assertEquals("MAT-1", draft.course?.code); assertNull(draft.location)
    }
    @Test fun malformedJsonReturnsActionableInvalidResult() { assertTrue(StrictJsonAiParser().parseStrictJson("not-json").warnings.isNotEmpty()) }
}
