package com.fluxtv.app.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.FluxTvApplication
import com.fluxtv.app.ShizukuWake
import com.fluxtv.app.canStartItself
import com.fluxtv.app.data.model.DiagnosticsSnapshot
import com.fluxtv.app.openSelfStartPermission
import com.fluxtv.app.ui.AppViewModel
import com.fluxtv.app.ui.UiState
import com.fluxtv.app.ui.components.ChannelRow
import com.fluxtv.app.ui.components.FluxButton
import com.fluxtv.app.ui.components.FluxChip
import com.fluxtv.app.ui.components.FluxTextField
import com.fluxtv.app.ui.components.FluxToggleRow
import com.fluxtv.app.ui.components.InfoRow
import com.fluxtv.app.ui.components.SectionTitle
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AdminSection { MENU, PLAYLIST, CHANNELS, PREFERENCES, DIAGNOSTICS }

/**
 * Administrator area, fully separated from Easy Mode (REQ-GEN-003, REQ-CFG-003) and optionally
 * protected by a PIN (REQ-CFG-004). Reached only by a deliberate gesture (hold OK / MENU). It also
 * holds "Exit FluxTV" (Back from the Categories view and Home also leave the app).
 */
@Composable
fun AdminScreen(state: UiState, vm: AppViewModel, onExitApp: () -> Unit) {
    var unlocked by remember { mutableStateOf(state.settings.adminPin.isBlank()) }
    if (!unlocked) {
        PinGate(expected = state.settings.adminPin, onUnlock = { unlocked = true }, onCancel = { vm.exitAdmin() })
        return
    }

    var section by remember { mutableStateOf(AdminSection.MENU) }
    BackHandler(enabled = true) {
        if (section != AdminSection.MENU) section = AdminSection.MENU else vm.exitAdmin()
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        when (section) {
            AdminSection.MENU -> AdminMenu(
                state,
                onSelect = { section = it },
                onExit = { vm.exitAdmin() },
                onExitApp = onExitApp
            )
            AdminSection.PLAYLIST -> PlaylistSection(state, vm)
            AdminSection.CHANNELS -> ChannelsSection(state, vm)
            AdminSection.PREFERENCES -> PreferencesSection(state, vm)
            AdminSection.DIAGNOSTICS -> DiagnosticsSection(state, vm)
        }
    }
}

@Composable
private fun PinGate(expected: String, onUnlock: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionTitle("Enter settings PIN")
            FluxTextField(
                label = "PIN",
                value = pin,
                onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = false },
                modifier = Modifier.fillMaxWidth(0.35f),
                focusRequester = focus,
                keyboardType = KeyboardType.NumberPassword
            )
            if (error) BasicText("Incorrect PIN", style = TextStyle(color = FluxAccent, fontSize = 18.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FluxButton("Unlock") { if (pin == expected) onUnlock() else error = true }
                FluxButton("Cancel") { onCancel() }
            }
        }
    }
}

@Composable
private fun AdminMenu(
    state: UiState,
    onSelect: (AdminSection) -> Unit,
    onExit: () -> Unit,
    onExitApp: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("FluxTV Settings")
        BasicText(
            "${state.channels.size} channels" + if (state.lastUpdatedMs > 0) " • updated ${formatTime(state.lastUpdatedMs)}" else "",
            style = TextStyle(color = FluxMuted, fontSize = 18.sp)
        )
        Spacer(Modifier.height(8.dp))
        FluxButton("Playlist", Modifier.fillMaxWidth(0.55f), focusRequester = first) { onSelect(AdminSection.PLAYLIST) }
        FluxButton("Channels & favorites", Modifier.fillMaxWidth(0.55f)) { onSelect(AdminSection.CHANNELS) }
        FluxButton("Preferences", Modifier.fillMaxWidth(0.55f)) { onSelect(AdminSection.PREFERENCES) }
        FluxButton("Diagnostics", Modifier.fillMaxWidth(0.55f)) { onSelect(AdminSection.DIAGNOSTICS) }
        // Side by side so the menu keeps its five rows and still fits a 720p screen.
        Row(Modifier.fillMaxWidth(0.55f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FluxButton("Back to TV", Modifier.weight(1f)) { onExit() }
            FluxButton("Exit FluxTV", Modifier.weight(1f)) { onExitApp() }
        }
    }
}

