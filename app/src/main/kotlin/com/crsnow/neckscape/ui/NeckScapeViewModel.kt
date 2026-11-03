package com.crsnow.neckscape.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crsnow.neckscape.theory.ChordType
import com.crsnow.neckscape.theory.Chords
import com.crsnow.neckscape.song.BridgeError
import com.crsnow.neckscape.song.Importers
import com.crsnow.neckscape.song.Progression
import com.crsnow.neckscape.song.ScaleOverlay
import com.crsnow.neckscape.song.Song
import com.crsnow.neckscape.song.Tab
import com.crsnow.neckscape.song.barsOf
import com.crsnow.neckscape.song.schedule
import com.crsnow.neckscape.theory.ChordSymbol
import com.crsnow.neckscape.theory.CagedPosition
import com.crsnow.neckscape.theory.Instrument
import com.crsnow.neckscape.theory.Interval
import com.crsnow.neckscape.theory.DiatonicChord
import com.crsnow.neckscape.theory.cagedPositions
import com.crsnow.neckscape.theory.cagedQuality
import com.crsnow.neckscape.theory.diatonicChords
import com.crsnow.neckscape.theory.isMinorQuality
import com.crsnow.neckscape.theory.isStandardShape
import com.crsnow.neckscape.theory.Level
import com.crsnow.neckscape.theory.Note
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Scale
import com.crsnow.neckscape.theory.ScaleFamily
import com.crsnow.neckscape.theory.Scales
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.VoicingFinder
import com.crsnow.neckscape.theory.allowsScale
import com.crsnow.neckscape.theory.nearestChord
import com.crsnow.neckscape.theory.nearestScale
import com.crsnow.neckscape.theory.pickVoicing
import com.crsnow.neckscape.theory.allows
import com.crsnow.neckscape.theory.withCapo
import com.crsnow.neckscape.theory.scaleIndices
import com.crsnow.neckscape.theory.fitsIn
import com.crsnow.neckscape.theory.scaleFor
import com.crsnow.neckscape.theory.tonicChord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * [Note]: every place one note falls on the neck, for the tuning in use.
 * [Song]: a progression or tab pushed by an AI or imported from a file (or an empty neck).
 */
enum class ViewMode { Scale, Chord, Arpeggio, Note, Song }

/** How the notes are shown: on the neck, in standard notation, or as tab. */
enum class Display { Fretboard, Tab, Score }
enum class LabelMode { Notes, Intervals }

const val MIN_TEMPO = 40
const val MAX_TEMPO = 240

/** Frets shown at Beginner level, so the neck isn't overwhelming: open strings to the 4th fret. */
const val BEGINNER_FRETS = 4

/** Stored range end meaning "as far as the neck goes", so it survives fret/string count changes. */
private const val TO_END = Int.MAX_VALUE

