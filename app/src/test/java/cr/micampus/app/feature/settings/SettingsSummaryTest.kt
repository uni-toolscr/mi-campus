package cr.micampus.app.feature.settings

import cr.micampus.app.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryTest {
    @Test fun institutionsNameEverySelectedUniversity() {
        assertEquals("UCR y UNA", institutionsSummary(ucrEnabled = true, unaEnabled = true))
        assertEquals("UCR", institutionsSummary(ucrEnabled = true, unaEnabled = false))
        assertEquals("UNA", institutionsSummary(ucrEnabled = false, unaEnabled = true))
        assertEquals("Ninguna seleccionada", institutionsSummary(ucrEnabled = false, unaEnabled = false))
    }

    @Test fun accountsReflectAulaVirtualConnection() {
        assertEquals("Aula Virtual UNA: sin conectar", accountsSummary(MoodleSettingsUiState()))
        assertEquals("Aula Virtual UNA: conectada", accountsSummary(MoodleSettingsUiState(connected = true, displayName = "Estudiante")))
        assertEquals("Aula Virtual UNA: sincronizando…", accountsSummary(MoodleSettingsUiState(connected = true, busy = true)))
    }

    @Test fun remindersPrioritizeDisabledThenSystemBlock() {
        assertEquals("Desactivados", remindersSummary(enabled = false, exact = true, blocked = true))
        assertEquals("Activados · bloqueados por el sistema", remindersSummary(enabled = true, exact = true, blocked = true))
        assertEquals("Activados · entrega exacta", remindersSummary(enabled = true, exact = true, blocked = false))
        assertEquals("Activados", remindersSummary(enabled = true, exact = false, blocked = false))
    }

    @Test fun appearanceCombinesThemeAndClock() {
        assertEquals("Tema del sistema · 24 horas", appearanceSummary(ThemeMode.SYSTEM, use12hClock = false))
        assertEquals("Tema claro · 12 horas", appearanceSummary(ThemeMode.LIGHT, use12hClock = true))
        assertEquals("Tema oscuro · 24 horas", appearanceSummary(ThemeMode.DARK, use12hClock = false))
    }

    @Test fun aiNamesTheActiveModesAndMissingKey() {
        assertEquals("Desactivada · nada sale del teléfono", aiSummary(localEnabled = false, cloudEnabled = false, keyPresent = true))
        assertEquals("Modelo local · nada sale del teléfono", aiSummary(localEnabled = true, cloudEnabled = false, keyPresent = false))
        assertEquals("Nube con consentimiento", aiSummary(localEnabled = false, cloudEnabled = true, keyPresent = true))
        assertEquals("Nube sin clave configurada", aiSummary(localEnabled = false, cloudEnabled = true, keyPresent = false))
        assertEquals("Modelo local y nube con consentimiento", aiSummary(localEnabled = true, cloudEnabled = true, keyPresent = true))
    }

    @Test fun aboutShowsVersionAndPendingUpdate() {
        assertEquals("Versión 1.0", aboutSummary("1.0", checking = false, downloaded = false, availableVersion = null))
        assertEquals("Versión 1.0 · buscando actualizaciones…", aboutSummary("1.0", checking = true, downloaded = false, availableVersion = "1.1"))
        assertEquals("Versión 1.0 · actualización lista para instalar", aboutSummary("1.0", checking = false, downloaded = true, availableVersion = "1.1"))
        assertEquals("Versión 1.0 · disponible la 1.1", aboutSummary("1.0", checking = false, downloaded = false, availableVersion = "1.1"))
    }
}
