package com.fluxtv.app.ui.easymode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.playback.PlaybackPhase
import com.fluxtv.app.playback.PlaybackProblem
import com.fluxtv.app.playback.PlaybackStatus
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import kotlinx.coroutines.delay

/**
 * What the middle of the picture says about the stream, one thing at a time, so that whatever keeps
 * the channel from playing normally is seen, not only noticed (REQ-SEN-013): the moving dots for
 * "wait, FluxTV is on it", an icon for why, recognisable without reading, and a few plain words.
 * Each goes by itself once the channel plays again; none asks the Viewer to do anything, except
 * [Paused], which only a key press can end. When automatic recovery is exhausted the full
 * reconnecting screen takes over instead ([ReconnectingScreen]).
 */
sealed interface StreamNotice {
    /** The channel is starting, or has stopped to load, for more than a moment: the dots alone. */
    data object Busy : StreamNotice

    /** FluxTV is trying again after [problem] (REQ-BUF-008, §20): its icon, the dots and [message]. */
    data class Retrying(val message: String, val problem: PlaybackProblem?) : StreamNotice

    /**
     * The channel plays, but its picture does not: it froze ([PlaybackProblem.FROZEN], FluxTV
     * restarts it) or this TV cannot show it ([PlaybackProblem.SOUND_ONLY]).
     */
    data class Picture(val problem: PlaybackProblem) : StreamNotice

    /** Android paused the channel for another app's sound; on a TV, OK plays it again. */
    data object Paused : StreamNotice
}

/**
 * The connection itself, said in a corner for as long as it lasts, whatever else is on screen:
 * [NO_NETWORK] even while the channel still plays from what it buffered, [SLOW] when the channel
 * keeps stopping to load. Each with the icon the reconnecting screen uses for it.
 */
enum class ConnectionNotice(val label: String, val problem: PlaybackProblem) {
    NO_NETWORK("No network connection", PlaybackProblem.NO_NETWORK),
    SLOW("Slow connection", PlaybackProblem.CONNECTION)
}

/** What [ConnectionNotice] the playback calls for now, if any. */
fun PlaybackStatus.connectionNotice(): ConnectionNotice? = when {
    networkLost -> ConnectionNotice.NO_NETWORK
    slowConnection -> ConnectionNotice.SLOW
    else -> null
}

/**
 * A start or a stall shorter than this shows nothing: zapping and the short stalls of a good
 * connection stay clean (REQ-BUF-003). Longer, the screen would otherwise be black or still, which
 * looks like a TV that has stopped working, so the dots say it is on its way.
 */
private const val BUSY_GRACE_MS = 1_500L

/**
 * The [StreamNotice] for [playback] now, or null: none while the reconnecting screen is up, which
 * says it all itself. [pausedBySystem]: Android paused the channel (FluxPlayerController.pausedBySystem);
 * the phone layout passes false and shows its own Play button instead.
 */
@Composable
fun rememberStreamNotice(playback: PlaybackStatus, pausedBySystem: Boolean): StreamNotice? {
    val waiting = when (playback.phase) {
        PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING -> true
        // A retry with nothing to say: the one more try a decoder gets, a frozen picture restarting.
        PlaybackPhase.RECOVERING -> playback.userMessage == null
        else -> false
    }
    // Counted afresh for each channel, so zapping does not carry the last channel's wait over.
    val channelId = playback.channel?.stableId
    var waitedLong by remember(waiting, channelId) { mutableStateOf(false) }
    LaunchedEffect(waiting, channelId) {
        if (waiting) {
            delay(BUSY_GRACE_MS)
            waitedLong = true
        }
    }
    val message = playback.userMessage
    val picture = playback.pictureProblem
    return when {
        playback.showRecoveryPrompt -> null
        playback.phase == PlaybackPhase.RECOVERING && message != null -> StreamNotice.Retrying(message, playback.problem)
        pausedBySystem -> StreamNotice.Paused
        waiting && waitedLong -> StreamNotice.Busy
        picture != null && playback.phase == PlaybackPhase.PLAYING -> StreamNotice.Picture(picture)
        else -> null
    }
}

/** Dark enough behind white text over any picture; the same as the banner's. */
private val NoticeScrim = Color(0xCC0A0F18)

/**
 * A [StreamNotice] in the middle of the TV screen, over the picture. [Paused] adds the key that
 * plays the channel again, the one thing it needs from the Viewer.
 */
@Composable
fun StreamNoticeOverlay(notice: StreamNotice) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StreamNoticeCard(notice, compact = false)
            if (notice == StreamNotice.Paused) {
                Spacer(Modifier.height(16.dp))
                // On the scrim too: its white words would be lost over a bright picture.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(NoticeScrim)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) { KeyHint("OK", "Play") }
            }
        }
    }
}

/** [notice] as a card: on the TV, or [compact] over the smaller picture of a phone. */
@Composable
fun StreamNoticeCard(notice: StreamNotice, compact: Boolean, modifier: Modifier = Modifier) {
    when (notice) {
        StreamNotice.Busy -> NoticeCard(null, null, working = true, compact = compact, modifier = modifier)
        is StreamNotice.Retrying -> NoticeCard(notice.message, notice.problem, working = true, compact = compact, modifier = modifier)
        is StreamNotice.Picture -> NoticeCard(
            notice.problem.title,
            notice.problem,
            working = notice.problem.healing,
            detail = notice.problem.hint,
            compact = compact,
            modifier = modifier
        )
        StreamNotice.Paused -> NoticeCard(
            PlaybackProblem.PAUSED.title,
            PlaybackProblem.PAUSED,
            working = false,
            detail = PlaybackProblem.PAUSED.hint,
            compact = compact,
            modifier = modifier
        )
    }
}

/**
 * One notice: [problem]'s icon, the moving dots while FluxTV is [working] on it, and [message], with
 * [detail] smaller under it. With no message, only the dots: a wait with nothing to explain.
 */
@Composable
private fun NoticeCard(
    message: String?,
    problem: PlaybackProblem?,
    working: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Row(
        modifier
            .clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
            .background(NoticeScrim)
            .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = if (compact) 10.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (problem != null) {
            ProblemIcon(problem, if (compact) 24.dp else 44.dp, FluxOnSurface)
            Spacer(Modifier.width(if (compact) 12.dp else 20.dp))
        }
        if (working) {
            WaitingDots(if (compact) 7.dp else 10.dp)
            if (message != null) Spacer(Modifier.width(if (compact) 12.dp else 18.dp))
        }
        if (message != null) {
            Column(Modifier.widthIn(max = if (compact) 420.dp else 760.dp)) {
                BasicText(message, style = TextStyle(color = FluxOnSurface, fontSize = if (compact) 16.sp else 24.sp))
                if (detail != null) {
                    Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                    BasicText(detail, style = TextStyle(color = FluxMuted, fontSize = if (compact) 13.sp else 18.sp))
                }
            }
        }
    }
}

/**
 * [notice] in a corner: its icon and its name. Kept calm, like everything about the stream: it says
 * what is wrong; FluxTV does the rest by itself. [background] for a dark panel, where the usual
 * scrim would not show.
 */
@Composable
fun ConnectionBadge(
    notice: ConnectionNotice,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    background: Color = NoticeScrim
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProblemIcon(notice.problem, if (compact) 20.dp else 32.dp, FluxOnSurface)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        BasicText(
            notice.label,
            maxLines = 1,
            style = TextStyle(
                color = FluxOnSurface,
                fontSize = if (compact) 14.sp else 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
