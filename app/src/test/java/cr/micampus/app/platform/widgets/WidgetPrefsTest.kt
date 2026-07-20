package cr.micampus.app.platform.widgets

import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.institution.directionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPrefsTest {
    @Test fun blankAndNullMeanAllDirections() {
        assertTrue(parseDirectionFilter(null).isEmpty())
        assertTrue(parseDirectionFilter("").isEmpty())
        assertTrue(parseDirectionFilter("  ").isEmpty())
    }

    @Test fun parsesCompositeKeysAndTrimsBlanks() {
        val ucr = directionKey(Institution.UCR, "a")
        val una = directionKey(Institution.UNA, "b")
        assertEquals(setOf(ucr, una), parseDirectionFilter("$ucr, $una"))
        assertEquals(setOf(ucr), parseDirectionFilter("$ucr,,"))
    }

    @Test fun compositeKeyIsInstitutionScoped() {
        assertEquals("UCR|a", directionKey(Institution.UCR, "a"))
        assertTrue(directionKey(Institution.UCR, "a") != directionKey(Institution.UNA, "a"))
    }
}
