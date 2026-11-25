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
 * change it follows the new channel faster for a few seconds, starting from no more than the sound's
 * own level: the gain a quiet channel was turned up by is not carried onto the next one, which
 * would blast for the second it took to come down.
 *
 * A limiter keeps what was turned up from going past full scale. It looks one block (10 ms) ahead,
 * so the gain comes down smoothly over the block before a loud moment rather than in one step on it:
 * a gain that jumps between two samples is heard as a click, and a loud passage on a channel that
 * was turned up used to crackle with them. The sound leaves a block or two later for it, which does
 * not move it against the picture: Media3 times the sound by the samples the speaker has played,
 * and the first one out is still the first one in.
 *
 * It works on 16-bit PCM, which is what Media3 hands audio processors unless float output is on.
 * Sound passed through undecoded to an amplifier or the TV set (AC-3 over HDMI, say) cannot be
 * levelled, and is left as it is. Not thread-safe, except [enabled] and [gainDb]: the playback
 * thread uses it.
 */
class LoudnessLeveler {

    /**
     * Off, the sound passes as it is (after a 10 ms fade to it, rather than a click). Changed from
     * the main thread; the playback thread reads it.
     */
    @Volatile
    var enabled = true

    private var sampleRate = 0
    private var channelCount = 0

    /** Frames per block: the sound is measured, and its gain set, a block at a time. */
    private var blockFrames = 0

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

    /** The block being filled from the input, and the frames in it so far. */
    private var filling = ShortArray(0)
    private var fillingFrames = 0

    /**
     * The last whole block, held back until the one after it is in, so that its gain can come down
     * ahead of a loud start to that one. [heldMost] is the most gain its own loudest sample allows.
     */
    private var held = ShortArray(0)
    private var heldFrames = 0
    private var heldMost = 1.0

    /** The gain on the last sample put out: the next block's gain starts from it. */
    @Volatile
    private var applied = 1.0

    /** The gain applied now, in dB, for Diagnostics. Read from the main thread, where it may lag a little. */
    val gainDb: Double get() = 20 * log10(applied)

    /** Starts (again) on sound of this format, as after a channel change. */
    fun start(sampleRate: Int, channelCount: Int) {
        if (sampleRate != this.sampleRate || channelCount != this.channelCount) {
            this.sampleRate = sampleRate
            this.channelCount = channelCount
            blockFrames = (sampleRate / BLOCKS_PER_SECOND).coerceAtLeast(1)
            filling = ShortArray(blockFrames * channelCount)
            held = ShortArray(blockFrames * channelCount)
            lastIn = FloatArray(channelCount)
            lastOut = FloatArray(channelCount)
            val rc = 1.0 / (2 * Math.PI * HIGH_PASS_HZ)
            highPass = (rc / (rc + 1.0 / sampleRate)).toFloat()
        } else {
            lastIn.fill(0f)
            lastOut.fill(0f)
        }
        // What was held back belongs to the sound before: a flush lets it go, as Media3 does its own.
        fillingFrames = 0
        heldFrames = 0
        // A channel turned down carries that on, but none starts turned up: it is brought up once
        // measured, at the fast pace below.
        gain = min(gain, 1.0)
        limiter = 1.0
        applied = gain
        fastFramesLeft = (FAST_SECONDS * sampleRate).toInt()
    }

    /** Forgets everything measured, as for a new player. */
    fun reset() {
        level = -1.0
        gain = 1.0
        limiter = 1.0
        applied = 1.0
        lastIn.fill(0f)
        lastOut.fill(0f)
        fillingFrames = 0
        heldFrames = 0
        fastFramesLeft = 0
    }

    /** The most bytes [process] can put out when handed [inputBytes] more. */
    fun outputBytesFor(inputBytes: Int): Int {
        val frameBytes = 2 * channelCount
        if (frameBytes <= 0) return inputBytes
        val frames = fillingFrames + inputBytes / frameBytes
        return frames / blockFrames * blockFrames * frameBytes
    }

