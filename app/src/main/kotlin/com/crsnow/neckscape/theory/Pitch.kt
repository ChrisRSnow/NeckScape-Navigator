package com.crsnow.neckscape.theory

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A pitch heard by [PitchDetector]: its frequency, and how clearly periodic the sound was (0–1). */
data class Pitch(val hz: Double, val clarity: Double)

/**
 * YIN pitch detection (de Cheveigné & Kawahara, "YIN, a fundamental frequency
 * estimator for speech and music", JASA 2002): the lag at which the signal best
 * matches a delayed copy of itself, with the difference normalised by its running
 * mean so a lag of two periods can't beat one (no octave-down errors), the first
 * clear dip taken (no octave-up errors), refined between samples with a parabola.
 *
 * The pitch comes from the period, not from the biggest peak of a spectrum: at
 * 4096 samples an FFT bin is 10.8 Hz wide, over two semitones at the low E, and
 * the loudest peak is often the second harmonic. The FFT is used to compute the
 * difference function quickly (correlation by multiplying spectra), O(n log n)
 * rather than O(n × lags).
 */
object PitchDetector {
    /** Samples per analysis: enough for two periods of the lowest note [detect] looks for. */
    const val WINDOW = 4096

    /**
     * The pitch of [samples] (−1..1), between [minHz] and [maxHz], or null if
     * it's too quiet or not clearly one note.
     */
    fun detect(samples: FloatArray, sampleRate: Int, minHz: Double = 28.0, maxHz: Double = 1400.0, threshold: Double = 0.15): Pitch? {
        val maxLag = (sampleRate / minHz).toInt()
        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        // Compare the first w samples with every lagged copy that still fits.
        val w = samples.size - maxLag - 1
        require(w >= maxLag) { "need at least ${2 * maxLag + 1} samples for $minHz Hz" }
        if (sqrt(samples.sumOf { it.toDouble() * it } / samples.size) < 0.002) return null

        // d(τ) = Σ (x[j] − x[j+τ])² = energy of x[0, w) + energy of x[τ, τ+w) − 2 · Σ x[j]·x[j+τ];
        // the energies from running sums, the cross term from FFTs (zero-padded, so it isn't circular).
        var size = 1
        while (size < samples.size + w) size *= 2
        val aRe = DoubleArray(size).also { for (j in 0 until w) it[j] = samples[j].toDouble() }
        val aIm = DoubleArray(size)
        val bRe = DoubleArray(size).also { for (j in samples.indices) it[j] = samples[j].toDouble() }
        val bIm = DoubleArray(size)
        fft(aRe, aIm)
        fft(bRe, bIm)
        for (k in 0 until size) {
            // conj(A)·B: the correlation of the window against the whole signal.
            val r = aRe[k] * bRe[k] + aIm[k] * bIm[k]
            val i = aRe[k] * bIm[k] - aIm[k] * bRe[k]
            bRe[k] = r
            bIm[k] = i
        }
        fft(bRe, bIm, inverse = true)
        val squares = DoubleArray(samples.size + 1).also { s -> for (j in samples.indices) s[j + 1] = s[j] + samples[j].toDouble() * samples[j] }
        val head = squares[w]

        val norm = DoubleArray(maxLag + 2)
        norm[0] = 1.0
        var running = 0.0
        for (lag in 1..maxLag + 1) {
            val d = (head + squares[lag + w] - squares[lag] - 2 * bRe[lag]).coerceAtLeast(0.0)
            running += d
            norm[lag] = if (running == 0.0) 1.0 else d * lag / running
        }

        // The first dip under the threshold, at its floor (the lowest point of the whole dip: noise makes
        // its sides ragged, so the first little minimum can be well off); failing that the deepest dip.
        var best = -1
        var lag = minLag
        while (lag <= maxLag) {
            if (norm[lag] < threshold) {
                var end = lag
                while (end < maxLag && norm[end + 1] < threshold) end++
                best = (lag..end).minBy { norm[it] }
                break
            }
            lag++
        }
        if (best < 0) {
            best = (minLag..maxLag).minBy { norm[it] }
            if (norm[best] > 0.35) return null
        }

        val (a, b, c) = Triple(norm[best - 1], norm[best], norm[best + 1])
        val curve = a - 2 * b + c
        val period = best + if (curve > 0) ((a - c) / (2 * curve)).coerceIn(-0.5, 0.5) else 0.0
        return Pitch(sampleRate / period, (1 - b).coerceIn(0.0, 1.0))
    }
}

