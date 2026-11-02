package com.crsnow.neckscape.song

import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Tuning

/*
 * Schema v1, as shared by all the apps and the MCP server (docs/ai-integration-spec.md and
 * the JSON Schema files in schema/v1). Conventions:
 *  - strings in tab payloads are numbered tab-style, 1 = the first (highest) string;
 *    internally index 0 is the lowest string, as everywhere else in the app;
 *  - chord fret arrays run lowest string to highest; null = muted, 0 = open;
 *  - times are in quarter notes: `at` from the start of the measure, `dur` its length.
 */

const val SCHEMA_VERSION = "1.0"

/** Where content came from; AI and online content are labelled on screen. */
data class Source(
    /** ai_generated, user_file, user_typed or online. */
    val kind: String,
    val url: String? = null,
    val author: String? = null,
    val rating: Double? = null,
    /** Shown for user files: the file name. */
    val file: String? = null,
) {
    val label: String
        get() = when (kind) {
            "ai_generated" -> "AI-generated — check by ear"
            "online" -> listOfNotNull(
                "Online" + (url?.let { " · " + hostOf(it) } ?: ""),
                rating?.let { "★ %.1f".format(java.util.Locale.ROOT, it) },
                author?.let { "by $it" },
            ).joinToString("  ·  ")
            "user_file" -> "Your file" + (file?.let { ": $it" } ?: "")
            else -> "Typed by you"
        }

    companion object {
        val KINDS = setOf("ai_generated", "user_file", "user_typed", "online")
        private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
    }
}

data class TimeSignature(val beats: Int, val unit: Int) {
    /** Measure length in quarter notes: 4/4 → 4, 6/8 → 3, 3/4 → 3. */
    val quarters: Double get() = beats * 4.0 / unit

    /** Beats a listener counts: compound time (6/8, 9/8, 12/8) in dotted quarters. */
    val pulse: Double get() = if (unit == 8 && beats % 3 == 0 && beats > 3) 1.5 else 4.0 / unit

    override fun toString() = "$beats/$unit"
}

data class Key(val tonic: Note, val mode: String) {
    override fun toString() = "${tonic.name} $mode"
}

sealed interface Song {
    val title: String
    val attribution: String?
    val source: Source
    val tuning: Tuning?
    val capo: Int
    val tempoBpm: Double?
    val timeSignature: TimeSignature
}

data class Section(val name: String, val bars: List<List<String>>, val repeat: Int = 1)

data class Progression(
    override val title: String,
    override val attribution: String?,
    override val source: Source,
    override val tuning: Tuning?,
    override val capo: Int,
    override val tempoBpm: Double?,
    override val timeSignature: TimeSignature,
    val key: Key?,
    /** open, barre, position_N or drop2: how the app picks voicings it isn't given. */
    val voicingStyle: String,
    /** Chord symbol → frets, lowest string first. */
    val chords: Map<String, List<Int?>>,
    val sections: List<Section>,
) : Song {
    /** Every bar in playing order, sections repeated. */
    val flatBars: List<Pair<Section, List<String>>>
        get() = sections.flatMap { s -> List(s.repeat) { s.bars }.flatten().map { s to it } }
}

data class Technique(val type: String, val semitones: Double? = null, val fret: Int? = null) {
    /** Short tab marking: "b2", "h", "p", "/7", "~", "PM", "<>", "r". */
    val mark: String
        get() = when (type) {
            "bend" -> "b" + (semitones?.let { if (it % 1.0 == 0.0) "${it.toInt()}" else "$it" } ?: "")
            "release" -> "r"
            "slide_to" -> "/" + (fret ?: "")
            "hammer_on" -> "h"
            "pull_off" -> "p"
            "vibrato" -> "~"
            "palm_mute" -> "PM"
            "harmonic" -> "<>"
            "let_ring" -> "LR"
            else -> type
        }

    companion object {
        val TYPES = setOf("bend", "release", "slide_to", "hammer_on", "pull_off", "vibrato", "palm_mute", "harmonic", "let_ring")
    }
}

/** [string] is tab-style: 1 = first (highest) string. */
data class TabNote(val string: Int, val fret: Int, val technique: Technique? = null)

/** An empty [notes] list is a rest. [dur] is null for tabs parsed from text, which have no rhythm. */
data class TabEvent(val at: Double, val dur: Double?, val notes: List<TabNote>)

/** [marker] labels where a part starts ("Solo", "Verse 2"), from imported files. */
data class Measure(val events: List<TabEvent>, val marker: String? = null)

data class Tab(
    override val title: String,
    override val attribution: String?,
    override val source: Source,
    override val tuning: Tuning?,
    override val capo: Int,
    override val tempoBpm: Double?,
    override val timeSignature: TimeSignature,
    val measures: List<Measure>,
) : Song {
    val playable: Boolean get() = measures.all { m -> m.events.all { it.dur != null } }
}

/** A scale to show alone or over the current content. */
data class ScaleOverlay(
    val root: Note,
    /** Index into Scales.families, scale index — or null with [intervals] for a custom one. */
    val scale: Pair<Int, Int>?,
    val intervals: List<Int>,
    val name: String,
    val fretRange: IntRange?,
    val highlightRoots: Boolean,
    val chordTonesOf: String?,
)

/** A problem the app noticed but rendered anyway (the AI can fix it next call). */
data class Warning(val code: String, val path: String, val detail: String) {
    fun toJson() = mapOf("code" to code, "path" to path, "detail" to detail)
}

/** A payload the app couldn't use at all. */
class BridgeError(val code: String, val detail: String, val path: String? = null) : Exception("$code: $detail") {
    fun toJson() = buildMap {
        put("code", code)
        path?.let { put("path", it) }
        put("detail", detail)
    }
}
