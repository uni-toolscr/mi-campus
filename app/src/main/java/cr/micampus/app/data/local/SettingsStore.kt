package cr.micampus.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.UnfinishedCoursePolicy
import java.time.LocalDate

private val Context.settingsDataStore by preferencesDataStore("micampus_settings")

internal fun parseAgendaExcludedKinds(values: Set<String>?): Set<EventKind> = values
    ?.mapNotNull { runCatching { EventKind.valueOf(it) }.getOrNull() }
    ?.toSet()
    ?: setOf(EventKind.CLASS)

internal fun parseAgendaExcludedInstitutions(values: Set<String>?): Set<Institution> = values
    ?.mapNotNull { runCatching { Institution.valueOf(it) }.getOrNull() }
    ?.toSet()
    ?: emptySet()

internal data class AiSettingsFlags(val local: Boolean, val cloud: Boolean)

internal fun resolveAiSettings(local: Boolean?, cloud: Boolean?, legacy: Boolean?): AiSettingsFlags =
    AiSettingsFlags(local = local ?: legacy ?: false, cloud = cloud ?: legacy ?: false)

data class AppSettings(
    val onboardingDone: Boolean = false,
    val ucrEnabled: Boolean = true,
    val unaEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = false,
    val localAiEnabled: Boolean = false,
    val cloudAiEnabled: Boolean = false,
    val exactReminders: Boolean = false,
    val use12hClock: Boolean = false,
    val semesterStart: LocalDate? = null,
    val semesterEnd: LocalDate? = null,
    val agendaExcludedKinds: Set<EventKind> = setOf(EventKind.CLASS),
    val agendaExcludedInstitutions: Set<Institution> = emptySet(),
    val academicProgressFilter: AcademicProgressFilter = AcademicProgressFilter(),
    val dismissedUpdateVersion: String? = null,
) {
    /**
     * Enabled institutions in enum order. There is intentionally no implicit institution when
     * every choice is disabled: attaching a guessed university to an AI request would be worse
     * than sending an empty institutional context.
     */
    fun selectedInstitutions(): List<Institution> = listOfNotNull(
        Institution.UCR.takeIf { ucrEnabled },
        Institution.UNA.takeIf { unaEnabled },
    )
}
interface MoodleSyncSettings {
    suspend fun current(): AppSettings
}

