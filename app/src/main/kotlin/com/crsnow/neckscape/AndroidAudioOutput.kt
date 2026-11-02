package com.crsnow.neckscape

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.crsnow.neckscape.ui.AudioOutput

/** One static AudioTrack per clip; starting a new clip stops and frees the last. */
class AndroidAudioOutput : AudioOutput {
    private var track: AudioTrack? = null

    @Synchronized
    override fun play(pcm: ShortArray, sampleRate: Int) {
        release()
        if (pcm.isEmpty()) return
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply {
                write(pcm, 0, pcm.size)
                play()
            }
    }

    @Synchronized
    override fun release() {
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }
}
