package com.fluxtv.app.ui.easymode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxtv.app.ui.AppViewModel
import com.fluxtv.app.ui.BrowsePane
import com.fluxtv.app.ui.EasyOverlay
import com.fluxtv.app.ui.UiState
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface

/**
 * The WATCHING state (§25). Full-screen video with transient overlays layered above it; playback
 * keeps running underneath the channel list (REQ-CH-003). Back steps back like other IPTV players:
 * watching -> the two-pane list on the current channel's category -> its categories column -> leave
 * the app. The last channel is remembered, so the next launch resumes it.
 */
@Composable
fun EasyModeScreen(state: UiState, vm: AppViewModel, onExit: () -> Unit) {
    val playerReady by vm.controller.playerReady.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(
            controller = vm.controller,
            playerReady = playerReady,
            onSurfaceReady = vm::onVideoSurfaceReady,
            onSurfaceLost = vm::onVideoSurfaceLost,
            modifier = Modifier.fillMaxSize()
        )

        if (state.channels.isEmpty()) {
            NoChannelsHint(loading = state.isRefreshing)
        }

        val channel = state.currentChannel
        val showRecovery = state.playback.showRecoveryPrompt && state.overlay == EasyOverlay.NONE

        if (state.showInfoBanner && channel != null && state.overlay == EasyOverlay.NONE && !showRecovery) {
            ChannelInfoBanner(channel.number, channel.name, channel.logoUrl, channel.group)
        }

        if (state.overlay == EasyOverlay.NONE && !showRecovery && state.playback.userMessage != null) {
            StatusOverlay(state.playback.userMessage!!)
        }

        if (showRecovery) {
            RecoveryOverlay(state.playback.userMessage ?: "Channel temporarily unavailable.")
        }

        if (state.overlay == EasyOverlay.CHANNEL_LIST) {
            InstantScroll {
                ChannelListOverlay(
                    allChannels = state.channels,
                    favorites = state.favorites,
                    currentId = state.currentChannel?.stableId,
                    startKey = state.browseStartKey,
                    pane = state.browsePane,
                    onPaneChange = vm::setBrowsePane,
                    onSelect = vm::selectChannel,
                    onToggleFavorite = vm::toggleFavorite
                )
            }
        }

        if (state.overlay == EasyOverlay.NUMBER_ENTRY) {
            NumberEntryOverlay(state.numberEntry)
        }

        state.transientMessage?.let { TransientMessage(it) }
    }

    BackHandler(enabled = true) {
        when {
            // The channels step up to the categories; the categories leave the app.
            state.overlay == EasyOverlay.CHANNEL_LIST && state.browsePane == BrowsePane.CHANNELS ->
                vm.setBrowsePane(BrowsePane.CATEGORIES)
            state.overlay == EasyOverlay.CHANNEL_LIST -> onExit()
            state.overlay != EasyOverlay.NONE -> vm.closeOverlay()
            else -> vm.openChannelList()
        }
    }
}

/** [loading]: a first playlist download is still running (it can take minutes over a slow line). */
@Composable
private fun NoChannelsHint(loading: Boolean) {
    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                if (loading) "Loading channels…" else "No channels yet",
                style = TextStyle(color = FluxOnSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                if (loading) "The first time can take a few minutes. TV starts on its own when they arrive."
                else "Open Settings to add your playlist.\nPress the MENU button, or press and hold OK.",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = TextStyle(color = FluxMuted, fontSize = 22.sp, textAlign = TextAlign.Center)
            )
        }
    }
}
