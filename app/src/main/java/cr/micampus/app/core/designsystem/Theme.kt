package cr.micampus.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import cr.micampus.app.core.model.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF315DA8),
    secondary = Color(0xFF526079),
    tertiary = Color(0xFF6D5678),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE0E2EC),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    secondary = Color(0xFFBAC7E5),
    tertiary = Color(0xFFD9BDE2),
)

@Composable
fun MiCampusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = Shapes(), content = content)
}