/**
 * Microphone samples in, a steady frequency out. The signal is high-passed below
 * the lowest note (rumble, handling noise, DC), and a window of
 * [PitchDetector.WINDOW] samples analysed every [HOP] (75 % overlap, about 40 a
 * second at 44.1 kHz). Unclear frames are dropped; a reading far from the recent
 * ones is an outlier, unless a few in a row agree, which means a new note (many
 * more if it's an octave or a fifth away, the usual detection slips); the
 * frequency shown is the median of the last few, and goes blank after a moment
 * of silence.
 */
class PitchTracker(private val sampleRate: Int) {
    private val highPass = Biquad.highPass(HIGH_PASS_HZ, sampleRate)
    private val ring = FloatArray(PitchDetector.WINDOW)
    private var write = 0
    private var filled = 0
    private var sinceLast = 0
    private val recent = ArrayDeque<Double>()
    private val outliers = ArrayList<Double>()
    private var misses = 0

    /** The steadied frequency, or null when nothing clear has been heard lately. */
    var hz: Double? = null
        private set

    /** Adds samples (−1..1); returns true if [hz] changed. */
    fun feed(block: FloatArray, count: Int = block.size): Boolean {
        var changed = false
        for (i in 0 until count) {
            ring[write] = highPass(block[i])
            write = (write + 1) % ring.size
            if (filled < ring.size) filled++
            if (++sinceLast >= HOP && filled == ring.size) {
                sinceLast = 0
                changed = analyse(FloatArray(ring.size) { ring[(write + it) % ring.size] }) || changed
            }
        }
        return changed
    }

    private fun analyse(window: FloatArray): Boolean {
        val before = hz
        val pitch = PitchDetector.detect(window, sampleRate)?.takeIf { it.clarity >= MIN_CLARITY }
        if (pitch == null) {
            if (++misses >= SILENCE_FRAMES) {
                recent.clear()
                outliers.clear()
                hz = null
            }
            return hz != before
        }
        misses = 0
        val median = recent.sorted().getOrNull(recent.size / 2)
        if (median != null && abs(cents(pitch.hz, median)) > OUTLIER_CENTS) {
            // Off from the recent readings: noise, or a new note if it keeps happening.
            outliers += pitch.hz
            val slip = listOf(2.0, 0.5, 3.0, 1 / 3.0).any { abs(cents(pitch.hz, median * it)) < OUTLIER_CENTS }
            if (outliers.size < if (slip) SLIP_FRAMES else NEW_NOTE_FRAMES) return false
            recent.clear()
            recent.addAll(outliers)
            outliers.clear()
        } else {
            outliers.clear()
            recent.addLast(pitch.hz)
            while (recent.size > MEDIAN_OF) recent.removeFirst()
        }
        hz = recent.sorted()[recent.size / 2]
        return hz != before
    }

    companion object {
        const val HOP = 1024
        const val MIN_CLARITY = 0.8
        const val OUTLIER_CENTS = 50.0
        const val NEW_NOTE_FRAMES = 3
        /** About 0.2 s: an octave or fifth away has to be meant, not a harmonic winning a frame or two. */
        const val SLIP_FRAMES = 8
        /** Below the lowest string of any preset (a 5-string bass's B0 is 30.9 Hz). */
        const val HIGH_PASS_HZ = 25.0
        const val MEDIAN_OF = 7
        /** About 0.4 s of nothing clear at 44.1 kHz. */
        const val SILENCE_FRAMES = 17
    }
}

