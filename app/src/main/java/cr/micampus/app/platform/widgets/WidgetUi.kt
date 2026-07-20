package cr.micampus.app.platform.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cr.micampus.app.MainActivity
import cr.micampus.app.R
import cr.micampus.app.core.model.Institution

/**
 * Shared size tiers for all home-screen widgets. The responsive sizes advertised by
 * each provider are 110 x 110, 110 x 180, 180 x 110, 180 x 180, 250 x 110,
 * 250 x 180, and 250 x 280 dp. SQUARE is a single-hero surface; NARROW,
 * COMPACT, MEDIUM, and TALL show two, one, two, and four items respectively.
 */
enum class WidgetSizeTier(val itemLimit: Int) {
    SQUARE(1),
    NARROW(2),
    COMPACT(1),
    MEDIUM(2),
    TALL(4),
}

val WidgetSizeTier.showsHeader: Boolean
    get() = this != WidgetSizeTier.SQUARE

val WidgetSizeTier.showsHeaderTitle: Boolean
    get() = this == WidgetSizeTier.MEDIUM || this == WidgetSizeTier.TALL

val WidgetSizeTier.heroTopAligned: Boolean
    get() = this == WidgetSizeTier.NARROW || this == WidgetSizeTier.MEDIUM || this == WidgetSizeTier.TALL

val WidgetSizeTier.followupShowsTrailing: Boolean
    get() = this != WidgetSizeTier.NARROW

val WIDGET_RESPONSIVE_SIZES = setOf(
    DpSize(110.dp, 110.dp),
    DpSize(110.dp, 180.dp),
    DpSize(180.dp, 110.dp),
    DpSize(180.dp, 180.dp),
    DpSize(250.dp, 110.dp),
    DpSize(250.dp, 180.dp),
    DpSize(250.dp, 280.dp),
)

fun widgetSizeTier(size: DpSize): WidgetSizeTier = when {
    size.width < 180.dp && size.height < 180.dp -> WidgetSizeTier.SQUARE
    size.height < 180.dp -> WidgetSizeTier.COMPACT
    size.width < 250.dp -> WidgetSizeTier.NARROW
    size.height < 280.dp -> WidgetSizeTier.MEDIUM
    else -> WidgetSizeTier.TALL
}

/** Content-specific constraints for the shared [HeroItem] layout. */
enum class HeroItemContent { BUS, EVENT }

/** The optional hero detail line that receives an accent pill treatment. */
enum class HeroBadgeSlot { NONE, PRIMARY, SECONDARY }

/** Text rules for a hero at each responsive size tier. */
data class HeroItemPresentation(
    val headlineMaxLines: Int,
    val primaryMaxLines: Int,
    val secondaryMaxLines: Int,
    val showSecondary: Boolean,
    val headlineSp: Int,
    val bodySp: Int?,
    val secondaryShortLabel: Boolean,
    val showInlineIcon: Boolean,
    val badgeSlot: HeroBadgeSlot,
)

fun heroItemPresentation(tier: WidgetSizeTier, content: HeroItemContent): HeroItemPresentation {
    val showInlineIcon = tier == WidgetSizeTier.SQUARE
    return when (content) {
        HeroItemContent.BUS -> when (tier) {
            WidgetSizeTier.SQUARE -> HeroItemPresentation(1, 3, 1, true, 16, 11, true, showInlineIcon, HeroBadgeSlot.SECONDARY)
            WidgetSizeTier.NARROW -> HeroItemPresentation(1, 2, 1, true, 18, 12, true, showInlineIcon, HeroBadgeSlot.SECONDARY)
            WidgetSizeTier.COMPACT -> HeroItemPresentation(1, 1, 1, true, 18, 12, true, showInlineIcon, HeroBadgeSlot.SECONDARY)
            WidgetSizeTier.MEDIUM -> HeroItemPresentation(1, 1, 1, true, 22, null, false, showInlineIcon, HeroBadgeSlot.SECONDARY)
            WidgetSizeTier.TALL -> HeroItemPresentation(1, 1, 1, true, 28, null, false, showInlineIcon, HeroBadgeSlot.SECONDARY)
        }
        HeroItemContent.EVENT -> when (tier) {
            WidgetSizeTier.SQUARE -> HeroItemPresentation(2, 1, 2, true, 16, 11, false, showInlineIcon, HeroBadgeSlot.PRIMARY)
            WidgetSizeTier.NARROW -> HeroItemPresentation(2, 1, 1, true, 18, 12, false, showInlineIcon, HeroBadgeSlot.PRIMARY)
            WidgetSizeTier.COMPACT -> HeroItemPresentation(1, 1, 1, false, 18, 12, false, showInlineIcon, HeroBadgeSlot.PRIMARY)
            WidgetSizeTier.MEDIUM -> HeroItemPresentation(2, 1, 1, true, 22, null, false, showInlineIcon, HeroBadgeSlot.PRIMARY)
            WidgetSizeTier.TALL -> HeroItemPresentation(2, 1, 1, true, 28, null, false, showInlineIcon, HeroBadgeSlot.PRIMARY)
        }
    }
}

