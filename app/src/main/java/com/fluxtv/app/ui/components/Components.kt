package com.fluxtv.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

private val OnAccent = Color(0xFF00131C)

/** TV-safe title margin so essential controls survive overscan (REQ-DEV-005). */
fun Modifier.overscan(): Modifier = this.padding(horizontal = 48.dp, vertical = 27.dp)

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

@Composable
fun FluxToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onToggle: (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .clickable { onToggle(!checked) }
            .background(if (focused) FluxSurfaceVariant else FluxSurface)
            .border(3.dp, if (focused) FluxAccent else Color.Transparent, shape)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BasicText(label, style = TextStyle(color = FluxOnSurface, fontSize = 22.sp))
        BasicText(
            if (checked) "ON" else "OFF",
            style = TextStyle(
                color = if (checked) FluxAccent else FluxMuted,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
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
    numberWidth: Dp = 64.dp
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = if (focused) OnAccent else FluxOnSurface
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = textColor, fontSize = 22.sp)
        )
        if (isFavorite) {
            Spacer(Modifier.width(12.dp))
            BasicText("★", style = TextStyle(color = if (focused) OnAccent else FluxWarning, fontSize = 22.sp))
        }
        if (isCurrent) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                "NOW",
                style = TextStyle(color = if (focused) OnAccent else FluxAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
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
        BasicText(value, style = TextStyle(color = FluxOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium))
    }
}