data class UiState(
    val viewMode: ViewMode = ViewMode.Scale,
    val display: Display = Display.Fretboard,
    /** Which chords and scales are offered; every launch starts at Beginner. */
    val level: Level = Level.Beginner,
    val root: Note = Note(0),
    val familyIndex: Int = 0,
    val scaleIndex: Int = 0,
    val chordIndex: Int = 0,
    val tuningIndex: Int = 0,
    /** Overrides the preset when the user has edited the tuning. */
    val customTuning: Tuning? = null,
    val fretCount: Int = 22,
    val fretFrom: Int = 0,
    /** Beginner (where every launch starts) shows the first position: open strings to fret 4. */
    val fretTo: Int = BEGINNER_FRETS,
    val stringFrom: Int = 0,
    val stringTo: Int = TO_END,
    val labelMode: LabelMode = LabelMode.Notes,
    val showOrder: Boolean = true,
    /** Scale playback speed, one note per beat. */
    val tempo: Int = 180,
    /** The playback sound. */
    val instrument: Instrument = Instrument.Acoustic,
    val leftHanded: Boolean = false,
    val diatonicSevenths: Boolean = false,
    /** Index into the voicing list, or -1 to show every chord tone. */
    val voicingIndex: Int = -1,
    /** A progression or tab pushed by an AI or imported (shown in ViewMode.Song). */
    val song: Song? = null,
    /** The bar (progression) or measure (tab) on the neck. */
    val songBar: Int = 0,
    /** A scale drawn under whatever else is showing. */
    val overlay: ScaleOverlay? = null,
    /** Notes marked by highlight_notes, with a caption. */
    val highlights: Highlights? = null,
    val capo: Int = 0,
    /** How many pushed changes Undo can take back. */
    val undoDepth: Int = 0,
    /** The CAGED position picked (index into [cagedPositions]), or -1 for all of them. */
    val cagedPick: Int = -1,
    /** A chord of the key picked in the Scale view (index into [keyChords]), or -1 for none. */
    val keyChord: Int = -1,
) {
    val family: ScaleFamily get() = Scales.families[familyIndex]
    val scale: Scale get() = family.scales[scaleIndex]
    val chord: ChordType get() = Chords.all[chordIndex]
    /** The strings as tuned, before any capo. */
    val baseTuning: Tuning get() = customTuning ?: Tunings.all[tuningIndex]

    /** What the frets sound: with a capo, fret 0 is the capo. */
    val tuning: Tuning get() = baseTuning.withCapo(capo)

    /** Seventh chords are past Beginner, so the switch only counts above it. */
    val sevenths: Boolean get() = diatonicSevenths && level != Level.Beginner

    /** The frets in view: a picked CAGED position's window, else the slider range. */
    val fretRange: IntRange
        get() = cagedSelected?.frets ?: fretFrom.coerceIn(0, fretCount).let { from -> from..fretTo.coerceIn(from, fretCount) }

    /** CAGED shapes are offered from Intermediate up, in standard tuning (or one shifted down/up), for scales and chords. */
    val cagedEnabled: Boolean
        get() = level != Level.Beginner && baseTuning.isStandardShape() &&
            viewMode in setOf(ViewMode.Scale, ViewMode.Arpeggio, ViewMode.Chord)

    /** The chords built on each degree of the scale (empty for scales that aren't seven-note). */
    val keyChords: List<DiatonicChord> get() = diatonicChords(scale, root, sevenths)

    /**
     * The chord of the key picked in the Scale view: its notes are ringed in the
     * scale, and at Advanced its CAGED shapes replace the key's own.
     */
    val pickedKeyChord: DiatonicChord?
        get() = if (viewMode == ViewMode.Scale && keyChord >= 0) keyChords.getOrNull(keyChord)?.takeIf { it.type != null && level.allows(it.type) } else null

    /** The picked chord of the key whose CAGED shapes sit over the scale (Advanced only). */
    val shapeChord: DiatonicChord? get() = pickedKeyChord?.takeIf { level == Level.Advanced }

    /** The five shapes up the neck for the current root and quality; empty when CAGED doesn't apply. */
    val cagedPositions: List<CagedPosition>
        get() {
            if (!cagedEnabled) return emptyList()
            // Shapes are relative to the open strings: E standard, or a whole tuning shifted (E♭ standard, a capo).
            val shift = tuning.midi[0] - 40
            val keyed = shapeChord
            val (minor, fifth) = when {
                keyed != null -> cagedQuality(keyed.type!!.intervals)
                viewMode == ViewMode.Scale -> isMinorQuality(scale.intervals) to 0
                else -> cagedQuality(chord.intervals)
            }
            return cagedPositions(Math.floorMod((keyed?.root ?: root).pitchClass - shift, 12), minor, fretCount, fifth)
        }

    val cagedSelected: CagedPosition? get() = if (cagedPick < 0) null else cagedPositions.getOrNull(cagedPick)

    /** String indices, lowest-pitched string = 0. */
    val stringRange: IntRange
        get() = (tuning.strings - 1).let { top -> stringFrom.coerceIn(0, top).let { from -> from..stringTo.coerceIn(from, top) } }

    /** Intervals of whatever is on the fretboard right now. */
    val intervals: List<Interval>
        get() = when (viewMode) {
            ViewMode.Scale -> scale.intervals
            ViewMode.Note -> listOf(Interval(0, 1))
            else -> chord.intervals
        }

    val title: String
        get() = when (viewMode) {
            ViewMode.Scale -> "${root.name} ${scale.name}" + run {
                val keyed = pickedKeyChord?.let { it.type!!.displayName(it.root) }
                val shape = cagedSelected?.shape
                when {
                    keyed != null && shapeChord == null -> " · $keyed chord tones"
                    keyed != null && shape != null -> " · $keyed in the $shape shape"
                    keyed != null -> " · $keyed shapes"
                    shape != null -> " · $shape shape"
                    else -> ""
                }
            }
            ViewMode.Arpeggio -> "${root.name} ${chord.name} arpeggio — ${chord.displayName(root)}"
            ViewMode.Chord -> "${chord.displayName(root)} — ${root.name} ${chord.name}" + (cagedSelected?.let { " · ${it.shape} shape" } ?: "")
            ViewMode.Note -> "Every ${root.name} on the neck"
            ViewMode.Song -> song?.title ?: overlay?.let { "${it.root.name} ${it.name}" } ?: "Nothing loaded"
        }

    /** The chord symbol of the bar on the neck, for a progression. */
    val songChord: String?
        get() = (song as? Progression)?.flatBars?.getOrNull(songBar)?.second?.firstOrNull()
}

