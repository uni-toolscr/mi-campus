package cr.micampus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeViewModel
import cr.micampus.app.feature.importer.ImporterScreen
import cr.micampus.app.feature.importer.ImporterViewModel
import cr.micampus.app.feature.onboarding.OnboardingScreen
import cr.micampus.app.feature.onboarding.OnboardingViewModel
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MiCampusRoot() }
    }
}

private enum class Destination(val label: String) { HOME("Inicio"), CALENDAR("Calendario"), TRANSPORT("Transporte"), SETTINGS("Ajustes") }

@Composable
private fun MiCampusRoot() {
    val app = LocalContext.current.applicationContext as MiCampusApplication
    val container = app.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val onboarding: OnboardingViewModel = viewModel(factory = factory { OnboardingViewModel(container.settings) })
    val onboardingState by onboarding.state.collectAsStateWithLifecycle()
    MiCampusTheme(themeMode = settings.themeMode) {
        if (!settings.onboardingDone) {
            OnboardingScreen(onboardingState, onboarding::setUcr, onboarding::setUna) { onboarding.complete {} }
        } else {
            MainDestinations(container)
        }
    }
}

@Composable
private fun MainDestinations(container: AppContainer) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    val home: HomeViewModel = viewModel(factory = factory { HomeViewModel(container.events, container.settings, container.transport) })
    val calendar: CalendarViewModel = viewModel(factory = factory { CalendarViewModel(container.events, container.calendar, container.reminders, container.settings) })
    val transport: TransportViewModel = viewModel(factory = factory { TransportViewModel(container.transport) })
    val settings: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settings, container.keyStore, container.events, container.reminders) })
    val importer: ImporterViewModel = viewModel(factory = factory { ImporterViewModel(container.documents, container.events, container.settings, container.reminders, container.cloud) })
    val homeState by home.state.collectAsStateWithLifecycle()
    val calendarState by calendar.state.collectAsStateWithLifecycle()
    val transportState by transport.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val importerState by importer.state.collectAsStateWithLifecycle()

    if (importing) {
        ImporterScreen(importerState, importer) { importing = false }
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.values().forEach { item ->
                item(
                    selected = destination == item,
                    onClick = { destination = item },
                    icon = { Icon(destinationIcon(item), contentDescription = item.label) },
                    label = { Text(item.label) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                Destination.HOME -> HomeScreen(homeState) { importing = true }
                Destination.CALENDAR -> CalendarScreen(calendarState, calendar, expanded)
                Destination.TRANSPORT -> TransportScreen(transportState, transport::selectInstitution, transport::selectDirection, transport::selectDate)
                Destination.SETTINGS -> SettingsScreen(settingsState, settings)
            }
        }
    }
}

private fun destinationIcon(destination: Destination) = when (destination) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.CALENDAR -> Icons.Outlined.CalendarMonth
    Destination.TRANSPORT -> Icons.Outlined.DirectionsBus
    Destination.SETTINGS -> Icons.Outlined.Settings
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
