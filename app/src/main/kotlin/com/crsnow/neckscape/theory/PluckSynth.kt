package com.crsnow.neckscape.theory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** A note to sound: MIDI pitch, starting [at] seconds into the clip. */
data class NoteEvent(val midi: Int, val at: Double)

/** The playback sounds, all synthesised (no samples), so they're the same on every platform. */
enum class Instrument(val label: String) {
    Acoustic("Acoustic guitar"),
    Classical("Classical guitar"),
    Electric("Electric (light overdrive)"),
    Piano("Piano"),
    Synth("Synth (clean)"),
}

/**
 * Note synthesis in plain Kotlin. Guitars are Karplus–Strong plucked strings:
 * a burst of noise circulating in a delay line one period long, low-pass
 * filtered each pass, decays like a plucked string; the nylon and electric
 * strings change the pluck, the damping and (electric) add a soft overdrive.
 * The piano adds up slightly inharmonic, decaying partials; the synth is two
 * detuned saws through a low-pass filter.
 */
object PluckSynth {
    const val SAMPLE_RATE = 44_100

    /** Renders [events] into one mono 16-bit clip, each note ringing for [ring] seconds. */
    fun render(events: List<NoteEvent>, ring: Double = 1.6, instrument: Instrument = Instrument.Acoustic): ShortArray {
        if (events.isEmpty()) return ShortArray(0)
        // Pianos and the electric ring on a little longer than a nylon string.
        val tail = when (instrument) {
            Instrument.Piano, Instrument.Electric -> ring * 1.25
            else -> ring
        }
        val length = ((events.maxOf { it.at } + tail) * SAMPLE_RATE).roundToInt()
        val mix = FloatArray(length)
        val random = Random(42) // same pluck every time
        val samples = (tail * SAMPLE_RATE).roundToInt()
        for (event in events) {
            val start = (event.at * SAMPLE_RATE).roundToInt()
            when (instrument) {
                Instrument.Acoustic -> pluck(mix, start, samples, event.midi, random, smooth = 0, damp = 0.996, brightness = 0.5f)
                Instrument.Classical -> pluck(mix, start, samples, event.midi, random, smooth = 10, damp = 0.992, brightness = 0.5f, body = 0.22f)
                Instrument.Electric -> pluck(mix, start, samples, event.midi, random, smooth = 0, damp = 0.9984, brightness = 0.5f, gain = 0.9f)
                Instrument.Piano -> piano(mix, start, samples, event.midi)
                Instrument.Synth -> synth(mix, start, samples, event.midi)
            }
        }
        if (instrument == Instrument.Electric) overdrive(mix)
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = 0.8f * Short.MAX_VALUE / peak
        return ShortArray(length) { (mix[it] * gain).roundToInt().toShort() }
    }

    private fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Short fade so a note cut off by the ring limit doesn't click. */
    private fun fade(i: Int, samples: Int) = ((samples - i) / (0.05f * SAMPLE_RATE)).coerceAtMost(1f)

    /**
     * Karplus–Strong. [smooth] passes of averaging soften the pluck (a fingertip
     * rather than a pick); [damp] sets how long it rings; [brightness] weights
     * the loop filter (0.5 = the classic two-point average); [body] < 1 adds a
     * one-pole low-pass on the way out, for a darker, woodier tone.
     */
    private fun pluck(
        mix: FloatArray, start: Int, samples: Int, midi: Int, random: Random,
        smooth: Int, damp: Double, brightness: Float, gain: Float = 1f, body: Float = 1f,
    ) {
        val period = (SAMPLE_RATE / freq(midi)).roundToInt().coerceAtLeast(2)
        val line = FloatArray(period) { random.nextFloat() - 0.5f }
        repeat(smooth) {
            val copy = line.copyOf()
            for (k in line.indices) line[k] = (copy[(k + period - 1) % period] + 2 * copy[k] + copy[(k + 1) % period]) / 4f
        }
        // Higher strings lose energy faster per pass, so damp them less to even out ring times.
        val damping = (damp + (1 - damp) * 0.75 * (1 - period / 1000.0).coerceIn(0.0, 1.0)).toFloat()
        var idx = 0
        var lp = 0f
        for (i in 0 until samples) {
            val pos = start + i
            if (pos >= mix.size) break
            val next = (idx + 1) % period
            lp += body * (line[idx] - lp)
            val out = if (body < 1f) lp else line[idx]
            line[idx] = damping * (brightness * line[next] + (1 - brightness) * line[idx])
            mix[pos] += out * fade(i, samples) * gain
            idx = next
        }
    }

