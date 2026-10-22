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
import com.fluxtv.app.ui.EasyOverlay
import com.fluxtv.app.ui.ListTab
import com.fluxtv.app.ui.UiState
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface

/**
 * The WATCHING state (§25). Full-screen video with transient overlays layered above it; playback
 * keeps running underneath the channel list (REQ-CH-003). Back closes an overlay, or opens the list
 * on the current channel's category — it never accidentally exits the app (REQ-CFG-001, REQ-REM-003).
 */
@Composable
fun EasyModeScreen(state: UiState, vm: AppViewModel) {
    val playerReady by vm.controller.playerReady.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(vm.controller, playerReady, Modifier.fillMaxSize())

        if (state.channels.isEmpty()) {
            NoChannelsHint()
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
            ChannelListOverlay(
                allChannels = state.channels,
                favoriteChannels = state.favoriteChannels,
                categoryChannels = state.categoryChannels,
                categoryName = state.categoryName,
                categories = if (state.channelListTab == ListTab.CATEGORIES) state.categories else emptyList(),
                favorites = state.favorites,
                currentId = state.currentChannel?.stableId,
                tab = state.channelListTab,
                onSelect = vm::tuneTo,
                onSelectCategory = vm::selectCategory,
                onToggleFavorite = vm::toggleFavorite,
                onSwitchTab = vm::setTab
            )
        }

        if (state.overlay == EasyOverlay.NUMBER_ENTRY) {
            NumberEntryOverlay(state.numberEntry)
        }

        state.transientMessage?.let { TransientMessage(it) }
    }

    BackHandler(enabled = true) {
        if (state.overlay != EasyOverlay.NONE) vm.closeOverlay() else vm.openCategoryList()
    }
}

@Composable
private fun NoChannelsHint() {
    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                "No channels yet",
                style = TextStyle(color = FluxOnSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                "Open Settings to add your playlist.\nPress the MENU button, or press and hold OK.",
                modifier = Modifier.padding(horizontal = 24.dp),
                style = TextStyle(color = FluxMuted, fontSize = 22.sp, textAlign = TextAlign.Center)
            )
        }
    }
}
