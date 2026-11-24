package com.fluxtv.app.ui.admin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.ui.components.InfoRow
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.easymode.ClockText
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxFocus
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurface
import com.fluxtv.app.ui.theme.FluxSurfaceVariant

/**
 * One line of the Settings menu on a TV, and what the panel beside the menu says about it.
 *
 * [value]: the state of this part of Settings in a word or two, at the right of its line (the playlist
 * in use, the number of favorites, whether FluxTV is up to date); null where there is none worth it.
 * [facts]: where things stand, label and value, in the panel. [opens]: a part of Settings, marked ›
 * and opened by ▶ as well as OK, rather than an action done at once (Back to TV, Exit FluxTV).
 */
@Immutable
internal class TvMenuEntry(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val value: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    val opens: Boolean = true,
    val onClick: () -> Unit
)

/**
 * The first screen of Settings on a TV, laid out for the whole width of the screen and the remote.
 *
 * On the left, the menu: the five parts of Settings, then, set apart under a line, Back to TV and
 * Exit FluxTV. Every line is one ▲ ▼ press from the next, in a single column, so the remote never has
 * a grid to find its way through, and the menu fits a 720p screen without scrolling. On the right, a
 * panel about the line the remote is on: what that part of Settings is for and where things stand,
 * so the administrator sees the playlist in use, the favorites or the version before opening
 * anything. The time is at the top right, as in the channel list.
 *
 * The focus starts on [initialKey] (the part of Settings just left, so Back returns to where one
 * was), or else on the first line.
 */
@Composable
internal fun TvSettingsHome(
    sections: List<TvMenuEntry>,
    actions: List<TvMenuEntry>,
    initialKey: String?,
    modifier: Modifier = Modifier
) {
    val entries = remember(sections, actions) { sections + actions }
    val requesters = remember(entries.size) { List(entries.size) { FocusRequester() } }
    val startIndex = entries.indexOfFirst { it.key == initialKey }.coerceAtLeast(0)
    var focusedKey by remember { mutableStateOf(entries.getOrNull(startIndex)?.key) }
    LaunchedEffect(Unit) { runCatching { requesters[startIndex].requestFocus() } }
    val focused = entries.firstOrNull { it.key == focusedKey } ?: entries.firstOrNull()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionTitle("FluxTV Settings", Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            ClockText()
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Scrolls only where it must: a system font size larger than the TV's usual.
            Column(
                Modifier.fillMaxWidth(MENU_FRACTION).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entries.forEachIndexed { index, entry ->
                    if (index == sections.size && actions.isNotEmpty()) {
                        Box(
                            Modifier
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DividerColor)
                        )
                    }
                    TvMenuRow(entry, requesters[index], onFocused = { focusedKey = entry.key })
                }
            }
            Spacer(Modifier.width(32.dp))
            if (focused != null) TvDetailPanel(focused, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** The share of the width the menu takes; the panel about the line in focus has the rest. */
private const val MENU_FRACTION = 0.46f

private val DividerColor = Color(0x33FFFFFF)
private val OnAccent = Color(0xFF00131C)

/**
 * One line of the menu: its icon, its name, its state in a word or two, and › for a part of Settings.
 * Nothing behind it until the remote is on it, then the accent fill and white border of every focused
 * control (REQ-SEN-015), so the line in focus is the only bright one on the screen.
 */
@Composable
private fun TvMenuRow(entry: TvMenuEntry, focusRequester: FocusRequester, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val content = if (focused) OnAccent else FluxOnSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                // ▶ opens a part of Settings, as it opens a category in the channel list.
                if (entry.opens && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    if (event.nativeKeyEvent.repeatCount == 0) entry.onClick()
                    true
                } else {
                    false
                }
            }
            .clip(shape)
            .clickable(role = Role.Button, onClick = entry.onClick)
            .background(if (focused) FluxAccent else Color.Transparent)
            .border(3.dp, if (focused) FluxFocus else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            entry.icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            colorFilter = ColorFilter.tint(if (focused) OnAccent else FluxAccent)
        )
        Spacer(Modifier.width(16.dp))
        BasicText(
            entry.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        )
        entry.value?.let { value ->
            Spacer(Modifier.width(12.dp))
            BasicText(
                value,
                modifier = Modifier.widthIn(max = 150.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (focused) OnAccent else FluxMuted, fontSize = 17.sp)
            )
        }
        if (entry.opens) {
            Spacer(Modifier.width(6.dp))
            Image(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(if (focused) OnAccent else FluxMuted)
            )
        } else {
            // Lines up the values of the actions with those of the parts above them.
            Spacer(Modifier.width(32.dp))
        }
    }
}

/**
 * The panel beside the menu: the icon and name of the line in focus, what it is for, where things
 * stand, and at the bottom what OK (and ▶) does there and what Back does.
 */
@Composable
private fun TvDetailPanel(entry: TvMenuEntry, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(FluxSurface)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(FluxSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    entry.icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(FluxAccent)
                )
            }
            Spacer(Modifier.width(16.dp))
            BasicText(
                entry.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = FluxOnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.height(16.dp))
        // What may not fit is cut at the bottom of this part, never the hint under it.
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            BasicText(
                entry.description,
                style = TextStyle(color = FluxMuted, fontSize = 18.sp, lineHeight = 25.sp)
            )
            if (entry.facts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                entry.facts.forEach { (label, value) -> InfoRow(label, value) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // U+FE0E: the plain sign, never an emoji.
        TvKeyHint(
            if (entry.opens) "OK or \u25B6\uFE0E: open   •   Back: return to TV" else "OK: ${entry.title.lowercaseFirst()}"
        )
    }
}

private fun String.lowercaseFirst(): String = replaceFirstChar { it.lowercase() }

/**
 * Where a screen of Settings is on a TV, above its title: "Settings ›", or "Settings › Playlists ›"
 * for the screens opened from Playlists. The remote's Back goes up one step of it.
 */
@Composable
internal fun TvBreadcrumb(path: List<String>, modifier: Modifier = Modifier) {
    BasicText(
        path.joinToString(separator = "  ›  ", postfix = "  ›"),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = FluxAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    )
}

/** What the remote's keys do on a screen of Settings on a TV, at the foot of it, as the channel list says. */
@Composable
internal fun TvKeyHint(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = FluxMuted, fontSize = 16.sp)
    )
}