    /** The bytes held back, which [drain] puts out. */
    fun heldBytes(): Int = (heldFrames + fillingFrames) * 2 * channelCount

    /**
     * Reads the 16-bit frames from [input]'s position to its limit, and puts in [output], from its
     * position, the levelled sound that is ready: [outputBytesFor] at most. Both buffers keep their
     * own byte order (the platform's, in Media3).
     */
    fun process(input: ByteBuffer, output: ByteBuffer) {
        val channels = channelCount
        if (channels <= 0) {
            output.put(input)
            return
        }
        val frameBytes = 2 * channels
        while (input.remaining() >= frameBytes) {
            val frames = min(blockFrames - fillingFrames, input.remaining() / frameBytes)
            val start = input.position()
            val offset = fillingFrames * channels
            for (i in 0 until frames * channels) filling[offset + i] = input.getShort(start + 2 * i)
            input.position(start + frames * frameBytes)
            fillingFrames += frames
            if (fillingFrames == blockFrames) endBlock(output)
        }
        // A broken last frame, which no decoder hands over, is let go rather than block the sound.
        input.position(input.limit())
    }

    /**
     * The end of the sound, or Media3 draining it to change something (its format, say): puts what
     * is held back in [output] ([heldBytes]).
     */
    fun drain(output: ByteBuffer) {
        if (fillingFrames > 0) endBlock(output)
        if (heldFrames > 0) writeHeld(output, min(applied, heldMost))
    }

    /** Measures the block filled, puts out the one held before it, and holds this one in its place. */
    private fun endBlock(output: ByteBuffer) {
        val most = measure(filling, fillingFrames)
        if (heldFrames > 0) writeHeld(output, end = min(heldMost, most))
        val next = held
        held = filling
        heldFrames = fillingFrames
        heldMost = most
        filling = next
        fillingFrames = 0
    }

    /**
     * Puts out the held block with its gain moving evenly from the last sample's to [end]. Neither
     * is above [heldMost], so no sample of the block goes past the ceiling either.
     */
    private fun writeHeld(output: ByteBuffer, end: Double) {
        val channels = channelCount
        val frames = heldFrames
        val from = min(applied, heldMost)
        val delta = (end - from) / frames
        for (f in 0 until frames) {
            val g = from + delta * (f + 1)
            val base = f * channels
            for (c in 0 until channels) {
                val y = (held[base + c] * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(y.toShort())
            }
        }
        applied = end
        heldFrames = 0
    }

    /**
     * Measures [frames] frames of [block] and moves the gain on by their length. Returns the most
     * gain the block can have: the level's, less what the limiter takes off for its loudest sample.
     */
    private fun measure(block: ShortArray, frames: Int): Double {
        val channels = channelCount
        val samples = frames * channels

        // Its weighted loudness, and its loudest sample.
        var sum = 0.0
        var peak = 0
        for (i in 0 until samples) {
            val x = block[i].toInt()
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

        if (!enabled) {
            gain = 1.0
            limiter = 1.0
            return 1.0
        }

        // The gain the level asks for, reached at a pace the ear does not notice.
        val wanted = if (level < 0) 1.0 else sqrt(TARGET_MEAN_SQUARE / level).coerceIn(MIN_GAIN, MAX_GAIN)
        val step = exp(GAIN_DB_PER_SECOND * DB_TO_LN * seconds)
        gain = wanted.coerceIn(gain / step, gain * step)

        // The limiter comes down for a peak that would go past the ceiling, and back up slowly.
        limiter = min(1.0, limiter * exp(RELEASE_DB_PER_SECOND * DB_TO_LN * seconds))
        val peakOut = peak / FULL_SCALE * gain
        if (peakOut * limiter > CEILING) limiter = CEILING / peakOut
        return gain * limiter
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
