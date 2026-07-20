package cr.micampus.app.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.TransportDirection
import cr.micampus.app.data.institution.directionKey
import kotlinx.coroutines.launch

/**
 * Configuration screen for the "Próximos buses" widget: choose the institution filter
 * (UCR / UNA / Ambas) and which directions/locations to show. Unchecking directions lets a
 * user place one widget per bus location; leaving all checked shows every direction.
 */
class BusWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val appWidgetId = appWidgetIdFromIntent()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = applicationContext as MiCampusApplication

        setContent {
            MiCampusTheme {
                val scope = rememberCoroutineScope()
                // Wait for the onboarding-derived default before showing controls, so a user's
                // in-progress selection isn't overwritten when the async read completes.
                var defaultFilter by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { defaultFilter = defaultInstitutionFilter(app) }
                val loaded = defaultFilter ?: return@MiCampusTheme

                var selected by remember { mutableStateOf(loaded) }

                // Directions available for the chosen institution(s); reset to "all checked"
                // whenever the institution selection changes.
                val directions: List<Pair<Institution, TransportDirection>> = remember(selected) {
                    institutionsFor(selected).flatMap { inst ->
                        runCatching { app.container.transport.directions(inst) }.getOrDefault(emptyList())
                            .map { inst to it }
                    }
                }
                val availableKeys = remember(directions) {
                    directions.map { (inst, dir) -> directionKey(inst, dir.id) }.toSet()
                }
                var checkedKeys by remember(selected) { mutableStateOf(availableKeys) }

                WidgetConfigScaffold(
                    title = stringResource(R.string.widget_config_title),
                    confirmEnabled = checkedKeys.isNotEmpty(),
                    onConfirm = {
                        val directionsValue = if (checkedKeys == availableKeys) "" else checkedKeys.joinToString(",")
                        scope.launch {
                            applyWidgetConfig(
                                appWidgetId = appWidgetId,
                                widget = BusesWidget(),
                                afterUpdate = app.container.busWidgetRefresh::synchronize,
                            ) { prefs ->
                                prefs[WIDGET_INSTITUTIONS_KEY] = selected
                                prefs[BUS_WIDGET_DIRECTIONS_KEY] = directionsValue
                            }
                        }
                    },
                ) {
                    InstitutionOptions(selected = selected, onSelect = { selected = it })
                    if (directions.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.widget_config_directions_section),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        directions.forEach { (inst, dir) ->
                            val key = directionKey(inst, dir.id)
                            val label = if (selected == WIDGET_FILTER_BOTH) {
                                "${inst.name} · ${dir.from} → ${dir.to}"
                            } else {
                                "${dir.from} → ${dir.to}"
                            }
                            DirectionOption(
                                label = label,
                                checked = key in checkedKeys,
                                onCheckedChange = { checked ->
                                    checkedKeys = if (checked) checkedKeys + key else checkedKeys - key
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange)
            .semantics { contentDescription = "Dirección $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
