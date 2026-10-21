package com.fluxtv.app.ui.easymode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import com.fluxtv.app.ui.theme.FluxSurface

private val Scrim = Color(0xCC0A0F18)

/** Bottom channel banner shown briefly on tune-in (REQ-SEN-014: number, logo, name). */
@Composable
fun ChannelInfoBanner(number: Int, name: String, logoUrl: String?, group: String?) {
    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.BottomStart) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Scrim)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                number.toString(),
                style = TextStyle(color = FluxAccent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.width(20.dp))
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(20.dp))
            }
            Column {
                BasicText(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = FluxOnSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                )
                if (!group.isNullOrBlank()) {
                    BasicText(group, style = TextStyle(color = FluxMuted, fontSize = 18.sp))
                }
            }
        }
    }
}

/** Small, centered, non-intrusive status while preparing / recovering (REQ-BUF-008, §20). */
@Composable
fun StatusOverlay(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Scrim)
                .padding(horizontal = 28.dp, vertical = 18.dp)
        ) {
            BasicText(message, style = TextStyle(color = FluxOnSurface, fontSize = 24.sp))
        }
    }
}

/** Shown only after automatic recovery is exhausted (REQ-REC-005). Keys handled centrally. */
@Composable
fun RecoveryOverlay(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(FluxSurface)
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                "This channel is temporarily unavailable.",
                style = TextStyle(color = FluxOnSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(12.dp))
            BasicText(message, style = TextStyle(color = FluxMuted, fontSize = 20.sp, textAlign = TextAlign.Center))
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PromptHint("OK", "Try again")
                PromptHint("▲ ▼", "Change channel")
            }
        }
    }
}

@Composable
private fun PromptHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FluxAccent)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            BasicText(key, style = TextStyle(color = Color(0xFF00131C), fontSize = 20.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = TextStyle(color = FluxOnSurface, fontSize = 20.sp))
    }
}

/** Direct channel-number entry overlay (REQ-REM-005). */
@Composable
fun NumberEntryOverlay(entry: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xDD0A0F18))
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText("Channel", style = TextStyle(color = FluxMuted, fontSize = 22.sp))
            Spacer(Modifier.height(8.dp))
            BasicText(
                entry.ifEmpty { "_" },
                style = TextStyle(color = FluxAccent, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(12.dp))
            BasicText("OK to watch", style = TextStyle(color = FluxMuted, fontSize = 18.sp))
        }
    }
}

/** Brief top-center toast, e.g. "No channel 55". */
@Composable
fun TransientMessage(message: String) {
    Box(Modifier.fillMaxSize().overscan(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Scrim)
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            BasicText(message, style = TextStyle(color = FluxOnSurface, fontSize = 22.sp))
        }
    }
}
