package com.fluxtv.app.ui.easymode

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fluxtv.app.data.model.Channel
import com.fluxtv.app.playback.PlaybackProblem
import com.fluxtv.app.ui.components.overscan
import com.fluxtv.app.ui.theme.FluxAccent
import com.fluxtv.app.ui.theme.FluxMuted
import com.fluxtv.app.ui.theme.FluxOnSurface
import kotlin.math.abs

/** Almost opaque, and no warning colour anywhere: this screen is a wait, not an alarm. */
private val Backdrop = Color(0xF00A0F18)

/**
 * What the Viewer sees when a channel could not be brought back (REQ-REC-005), made to be understood
 * without reading. It looks the same every time: the channel's logo and number, which the Viewer
 * knows, and dots that move slowly while FluxTV brings the channel back by itself, with one word
 * under them for those who read (REQ-SEN-013). There is nothing to choose: the picture comes back
 * on its own, OK tries again at once, ▲ ▼ change channel as always. Why it happened is in small
 * print at the bottom, with an icon, for whoever looks after the TV.
 *
 * A channel FluxTV is not trying to bring back, because this TV cannot play it at all
 * ([PlaybackProblem.healing] false), shows a crossed-out TV instead of the dots, and [actions]
 * (on a TV, the ▲ ▼ key hint) says what can be done.
 *
 * [compact]: the small picture above the channel list on an upright phone, where the channel is
 * marked in the list below and only the dots, the word, the reason and [actions] fit. Short of the
 * height a TV has (a phone held sideways), everything is drawn smaller to fit.
 */
@Composable
fun ReconnectingScreen(
    channel: Channel?,
    problem: PlaybackProblem,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    actions: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Backdrop)) {
        // A TV has 540 dp of height or more; a phone held sideways about 360 to 420.
        val roomy = !compact && maxHeight >= 480.dp
        val wordSize = when {
            roomy -> 28.sp
            compact -> 17.sp
            else -> 22.sp
        }
        Column(Modifier.fillMaxSize()) {
            // The middle takes the room the reason at the bottom leaves, so a large logo never runs
            // into it.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (channel != null && !compact) {
                        ChannelMark(channel, roomy)
                        Spacer(Modifier.height(if (roomy) 40.dp else 18.dp))
                    }
                    if (problem.healing) {
                        WaitingDots(
                            when {
                                roomy -> 22.dp
                                compact -> 12.dp
                                else -> 16.dp
                            }
                        )
                        Spacer(Modifier.height(if (roomy) 24.dp else 12.dp))
                        BasicText("Reconnecting…", style = TextStyle(color = FluxOnSurface, fontSize = wordSize))
                    } else {
                        ProblemIcon(problem, if (roomy) 72.dp else 40.dp, FluxOnSurface)
                        Spacer(Modifier.height(if (roomy) 20.dp else 8.dp))
                        BasicText(
                            problem.title,
                            style = TextStyle(color = FluxOnSurface, fontSize = wordSize, textAlign = TextAlign.Center)
                        )
                    }
                    if (compact) {
                        Spacer(Modifier.height(8.dp))
                        Reason(problem, compact = true)
                    }
                    if (actions != null) {
                        Spacer(Modifier.height(if (roomy) 32.dp else 14.dp))
                        actions()
                    }
                }
            }
            if (!compact) {
                Box(
                    Modifier.fillMaxWidth().overscan().padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Reason(problem, compact = false)
                }
            }
        }
    }
}

/** The channel as the Viewer knows it: its logo, large, and its number and name under it. */
@Composable
private fun ChannelMark(channel: Channel, roomy: Boolean) {
    val logo = channel.logoUrl
    // A logo that will not load leaves no empty frame: the number and name carry on alone.
    var logoFailed by remember(logo) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!logo.isNullOrBlank() && !logoFailed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                onError = { logoFailed = true },
                modifier = if (roomy) Modifier.size(width = 280.dp, height = 140.dp) else Modifier.size(width = 128.dp, height = 64.dp)
            )
            Spacer(Modifier.height(if (roomy) 20.dp else 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                channel.number.toString(),
                style = TextStyle(color = FluxAccent, fontSize = if (roomy) 34.sp else 24.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.width(if (roomy) 16.dp else 12.dp))
            BasicText(
                channel.name,
                modifier = Modifier.widthIn(max = 640.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = FluxOnSurface, fontSize = if (roomy) 28.sp else 20.sp)
            )
        }
    }
}

/** Why, in small print with its icon: for the family, not something the Viewer has to read. */
@Composable
private fun Reason(problem: PlaybackProblem, compact: Boolean) {
    Row(
        Modifier
            .widthIn(max = if (compact) 480.dp else 900.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProblemIcon(problem, if (compact) 16.dp else 26.dp, FluxMuted)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        BasicText(
            // A crossed-out TV already says the title in the middle of the screen.
            when {
                compact -> problem.title
                problem.healing -> "${problem.title} ${problem.hint}"
                else -> problem.hint
            },
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = FluxMuted, fontSize = if (compact) 13.sp else 18.sp)
        )
    }
}

