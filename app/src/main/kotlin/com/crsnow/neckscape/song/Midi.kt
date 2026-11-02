package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Tuning
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A note to write: start and length in quarter notes. */
data class MidiNote(val start: Double, val length: Double, val midi: Int, val velocity: Int = 90)

/**
 * Standard MIDI files, read and written in plain Kotlin (Android has no
 * javax.sound.midi). Writing gives a type-1 file: a tempo/time-signature
 * track plus one guitar track. Reading turns one track into a tab, placing
 * the notes on the neck with [Fingering].
 */
object Midi {
    private const val PPQ = 480

    /** [bpm] is quarter notes per minute. */
    fun write(notes: List<MidiNote>, title: String, bpm: Double, time: TimeSignature, markers: List<Pair<Double, String>> = emptyList()): ByteArray {
        val conductor = Track().apply {
            meta(0, 0x03, title.toByteArray(Charsets.UTF_8))
            val usPerQuarter = (60_000_000.0 / bpm).roundToInt()
            meta(0, 0x51, byteArrayOf((usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte()))
            val denPower = Integer.numberOfTrailingZeros(time.unit)
            meta(0, 0x58, byteArrayOf(time.beats.toByte(), denPower.toByte(), 24, 8))
            for ((at, text) in markers) meta(ticks(at), 0x06, text.toByteArray(Charsets.UTF_8))
        }
        val guitar = Track().apply {
            meta(0, 0x03, "Guitar".toByteArray())
            event(0, byteArrayOf(0xC0.toByte(), 25)) // GM 26: acoustic guitar (steel)
            for (n in notes) {
                event(ticks(n.start), byteArrayOf(0x90.toByte(), n.midi.coerceIn(0, 127).toByte(), n.velocity.coerceIn(1, 127).toByte()))
                event(ticks(n.start + n.length), byteArrayOf(0x80.toByte(), n.midi.coerceIn(0, 127).toByte(), 0))
            }
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.int32(6); out.int16(1); out.int16(2); out.int16(PPQ)
        for (t in listOf(conductor, guitar)) {
            val body = t.bytes()
            out.write("MTrk".toByteArray())
            out.int32(body.size)
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun ticks(quarters: Double) = (quarters * PPQ).roundToInt().coerceAtLeast(0)

    /** Collects timed events, then writes them sorted with delta times (note-offs before note-ons at a tick). */
    private class Track {
        private val events = ArrayList<Triple<Int, Int, ByteArray>>() // tick, order, bytes

        fun meta(tick: Int, type: Int, data: ByteArray) {
            events += Triple(tick, 0, byteArrayOf(0xFF.toByte(), type.toByte()) + vlq(data.size) + data)
        }

        fun event(tick: Int, data: ByteArray) {
            val order = if ((data[0].toInt() and 0xF0) == 0x80) 1 else 2
            events += Triple(tick, order, data)
        }

        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            var last = 0
            for ((tick, _, data) in events.sortedWith(compareBy({ it.first }, { it.second }))) {
                out.write(vlq(tick - last))
                out.write(data)
                last = tick
            }
            out.write(vlq(0)); out.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            return out.toByteArray()
        }
    }

    private fun vlq(value: Int): ByteArray {
        var v = value
        val bytes = ArrayList<Byte>()
        bytes += (v and 0x7F).toByte()
        v = v shr 7
        while (v > 0) {
            bytes += ((v and 0x7F) or 0x80).toByte()
            v = v shr 7
        }
        return bytes.reversed().toByteArray()
    }

    private fun ByteArrayOutputStream.int32(v: Int) = write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    private fun ByteArrayOutputStream.int16(v: Int) = write(byteArrayOf((v shr 8).toByte(), v.toByte()))

    // ---- Reading ----

    private class RNote(val start: Long, var end: Long, val midi: Int, val channel: Int)
    private class RTrack(var name: String? = null, val notes: ArrayList<RNote> = ArrayList(), val markers: ArrayList<Pair<Long, String>> = ArrayList())

    fun read(bytes: ByteArray, fileName: String, part: String?, track: String?, guitar: Tuning): ImportResult {
        val r = Reader(bytes)
        if (r.ascii(4) != "MThd") throw BridgeError("PARSE_FAILED", "Not a MIDI file")
        val headerLen = r.u32()
        r.u16() // format
        val count = r.u16()
        val division = r.u16()
        r.skip(headerLen - 6)
        if (division and 0x8000 != 0) throw BridgeError("UNSUPPORTED_FORMAT", "SMPTE-timed MIDI files aren't supported", "format")
        var usPerQuarter: Int? = null
        var time: TimeSignature? = null
        val tracks = ArrayList<RTrack>()
        repeat(count.coerceAtMost(256)) {
            if (r.remaining() < 8) return@repeat
            val id = r.ascii(4)
            val len = r.u32()
            if (id != "MTrk") { r.skip(len); return@repeat }
            val end = r.pos + len
            val t = RTrack()
            var tick = 0L
            var status = 0
            val open = HashMap<Int, RNote>() // channel*128+key
            while (r.pos < end) {
                tick += r.vlq()
                var b = r.u8()
                if (b < 0x80) { r.pos--; b = status } else if (b < 0xF0) status = b
                when {
                    b == 0xFF -> {
                        val type = r.u8()
                        val data = r.bytes(r.vlq().toInt())
                        when (type) {
                            0x03 -> if (t.name == null) t.name = String(data, Charsets.ISO_8859_1).trim()
                            0x06 -> t.markers += tick to String(data, Charsets.ISO_8859_1).trim()
                            0x01 -> String(data, Charsets.ISO_8859_1).trim().takeIf { Importers.SECTION_WORDS.matches(it) }?.let { t.markers += tick to it }
                            0x51 -> if (usPerQuarter == null && data.size == 3) usPerQuarter = ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                            0x58 -> if (time == null && data.size >= 2) time = TimeSignature(data[0].toInt().coerceIn(1, 32), 1 shl data[1].toInt().coerceIn(0, 5))
                        }
                    }
                    b == 0xF0 || b == 0xF7 -> r.skip(r.vlq().toInt())
                    else -> {
                        val ch = b and 0x0F
                        when (b and 0xF0) {
                            0x80, 0x90 -> {
                                val key = r.u8()
                                val vel = r.u8()
                                if ((b and 0xF0) == 0x90 && vel > 0) {
                                    open.remove(ch * 128 + key)?.let { it.end = tick }
                                    val n = RNote(tick, tick, key, ch)
                                    open[ch * 128 + key] = n
                                    t.notes += n
                                } else open.remove(ch * 128 + key)?.let { it.end = tick }
                            }
                            0xA0, 0xB0, 0xE0 -> r.skip(2)
                            0xC0, 0xD0 -> r.skip(1)
                            else -> throw BridgeError("PARSE_FAILED", "Bad MIDI event 0x${b.toString(16)}")
                        }
                    }
                }
            }
            open.values.forEach { if (it.end == it.start) it.end = it.start + division }
            r.pos = end
            tracks += t
        }
        val playable = tracks.filter { t -> t.notes.any { it.channel != 9 } }
        if (playable.isEmpty()) throw BridgeError("PARSE_FAILED", "No notes outside the drum channel in $fileName")
        val names = playable.mapIndexed { i, t -> t.name?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}" }
        val chosen = playable[chooseTrack(names, track, part)]
        val ts = time ?: TimeSignature(4, 4)
        val measureTicks = (ts.quarters * division).toLong().coerceAtLeast(1)
        // Quantise to 1/12 of a quarter: catches sixteenths and triplets.
        fun q(ticks: Long) = (ticks * 12.0 / division).roundToInt() / 12.0
        val fingering = Fingering(guitar)
        val markers = tracks.flatMap { it.markers }.associate { (tick, text) -> (tick / measureTicks).toInt() to text }
        val notes = chosen.notes.filter { it.channel != 9 }
        val lastMeasure = ((notes.maxOf { it.start }) / measureTicks).toInt()
        var dropped = 0
        val measures = (0..lastMeasure).map { m ->
            val inBar = notes.filter { it.start / measureTicks == m.toLong() }.groupBy { q(it.start - m * measureTicks) }
            val events = inBar.entries.sortedBy { it.key }.map { (at, group) ->
                val placed = fingering.place(group.map { it.midi })
                dropped += group.map { it.midi }.distinct().size - placed.size
                val len = q(group.maxOf { it.end - it.start }).coerceIn(1.0 / 12, ts.quarters - at)
                TabEvent(at, len, placed)
            }
            Measure(events, markers[m])
        }
        val tab = Tab(
            title = tracks.firstOrNull()?.name?.takeIf { it.isNotBlank() && tracks.size > 1 } ?: Importers.fileTitle(fileName),
            attribution = null, source = Source("user_file", file = fileName), tuning = guitar, capo = 0,
            tempoBpm = usPerQuarter?.let { 60_000_000.0 / it / ts.pulse }, timeSignature = ts, measures = measures,
        )
        val note = listOfNotNull(
            if (names.size > 1) "track '${names[playable.indexOf(chosen)]}' of ${names.size}" else null,
            if (dropped > 0) "$dropped notes were out of the guitar's range and left out" else null,
        ).joinToString("; ").ifEmpty { null }
        return ImportResult(tab, note = note)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        private fun need(n: Int) { if (n < 0 || pos + n > b.size) throw BridgeError("PARSE_FAILED", "MIDI file ends early") }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun skip(n: Int) { need(n); pos += n }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
        fun ascii(n: Int) = String(bytes(n), Charsets.ISO_8859_1)
        fun vlq(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            return v
        }
    }
}
