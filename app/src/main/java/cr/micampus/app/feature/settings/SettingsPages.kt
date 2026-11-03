package cr.micampus.app.feature.settings

import cr.micampus.app.core.model.ThemeMode

/** Sub-pages reachable from the Ajustes front page; `null` stands for the front page itself. */
enum class SettingsPage(val title: String) {
    INSTITUTIONS("Universidades"),
    ACCOUNTS("Cuentas"),
    REMINDERS("Recordatorios"),
    APPEARANCE("Apariencia"),
    AI("IA y privacidad"),
    ADVANCED("Avanzado"),
    ABOUT("Acerca de"),
}

// One-line status shown under each front-page row, so the state reads without opening the page.

internal fun institutionsSummary(ucrEnabled: Boolean, unaEnabled: Boolean): String = when {
    ucrEnabled && unaEnabled -> "UCR y UNA"
    ucrEnabled -> "UCR"
    unaEnabled -> "UNA"
    else -> "Ninguna seleccionada"
}

internal fun accountsSummary(moodle: MoodleSettingsUiState): String = when {
    moodle.busy -> "Aula Virtual UNA: sincronizando…"
    moodle.connected -> "Aula Virtual UNA: conectada"
    else -> "Aula Virtual UNA: sin conectar"
}

internal fun remindersSummary(enabled: Boolean, exact: Boolean, blocked: Boolean): String = when {
    !enabled -> "Desactivados"
    blocked -> "Activados · bloqueados por el sistema"
    exact -> "Activados · entrega exacta"
    else -> "Activados"
}

internal fun appearanceSummary(themeMode: ThemeMode, use12hClock: Boolean): String {
    val theme = when (themeMode) {
        ThemeMode.SYSTEM -> "Tema del sistema"
        ThemeMode.LIGHT -> "Tema claro"
        ThemeMode.DARK -> "Tema oscuro"
    }
    return "$theme · ${if (use12hClock) "12 horas" else "24 horas"}"
}

internal fun aiSummary(localEnabled: Boolean, cloudEnabled: Boolean, keyPresent: Boolean): String {
    val cloud = if (keyPresent) "nube con consentimiento" else "nube sin clave configurada"
    return when {
        localEnabled && cloudEnabled -> "Modelo local y $cloud"
        localEnabled -> "Modelo local · nada sale del teléfono"
        cloudEnabled -> cloud.replaceFirstChar(Char::uppercase)
        else -> "Desactivada · nada sale del teléfono"
    }
}

internal fun aboutSummary(version: String, checking: Boolean, downloaded: Boolean, availableVersion: String?): String = when {
    checking -> "Versión $version · buscando actualizaciones…"
    downloaded -> "Versión $version · actualización lista para instalar"
    availableVersion != null -> "Versión $version · disponible la $availableVersion"
    else -> "Versión $version"
}
