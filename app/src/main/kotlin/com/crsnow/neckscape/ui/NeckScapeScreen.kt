@file:OptIn(ExperimentalMaterial3Api::class)

package com.crsnow.neckscape.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.crsnow.neckscape.theory.playingOrder
import com.crsnow.neckscape.theory.thereAndBack
import kotlin.math.roundToInt

private val ROOT_LABELS = listOf("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")

@Composable
fun NeckScapeScreen(vm: NeckScapeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val voicings by vm.voicings.collectAsStateWithLifecycle()
    val nearestVoicings by vm.nearestVoicings.collectAsStateWithLifecycle()
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
    val noteView = state.viewMode == ViewMode.Note
    val dots = remember(marks, state.tuning, state.fretRange, state.stringRange, voicing, state.showOrder, noteView) {
        val all = buildDots(state.tuning, marks, state.fretRange, state.stringRange, voicing, state.showOrder && !noteView)
        // One note everywhere: each dot names its octave (C2, C3 …) in place of a playing order.
        if (noteView && state.labelMode == LabelMode.Notes) all.map { d ->
            d.copy(mark = d.mark.copy(label = state.root.withOctave(state.tuning.midi[d.position.string] + d.position.fret)))
        } else all
    }
    val muted = voicing?.frets?.withIndex()?.filter { it.value == null }?.map { it.index }?.toSet().orEmpty()
    // A chord of the key picked over the scale: below Advanced its notes stay bright and the rest of the scale fades.
    val keyTones = state.pickedKeyChord?.takeIf { state.shapeChord == null }?.let { k ->
        val pcs = k.type!!.intervals.map { Math.floorMod(k.root.pitchClass + it.semitones, 12) }.toSet()
        dots.filter { !it.faded && state.tuning.pitchClassAt(it.position.string, it.position.fret) in pcs }.map { it.position }.toSet()
    }.orEmpty()
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
    val shownDots = if (keyTones.isEmpty()) dots else dots.map { if (it.position in keyTones) it else it.copy(faded = true) }
    val boardDots = (if (isSong) songDotList else shownDots) + overlay
    val boardMuted = if (isSong) songMuted else muted

    val music = remember(state, dots, sequence, voicings, nearestVoicings) {
        if (isSong) musicForSong(state, songDotList + overlay.filter { !it.faded })
        else musicFor(state, dots, sequence, voicings.ifEmpty { nearestVoicings })
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
    fun strum(shape: Voicing, fromPlay: Boolean = false) {
        player?.play(PluckSynth.strum(shape.midi()), ring = 2.5) ?: return
        vm.follow(listOf(shape.positions().toSet()), step = 1.5, playing = fromPlay)
    }
    /** ▶ plays what's showing; pressed again while it plays, it stops. */
    fun playAll() {
        player ?: return
        if (playback.playing) {
            vm.stopPlayback()
            return
        }
        when (state.viewMode) {
            ViewMode.Song -> {
                runCatching { vm.playSong(loop = false, section = null, tempoPct = 100.0) }.onFailure { message = it.message }
            }
            // Up the numbered order and back down (the note view: one per octave).
            ViewMode.Scale, ViewMode.Arpeggio, ViewMode.Note -> {
                val step = 60.0 / state.tempo
                player.play(PluckSynth.upAndDown(sequence.map { state.tuning.midi[it.string] + it.fret }, step))
                vm.follow(sequence.thereAndBack().map { setOf(it) }, step, playing = true)
            }
            // Strum the selected shape, else the first one, else the bare chord tones.
            ViewMode.Chord -> {
                val shape = voicing ?: voicings.firstOrNull()
                if (shape != null) strum(shape, fromPlay = true)
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

    // Phones on their side are wide but short: two panes, slimmer bars.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val short = maxHeight < 480.dp
    val twoPane = maxWidth >= 840.dp || (maxWidth > maxHeight && maxWidth >= 600.dp)
    val sidebarWidth = if (maxWidth < 960.dp) 300.dp else 340.dp
    // Phone-sized layouts: one button per choice, reading what comes next, instead of a row of segments.
    val phone = !twoPane || short
    val modes = ViewMode.entries.filter { it != ViewMode.Song || state.song != null || isSong }
    Scaffold(
        topBar = {
            AppTopBar(
                short = short,
                // Short screens: what's shown, and how, are two buttons in the top bar.
                display = if (short) ({
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DisplaySwitch(state.display, vm::setDisplay, cycle = phone)
                        if (phone) CycleButton(modes, state.viewMode, ViewMode::name, vm::setViewMode)
                    }
                }) else null,
                state = state,
                canOpen = opener != null,
                canSave = saver != null,
                onUndo = vm::undo,
                onOpen = ::openFile,
                onPdf = ::savePdf,
                onMidi = ::saveMidi,
                onSettings = { showSettings = true },
            )
        },
        bottomBar = {
            TransportBar(state, vm, playing = playback.playing, enabled = player != null, short = short, onPlay = ::playAll)
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val scrollbars = LocalScrollbars.current

        // ---- Sections, arranged below for a wide window (sidebar + stage) or a phone (one column) ----
        val level = @Composable {
            Section("Level") {
                // Labels shrink to fit a narrow sidebar, so "Intermediate" stays whole.
                BoxWithConstraints {
                    val style = MaterialTheme.typography.labelMedium
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Level.entries.forEachIndexed { i, l ->
                            SegmentedButton(
                                selected = state.level == l,
                                onClick = { vm.setLevel(l) },
                                shape = SegmentedButtonDefaults.itemShape(i, Level.entries.size),
                                icon = {},
                                label = { FitText(l.name, style) },
                            )
                        }
                    }
                }
            }
        }
        val view = @Composable {
            Section("Show") {
                if (phone) CycleButton(modes, state.viewMode, ViewMode::name, vm::setViewMode, Modifier.fillMaxWidth())
                else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.viewMode == m,
                            onClick = { vm.setViewMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = {},
                            // Up to five views share a sidebar's width, so "Arpeggio" needs the room.
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            label = { FitText(m.name, MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }
        val key = @Composable { compact: Boolean ->
            if (!isSong) Section(if (noteView) "Note" else "Key") { RootPicker(state.root.pitchClass, compact, vm::selectRoot) }
        }
        val choice = @Composable { stacked: Boolean ->
            when (state.viewMode) {
                ViewMode.Scale -> Section("Scale") {
                    // Only what the level offers.
                    val offered = state.level.scaleIndices()
                    val families = offered.map { it.first }.distinct()
                    val modes = offered.filter { it.first == state.familyIndex }.map { it.second }
                    val family = @Composable { m: Modifier ->
                        Picker("Family", families.map { Scales.families[it].name }, families.indexOf(state.familyIndex).coerceAtLeast(0), { vm.selectFamily(families[it]) }, m)
                    }
                    val mode = @Composable { m: Modifier ->
                        Picker("Mode", modes.map { state.family.scales[it].name }, modes.indexOf(state.scaleIndex).coerceAtLeast(0), { vm.selectScale(modes[it]) }, m)
                    }
                    if (stacked) {
                        family(Modifier.fillMaxWidth())
                        mode(Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        family(Modifier.weight(1f))
                        mode(Modifier.weight(1f))
                    }
                }
                ViewMode.Arpeggio, ViewMode.Chord -> Section(if (state.viewMode == ViewMode.Chord) "Chord" else "Arpeggio") {
                    val offered = state.level.chordIndices()
                    Picker(
                        label = "Type",
                        items = offered.map { Chords.all[it].displayName(state.root) + "  ·  " + Chords.all[it].name },
                        selected = offered.indexOf(state.chordIndex).coerceAtLeast(0),
                        onSelect = { vm.selectChord(offered[it]) },
                        modifier = Modifier.fillMaxWidth(),
                        header = { i -> Chords.all[offered[i]].group.takeIf { i == 0 || Chords.all[offered[i - 1]].group != it } },
                    )
                }
                ViewMode.Note, ViewMode.Song -> {}
            }
        }
        val tuning = @Composable {
            Section("Tuning") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picker(
                        label = if (state.capo > 0) "Capo ${state.capo}" else "Strings",
                        items = Tunings.all.map { "${it.name}  ${it.stringNames.joinToString(" ")}" } +
                            listOfNotNull(state.customTuning?.let { "Custom  ${it.stringNames.joinToString(" ")}" }),
                        selected = if (state.customTuning != null) Tunings.all.size else state.tuningIndex,
                        onSelect = { if (it < Tunings.all.size) vm.selectTuning(it) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { showTuningEditor = true }, modifier = Modifier.height(56.dp), shape = MaterialTheme.shapes.medium) { Text("Edit") }
                }
            }
        }
        val labels = @Composable {
            Section("Labels") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LabelMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.labelMode == m,
                            onClick = { vm.setLabelMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, LabelMode.entries.size),
                            icon = {},
                            label = { Text(m.name, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                if (!noteView) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playing-order numbers", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.showOrder, onCheckedChange = vm::setShowOrder)
                }
            }
        }
        val range = @Composable { NeckRange(state, vm) }
        val header = @Composable { wide: Boolean ->
            Column(verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 10.dp)) {
                if (!short) Text(
                    state.title,
                    style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                )
                if (isSong) SongHeader(state)
                state.highlights?.let { h ->
                    StatusPill("Marked: ${h.label ?: "${h.positions.size} notes"}", HighlightPink) { vm.highlight(null) }
                }
                state.overlay?.takeIf { !(isSong && state.song == null) }?.let { o ->
                    StatusPill("Overlay: ${o.root.name} ${o.name}", MaterialTheme.colorScheme.primary, onClear = vm::clearOverlay)
                }
                val strip = rememberScrollState()
                // Short screens: the title and notes share a row (the display switch is in the top bar), leaving height for the neck.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (short) Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Row(Modifier.weight(1f).horizontalScroll(strip), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val chips = if (isSong) music.legend.map { Triple(it.note, it.interval, Color(it.color)) }
                        else state.intervals.zip(notes).map { (iv, note) -> Triple(note.name, iv.label, degreeColor(iv)) }
                        chips.forEach { (name, label, color) -> NoteChip(name, label, color) }
                    }
                }
                scrollbars?.Horizontal(strip, Modifier.fillMaxWidth())
            }
        }
        val display = @Composable { if (!short) DisplaySwitch(state.display, vm::setDisplay) }
        val cagedChips = @Composable { if (state.cagedEnabled) CagedRow(state, vm) }
        val board = @Composable {
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
            } else Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Fretboard(
                    tuning = state.tuning,
                    fretCount = state.fretCount,
                    leftHanded = state.leftHanded,
                    dots = boardDots,
                    frets = state.fretRange,
                    strings = state.stringRange,
                    muted = boardMuted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (short) 4.dp else 8.dp),
                    compact = short,
                    sounding = sounding,
                    marked = state.highlights?.positions.orEmpty(),
                    regions = state.cagedPositions.mapIndexed { i, p ->
                        Region(
                            p.shape.name, p.frets, cagedColor(p.shape.name), selected = i == state.cagedPick,
                            shape = p.voicing.frets.withIndex().mapNotNull { (str, f) -> f?.let { Position(str, it) } }.toSet(),
                        )
                    },
                    outlined = when {
                        voicing != null -> emptySet()
                        else -> cagedShape?.frets?.withIndex()?.mapNotNull { (str, f) -> f?.let { Position(str, it) } }?.toSet().orEmpty()
                    },
                    onTap = { p ->
                        // A tapped note cuts off whatever was playing, so stop its ringing too.
                        if (playback.playing) vm.stopPlayback()
                        player?.playNote(state.tuning.midi[p.string] + p.fret)
                        vm.follow(listOf(setOf(p)), step = 0.6)
                    },
                    // A tapped CAGED band strums its chord shape.
                    onRegionTap = { i ->
                        state.cagedPositions.getOrNull(i)?.let { p ->
                            if (playback.playing) vm.stopPlayback()
                            strum(p.voicing)
                        }
                    },
                )
            }
        }
        val below = @Composable {
            when (state.viewMode) {
                ViewMode.Chord -> if (caged != null) VoicingBar(
                    text = "CAGED ${caged.shape} shape  ·  frets ${caged.frets.first}–${caged.frets.last}" +
                        (cagedShape?.let { "  ·  ${it.tab}" + if (triad) "" else " (triad shape ringed)" } ?: ""),
                    enabled = true,
                    onStep = vm::stepCaged,
                ) else VoicingBar(
                    text = when {
                        voicings.isEmpty() && nearestVoicings.isNotEmpty() ->
                            "No shape fits frets ${state.fretRange.first}–${state.fretRange.last} · nearest: ${nearestVoicings.joinToString("  ") { it.tab }}"
                        voicings.isEmpty() -> "No playable shapes found"
                        voicing == null -> "All chord tones · ${voicings.size} shapes"
                        else -> "Shape ${state.voicingIndex + 1} of ${voicings.size}  ·  ${voicing.tab}"
                    },
                    enabled = voicings.isNotEmpty(),
                    onStep = vm::stepVoicing,
                )
                ViewMode.Scale -> DiatonicChords(state, vm)
                ViewMode.Arpeggio, ViewMode.Note -> {}
                ViewMode.Song -> SongBars(state) { i ->
                    vm.selectBar(i)
                    val s = vm.state.value
                    (s.song as? Progression)?.let { p -> s.songChord?.let { p.voicingFor(it, s.baseTuning, s.fretCount) } }?.let(::strum)
                }
            }
        }

        Box(Modifier.fillMaxSize().padding(padding)) {
            if (twoPane) {
                // Desktop and tablets: a quiet, fixed-width sidebar of controls; the neck is the stage.
                Row(Modifier.fillMaxSize()) {
                    val side = rememberScrollState()
                    Box(Modifier.width(sidebarWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(side).padding(horizontal = 20.dp, vertical = if (short) 12.dp else 20.dp),
                            verticalArrangement = Arrangement.spacedBy(if (short) 16.dp else 22.dp),
                        ) {
                            // On a phone on its side the neck range leads, beside the neck.
                            if (short) range()
                            level()
                            if (!short) view()
                            key(false); choice(true); tuning()
                            if (!short) range()
                            labels()
                        }
                        scrollbars?.Vertical(side, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val stage = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(stage)
                                .padding(horizontal = if (short) 16.dp else 28.dp, vertical = if (short) 10.dp else 22.dp),
                            verticalArrangement = Arrangement.spacedBy(if (short) 12.dp else 18.dp),
                        ) {
                            header(!short)
                            Row(verticalAlignment = Alignment.CenterVertically) { display() }
                            cagedChips()
                            board()
                            below()
                        }
                        scrollbars?.Vertical(stage, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            } else {
                // Phones: one column, the neck high up, transport in the thumb zone below.
                val page = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(page).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // The neck within the first screenful, its range sliders right under it; the finer controls follow.
                        level()
                        header(false)
                        board()
                        range()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CycleButton(Display.entries, state.display, Display::name, vm::setDisplay, Modifier.weight(1f))
                            CycleButton(modes, state.viewMode, ViewMode::name, vm::setViewMode, Modifier.weight(1f))
                        }
                        key(true); choice(false)
                        cagedChips()
                        below()
                        tuning(); labels()
                    }
                    scrollbars?.Vertical(page, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
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

/** One line of text, shrunk (down to 9 sp) until it fits its space rather than being cut off. */
@Composable
private fun FitText(text: String, style: TextStyle) {
    BasicText(
        text,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** Fretboard / Tab / Score. */
@Composable
private fun DisplaySwitch(current: Display, onPick: (Display) -> Unit, cycle: Boolean = false) {
    if (cycle) CycleButton(Display.entries, current, Display::name, onPick)
    else SingleChoiceSegmentedButtonRow {
        Display.entries.forEachIndexed { i, d ->
            SegmentedButton(
                selected = current == d,
                onClick = { onPick(d) },
                shape = SegmentedButtonDefaults.itemShape(i, Display.entries.size),
                icon = {},
                label = { Text(d.name, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** One button for a set of choices: it reads the next one (Fretboard showing → "Tab"), and a press moves on to it. */
@Composable
private fun <T> CycleButton(options: List<T>, current: T, name: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    val next = options[(options.indexOf(current) + 1) % options.size]
    FilledTonalButton(onClick = { onPick(next) }, modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Text(name(next), maxLines = 1, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** A group of controls with a small, quiet heading: spacing does the grouping, not boxes. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** Logo and name, then the actions as icons with tooltips. */
@Composable
private fun AppTopBar(
    short: Boolean,
    display: (@Composable () -> Unit)?,
    state: UiState,
    canOpen: Boolean,
    canSave: Boolean,
    onUndo: () -> Unit,
    onOpen: () -> Unit,
    onPdf: (Boolean) -> Unit,
    onMidi: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            LocalLogo.current?.let { Image(it, contentDescription = null, modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(if (short) 28.dp else 36.dp)) }
        },
        title = {
            BoxWithConstraints {
                Text(
                    if (maxWidth < 240.dp) "NeckScape" else "NeckScape-Navigator",
                    maxLines = 1,
                    style = if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                )
            }
        },
        actions = {
            display?.let {
                it()
                Spacer(Modifier.width(8.dp))
            }
            if (state.undoDepth > 0) ToolIcon("Undo the last change", AppIcons.Undo, onUndo)
            if (canOpen) ToolIcon("Open a song or tab", AppIcons.Open, onOpen)
            if (canSave) SaveMenu(state.display, onPdf, onMidi)
            ToolIcon("Settings", AppIcons.Tune, onSettings)
            Spacer(Modifier.width(4.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        expandedHeight = if (short) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
    )
}

@Composable
private fun ToolIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/** Save the current view as a PDF (alone, or with neck + score + tab), or as a MIDI file. */
@Composable
private fun SaveMenu(display: Display, onPdf: (everything: Boolean) -> Unit, onMidi: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIcon("Save as PDF or MIDI", AppIcons.Save) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("PDF: ${display.name.lowercase()} only") }, onClick = { open = false; onPdf(false) })
            DropdownMenuItem(text = { Text("PDF: fretboard, score and tab") }, onClick = { open = false; onPdf(true) })
            DropdownMenuItem(text = { Text("MIDI file") }, onClick = { open = false; onMidi() })
        }
    }
}

/** Always in reach at the bottom: a big Play/Stop, the tempo and the sound. */
@Composable
private fun TransportBar(state: UiState, vm: NeckScapeViewModel, playing: Boolean, enabled: Boolean, short: Boolean, onPlay: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            val compact = maxWidth < 600.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (short) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledIconButton(
                    onClick = onPlay,
                    enabled = enabled,
                    modifier = Modifier.size(if (short) 48.dp else 56.dp),
                    shape = CircleShape,
                ) {
                    Icon(if (playing) AppIcons.Stop else AppIcons.Play, contentDescription = if (playing) "Stop" else "Play", Modifier.size(if (short) 26.dp else 30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Tempo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("♩ = ${state.tempo}", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = state.tempo.toFloat(),
                        onValueChange = { vm.setTempo((it / 5).roundToInt() * 5) },
                        valueRange = MIN_TEMPO.toFloat()..MAX_TEMPO.toFloat(),
                        modifier = Modifier.height(32.dp),
                    )
                }
                SoundPicker(state.instrument, compact, vm::setInstrument)
            }
        }
    }
}

/** The playback sound, as a labelled button (or just the icon on a phone). */
@Composable
private fun SoundPicker(current: Instrument, compact: Boolean, onPick: (Instrument) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.Speaker, contentDescription = "Sound: ${current.label}")
            }
        } else {
            FilledTonalButton(onClick = { open = true }, shape = MaterialTheme.shapes.medium, modifier = Modifier.height(48.dp)) {
                Icon(AppIcons.Speaker, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(current.label, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(AppIcons.ChevronDown, contentDescription = null, Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Sound", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            Instrument.entries.forEach { i ->
                DropdownMenuItem(
                    text = { Text(i.label, fontWeight = if (i == current) FontWeight.SemiBold else null) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

/** The twelve keys: a 4 × 3 grid in the sidebar, a scrolling row on a phone. */
@Composable
private fun RootPicker(selected: Int, compact: Boolean, onSelect: (Int) -> Unit) {
    val chip = @Composable { pc: Int, modifier: Modifier ->
        val on = pc == selected
        Surface(
            onClick = { onSelect(pc) },
            shape = MaterialTheme.shapes.small,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = modifier.height(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(ROOT_LABELS[pc], style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
    if (compact) {
        val row = rememberLazyListState()
        Column {
            LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(12) { pc -> chip(pc, Modifier.width(if (ROOT_LABELS[pc].length > 2) 72.dp else 52.dp)) }
            }
            LocalScrollbars.current?.HorizontalLazy(row, Modifier.fillMaxWidth())
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rowStart in 0 until 12 step 4) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pc in rowStart until rowStart + 4) chip(pc, Modifier.weight(1f))
            }
        }
    }
}

/** A note of what's showing: name over interval, in its degree colour. */
@Composable
private fun NoteChip(name: String, interval: String, color: Color) {
    Column(
        Modifier.background(color, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge)
        Text(interval, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
    }
}

/** A coloured "marked" / "overlay" line with a clear button. */
@Composable
private fun StatusPill(text: String, color: Color, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        )
        IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) { Icon(AppIcons.Close, contentDescription = "Clear", Modifier.size(18.dp)) }
    }
}

/** Fret range (nut → bridge) and string range (low → high). */
@Composable
private fun NeckRange(state: UiState, vm: NeckScapeViewModel) {
    val frets = state.fretRange
    val strings = state.stringRange
    val tuning = state.tuning
    // Guitarists number strings from the highest-pitched one, which is index strings-1.
    fun stringLabel(s: Int) = "${tuning.strings - s} (${tuning.stringNames[s]})"
    Section("Neck range") {
        Column {
            LabelledValue("Frets", "${frets.first}–${frets.last}")
            RangeSlider(
                value = frets.first.toFloat()..frets.last.toFloat(),
                onValueChange = { vm.setFretRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                valueRange = 0f..state.fretCount.toFloat(),
                steps = state.fretCount - 1,
            )
            LabelledValue("Strings", "${stringLabel(strings.first)} to ${stringLabel(strings.last)}")
            RangeSlider(
                value = strings.first.toFloat()..strings.last.toFloat(),
                onValueChange = { vm.setStringRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                valueRange = 0f..(tuning.strings - 1).toFloat(),
                steps = (tuning.strings - 2).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

/** ‹ text › in a soft pill: stepping through shapes. */
@Composable
private fun VoicingBar(text: String, enabled: Boolean, onStep: (Int) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }, enabled = enabled) { Icon(AppIcons.ChevronLeft, contentDescription = "Previous") }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            IconButton(onClick = { onStep(1) }, enabled = enabled) { Icon(AppIcons.ChevronRight, contentDescription = "Next") }
        }
    }
}

@Composable
private fun DiatonicChords(state: UiState, vm: NeckScapeViewModel) {
    val chords = state.keyChords
    if (chords.isEmpty()) return
    // A tap shows that chord over the scale (at Advanced, its CAGED shapes); "Open" takes it to Chord view.
    val shapes = state.level == Level.Advanced && state.cagedEnabled
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CHORDS IN THIS KEY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.level != Level.Beginner) {
                Text("7ths", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.diatonicSevenths, onCheckedChange = vm::setDiatonicSevenths)
            }
        }
        val row = rememberLazyListState()
        LazyRow(state = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(chords) { degree, chord ->
                val usable = chord.type != null && state.level.allows(chord.type)
                val picked = state.keyChord == degree && state.pickedKeyChord != null
                Surface(
                    onClick = { vm.pickKeyChord(degree) },
                    enabled = usable,
                    shape = MaterialTheme.shapes.medium,
                    color = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(52.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(chord.numeral, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            chord.type?.displayName(chord.root) ?: "?",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
        LocalScrollbars.current?.HorizontalLazy(row, Modifier.fillMaxWidth())
        run {
            val keyed = state.pickedKeyChord
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = keyed?.type?.displayName(keyed.root)
                Text(
                    when {
                        keyed == null && shapes -> "Tap a chord to see its CAGED shapes over the scale"
                        keyed == null -> "Tap a chord to pick out its notes in the scale"
                        shapes -> "$name's CAGED shapes over the scale · tap it again for the key's own"
                        else -> "$name's notes stand out in the scale · tap it again to clear"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (keyed != null) TextButton(onClick = { vm.openChord(state.keyChord, keyed.root, keyed.type!!) }) {
                    Text("Open ${keyed.type!!.displayName(keyed.root)}")
                }
            }
        }
    }
}

/** The score or tab of what's selected, on white paper, as wide as the screen allows. */
@Composable
private fun Notation(music: Music, staves: Staves, highlight: (Tag) -> Color?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.value
        val sheet = remember(music, staves, width) {
            val margin = 12f
            stack(width, Engraver(music, screenStaffSpace(width)).blocks(staves, width - 2 * margin), gap = 10f, margin = margin)
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("CAGED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
        FilterChip(selected = state.cagedPick < 0, onClick = { vm.setCaged(-1) }, label = { Text("All") }, shape = MaterialTheme.shapes.small)
        state.cagedPositions.forEachIndexed { i, p ->
            FilterChip(
                selected = state.cagedPick == i,
                onClick = { vm.setCaged(i) },
                shape = MaterialTheme.shapes.small,
                leadingIcon = { Box(Modifier.size(10.dp).background(cagedColor(p.shape.name), CircleShape)) },
                label = {
                    Text(p.shape.name, fontWeight = FontWeight.SemiBold)
                    Text("  ${p.frets.first}–${p.frets.last}", style = MaterialTheme.typography.labelSmall)
                },
            )
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

/** A labelled field that opens a menu; [header] can insert a group title before an item. */
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
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(items[selected], maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(AppIcons.ChevronDown, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, item ->
                header(i)?.let {
                    Text(
                        it.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                DropdownMenuItem(
                    text = { Text(item, fontWeight = if (i == selected) FontWeight.SemiBold else null) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    },
                )
            }
        }
    }
}
