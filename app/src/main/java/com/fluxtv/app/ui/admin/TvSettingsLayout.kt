package com.fluxtv.app.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import com.fluxtv.app.AppName
import com.fluxtv.app.ui.components.InfoRow
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.components.SwitchMark
import com.fluxtv.app.ui.easymode.ClockText
import com.fluxtv.app.ui.easymode.InstantScroll
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxBackground
import com.fluxtv.app.ui.theme.FluxFocus
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurface
import com.fluxtv.app.ui.theme.FluxSurfaceVariant
import com.fluxtv.app.ui.theme.FluxWarning

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
            SectionTitle("${AppName} Settings", Modifier.weight(1f))
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
    TvPanel(
        title = entry.title,
        // U+FE0E: the plain sign, never an emoji.
        hint = if (entry.opens) "OK or \u25B6\uFE0E: open   •   Back: return to TV" else "OK: ${entry.title.lowercaseFirst()}",
        modifier = modifier,
        icon = entry.icon
    ) {
        TvPanelText(entry.description)
        if (entry.facts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            entry.facts.forEach { (label, value) -> InfoRow(label, value) }
        }
    }
}

/**
 * The panel at the right of a two-pane screen of Settings on a TV: [title] (with [icon], if any),
 * [content], and at the foot [hint], what the remote's keys do. [content] scrolls if it must, so
 * that a field or button in it can always be reached and the hint is never pushed out; it starts
 * at its top for each new [title].
 */
@Composable
internal fun TvPanel(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(FluxSurface)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(FluxSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        colorFilter = ColorFilter.tint(FluxAccent)
                    )
                }
                Spacer(Modifier.width(16.dp))
            }
            BasicText(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = FluxOnSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.height(16.dp))
        val scroll = remember(title) { ScrollState(0) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), content = content)
        Spacer(Modifier.height(10.dp))
        TvKeyHint(hint)
    }
}

/** A paragraph of a [TvPanel]: what a setting is for. */
@Composable
internal fun TvPanelText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, style = TextStyle(color = FluxMuted, fontSize = 18.sp, lineHeight = 25.sp))
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

/** What a setting in a TV list is, which decides how its line looks and what the remote does on it. */
internal sealed interface TvSettingKind {
    /** On or off: OK switches it. */
    class Toggle(val on: Boolean, val onToggle: (Boolean) -> Unit) : TvSettingKind

