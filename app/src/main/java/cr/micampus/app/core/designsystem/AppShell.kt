package cr.micampus.app.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape

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

@Composable
fun FloatingNavToolbar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    onOpenChat: () -> Unit,
    aiEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ShellTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val selectionDuration = if (isSelected) 250 else 200
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        animationSpec = tween(selectionDuration, easing = FastOutSlowInEasing),
                        label = "${tab.name}-container",
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(selectionDuration, easing = FastOutSlowInEasing),
                        label = "${tab.name}-content",
                    )
                    Surface(
                        onClick = { onSelect(tab) },
                        shape = CircleShape,
                        color = containerColor,
                        contentColor = contentColor,
                        modifier = Modifier
                            .height(56.dp)
                            .defaultMinSize(minWidth = 48.dp)
                            .semantics {
                                role = Role.Tab
                                this.selected = isSelected
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(tabIcon(tab, isSelected), contentDescription = tab.label)
                            AnimatedVisibility(
                                visible = isSelected,
                                modifier = Modifier.weight(1f, fill = false),
                                enter = expandHorizontally(
                                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                                    expandFrom = Alignment.Start,
                                ) + fadeIn(animationSpec = tween(150, delayMillis = 50)),
                                exit = shrinkHorizontally(
                                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                                    shrinkTowards = Alignment.Start,
                                ) + fadeOut(animationSpec = tween(120)),
                            ) {
                                Text(
                                    tab.label,
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
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
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = "Chat con tus documentos",
                    modifier = Modifier.padding(20.dp),
                )
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
