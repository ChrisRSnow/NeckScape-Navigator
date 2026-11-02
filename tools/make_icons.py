#!/usr/bin/env python3
"""Draws the NeckScape-Navigator logo and writes every platform's icon.

A ship's wheel in the teal/cyan/white palette of 1990s web-browser splash
screens, with guitar necks for spokes: each neck runs from a soundhole hub
through the rim and ends in a headstock — six-in-line and three-a-side shapes
alternating round the wheel. Original artwork; the headstocks are generic
"inline" and "3+3" shapes, not any maker's outline.

Needs CairoSVG and Pillow:  python3 tools/make_icons.py
Writes:
  icons/neckscape.svg, icons/neckscape-1024.png        master artwork
  icons/neckscape.ico (Windows), icons/neckscape.icns (macOS), icons/neckscape.png (Linux)
  app/src/main/res/mipmap-*/ic_launcher_{foreground,background,monochrome}.png (Android adaptive icon)
  desktop/src/main/resources/icon.png                window icon
"""
import io
import math
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent

# Palette: deep sea teal to black, cyan glow, white highlights, lime streaks.
DEEP = "#03181d"
SEA = "#0a4f57"
GLOW = "#2fb3bd"
CYAN = "#8fe3ea"
PALE = "#e9fcff"
INK = "#05262d"
LIME = "#c6dd3e"

# Wheel geometry, in a 1024 box centred on (0, 0); everything fits inside r = 490.
RIM = 300          # rim centre line
RIM_W = 46
HUB = 98
NUT = 392          # where neck meets headstock
HEAD = 104         # headstock length


def neck(angle: float, inline: bool) -> str:
    """One spoke: fretboard from the hub to the nut, then a headstock, drawn along +x and rotated."""
    w0, w1 = 22, 16  # half widths at hub and nut
    parts = [f'<g transform="rotate({angle:.2f})">']
    # Fretboard.
    parts.append(f'<path d="M {HUB - 10},{-w0} L {NUT},{-w1} L {NUT},{w1} L {HUB - 10},{w0} Z" '
                 f'fill="url(#board)" stroke="{INK}" stroke-width="5" stroke-linejoin="round"/>')
    # Frets: closer together towards the body (here, the nut is outward).
    scale = NUT - HUB
    for k in range(1, 12):
        x = NUT - scale * (1 - 2 ** (-k / 12)) * 1.9
        if x <= HUB + 6:
            break
        hw = w1 + (w0 - w1) * (NUT - x) / scale
        parts.append(f'<line x1="{x:.1f}" y1="{-hw + 2:.1f}" x2="{x:.1f}" y2="{hw - 2:.1f}" stroke="{CYAN}" stroke-width="2.6" opacity="0.85"/>')
    # Strings.
    for s in (-0.55, -0.18, 0.18, 0.55):
        parts.append(f'<line x1="{HUB}" y1="{s * w0:.1f}" x2="{NUT}" y2="{s * w1:.1f}" stroke="{PALE}" stroke-width="1.6" opacity="0.7"/>')
    # Nut.
    parts.append(f'<rect x="{NUT - 3}" y="{-w1 - 1}" width="7" height="{2 * w1 + 2}" fill="{PALE}" stroke="{INK}" stroke-width="2"/>')
    # Headstock.
    n = NUT + 4
    if inline:
        # Six in line: straight lower edge, a swelling upper edge, a hooked tip.
        d = (f"M {n},{-w1} C {n + 30},{-w1 - 2} {n + 62},{-w1 - 6} {n + 88},{-w1 - 8} "
             f"C {n + 104},{-w1 - 9} {n + HEAD + 6},{-6} {n + HEAD},{6} "
             f"C {n + HEAD - 6},{20} {n + 92},{34} {n + 74},{38} "
             f"C {n + 54},{42} {n + 34},{28} {n + 20},{20} "
             f"C {n + 12},{16} {n + 6},{w1} {n},{w1} Z")
        tuners = [(n + 16 + i * 14.5, -w1 + 7) for i in range(6)]
        buttons = [(x, -w1 - 13) for x, _ in tuners]
    else:
        # Three a side: symmetric flare, flat top with cut corners.
        d = (f"M {n},{-w1} C {n + 26},{-w1 - 4} {n + 66},{-30} {n + 88},{-32} "
             f"L {n + HEAD},{-22} L {n + HEAD},{22} L {n + 88},{32} "
             f"C {n + 66},{30} {n + 26},{w1 + 4} {n},{w1} Z")
        tuners = [(n + 34 + i * 25, sgn * (17 + i * 3)) for sgn in (-1, 1) for i in range(3)]
        buttons = [(x, y + (12 if y > 0 else -12)) for x, y in tuners]
    for (bx, by) in buttons:
        parts.append(f'<circle cx="{bx:.1f}" cy="{by:.1f}" r="6.5" fill="{CYAN}" stroke="{INK}" stroke-width="3"/>')
    parts.append(f'<path d="{d}" fill="url(#head)" stroke="{INK}" stroke-width="6" stroke-linejoin="round"/>')
    for (tx, ty) in tuners:
        parts.append(f'<circle cx="{tx:.1f}" cy="{ty:.1f}" r="4.6" fill="{INK}"/>')
    parts.append("</g>")
    return "\n".join(parts)


