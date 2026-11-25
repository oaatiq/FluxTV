package com.fluxtv.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class LoudnessLevelerTest {

    @Test
    fun offPassesTheSoundAsItIs() {
        val leveler = LoudnessLeveler().apply { enabled = false; start(RATE, CHANNELS) }
        val input = tone(seconds = 1.0, amplitude = 20_000) + tone(seconds = 1.0, amplitude = 500)
        assertArrayEquals(input, run(leveler, input, chunkFrames = 333))
    }

    @Test
    fun everySampleComesOutWhateverTheChunkSizes() {
        for (chunk in listOf(1, 7, 480, 1024, 4096)) {
            val leveler = LoudnessLeveler().apply { start(RATE, CHANNELS) }
            val input = tone(seconds = 0.5, amplitude = 600) + tone(seconds = 0.5, amplitude = 20_000)
            assertEquals(input.size, run(leveler, input, chunk).size)
        }
    }

    @Test
    fun quietChannelIsTurnedUpAndLoudOneDown() {
        val quiet = LoudnessLeveler().apply { start(RATE, CHANNELS) }
        run(quiet, tone(seconds = 6.0, amplitude = 600))
        assertTrue("quiet: ${quiet.gainDb} dB", quiet.gainDb in 9.0..10.1)

        val loud = LoudnessLeveler().apply { start(RATE, CHANNELS) }
        run(loud, tone(seconds = 6.0, amplitude = 30_000))
        assertTrue("loud: ${loud.gainDb} dB", loud.gainDb in -12.1..-9.0)
    }

    /**
     * A loud passage on a channel that was turned up: the limiter takes the gain down, smoothly and
     * before the passage starts, so there is no step between two samples (a click) and no peak over
     * the ceiling.
     */
    @Test
    fun limiterNeitherClicksNorLetsPeaksThrough() {
        val leveler = LoudnessLeveler().apply { start(RATE, CHANNELS) }
        // Off a block boundary on purpose, so the loud part starts inside one.
        val input = tone(seconds = 6.0 + 123.0 / RATE, amplitude = 600) + tone(seconds = 2.0, amplitude = 20_000)
        val output = run(leveler, input)

        var largestStep = 0.0
        val last = DoubleArray(CHANNELS) { Double.NaN }
        for (i in input.indices) {
            val c = i % CHANNELS
            if (abs(input[i].toInt()) < 400) {
                last[c] = Double.NaN // too small to read the gain from without rounding noise
                continue
            }
            val g = output[i].toDouble() / input[i]
            if (!last[c].isNaN()) largestStep = max(largestStep, abs(g - last[c]))
            last[c] = g
        }
        assertTrue("gain stepped by $largestStep between two samples", largestStep < 0.02)

        val ceiling = 0.89 * 32_768 + 1
        val peak = output.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak over the ceiling $ceiling", peak <= ceiling)
    }

    /** A quiet channel was turned up by 10 dB; the next channel, at a normal level, does not start that loud. */
    @Test
    fun nextChannelDoesNotStartTurnedUp() {
        val leveler = LoudnessLeveler().apply { start(RATE, CHANNELS) }
        run(leveler, tone(seconds = 6.0, amplitude = 600))
        assertTrue(leveler.gainDb > 9.0)

        leveler.start(RATE, CHANNELS) // channel change
        assertTrue("starts at ${leveler.gainDb} dB", leveler.gainDb <= 0.0)
        val input = tone(seconds = 0.05, amplitude = 8_000)
        val output = run(leveler, input)
        val ratio = output.maxOf { abs(it.toInt()) }.toDouble() / input.maxOf { abs(it.toInt()) }
        assertTrue("first 50 ms came out ${"%.2f".format(ratio)}x as loud", ratio < 1.1)
    }

    /**
     * Through Media3's own audio pipeline, driven as the audio sink drives it: the AudioTrack takes a
     * little of the output at a time, and the end of the sound (or a drain to change format) is
     * queued while the last output is still only partly played. Every sample comes out, in order,
     * including what the leveler held back, and the pipeline then ends.
     */
    @Test
    fun audioPipelineGetsEverySampleBackInOrder() {
        val processor = EvenVolumeAudioProcessor()
        processor.leveler.enabled = false // so what comes out can be compared with what went in
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        pipeline.configure(AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        pipeline.flush()
        assertTrue(processor.isActive)

        val input = tone(seconds = 0.3, amplitude = 600) + tone(seconds = 0.3, amplitude = 20_000)
        val out = ArrayList<Short>(input.size)
        // Takes at most [room] bytes of output, as an AudioTrack with that much room left does.
        fun play(room: Int) {
            var left = room
            while (left >= 2) {
                val o = pipeline.output
                if (!o.hasRemaining()) return
                while (left >= 2 && o.hasRemaining()) {
                    out += o.short
                    left -= 2
                }
            }
        }
        var i = 0
        while (i < input.size) {
            val n = min(1_100 * CHANNELS, input.size - i)
            val buffer = bytes(input, i, n)
            while (buffer.hasRemaining()) {
                pipeline.queueInput(buffer) // takes nothing while the last output is still waiting
                play(room = 1_000)
            }
            i += n
        }
        pipeline.queueEndOfStream()
        var rounds = 0
        while (!pipeline.isEnded && rounds++ < 10_000) play(room = 1_000)
        assertTrue(pipeline.isEnded)
        assertArrayEquals(input, out.toShortArray())
    }

    /** Puts [input] through [leveler] [chunkFrames] frames at a time, then drains it. */
    private fun run(leveler: LoudnessLeveler, input: ShortArray, chunkFrames: Int = 1024): ShortArray {
        val output = ShortArray(input.size)
        var written = 0
        fun collect(buffer: ByteBuffer) {
            buffer.flip()
            while (buffer.hasRemaining()) output[written++] = buffer.short
        }
        var i = 0
        while (i < input.size) {
            val n = min(chunkFrames * CHANNELS, input.size - i)
            val buffer = bytes(input, i, n)
            // Sized as the processor sizes it: writing more would overflow.
            val out = ByteBuffer.allocateDirect(leveler.outputBytesFor(buffer.remaining())).order(ByteOrder.nativeOrder())
            leveler.process(buffer, out)
            assertFalse(buffer.hasRemaining())
            collect(out)
            i += n
        }
        val tail = ByteBuffer.allocateDirect(leveler.heldBytes()).order(ByteOrder.nativeOrder())
        leveler.drain(tail)
        collect(tail)
        assertEquals(input.size, written)
        assertEquals(0, leveler.heldBytes())
        return output
    }

    private fun bytes(samples: ShortArray, from: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (k in 0 until count) buffer.putShort(samples[from + k])
        buffer.flip()
        return buffer
    }

    /** A 1 kHz tone, the same on both channels. */
    private fun tone(seconds: Double, amplitude: Int): ShortArray {
        val frames = (seconds * RATE).roundToInt()
        return ShortArray(frames * CHANNELS) { i -> (amplitude * sin(2 * PI * 1_000 * (i / CHANNELS) / RATE)).roundToInt().toShort() }
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
    }
}
