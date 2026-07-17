package cr.micampus.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import cr.micampus.app.core.model.ThemeMode

private val Context.settingsDataStore by preferencesDataStore("micampus_settings")
data class AppSettings(
    val onboardingDone: Boolean = false,
    val ucrEnabled: Boolean = true,
    val unaEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = false,
    val aiEnabled: Boolean = false,
    val exactReminders: Boolean = false,
    val use12hClock: Boolean = false,
)
class SettingsStore(private val context: Context) {
    private object Keys {
        val done = booleanPreferencesKey("onboarding_done")
        val ucr = booleanPreferencesKey("ucr")
        val una = booleanPreferencesKey("una")
        val legacyDark = booleanPreferencesKey("dark")
        val theme = stringPreferencesKey("theme")
        val reminders = booleanPreferencesKey("reminders")
        val exact = booleanPreferencesKey("exact_reminders")
        val ai = booleanPreferencesKey("ai")
        val clock12 = booleanPreferencesKey("clock12")
    }
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val fallbackTheme = if (p[Keys.legacyDark] == true) ThemeMode.DARK else ThemeMode.SYSTEM
        AppSettings(
            onboardingDone = p[Keys.done] ?: false,
            ucrEnabled = p[Keys.ucr] ?: true,
            unaEnabled = p[Keys.una] ?: true,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: fallbackTheme,
            remindersEnabled = p[Keys.reminders] ?: false,
            aiEnabled = p[Keys.ai] ?: false,
            exactReminders = p[Keys.exact] ?: false,
            use12hClock = p[Keys.clock12] ?: false,
        )
    }
    suspend fun setOnboarding(done: Boolean, ucr: Boolean, una: Boolean) = context.settingsDataStore.edit { it[Keys.done] = done; it[Keys.ucr] = ucr; it[Keys.una] = una }
    suspend fun setTheme(value: ThemeMode) = context.settingsDataStore.edit { it[Keys.theme] = value.name }
    suspend fun setReminders(value: Boolean) = context.settingsDataStore.edit { it[Keys.reminders] = value }
    suspend fun setExactReminders(value: Boolean) = context.settingsDataStore.edit { it[Keys.exact] = value }
    suspend fun setAi(value: Boolean) = context.settingsDataStore.edit { it[Keys.ai] = value }
    suspend fun setUse12hClock(value: Boolean) = context.settingsDataStore.edit { it[Keys.clock12] = value }
    suspend fun current(): AppSettings = settings.first()
}
