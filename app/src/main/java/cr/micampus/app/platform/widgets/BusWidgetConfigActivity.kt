package cr.micampus.app.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.MiCampusTheme
import kotlinx.coroutines.launch

/**
 * Configuration screen for the "Próximos buses" widget: lets the user choose
 * the per-widget institution filter (UCR / UNA / Ambas) before it is placed.
 */
class BusWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = applicationContext as MiCampusApplication

        setContent {
            MiCampusTheme {
                val scope = rememberCoroutineScope()
                var defaultFilter by remember { mutableStateOf(BUS_WIDGET_FILTER_BOTH) }

                LaunchedEffect(Unit) {
                    val settings = runCatching { app.container.settings.current() }.getOrNull()
                    defaultFilter = when {
                        settings == null -> BUS_WIDGET_FILTER_BOTH
                        settings.ucrEnabled && settings.unaEnabled -> BUS_WIDGET_FILTER_BOTH
                        settings.ucrEnabled -> BUS_WIDGET_FILTER_UCR
                        settings.unaEnabled -> BUS_WIDGET_FILTER_UNA
                        else -> BUS_WIDGET_FILTER_BOTH
                    }
                }

                var selected by remember(defaultFilter) { mutableStateOf(defaultFilter) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(innerPadding)
                                .padding(24.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.widget_config_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            InstitutionOption(
                                label = stringResource(R.string.widget_institution_ucr),
                                value = BUS_WIDGET_FILTER_UCR,
                                selected = selected,
                                onSelect = { selected = it },
                            )
                            InstitutionOption(
                                label = stringResource(R.string.widget_institution_una),
                                value = BUS_WIDGET_FILTER_UNA,
                                selected = selected,
                                onSelect = { selected = it },
                            )
                            InstitutionOption(
                                label = stringResource(R.string.widget_institution_both),
                                value = BUS_WIDGET_FILTER_BOTH,
                                selected = selected,
                                onSelect = { selected = it },
                            )
                            val confirmLabel = stringResource(R.string.widget_config_confirm)
                            Button(
                                onClick = {
                                    scope.launch {
                                        confirm(selected)
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .semantics { contentDescription = confirmLabel },
                            ) {
                                Text(confirmLabel)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun confirm(filter: String) {
        val manager = GlanceAppWidgetManager(applicationContext)
        val glanceId = manager.getGlanceIdBy(appWidgetId)
        updateAppWidgetState(applicationContext, glanceId) { prefs ->
            prefs[BUS_WIDGET_INSTITUTIONS_KEY] = filter
        }
        BusesWidget().update(applicationContext, glanceId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}

@Composable
private fun InstitutionOption(
    label: String,
    value: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .selectable(selected = selected == value, onClick = { onSelect(value) })
            .semantics { contentDescription = "Filtro $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
