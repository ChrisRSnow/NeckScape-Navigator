# NeckScape-Navigator

A landscape of the guitar neck, and a way to navigate it (yes, the name is a nod to Netscape Navigator).

Maps scales, arpeggios and chords onto a guitar fretboard, and shows them as
standard notation or tab — Android app plus Windows, macOS and Linux desktop
apps built from the same code.

- **Level** — Beginner (major and minor scales, chords and arpeggios, plus the
  two pentatonics), Intermediate (adds extended chords — 6ths, 7ths, 9ths,
  11ths, 13ths, sus, dim, aug — and the modes, blues, harmonic and melodic
  minor) or Advanced (everything). The pickers only offer what the level
  allows; switching level swaps in the nearest thing it does offer.

- **Scale view** — pick a root and a scale family, then any mode of it (101
  scales). Notes are coloured by degree and numbered in playing order; the
  diatonic triads/sevenths sit underneath (tap one to jump to it — the scale
  follows, so Dm from C Ionian becomes D Dorian).
- **Arpeggio view** — the chord's tones across the neck, numbered and played
  up and down like a scale (shares the chord with Chord view).
- **Chord view** — 106 chord types. Every chord tone on the neck, then step
  through playable shapes (‹ ›) generated for the current tuning.
- Switching views keeps them related: C Ionian ⇄ C, C Dorian ⇄ Cm, Cm7 → C Aeolian.
- **Fret range** and **string range** sliders limit what's shown (and which
  chord shapes are offered) — e.g. frets 5–8 shows one box pattern.
- **Sound** — tap any fret to hear it; ▶ Play runs the scale up and down in the
  numbered order (tempo slider, 40–240 BPM), or strums the chord. A ring shows
  the note(s) sounding. Stepping to a shape strums it.
- **Fretboard / Score / Tab** — the same notes as a neck diagram, in standard
  notation (treble clef 8vb for guitar, bass clef for bass, at pitch for
  ukulele; accidentals rather than a key signature, so every scale works) or as
  tab. Score and tab light up the note that's sounding; in Chord view they show
  every shape, with the selected one highlighted.
- **Ask your AI** — Claude Code, Codex or any MCP client can push chords,
  progressions, scales and tabs into the running app (desktop, or a phone over
  adb), read back what's showing, and find tabs in your own library. Pushed
  content is labelled ("AI-generated — check by ear") and every push can be
  undone. See [docs/ai-integration-spec.md](docs/ai-integration-spec.md) and
  [mcp/README.md](mcp/README.md).
- **Open** Guitar Pro (.gp3/.gp4/.gp5/.gp), MusicXML (.musicxml/.mxl), MIDI,
  plain-text tab or ChordPro files (chords kept, lyrics dropped) into the Song
  view: bars or measures to step through, score, tab and playback.
- **MIDI** export of whatever's showing — scale, arpeggio, chord shapes,
  progression or tab — from the Save menu.
- **PDF** — saves the current view, or the neck, chord boxes, score and tab on
  one handout. Vector output from a small built-in PDF writer, identical on
  every platform; Android saves through the system document picker.
- 11 preset tunings (guitar, 7-string, bass, ukulele) plus per-string custom
  tuning; notes or interval labels; 12–24 frets; left-handed mode.

## Build

```sh
# Android (Android Studio's bundled JDK is enough)
export JAVA_HOME=/snap/android-studio/current/jbr
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Desktop — needs a JDK with jpackage (Temurin 21 in ~/.jdks)
export JAVA_HOME=~/.jdks/jdk-21.0.13+9
./gradlew :desktop:run              # run it
./gradlew :desktop:test             # renders the screen to desktop/build/snapshots/*.png
tools/package_desktop.sh            # → desktop/build/dist/ (.deb, Linux tar.gz, Windows zip, macOS .app zips)
tools/make_video.sh                 # → desktop/build/neckscape-features.mp4 (feature tour)
tools/install_mcp.sh                # MCP server for AI assistants (see mcp/README.md)
```

