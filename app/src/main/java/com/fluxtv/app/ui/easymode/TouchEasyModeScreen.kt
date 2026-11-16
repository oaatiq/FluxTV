package com.fluxtv.app.ui.easymode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.fluxtv.app.data.guide.Guide
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.playback.PlaybackProblem
import com.fluxtv.app.ui.AppViewModel
import com.fluxtv.app.ui.BROWSE_ALL
import com.fluxtv.app.ui.BROWSE_FAVORITES
import com.fluxtv.app.ui.EasyOverlay
import com.fluxtv.app.ui.UiState
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurface
import com.fluxtv.app.ui.theme.FluxSurfaceVariant
import com.fluxtv.app.ui.theme.FluxWarning
import com.fluxtv.app.ui.theme.LocalTextScale
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** How long the controls stay on the full-screen picture after the last touch. */
private const val CONTROLS_MS = 5_000L

/** How far a finger travels up or down the picture to change channel. */
private val SWIPE_DISTANCE = 56.dp

private val Scrim = Color(0xCC0A0F18)
private val PanelBackground = Color(0xF2080C14)
private val OnAccent = Color(0xFF00131C)

/**
 * What the channel list shows, kept above the two layouts so that turning the phone keeps it.
 * [key]: the category picked, or null for the one of the channel playing (as on the TV). [searching]:
 * the search field has the keyboard, so the upright layout gives the list the room the card had.
 */
@Stable
private class BrowseState {
    var key by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
}

/**
 * Easy Mode on a phone or tablet: the same channels, favorites and programmes as on the TV, worked by
 * touch instead of a remote, with nothing new to learn about playlists or streams.
 *
 * Held upright, the picture is at the top, the channel playing under it with Previous and Next, and
 * the channel list below that, always there: one tap on a channel watches it. Sideways, or with the
 * full-screen button, the picture fills the screen; a tap shows the controls, and the channel list
 * opens from them. Either way, swiping up or down on the picture goes to the next or previous channel
 * (▲ / ▼ on the remote), and pressing on a channel for a moment adds it to the favorites or takes it
 * out. [onFullscreen] turns the screen sideways (true) or back upright (false).
 */
