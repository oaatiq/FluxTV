package com.fluxtv.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Puts [LoudnessLeveler] in the player's sound path (Preferences → Even out the volume between
 * channels). It stays in the path when switched off, passing the sound as it is, so that switching it
 * on or off takes effect at once, without restarting the channel.
 *
 * The leveler holds back a block or two of sound to look ahead, so what comes out of one call is
 * not always as long as what went in; the rest comes out with the next, or at the end of the sound.
 */
class EvenVolumeAudioProcessor : BaseAudioProcessor() {

    val leveler = LoudnessLeveler()

    // Sound that is not 16-bit PCM (passed through undecoded, or float) goes past it untouched.
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 also calls this with nothing, after the end of the sound.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(leveler.outputBytesFor(inputBuffer.remaining()))
        leveler.process(inputBuffer, output)
        output.flip()
    }

    /**
     * After the end of the sound, or Media3 draining it to change something (a channel going from
     * stereo to 5.1, say), what the leveler holds back comes out too. Here, and not when the end is
     * queued: Media3 queues it while the last output may still be on its way to the speaker, in the
     * same buffer, and only asks for output again once that has all gone.
     */
    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && leveler.heldBytes() > 0) {
            val output = replaceOutputBuffer(leveler.heldBytes())
            leveler.drain(output)
            output.flip()
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && leveler.heldBytes() == 0

    // A flush comes with each new channel (and each restart of one), with the format now in use.
    override fun onFlush() {
        if (inputAudioFormat != AudioFormat.NOT_SET) {
            leveler.start(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
    }

    override fun onReset() {
        leveler.reset()
    }
}