The feature-tour video is scripted in `desktop/src/test/.../FeatureVideo.kt`:
it drives the real screen through each scene, renders frames headlessly, and
builds the soundtrack with the app's own synth — edit the script and re-run to
update it after UI changes. The voice-over text is `tools/narration.txt`
(one line per scene; scenes wait for their line), voiced offline by
[Piper](https://github.com/OHF-Voice/piper1-gpl) with the `en_GB-cori-high`
voice (trained on public-domain LibriVox recordings). Piper lives in
`~/.local/share/piper` (`python3 -m venv venv && venv/bin/pip install piper-tts`,
voice from `huggingface.co/rhasspy/piper-voices`).

The Windows zip is assembled on Linux (jpackage can only make Windows
installers on Windows): app jar with Windows natives + Temurin JRE + `NeckScape.bat`.
To build a real `.msi`, run `gradlew :desktop:packageMsi` on a Windows machine.
The macOS zips (Apple silicon and Intel) hold an unsigned `NeckScape.app` built
the same way — jar with macOS natives + Temurin macOS JRE + a launcher script.
First launch needs right-click → Open (see the README.txt inside). For a signed
`.dmg`, run `gradlew :desktop:packageDmg` on a Mac with a Developer ID.

AGP 9.4 (built-in Kotlin, so no `kotlin.android` plugin) · Kotlin 2.4 ·
Compose BOM 2026.09 (Android) / Compose Multiplatform 1.12 (desktop) ·
compileSdk 37 · minSdk 26.

## Layout

| Path | What |
|---|---|
| `app/.../theory/` | Plain Kotlin, no Android — all unit-tested in `TheoryTest` |
| `theory/Note.kt`, `Interval.kt` | Spelled notes (D♭ major's third is F, not E♯) and intervals |
| `theory/Scales.kt`, `Chords.kt` | Curated lists (modes generated by rotation) + tonal extras |
| `theory/TonalData.kt` | **Generated** — tonal's chord/scale dictionaries |
| `theory/Voicings.kt` | Brute-force shape finder (4-fret window, ≤4 fingers, root in bass) |
| `theory/PlayingOrder.kt` | Note numbering: up the strings, handing over as soon as the next string can |
| `theory/Harmony.kt` | Diatonic chords, home chord of a scale, best scale for a chord |
| `theory/PluckSynth.kt` | Karplus–Strong plucked-string synth (pure Kotlin) |
| `theory/Levels.kt` | Beginner / Intermediate / Advanced: what each level offers |
| `app/.../notation/` | Plain Kotlin: engraver (score, tab, neck, chord boxes), drawing model, PDF writer — **shared with desktop** |
| `notation/Glyphs.kt` | **Generated** by `tools/gen_glyphs.py` — Bravura music symbols + DejaVu Sans letters as vector outlines |
| `app/.../ui/` | Compose screen, fretboard canvas, ViewModel — **shared with desktop** |
| `app/.../` (root) | Android-only: `MainActivity`, theme, `AndroidAudioOutput` |
| `desktop/` | Compiles `theory/`, `notation/` and `ui/` directly; adds `Main.kt` and `JvmAudioOutput` |

Keep `ui/` free of `android.*` imports — the desktop build compiles it too.

## Notation and PDFs

Score, tab and PDFs are drawn from one vector model (`notation/Sheet.kt`): lines,
circles and filled outlines, with text and music symbols as glyph outlines, so
the screen and the PDF match exactly and the PDF needs no fonts. To change the
glyph set, edit `tools/gen_glyphs.py` and run it with fontTools against
`Bravura.otf` and the DejaVu Sans TTFs (usage in the script).

## Licences

NeckScape-Navigator is MIT licensed ([LICENSE](LICENSE)). Third-party material — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): tonal (MIT), Bravura (SIL
OFL 1.1), DejaVu Sans (Bitstream Vera licence), Compose/Kotlin (Apache 2.0),
bundled Temurin runtime (GPLv2 + Classpath Exception).

## Extending the dictionaries

Add to the curated lists in `Scales.kt` / `Chords.kt` by hand. Anything in
[tonal](https://github.com/tonaljs/tonal) (MIT, © 2015 danigb) that the curated
lists don't already cover appears under **More (tonal dictionary)**; refresh it
with `tools/import_tonal.py`. Curated entries always win on duplicates.

## Ideas / next steps

- Persist settings (DataStore / desktop prefs) — currently reset on restart.
- Key signatures and rhythms in the score; MusicXML export; tap a note in the score to hear it.
- Scale "position"/CAGED presets for the fret range; inversions toggle for shapes.
- Better voicing ranking (voice-leading, drop-2/3 shapes); compare
  [pcorey/chord](https://github.com/pcorey/chord) and [nschweitz/cg](https://github.com/nschweitz/cg).
- Richer sound: SoundFont playback as in [ScaleInKey](https://github.com/roge-rm/ScaleInKey) (MIT).