@Composable
fun TouchEasyModeScreen(state: UiState, vm: AppViewModel, onFullscreen: (Boolean) -> Unit) {
    val player by vm.controller.activePlayer.collectAsStateWithLifecycle()
    val pausedBySystem by vm.controller.pausedBySystem.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    val browse = remember { BrowseState() }
    var controlsShown by remember { mutableStateOf(false) }
    // Bumped by every touch on the controls, so they stay while they are being used.
    var controlsTouches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, controlsTouches) {
        if (controlsShown) {
            delay(CONTROLS_MS)
            controlsShown = false
        }
    }
    val touched = { controlsTouches++ }

    val channel = state.currentChannel
    val isFavorite = channel != null && channel.stableId in state.favorites
    val recovery = state.playback.showRecoveryPrompt
    // Android's pause gets the Play button below rather than a notice.
    val notice = rememberStreamNotice(state.playback, pausedBySystem = false)
    val select: (Channel) -> Unit = {
        focusManager.clearFocus()
        vm.selectChannel(it)
    }
    val toggleFavorite: (Channel) -> Unit = { vm.toggleFavorite(it, announce = true) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        val screenWidth = maxWidth
        // Upright, the channels are always on screen, so a list opened sideways has nowhere to be.
        LaunchedEffect(portrait) {
            controlsShown = false
            if (portrait && vm.uiState.value.overlay == EasyOverlay.CHANNEL_LIST) vm.closeOverlay()
        }

        Column(Modifier.fillMaxSize()) {
            // The picture stays this one view in both layouts, only resized, so turning the phone
            // never tears down the video surface (which would re-tune the channel).
            Box(
                if (portrait) {
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .aspectRatio(16f / 9f)
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                PlayerSurface(
                    player = player,
                    onSurfaceReady = vm::onVideoSurfaceReady,
                    onSurfaceLost = vm::onVideoSurfaceLost,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().videoGestures(
                        onTap = {
                            focusManager.clearFocus()
                            if (!portrait) {
                                if (controlsShown) controlsShown = false else { controlsShown = true; touched() }
                            }
                        },
                        onSwipe = { up ->
                            if (controlsShown) touched()
                            if (up) vm.channelUp() else vm.channelDown()
                        }
                    )
                )

                if (!portrait && state.channels.isEmpty()) {
                    NoChannels(state, onSettings = vm::requestAdmin, Modifier.fillMaxSize().background(Scrim).overscan())
                }

                val problem = state.playback.problem ?: PlaybackProblem.UNAVAILABLE
                val listOpen = state.overlay != EasyOverlay.NONE
                when {
                    recovery && portrait -> CompactRecovery(problem, onRetry = vm::retryCurrent)
                    recovery && !listOpen -> RecoveryCard(
                        channel = state.playback.channel ?: channel,
                        problem = problem,
                        onRetry = vm::retryCurrent,
                        onPrevious = vm::channelDown,
                        onNext = vm::channelUp,
                        onChannels = vm::openChannelList
                    )
                    pausedBySystem && channel != null -> RoundButton(
                        "Play",
                        onClick = vm::resumePlayback,
                        modifier = Modifier.align(Alignment.Center),
                        size = 72.dp
                    ) { Glyph(Icons.Filled.PlayArrow, FluxOnSurface, 44.dp) }
                    notice != null && !listOpen -> StreamNoticeCard(
                        notice,
                        compact = true,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    )
                }

                // A lost or slow connection, at the top left of the picture while it lasts. Not with
                // a retry or the reconnecting screen, which show its icon themselves, nor under the
                // sideways controls, which take that corner for a moment.
                val connection = state.playback.connectionNotice()
                if (connection != null && !recovery && notice !is StreamNotice.Retrying && (portrait || !controlsShown)) {
                    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.TopStart) {
                        ConnectionBadge(connection, compact = true)
                    }
                }

                if (!portrait && !listOpen && !recovery) {
                    if (controlsShown) {
                        LandscapeControls(
                            channel = channel,
                            guide = guide,
                            isFavorite = isFavorite,
                            onPrevious = { touched(); vm.channelDown() },
                            onNext = { touched(); vm.channelUp() },
                            onChannels = { controlsShown = false; vm.openChannelList() },
                            onFavorite = { touched(); channel?.let(toggleFavorite) },
                            onSettings = vm::requestAdmin,
                            onLeaveFullscreen = { controlsShown = false; onFullscreen(false) }
                        )
                    } else if (state.showInfoBanner && channel != null) {
                        LandscapeBanner(channel, guide, isFavorite)
                    }
                }

                if (portrait && channel != null) {
                    RoundButton(
                        "Full screen",
                        onClick = { onFullscreen(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        size = 44.dp
                    ) { FullscreenGlyph(leave = false, color = FluxOnSurface, modifier = Modifier.size(20.dp)) }
                }

                if (!portrait && state.overlay == EasyOverlay.CHANNEL_LIST) {
                    LandscapeChannelPanel(
                        screenWidth = screenWidth,
                        state = state,
                        guide = guide,
                        browse = browse,
                        onSelect = select,
                        onToggleFavorite = toggleFavorite,
                        onClose = {
                            focusManager.clearFocus()
                            vm.closeOverlay()
                        }
                    )
                }

                state.transientMessage?.let {
                    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.TopCenter) { StatusPill(it) }
                }
            }

            if (portrait) {
                PortraitPanel(
                    state = state,
                    guide = guide,
                    browse = browse,
                    isFavorite = isFavorite,
                    vm = vm,
                    onSelect = select,
                    onToggleFavorite = toggleFavorite,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }

    // Back closes what is open; with nothing open it leaves FluxTV, as Back does in any phone app.
    BackHandler(enabled = state.overlay != EasyOverlay.NONE || controlsShown || browse.searching) {
        when {
            browse.searching -> focusManager.clearFocus()
            state.overlay != EasyOverlay.NONE -> vm.closeOverlay()
            else -> controlsShown = false
        }
    }
}

/**
 * Taps and up/down swipes on the picture. A swipe up goes to the next channel and a swipe down to the
 * previous one, like ▲ and ▼ on the remote.
 */
private fun Modifier.videoGestures(onTap: () -> Unit, onSwipe: (up: Boolean) -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    this
        .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
        .pointerInput(threshold) {
            var travelled = 0f
            detectVerticalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = { if (abs(travelled) >= threshold) swipe(travelled < 0f) },
                onDragCancel = { travelled = 0f },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    travelled += dy
                }
            )
        }
}

/** Upright: the channel playing, Previous / Next, and the channel list, under the picture. */
@Composable
private fun PortraitPanel(
    state: UiState,
    guide: Guide,
    browse: BrowseState,
    isFavorite: Boolean,
    vm: AppViewModel,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier
) {
    val channel = state.currentChannel
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        if (state.channels.isEmpty()) {
            NoChannels(state, onSettings = vm::requestAdmin, Modifier.fillMaxSize().padding(24.dp))
        } else {
            if (!browse.searching) {
                if (channel != null) {
                    NowPlaying(channel, guide, isFavorite, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton("Previous", Icons.AutoMirrored.Filled.KeyboardArrowLeft, Modifier.weight(1f), onClick = vm::channelDown)
                    PillButton("Next", Icons.AutoMirrored.Filled.KeyboardArrowRight, Modifier.weight(1f), iconAtEnd = true, onClick = vm::channelUp)
                    if (channel != null) FavoriteButton(isFavorite) { onToggleFavorite(channel) }
                    RoundButton("Settings", onClick = vm::requestAdmin) { Glyph(Icons.Filled.Settings, FluxOnSurface, 24.dp) }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            ChannelBrowser(state, guide, browse, onSelect, onToggleFavorite, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Sideways: the channel list over the left of the picture; a tap beside it closes it. */
@Composable
private fun LandscapeChannelPanel(
    screenWidth: Dp,
    state: UiState,
    guide: Guide,
    browse: BrowseState,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onClose: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    // Wider with larger text, so names keep their length; the picture always keeps a strip to tap.
    val width = maxOf(screenWidth * 0.55f, 380.dp * LocalTextScale.current).coerceAtMost(screenWidth * 0.85f)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .background(PanelBackground)
                // Taps between the rows only put the keyboard away; they must not reach the picture.
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .padding(top = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "Channels",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = FluxOnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                )
                ClockPill()
                Spacer(Modifier.width(8.dp))
                RoundButton("Close", onClick = onClose, size = 44.dp) { Glyph(Icons.Filled.Close, FluxOnSurface, 24.dp) }
            }
            Spacer(Modifier.height(8.dp))
            ChannelBrowser(state, guide, browse, onSelect, onToggleFavorite, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * A search field, the categories as a row of chips (Favorites and All channels first, as on the TV),
 * and the channels of the one picked. A search looks through all the channels, by name or number.
 */
@Composable
private fun ChannelBrowser(
    state: UiState,
    guide: Guide,
    browse: BrowseState,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier
) {
    val all = state.channels
    val favorites = state.favorites
    val favoriteCount = remember(all, favorites) { all.count { it.stableId in favorites } }
    val entries = remember(state.groups, all.size, favoriteCount) {
        buildList {
            add(CategoryEntry(BROWSE_FAVORITES, "Favorites", favoriteCount))
            add(CategoryEntry(BROWSE_ALL, "All channels", all.size))
            state.groups.forEach { (name, list) -> add(CategoryEntry(name, name, list.size)) }
        }
    }
    val requested = browse.key ?: state.browseStartKey
    val key = if (entries.any { it.key == requested }) requested else BROWSE_ALL
    val query = browse.query.trim()
    // Folded once per playlist, and only once someone searches.
    val searchNames = remember(all) { lazy(LazyThreadSafetyMode.NONE) { all.map { foldForSearch(it.name) } } }
    val shown = remember(key, query, all, favorites, state.groups) {
        if (query.isNotEmpty()) {
            val folded = foldForSearch(query)
            val names = searchNames.value
            all.filterIndexed { i, c -> names[i].contains(folded) || c.number.toString().startsWith(query) }
        } else {
            when (key) {
                BROWSE_ALL -> all
                BROWSE_FAVORITES -> all.filter { it.stableId in favorites }
                else -> state.groups[key].orEmpty()
            }
        }
    }

    Column(modifier) {
        SearchField(
            value = browse.query,
            onValueChange = { browse.query = it },
            onFocusChange = { browse.searching = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        if (query.isEmpty()) {
            CategoryChips(entries, key, onPick = { browse.key = it }, Modifier.fillMaxWidth().padding(vertical = 10.dp))
        } else {
            BasicText(
                if (shown.size == 1) "1 channel found" else "${shown.size} channels found",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = TextStyle(color = FluxMuted, fontSize = 14.sp)
            )
        }
        if (shown.isEmpty()) {
            BasicText(
                when {
                    query.isNotEmpty() -> "No channel matches “$query”."
                    key == BROWSE_FAVORITES -> "No favorite channels yet.\nPress and hold a channel to add it."
                    else -> "No channels."
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = TextStyle(color = FluxMuted, fontSize = 16.sp)
            )
        } else {
            ChannelColumn(
                channels = shown,
                all = all,
                listKey = "$key\u0000$query",
                currentId = state.currentChannel?.stableId,
                favorites = favorites,
                guide = guide.takeIf { state.settings.programmeInList },
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

/**
 * The channels, opening on the channel playing. A new category or search starts there too (or at
 * the top, when it is not in it), and a channel changed by a swipe is scrolled into view.
 */
@Composable
private fun ChannelColumn(
    channels: List<Channel>,
    all: List<Channel>,
    listKey: String,
    currentId: String?,
    favorites: Set<String>,
    guide: Guide?,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    val latest by rememberUpdatedState(channels)
    LaunchedEffect(listKey, all) {
        val index = latest.indexOfFirst { it.stableId == currentId }
        listState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    var followed by remember { mutableStateOf(currentId) }
    LaunchedEffect(currentId) {
        if (currentId == followed) return@LaunchedEffect
        followed = currentId
        val index = latest.indexOfFirst { it.stableId == currentId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    // Only ticks while there is a guide to read: without one the list never recomposes for the time.
    val now = if (guide != null && guide.channelCount > 0) rememberMinuteClock() else 0L
    val digits = remember(all) { (all.maxOfOrNull { it.number } ?: 0).toString().length }

    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(channels, key = { it.stableId }) { channel ->
            val programme = remember(guide, channel, now) {
                if (now == 0L) null else guide?.nowAndNext(channel, now)?.now?.title
            }
            TouchChannelRow(
                channel = channel,
                isCurrent = channel.stableId == currentId,
                isFavorite = channel.stableId in favorites,
                programme = programme,
                digits = digits,
                onClick = { onSelect(channel) },
                onLongClick = { onToggleFavorite(channel) }
            )
        }
    }
}

/**
 * One channel: number, logo and name, with what is on now under the name when the guide has it. The
 * channel playing is marked with ▶ and a lighter background; a favorite with ★. A tap watches it;
 * pressing on it for a moment adds it to the favorites or takes it out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TouchChannelRow(
    channel: Channel,
    isCurrent: Boolean,
    isFavorite: Boolean,
    programme: String?,
    digits: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val density = LocalDensity.current
    val logo = channel.logoUrl
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) FluxSurfaceVariant else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (isFavorite) "Remove from favorites" else "Add to favorites"
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Every row keeps the slot, so the numbers line up. U+FE0E: the plain sign, never an emoji.
        Box(Modifier.width(with(density) { 16.sp.toDp() }), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                BasicText("▶︎", style = TextStyle(color = FluxAccent, fontSize = 12.sp))
            }
        }
        BasicText(
            channel.number.toString(),
            modifier = Modifier.width(with(density) { (digits * 11 + 10).sp.toDp() }),
            maxLines = 1,
            style = TextStyle(
                color = if (isCurrent) FluxAccent else FluxOnSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        )
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = FluxOnSurface,
                    fontSize = 18.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                )
            )
            if (!programme.isNullOrBlank()) {
                BasicText(
                    programme,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = FluxMuted, fontSize = 14.sp)
                )
            }
        }
        if (isFavorite) {
            Spacer(Modifier.width(8.dp))
            BasicText("★", style = TextStyle(color = FluxWarning, fontSize = 18.sp))
        }
    }
}

@Composable
private fun CategoryChips(entries: List<CategoryEntry>, selected: String, onPick: (String) -> Unit, modifier: Modifier) {
    // Opens with the category shown in sight.
    val start = remember { entries.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    LazyRow(
        modifier = modifier,
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.key }) { entry ->
            val isSelected = entry.key == selected
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) FluxAccent else FluxSurfaceVariant)
                    .clickable(role = Role.Tab) { onPick(entry.key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    entry.name,
                    modifier = Modifier.widthIn(max = 200.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = if (isSelected) OnAccent else FluxOnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(Modifier.width(6.dp))
                BasicText(
                    entry.count.toString(),
                    style = TextStyle(color = if (isSelected) OnAccent.copy(alpha = 0.7f) else FluxMuted, fontSize = 13.sp)
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    // A field that goes while it has the keyboard (the phone turned, the list closed) may never hear
    // that it lost the focus; left set, "searching" would keep the card hidden and Back busy.
    val focusChange by rememberUpdatedState(onFocusChange)
    DisposableEffect(Unit) { onDispose { focusChange(false) } }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(40)) },
        singleLine = true,
        textStyle = TextStyle(color = FluxOnSurface, fontSize = 17.sp),
        cursorBrush = SolidColor(FluxAccent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .clip(shape)
            .background(FluxSurface)
            .border(2.dp, if (focused) FluxAccent else FluxSurfaceVariant, shape),
        decorationBox = { field ->
            Row(
                Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Glyph(Icons.Filled.Search, FluxMuted, 22.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        BasicText("Find a channel", style = TextStyle(color = FluxMuted, fontSize = 17.sp))
                    }
                    field()
                }
                if (value.isNotEmpty()) {
                    RoundButton("Clear", onClick = { onValueChange("") }, size = 40.dp, background = Color.Transparent) {
                        Glyph(Icons.Filled.Close, FluxMuted, 20.dp)
                    }
                }
            }
        }
    )
}

/**
 * The channel playing: number, logo, name, and what is on now and next when the programme guide has
 * it, with a thin bar under Now for how much of it has gone. Without a guide, its category.
 */
@Composable
private fun NowPlaying(channel: Channel, guide: Guide, isFavorite: Boolean, modifier: Modifier = Modifier) {
    val now = rememberMinuteClock()
    val nowNext = remember(guide, channel, now) { guide.nowAndNext(channel, now) }
    val timeFormat = rememberTimeFormat()
    val logo = channel.logoUrl
    val group = channel.group
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            channel.number.toString(),
            style = TextStyle(color = FluxAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.width(12.dp))
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    channel.name,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = FluxOnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    BasicText("★", style = TextStyle(color = FluxWarning, fontSize = 18.sp))
                }
            }
            val on = nowNext?.now
            val next = nowNext?.next
            if (on == null && next == null && !group.isNullOrBlank()) {
                BasicText(group, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = FluxMuted, fontSize = 14.sp))
            }
            if (on != null) {
                ProgrammeRow("Now", on.title, "${timeFormat.format(Date(on.startMs))} – ${timeFormat.format(Date(on.endMs))}", current = true)
                val length = (on.endMs - on.startMs).coerceAtLeast(1L)
                ProgrammeBar(((now - on.startMs).toFloat() / length).coerceIn(0f, 1f))
            }
            if (next != null) {
                ProgrammeRow("Next", next.title, timeFormat.format(Date(next.startMs)), current = false)
            }
        }
    }
}

private val ProgrammeLabelWidth = 44.sp

@Composable
private fun ProgrammeRow(label: String, title: String, time: String, current: Boolean) {
    val density = LocalDensity.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            label,
            modifier = Modifier.width(with(density) { ProgrammeLabelWidth.toDp() }),
            style = TextStyle(color = if (current) FluxAccent else FluxMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        BasicText(
            title,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = FluxOnSurface, fontSize = 15.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        )
        Spacer(Modifier.width(8.dp))
        BasicText(time, maxLines = 1, style = TextStyle(color = FluxMuted, fontSize = 13.sp))
    }
}

@Composable
private fun ProgrammeBar(fraction: Float) {
    val density = LocalDensity.current
    Box(
        Modifier
            .padding(start = with(density) { ProgrammeLabelWidth.toDp() }, top = 3.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(FluxAccent))
    }
}

/** Sideways, after a channel change: the channel playing at the bottom, the time at the top right. */
@Composable
private fun LandscapeBanner(channel: Channel, guide: Guide, isFavorite: Boolean) {
    Box(Modifier.fillMaxSize().overscan()) {
        ClockPill(Modifier.align(Alignment.TopEnd))
        NowPlaying(
            channel,
            guide,
            isFavorite,
            Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Scrim)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

/**
 * Sideways, after a tap on the picture: Previous, Channels and Next in the middle, the channel
 * playing at the bottom with its favorite star, and Settings and leaving full screen at the top.
 * A tap on the picture, or a few seconds untouched, hides them.
 */
@Composable
private fun LandscapeControls(
    channel: Channel?,
    guide: Guide,
    isFavorite: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onFavorite: () -> Unit,
    onSettings: () -> Unit,
    onLeaveFullscreen: () -> Unit
) {
    // No touch handling of its own: a tap between the buttons reaches the picture, which hides them.
    Box(Modifier.fillMaxSize().background(Color(0x8C000000)).overscan()) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("Leave full screen", onClick = onLeaveFullscreen) {
                FullscreenGlyph(leave = true, color = FluxOnSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            ClockPill()
            Spacer(Modifier.width(12.dp))
            RoundButton("Settings", onClick = onSettings) { Glyph(Icons.Filled.Settings, FluxOnSurface, 24.dp) }
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton("Previous channel", onClick = onPrevious, size = 64.dp) {
                Glyph(Icons.AutoMirrored.Filled.KeyboardArrowLeft, FluxOnSurface, 40.dp)
            }
            PillButton("Channels", Icons.AutoMirrored.Filled.List, primary = true, onClick = onChannels)
            RoundButton("Next channel", onClick = onNext, size = 64.dp) {
                Glyph(Icons.AutoMirrored.Filled.KeyboardArrowRight, FluxOnSurface, 40.dp)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel != null) {
                    NowPlaying(channel, guide, isFavorite, Modifier.weight(1f, fill = false).widthIn(max = 560.dp))
                    Spacer(Modifier.width(16.dp))
                    FavoriteButton(isFavorite, onClick = onFavorite)
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                "Swipe up or down on the picture to change channel",
                style = TextStyle(color = FluxMuted, fontSize = 13.sp)
            )
        }
    }
}

/**
 * Upright, when the channel could not be brought back: the reconnecting screen in small, and Try
 * again. The channel is marked in the list right below, which is also where another is chosen.
 */
@Composable
private fun CompactRecovery(problem: PlaybackProblem, onRetry: () -> Unit) {
    ReconnectingScreen(
        channel = null,
        problem = problem,
        compact = true,
        actions = { PillButton("Try again", Icons.Filled.Refresh, primary = true, onClick = onRetry) }
    )
}

/**
 * Sideways, when the channel could not be brought back (REQ-REC-005): the reconnecting screen,
 * with buttons under it, as a touch screen has no remote keys to try again or change channel with.
 */
@Composable
private fun RecoveryCard(
    channel: Channel?,
    problem: PlaybackProblem,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit
) {
    ReconnectingScreen(
        channel = channel,
        problem = problem,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Try again", Icons.Filled.Refresh, primary = true, onClick = onRetry)
                PillButton("Previous", Icons.AutoMirrored.Filled.KeyboardArrowLeft, onClick = onPrevious)
                PillButton("Next", Icons.AutoMirrored.Filled.KeyboardArrowRight, iconAtEnd = true, onClick = onNext)
                PillButton("Channels", Icons.AutoMirrored.Filled.List, onClick = onChannels)
            }
        }
    )
}

/** No channels to show yet: why, in words, and the way to Settings, where a playlist is added. */
@Composable
private fun NoChannels(state: UiState, onSettings: () -> Unit, modifier: Modifier) {
    val (title, detail) = when {
        state.isRefreshing -> "Loading channels…" to
            "The first time can take a few minutes. TV starts on its own when they arrive."
        state.firstLoadFailed -> "Can't load the channels" to
            "FluxTV keeps trying, and TV starts on its own when they arrive. If this lasts, check the " +
            "internet connection and the playlist address in Settings."
        else -> "No channels yet" to "Open Settings to add your playlist."
    }
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            title,
            style = TextStyle(color = FluxOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(10.dp))
        BasicText(detail, style = TextStyle(color = FluxMuted, fontSize = 16.sp, textAlign = TextAlign.Center))
        Spacer(Modifier.height(18.dp))
        PillButton("Settings", Icons.Filled.Settings, primary = true, onClick = onSettings)
    }
}

/**
 * A short message at the top, such as a favorite added. No moving dots: those mean "wait", and these
 * messages are done when they show (the stream's notices are [StreamNoticeCard]).
 */
@Composable
private fun StatusPill(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Scrim)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        BasicText(message, style = TextStyle(color = FluxOnSurface, fontSize = 16.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun ClockPill(modifier: Modifier = Modifier) {
    val now = rememberMinuteClock()
    val timeFormat = rememberTimeFormat()
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Scrim).padding(horizontal = 14.dp, vertical = 6.dp)) {
        BasicText(
            timeFormat.format(Date(now)),
            style = TextStyle(color = FluxOnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
    }
}

/** The star of the channel playing: gold when it is a favorite. What changed is also said in words. */
@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    RoundButton(if (isFavorite) "Remove from favorites" else "Add to favorites", onClick = onClick) {
        Glyph(Icons.Filled.Star, if (isFavorite) FluxWarning else FluxMuted, 26.dp)
    }
}

@Composable
private fun RoundButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    background: Color = FluxSurfaceVariant,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    iconAtEnd: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (primary) OnAccent else FluxOnSurface
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (primary) FluxAccent else FluxSurfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null && !iconAtEnd) {
            Glyph(icon, color, 24.dp)
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        )
        if (icon != null && iconAtEnd) {
            Spacer(Modifier.width(6.dp))
            Glyph(icon, color, 24.dp)
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color, size: Dp) {
    Image(icon, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * The full-screen sign, four corners: pointing out to go full screen, in to leave it. Drawn here, as
 * the small icon set FluxTV uses has none.
 */
@Composable
private fun FullscreenGlyph(leave: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val side = size.minDimension
        val stroke = side * 0.13f
        val edge = stroke / 2f
        val arm = side * 0.36f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Square)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Square)
        }
        if (!leave) {
            corner(edge, edge, 1f, 1f)
            corner(side - edge, edge, -1f, 1f)
            corner(edge, side - edge, 1f, -1f)
            corner(side - edge, side - edge, -1f, -1f)
        } else {
            val inner = arm + edge
            corner(inner, inner, -1f, -1f)
            corner(side - inner, inner, 1f, -1f)
            corner(inner, side - inner, -1f, 1f)
            corner(side - inner, side - inner, 1f, 1f)
        }
    }
}

private val CombiningMarks = Regex("\\p{Mn}+")

/** For search: "Météo" and "meteo" alike. */
private fun foldForSearch(text: String): String =
    CombiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