/** Notes marked by highlight_notes. */
data class Highlights(val positions: Set<Position>, val label: String?)

/** What playback is doing, for get_state and the Play button. */
data class Playback(val playing: Boolean = false, val looping: Boolean = false, val section: String? = null)

/** Shapes inside the selected frets and strings; open strings only count if fret 0 is included. */
private fun Voicing.fits(frets: IntRange, strings: IntRange) =
    this.frets.withIndex().all { (s, f) -> f == null || (s in strings && f in frets) }

/** The inputs that determine the voicing list. */
private data class VoicingKey(val mode: ViewMode, val tuning: Tuning, val root: Note, val chord: ChordType, val frets: Int)

private val UiState.voicingKey get() = VoicingKey(viewMode, tuning, root, chord, fretCount)

class NeckScapeViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Kept out of UiState: it changes several times a second during playback
    // and has nothing to do with what's selected.
    private val _sounding = MutableStateFlow(emptySet<Position>())

    /** Positions sounding right now; the fretboard rings them. */
    val sounding: StateFlow<Set<Position>> = _sounding.asStateFlow()
    private var followJob: Job? = null
    private var followRuns = 0
    /** The follow run that owns the "playing" state, so an interrupted run still clears it. */
    private var playingRun = 0

    /** Rings each group in turn, [step] seconds apart, then clears after [hold] — in time with the audio. */
    fun follow(groups: List<Set<Position>>, step: Double, hold: Double = 0.5, playing: Boolean = false) {
        followJob?.cancel()
        val run = ++followRuns
        // Play-button playback shows as playing (so the button can stop it); a tapped note doesn't.
        if (playing) {
            playingRun = run
            _playback.value = Playback(playing = true)
        } else if (playingRun != 0) {
            // A tap cuts the run short; a run cancelled before it started wouldn't reach its finally.
            playingRun = 0
            _playback.value = Playback()
        }
        followJob = viewModelScope.launch {
            try {
                for (group in groups) {
                    _sounding.value = group
                    delay((step * 1000).toLong())
                }
                delay((hold * 1000).toLong())
                _sounding.value = emptySet()
            } finally {
                // Finished or cut short (a tap, Stop): unless a newer run took over, playing is over.
                if (playingRun == run) {
                    playingRun = 0
                    _playback.value = Playback()
                }
            }
        }
    }

    /** Sets the ringed positions directly (scripted demos). */
    fun setSounding(positions: Set<Position>) {
        followJob?.cancel()
        _sounding.value = positions
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allVoicings = _state
        .map { it.voicingKey }
        .distinctUntilChanged()
        .mapLatest { key ->
            if (key.mode != ViewMode.Chord) emptyList()
            else VoicingFinder.findAll(key.tuning, key.root, key.chord, key.frets)
        }
        .flowOn(Dispatchers.Default)

    private val windowed = combine(
        allVoicings,
        _state.map { it.fretRange to it.stringRange }.distinctUntilChanged(),
    ) { shapes, (frets, strings) ->
        val fitting = VoicingFinder.bestPerPosition(shapes.filter { it.fits(frets, strings) })
        // Nothing fits the frets on view: the nearest couple of shapes, so the score and tab still show the chord.
        val nearest = if (fitting.isNotEmpty()) emptyList() else VoicingFinder.bestPerPosition(
            shapes.filter { it.fits(0..Int.MAX_VALUE, strings) }
                .sortedBy { v -> v.frets.filterNotNull().sumOf { f -> maxOf(frets.first - f, f - frets.last, 0) } },
        ).take(2)
        fitting to nearest
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Voicing>() to emptyList())

    /** The shapes that fit the frets and strings on view. */
    val voicings: StateFlow<List<Voicing>> = windowed.map { it.first }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** When no shape fits the view, the closest ones outside it (for the score and tab). */
    val nearestVoicings: StateFlow<List<Voicing>> = windowed.map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun edit(block: UiState.() -> UiState) = _state.update { old ->
        val new = old.block()
        val changed = new.voicingKey != old.voicingKey ||
            new.fretRange != old.fretRange || new.stringRange != old.stringRange
        if (changed) new.copy(voicingIndex = -1) else new
    }

    /** Re-spell the root (C♯ vs D♭) to suit what's now displayed. */
    private fun UiState.respelled() = copy(root = Note.bestSpelling(root.pitchClass, intervals))

    /** Swaps anything the level doesn't offer for the closest thing it does. */
    private fun UiState.fitLevel(): UiState {
        val (f, s) = level.nearestScale(familyIndex, scaleIndex)
        return copy(familyIndex = f, scaleIndex = s, chordIndex = Chords.all.indexOf(level.nearestChord(chord)))
    }

    /**
     * Switching keeps scale and chord related: C Ionian ⇄ C, C Dorian ⇄ Cm,
     * Cm7 → C Aeolian. Chord and arpeggio share the chord. The note view opens
     * the whole neck, since its point is every place the note falls.
     */
    fun setViewMode(mode: ViewMode) = edit {
        if (mode == viewMode) return@edit this
        when {
            mode == ViewMode.Song -> copy(viewMode = mode)
            mode == ViewMode.Note -> copy(viewMode = mode).fullNeck()
            mode != ViewMode.Scale && viewMode != ViewMode.Scale -> copy(viewMode = mode)
            mode != ViewMode.Scale -> {
                val fitting = if (chord.fitsIn(scale)) chord else tonicChord(scale, sevenths) ?: chord
                copy(viewMode = mode, chordIndex = Chords.all.indexOf(level.nearestChord(fitting)))
            }
            else -> {
                val (f, s) = if (chord.fitsIn(scale)) familyIndex to scaleIndex
                else scaleFor(chord, familyIndex, level::allowsScale) ?: (familyIndex to scaleIndex)
                copy(viewMode = mode, familyIndex = f, scaleIndex = s)
            }
        }.fitLevel().respelled()
    }

    fun setDisplay(display: Display) = edit { copy(display = display) }

    /** Picks a CAGED position (index along the neck), or -1 to show the whole neck with every shape marked. */
    fun setCaged(pick: Int) = edit { copy(cagedPick = pick) }

    /** ‹ › through the CAGED positions in order up the neck. */
    fun stepCaged(delta: Int) = edit {
        val n = cagedPositions.size
        if (n == 0) this else copy(cagedPick = Math.floorMod((if (cagedPick < 0) -1 else cagedPick) + delta, n))
    }

    /**
     * Beginner narrows the neck to the first position; leaving it opens the whole
     * neck again, unless the frets were moved from that starting range.
     */
    fun setLevel(level: Level) = edit {
        val firstPosition = fretFrom == 0 && fretTo == BEGINNER_FRETS
        val frets = when {
            level == Level.Beginner -> copy(fretFrom = 0, fretTo = BEGINNER_FRETS, cagedPick = -1)
            this.level == Level.Beginner && firstPosition -> copy(fretFrom = 0, fretTo = TO_END)
            else -> this
        }
        frets.copy(level = level).fitLevel().respelled()
    }

    fun selectRoot(pitchClass: Int) = edit { copy(root = Note.bestSpelling(pitchClass, intervals)) }

    fun selectFamily(index: Int) = edit {
        val first = level.scaleIndices().firstOrNull { it.first == index }?.second ?: 0
        copy(familyIndex = index, scaleIndex = first).respelled()
    }

    fun selectScale(index: Int) = edit { copy(scaleIndex = index).respelled() }

    fun selectChord(index: Int) = edit { copy(chordIndex = index).respelled() }

    /**
     * Jump from the chip for [degree] (0-based) to that chord, keeping the scale's
     * spelling of its root. In a modal family the scale follows along (Dm from
     * C Ionian → D Dorian), so switching back shows the same notes.
     */
    fun openChord(degree: Int, root: Note, type: ChordType) = edit {
        val rotated = (scaleIndex + degree) % family.scales.size
        val mode = if (family.modal && level.allowsScale(familyIndex, rotated)) rotated else scaleIndex
        copy(viewMode = ViewMode.Chord, root = root, scaleIndex = mode, chordIndex = Chords.all.indexOf(type))
    }

    /** Picks a chord of the key to show over the scale (its CAGED shapes at Advanced); picking it again clears it. */
    fun pickKeyChord(degree: Int) = edit { copy(keyChord = if (keyChord == degree) -1 else degree, cagedPick = -1) }

    fun selectTuning(index: Int) = edit { copy(tuningIndex = index, customTuning = null) }

    fun retuneString(string: Int, semitones: Int) = edit { copy(customTuning = baseTuning.retune(string, semitones)) }

    fun resetTuning() = edit { copy(customTuning = null) }

    fun setFretCount(count: Int) = edit { copy(fretCount = count) }

    fun setFretRange(from: Int, to: Int) = edit {
        copy(fretFrom = from, fretTo = if (to >= fretCount) TO_END else to, cagedPick = -1)
    }

    fun setStringRange(from: Int, to: Int) = edit {
        copy(stringFrom = from, stringTo = if (to >= tuning.strings - 1) TO_END else to)
    }

    fun setLabelMode(mode: LabelMode) = edit { copy(labelMode = mode) }

    fun setShowOrder(show: Boolean) = edit { copy(showOrder = show) }

    fun setTempo(bpm: Int) = edit { copy(tempo = bpm.coerceIn(MIN_TEMPO, MAX_TEMPO)) }

    fun setInstrument(instrument: Instrument) {
        player?.instrument = instrument
        edit { copy(instrument = instrument) }
    }

    fun setLeftHanded(left: Boolean) = edit { copy(leftHanded = left) }

    fun setDiatonicSevenths(sevenths: Boolean) = edit { copy(diatonicSevenths = sevenths) }

    fun stepVoicing(delta: Int) {
        val count = voicings.value.size
        if (count == 0) return
        // Cycles: all tones (-1) → shape 0 → … → shape n-1 → all tones.
        edit { copy(voicingIndex = Math.floorMod(voicingIndex + 1 + delta, count + 1) - 1) }
    }

    // ---- Content pushed from outside: the AI bridge and file imports ----

    private val undo = ArrayDeque<UiState>()

    /** Applies a pushed change, keeping what was there for Undo. */
    private fun push(block: UiState.() -> UiState) = synchronized(undo) {
        undo.addLast(_state.value)
        if (undo.size > 30) undo.removeFirst()
        edit { block().copy(undoDepth = undo.size) }
    }

    /**
     * Takes back the last pushed change: what's showing goes back, but settings
     * the user changed since (sound, tempo, labels, display…) stay as they are.
     */
    fun undo() = synchronized(undo) {
        val previous = undo.removeLastOrNull() ?: return@synchronized
        stopPlayback()
        val now = _state.value
        _state.value = previous.copy(
            undoDepth = undo.size,
            labelMode = now.labelMode, showOrder = now.showOrder, tempo = now.tempo, instrument = now.instrument,
            leftHanded = now.leftHanded, fretCount = now.fretCount, display = now.display,
        )
    }

    private fun UiState.fullNeck() = copy(fretFrom = 0, fretTo = TO_END, stringFrom = 0, stringTo = TO_END, cagedPick = -1)

    /** Uses [t] as the tuning: a preset if one matches, else a custom tuning. */
    private fun UiState.tunedTo(t: Tuning): UiState {
        val preset = Tunings.all.indexOfFirst { it.midi == t.midi }
        return if (preset >= 0) copy(tuningIndex = preset, customTuning = null) else copy(customTuning = t)
    }

    fun loadSong(song: Song) {
        stopPlayback()
        push { loaded(song) }
    }

    private fun UiState.loaded(song: Song) =
        (song.tuning?.let { tunedTo(it) } ?: this).fullNeck().copy(
            viewMode = ViewMode.Song, song = song, songBar = 0, capo = song.capo, overlay = null, highlights = null,
        )

    fun setTuning(tuning: Tuning?, capo: Int?) = push {
        (tuning?.let { tunedTo(it) } ?: this).copy(capo = capo ?: this.capo)
    }

    /** Chord view of [symbol], with a shape picked in [style]. */
    fun showChord(symbol: ChordSymbol, style: String) {
        push {
            copy(
                viewMode = ViewMode.Chord, root = symbol.root, chordIndex = Chords.all.indexOf(symbol.type),
                level = if (level.allows(symbol.type)) level else Level.Advanced,
            ).fullNeck()
        }
        val s = _state.value
        val shapes = VoicingFinder.find(s.tuning, s.root, s.chord, s.fretCount)
        val pick = pickVoicing(s.tuning, ChordSymbol(symbol.root, symbol.type), style, s.fretCount)
        edit { copy(voicingIndex = shapes.indexOf(pick)) }
    }

    /** A scale on its own (the Scale view, or the Song view for custom intervals) or [over] the current content. */
    fun showScale(scale: ScaleOverlay, over: Boolean) = push {
        val named = scale.scale
        when {
            over -> copy(overlay = scale)
            named != null -> {
                val (f, i) = named
                val allowed = Scales.families[f].let { level.allows(it, it.scales[i]) }
                fullNeck().copy(
                    viewMode = ViewMode.Scale, root = scale.root, familyIndex = f, scaleIndex = i, overlay = null,
                    level = if (allowed) level else Level.Advanced,
                    fretFrom = scale.fretRange?.first ?: 0, fretTo = scale.fretRange?.last ?: TO_END,
                )
            }
            else -> fullNeck().copy(viewMode = ViewMode.Song, song = null, overlay = scale)
        }
    }

    fun highlight(h: Highlights?) = push { copy(highlights = h) }

    fun clearOverlay() = edit { copy(overlay = null) }

    /** Opens a file the user picked; returns a line to show them (what loaded, or what went wrong). */
    fun importUserFile(name: String, bytes: ByteArray): String = try {
        val format = Importers.formatFor(name)
            ?: throw BridgeError("UNSUPPORTED_FORMAT", "Can't open '$name': use Guitar Pro, MusicXML, MIDI, ChordPro or a text tab")
        val result = Importers.import(bytes, format, name, null, null, _state.value.baseTuning)
        loadSong(result.song)
        "Opened ${result.song.title}" + (result.note?.let { " — $it" } ?: "")
    } catch (e: BridgeError) {
        e.detail
    }

    /** Empties the neck. */
    fun clear() {
        stopPlayback()
        push { copy(viewMode = ViewMode.Song, song = null, songBar = 0, overlay = null, highlights = null) }
    }

    fun selectBar(index: Int) = edit {
        val count = when (val s = song) {
            is Progression -> s.flatBars.size
            is Tab -> s.measures.size
            null -> 1
        }
        copy(songBar = index.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    // ---- Playback of pushed content ----

    /** Set by the platform's entry point; null where there's no sound (tests). */
    @Volatile var player: NotePlayer? = null
    private val _playback = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = _playback.asStateFlow()
    private var playJob: Job? = null

    /** Plays the loaded song (or one [section]); returns what it's doing. */
    fun playSong(loop: Boolean, section: String?, tempoPct: Double): String {
        val s = _state.value
        val song = s.song ?: throw BridgeError("NOT_FOUND", "Nothing to play — load a progression or tab first")
        val plan = schedule(song, s.tuning, s.fretCount, section, tempoPct, shapes = s.baseTuning)
        stopPlayback()
        _playback.value = Playback(playing = true, looping = loop, section = section)
        playJob = viewModelScope.launch {
            do {
                player?.play(plan.notes, ring = 1.6)
                var last = 0.0
                for (step in plan.steps) {
                    delay(((step.at - last) * 1000).toLong())
                    last = step.at
                    edit { copy(songBar = step.bar) }
                    _sounding.value = step.positions
                }
                delay(((plan.seconds - last) * 1000).toLong().coerceAtLeast(0))
            } while (loop)
            _sounding.value = emptySet()
            _playback.value = Playback()
        }
        return plan.summary + if (loop) ", looping" else ""
    }

    /** Stops whatever is playing — a song, a scale run or a strum — sound and ringing together. */
    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        followJob?.cancel()
        followJob = null
        player?.stop()
        _sounding.value = emptySet()
        _playback.value = Playback()
    }

    /** Bars of the current song, for the section list. */
    fun sectionBars(section: String?): IntRange? = _state.value.song?.barsOf(section)
}
