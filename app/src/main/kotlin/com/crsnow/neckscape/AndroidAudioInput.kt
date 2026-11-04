package com.crsnow.neckscape

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.crsnow.neckscape.ui.AudioInput

/**
 * The microphone for the tuner, read on its own thread. [askPermission] gets the
 * RECORD_AUDIO permission (asking the first time) and answers whether it's granted.
 */
class AndroidAudioInput(
    private val context: Context,
    private val askPermission: (answer: (Boolean) -> Unit) -> Unit,
) : AudioInput {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sampleRate: Int, onBlock: (FloatArray, Int) -> Unit, onError: (String) -> Unit) {
        askPermission { granted ->
            if (granted) record(sampleRate, onBlock, onError)
            else onError("The tuner needs permission to use the microphone (Settings → Apps → NeckScape → Permissions).")
        }
    }

    @SuppressLint("MissingPermission") // checked by askPermission
    private fun record(sampleRate: Int, onBlock: (FloatArray, Int) -> Unit, onError: (String) -> Unit) {
        stop()
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // The rawest source the phone offers: automatic gain and noise suppression bend a tuner's signal.
        val unprocessed = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed },
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val recorder = if (min <= 0) null else sources.firstNotNullOfOrNull { source ->
            runCatching {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 16 * 1024))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        }
        if (recorder == null) {
            onError("Couldn't open the microphone.")
            return
        }
        running = true
        thread = Thread({
            val block = FloatArray(1024)
            recorder.startRecording()
            while (running) {
                val n = recorder.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (n > 0) onBlock(block, n)
                else if (n < 0) {
                    onError("The microphone stopped (error $n).")
                    break
                }
            }
            recorder.stop()
            recorder.release()
        }, "tuner-mic").apply { start() }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}
