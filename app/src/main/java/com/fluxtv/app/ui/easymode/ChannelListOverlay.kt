package com.fluxtv.app.ui.easymode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.fluxtv.app.ui.ListTab
import com.fluxtv.app.ui.components.ChannelRow
import com.fluxtv.app.ui.components.FluxChip
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxMuted
import kotlinx.coroutines.delay

/**
 * Deliberately simple channel list (§8). A left panel keeps the picture visible behind it so
 * opening the list does not interrupt viewing (REQ-CH-003). The current channel is scrolled to and
 * focused, and ◀ ▶ switch between the current category (when it has one), All and Favorites.
 */
@Composable
fun ChannelListOverlay(
    allChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    categoryChannels: List<Channel>,
    categoryName: String?,
    favorites: Set<String>,
    currentId: String?,
    tab: ListTab,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onSwitchTab: (ListTab) -> Unit
) {
    // Left-to-right order of the tabs; the category tab only exists when the channel has one.
    val tabs = if (categoryName != null) listOf(ListTab.CATEGORY, ListTab.ALL, ListTab.FAVORITES)
    else listOf(ListTab.ALL, ListTab.FAVORITES)
    val list = when (tab) {
        ListTab.CATEGORY -> categoryChannels
        ListTab.FAVORITES -> favoriteChannels
        ListTab.ALL -> allChannels
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val focusId = currentId?.takeIf { id -> list.any { it.stableId == id } } ?: list.firstOrNull()?.stableId

    LaunchedEffect(tab, list.size) {
        val index = list.indexOfFirst { it.stableId == focusId }.coerceAtLeast(0)
        if (list.isNotEmpty()) {
            listState.scrollToItem(index)
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
                            Key.DirectionLeft -> { tabs.getOrNull(tabs.indexOf(tab) - 1)?.let(onSwitchTab); true }
                            Key.DirectionRight -> { tabs.getOrNull(tabs.indexOf(tab) + 1)?.let(onSwitchTab); true }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .overscan()
        ) {
            SectionTitle("Channels")
            Spacer(Modifier.height(16.dp))
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
                    "OK: watch   •   Hold OK: favorite   •   ◀ ▶: switch list   •   Back: close",
                    style = TextStyle(color = FluxMuted, fontSize = 16.sp)
                )
            }
        }
    }
}