class SettingsStore internal constructor(private val dataStore: DataStore<Preferences>) : MoodleSyncSettings {
    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val done = booleanPreferencesKey("onboarding_done")
        val ucr = booleanPreferencesKey("ucr")
        val una = booleanPreferencesKey("una")
        val legacyDark = booleanPreferencesKey("dark")
        val theme = stringPreferencesKey("theme")
        val reminders = booleanPreferencesKey("reminders")
        val exact = booleanPreferencesKey("exact_reminders")
        val legacyAi = booleanPreferencesKey("ai")
        val localAi = booleanPreferencesKey("ai_local")
        val cloudAi = booleanPreferencesKey("ai_cloud")
        val clock12 = booleanPreferencesKey("clock12")
        val semesterStart = stringPreferencesKey("semester_start")
        val semesterEnd = stringPreferencesKey("semester_end")
        val agendaExcludedKinds = stringSetPreferencesKey("agenda_excluded_kinds")
        val agendaExcludedInstitutions = stringSetPreferencesKey("agenda_excluded_institutions")
        val academicProgressYear = intPreferencesKey("academic_progress_year")
        val academicProgressCycle = stringPreferencesKey("academic_progress_cycle")
        val academicProgressUnfinishedPolicy = stringPreferencesKey("academic_progress_unfinished_policy")
        val dismissedUpdateVersion = stringPreferencesKey("dismissed_update_version")
    }
    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        val fallbackTheme = if (p[Keys.legacyDark] == true) ThemeMode.DARK else ThemeMode.SYSTEM
        val ai = resolveAiSettings(p[Keys.localAi], p[Keys.cloudAi], p[Keys.legacyAi])
        AppSettings(
            onboardingDone = p[Keys.done] ?: false,
            ucrEnabled = p[Keys.ucr] ?: true,
            unaEnabled = p[Keys.una] ?: true,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: fallbackTheme,
            remindersEnabled = p[Keys.reminders] ?: false,
            // The former single switch enabled the complete local-first/cloud-fallback ladder.
            // Use it only as a fallback so existing installations keep their behavior while
            // fresh installations leave both independent processors disabled.
            localAiEnabled = ai.local,
            cloudAiEnabled = ai.cloud,
            exactReminders = p[Keys.exact] ?: false,
            use12hClock = p[Keys.clock12] ?: false,
            semesterStart = p[Keys.semesterStart]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            semesterEnd = p[Keys.semesterEnd]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            agendaExcludedKinds = parseAgendaExcludedKinds(p[Keys.agendaExcludedKinds]),
            agendaExcludedInstitutions = parseAgendaExcludedInstitutions(p[Keys.agendaExcludedInstitutions]),
            academicProgressFilter = AcademicProgressFilter(
                year = p[Keys.academicProgressYear]?.takeIf { it in 1900..2100 },
                cycle = p[Keys.academicProgressCycle]?.let { runCatching { AcademicCycle.valueOf(it) }.getOrNull() },
                unfinishedPolicy = p[Keys.academicProgressUnfinishedPolicy]?.let { runCatching { UnfinishedCoursePolicy.valueOf(it) }.getOrNull() }
                    ?: UnfinishedCoursePolicy.EXCLUDE,
            ),
            dismissedUpdateVersion = p[Keys.dismissedUpdateVersion],
        )
    }
    suspend fun setOnboarding(done: Boolean, ucr: Boolean, una: Boolean) = dataStore.edit { it[Keys.done] = done; it[Keys.ucr] = ucr; it[Keys.una] = una }
    suspend fun setTheme(value: ThemeMode) = dataStore.edit { it[Keys.theme] = value.name }
    suspend fun setReminders(value: Boolean) = dataStore.edit { it[Keys.reminders] = value }
    suspend fun setExactReminders(value: Boolean) = dataStore.edit { it[Keys.exact] = value }
    suspend fun setLocalAiEnabled(value: Boolean) = dataStore.edit { it[Keys.localAi] = value }
    suspend fun setCloudAiEnabled(value: Boolean) = dataStore.edit { it[Keys.cloudAi] = value }
    suspend fun setUse12hClock(value: Boolean) = dataStore.edit { it[Keys.clock12] = value }
    suspend fun setSemesterRange(start: LocalDate, end: LocalDate) = dataStore.edit { it[Keys.semesterStart] = start.toString(); it[Keys.semesterEnd] = end.toString() }
    suspend fun setAgendaFilters(excludedKinds: Set<EventKind>, excludedInstitutions: Set<Institution>) = dataStore.edit {
        it[Keys.agendaExcludedKinds] = excludedKinds.mapTo(mutableSetOf(), EventKind::name)
        it[Keys.agendaExcludedInstitutions] = excludedInstitutions.mapTo(mutableSetOf(), Institution::name)
    }
    suspend fun setAcademicProgressFilter(filter: AcademicProgressFilter) = dataStore.edit {
        if (filter.year == null) it.remove(Keys.academicProgressYear) else it[Keys.academicProgressYear] = filter.year
        if (filter.cycle == null) it.remove(Keys.academicProgressCycle) else it[Keys.academicProgressCycle] = filter.cycle.name
        it[Keys.academicProgressUnfinishedPolicy] = filter.unfinishedPolicy.name
    }
    suspend fun setDismissedUpdateVersion(version: String?) = dataStore.edit { if (version == null) it.remove(Keys.dismissedUpdateVersion) else it[Keys.dismissedUpdateVersion] = version }
    override suspend fun current(): AppSettings = settings.first()
}
