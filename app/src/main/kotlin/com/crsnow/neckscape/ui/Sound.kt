package com.crsnow.neckscape.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.crsnow.neckscape.theory.Instrument
import com.crsnow.neckscape.theory.NoteEvent
import com.crsnow.neckscape.theory.PluckSynth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The one platform-specific piece of sound: get 16-bit mono PCM out of the speaker. */
interface AudioOutput {
    /** Starts [pcm] playing, cutting off anything already playing. */
    fun play(pcm: ShortArray, sampleRate: Int)
    fun release()
}

/** Renders notes off the main thread and hands them to the platform output. */
class NotePlayer(private val output: AudioOutput) {
    /** The sound everything plays with. */
    @Volatile var instrument: Instrument = Instrument.Acoustic
    /** The reference pitch everything plays at: A4 in Hz. */
    @Volatile var referenceA: Double = 440.0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun play(events: List<NoteEvent>, ring: Double = 1.6) {
        job?.cancel()
        job = scope.launch {
            val pcm = PluckSynth.render(events, ring, instrument, referenceA)
            output.play(pcm, PluckSynth.SAMPLE_RATE)
        }
    }

    fun playNote(midi: Int) = play(listOf(NoteEvent(midi, 0.0)))

    fun stop() {
        job?.cancel()
        output.play(ShortArray(0), PluckSynth.SAMPLE_RATE)
    }

    fun release() = output.release()
}

/** Provided by each platform's entry point; null (silent) in previews. */
val LocalNotePlayer = staticCompositionLocalOf<NotePlayer?> { null }
