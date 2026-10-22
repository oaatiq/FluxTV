package com.fluxtv.app.ui.easymode

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.ui.CategoryInfo
import com.fluxtv.app.ui.ListTab
import com.fluxtv.app.ui.components.CategoryRow
import com.fluxtv.app.ui.components.ChannelRow
import com.fluxtv.app.ui.components.FluxChip
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxMuted
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Makes the lists inside [content] jump straight to the focused row instead of Compose's default
 * spring animation. D-pad scrolling is a series of one-row steps, and on the MiTV the animation trails
 * behind the focus and costs extra frames, so the list feels sluggish; moving instantly feels snappy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstantScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides InstantBringIntoViewSpec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private object InstantBringIntoViewSpec : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()

    // Same distance as the default (bring the row just into view); only the animation is dropped.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= 0 && trailing <= containerSize -> 0f
            leading < 0 && trailing > containerSize -> 0f
            abs(leading) < abs(trailing - containerSize) -> leading
            else -> trailing - containerSize
        }
    }
}

/**
 * Deliberately simple channel list (§8). A left panel keeps the picture visible behind it so
 * opening the list does not interrupt viewing (REQ-CH-003). The current channel is scrolled to and
 * focused. ◀ ▶ move across Categories (every category), the current category (when it has one),
 * All and Favorites; picking a category in the Categories view shows its channels.
 */
@Composable
fun ChannelListOverlay(
    allChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    categoryChannels: List<Channel>,
    categoryName: String?,
    categories: List<CategoryInfo>,
    favorites: Set<String>,
    currentId: String?,
    tab: ListTab,
    onSelect: (Channel) -> Unit,
    onSelectCategory: (String) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onSwitchTab: (ListTab) -> Unit
) {
    // Left-to-right order of the views; the category tab only exists when there is a category to show.
    val tabs = if (categoryName != null) listOf(ListTab.CATEGORY, ListTab.ALL, ListTab.FAVORITES)
    else listOf(ListTab.ALL, ListTab.FAVORITES)
    val order = listOf(ListTab.CATEGORIES) + tabs
    val inCategories = tab == ListTab.CATEGORIES

    val list = when (tab) {
        ListTab.CATEGORIES -> emptyList()
        ListTab.CATEGORY -> categoryChannels
        ListTab.FAVORITES -> favoriteChannels
        ListTab.ALL -> allChannels
    }
    val watchingGroup = remember(allChannels, currentId) {
        allChannels.firstOrNull { it.stableId == currentId }?.group
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val focusId = currentId?.takeIf { id -> list.any { it.stableId == id } } ?: list.firstOrNull()?.stableId
    val itemCount = if (inCategories) categories.size else list.size
    val focusIndex = if (inCategories) categories.indexOfFirst { it.name == categoryName }
    else list.indexOfFirst { it.stableId == focusId }

    LaunchedEffect(tab, itemCount) {
        if (itemCount > 0) {
            listState.scrollToItem(focusIndex.coerceAtLeast(0))
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(540.dp)
                .background(Color(0xF2080C14))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionLeft -> { order.getOrNull(order.indexOf(tab) - 1)?.let(onSwitchTab); true }
                            Key.DirectionRight -> { order.getOrNull(order.indexOf(tab) + 1)?.let(onSwitchTab); true }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .overscan()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(if (inCategories) "Categories" else "Channels")
                Spacer(Modifier.weight(1f))
                BasicText(
                    if (inCategories) "Channels ▶" else "◀ Categories",
                    style = TextStyle(color = FluxMuted, fontSize = 18.sp)
                )
            }
            Spacer(Modifier.height(16.dp))
            if (inCategories) {
                if (categories.isEmpty()) {
                    BasicText("No categories.", style = TextStyle(color = FluxMuted, fontSize = 22.sp))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        itemsIndexed(categories, key = { _, category -> category.name }) { index, category ->
                            CategoryRow(
                                name = category.name,
                                count = category.count,
                                isWatching = category.name == watchingGroup,
                                modifier = if (index == focusIndex.coerceAtLeast(0)) Modifier.focusRequester(focusRequester) else Modifier,
                                onClick = { onSelectCategory(category.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        "OK: open   •   ▶: channels   •   Back: exit",
                        style = TextStyle(color = FluxMuted, fontSize = 16.sp)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (categoryName != null) {
                        // Shrinks (with an ellipsis) rather than pushing the other chips off the panel.
                        FluxChip(categoryName, tab == ListTab.CATEGORY, Modifier.weight(1f, fill = false)) {
                            onSwitchTab(ListTab.CATEGORY)
                        }
                    }
                    FluxChip("All channels", tab == ListTab.ALL) { onSwitchTab(ListTab.ALL) }
                    FluxChip("Favorites", tab == ListTab.FAVORITES) { onSwitchTab(ListTab.FAVORITES) }
                }
                Spacer(Modifier.height(16.dp))
                if (list.isEmpty()) {
                    BasicText(
                        if (tab == ListTab.FAVORITES) "No favorite channels yet." else "No channels.",
                        style = TextStyle(color = FluxMuted, fontSize = 22.sp)
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        itemsIndexed(list, key = { _, channel -> channel.stableId }) { _, channel ->
                            ChannelRow(
                                number = channel.number,
                                name = channel.name,
                                logoUrl = channel.logoUrl,
                                isCurrent = channel.stableId == currentId,
                                isFavorite = channel.stableId in favorites,
                                modifier = if (channel.stableId == focusId) Modifier.focusRequester(focusRequester) else Modifier,
                                onClick = { onSelect(channel) },
                                onLongClick = { onToggleFavorite(channel) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        "OK: watch   •   Hold OK: favorite   •   ◀ ▶: switch list   •   Back: categories",
                        style = TextStyle(color = FluxMuted, fontSize = 16.sp)
                    )
                }
            }
        }
    }
}
