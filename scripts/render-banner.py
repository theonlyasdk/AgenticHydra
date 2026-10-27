#!/usr/bin/env python3
"""Render GitHub's banner as portable SVG paths using the bundled OFL fonts.

Requires: pip install fonttools
"""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parents[1]


def lettering(text, font_name, size, x, baseline, color):
    font = TTFont(ROOT / 'app/src/main/res/font' / font_name)
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    scale = size / font['head'].unitsPerEm
    pen = SVGPathPen(glyphs)
    cursor = x
    for character in text:
        name = cmap[ord(character)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, cursor, baseline)))
        cursor += font['hmtx'][name][0] * scale
    return f'<path fill="{color}" d="{pen.getCommands()}"/>'


svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="560" viewBox="0 0 1600 560" role="img" aria-labelledby="title description">
<title id="title">Agentic Hydra</title>
<desc id="description">An Android workspace for chat, research, and coding. A teal trident surrounded by orbital rings on a dark background.</desc>
<rect width="1600" height="560" rx="32" fill="#0C1318"/>
<path d="M88 100h64" stroke="#80D5DE" stroke-width="5" stroke-linecap="round"/>
<g fill="none" stroke="#2D424B" stroke-width="2">
<circle cx="1262" cy="280" r="142"/>
<circle cx="1262" cy="280" r="204"/>
<ellipse cx="1262" cy="280" rx="258" ry="95" transform="rotate(-30 1262 280)"/>
</g>
<circle cx="1262" cy="280" r="91" fill="#153A42"/>
<g transform="translate(1165 178) scale(1.8)" fill="none" stroke="#80D5DE" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">
<path d="M54 78V34M54 62C38 62 34 53 34 35M54 62C70 62 74 53 74 35M28 37L34 30L40 37M48 41L54 34L60 41M68 37L74 30L80 37"/>
</g>
<circle cx="1120" cy="280" r="14" fill="#80D5DE"/>
<circle cx="1356" cy="106" r="10" fill="#A5C8FF"/>
<circle cx="1389" cy="343" r="19" fill="#EBC16B"/>
<circle cx="1130" cy="435" r="8" fill="#A5C8FF"/>
<circle cx="1490" cy="169" r="6" fill="#80D5DE"/>
'''
svg += lettering('Agentic Hydra', 'instrument_serif.ttf', 100, 86, 260, '#E1ECF0')
svg += lettering('An Android workspace for', 'instrument_sans.ttf', 31, 90, 328, '#ABBDC8')
svg += lettering('chat, research, and coding.', 'instrument_sans.ttf', 31, 90, 374, '#ABBDC8')
svg += lettering('YOUR MODELS.  YOUR WORKSPACE.', 'instrument_sans.ttf', 17, 90, 468, '#80D5DE')
svg += '\n</svg>\n'
output = ROOT / 'docs/assets/banner.svg'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(svg, encoding='utf-8')
print(f'Wrote {output.relative_to(ROOT)}')
