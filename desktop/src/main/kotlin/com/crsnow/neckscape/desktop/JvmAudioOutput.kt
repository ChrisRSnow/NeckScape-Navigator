package com.crsnow.neckscape.desktop

import com.crsnow.neckscape.ui.AudioOutput
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/** Java Sound clip per sound; works the same on Windows and Linux (ALSA/PipeWire). */
class JvmAudioOutput : AudioOutput {
    private var clip: Clip? = null

    @Synchronized
    override fun play(pcm: ShortArray, sampleRate: Int) {
        release()
        if (pcm.isEmpty()) return
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, s ->
            bytes[2 * i] = s.toInt().toByte()
            bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        // No sound device (or it's busy) shouldn't take the app down — just stay silent.
        clip = runCatching {
            AudioSystem.getClip().apply {
                open(format, bytes, 0, bytes.size)
                start()
            }
        }.onFailure { System.err.println("NeckScape: no audio (${it.message})") }.getOrNull()
    }

    @Synchronized
    override fun release() {
        clip?.run {
            stop()
            close()
        }
        clip = null
    }
}
