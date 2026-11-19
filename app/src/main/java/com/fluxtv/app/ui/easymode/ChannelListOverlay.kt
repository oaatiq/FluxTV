package com.fluxtv.app.ui.easymode

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.data.guide.Guide
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.ui.BROWSE_ALL
import com.fluxtv.app.ui.BROWSE_FAVORITES
import com.fluxtv.app.ui.BrowsePane
import com.fluxtv.app.ui.components.CategoryRow
import com.fluxtv.app.ui.components.ChannelRow
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxFocus
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurfaceVariant
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

/** One category of the channel list, on the TV and on a phone: its key, its name and its channel count. */
internal class CategoryEntry(val key: String, val name: String, val count: Int)

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
 *
 * At the far left, ◀ from the categories reaches a Settings button ([onOpenSettings]): Settings with
 * nothing but the arrows and OK, for the remotes that send no MENU key and no long press of OK (a
 * TV's own remote through HDMI-CEC, an LG or Samsung one among them). It is the same Settings as
 * hold OK and MENU, behind the same PIN.
 *
 * [guide]: when given, each channel that has it shows what is on now after its name (EPG-06); null
 * when Preferences turns that off. Only the rows on screen look it up, once a minute.
 *
 * [connection]: a lost or slow connection, said under the title while it lasts: it is why channels
 * picked now may not start, and the panel covers the corner of the screen where it is said otherwise.
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
    onToggleFavorite: (Channel) -> Unit,
    onOpenSettings: () -> Unit,
    guide: Guide? = null,
    connection: ConnectionNotice? = null
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
    val settingsFocus = remember { FocusRequester() }

    LaunchedEffect(pane) {
        when (pane) {
            BrowsePane.SETTINGS -> {
                delay(60)
                runCatching { settingsFocus.requestFocus() }
            }
            BrowsePane.CATEGORIES -> {
                categoryState.scrollToItem(entries.indexOfFirst { it.key == shownKey }.coerceAtLeast(0))
                delay(60)
                runCatching { categoryFocus.requestFocus() }
            }
            BrowsePane.CHANNELS -> if (channels.isNotEmpty()) {
                delay(60)
                runCatching { channelFocus.requestFocus() }
            }
        }
    }

    val openCategory = { key: String ->
        shownKey = key
        onPaneChange(BrowsePane.CHANNELS)
    }

    // Only ticks while there is a guide to read: without one the list never recomposes for the time.
    val now = if (guide != null && guide.channelCount > 0) rememberMinuteClock() else 0L

    val textScale = LocalTextScale.current
    // The rail grows with the text so its "Settings" label keeps fitting under the button. The panel
    // is as wide as before plus the rail; at the largest text it can reach the edge of the screen,
    // where the width gives way and the channels column takes what is left.
    val railWidth = 60.dp * textScale
    val panelWidth = 700.dp + 320.dp * (textScale - 1f) + railWidth + RAIL_GAP
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
                                when (pane) {
                                    BrowsePane.CHANNELS -> onPaneChange(BrowsePane.CATEGORIES)
                                    // A fresh press only: a ◀ held down to reach the categories does
                                    // not run on into Settings.
                                    BrowsePane.CATEGORIES ->
                                        if (event.nativeKeyEvent.repeatCount == 0) onPaneChange(BrowsePane.SETTINGS)
                                    BrowsePane.SETTINGS -> Unit
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                when (pane) {
                                    BrowsePane.SETTINGS -> onPaneChange(BrowsePane.CATEGORIES)
                                    BrowsePane.CATEGORIES -> openCategory(focusedKey)
                                    BrowsePane.CHANNELS -> Unit
                                }
                                true
                            }
                            // The rail holds one button: ▲ ▼ there must not wander into the categories.
                            Key.DirectionUp, Key.DirectionDown -> pane == BrowsePane.SETTINGS
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
            if (connection != null) {
                Spacer(Modifier.height(12.dp))
                ConnectionBadge(connection, background = FluxSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                SettingsRailButton(
                    modifier = Modifier.width(railWidth),
                    focusRequester = settingsFocus,
                    onClick = onOpenSettings
                )
                Spacer(Modifier.width(RAIL_GAP))
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
                            val programme = remember(guide, channel, now) {
                                if (now == 0L) null else guide?.nowAndNext(channel, now)?.now?.title
                            }
                            ChannelRow(
                                number = channel.number,
                                name = channel.name,
                                logoUrl = channel.logoUrl,
                                isCurrent = channel.stableId == currentId,
                                isFavorite = channel.stableId in favorites,
                                modifier = if (channel.stableId == focusChannelId) Modifier.focusRequester(channelFocus) else Modifier,
                                onClick = { onSelect(channel) },
                                onLongClick = { onToggleFavorite(channel) },
                                numberWidth = 58.dp * textScale,
                                programme = programme
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                when (pane) {
                    BrowsePane.CHANNELS -> "OK: watch   •   Hold OK: favorite   •   ◀: categories   •   Back: exit"
                    BrowsePane.CATEGORIES -> "OK / ▶: open   •   ◀: Settings   •   Back: exit"
                    BrowsePane.SETTINGS -> "OK: open Settings   •   ▶: categories   •   Back: exit"
                },
                style = TextStyle(color = FluxMuted, fontSize = 16.sp)
            )
        }
    }
}

/** Between the Settings rail and the categories column. */
private val RAIL_GAP = 14.dp

/**
 * The Settings button at the far left of the channel list, with its name under it so it reads as
 * Settings to whoever sets up the TV. Muted until the D-pad reaches it, then the accent fill and white
 * border of every focused control (REQ-SEN-015).
 */
@Composable
private fun SettingsRailButton(modifier: Modifier, focusRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .background(if (focused) FluxAccent else FluxSurfaceVariant)
                .border(3.dp, if (focused) FluxFocus else Color.Transparent, shape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(if (focused) OnAccent else FluxMuted)
            )
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Settings",
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                color = if (focused) FluxOnSurface else FluxMuted,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

private val OnAccent = Color(0xFF00131C)