    /** One of a few [options]: ◀ ▶ step through them, OK goes to the next. */
    class Choice(val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : TvSettingKind

    /** Typed in its panel ([TvSettingItem.panel]): OK or ▶ goes there. [value]: what it is now. */
    class Edit(val value: String) : TvSettingKind
}

/**
 * What a [TvSettingItem.panel] needs: [focusRequester], for the first field or button in it, which
 * OK or ▶ on the setting's line moves to; [done], to bring the focus back to that line.
 */
internal class TvPanelControls(val focusRequester: FocusRequester, val done: () -> Unit)

/**
 * One setting of a two-pane list ([TvSettingsList]). [label]: its short name, in the list; [title]:
 * its full name, at the top of its panel; [description]: what it does, in the panel. [note]: a word of
 * warning under the label, while something it needs is missing. [panel]: what else its panel holds, a
 * field and buttons for an [TvSettingKind.Edit]; [interactive] when that holds something to focus,
 * which ▶ then reaches, [enterHint] saying what for.
 */
@Immutable
internal class TvSettingItem(
    val key: String,
    val label: String,
    val description: String,
    val kind: TvSettingKind,
    val title: String = label,
    val note: String? = null,
    val interactive: Boolean = kind is TvSettingKind.Edit,
    val enterHint: String = "change",
    val panel: (@Composable ColumnScope.(TvPanelControls) -> Unit)? = null
)

/**
 * A screen of settings on a TV in two panes, as the first screen of Settings: the settings down the
 * left, one line each with its state (On / Off and a switch, the option chosen, or the value typed),
 * and on the right a panel about the one the remote is on: what it does, the options, and the field
 * and buttons of a setting that is typed. Explanations live in the panel rather than under each
 * setting, so the list stays short enough to take in from the sofa.
 *
 * On the list, ▲ ▼ move from setting to setting and never leave it; OK switches or changes the one in
 * focus, ◀ ▶ step through the options of a choice, and OK or ▶ on a setting that is typed goes into its
 * panel. There, ◀ from the first control or Back returns to the setting's line; ▲ ▼ stay in the panel.
 * Back from the list leaves the screen (the caller's Back). The focus starts on [initialKey], or else on
 * the first setting.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSettingsList(
    title: String,
    items: List<TvSettingItem>,
    modifier: Modifier = Modifier,
    initialKey: String? = null
) {
    val rowRequesters = remember { HashMap<String, FocusRequester>() }
    val panelFocus = remember { FocusRequester() }
    var selectedKey by remember { mutableStateOf(items.firstOrNull { it.key == initialKey }?.key ?: items.firstOrNull()?.key) }
    var inPanel by remember { mutableStateOf(false) }
    val selected = items.firstOrNull { it.key == selectedKey } ?: items.firstOrNull()
    val backToList: () -> Unit = { selected?.let { rowRequesters[it.key] }?.let { runCatching { it.requestFocus() } } }
    LaunchedEffect(Unit) { selectedKey?.let { rowRequesters[it] }?.let { runCatching { it.requestFocus() } } }
    // Before the Back of the screen, registered earlier: in the panel, Back goes back to the list only.
    BackHandler(enabled = inPanel) { backToList() }

    Row(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth(LIST_FRACTION).fillMaxHeight()) {
            SectionTitle(title)
            Spacer(Modifier.height(16.dp))
            val listScroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                InstantScroll {
                    Column(
                        Modifier
                            .fillMaxSize()
                            // The list keeps the focus: only OK or ▶ on a typed setting leaves it.
                            .focusProperties { exit = { FocusRequester.Cancel } }
                            .focusGroup()
                            .verticalScroll(listScroll),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items.forEach { item ->
                            key(item.key) {
                                TvSettingRow(
                                    item,
                                    focusRequester = rowRequesters.getOrPut(item.key) { FocusRequester() },
                                    onFocused = { selectedKey = item.key },
                                    onEnterPanel = { runCatching { panelFocus.requestFocus() } }
                                )
                            }
                        }
                    }
                }
                ScrollFades(listScroll)
            }
        }
        Spacer(Modifier.width(32.dp))
        if (selected != null) {
            TvPanel(
                title = selected.title,
                hint = if (inPanel) "\u25C0\uFE0E or Back: return to the list" else listHint(selected),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { inPanel = it.hasFocus }
                    // ◀ out of the panel lands on the setting it is about; nothing else leaves it.
                    .focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Left) {
                                rowRequesters[selected.key] ?: FocusRequester.Default
                            } else {
                                FocusRequester.Cancel
                            }
                        }
                    }
                    .focusGroup()
            ) {
                TvPanelText(selected.description)
                val kind = selected.kind
                if (kind is TvSettingKind.Choice) {
                    Spacer(Modifier.height(14.dp))
                    kind.options.forEachIndexed { index, option -> TvOptionLine(option, index == kind.selected) }
                }
                selected.panel?.let { panel ->
                    Spacer(Modifier.height(16.dp))
                    panel(TvPanelControls(panelFocus, backToList))
                }
            }
        }
    }
}

/** The share of the width the list of settings takes; the panel has the rest. */
private const val LIST_FRACTION = MENU_FRACTION

/** What the remote does on [item]'s line, at the foot of its panel. */
private fun listHint(item: TvSettingItem): String {
    val keys = when (val kind = item.kind) {
        is TvSettingKind.Toggle ->
            "OK: turn ${if (kind.on) "off" else "on"}" +
                if (item.interactive) "   •   \u25B6\uFE0E: ${item.enterHint}" else ""
        is TvSettingKind.Choice -> "\u25C0\uFE0E \u25B6\uFE0E or OK: change"
        is TvSettingKind.Edit -> "OK or \u25B6\uFE0E: ${item.enterHint}"
    }
    return "$keys   •   Back: Settings"
}

/**
 * One line of a [TvSettingsList]: the setting's label (and [TvSettingItem.note] under it), and its
 * state at the right. Nothing behind it until the remote is on it, then the accent fill and white
 * border of every focused control (REQ-SEN-015).
 */
@Composable
private fun TvSettingRow(
    item: TvSettingItem,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onEnterPanel: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val content = if (focused) OnAccent else FluxOnSurface
    val kind = item.kind
    val action: Modifier = when (kind) {
        is TvSettingKind.Toggle -> Modifier.toggleable(value = kind.on, role = Role.Switch, onValueChange = kind.onToggle)
        is TvSettingKind.Choice -> Modifier.clickable(role = Role.Button) {
            kind.onSelect((kind.selected + 1) % kind.options.size)
        }
        is TvSettingKind.Edit -> Modifier.clickable(role = Role.Button, onClick = onEnterPanel)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    kind is TvSettingKind.Choice && event.key == Key.DirectionLeft -> {
                        if (kind.selected > 0) kind.onSelect(kind.selected - 1)
                        true
                    }
                    kind is TvSettingKind.Choice && event.key == Key.DirectionRight -> {
                        if (kind.selected < kind.options.lastIndex) kind.onSelect(kind.selected + 1)
                        true
                    }
                    item.interactive && event.key == Key.DirectionRight -> {
                        if (event.nativeKeyEvent.repeatCount == 0) onEnterPanel()
                        true
                    }
                    else -> false
                }
            }
            .clip(shape)
            .then(action)
            .background(if (focused) FluxAccent else Color.Transparent)
            .border(3.dp, if (focused) FluxFocus else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                item.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            )
            item.note?.let { note ->
                BasicText(
                    note,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (focused) OnAccent else FluxWarning, fontSize = 15.sp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val valueStyle = TextStyle(
            color = if (focused) OnAccent else FluxMuted,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        when (kind) {
            is TvSettingKind.Toggle -> {
                BasicText(if (kind.on) "On" else "Off", maxLines = 1, softWrap = false, style = valueStyle)
                Spacer(Modifier.width(10.dp))
                SwitchMark(kind.on, onAccent = focused)
            }
            is TvSettingKind.Choice -> {
                // The arrows show while the remote is on the line, dimmed where there is no further option.
                StepArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, shown = focused, enabled = kind.selected > 0)
                BasicText(
                    kind.options.getOrElse(kind.selected) { "" },
                    modifier = Modifier.widthIn(max = 160.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = valueStyle
                )
                StepArrow(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    shown = focused,
                    enabled = kind.selected < kind.options.lastIndex
                )
            }
            is TvSettingKind.Edit -> {
                BasicText(
                    kind.value,
                    modifier = Modifier.widthIn(max = 160.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = valueStyle
                )
                Spacer(Modifier.width(4.dp))
                Image(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(if (focused) OnAccent else FluxMuted)
                )
            }
        }
    }
}

@Composable
private fun StepArrow(icon: ImageVector, shown: Boolean, enabled: Boolean) {
    Image(
        icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp).alpha(if (!shown) 0f else if (enabled) 1f else 0.3f),
        colorFilter = ColorFilter.tint(OnAccent)
    )
}

/** One option of a choice in its panel; the one chosen marked ✓ and in white. */
@Composable
private fun TvOptionLine(text: String, chosen: Boolean) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (chosen) {
                Image(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(FluxAccent)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            text,
            style = TextStyle(
                color = if (chosen) FluxOnSurface else FluxMuted,
                fontSize = 19.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

/**
 * A fade at the top and bottom of a scrolling list while it goes on past them, so the line cut by the
 * edge reads as "more this way" rather than as cut off. Reads the scroll position here only, so the
 * list is not recomposed as it scrolls.
 */
@Composable
private fun BoxScope.ScrollFades(state: ScrollState) {
    if (state.canScrollBackward) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(FluxBackground, Color.Transparent)))
        )
    }
    if (state.canScrollForward) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, FluxBackground)))
        )
    }
}
