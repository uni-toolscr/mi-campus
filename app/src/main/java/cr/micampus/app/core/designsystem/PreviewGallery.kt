package cr.micampus.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.TransportService
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeUiState
import cr.micampus.app.feature.onboarding.OnboardingScreen
import cr.micampus.app.feature.onboarding.OnboardingUiState
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportUiState
import java.time.LocalDate

@Preview(name = "Onboarding", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun OnboardingPreview() {
    MiCampusTheme(dynamicColor = false) {
        OnboardingScreen(OnboardingUiState(), {}, {}, {})
    }
}

@Preview(name = "Inicio vacío", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun EmptyHomePreview() {
    MiCampusTheme(dynamicColor = false) {
        AppBackgroundSurface {
            HomeScreen(HomeUiState(loading = false), {}, {})
        }
    }
}

@Preview(name = "Inicio oscuro", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun DarkHomePreview() {
    MiCampusTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        AppBackgroundSurface {
            HomeScreen(HomeUiState(loading = false), {}, {})
        }
    }
}

@Preview(name = "Transporte vencido", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ExpiredTransportPreview() {
    val date = LocalDate.of(2027, 1, 1)
    MiCampusTheme(dynamicColor = false) {
        AppBackgroundSurface {
            TransportScreen(
                state = TransportUiState(
                    institution = Institution.UNA,
                    date = date,
                    service = TransportService(ServiceStatus.EXPIRED, "UNA-STI-CIRC-002-2026", date, emptyList()),
                ),
                onInstitution = {},
                onDirection = {},
                onDate = {},
                onOpenSettings = {},
            )
        }
    }
}