@Composable
private fun PlaylistSection(state: UiState, vm: AppViewModel) {
    var url by remember { mutableStateOf(state.settings.playlistUrl ?: "") }
    var name by remember { mutableStateOf(state.settings.playlistName ?: "") }
    var isFile by remember { mutableStateOf(state.settings.playlistType == "file") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Playlist")
        FluxTextField(
            label = "Playlist address (M3U URL) or file path",
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(0.9f),
            placeholder = "https://provider.example/playlist.m3u",
            focusRequester = first,
            keyboardType = KeyboardType.Uri
        )
        FluxTextField(
            label = "Name",
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(0.6f),
            placeholder = "My Playlist"
        )
        FluxToggleRow("Source is a local file", isFile, Modifier.fillMaxWidth(0.6f)) { isFile = it }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FluxButton("Save & Load") { vm.savePlaylist(url, name, if (isFile) "file" else "url") }
            FluxButton("Refresh now") { vm.refreshNow() }
        }
        FluxButton("Use sample test playlist") {
            url = "https://iptv-org.github.io/iptv/index.m3u"
            name = "IPTV-Org Sample"
            isFile = false
        }
        if (state.isRefreshing) BasicText("Loading…", style = TextStyle(color = FluxAccent, fontSize = 20.sp))
        state.lastRefreshMessage?.let {
            BasicText(it, style = TextStyle(color = FluxOnSurface, fontSize = 20.sp))
        }
        BasicText("Channels loaded: ${state.channels.size}", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        if (state.lastUpdatedMs > 0) {
            BasicText("Last updated: ${formatTime(state.lastUpdatedMs)}", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        }
        BasicText(
            "Credentials in the address stay hidden from the Viewer during normal use.",
            style = TextStyle(color = FluxMuted, fontSize = 15.sp)
        )
    }
}

@Composable
private fun ChannelsSection(state: UiState, vm: AppViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Channels & favorites")
        FluxToggleRow(
            "Easy Mode shows favorites only",
            state.settings.favoritesOnly,
            Modifier.fillMaxWidth(0.7f),
            focusRequester = first
        ) { vm.setFavoritesOnly(it) }
        BasicText("OK: add or remove a favorite ★", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        if (state.channels.isEmpty()) {
            BasicText("No channels yet. Add a playlist first.", style = TextStyle(color = FluxMuted, fontSize = 22.sp))
        } else {
            LazyColumn(Modifier.fillMaxWidth(0.8f).weight(1f)) {
                itemsIndexed(state.channels, key = { _, c -> c.stableId }) { _, channel ->
                    ChannelRow(
                        number = channel.number,
                        name = channel.name,
                        logoUrl = channel.logoUrl,
                        isCurrent = false,
                        isFavorite = channel.stableId in state.favorites,
                        onClick = { vm.toggleFavorite(channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferencesSection(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SectionTitle("Preferences")
        FluxToggleRow(
            "Easy Mode shows favorites only",
            state.settings.favoritesOnly,
            Modifier.fillMaxWidth(0.7f),
            focusRequester = first
        ) { vm.setFavoritesOnly(it) }

        BasicText("Maximum quality", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FluxChip("Auto", state.settings.maxVideoHeight == 0) { vm.setMaxVideoHeight(0) }
            FluxChip("Up to 720p", state.settings.maxVideoHeight == 720) { vm.setMaxVideoHeight(720) }
            FluxChip("Up to 1080p", state.settings.maxVideoHeight == 1080) { vm.setMaxVideoHeight(1080) }
        }

        FluxToggleRow(
            "Match TV refresh rate to the video",
            state.settings.frameRateMatching,
            Modifier.fillMaxWidth(0.7f)
        ) { vm.setFrameRateMatching(it) }

        FluxToggleRow(
            "Start FluxTV when the TV turns on",
            state.settings.startOnBoot,
            Modifier.fillMaxWidth(0.7f)
        ) { vm.setStartOnBoot(it) }

        // Android 10+ blocks an app from opening itself unless it is exempt, so on those versions
        // the toggle above needs one permission from the administrator to have any effect.
        if (state.settings.startOnBoot && !canStartItself(context)) {
            BasicText(
                "This Android version needs one more permission before FluxTV may open itself.\n" +
                    "Choose FluxTV on the next screen and allow it.",
                style = TextStyle(color = FluxMuted, fontSize = 18.sp)
            )
            FluxButton("Allow FluxTV to open itself") { openSelfStartPermission(context) }
        }

        // A force-stopped app is not run again by a wake. Where Shizuku runs on the TV, FluxTV can have
        // a helper that outlives it; without Shizuku there is nothing to show.
        val shizuku = (context.applicationContext as FluxTvApplication).shizukuWake
        val shizukuState by shizuku.state.collectAsState()
        when (shizukuState) {
            ShizukuWake.State.NOT_RUNNING -> Unit
            ShizukuWake.State.NEEDS_PERMISSION -> {
                BasicText(
                    "Shizuku is running on this TV. Allow FluxTV to use it and FluxTV will also start by " +
                        "itself when the TV wakes after it was force-stopped.",
                    style = TextStyle(color = FluxMuted, fontSize = 18.sp)
                )
                FluxButton("Allow FluxTV to use Shizuku") { shizuku.requestPermission() }
            }
            ShizukuWake.State.STARTING -> BasicText(
                "Starting the Shizuku helper…",
                style = TextStyle(color = FluxMuted, fontSize = 18.sp)
            )
            ShizukuWake.State.ACTIVE -> BasicText(
                "Shizuku helper active: FluxTV starts when the TV wakes, even after a force-stop.",
                style = TextStyle(color = FluxMuted, fontSize = 18.sp)
            )
        }

        BasicText("Automatic playlist refresh", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FluxChip("Every 6 hours", state.settings.refreshIntervalHours == 6) { vm.setRefreshHours(6) }
            FluxChip("Every 12 hours", state.settings.refreshIntervalHours == 12) { vm.setRefreshHours(12) }
            FluxChip("Every 24 hours", state.settings.refreshIntervalHours == 24) { vm.setRefreshHours(24) }
        }

        BasicText("Settings PIN (optional)", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        BasicText(
            if (state.settings.adminPin.isBlank()) "No PIN set" else "A PIN is set",
            style = TextStyle(color = FluxOnSurface, fontSize = 18.sp)
        )
        FluxTextField(
            label = "New PIN (digits)",
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
            modifier = Modifier.fillMaxWidth(0.4f),
            keyboardType = KeyboardType.NumberPassword
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FluxButton("Save PIN") { vm.setAdminPin(pin); pin = "" }
            FluxButton("Clear PIN") { vm.setAdminPin(""); pin = "" }
        }
    }
}

@Composable
private fun DiagnosticsSection(state: UiState, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var reachability by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // The boot and wake receivers write history straight to the store, so pick up anything the
    // player has not seen.
    LaunchedEffect(Unit) { vm.reloadDiagnosticsEvents() }
    // Buffer, bandwidth and dropped frames otherwise only refresh on playback state changes.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshDiagnostics()
            delay(1_000)
        }
    }
    val d = state.diagnostics

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Diagnostics")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FluxButton("Check this channel", focusRequester = first) {
                val url = state.currentChannel?.url
                if (url != null) {
                    reachability = "Checking…"
                    scope.launch { reachability = if (vm.checkReachable(url)) "Reachable" else "Not reachable" }
                }
            }
            FluxButton("Clear history") { vm.clearDiagnostics() }
        }

        Column {
            InfoRow("Channel", d.channelName ?: "—")
            reachability?.let { InfoRow("Reachability", it) }
            InfoRow("Decoding", decodeLabel(d))
            InfoRow("Resolution", if (d.videoWidth > 0) "${d.videoWidth}×${d.videoHeight}" else "—")
            InfoRow("Frame rate", if (d.frameRate > 0f) "${d.frameRate} fps" else "—")
            InfoRow("Video codec", d.videoCodec ?: "—")
            InfoRow("Audio codec", d.audioCodec ?: "—")
            InfoRow("Video bitrate", if (d.videoBitrate > 0) "${d.videoBitrate / 1000} kbps" else "—")
            InfoRow("Estimated bandwidth", if (d.bandwidthBps > 0) formatMbps(d.bandwidthBps) else "—")
            InfoRow("Buffered", "${d.bufferedMs / 1000} s")
            InfoRow("Dropped frames", d.droppedFrames.toString())
            InfoRow("Buffering events", d.bufferingEvents.toString())
            InfoRow("Recoveries", d.recoveryCount.toString())
            InfoRow("Channel startup time", if (d.startupTimeMs > 0) "${d.startupTimeMs} ms" else "—")
            InfoRow("Playback state", d.playbackState)
        }

        BasicText("History", style = TextStyle(color = FluxOnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.diagnosticsEvents.reversed()) { event ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    BasicText(
                        "${formatTime(event.timestampMs)}  [${event.category}]  ${event.channelName ?: ""}",
                        style = TextStyle(color = FluxMuted, fontSize = 14.sp)
                    )
                    BasicText(event.message, style = TextStyle(color = FluxOnSurface, fontSize = 16.sp))
                }
            }
        }
    }
}

private fun decodeLabel(d: DiagnosticsSnapshot): String {
    val name = d.videoDecoder ?: return "—"
    val hw = d.hardwareDecoding
    val kind = when (hw) {
        true -> "Hardware"
        false -> "Software"
        null -> "Unknown"
    }
    return "$kind ($name)"
}

private fun formatMbps(bitsPerSecond: Long): String =
    String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)

private fun formatTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
