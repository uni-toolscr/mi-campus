package cr.micampus.app.feature

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Scrolls the lazy list tagged [listTag] until [matcher] is composed; `performScrollTo` can't reach items that aren't. */
fun ComposeContentTestRule.scrollListTo(listTag: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
    onNodeWithTag(listTag).performScrollToNode(matcher)
    return onNode(matcher)
}

fun ComposeContentTestRule.scrollSettingsToText(text: String) = scrollListTo("settings-list", hasText(text))

fun ComposeContentTestRule.scrollSettingsToTag(tag: String) = scrollListTo("settings-list", hasTestTag(tag))
