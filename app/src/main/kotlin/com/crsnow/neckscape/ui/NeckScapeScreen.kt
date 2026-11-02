@file:OptIn(ExperimentalMaterial3Api::class)

package com.crsnow.neckscape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crsnow.neckscape.bridge.BridgeServer
import com.crsnow.neckscape.notation.Engraver
import com.crsnow.neckscape.notation.Music
import com.crsnow.neckscape.notation.Staves
import com.crsnow.neckscape.notation.Tag
import com.crsnow.neckscape.notation.stack
import com.crsnow.neckscape.song.Progression
import com.crsnow.neckscape.song.voicingFor
import com.crsnow.neckscape.theory.Chords
import com.crsnow.neckscape.theory.Instrument
import com.crsnow.neckscape.theory.Level
import com.crsnow.neckscape.theory.allows
import com.crsnow.neckscape.theory.chordIndices
import com.crsnow.neckscape.theory.scaleIndices
import com.crsnow.neckscape.theory.PluckSynth
import com.crsnow.neckscape.theory.Position
import com.crsnow.neckscape.theory.Scales
import com.crsnow.neckscape.theory.Tuning
import com.crsnow.neckscape.theory.Tunings
import com.crsnow.neckscape.theory.Voicing
import com.crsnow.neckscape.theory.diatonicChords
import com.crsnow.neckscape.theory.playingOrder
import com.crsnow.neckscape.theory.thereAndBack
import kotlin.math.roundToInt

private val ROOT_LABELS = listOf("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")