/** Horizontal and vertical hero padding, in dp, selected by responsive tier. */
data class HeroPaddingDp(val horizontal: Int, val vertical: Int)

fun widgetOuterPaddingDp(tier: WidgetSizeTier): Int = when (tier) {
    WidgetSizeTier.SQUARE -> 4
    WidgetSizeTier.NARROW -> 6
    WidgetSizeTier.COMPACT -> 8
    WidgetSizeTier.MEDIUM -> 10
    WidgetSizeTier.TALL -> 14
}

fun heroPaddingDp(tier: WidgetSizeTier): HeroPaddingDp = when (tier) {
    WidgetSizeTier.SQUARE -> HeroPaddingDp(horizontal = 4, vertical = 4)
    WidgetSizeTier.NARROW -> HeroPaddingDp(horizontal = 8, vertical = 6)
    WidgetSizeTier.COMPACT -> HeroPaddingDp(horizontal = 10, vertical = 6)
    WidgetSizeTier.MEDIUM -> HeroPaddingDp(horizontal = 12, vertical = 8)
    WidgetSizeTier.TALL -> HeroPaddingDp(horizontal = 16, vertical = 12)
}

fun widgetHeaderIconSizeDp(tier: WidgetSizeTier): Int = when (tier) {
    WidgetSizeTier.NARROW, WidgetSizeTier.COMPACT -> 16
    WidgetSizeTier.MEDIUM -> 18
    WidgetSizeTier.TALL -> 20
    WidgetSizeTier.SQUARE -> 0
}

// --- Widget deep-link targets -------------------------------------------------
// Carried as a MainActivity intent extra so a widget tap lands on the matching
// screen instead of Inicio. MainActivity reads and routes these.

const val EXTRA_WIDGET_DESTINATION = "cr.micampus.app.widget.DESTINATION"
const val WIDGET_DEST_CALENDAR_HORARIO = "calendar_horario"
const val WIDGET_DEST_CALENDAR_AGENDA = "calendar_agenda"
const val WIDGET_DEST_TRANSPORT = "transport"

// --- Shared widget preferences ------------------------------------------------
// Each widget instance owns its own PreferencesGlanceStateDefinition datastore, so
// these key names are reused across widget types without colliding.

/** Per-widget institution filter: "UCR" | "UNA" | "BOTH". */
val WIDGET_INSTITUTIONS_KEY: Preferences.Key<String> = stringPreferencesKey("institutions")

/** Bus widget only: comma-joined [directionKey] values; absent/blank = all directions. */
val BUS_WIDGET_DIRECTIONS_KEY: Preferences.Key<String> = stringPreferencesKey("directions")

const val WIDGET_FILTER_UCR = "UCR"
const val WIDGET_FILTER_UNA = "UNA"
const val WIDGET_FILTER_BOTH = "BOTH"

fun institutionsFor(filter: String): List<Institution> = when (filter) {
    WIDGET_FILTER_UCR -> listOf(Institution.UCR)
    WIDGET_FILTER_UNA -> listOf(Institution.UNA)
    else -> listOf(Institution.UCR, Institution.UNA)
}

fun widgetInstitutionLabel(context: Context, filter: String): String = when (filter) {
    WIDGET_FILTER_UCR -> context.getString(R.string.widget_institution_ucr)
    WIDGET_FILTER_UNA -> context.getString(R.string.widget_institution_una)
    else -> context.getString(R.string.widget_institution_both)
}

/** Parse the persisted direction filter into a set of [directionKey]s (empty = all). */
fun parseDirectionFilter(raw: String?): Set<String> =
    raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

// --- Shared Glance composables ------------------------------------------------

/**
 * Outer widget frame: taps open the app, a tinted icon + bold title header, then the
 * caller-supplied body. The host rounds the outer corners on Android 12+, so we leave the
 * surface square here and only round the inner hero/rows.
 */
@Composable
fun WidgetShell(
    title: String,
    iconRes: Int,
    description: String,
    launchDestination: String,
    tier: WidgetSizeTier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dense = tier != WidgetSizeTier.TALL
    val context = LocalContext.current
    val launchIntent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_WIDGET_DESTINATION, launchDestination)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(widgetOuterPaddingDp(tier).dp)
            .clickable(actionStartActivity(launchIntent))
            .semantics { contentDescription = description },
    ) {
        if (tier.showsHeader) {
            Row(
                modifier = GlanceModifier.fillMaxWidth()
                    .padding(bottom = if (dense) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(widgetHeaderIconSizeDp(tier).dp),
                )
                if (tier.showsHeaderTitle) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = title,
                        maxLines = 1,
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                    )
                }
            }
        }
        content()
    }
}