fun cents(hz: Double, reference: Double) = 1200 * ln(hz / reference) / ln(2.0)

/** A second-order IIR filter, one sample at a time (coefficients from the RBJ Audio EQ Cookbook). */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    operator fun invoke(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x.toDouble()
        y2 = y1
        y1 = y
        return y.toFloat()
    }

    companion object {
        /** Butterworth (Q = 1/√2) high-pass at [hz]. */
        fun highPass(hz: Double, sampleRate: Int): Biquad {
            val w = 2 * kotlin.math.PI * hz / sampleRate
            val alpha = kotlin.math.sin(w) / (2 * sqrt(0.5))
            val cos = kotlin.math.cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + cos) / 2 / a0, -(1 + cos) / a0, (1 + cos) / 2 / a0, -2 * cos / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * What the tuner shows for a pitch: the nearest note (C♯3) and how many cents
 * it's off, and the string being tuned with how many cents it's off its target.
 */
data class TunerReading(val hz: Double, val note: String, val noteCents: Double, val string: Int, val stringCents: Double)

/**
 * The reading for [hz] in [tuning], with A4 at [a4] Hz. The string is [locked] if one is picked,
 * else the nearest; but a [previous] string is kept until another is nearer by
 * a semitone, so the target doesn't flicker on a note between two strings.
 */
fun tunerReading(hz: Double, tuning: Tuning, locked: Int? = null, previous: Int? = null, a4: Double = 440.0): TunerReading {
    val midi = 69 + 12 * ln(hz / a4) / ln(2.0)
    val nearest = midi.roundToInt()
    // Sharps, as tuners name notes.
    val name = Note.spellings(nearest).first().withOctave(nearest)
    fun off(s: Int) = abs(midi - tuning.midi[s])
    val closest = tuning.midi.indices.minBy(::off)
    val string = locked ?: previous?.takeIf { it in tuning.midi.indices && off(it) <= off(closest) + 1 } ?: closest
    return TunerReading(hz, name, (midi - nearest) * 100, string, (midi - tuning.midi[string]) * 100)
}

/**
 * Instruments the tuner tunes besides whatever's on the neck: the bowed strings,
 * in fifths (the double bass in fourths). They're for the tuner only, not neck views.
 */
object TunerInstruments {
    val all: List<Tuning> = listOf(
        Tuning.parse("Violin (GDAE)", "G3 D4 A4 E5"),
        Tuning.parse("Cello (CGDA)", "C2 G2 D3 A3"),
        Tuning.parse("Double bass (EADG)", "E1 A1 D2 G2"),
    )
}

/** A string's open note with its octave, spelled as the tuning names it (E2, E♭4). */
fun Tuning.openNote(string: Int): String =
    runCatching { Note.parse(stringNames[string]).withOctave(midi[string]) }.getOrDefault(stringNames[string])

/**
 * Which strings are in tune: one counts once it's held within [IN_TUNE_CENTS]
 * for [HOLD_SECONDS], and stays ticked until it's heard off again.
 */
class TunedStrings {
    private val tuned = HashSet<Int>()
    private var holding = -1
    private var since = 0.0

    val strings: Set<Int> get() = tuned

    /** Updates from a reading at [seconds]; returns true if the set changed. */
    fun update(reading: TunerReading?, seconds: Double): Boolean {
        if (reading == null) {
            holding = -1
            return false
        }
        val s = reading.string
        if (abs(reading.stringCents) > IN_TUNE_CENTS) {
            holding = -1
            // Clearly off again (not just a wobble at the edge): untick it.
            return abs(reading.stringCents) > 2 * IN_TUNE_CENTS && tuned.remove(s)
        }
        if (holding != s) {
            holding = s
            since = seconds
        }
        return seconds - since >= HOLD_SECONDS && tuned.add(s)
    }

    fun clear() {
        tuned.clear()
        holding = -1
    }

    companion object {
        const val IN_TUNE_CENTS = 5.0
        const val HOLD_SECONDS = 0.6
    }
}
