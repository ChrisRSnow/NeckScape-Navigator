package com.crsnow.neckscape.theory

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** A note to sound: MIDI pitch, starting [at] seconds into the clip. */
data class NoteEvent(val midi: Int, val at: Double)

/**
 * Karplus–Strong plucked-string synthesis: a burst of noise circulating in a
 * delay line one period long, low-pass filtered each pass, decays like a
 * plucked string. No samples or native code, so it runs anywhere Kotlin does.
 */
object PluckSynth {
    const val SAMPLE_RATE = 44_100

    /** Renders [events] into one mono 16-bit clip, each note ringing for [ring] seconds. */
    fun render(events: List<NoteEvent>, ring: Double = 1.6): ShortArray {
        if (events.isEmpty()) return ShortArray(0)
        val length = ((events.maxOf { it.at } + ring) * SAMPLE_RATE).roundToInt()
        val mix = FloatArray(length)
        val random = Random(42) // same pluck every time
        for (event in events) {
            val freq = 440.0 * 2.0.pow((event.midi - 69) / 12.0)
            val period = (SAMPLE_RATE / freq).roundToInt().coerceAtLeast(2)
            val line = FloatArray(period) { random.nextFloat() - 0.5f }
            // Higher strings lose energy faster per pass, so damp them less to even out ring times.
            val damping = (0.996 + 0.003 * (1 - period / 1000.0).coerceIn(0.0, 1.0)).toFloat()
            val start = (event.at * SAMPLE_RATE).roundToInt()
            val samples = (ring * SAMPLE_RATE).roundToInt()
            var idx = 0
            for (i in 0 until samples) {
                val pos = start + i
                if (pos >= length) break
                val next = (idx + 1) % period
                val out = line[idx]
                line[idx] = damping * 0.5f * (line[idx] + line[next])
                // Short fade so a note cut off by the ring limit doesn't click.
                val fade = ((samples - i) / (0.05f * SAMPLE_RATE)).coerceAtMost(1f)
                mix[pos] += out * fade
                idx = next
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val gain = 0.8f * Short.MAX_VALUE / peak
        return ShortArray(length) { (mix[it] * gain).roundToInt().toShort() }
    }

    /** Low to high, a strum's worth apart — how a chord shape sounds when strummed. */
    fun strum(midi: List<Int>, gap: Double = 0.035) = midi.sorted().mapIndexed { i, m -> NoteEvent(m, i * gap) }

    /** Up then back down, one note every [step] seconds. */
    fun upAndDown(midi: List<Int>, step: Double): List<NoteEvent> =
        midi.thereAndBack().mapIndexed { i, m -> NoteEvent(m, i * step) }
}
