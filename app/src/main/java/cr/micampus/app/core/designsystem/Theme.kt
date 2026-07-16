package cr.micampus.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

// The expressive theme entry points (MaterialExpressiveTheme, expressiveLightColorScheme) are
// internal in the resolved material3 artifact, so the expressive feel comes from this larger
// corner-radius scale plus the expressive components used across the screens.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = ExpressiveShapes, content = content)
}
