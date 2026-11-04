package com.crsnow.neckscape.desktop

import com.crsnow.neckscape.ui.AudioInput
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** The default microphone through Java Sound (ALSA/PipeWire on Linux, WASAPI on Windows, Core Audio on the Mac). */
class JvmAudioInput : AudioInput {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sampleRate: Int, onBlock: (FloatArray, Int) -> Unit, onError: (String) -> Unit) {
        stop()
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val line = runCatching {
            AudioSystem.getTargetDataLine(format).apply {
                open(format, 8192)
                start()
            }
        }.getOrElse {
            onError("Couldn't open a microphone (${it.message ?: it.javaClass.simpleName}).")
            return
        }
        running = true
        thread = Thread({
            val bytes = ByteArray(2048)
            val block = FloatArray(bytes.size / 2)
            while (running) {
                val n = line.read(bytes, 0, bytes.size) / 2
                for (i in 0 until n) block[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
                if (n > 0) onBlock(block, n)
            }
            line.stop()
            line.close()
        }, "tuner-mic").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