def wheel(mono: bool = False) -> str:
    """The wheel alone (transparent background), centred on (0, 0)."""
    parts = []
    for i in range(8):
        parts.append(neck(i * 45 - 90 + 22.5, inline=i % 2 == 0))
    # Rim over the necks, as a ship's wheel's spokes pass through it.
    parts.append(f'<circle r="{RIM}" fill="none" stroke="{INK}" stroke-width="{RIM_W + 12}"/>')
    parts.append(f'<circle r="{RIM}" fill="none" stroke="url(#rim)" stroke-width="{RIM_W}"/>')
    parts.append(f'<circle r="{RIM - RIM_W / 2 + 7}" fill="none" stroke="{INK}" stroke-width="3" opacity="0.6"/>')
    parts.append(f'<circle r="{RIM + RIM_W / 2 - 7}" fill="none" stroke="{PALE}" stroke-width="3" opacity="0.8"/>')
    # Hub: a soundhole with a rosette.
    parts.append(f'<circle r="{HUB}" fill="url(#rim)" stroke="{INK}" stroke-width="8"/>')
    parts.append(f'<circle r="{HUB - 18}" fill="none" stroke="{LIME}" stroke-width="5"/>')
    parts.append(f'<circle r="{HUB - 27}" fill="none" stroke="{SEA}" stroke-width="4"/>')
    parts.append(f'<circle r="{HUB - 36}" fill="{DEEP}" stroke="{INK}" stroke-width="4"/>')
    parts.append(f'<circle cx="-14" cy="-16" r="10" fill="{GLOW}" opacity="0.35"/>')
    body = "\n".join(parts)
    if mono:
        body = body.replace('url(#board)', 'white').replace('url(#head)', 'white').replace('url(#rim)', 'white')
    return body


DEFS = f"""
<defs>
  <radialGradient id="sea" cx="50%" cy="42%" r="70%">
    <stop offset="0" stop-color="{GLOW}"/>
    <stop offset="0.38" stop-color="{SEA}"/>
    <stop offset="1" stop-color="{DEEP}"/>
  </radialGradient>
  <linearGradient id="rim" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="{PALE}"/>
    <stop offset="0.55" stop-color="{CYAN}"/>
    <stop offset="1" stop-color="{GLOW}"/>
  </linearGradient>
  <linearGradient id="head" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="{PALE}"/>
    <stop offset="1" stop-color="{CYAN}"/>
  </linearGradient>
  <linearGradient id="board" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="{INK}"/>
    <stop offset="1" stop-color="{SEA}"/>
  </linearGradient>
</defs>"""


def streaks() -> str:
    """Faint lime light-streaks and a few stars, as on the old splash screens."""
    lines = [((-560, 120), (560, -260)), ((-560, -330), (560, 40)), ((-300, 560), (420, -560)), ((-560, 380), (560, 300))]
    out = [f'<line x1="{a[0]}" y1="{a[1]}" x2="{b[0]}" y2="{b[1]}" stroke="{LIME}" stroke-width="3" opacity="0.32"/>' for a, b in lines]
    for x, y, r in [(-380, -360, 4), (330, -400, 3), (410, 250, 4), (-420, 300, 3), (200, 430, 3), (-200, -440, 2.5)]:
        out.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{PALE}" opacity="0.9"/>')
    return "\n".join(out)


def svg(content: str, size: int = 1024) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="-512 -512 1024 1024">'
            f"{DEFS}{content}</svg>")


def tile() -> str:
    """The desktop/store icon: rounded tile, sea, streaks, wheel."""
    return svg(f'<clipPath id="tile"><rect x="-480" y="-480" width="960" height="960" rx="200"/></clipPath>'
               f'<g clip-path="url(#tile)"><rect x="-512" y="-512" width="1024" height="1024" fill="url(#sea)"/>{streaks()}</g>'
               f'<rect x="-480" y="-480" width="960" height="960" rx="200" fill="none" stroke="{INK}" stroke-width="10"/>'
               f'<g transform="scale(0.9)">{wheel()}</g>')


def render(svg_text: str, size: int) -> Image.Image:
    png = cairosvg.svg2png(bytestring=svg_text.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def main():
    icons = ROOT / "icons"
    icons.mkdir(exist_ok=True)
    master = tile()
    (icons / "neckscape.svg").write_text(master)
    big = render(master, 1024)
    big.save(icons / "neckscape-1024.png")
    big.resize((512, 512), Image.LANCZOS).save(icons / "neckscape.png")
    big.save(icons / "neckscape.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save(icons / "neckscape.icns")
    res = ROOT / "desktop/src/main/resources"
    res.mkdir(parents=True, exist_ok=True)
    big.resize((256, 256), Image.LANCZOS).save(res / "icon.png")

    # Android adaptive icon: 108dp layers; the mask shows the middle ~72dp, so the wheel sits in r ≈ 0.31.
    fg = svg(f'<g transform="scale(0.64)">{wheel()}</g>')
    bg = svg(f'<rect x="-512" y="-512" width="1024" height="1024" fill="url(#sea)"/>{streaks()}')
    mono = svg(f'<g transform="scale(0.64)">{wheel(mono=True)}</g>')
    for density, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
        out = ROOT / f"app/src/main/res/mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        render(fg, px).save(out / "ic_launcher_foreground.png")
        render(bg, px).save(out / "ic_launcher_background.png")
        render(mono, px).save(out / "ic_launcher_monochrome.png")
    print("icons written")


if __name__ == "__main__":
    main()
