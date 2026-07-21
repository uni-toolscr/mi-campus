package cr.micampus.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cr.micampus.app.core.model.CourseStyle

// Hand-picked, high-chroma course colors independent of the (possibly muted) dynamic
// scheme, so each class reads as a distinct vibrant block instead of wallpaper-derived
// browns. Eight hues, container/on-container pairs cleared for ~4.5:1 text contrast.
// Order and size (8) are fixed: stored CourseStyle.colorIndex values map here by index.
private val LightCoursePalette = listOf(
    Color(0xFFD8E2FF) to Color(0xFF001945), // Blue
    Color(0xFF9FF2E4) to Color(0xFF00201C), // Teal
    Color(0xFFECDCFF) to Color(0xFF25005A), // Violet
    Color(0xFFFFE08B) to Color(0xFF241A00), // Amber
    Color(0xFFFFD9E2) to Color(0xFF3E001D), // Rose
    Color(0xFFB8F397) to Color(0xFF052100), // Green
    Color(0xFFE0E0FF) to Color(0xFF12006E), // Indigo
    Color(0xFFFFDBC9) to Color(0xFF380D00), // Orange
)
private val DarkCoursePalette = listOf(
    Color(0xFF284777) to Color(0xFFD8E2FF), // Blue
    Color(0xFF00504A) to Color(0xFF9FF2E4), // Teal
    Color(0xFF583D82) to Color(0xFFECDCFF), // Violet
    Color(0xFF5B4300) to Color(0xFFFFE08B), // Amber
    Color(0xFF7D2A48) to Color(0xFFFFD9E2), // Rose
    Color(0xFF2C5000) to Color(0xFFB8F397), // Green
    Color(0xFF40409A) to Color(0xFFE0E0FF), // Indigo
    Color(0xFF8A3E1E) to Color(0xFFFFDBC9), // Orange
)

@Composable
fun coursePalette(): List<Pair<Color, Color>> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) DarkCoursePalette else LightCoursePalette
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