    /** Overdrive on the whole mix, so chords grit up together like through an amp, then a speaker-ish roll-off. */
    private fun overdrive(mix: FloatArray) {
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val drive = 2.6f
        val norm = tanh(drive.toDouble()).toFloat()
        var lp = 0f
        val a = 0.45f // one-pole low-pass, ~5 kHz
        for (i in mix.indices) {
            val x = mix[i] / peak
            val y = tanh((drive * x + 0.08f * x * x).toDouble()).toFloat() / norm
            lp += a * (y - lp)
            mix[i] = lp
        }
    }

    /**
     * Struck strings: partials slightly sharpened by string stiffness, each
     * decaying (high ones faster) in two stages, two strings per note a hair
     * apart for the piano's slow beating.
     */
    private fun piano(mix: FloatArray, start: Int, samples: Int, midi: Int) {
        val f0 = freq(midi)
        val b = 0.00012 * 2.0.pow((midi - 48) / 24.0) // inharmonicity grows up the keyboard
        val base = 3.2 * 2.0.pow(-(midi - 48) / 30.0) // low notes sustain longer
        val partials = (1..14).filter { n -> n * f0 * sqrt(1 + b * n * n) < 9_000 }
        val attack = exp(-1.0 / (0.002 * SAMPLE_RATE))
        for (n in partials) {
            val fn = n * f0 * sqrt(1 + b * n * n)
            val amp = (1.0 / n.toDouble().pow(1.1)) * if (n == 1) 1.0 else 0.85
            // Envelopes and oscillators run as recurrences (a multiply per sample), not exp/sin calls.
            val fast = exp(-1.0 / (base * 0.18 / n.toDouble().pow(0.6) * SAMPLE_RATE))
            val slow = exp(-1.0 / (base / n.toDouble().pow(0.7) * SAMPLE_RATE))
            for (detune in doubleArrayOf(-0.9, 0.9)) { // cents
                val w = 2 * PI * fn * 2.0.pow(detune / 1200) / SAMPLE_RATE
                val k = 2 * kotlin.math.cos(w)
                var s1 = 0.0
                var s2 = -sin(w)
                var eFast = 0.65 * amp
                var eSlow = 0.35 * amp
                var rise = 1.0
                for (i in 0 until samples) {
                    val pos = start + i
                    if (pos >= mix.size) break
                    val s0 = k * s1 - s2
                    s2 = s1
                    s1 = s0
                    mix[pos] += ((eFast + eSlow) * (1 - rise) * s0).toFloat() * 0.5f * fade(i, samples)
                    eFast *= fast
                    eSlow *= slow
                    rise *= attack
                }
            }
        }
    }

    /** Two detuned band-limited saws, low-passed, with a soft attack, decay to sustain and release. */
    private fun synth(mix: FloatArray, start: Int, samples: Int, midi: Int) {
        val f = freq(midi)
        val incs = doubleArrayOf(f * 2.0.pow(-4 / 1200.0) / SAMPLE_RATE, f * 2.0.pow(4 / 1200.0) / SAMPLE_RATE)
        val phases = doubleArrayOf(0.0, 0.37)
        // Cutoff a few octaves above the note, capped, so it stays smooth ("clean").
        val cutoff = (f * 6).coerceIn(600.0, 3_200.0)
        val a = (1 - exp(-2 * PI * cutoff / SAMPLE_RATE)).toFloat()
        var lp1 = 0f
        var lp2 = 0f
        val release = (0.25 * SAMPLE_RATE).toInt()
        for (i in 0 until samples) {
            val pos = start + i
            if (pos >= mix.size) break
            var s = 0.0
            for (k in 0..1) {
                val p = phases[k]
                var saw = 2 * p - 1
                saw -= polyBlep(p, incs[k])
                s += saw
                phases[k] = (p + incs[k]).let { if (it >= 1) it - 1 else it }
            }
            lp1 += a * (s.toFloat() * 0.5f - lp1)
            lp2 += a * (lp1 - lp2)
            val t = i.toFloat() / SAMPLE_RATE
            val attack = (t / 0.012f).coerceAtMost(1f)
            val decay = 0.7f + 0.3f * exp(-t / 0.35f)
            val rel = ((samples - i).toFloat() / release).coerceAtMost(1f)
            mix[pos] += lp2 * attack * decay * rel * 0.6f
        }
    }

    /** PolyBLEP correction: takes the aliasing edge off a naive sawtooth's jump. */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> (t / dt).let { x -> x + x - x * x - 1 }
        t > 1 - dt -> ((t - 1) / dt).let { x -> x * x + x + x + 1 }
        else -> 0.0
    }

    /** Low to high, a strum's worth apart — how a chord shape sounds when strummed. */
    fun strum(midi: List<Int>, gap: Double = 0.035) = midi.sorted().mapIndexed { i, m -> NoteEvent(m, i * gap) }

    /** Up then back down, one note every [step] seconds. */
    fun upAndDown(midi: List<Int>, step: Double): List<NoteEvent> =
        midi.thereAndBack().mapIndexed { i, m -> NoteEvent(m, i * step) }
}
