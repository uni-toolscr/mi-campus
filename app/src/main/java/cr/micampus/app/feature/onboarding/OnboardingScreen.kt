package cr.micampus.app.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.safeHorizontalInsets

@Composable
fun OnboardingScreen(state: OnboardingUiState, onUcr: (Boolean) -> Unit, onUna: (Boolean) -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.School, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Mi Campus", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text("Organiza tu vida universitaria, incluso sin conexión.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Text("¿Dónde estudias?", style = MaterialTheme.typography.headlineSmall)
        Text("Puedes seleccionar ambas instituciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        InstitutionCard("Universidad de Costa Rica", "UCR", state.ucrSelected) { onUcr(!state.ucrSelected) }
        Spacer(Modifier.height(12.dp))
        InstitutionCard("Universidad Nacional de Costa Rica", "UNA", state.unaSelected) { onUna(!state.unaSelected) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, enabled = state.canContinue && !state.saving, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Text(if (state.saving) "Guardando…" else "Continuar", style = MaterialTheme.typography.titleMedium)
        }
        if (!state.canContinue) Text("Selecciona al menos una institución", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun InstitutionCard(name: String, acronym: String, checked: Boolean, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "institutionCardColor",
    )
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(acronym, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
