package cr.micampus.app.data.local

import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaSettingsTest {
    @Test fun institutionsExposeStableShortNamesForModelRequests() {
        assertEquals("UCR", Institution.UCR.llmShortName)
        assertEquals("UNA", Institution.UNA.llmShortName)
    }

    @Test fun selectedInstitutionsFollowTheStoredChoicesInEnumOrder() {
        assertEquals(listOf(Institution.UCR, Institution.UNA), AppSettings().selectedInstitutions())
        assertEquals(listOf(Institution.UNA), AppSettings(ucrEnabled = false).selectedInstitutions())
        assertEquals(listOf(Institution.UCR), AppSettings(unaEnabled = false).selectedInstitutions())
    }

    @Test fun selectedInstitutionsDoesNotGuessWhenNothingIsEnabled() {
        assertTrue(AppSettings(ucrEnabled = false, unaEnabled = false).selectedInstitutions().isEmpty())
    }

    @Test fun missingPreferencesUseTheAgendaDefaults() {
        assertEquals(setOf(EventKind.CLASS), parseAgendaExcludedKinds(null))
        assertTrue(parseAgendaExcludedInstitutions(null).isEmpty())
    }

    @Test fun malformedEnumNamesAreIgnoredAndEmptySelectionsRemainEmpty() {
        assertEquals(setOf(EventKind.EXAM), parseAgendaExcludedKinds(setOf("EXAM", "UNKNOWN")))
        assertEquals(setOf(Institution.UNA), parseAgendaExcludedInstitutions(setOf("UNA", "OTHER")))
        assertTrue(parseAgendaExcludedKinds(emptySet()).isEmpty())
    }
}
