package com.fluxtv.app.ui.easymode

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.fluxtv.app.ui.BROWSE_ALL
import com.fluxtv.app.ui.BROWSE_FAVORITES
import com.fluxtv.app.ui.BrowsePane
import com.fluxtv.app.ui.components.CategoryRow
import com.fluxtv.app.ui.components.ChannelRow
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.LocalTextScale
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

private class CategoryEntry(val key: String, val name: String, val count: Int)

/** How long the D-pad rests on a category before its channels replace the ones on the right. */
private const val PREVIEW_DELAY_MS = 140L

/**
 * Two-pane channel list (§8): categories on the left, the channels of the highlighted category on the
 * right, both visible at once. A left panel keeps the picture visible behind it so opening the list
 * does not interrupt viewing (REQ-CH-003). It opens on the watched channel's category with the focus on
 * that channel. ◀ (or Back) moves to the categories; moving through them previews each category's
 * channels once the focus rests there, and OK or ▶ goes into the channels. Favorites and All channels are
 * the first two categories. The time is at the top right. With larger text (Preferences → Text size)
 * the panel and its columns grow with it, so names keep as much of their length as they can.
 */
@Composable
fun ChannelListOverlay(
    allChannels: List<Channel>,
    byGroup: Map<String, List<Channel>>,
    favorites: Set<String>,
    currentId: String?,
    startKey: String,
    pane: BrowsePane,
    onPaneChange: (BrowsePane) -> Unit,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit
) {
    // byGroup: the real categories in first-appearance order, grouped by the view model when the
    // playlist changes rather than here, on the main thread, every time the list opens.
    val favoriteCount = remember(allChannels, favorites) { allChannels.count { it.stableId in favorites } }
    val entries = remember(byGroup, allChannels.size, favoriteCount) {
        buildList {
            add(CategoryEntry(BROWSE_FAVORITES, "Favorites", favoriteCount))
            add(CategoryEntry(BROWSE_ALL, "All channels", allChannels.size))
            byGroup.forEach { (name, list) -> add(CategoryEntry(name, name, list.size)) }
        }
    }

    val firstKey = if (entries.any { it.key == startKey }) startKey else BROWSE_ALL
    var shownKey by remember { mutableStateOf(firstKey) }      // category whose channels are listed
    var focusedKey by remember { mutableStateOf(firstKey) }    // category the D-pad is on
    val channels = remember(shownKey, allChannels, favorites) {
        when (shownKey) {
            BROWSE_ALL -> allChannels
            BROWSE_FAVORITES -> allChannels.filter { it.stableId in favorites }
            else -> byGroup[shownKey].orEmpty()
        }
    }

    LaunchedEffect(focusedKey) {
        if (focusedKey != shownKey) {
            delay(PREVIEW_DELAY_MS)
            shownKey = focusedKey
        }
    }

    // Open on the watched channel; a freshly shown category starts at its top.
    val focusChannelId = currentId?.takeIf { id -> channels.any { it.stableId == id } } ?: channels.firstOrNull()?.stableId
    val categoryState = remember { LazyListState(firstVisibleItemIndex = entries.indexOfFirst { it.key == firstKey }.coerceAtLeast(0)) }
    val channelState = remember(shownKey) {
        LazyListState(firstVisibleItemIndex = channels.indexOfFirst { it.stableId == focusChannelId }.coerceAtLeast(0))
    }
    val categoryFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }

    LaunchedEffect(pane) {
        if (pane == BrowsePane.CATEGORIES) {
            categoryState.scrollToItem(entries.indexOfFirst { it.key == shownKey }.coerceAtLeast(0))
            delay(60)
            runCatching { categoryFocus.requestFocus() }
        } else if (channels.isNotEmpty()) {
            delay(60)
            runCatching { channelFocus.requestFocus() }
        }
    }

    val openCategory = { key: String ->
        shownKey = key
        onPaneChange(BrowsePane.CHANNELS)
    }

    val textScale = LocalTextScale.current
    val panelWidth = 700.dp + 320.dp * (textScale - 1f)
    val categoryWidth = 200.dp * (1f + 0.6f * (textScale - 1f))

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .background(Color(0xF2080C14))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (pane == BrowsePane.CHANNELS) onPaneChange(BrowsePane.CATEGORIES)
                                true
                            }
                            Key.DirectionRight -> {
                                if (pane == BrowsePane.CATEGORIES) openCategory(focusedKey)
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .overscan()
        ) {
            // The clock is measured first, so a long category name cannot push it off the panel.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    SectionTitle(
                        entries.firstOrNull { it.key == shownKey }?.name ?: "Channels",
                        Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(16.dp))
                    BasicText(
                        "${channels.size} channels",
                        modifier = Modifier.padding(bottom = 4.dp),
                        style = TextStyle(color = FluxMuted, fontSize = 18.sp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                ClockText()
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = categoryState, modifier = Modifier.width(categoryWidth).fillMaxHeight()) {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                        CategoryRow(
                            name = entry.name,
                            count = entry.count,
                            selected = entry.key == shownKey,
                            modifier = (if (entry.key == shownKey) Modifier.focusRequester(categoryFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedKey = entry.key },
                            onClick = { openCategory(entry.key) }
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Spacer(Modifier.width(2.dp).fillMaxHeight().background(Color(0x1AFFFFFF)))
                Spacer(Modifier.width(14.dp))
                if (channels.isEmpty()) {
                    BasicText(
                        if (shownKey == BROWSE_FAVORITES) "No favorite channels yet." else "No channels.",
                        style = TextStyle(color = FluxMuted, fontSize = 20.sp)
                    )
                } else {
                    LazyColumn(state = channelState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        itemsIndexed(channels, key = { _, channel -> channel.stableId }) { _, channel ->
                            ChannelRow(
                                number = channel.number,
                                name = channel.name,
                                logoUrl = channel.logoUrl,
                                isCurrent = channel.stableId == currentId,
                                isFavorite = channel.stableId in favorites,
                                modifier = if (channel.stableId == focusChannelId) Modifier.focusRequester(channelFocus) else Modifier,
                                onClick = { onSelect(channel) },
                                onLongClick = { onToggleFavorite(channel) },
                                numberWidth = 58.dp * textScale
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                if (pane == BrowsePane.CHANNELS) "OK: watch   •   Hold OK: favorite   •   ◀ / Back: categories"
                else "OK / ▶: open   •   Back: exit",
                style = TextStyle(color = FluxMuted, fontSize = 16.sp)
            )
        }
    }
}