/**
 * Three dots that brighten one after another, slowly and without end: "busy, wait". The light moves
 * from dot to dot without going out between them, so a glance at any moment shows it. Only the
 * dots' layers change from frame to frame; nothing is laid out or composed again for it.
 */
@Composable
fun WaitingDots(dotSize: Dp, modifier: Modifier = Modifier, color: Color = FluxAccent) {
    val transition = rememberInfiniteTransition(label = "waiting")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing)),
        label = "phase"
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSize * 0.8f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        // How far the light is from this dot, around the circle of three: full on
                        // it, fading over a dot and a bit, so the next dot is lighting up already.
                        var distance = abs(phase - (index + 0.5f))
                        if (distance > 1.5f) distance = 3f - distance
                        val glow = (1f - distance / 1.2f).coerceAtLeast(0f)
                        alpha = 0.3f + 0.7f * glow
                        scaleX = 0.8f + 0.2f * glow
                        scaleY = scaleX
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/**
 * A picture for each reason, recognisable without reading: Wi-Fi crossed out for no network, faint
 * Wi-Fi for a connection that keeps failing, a padlock for a provider that refuses the channel, a
 * magnifier for one that does not have it, a TV for a decoder that failed (crossed out when it
 * cannot play the channel at all), and "i" for the rest. Drawn here, as the icon set FluxTV carries
 * has no Wi-Fi or TV.
 */
@Composable
fun ProblemIcon(problem: PlaybackProblem, size: Dp, color: Color) {
    val modifier = Modifier.size(size)
    when (problem) {
        PlaybackProblem.NO_NETWORK -> Canvas(modifier) { drawWifi(color, faint = false, crossed = true) }
        PlaybackProblem.CONNECTION -> Canvas(modifier) { drawWifi(color, faint = true, crossed = false) }
        PlaybackProblem.REFUSED -> Image(Icons.Filled.Lock, null, modifier, colorFilter = ColorFilter.tint(color))
        PlaybackProblem.GONE -> Image(Icons.Filled.Search, null, modifier, colorFilter = ColorFilter.tint(color))
        PlaybackProblem.UNAVAILABLE -> Image(Icons.Filled.Info, null, modifier, colorFilter = ColorFilter.tint(color))
        PlaybackProblem.DECODER -> Canvas(modifier) { drawTv(color, crossed = false) }
        PlaybackProblem.CANNOT_PLAY -> Canvas(modifier) { drawTv(color, crossed = true) }
    }
}

/** Wi-Fi: a dot and three arcs over it; [faint] dims the outer two, [crossed] strikes it out. */
private fun DrawScope.drawWifi(color: Color, faint: Boolean, crossed: Boolean) {
    val w = size.minDimension
    val stroke = w * 0.09f
    val base = Offset(size.width / 2, size.height / 2 + w * 0.3f)
    listOf(0.2f, 0.38f, 0.56f).forEachIndexed { i, r ->
        val radius = r * w
        drawArc(
            color = if (faint && i > 0) color.copy(alpha = color.alpha * 0.3f) else color,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(base.x - radius, base.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
    drawCircle(color, radius = stroke * 0.9f, center = base)
    if (crossed) drawSlash(color, stroke)
}

/** A TV set: the screen and its foot; [crossed] strikes it out. */
private fun DrawScope.drawTv(color: Color, crossed: Boolean) {
    val w = size.minDimension
    val stroke = w * 0.08f
    val left = (size.width - w) / 2
    val top = (size.height - w) / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(left + w * 0.1f, top + w * 0.18f),
        size = Size(w * 0.8f, w * 0.54f),
        cornerRadius = CornerRadius(w * 0.06f),
        style = Stroke(stroke)
    )
    drawLine(color, Offset(left + w * 0.5f, top + w * 0.72f), Offset(left + w * 0.5f, top + w * 0.84f), stroke)
    drawLine(color, Offset(left + w * 0.32f, top + w * 0.86f), Offset(left + w * 0.68f, top + w * 0.86f), stroke, StrokeCap.Round)
    if (crossed) drawSlash(color, stroke)
}

/** A stroke from top left to bottom right, with a gap cut around it so it reads over the drawing. */
private fun DrawScope.drawSlash(color: Color, stroke: Float) {
    val w = size.minDimension
    val start = Offset((size.width - w) / 2 + w * 0.12f, (size.height - w) / 2 + w * 0.12f)
    val end = Offset((size.width + w) / 2 - w * 0.12f, (size.height + w) / 2 - w * 0.12f)
    drawLine(Backdrop.copy(alpha = 1f), start, end, stroke * 2.6f, StrokeCap.Round)
    drawLine(color, start, end, stroke, StrokeCap.Round)
}

/** A remote key and what it does, e.g. "▲ ▼  Change channel". */
@Composable
fun KeyHint(key: String, label: String) {
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
