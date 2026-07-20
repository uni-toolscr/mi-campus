package cr.micampus.app.platform.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizeTierTest {
    @Test
    fun `responsive supported sizes select the matching tier and item limit`() {
        assertTier(DpSize(110.dp, 110.dp), WidgetSizeTier.SQUARE, 1)
        assertTier(DpSize(110.dp, 180.dp), WidgetSizeTier.NARROW, 2)
        assertTier(DpSize(180.dp, 110.dp), WidgetSizeTier.COMPACT, 1)
        assertTier(DpSize(180.dp, 180.dp), WidgetSizeTier.NARROW, 2)
        assertTier(DpSize(250.dp, 110.dp), WidgetSizeTier.COMPACT, 1)
        assertTier(DpSize(250.dp, 180.dp), WidgetSizeTier.MEDIUM, 2)
        assertTier(DpSize(250.dp, 280.dp), WidgetSizeTier.TALL, 4)
    }

    @Test
    fun `intermediate sizes keep the lower supported tier`() {
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(179.dp, 280.dp)))
        assertEquals(WidgetSizeTier.COMPACT, widgetSizeTier(DpSize(250.dp, 179.dp)))
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(249.dp, 280.dp)))
        assertEquals(WidgetSizeTier.MEDIUM, widgetSizeTier(DpSize(250.dp, 279.dp)))
    }

    @Test
    fun `only square hides the header`() {
        assertFalse(WidgetSizeTier.SQUARE.showsHeader)
        assertTrue(WidgetSizeTier.NARROW.showsHeader)
        assertTrue(WidgetSizeTier.COMPACT.showsHeader)
        assertTrue(WidgetSizeTier.MEDIUM.showsHeader)
        assertTrue(WidgetSizeTier.TALL.showsHeader)

        assertFalse(WidgetSizeTier.SQUARE.showsHeaderTitle)
        assertFalse(WidgetSizeTier.NARROW.showsHeaderTitle)
        assertFalse(WidgetSizeTier.COMPACT.showsHeaderTitle)
        assertTrue(WidgetSizeTier.MEDIUM.showsHeaderTitle)
        assertTrue(WidgetSizeTier.TALL.showsHeaderTitle)
        assertEquals(16, widgetHeaderIconSizeDp(WidgetSizeTier.NARROW))
    }

    @Test
    fun `hero alignment and follow-up trailing adapt to the tier`() {
        assertFalse(WidgetSizeTier.SQUARE.heroTopAligned)
        assertTrue(WidgetSizeTier.NARROW.heroTopAligned)
        assertFalse(WidgetSizeTier.COMPACT.heroTopAligned)
        assertTrue(WidgetSizeTier.MEDIUM.heroTopAligned)
        assertTrue(WidgetSizeTier.TALL.heroTopAligned)

        assertTrue(WidgetSizeTier.SQUARE.followupShowsTrailing)
        assertFalse(WidgetSizeTier.NARROW.followupShowsTrailing)
        assertTrue(WidgetSizeTier.COMPACT.followupShowsTrailing)
        assertTrue(WidgetSizeTier.MEDIUM.followupShowsTrailing)
        assertTrue(WidgetSizeTier.TALL.followupShowsTrailing)
    }

    @Test
    fun `bus hero always shows its departure countdown`() {
        WidgetSizeTier.entries.forEach { tier ->
            assertTrue("$tier must show the bus countdown", heroItemPresentation(tier, HeroItemContent.BUS).showSecondary)
        }
    }

    @Test
    fun `square hero shows inline icons and content selects its badge slot`() {
        WidgetSizeTier.entries.forEach { tier ->
            val shouldShowInlineIcon = tier == WidgetSizeTier.SQUARE
            assertEquals(
                shouldShowInlineIcon,
                heroItemPresentation(tier, HeroItemContent.BUS).showInlineIcon,
            )
            assertEquals(
                shouldShowInlineIcon,
                heroItemPresentation(tier, HeroItemContent.EVENT).showInlineIcon,
            )
            assertEquals(
                HeroBadgeSlot.SECONDARY,
                heroItemPresentation(tier, HeroItemContent.BUS).badgeSlot,
            )
            assertEquals(
                HeroBadgeSlot.PRIMARY,
                heroItemPresentation(tier, HeroItemContent.EVENT).badgeSlot,
            )
        }
    }

    @Test
    fun `hero presentations match each tier text budget`() {
        assertEquals(
            HeroItemPresentation(1, 3, 1, true, 16, 11, true, true, HeroBadgeSlot.SECONDARY),
            heroItemPresentation(WidgetSizeTier.SQUARE, HeroItemContent.BUS),
        )
        assertEquals(
            HeroItemPresentation(1, 2, 1, true, 18, 12, true, false, HeroBadgeSlot.SECONDARY),
            heroItemPresentation(WidgetSizeTier.NARROW, HeroItemContent.BUS),
        )
        assertEquals(
            HeroItemPresentation(1, 1, 1, true, 18, 12, true, false, HeroBadgeSlot.SECONDARY),
            heroItemPresentation(WidgetSizeTier.COMPACT, HeroItemContent.BUS),
        )
        assertEquals(
            HeroItemPresentation(1, 1, 1, true, 22, null, false, false, HeroBadgeSlot.SECONDARY),
            heroItemPresentation(WidgetSizeTier.MEDIUM, HeroItemContent.BUS),
        )
        assertEquals(
            HeroItemPresentation(1, 1, 1, true, 28, null, false, false, HeroBadgeSlot.SECONDARY),
            heroItemPresentation(WidgetSizeTier.TALL, HeroItemContent.BUS),
        )

        assertEquals(
            HeroItemPresentation(2, 1, 2, true, 16, 11, false, true, HeroBadgeSlot.PRIMARY),
            heroItemPresentation(WidgetSizeTier.SQUARE, HeroItemContent.EVENT),
        )
        assertEquals(
            HeroItemPresentation(2, 1, 1, true, 18, 12, false, false, HeroBadgeSlot.PRIMARY),
            heroItemPresentation(WidgetSizeTier.NARROW, HeroItemContent.EVENT),
        )
        assertEquals(
            HeroItemPresentation(1, 1, 1, false, 18, 12, false, false, HeroBadgeSlot.PRIMARY),
            heroItemPresentation(WidgetSizeTier.COMPACT, HeroItemContent.EVENT),
        )
        assertEquals(
            HeroItemPresentation(2, 1, 1, true, 22, null, false, false, HeroBadgeSlot.PRIMARY),
            heroItemPresentation(WidgetSizeTier.MEDIUM, HeroItemContent.EVENT),
        )
        assertEquals(
            HeroItemPresentation(2, 1, 1, true, 28, null, false, false, HeroBadgeSlot.PRIMARY),
            heroItemPresentation(WidgetSizeTier.TALL, HeroItemContent.EVENT),
        )
    }

    @Test
    fun `bus uses short countdown label through compact`() {
        assertTrue(heroItemPresentation(WidgetSizeTier.SQUARE, HeroItemContent.BUS).secondaryShortLabel)
        assertTrue(heroItemPresentation(WidgetSizeTier.NARROW, HeroItemContent.BUS).secondaryShortLabel)
        assertTrue(heroItemPresentation(WidgetSizeTier.COMPACT, HeroItemContent.BUS).secondaryShortLabel)
        assertFalse(heroItemPresentation(WidgetSizeTier.MEDIUM, HeroItemContent.BUS).secondaryShortLabel)
        assertFalse(heroItemPresentation(WidgetSizeTier.TALL, HeroItemContent.BUS).secondaryShortLabel)
    }

    @Test
    fun `tier padding follows available space`() {
        assertEquals(4, widgetOuterPaddingDp(WidgetSizeTier.SQUARE))
        assertEquals(6, widgetOuterPaddingDp(WidgetSizeTier.NARROW))
        assertEquals(8, widgetOuterPaddingDp(WidgetSizeTier.COMPACT))
        assertEquals(10, widgetOuterPaddingDp(WidgetSizeTier.MEDIUM))
        assertEquals(14, widgetOuterPaddingDp(WidgetSizeTier.TALL))

        assertEquals(HeroPaddingDp(4, 4), heroPaddingDp(WidgetSizeTier.SQUARE))
        assertEquals(HeroPaddingDp(8, 6), heroPaddingDp(WidgetSizeTier.NARROW))
        assertEquals(HeroPaddingDp(10, 6), heroPaddingDp(WidgetSizeTier.COMPACT))
        assertEquals(HeroPaddingDp(12, 8), heroPaddingDp(WidgetSizeTier.MEDIUM))
        assertEquals(HeroPaddingDp(16, 12), heroPaddingDp(WidgetSizeTier.TALL))
    }

    private fun assertTier(size: DpSize, expectedTier: WidgetSizeTier, expectedItemLimit: Int) {
        assertEquals(expectedTier, widgetSizeTier(size))
        assertEquals(expectedItemLimit, widgetSizeTier(size).itemLimit)
    }
}
