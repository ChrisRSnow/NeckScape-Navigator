package com.crsnow.neckscape.theory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class PitchTest {
    private val rate = PluckSynth.SAMPLE_RATE

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** A string-like tone: a fundamental with weaker harmonics (the 2nd strong, as on a guitar), plus noise. */
    private fun tone(f: Double, noise: Double = 0.0, seed: Int = 1): FloatArray {
        val random = Random(seed)
        return FloatArray(PitchDetector.WINDOW) { i ->
            val t = i.toDouble() / rate
            val s = 0.5 * sin(2 * PI * f * t) + 0.45 * sin(4 * PI * f * t) + 0.2 * sin(6 * PI * f * t) + 0.1 * sin(8 * PI * f * t)
            (0.4 * s + noise * (random.nextDouble() - 0.5)).toFloat()
        }
    }

    @Test fun `every open string of every preset is heard within a cent`() {
        for (tuning in Tunings.all) for (m in tuning.midi) {
            for (offset in listOf(-30.0, 0.0, 12.0)) {
                val f = hz(m) * 2.0.pow(offset / 1200)
                val heard = PitchDetector.detect(tone(f), rate)!!.hz
                assertTrue("${tuning.name} ${m} ${offset}¢: heard ${cents(heard, f)}¢ off", abs(cents(heard, f)) < 1.0)
            }
        }
    }

    @Test fun `noise doesn't throw it off`() {
        for (m in listOf(28, 40, 45, 57, 64)) {
            val heard = PitchDetector.detect(tone(hz(m), noise = 0.2, seed = m), rate)!!.hz
            assertTrue("midi $m: ${cents(heard, hz(m))}¢", abs(cents(heard, hz(m))) < 3.0)
        }
    }

    @Test fun `the app's own plucked strings are in tune`() {
        // Each sound, from the low E to the top fret: the reference notes the tuner plays have to be right.
        for (instrument in listOf(Instrument.Acoustic, Instrument.Classical, Instrument.Electric)) for (m in 40..88 step 4) {
            val pcm = PluckSynth.render(listOf(NoteEvent(m, 0.0)), ring = 1.0, instrument = instrument)
            val window = FloatArray(PitchDetector.WINDOW) { pcm[4000 + it] / 32768f }
            val heard = PitchDetector.detect(window, rate)!!.hz
            assertTrue("$instrument midi $m: ${cents(heard, hz(m))}¢", abs(cents(heard, hz(m))) < 1.0)
        }
    }

    @Test fun `an octave slip for a frame or two doesn't move the reading`() {
        val tracker = PitchTracker(rate)
        var t = 0
        fun play(f: Double, seconds: Double) {
            val block = FloatArray((seconds * rate).toInt()) { i -> (0.3 * sin(2 * PI * f * (t + i).toDouble() / rate)).toFloat() }
            t += block.size
            tracker.feed(block)
        }
        play(110.0, 0.6)
        play(220.0, 0.08)
        assertTrue("held A2: ${tracker.hz}", abs(cents(tracker.hz!!, 110.0)) < 2)
        play(220.0, 0.6)
        assertTrue("then A3 once it's clearly meant: ${tracker.hz}", abs(cents(tracker.hz!!, 220.0)) < 2)
    }

    @Test fun `silence and noise aren't notes`() {
        assertNull(PitchDetector.detect(FloatArray(PitchDetector.WINDOW), rate))
        val random = Random(7)
        assertNull(PitchDetector.detect(FloatArray(PitchDetector.WINDOW) { (random.nextDouble() - 0.5).toFloat() }, rate))
    }

    @Test fun `the FFT matches a plain DFT`() {
        val random = Random(3)
        val x = DoubleArray(64) { random.nextDouble() - 0.5 }
        val re = x.copyOf()
        val im = DoubleArray(64)
        fft(re, im)
        for (k in 0 until 64) {
            val dRe = (0 until 64).sumOf { x[it] * kotlin.math.cos(2 * PI * k * it / 64) }
            val dIm = (0 until 64).sumOf { -x[it] * sin(2 * PI * k * it / 64) }
            assertEquals(dRe, re[k], 1e-9)
            assertEquals(dIm, im[k], 1e-9)
        }
        fft(re, im, inverse = true)
        for (k in 0 until 64) assertEquals(x[k], re[k], 1e-12)
    }

    @Test fun `the tracker holds steady through noise, follows a new note and goes blank in silence`() {
        val tracker = PitchTracker(rate)
        fun play(f: Double, seconds: Double, glitches: Boolean = false) {
            val n = (seconds * rate).toInt()
            val random = Random(5)
            val block = FloatArray(n) { i ->
                val t = i.toDouble() / rate
                val s = 0.3 * sin(2 * PI * f * t) + 0.2 * sin(4 * PI * f * t)
                // Short loud clicks now and then.
                val click = if (glitches && i % 9000 < 200) random.nextDouble() - 0.5 else 0.0
                (s + click).toFloat()
            }
            tracker.feed(block)
        }
        play(110.0, 1.0, glitches = true)
        assertTrue("A2 through clicks: ${tracker.hz}", abs(cents(tracker.hz!!, 110.0)) < 2)
        play(147.0, 0.5)
        assertTrue("then D3: ${tracker.hz}", abs(cents(tracker.hz!!, 147.0)) < 2)
        tracker.feed(FloatArray(rate))
        assertNull(tracker.hz)
    }

    @Test fun `the target string doesn't flicker between two`() {
        val standard = Tunings.all.first()
        // Halfway between A2 and D3 (C3 is 3 semitones above A, 2 below D): held on A if A was the target.
        val c3 = hz(48)
        assertEquals(2, tunerReading(c3, standard).string)
        assertEquals(1, tunerReading(c3, standard, previous = 1).string)
        // Once it's clearly nearer D, it moves.
        assertEquals(2, tunerReading(hz(49), standard, previous = 1).string)
    }

    @Test fun `a string is ticked once it holds in tune`() {
        val standard = Tunings.all.first()
        val tuned = TunedStrings()
        fun at(cents: Double, t: Double) = tuned.update(tunerReading(hz(45) * 2.0.pow(cents / 1200), standard, locked = 1), t)
        assertTrue(!at(3.0, 0.0))
        assertTrue(!at(-2.0, 0.3))
        assertTrue(at(1.0, 0.65))
        assertEquals(setOf(1), tuned.strings)
        // A wobble just past the edge keeps the tick; clearly off loses it.
        at(7.0, 0.8)
        assertEquals(setOf(1), tuned.strings)
        at(25.0, 0.9)
        assertTrue(tuned.strings.isEmpty())
    }

    @Test fun `readings name the note and the string to tune`() {
        val standard = Tunings.all.first()
        val flatA = tunerReading(hz(45) * 2.0.pow(-20 / 1200.0), standard)
        assertEquals("A2", flatA.note)
        assertEquals(1, flatA.string)
        assertEquals(-20.0, flatA.stringCents, 0.01)
        // A sharp G on the D string: the nearest note is G♯3, but locked to D it's 5 semitones up.
        val g = tunerReading(hz(56), standard, locked = 2)
        assertEquals("G♯3", g.note)
        assertEquals(600.0, g.stringCents, 0.01)
        assertEquals("E2", standard.openNote(0))
        assertEquals("E4", standard.openNote(5))
    }
}
