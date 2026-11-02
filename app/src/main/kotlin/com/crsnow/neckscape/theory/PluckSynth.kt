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
    Electric("Electric (overdrive)"),
    Piano("Piano"),
}

/**
 * Note synthesis in plain Kotlin. Guitars are Karplus–Strong plucked strings:
 * a burst of noise circulating in a delay line one period long, low-pass
 * filtered each pass, decays like a plucked string; the nylon and electric
 * strings change the pluck, the damping and (electric) add an overdrive.
 * The piano adds up slightly inharmonic, decaying partials.
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
        val notes = if (instrument == Instrument.Classical) fingerPicked(events) else events.map { it to 1f }
        for ((event, velocity) in notes) {
            val start = (event.at * SAMPLE_RATE).roundToInt()
            when (instrument) {
                // Guitars: the pick (or finger) position and a few-ms onset keep the attack from clicking.
                Instrument.Acoustic -> pluck(mix, start, samples, event.midi, random, smooth = 2, damp = 0.996, brightness = 0.5f,
                    pluckAt = 0.18, attackMs = 4.0)
                Instrument.Classical -> pluck(mix, start, samples, event.midi, random, smooth = 12, damp = 0.992, brightness = 0.5f,
                    body = 0.22f, pluckAt = 0.27, attackMs = 11.0, gain = velocity)
                Instrument.Electric -> pluck(mix, start, samples, event.midi, random, smooth = 2, damp = 0.9984, brightness = 0.5f,
                    gain = 0.9f, pluckAt = 0.15, attackMs = 3.0)
                Instrument.Piano -> piano(mix, start, samples, event.midi)
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
     * Classical guitar is finger-picked: a chord (notes within a strum of each
     * other) becomes the thumb on the bass, then the fingers together a moment
     * later, a touch softer — not a pick sweeping across.
     */
    private fun fingerPicked(events: List<NoteEvent>): List<Pair<NoteEvent, Float>> {
        val out = ArrayList<Pair<NoteEvent, Float>>()
        val sorted = events.sortedBy { it.at }
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && sorted[j].at - sorted[j - 1].at <= 0.06) j++
            val chord = sorted.subList(i, j)
            if (chord.size == 1) {
                out += chord[0] to 1f
            } else {
                val start = chord.first().at
                val bass = chord.minBy { it.midi }
                out += bass.copy(at = start) to 1f
                chord.filter { it !== bass }.sortedBy { it.midi }.forEachIndexed { k, n ->
                    out += n.copy(at = start + 0.085 + k * 0.012) to 0.8f
                }
            }
            i = j
        }
        return out
    }

    /**
     * Karplus–Strong. [smooth] passes of averaging soften the pluck (a fingertip
     * rather than a pick); [pluckAt] is where along the string it's plucked
     * (nearer the bridge is brighter), which notches out harsh overtones;
     * [attackMs] eases the note in; [damp] sets how long it rings;
     * [brightness] weights the loop filter (0.5 = the classic two-point
     * average); [body] < 1 adds a one-pole low-pass on the way out, for a
     * darker, woodier tone.
     */
    private fun pluck(
        mix: FloatArray, start: Int, samples: Int, midi: Int, random: Random,
        smooth: Int, damp: Double, brightness: Float, gain: Float = 1f, body: Float = 1f,
        pluckAt: Double = 0.0, attackMs: Double = 0.0,
    ) {
        val period = (SAMPLE_RATE / freq(midi)).roundToInt().coerceAtLeast(2)
        val noise = FloatArray(period) { random.nextFloat() - 0.5f }
        // Plucking a fraction β along the string cancels the harmonics with a node there.
        val offset = (pluckAt * period).roundToInt()
        val line = if (offset > 0) FloatArray(period) { k -> noise[k] - noise[(k + period - offset) % period] } else noise
        repeat(smooth) {
            val copy = line.copyOf()
            for (k in line.indices) line[k] = (copy[(k + period - 1) % period] + 2 * copy[k] + copy[(k + 1) % period]) / 4f
        }
        // Higher strings lose energy faster per pass, so damp them less to even out ring times.
        val damping = (damp + (1 - damp) * 0.75 * (1 - period / 1000.0).coerceIn(0.0, 1.0)).toFloat()
        var idx = 0
        var lp = 0f
        val attack = (attackMs / 1000 * SAMPLE_RATE).coerceAtLeast(1.0)
        for (i in 0 until samples) {
            val pos = start + i
            if (pos >= mix.size) break
            val next = (idx + 1) % period
            lp += body * (line[idx] - lp)
            val out = if (body < 1f) lp else line[idx]
            line[idx] = damping * (brightness * line[next] + (1 - brightness) * line[idx])
            // Raised-cosine onset: the string speaks without a click.
            val onset = if (i < attack) (0.5 - 0.5 * kotlin.math.cos(PI * i / attack)).toFloat() else 1f
            mix[pos] += out * fade(i, samples) * gain * onset
            idx = next
        }
    }

    /**
     * A crunchy overdrive on the whole mix, so chords grit up together like
     * through an amp: tighten the lows, boost the mids into an asymmetric
     * clipper (odd and even harmonics), then a speaker-cabinet roll-off.
     */
    private fun overdrive(mix: FloatArray) {
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val drive = 9f
        val bias = 0.22f
        val offset = tanh(bias.toDouble()).toFloat()
        var low = 0f
        var cab1 = 0f
        var cab2 = 0f
        var dc = 0f
        val aLow = 0.03f   // ~200 Hz: what's taken out before the clipper
        val aCab = 0.33f   // ~3 kHz, two poles: the speaker
        for (i in mix.indices) {
            val x = mix[i] / peak
            low += aLow * (x - low)
            val tight = x - 0.7f * low
            val y = tanh((drive * tight + bias).toDouble()).toFloat() - offset
            dc += 0.0005f * (y - dc) // the bias leaves an offset; take it back out
            cab1 += aCab * (y - dc - cab1)
            cab2 += aCab * (cab1 - cab2)
            mix[i] = cab2 + 0.25f * low // a little clean low end under the crunch
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
        val partials = (1..24).filter { n -> n * f0 * sqrt(1 + b * n * n) < 9_000 }
        // Low notes carry most of their sound in the overtones (and small speakers can't play
        // a 40–120 Hz fundamental anyway), so weight partials away from the very bottom and
        // level each note's overall strength, so the bass end isn't silent.
        fun weight(n: Int): Double {
            val f = n * f0
            return (1.0 / n.toDouble().pow(0.85)) * (f * f / (f * f + 220.0 * 220.0))
        }
        val level = 0.9 / sqrt(partials.sumOf { weight(it).pow(2) })
        val attack = exp(-1.0 / (0.002 * SAMPLE_RATE))
        for (n in partials) {
            val fn = n * f0 * sqrt(1 + b * n * n)
            val amp = weight(n) * level
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

    /** Low to high, a strum's worth apart — how a chord shape sounds when strummed. */
    fun strum(midi: List<Int>, gap: Double = 0.035) = midi.sorted().mapIndexed { i, m -> NoteEvent(m, i * gap) }

    /** Up then back down, one note every [step] seconds. */
    fun upAndDown(midi: List<Int>, step: Double): List<NoteEvent> =
        midi.thereAndBack().mapIndexed { i, m -> NoteEvent(m, i * step) }
}
