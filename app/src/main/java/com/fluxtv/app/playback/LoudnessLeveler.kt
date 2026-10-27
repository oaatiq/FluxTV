package com.fluxtv.app.playback

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Brings every channel to about the same loudness, so the Viewer does not reach for the volume at
 * each channel change: a quiet channel is brought up by as much as 10 dB, a loud one down by as much
 * as 12 dB.
 *
 * It measures the sound the way an ear weighs it, roughly (without the deep bass, which sounds far
 * quieter than it measures), and follows that level over a few seconds, so the sound inside one
 * programme keeps its shape while a channel that is quieter or louder than the last is evened out.
 * Silence and very quiet passages are not measured, so they are not turned up. After a channel
 * change it follows the new channel faster for a few seconds. A limiter keeps what was turned up
 * from going past full scale.
 *
 * It works on 16-bit PCM, which is what Media3 hands audio processors unless float output is on.
 * Sound passed through undecoded to an amplifier or the TV set (AC-3 over HDMI, say) cannot be
 * levelled, and is left as it is. Not thread-safe, except [enabled]: the playback thread uses it.
 */
class LoudnessLeveler {

    /** Off, the sound passes as it is. Changed from the main thread; the playback thread reads it. */
    @Volatile
    var enabled = true

    private var sampleRate = 0
    private var channelCount = 0

    // A first-order high-pass filter, per channel, on what is measured (not on what is heard).
    private var highPass = 0f
    private var lastIn = FloatArray(0)
    private var lastOut = FloatArray(0)

    /** The measured loudness, as a mean square of full scale; below zero until something is measured. */
    private var level = -1.0
    private var gain = 1.0
    private var limiter = 1.0

    /** Frames of sound still to measure at the fast pace that follows a channel change. */
    private var fastFramesLeft = 0

    /** The gain applied now, in dB, for Diagnostics. Read from the main thread, where it may lag a little. */
    val gainDb: Double get() = 20 * log10(gain * limiter)

    /** Starts (again) on sound of this format, as after a channel change: the gain carries on from the last. */
    fun start(sampleRate: Int, channelCount: Int) {
        if (sampleRate != this.sampleRate || channelCount != this.channelCount) {
            this.sampleRate = sampleRate
            this.channelCount = channelCount
            lastIn = FloatArray(channelCount)
            lastOut = FloatArray(channelCount)
            val rc = 1.0 / (2 * Math.PI * HIGH_PASS_HZ)
            highPass = (rc / (rc + 1.0 / sampleRate)).toFloat()
        } else {
            lastIn.fill(0f)
            lastOut.fill(0f)
        }
        fastFramesLeft = (FAST_SECONDS * sampleRate).toInt()
    }

    /** Forgets everything measured, as for a new player. */
    fun reset() {
        level = -1.0
        gain = 1.0
        limiter = 1.0
        lastIn.fill(0f)
        lastOut.fill(0f)
        fastFramesLeft = 0
    }

    /**
     * Reads the 16-bit frames from [input]'s position to its limit and puts them, levelled, in
     * [output] from its position. Both buffers keep their own byte order (the platform's, in Media3).
     */
    fun process(input: ByteBuffer, output: ByteBuffer) {
        val channels = channelCount
        val frameBytes = 2 * channels
        if (channels <= 0 || !enabled) {
            gain = 1.0
            limiter = 1.0
            output.put(input)
            return
        }
        val blockFrames = (sampleRate / BLOCKS_PER_SECOND).coerceAtLeast(1)
        while (input.remaining() >= frameBytes) {
            val frames = min(blockFrames, input.remaining() / frameBytes)
            val start = input.position()
            val samples = frames * channels

            // Measure the block: its weighted loudness, and its loudest sample.
            var sum = 0.0
            var peak = 0
            for (i in 0 until samples) {
                val x = input.getShort(start + 2 * i).toInt()
                val a = abs(x)
                if (a > peak) peak = a
                val c = i % channels
                val xf = x.toFloat()
                val y = highPass * (lastOut[c] + xf - lastIn[c])
                lastIn[c] = xf
                lastOut[c] = y
                sum += y * y
            }
            val seconds = frames.toDouble() / sampleRate
            val meanSquare = sum / samples / FULL_SCALE_SQUARED
            if (meanSquare > GATE_MEAN_SQUARE) {
                val tau = if (fastFramesLeft > 0) FAST_TAU_S else SLOW_TAU_S
                level = if (level < 0) meanSquare else level + (meanSquare - level) * (1 - exp(-seconds / tau))
                fastFramesLeft -= frames
            }

            // The gain the level asks for, reached at a pace the ear does not notice.
            val wanted = if (level < 0) 1.0 else sqrt(TARGET_MEAN_SQUARE / level).coerceIn(MIN_GAIN, MAX_GAIN)
            val step = exp(GAIN_DB_PER_SECOND * DB_TO_LN * seconds)
            val newGain = wanted.coerceIn(gain / step, gain * step)

            // The limiter comes down at once for a peak that would go past the ceiling, and back up slowly.
            var newLimiter = min(1.0, limiter * exp(RELEASE_DB_PER_SECOND * DB_TO_LN * seconds))
            val peakOut = peak / FULL_SCALE * newGain
            if (peakOut * newLimiter > CEILING) newLimiter = CEILING / peakOut

            // Gain going up is spread over the block; going down, it applies from its first sample.
            val from = gain * limiter
            val to = newGain * newLimiter
            val first = if (to < from) to else from
            val delta = (to - first) / frames
            for (f in 0 until frames) {
                val g = first + delta * (f + 1)
                val base = start + 2 * f * channels
                for (c in 0 until channels) {
                    val x = input.getShort(base + 2 * c)
                    output.putShort((x * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            input.position(start + frames * frameBytes)
            gain = newGain
            limiter = newLimiter
        }
    }

    private companion object {
        const val FULL_SCALE = 32768.0
        const val FULL_SCALE_SQUARED = FULL_SCALE * FULL_SCALE

        /** Where every channel is brought to: about -20 dB of full scale, leaving room for peaks. */
        const val TARGET_MEAN_SQUARE = 0.01

        /** Quieter than -50 dB of full scale is silence or close to it, and is not measured. */
        const val GATE_MEAN_SQUARE = 1e-5

        /** At most +10 dB for a quiet channel, and -12 dB for a loud one. */
        const val MAX_GAIN = 3.16
        const val MIN_GAIN = 0.25

        /** Peaks are held under -1 dB of full scale. */
        const val CEILING = 0.89

        const val HIGH_PASS_HZ = 150.0
        const val BLOCKS_PER_SECOND = 100
        const val SLOW_TAU_S = 5.0
        const val FAST_TAU_S = 0.6
        const val FAST_SECONDS = 3.0
        const val GAIN_DB_PER_SECOND = 10.0
        const val RELEASE_DB_PER_SECOND = 6.0
        val DB_TO_LN = ln(10.0) / 20
    }
}
