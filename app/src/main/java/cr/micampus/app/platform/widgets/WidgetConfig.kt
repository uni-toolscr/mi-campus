package cr.micampus.app.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.model.Institution
import kotlinx.coroutines.launch

/** Resolve the appWidgetId from the configuration intent, or [AppWidgetManager.INVALID_APPWIDGET_ID]. */
fun ComponentActivity.appWidgetIdFromIntent(): Int = intent?.extras?.getInt(
    AppWidgetManager.EXTRA_APPWIDGET_ID,
    AppWidgetManager.INVALID_APPWIDGET_ID,
) ?: AppWidgetManager.INVALID_APPWIDGET_ID

/** The institution filter a widget should default to, derived from onboarding settings. */
suspend fun defaultInstitutionFilter(app: MiCampusApplication): String {
    val settings = runCatching { app.container.settings.current() }.getOrNull()
    return when (settings?.selectedInstitutions()?.toSet()) {
        setOf(Institution.UCR) -> WIDGET_FILTER_UCR
        setOf(Institution.UNA) -> WIDGET_FILTER_UNA
        else -> WIDGET_FILTER_BOTH
    }
}

/** Persist per-widget prefs, refresh the widget, return RESULT_OK, and finish. */
suspend fun ComponentActivity.applyWidgetConfig(
    appWidgetId: Int,
    widget: GlanceAppWidget,
    afterUpdate: suspend () -> Unit = {},
    apply: (MutablePreferences) -> Unit,
) {
    val manager = GlanceAppWidgetManager(applicationContext)
    val glanceId = manager.getGlanceIdBy(appWidgetId)
    updateAppWidgetState(applicationContext, glanceId) { prefs -> apply(prefs) }
    widget.update(applicationContext, glanceId)
    afterUpdate()
    val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    setResult(Activity.RESULT_OK, result)
    finish()
}

@Composable
fun WidgetConfigScaffold(
    title: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                content()
                val confirmLabel = stringResource(R.string.widget_config_confirm)
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
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

@Composable
fun InstitutionOptions(selected: String, onSelect: (String) -> Unit) {
    Text(
        text = stringResource(R.string.widget_config_institution_section),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    InstitutionOption(stringResource(R.string.widget_institution_ucr), WIDGET_FILTER_UCR, selected, onSelect)
    InstitutionOption(stringResource(R.string.widget_institution_una), WIDGET_FILTER_UNA, selected, onSelect)
    InstitutionOption(stringResource(R.string.widget_institution_both), WIDGET_FILTER_BOTH, selected, onSelect)
}

@Composable
private fun InstitutionOption(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
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

/** Shared institution-only configuration screen used by the events and class widgets. */
@Composable
fun InstitutionOnlyConfig(app: MiCampusApplication, title: String, onConfirm: (String) -> Unit) {
    // Wait for the onboarding-derived default before showing interactive controls, so a
    // selection made during the async read is never overwritten when the default arrives.
    var defaultFilter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { defaultFilter = defaultInstitutionFilter(app) }
    val loaded = defaultFilter ?: return
    var selected by remember { mutableStateOf(loaded) }
    WidgetConfigScaffold(title = title, confirmEnabled = true, onConfirm = { onConfirm(selected) }) {
        InstitutionOptions(selected = selected, onSelect = { selected = it })
    }
}

/** Configuration screen for the "Próximos eventos" widget: institution filter only. */
class EventsWidgetConfigActivity : ComponentActivity() {
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
                InstitutionOnlyConfig(app, stringResource(R.string.widget_config_title_events)) { filter ->
                    scope.launch {
                        applyWidgetConfig(appWidgetId, EventsWidget()) { it[WIDGET_INSTITUTIONS_KEY] = filter }
                    }
                }
            }
        }
    }
}

/** Configuration screen for the "Próximas clases" widget: institution filter only. */
class ClassWidgetConfigActivity : ComponentActivity() {
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
                InstitutionOnlyConfig(app, stringResource(R.string.widget_config_title_class)) { filter ->
                    scope.launch {
                        applyWidgetConfig(appWidgetId, ClassWidget()) { it[WIDGET_INSTITUTIONS_KEY] = filter }
                    }
                }
            }
        }
    }
}
