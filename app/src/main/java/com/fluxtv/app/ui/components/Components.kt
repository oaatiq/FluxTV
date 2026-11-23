package com.fluxtv.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxFocus
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurface
import com.fluxtv.app.ui.theme.FluxSurfaceVariant
import com.fluxtv.app.ui.theme.FluxWarning
import com.fluxtv.app.ui.theme.LocalTextScale
import com.fluxtv.app.ui.theme.LocalTouchUi

private val OnAccent = Color(0xFF00131C)

/**
 * The margin that keeps what matters on screen. On a TV, the title-safe margin, so essential controls
 * survive overscan (REQ-DEV-005). On a phone or tablet, the system bars, the camera cutout and the
 * keyboard, with a little room besides.
 */
fun Modifier.overscan(): Modifier = composed {
    if (LocalTouchUi.current) {
        windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        padding(horizontal = 48.dp, vertical = 27.dp)
    }
}

/**
 * [tvFraction] of the width on a TV, where a full-width field or button would be hard to take in from
 * the sofa; the full width on a phone, which is narrow enough already.
 */
fun Modifier.formWidth(tvFraction: Float): Modifier = composed {
    if (LocalTouchUi.current) fillMaxWidth() else fillMaxWidth(tvFraction)
}

/**
 * Buttons side by side, on more lines where the screen is too narrow for them all, as a phone held
 * upright is. On a TV they fit one line, as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(modifier: Modifier = Modifier, spacing: Dp = 16.dp, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) { content() }
}

/** "← Back" at the top of a Settings screen on a phone, for whoever does not use the Back gesture. */
@Composable
fun TouchBackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(FluxOnSurface)
        )
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = TextStyle(color = FluxOnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = FluxOnSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    )
}

@Composable
fun FluxButton(
    text: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .clickable(onClick = onClick)
            .background(if (focused) FluxAccent else FluxSurfaceVariant)
            .border(3.dp, if (focused) FluxFocus else Color.Transparent, shape)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (focused) OnAccent else FluxOnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/**
 * A setting that is on or off. Its state always keeps its room at the right, however long the label:
 * a long label wraps onto more lines instead of squeezing the state out of sight, as it did on a
 * phone held upright. On a phone, the state is also a switch, as phones show one, and ON / OFF beside it.
 */
@Composable
fun FluxToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onToggle: (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val touch = LocalTouchUi.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
            .background(if (focused) FluxSurfaceVariant else FluxSurface)
            .border(
                if (touch) 2.dp else 3.dp,
                when {
                    focused -> FluxAccent
                    // On a phone an option that is on is outlined too, to be seen at a glance.
                    touch && checked -> FluxAccent.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
                shape
            )
            .padding(horizontal = if (touch) 18.dp else 24.dp, vertical = if (touch) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            label,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = FluxOnSurface, fontSize = if (touch) 19.sp else 22.sp)
        )
        Spacer(Modifier.width(16.dp))
        BasicText(
            if (checked) "ON" else "OFF",
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                color = if (checked) FluxAccent else FluxMuted,
                fontSize = if (touch) 16.sp else 22.sp,
                fontWeight = FontWeight.Bold
            )
        )
        if (touch) {
            Spacer(Modifier.width(10.dp))
            SwitchMark(checked)
        }
    }
}

/** The switch of a [FluxToggleRow] on a phone: filled, its knob to the right, when on. */
@Composable
private fun SwitchMark(checked: Boolean) {
    val track = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(track)
            .background(if (checked) FluxAccent else FluxSurfaceVariant)
            .border(2.dp, if (checked) FluxAccent else FluxMuted, track)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(if (checked) 22.dp else 18.dp)
                .clip(CircleShape)
                .background(if (checked) OnAccent else FluxMuted)
        )
    }
}

@Composable
fun FluxTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        BasicText(label, style = TextStyle(color = FluxMuted, fontSize = 16.sp))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = FluxOnSurface, fontSize = 20.sp),
            cursorBrush = SolidColor(FluxAccent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(FluxSurface)
                .border(3.dp, if (focused) FluxAccent else FluxSurfaceVariant, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        BasicText(placeholder, style = TextStyle(color = FluxMuted, fontSize = 20.sp))
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
fun FluxChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .clickable(onClick = onClick)
            .background(if (selected) FluxAccent else FluxSurfaceVariant)
            .border(3.dp, if (focused) FluxFocus else Color.Transparent, shape)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        BasicText(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (selected) OnAccent else FluxOnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/**
 * One channel in the channel list. [programme]: what is on now, from the programme guide, written
 * smaller and greyer after the name on the same line, so the row keeps its height and the name comes
 * first: when the line is too long, the programme is what gets cut short. [isCurrent]: the channel
 * playing, marked with ▶ before its number, where it takes none of the name's or programme's width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    number: Int,
    name: String,
    logoUrl: String?,
    isCurrent: Boolean,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    numberWidth: Dp = 64.dp,
    programme: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = if (focused) OnAccent else FluxOnSurface
    val label = remember(name, programme, focused) {
        buildAnnotatedString {
            append(name)
            if (!programme.isNullOrBlank()) {
                withStyle(SpanStyle(color = if (focused) OnAccent.copy(alpha = 0.7f) else FluxMuted, fontSize = 18.sp)) {
                    append("   ")
                    append(programme)
                }
            }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                when {
                    focused -> FluxAccent
                    isCurrent -> FluxSurfaceVariant
                    else -> Color.Transparent
                }
            )
            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Every row keeps the slot, so the numbers line up. U+FE0E: the plain sign, never an emoji.
        Box(Modifier.width(16.dp * LocalTextScale.current), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                BasicText(
                    "\u25B6\uFE0E",
                    maxLines = 1,
                    softWrap = false,
                    style = TextStyle(color = if (focused) OnAccent else FluxAccent, fontSize = 14.sp)
                )
            }
        }
        BasicText(
            number.toString(),
            modifier = Modifier.width(numberWidth),
            style = TextStyle(color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        BasicText(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = textColor, fontSize = 22.sp)
        )
        if (isFavorite) {
            Spacer(Modifier.width(12.dp))
            BasicText("★", style = TextStyle(color = if (focused) OnAccent else FluxWarning, fontSize = 22.sp))
        }
    }
}

/** One category in the channel list's left column: its name and channel count; [selected] marks the one shown. */
@Composable
fun CategoryRow(
    name: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = if (focused) OnAccent else FluxOnSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> FluxAccent
                    selected -> FluxSurfaceVariant
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = textColor,
                fontSize = 20.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal
            )
        )
        Spacer(Modifier.width(10.dp))
        BasicText(
            count.toString(),
            style = TextStyle(color = if (focused) OnAccent else FluxMuted, fontSize = 17.sp)
        )
    }
}

/** One saved playlist in Settings → Playlists. [inUse] marks, in words, the one TV shows. */
@Composable
fun PlaylistRow(
    name: String,
    inUse: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = if (focused) OnAccent else FluxOnSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(if (focused) FluxAccent else FluxSurface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = textColor, fontSize = 22.sp, fontWeight = if (inUse) FontWeight.Bold else FontWeight.Normal)
        )
        if (inUse) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                "IN USE",
                style = TextStyle(color = if (focused) OnAccent else FluxAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(label, style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        Spacer(Modifier.width(16.dp))
        // A long value wraps under itself on a narrow phone, rather than crushing the label.
        BasicText(
            value,
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(
                color = FluxOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            )
        )
    }
}
