package cr.micampus.app.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkContainerGuardTest {
    @Test
    fun `light tertiary container in dark scheme is replaced with a dark tone`() {
        val broken = darkColorScheme(
            tertiaryContainer = Color(0xFFFFE088),
            onTertiaryContainer = Color(0xFF4A4458),
        )
        val fixed = broken.ensureDarkContainers()
        assertTrue(fixed.tertiaryContainer.luminance() <= 0.5f)
    }

    @Test
    fun `already dark containers are returned unchanged`() {
        val scheme = darkColorScheme()
        assertEquals(scheme, scheme.ensureDarkContainers())
    }

    @Test
    fun `only the offending container is replaced`() {
        val broken = darkColorScheme(
            primaryContainer = Color(0xFF203050),
            tertiaryContainer = Color(0xFFFFE088),
        )
        val fixed = broken.ensureDarkContainers()
        assertEquals(Color(0xFF203050), fixed.primaryContainer)
        assertTrue(fixed.tertiaryContainer.luminance() <= 0.5f)
    }
}
