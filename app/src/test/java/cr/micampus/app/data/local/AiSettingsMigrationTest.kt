package cr.micampus.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.UnfinishedCoursePolicy

class AiSettingsMigrationTest {
    @Test fun freshInstallDisablesBothProcessors() {
        assertEquals(AiSettingsFlags(local = false, cloud = false), resolveAiSettings(null, null, null))
    }

    @Test fun legacyEnabledSettingEnablesBothProcessors() {
        assertEquals(AiSettingsFlags(local = true, cloud = true), resolveAiSettings(null, null, true))
    }

    @Test fun independentSettingsOverrideLegacyValue() {
        assertEquals(AiSettingsFlags(local = false, cloud = true), resolveAiSettings(false, true, false))
        assertEquals(AiSettingsFlags(local = true, cloud = false), resolveAiSettings(true, false, true))
    }

    @Test fun oneNewSettingCanStillUseLegacyFallbackForTheOther() {
        assertEquals(AiSettingsFlags(local = false, cloud = true), resolveAiSettings(false, null, true))
    }

    @Test fun settersUpdateLocalAndCloudIndependently() = runTest {
        val file = File.createTempFile("micampus-ai-settings", ".preferences_pb").also(File::delete)
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = SettingsStore(dataStore)

        store.setLocalAiEnabled(true)
        assertEquals(AppSettings().copy(localAiEnabled = true), store.current())

        store.setCloudAiEnabled(true)
        assertEquals(AppSettings().copy(localAiEnabled = true, cloudAiEnabled = true), store.current())

        store.setLocalAiEnabled(false)
        assertEquals(AppSettings().copy(cloudAiEnabled = true), store.current())
    }

    @Test fun academicProgressFilterPersistsAndDefaultsSafely() = runTest {
        val file = File.createTempFile("micampus-progress-settings", ".preferences_pb").also(File::delete)
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertEquals(AcademicProgressFilter(), store.current().academicProgressFilter)
        val filter = AcademicProgressFilter(2025, AcademicCycle.II, UnfinishedCoursePolicy.AS_FAILED)
        store.setAcademicProgressFilter(filter)
        assertEquals(filter, store.current().academicProgressFilter)
    }
}