/**
 * Groups a hero + follow-up rows into one cohesive card: the outer cornerRadius clips the
 * stacked children to a single rounded outline, so only the group's outer corners round and the
 * internal seams between items stay square. Items should carry no corner radius of their own and
 * be separated by [WidgetCardSeam]. cornerRadius/clipping applies on API 31+; on 26–30 the group
 * renders square (acceptable for MVP).
 */
@Composable
fun ColumnScope.WidgetCardGroup(tier: WidgetSizeTier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .defaultWeight()
            .cornerRadius(24.dp),
        content = content,
    )
}

/** Thin surface-colored divider between items inside a [WidgetCardGroup], reading as a square seam. */
@Composable
fun WidgetCardSeam() {
    Spacer(GlanceModifier.fillMaxWidth().height(2.dp).background(GlanceTheme.colors.surface))
}

/**
 * Prominent "next up" card mirroring the in-app Transporte hero: big [headline], a [primary]
 * line, and an optional muted [secondary] line, on a primaryContainer surface. [heroIconRes] is
 * shown inline when the shell header is hidden. Corners come from the enclosing [WidgetCardGroup],
 * not this item.
 */
@Composable
fun ColumnScope.HeroItem(
    headline: String,
    primary: String,
    secondary: String?,
    heroIconRes: Int?,
    tier: WidgetSizeTier,
    content: HeroItemContent,
) {
    val presentation = heroItemPresentation(tier, content)
    val padding = heroPaddingDp(tier)
    val bodyFontSize = presentation.bodySp?.sp
    val primaryIsBadge = presentation.badgeSlot == HeroBadgeSlot.PRIMARY
    val secondaryIsBadge = presentation.badgeSlot == HeroBadgeSlot.SECONDARY
    val badgeModifier = GlanceModifier
        .background(GlanceTheme.colors.primary)
        .cornerRadius(12.dp)
        .padding(horizontal = 8.dp, vertical = 3.dp)
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .defaultWeight()
            .background(GlanceTheme.colors.primaryContainer)
            .padding(horizontal = padding.horizontal.dp, vertical = padding.vertical.dp),
        verticalAlignment = if (tier.heroTopAligned) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (presentation.showInlineIcon && heroIconRes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(heroIconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(16.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = headline,
                    maxLines = presentation.headlineMaxLines,
                    style = TextStyle(
                        fontSize = presentation.headlineSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onPrimaryContainer,
                    ),
                )
            }
        } else {
            Text(
                text = headline,
                maxLines = presentation.headlineMaxLines,
                style = TextStyle(
                    fontSize = presentation.headlineSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onPrimaryContainer,
                ),
            )
        }
        if (presentation.showInlineIcon) {
            Spacer(GlanceModifier.height(2.dp))
        }
        if (primaryIsBadge) {
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            text = primary,
            maxLines = if (primaryIsBadge) 1 else presentation.primaryMaxLines,
            style = TextStyle(
                fontSize = bodyFontSize,
                fontWeight = FontWeight.Medium,
                color = if (primaryIsBadge) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onPrimaryContainer,
            ),
            modifier = if (primaryIsBadge) badgeModifier else GlanceModifier,
        )
        if (presentation.showSecondary && !secondary.isNullOrBlank()) {
            if (secondaryIsBadge) {
                Spacer(GlanceModifier.height(4.dp))
            }
            Text(
                text = secondary,
                maxLines = if (secondaryIsBadge) 1 else presentation.secondaryMaxLines,
                style = TextStyle(
                    fontSize = bodyFontSize,
                    color = if (secondaryIsBadge) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onPrimaryContainer,
                ),
                modifier = if (secondaryIsBadge) badgeModifier else GlanceModifier,
            )
        }
    }
}

/**
 * Compact follow-up row (subsequent departures/events) on a secondaryContainer. Corners come from
 * the enclosing [WidgetCardGroup]; rows are separated by [WidgetCardSeam].
 */
@Composable
fun FollowupRow(leading: String, trailing: String, tier: WidgetSizeTier) {
    val dense = tier != WidgetSizeTier.TALL
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(horizontal = if (dense) 12.dp else 14.dp, vertical = if (dense) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leading,
            maxLines = 1,
            style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSecondaryContainer),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (tier.followupShowsTrailing) {
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = trailing,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer),
            )
        }
    }
}

/** Empty-state line shared by all widgets. */
@Composable
fun ColumnScope.WidgetEmpty(message: String, tier: WidgetSizeTier) {
    if (tier == WidgetSizeTier.TALL) {
        Text(
            text = message,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.padding(top = 8.dp),
        )
    } else {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(24.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer))
        }
    }
}
