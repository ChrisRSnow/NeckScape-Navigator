#!/usr/bin/env python3
"""Writes small Guitar Pro 3/4/5 test files for the Kotlin reader's unit tests.

They're made with PyGuitarPro (LGPL-3.0, a development tool only — nothing of
it ships in the app), so the reader is checked against an independent
implementation of the format.

    python3 -m venv /tmp/gpvenv && /tmp/gpvenv/bin/pip install pyguitarpro==0.10.1
    /tmp/gpvenv/bin/python tools/make_gp_fixtures.py
"""
from pathlib import Path

import guitarpro as gp

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/gp"


def beat(voice, value, notes, dotted=False):
    b = gp.Beat(voice, duration=gp.Duration(value=value, isDotted=dotted), status=gp.BeatStatus.normal if notes else gp.BeatStatus.rest)
    for string, fret, kind, setup in notes:
        n = gp.Note(b, value=fret, string=string, type=kind)
        if setup:
            setup(n)
        b.notes.append(n)
    voice.beats.append(b)


def hammer(n):
    n.effect.hammer = True


def slide(n):
    n.effect.slides = [gp.SlideType.shiftSlideTo]


def bend_full(n):
    # Point values are in semitones; a "full" bend is a whole tone.
    n.effect.bend = gp.BendEffect(type=gp.BendType.bend, value=100,
                                  points=[gp.BendPoint(0, 0), gp.BendPoint(6, 2), gp.BendPoint(12, 2)])


def song():
    s = gp.Song(title="GP Fixture", artist="NeckScape tests", tempo=96)
    rhythm = s.tracks[0]
    rhythm.name = "Rhythm Guitar"
    lead = gp.Track(s, number=2, name="Lead Guitar", strings=[gp.GuitarString(i + 1, v) for i, v in enumerate([64, 59, 55, 50, 45, 40])])
    lead.channel = gp.MidiChannel(channel=1, effectChannel=1, instrument=29)
    s.tracks.append(lead)
    s.newMeasure()
    s.newMeasure()
    s.measureHeaders[0].marker = gp.Marker("Intro")
    s.measureHeaders[1].marker = gp.Marker("Solo")
    for track in s.tracks:
        while len(track.measures) < len(s.measureHeaders):
            track.measures.append(gp.Measure(track, s.measureHeaders[len(track.measures)]))
    N, TIE, DEAD = gp.NoteType.normal, gp.NoteType.tie, gp.NoteType.dead

    # Rhythm: a C chord, a rest, then G.
    v = rhythm.measures[0].voices[0]
    beat(v, 2, [(5, 3, N, None), (4, 2, N, None), (3, 0, N, None), (2, 1, N, None), (1, 0, N, None)])
    beat(v, 4, [])
    beat(v, 4, [(6, 3, N, None), (1, 3, N, None)])
    for m in rhythm.measures[1:]:
        beat(m.voices[0], 1, [(6, 3, N, None)])

    # Lead: hammer-on 5→7, a slide 5→7, then a bend and a tie, then a dotted chord and a dead note.
    v = lead.measures[0].voices[0]
    beat(v, 4, [(3, 5, N, hammer)])
    beat(v, 4, [(3, 7, N, None)])
    beat(v, 4, [(1, 5, N, slide)])
    beat(v, 4, [(1, 7, N, None)])
    v = lead.measures[1].voices[0]
    beat(v, 2, [(2, 8, N, bend_full)])
    beat(v, 4, [(2, 8, TIE, None)])
    beat(v, 8, [(3, 7, N, None)])
    beat(v, 8, [(3, 5, N, None)])
    v = lead.measures[2].voices[0]
    beat(v, 2, [(2, 5, N, None), (1, 5, N, None)], dotted=True)
    beat(v, 4, [(5, 0, DEAD, None)])
    return s


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for ext, version in [("gp3", (3, 0, 0)), ("gp4", (4, 0, 0)), ("gp5", (5, 1, 0)), ("500.gp5", (5, 0, 0))]:
        path = OUT / f"fixture.{ext}"
        gp.write(song(), str(path), version=version)
        back = gp.parse(str(path))
        assert back.tracks[1].name == "Lead Guitar", back.tracks[1].name
        print("wrote", path.relative_to(OUT.parent.parent.parent.parent), path.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
