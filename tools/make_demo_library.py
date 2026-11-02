#!/usr/bin/env python3
"""Writes demo/library/Traditional - Greensleeves.musicxml: the traditional
English tune (public domain), in first position with tab, for trying find_tab
and for the feature video."""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "demo/library/Traditional - Greensleeves.musicxml"

# (note, octave, quarters) — A minor, 3/4, verse then refrain.
VERSE = [[("A", 3, 1)],
         [("C", 4, 2), ("D", 4, 1)], [("E", 4, 1.5), ("F", 4, 0.5), ("E", 4, 1)], [("D", 4, 2), ("B", 3, 1)],
         [("G", 3, 1.5), ("A", 3, 0.5), ("B", 3, 1)], [("C", 4, 2), ("A", 3, 1)], [("A", 3, 1.5), ("G#", 3, 0.5), ("A", 3, 1)],
         [("B", 3, 2), ("G#", 3, 1)], [("E", 3, 2), ("A", 3, 1)], [("C", 4, 2), ("D", 4, 1)], [("E", 4, 1.5), ("F", 4, 0.5), ("E", 4, 1)],
         [("D", 4, 2), ("B", 3, 1)], [("G", 3, 1.5), ("A", 3, 0.5), ("B", 3, 1)], [("C", 4, 1.5), ("B", 3, 0.5), ("A", 3, 1)],
         [("G#", 3, 1.5), ("F#", 3, 0.5), ("G#", 3, 1)], [("A", 3, 3)]]
REFRAIN = [[("G", 4, 3)], [("G", 4, 1.5), ("F#", 4, 0.5), ("E", 4, 1)], [("D", 4, 2), ("B", 3, 1)],
           [("G", 3, 1.5), ("A", 3, 0.5), ("B", 3, 1)], [("C", 4, 2), ("A", 3, 1)], [("A", 3, 1.5), ("G#", 3, 0.5), ("A", 3, 1)],
           [("B", 3, 2), ("G#", 3, 1)], [("E", 3, 3)], [("G", 4, 3)], [("G", 4, 1.5), ("F#", 4, 0.5), ("E", 4, 1)],
           [("D", 4, 2), ("B", 3, 1)], [("G", 3, 1.5), ("A", 3, 0.5), ("B", 3, 1)], [("C", 4, 1.5), ("B", 3, 0.5), ("A", 3, 1)],
           [("G#", 3, 1.5), ("F#", 3, 0.5), ("G#", 3, 1)], [("A", 3, 3)]]

# First-position fingering: note → (string, fret), string 1 = high E.
POS = {("E", 3): (4, 2), ("F#", 3): (4, 4), ("G", 3): (3, 0), ("G#", 3): (3, 1), ("A", 3): (3, 2), ("B", 3): (2, 0),
       ("C", 4): (2, 1), ("D", 4): (2, 3), ("E", 4): (1, 0), ("F", 4): (1, 1), ("F#", 4): (1, 2), ("G", 4): (1, 3)}
TYPES = {3: ("half", True), 2: ("half", False), 1.5: ("quarter", True), 1: ("quarter", False), 0.5: ("eighth", False)}


def note(name, octave, q):
    step, alter = name[0], ("<alter>1</alter>" if name.endswith("#") else "")
    string, fret = POS[(name, octave)]
    kind, dot = TYPES[q]
    return (f"<note><pitch><step>{step}</step>{alter}<octave>{octave}</octave></pitch><duration>{int(q * 2)}</duration>"
            f"<type>{kind}</type>{'<dot/>' if dot else ''}<notations><technical><string>{string}</string><fret>{fret}</fret>"
            f"</technical></notations></note>")


def main():
    tuning = "".join(f'<staff-tuning line="{i + 1}"><tuning-step>{s}</tuning-step><tuning-octave>{o}</tuning-octave></staff-tuning>'
                     for i, (s, o) in enumerate([("E", 2), ("A", 2), ("D", 3), ("G", 3), ("B", 3), ("E", 4)]))
    measures = []
    for i, bar in enumerate(VERSE + REFRAIN):
        head = ""
        if i == 0:
            head = (f'<attributes><divisions>2</divisions><key><fifths>0</fifths><mode>minor</mode></key><time><beats>3</beats>'
                    f'<beat-type>4</beat-type></time><clef><sign>TAB</sign><line>5</line></clef><staff-details><staff-lines>6</staff-lines>{tuning}'
                    f'</staff-details></attributes><direction placement="above"><direction-type><rehearsal>Verse</rehearsal></direction-type>'
                    f'<sound tempo="100"/></direction>')
        if i == len(VERSE):
            head = '<direction placement="above"><direction-type><rehearsal>Refrain</rehearsal></direction-type></direction>'
        attr = ' implicit="yes"' if i == 0 else ""
        measures.append(f'<measure number="{i}"{attr}>{head}{"".join(note(*n) for n in bar)}</measure>')
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<score-partwise version="4.0">'
           '<work><work-title>Greensleeves</work-title></work>'
           '<identification><creator type="composer">Traditional</creator><rights>Public domain</rights></identification>'
           '<part-list><score-part id="P1"><part-name>Guitar</part-name></score-part></part-list>'
           f'<part id="P1">{"".join(measures)}</part></score-partwise>\n')
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(xml)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
