package com.fluxtv.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Puts [LoudnessLeveler] in the player's sound path (Preferences → Even out the volume between
 * channels). It stays in the path when switched off, passing the sound as it is, so that switching it
 * on or off takes effect at once, without restarting the channel.
 */
class EvenVolumeAudioProcessor : BaseAudioProcessor() {

    val leveler = LoudnessLeveler()

    // Sound that is not 16-bit PCM (passed through undecoded, or float) goes past it untouched.
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        leveler.process(inputBuffer, output)
        output.flip()
    }

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
