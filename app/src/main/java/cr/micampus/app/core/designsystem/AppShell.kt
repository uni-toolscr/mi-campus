package cr.micampus.app.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ShellTab(val label: String) {
    HOME("Inicio"),
    CALENDAR("Calendario"),
    CONTENTS("Contenidos"),
    TRANSPORT("Transporte"),
}

@Composable
fun AppBackgroundSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/** Keeps the status bar legible over edge-to-edge lists that scroll beneath it. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)),
    )
}

/**
 * Hides the dock while content scrolls toward its end and brings it back on any scroll toward the
 * start. Attach [connection] with `Modifier.nestedScroll` around the screens.
 */
@Stable
class DockScrollState {
    var visible by mutableStateOf(true)

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Only react to scrolls a list actually performed, so a short screen that can't move keeps the dock.
            if (consumed.y < -DockScrollThreshold) visible = false
            if (consumed.y > DockScrollThreshold || available.y > DockScrollThreshold) visible = true
            return Offset.Zero
        }
    }
}

private const val DockScrollThreshold = 4f

/** Whether the dock is showing, so screen-level floating buttons can hide and return with it. */
val LocalDockVisible = compositionLocalOf { true }

@Composable
fun rememberDockScrollState(): DockScrollState = remember { DockScrollState() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingNavToolbar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    onOpenChat: () -> Unit,
    aiEnabled: Boolean,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val motion = MaterialTheme.motionScheme
    val haptics = LocalHapticFeedback.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it * 2 } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it * 2 } + fadeOut(motion.fastEffectsSpec()),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShellTab.entries.forEach { tab ->
                        val isSelected = tab == selected
                        val containerColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            animationSpec = motion.defaultEffectsSpec(),
                            label = "${tab.name}-container",
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            animationSpec = motion.defaultEffectsSpec(),
                            label = "${tab.name}-content",
                        )
                        Surface(
                            onClick = {
                                if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(tab)
                            },
                            shape = CircleShape,
                            color = containerColor,
                            contentColor = contentColor,
                            modifier = Modifier
                                .height(52.dp)
                                .defaultMinSize(minWidth = 48.dp)
                                .semantics {
                                    role = Role.Tab
                                    this.selected = isSelected
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(tabIcon(tab, isSelected), contentDescription = tab.label)
                                AnimatedVisibility(
                                    visible = isSelected,
                                    modifier = Modifier.weight(1f, fill = false),
                                    enter = expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.Start) + fadeIn(motion.defaultEffectsSpec()),
                                    exit = shrinkHorizontally(motion.fastSpatialSpec(), shrinkTowards = Alignment.Start) + fadeOut(motion.fastEffectsSpec()),
                                ) {
                                    Text(
                                        tab.label,
                                        modifier = Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (aiEnabled) {
                Surface(
                    onClick = onOpenChat,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "Chat con tus documentos")
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: ShellTab, selected: Boolean) = when (tab) {
    ShellTab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    ShellTab.CALENDAR -> if (selected) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth
    ShellTab.CONTENTS -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    ShellTab.TRANSPORT -> if (selected) Icons.Filled.DirectionsBus else Icons.Outlined.DirectionsBus
}
