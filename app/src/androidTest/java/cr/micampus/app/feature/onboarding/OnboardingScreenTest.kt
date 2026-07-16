package cr.micampus.app.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cr.micampus.app.core.designsystem.MiCampusTheme
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneOrBothInstitutionsAreAcceptedButZeroIsBlocked() {
        compose.setContent {
            var state by remember { mutableStateOf(OnboardingUiState()) }
            MiCampusTheme {
                OnboardingScreen(
                    state,
                    onUcr = { state = state.copy(ucrSelected = it) },
                    onUna = { state = state.copy(unaSelected = it) },
                    onDone = {},
                )
            }
        }
        compose.onNodeWithText("Continuar").assertIsEnabled()
        compose.onNodeWithText("Universidad de Costa Rica").performClick()
        compose.onNodeWithText("Universidad Nacional de Costa Rica").performClick()
        compose.onNodeWithText("Continuar").assertIsNotEnabled()
        compose.onNodeWithText("Universidad de Costa Rica").performClick()
        compose.onNodeWithText("Continuar").assertIsEnabled()
    }
}