@Composable
fun NeckScapeScreen(vm: NeckScapeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val voicings by vm.voicings.collectAsStateWithLifecycle()
    val sounding by vm.sounding.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTuningEditor by rememberSaveable { mutableStateOf(false) }

    val notes = state.intervals.map { state.root + it }
    val marks = remember(state.root, state.intervals, state.labelMode) {
        state.intervals.zip(notes).associate { (iv, note) ->
            note.pitchClass to NoteMark(
                label = if (state.labelMode == LabelMode.Notes) note.name else iv.label,
                color = degreeColor(iv),
            )
        }
    }
    // A picked CAGED position: its chord shape drives the neck (and is strummed) for plain major/minor triads,
    // and is ringed inside the notes for scales, arpeggios and bigger chords.
    val caged = state.cagedSelected
    val cagedShape = caged?.voicing?.takeIf { v -> v.frets.all { it == null || it <= state.fretCount } }
    val triad = state.chord.pitchSet.size == 3 && 7 in state.chord.pitchSet && (3 in state.chord.pitchSet || 4 in state.chord.pitchSet)
    val voicing = if (state.viewMode == ViewMode.Chord && caged != null) cagedShape.takeIf { triad } else voicings.getOrNull(state.voicingIndex)
    val dots = remember(marks, state.tuning, state.fretRange, state.stringRange, voicing, state.showOrder) {
        buildDots(state.tuning, marks, state.fretRange, state.stringRange, voicing, state.showOrder)
    }
    val muted = voicing?.frets?.withIndex()?.filter { it.value == null }?.map { it.index }?.toSet().orEmpty()
    // Scales and arpeggios: the visible notes in the numbered order.
    val sequence = remember(dots, state.tuning) {
        playingOrder(state.tuning, dots.filter { !it.faded }.map { it.position }).keys.toList()
    }
    val playback by vm.playback.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    // The Song view (pushed or imported content) and any scale overlay on top of whatever's showing.
    val isSong = state.viewMode == ViewMode.Song
    val (songDotList, songMuted) = remember(state) { if (isSong) songDots(state) else emptyList<Dot>() to emptySet() }
    val overlay = remember(state) { state.overlay?.let { overlayDots(state, it, alone = isSong && state.song == null) }.orEmpty() }
    val boardDots = (if (isSong) songDotList else dots) + overlay
    val boardMuted = if (isSong) songMuted else muted

    val music = remember(state, dots, sequence, voicings) {
        if (isSong) musicForSong(state, songDotList + overlay.filter { !it.faded }) else musicFor(state, dots, sequence, voicings)
    }
    val saver = LocalFileSaver.current
    val opener = LocalFileOpener.current
    fun savePdf(everything: Boolean) {
        saver?.save(pdfFileName(music, state.display, everything), "application/pdf", pdfFor(music, state.display, everything))
    }
    fun saveMidi() {
        runCatching { midiFor(state, sequence, voicings) }
            .onSuccess { saver?.save(music.title.replace(Regex("""[\\/:*?"<>|]"""), "-") + ".mid", "audio/midi", it) }
            .onFailure { message = it.message }
    }
    fun openFile() {
        opener?.open { name, bytes -> message = vm.importUserFile(name, bytes) }
    }

    val player = LocalNotePlayer.current
    fun Voicing.positions() = frets.withIndex().mapNotNull { (s, f) -> f?.let { Position(s, it) } }
    fun Voicing.midi() = positions().map { state.tuning.midi[it.string] + it.fret }
    fun strum(shape: Voicing) {
        player?.play(PluckSynth.strum(shape.midi()), ring = 2.5) ?: return
        vm.follow(listOf(shape.positions().toSet()), step = 1.5)
    }
    fun playAll() {
        player ?: return
        when (state.viewMode) {
            ViewMode.Song -> {
                if (playback.playing) vm.stopPlayback()
                else runCatching { vm.playSong(loop = false, section = null, tempoPct = 100.0) }.onFailure { message = it.message }
            }
            // Up the numbered order and back down.
            ViewMode.Scale, ViewMode.Arpeggio -> {
                val step = 60.0 / state.tempo
                player.play(PluckSynth.upAndDown(sequence.map { state.tuning.midi[it.string] + it.fret }, step))
                vm.follow(sequence.thereAndBack().map { setOf(it) }, step)
            }
            // Strum the selected shape, else the first one, else the bare chord tones.
            ViewMode.Chord -> {
                val shape = voicing ?: voicings.firstOrNull()
                if (shape != null) strum(shape)
                else player.play(PluckSynth.strum(state.chord.intervals.map { 48 + state.root.pitchClass + it.semitones }), ring = 2.5)
            }
        }
    }
    // Stepping to a shape strums it.
    LaunchedEffect(voicing) {
        voicing?.let(::strum)
    }
    // Pushed content (the AI bridge) plays through the same speaker, with the chosen sound.
    LaunchedEffect(player) { vm.player = player }
    LaunchedEffect(player, state.instrument) { player?.instrument = state.instrument }

    Scaffold(
        topBar = {
            TopAppBar(
                // The full name where there's room; phones get the short one.
                title = {
                    BoxWithConstraints {
                        Text(if (maxWidth < 260.dp) "NeckScape" else "NeckScape-Navigator", maxLines = 1)
                    }
                },
                actions = {
                    if (state.undoDepth > 0) TextButton(onClick = vm::undo) { Text("Undo") }
                    if (opener != null) TextButton(onClick = ::openFile) { Text("Open") }
                    TextButton(onClick = ::playAll, enabled = player != null) { Text(if (playback.playing) "■ Stop" else "▶ Play") }
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        val page = rememberScrollState()
        val scrollbars = LocalScrollbars.current
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(page)
                .padding(top = 8.dp, bottom = 8.dp, end = if (scrollbars != null) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Level.entries.forEachIndexed { i, level ->
                    SegmentedButton(
                        selected = state.level == level,
                        onClick = { vm.setLevel(level) },
                        shape = SegmentedButtonDefaults.itemShape(i, Level.entries.size),
                        label = { Text(level.name, maxLines = 1) },
                    )
                }
            }

            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ViewMode.entries.filter { it != ViewMode.Song || state.song != null || isSong }.forEach { mode ->
                    FilterChip(
                        selected = state.viewMode == mode,
                        onClick = { vm.setViewMode(mode) },
                        label = { Text(mode.name) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                LabelMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.labelMode == mode,
                        onClick = { vm.setLabelMode(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }

            if (!isSong) Column {
                val roots = rememberLazyListState()
                LazyRow(
                    state = roots,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(ROOT_LABELS) { pc, label ->
                        FilterChip(
                            selected = state.root.pitchClass == pc,
                            onClick = { vm.selectRoot(pc) },
                            label = { Text(label) },
                        )
                    }
                }
                scrollbars?.HorizontalLazy(roots, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.viewMode) {
                    ViewMode.Scale -> {
                        // Only what the level offers.
                        val offered = state.level.scaleIndices()
                        val families = offered.map { it.first }.distinct()
                        val modes = offered.filter { it.first == state.familyIndex }.map { it.second }
                        Picker(
                            "Family", families.map { Scales.families[it].name }, families.indexOf(state.familyIndex).coerceAtLeast(0),
                            { vm.selectFamily(families[it]) }, Modifier.weight(1f),
                        )
                        Picker(
                            "Mode", modes.map { state.family.scales[it].name }, modes.indexOf(state.scaleIndex).coerceAtLeast(0),
                            { vm.selectScale(modes[it]) }, Modifier.weight(1f),
                        )
                    }
                    ViewMode.Arpeggio, ViewMode.Chord -> {
                        val offered = state.level.chordIndices()
                        Picker(
                            label = if (state.viewMode == ViewMode.Chord) "Chord" else "Arpeggio",
                            items = offered.map { Chords.all[it].displayName(state.root) + "  ·  " + Chords.all[it].name },
                            selected = offered.indexOf(state.chordIndex).coerceAtLeast(0),
                            onSelect = { vm.selectChord(offered[it]) },
                            modifier = Modifier.weight(1f),
                            header = { i ->
                                Chords.all[offered[i]].group.takeIf { i == 0 || Chords.all[offered[i - 1]].group != it }
                            },
                        )
                    }
                    ViewMode.Song -> {}
                }
            }

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Picker(
                    label = "Tuning",
                    items = Tunings.all.map { "${it.name}  ${it.stringNames.joinToString(" ")}" } +
                        listOfNotNull(state.customTuning?.let { "Custom  ${it.stringNames.joinToString(" ")}" }),
                    selected = if (state.customTuning != null) Tunings.all.size else state.tuningIndex,
                    onSelect = { if (it < Tunings.all.size) vm.selectTuning(it) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { showTuningEditor = true }) { Text("Edit") }
            }

            Text(
                state.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.cagedEnabled) CagedRow(state, vm)
            if (isSong) SongHeader(state)
            state.highlights?.let { h ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Marked: ${h.label ?: "${h.positions.size} notes"}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.background(HighlightPink, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    TextButton(onClick = { vm.highlight(null) }) { Text("Clear") }
                }
            }
            state.overlay?.takeIf { !(isSong && state.song == null) }?.let { o ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Overlay: ${o.root.name} ${o.name}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = vm::clearOverlay) { Text("Clear") }
                }
            }

            val strip = rememberScrollState()
            Row(
                Modifier
                    .horizontalScroll(strip)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val chips = if (isSong) music.legend.map { Triple(it.note, it.interval, Color(it.color)) }
                else state.intervals.zip(notes).map { (iv, note) -> Triple(note.name, iv.label, degreeColor(iv)) }
                chips.forEach { (name, label, color) ->
                    Column(
                        Modifier
                            .background(color, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            scrollbars?.Horizontal(strip, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Display.entries.forEach { display ->
                    FilterChip(
                        selected = state.display == display,
                        onClick = { vm.setDisplay(display) },
                        label = { Text(display.name) },
                    )
                }
                Spacer(Modifier.weight(1f))
                SaveButton(state.display, enabled = saver != null, onPdf = ::savePdf, onMidi = ::saveMidi)
            }

            if (state.display != Display.Fretboard) {
                val accent = MaterialTheme.colorScheme.primary
                Notation(
                    music = music,
                    staves = if (state.display == Display.Score) Staves.Score else Staves.Tab,
                    highlight = { tag ->
                        when {
                            isSong -> if (tag.position in sounding) Color(0xFFE53935) else accent.takeIf { tag.group == state.songBar }
                            state.viewMode == ViewMode.Chord -> accent.takeIf { tag.group == state.voicingIndex }
                            tag.position in sounding -> Color(0xFFE53935)
                            else -> null
                        }
                    },
                )
            } else Fretboard(
                tuning = state.tuning,
                fretCount = state.fretCount,
                leftHanded = state.leftHanded,
                dots = boardDots,
                frets = state.fretRange,
                strings = state.stringRange,
                muted = boardMuted,
                modifier = Modifier.fillMaxWidth(),
                sounding = sounding,
                marked = state.highlights?.positions.orEmpty(),
                regions = state.cagedPositions.mapIndexed { i, p ->
                    Region(p.shape.name, p.frets, cagedColor(p.shape.name), selected = i == state.cagedPick)
                },
                outlined = if (voicing == null) cagedShape?.frets?.withIndex()?.mapNotNull { (str, f) -> f?.let { Position(str, it) } }?.toSet().orEmpty() else emptySet(),
                onTap = { p ->
                    player?.playNote(state.tuning.midi[p.string] + p.fret)
                    vm.follow(listOf(setOf(p)), step = 0.6)
                },
            )

            RangeControls(state, vm)

            when (state.viewMode) {
                ViewMode.Chord -> if (caged != null) VoicingBar(
                    text = "CAGED ${caged.shape} shape  ·  frets ${caged.frets.first}–${caged.frets.last}" +
                        (cagedShape?.let { "  ·  ${it.tab}" + if (triad) "" else " (triad shape ringed)" } ?: ""),
                    enabled = true,
                    onStep = vm::stepCaged,
                ) else VoicingBar(
                    text = when {
                        voicings.isEmpty() -> "No playable shapes found"
                        voicing == null -> "All chord tones · ${voicings.size} shapes"
                        else -> "Shape ${state.voicingIndex + 1}/${voicings.size}  ·  ${voicing.tab}"
                    },
                    enabled = voicings.isNotEmpty(),
                    onStep = vm::stepVoicing,
                )
                ViewMode.Scale -> DiatonicChords(state, vm)
                ViewMode.Arpeggio -> {}
                ViewMode.Song -> SongBars(state) { i ->
                    vm.selectBar(i)
                    val s = vm.state.value
                    (s.song as? Progression)?.let { p -> s.songChord?.let { p.voicingFor(it, s.tuning, s.fretCount) } }?.let(::strum)
                }
            }
        }
        scrollbars?.Vertical(page, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            text = { Text(text) },
        )
    }

    if (showSettings) SettingsDialog(state, vm) { showSettings = false }
    if (showTuningEditor) TuningEditor(state, vm) { showTuningEditor = false }
}

/** Fret range (nut → bridge), string range (low → high) and the playing-order toggle. */
@Composable
private fun RangeControls(state: UiState, vm: NeckScapeViewModel) {
    val frets = state.fretRange
    val strings = state.stringRange
    val tuning = state.tuning
    // Guitarists number strings from the highest-pitched one, which is index strings-1.
    fun stringLabel(s: Int) = "${tuning.strings - s} (${tuning.stringNames[s]})"

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Frets ${frets.first}–${frets.last}", style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = frets.first.toFloat()..frets.last.toFloat(),
            onValueChange = { vm.setFretRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            valueRange = 0f..state.fretCount.toFloat(),
            steps = state.fretCount - 1,
        )
        Text(
            "Strings ${stringLabel(strings.first)} to ${stringLabel(strings.last)}",
            style = MaterialTheme.typography.labelLarge,
        )
        RangeSlider(
            value = strings.first.toFloat()..strings.last.toFloat(),
            onValueChange = { vm.setStringRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            valueRange = 0f..(tuning.strings - 1).toFloat(),
            steps = (tuning.strings - 2).coerceAtLeast(0),
        )
        val numbering = @Composable {
            Text("Order numbers")
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.showOrder, onCheckedChange = vm::setShowOrder)
        }
        val tempo = @Composable { modifier: Modifier ->
            Text("Tempo ♩=${state.tempo}", Modifier.width(110.dp))
            Slider(
                value = state.tempo.toFloat(),
                onValueChange = { vm.setTempo((it / 5).roundToInt() * 5) },
                valueRange = MIN_TEMPO.toFloat()..MAX_TEMPO.toFloat(),
                modifier = modifier,
            )
        }
        val sound = @Composable { SoundPicker(state.instrument, vm::setInstrument) }
        // One row when there's room (tablet, desktop), more on a phone.
        BoxWithConstraints {
            if (maxWidth >= 760.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    numbering()
                    Spacer(Modifier.width(24.dp))
                    tempo(Modifier.weight(1f))
                    Spacer(Modifier.width(16.dp))
                    sound()
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        numbering()
                        Spacer(Modifier.weight(1f))
                        sound()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { tempo(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The playback sound: acoustic, classical, electric, piano or synth. */
@Composable
private fun SoundPicker(current: Instrument, onPick: (Instrument) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Sound: ${current.label}", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Instrument.entries.forEach { i ->
                DropdownMenuItem(
                    text = { Text(i.label, fontWeight = if (i == current) FontWeight.Bold else null) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

/** Per-string −/+ semitone editor; any change makes the tuning "Custom". */
@Composable
private fun TuningEditor(state: UiState, vm: NeckScapeViewModel, onDismiss: () -> Unit) {
    val tuning = state.tuning
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = vm::resetTuning, enabled = state.customTuning != null) {
                Text("Reset to ${Tunings.all[state.tuningIndex].name}")
            }
        },
        title = { Text("Tuning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Start from a preset, then retune any string.", style = MaterialTheme.typography.bodySmall)
                // Highest string first, matching the fretboard.
                for (s in tuning.strings - 1 downTo 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("String ${tuning.strings - s}", Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.retuneString(s, -1) }) { Text("−") }
                        Text(
                            Tuning.midiName(tuning.midi[s]),
                            Modifier.width(56.dp),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(onClick = { vm.retuneString(s, 1) }) { Text("+") }
                    }
                }
            }
        },
    )
}

@Composable
private fun VoicingBar(text: String, enabled: Boolean, onStep: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { onStep(-1) }, enabled = enabled) { Text("‹") }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onStep(1) }, enabled = enabled) { Text("›") }
    }
}

@Composable
private fun DiatonicChords(state: UiState, vm: NeckScapeViewModel) {
    val chords = diatonicChords(state.scale, state.root, state.sevenths)
    if (chords.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chords in this mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.level != Level.Beginner) {
                Text("7ths", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.diatonicSevenths, onCheckedChange = vm::setDiatonicSevenths)
            }
        }
        val row = rememberLazyListState()
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(chords) { degree, chord ->
                AssistChip(
                    enabled = chord.type != null && state.level.allows(chord.type),
                    onClick = { chord.type?.let { vm.openChord(degree, chord.root, it) } },
                    label = { Text("${chord.numeral}  ${chord.type?.displayName(chord.root) ?: "?"}") },
                )
            }
        }
        LocalScrollbars.current?.HorizontalLazy(row, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}

/** The score or tab of what's selected, on white paper, as wide as the screen allows. */
@Composable
private fun Notation(music: Music, staves: Staves, highlight: (Tag) -> Color?) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val width = maxWidth.value
        val sheet = remember(music, staves, width) {
            val margin = 12f
            stack(width, Engraver(music, screenStaffSpace(width)).blocks(staves, width - 2 * margin), gap = 10f, margin = margin)
        }
        Box(Modifier.background(Color.White, RoundedCornerShape(8.dp))) {
            if (sheet.items.isEmpty()) {
                Text("Nothing to show — no notes in the selected frets and strings.", Modifier.padding(16.dp), color = Color.DarkGray)
            } else {
                SheetView(sheet, highlight = highlight)
            }
        }
    }
}

/** CAGED: every shape marked on the neck, or one position picked (frets, ringed chord shape). */
@Composable
private fun CagedRow(state: UiState, vm: NeckScapeViewModel) {
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("CAGED", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
        FilterChip(selected = state.cagedPick < 0, onClick = { vm.setCaged(-1) }, label = { Text("All") })
        state.cagedPositions.forEachIndexed { i, p ->
            FilterChip(
                selected = state.cagedPick == i,
                onClick = { vm.setCaged(i) },
                label = {
                    Text(p.shape.name, color = cagedColor(p.shape.name), fontWeight = FontWeight.Bold)
                    Text("  ${p.frets.first}–${p.frets.last}", style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }
}

/** Save the current view as a PDF (alone, or with neck + score + tab), or as a MIDI file. */
@Composable
private fun SaveButton(display: Display, enabled: Boolean, onPdf: (everything: Boolean) -> Unit, onMidi: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("Save") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("PDF: ${display.name.lowercase()} only") }, onClick = { open = false; onPdf(false) })
            DropdownMenuItem(text = { Text("PDF: fretboard, score and tab") }, onClick = { open = false; onPdf(true) })
            DropdownMenuItem(text = { Text("MIDI file") }, onClick = { open = false; onMidi() })
        }
    }
}

@Composable
private fun SettingsDialog(state: UiState, vm: NeckScapeViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            val scroll = rememberScrollState()
            Box {
            Column(Modifier.verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Frets: ${state.fretCount}")
                    Slider(
                        value = state.fretCount.toFloat(),
                        onValueChange = { vm.setFretCount(it.roundToInt()) },
                        valueRange = 12f..24f,
                        steps = 11,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Left-handed", Modifier.weight(1f))
                    Switch(checked = state.leftHanded, onCheckedChange = vm::setLeftHanded)
                }
                LocalBridge.current?.let { BridgeSettings(it) }
            }
            LocalScrollbars.current?.Vertical(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        },
    )
}

/** The local AI bridge: where it listens, the token an MCP server needs, and LAN pairing (Android). */
@Composable
private fun BridgeSettings(bridge: BridgeServer) {
    val status by bridge.status.collectAsStateWithLifecycle()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AI bridge", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                status.running -> "Listening on ${if (status.lan) "this network" else "127.0.0.1"}, port ${status.port}"
                else -> "Not running" + (status.error?.let { ": $it" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Token: " + if (reveal) status.token else status.token.take(6) + "…", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(status.token)) }) { Text("Copy") }
            OutlinedButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
            OutlinedButton(onClick = bridge::regenerateToken) { Text("Regenerate") }
        }
        if (bridge.lanCapable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow access over Wi-Fi", Modifier.weight(1f))
                Switch(checked = status.lan, onCheckedChange = bridge::setLan)
            }
            if (status.lan) {
                Text(
                    "Pairing code: ${status.pairingCode ?: "—"}. Only turn this on at home or on other networks you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        status.lastCall?.let { Text("Last request: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** A labelled button that opens a menu; [header] can insert a group title before an item. */
@Composable
private fun Picker(
    label: String,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (Int) -> String? = { null },
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(items[selected], maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, item ->
                header(i)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                DropdownMenuItem(
                    text = { Text(item, fontWeight = if (i == selected) FontWeight.Bold else null) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    },
                )
            }
        }
    }
}
