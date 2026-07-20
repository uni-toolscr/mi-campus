package cr.micampus.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

// Some OEM/dynamic palettes serve light-tone accent containers in the dark scheme,
// which breaks contrast for content drawn with scheme-global colors (error text,
// primary buttons, disabled onSurface alphas) placed on those containers.
internal fun ColorScheme.ensureDarkContainers(): ColorScheme {
    val lightPrimary = primaryContainer.luminance() > 0.5f
    val lightSecondary = secondaryContainer.luminance() > 0.5f
    val lightTertiary = tertiaryContainer.luminance() > 0.5f
    if (!lightPrimary && !lightSecondary && !lightTertiary) return this
    return copy(
        primaryContainer = if (lightPrimary) Dark.primaryContainer else primaryContainer,
        onPrimaryContainer = if (lightPrimary) Dark.onPrimaryContainer else onPrimaryContainer,
        secondaryContainer = if (lightSecondary) Dark.secondaryContainer else secondaryContainer,
        onSecondaryContainer = if (lightSecondary) Dark.onSecondaryContainer else onSecondaryContainer,
        tertiaryContainer = if (lightTertiary) Dark.tertiaryContainer else tertiaryContainer,
        onTertiaryContainer = if (lightTertiary) Dark.onTertiaryContainer else onTertiaryContainer,
    )
}

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
    val safeColors = if (dark) colors.ensureDarkContainers() else colors
    MaterialTheme(colorScheme = safeColors, typography = Typography(), shapes = ExpressiveShapes, content = content)
}
