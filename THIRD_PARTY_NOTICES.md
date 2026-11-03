# Third-party notices

NeckScape-Navigator includes or is derived from the following third-party material.

## tonal — chord and scale dictionaries

`app/src/main/kotlin/com/crsnow/neckscape/theory/TonalData.kt` is generated from
the chord and scale dictionaries of [tonal](https://github.com/tonaljs/tonal)
by `tools/import_tonal.py`.

> MIT License
>
> Copyright (c) 2015 danigb
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## Bravura — music symbols

The clefs, noteheads and accidentals in
`app/src/main/kotlin/com/crsnow/neckscape/notation/Glyphs.kt` are outlines taken
from the [Bravura](https://github.com/steinbergmedia/bravura) font by
`tools/gen_glyphs.py`. No font file is distributed, and nothing in this project is
named "Bravura".

Copyright © 2015, Steinberg Media Technologies GmbH (http://www.steinberg.net/),
with Reserved Font Name "Bravura". Licensed under the SIL Open Font License,
Version 1.1: https://openfontlicense.org — full text in
[licenses/OFL-1.1.txt](licenses/OFL-1.1.txt).

## DejaVu Sans — text on the score, tab and PDFs

The letters and digits in `Glyphs.kt` are outlines taken from DejaVu Sans and
DejaVu Sans Bold. No font file is distributed.

Fonts are (c) Bitstream (see below). DejaVu changes are in public domain.
Bitstream Vera Fonts Copyright (c) 2003 by Bitstream, Inc. All Rights Reserved.
Bitstream Vera is a trademark of Bitstream, Inc. Full licence:
[licenses/DejaVu-LICENSE.txt](licenses/DejaVu-LICENSE.txt).

## Inter — the interface typeface

`app/src/main/res/font/inter_*.ttf` are static Regular, Medium, SemiBold and Bold
instances of [Inter](https://github.com/rsms/inter), cut from the variable font
in Google Fonts. Copyright 2020 The Inter Project Authors. Licensed under the
SIL Open Font License, Version 1.1 — full text in
[licenses/Inter-OFL.txt](licenses/Inter-OFL.txt).

## Libraries

Built with Jetpack Compose / Compose Multiplatform, Kotlin, kotlinx.coroutines
and AndroidX Lifecycle (all Apache License 2.0). The Windows and macOS builds
bundle the [Eclipse Temurin](https://adoptium.net) Java runtime (GPLv2 with the
Classpath Exception); its licence files are inside the bundled runtime's
`legal` folder.

## MCP server and development tools

The MCP server (`mcp/`) uses the [Model Context Protocol Python SDK](https://github.com/modelcontextprotocol/python-sdk)
and [jsonschema](https://github.com/python-jsonschema/jsonschema) (both MIT), installed from
PyPI by `tools/install_mcp.sh`; they are not bundled.

The Guitar Pro test files in `app/src/test/resources/gp` were written with
[PyGuitarPro](https://github.com/Perlence/PyGuitarPro) (LGPL-3.0) by
`tools/make_gp_fixtures.py`. PyGuitarPro is a development tool only; the app's
Guitar Pro reader is its own code.

`demo/library/Traditional - Greensleeves.musicxml` is the traditional English
tune (public domain), arranged for this project.

## Trademarks

Guitar Pro is a trademark of Arobas Music. MusicXML is a registered trademark of
MakeMusic, Inc. MIDI is a trademark of the Association of Musical Electronics
Industry (AMEI) and the MIDI Manufacturers Association. These names are used only
to say which files NeckScape-Navigator can open or save; it isn't affiliated
with, sponsored by or endorsed by any of these companies. The file readers are
written from the formats' published documentation and don't use code from the
owners' software.

## Feature video

The narration in the feature video was synthesised offline with
[Piper](https://github.com/OHF-Voice/piper1-gpl) using the `en_GB-cori-high`
voice, trained on public-domain LibriVox recordings. Piper itself is not part
of NeckScape-Navigator.
