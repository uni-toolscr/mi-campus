package cr.micampus.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cr.micampus.app.core.model.CourseStyle

@Composable
fun coursePalette(): List<Pair<Color, Color>> {
    val colors = MaterialTheme.colorScheme
    return listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.surfaceVariant to colors.onSurfaceVariant,
        colors.errorContainer to colors.onErrorContainer,
        colors.inversePrimary to colors.inverseSurface,
        lerp(colors.primaryContainer, colors.secondaryContainer, 0.5f) to lerp(colors.onPrimaryContainer, colors.onSecondaryContainer, 0.5f),
        lerp(colors.secondaryContainer, colors.tertiaryContainer, 0.5f) to lerp(colors.onSecondaryContainer, colors.onTertiaryContainer, 0.5f),
    )
}

fun assignCourseColors(courseKeys: List<String>, stored: Map<String, CourseStyle>, paletteSize: Int): Map<String, Int> {
    require(paletteSize > 0) { "paletteSize must be positive" }
    val keys = courseKeys.distinct().sorted()
    val assignments = mutableMapOf<String, Int>()
    val occupied = mutableSetOf<Int>()
    keys.forEach { key ->
        stored[key]?.let { style ->
            val index = Math.floorMod(style.colorIndex, paletteSize)
            assignments[key] = index
            occupied += index
        }
    }
    var nextIndex = 0
    keys.filterNot(assignments::containsKey).forEach { key ->
        val index = (0 until paletteSize).firstOrNull { it !in occupied } ?: (nextIndex++ % paletteSize)
        assignments[key] = index
        occupied += index
    }
    return assignments
}
