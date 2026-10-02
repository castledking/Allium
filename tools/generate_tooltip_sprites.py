#!/usr/bin/env python3
"""Build the trading-card tooltip sprites consumed by the position_tex_color shader.

The shader draws these 1:1 from the tooltip quad's top-left and stops at the
height encoded in the sprite, so the panel does not grow over the F3+H lines.

Sprite layout (row 0 is metadata and is never drawn):

    (0,0) marker  rgba(255,0,255,255)  identifies the quad as a trading card
    (1,0) height  R + 256*G             frame height in GUI pixels
    (2,0) caps    R = top, G = bottom   cap heights, measured from the artwork
    rows 1..n     the artwork, unchanged

Frame height is per lore length. The shader draws the art 1:1 and stops at the
height in the metadata row, and lore length varies per card (each signature is a
line, the xp row is optional), so a single height would leave a tall panel on a
short card and clip the last line on a long one. The plugin counts the lines it
just rendered and picks the matching sprite.

    H(n) = HEIGHT_BASE + LINE_PITCH * n

HEIGHT_BASE is calibrated against the client, not derived: the bottom cap is 15
art rows and the lore ends only about 3px above the quad's bottom edge, so the
cap cannot be positioned from the font metrics alone.

Usage:
    python3 tools/generate_tooltip_sprites.py --out <dir>
"""

import argparse
import json
import pathlib
from collections import Counter

from PIL import Image

TIERS = ("simple", "elite", "ultimate", "legendary", "fabled")

# Must match CardTooltipStyle.TEXT_TOP, .BOTTOM_CAP, .CAP_GAP and .LINE_PITCH.
# The frame has to clear the text AND the whole bottom cap, or the cap's top rule
# lands on the last lore line. TEXT_TOP is where lore line 0 starts: the tooltip's
# first line is a blanked name, so the lore is pushed down by its 10px plus the
# 2px vanilla puts after the name.
TEXT_TOP = 25
CAP_GAP = 2
LINE_PITCH = 10
MIN_LINES = 15
MAX_LINES = 30

# Cards already in circulation carry the old unsuffixed style id
# (sf:card_fabled). Nothing rewrites a card sitting in a player's inventory, so
# without these they would quietly fall back to the vanilla tooltip until the
# card was next written. Emitted at a typical lore length.
COMPAT_LINES = 21
HERE = pathlib.Path(__file__).resolve().parent.parent
ORIGINALS = HERE / "src/main/resources/tradingcards/tooltip-originals"

STRETCH_MCMETA = {"gui": {"scaling": {"type": "stretch"}}}


def measure(art: Image.Image) -> tuple[int, int]:
    """Find the top and bottom cap heights, measured rather than guessed.

    The plain panel row is the most common row in the art. Everything above its
    first occurrence is the top cap (the ornament), everything below its last is
    the bottom cap (the rules and flourishes). The rows between must be
    identical to it, because the shader repeats one of them to fill any height
    and stretching a non-uniform row smears it.
    """
    w, h = art.size
    px = art.load()
    rows = [tuple(px[x, y] for x in range(w)) for y in range(h)]
    ref = Counter(rows).most_common(1)[0][0]
    first = rows.index(ref)
    last = h - 1 - rows[::-1].index(ref)
    bad = [y for y in range(first, last + 1) if rows[y] != ref]
    if bad:
        raise SystemExit(
            f"non-uniform rows inside the middle band, cannot repeat: {bad}")
    return first, h - 1 - last


def build(art: Image.Image, height: int) -> Image.Image:
    """Wrap artwork in a metadata row carrying the marker, height and caps."""
    w, h = art.size
    top, bot = measure(art)
    out = Image.new("RGBA", (w, h + 1), (0, 0, 0, 0))
    out.paste(art, (0, 1))
    out.putpixel((0, 0), (255, 0, 255, 255))
    out.putpixel((1, 0), (height & 255, (height >> 8) & 255, 0, 255))
    out.putpixel((2, 0), (top, bot, 0, 255))
    print(f"    caps: top {top}, bottom {bot}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    lengths = range(MIN_LINES, MAX_LINES + 1)
    bases = {}
    for tier in TIERS:
        # The style id is sf:card_<tier>_<lines>, which the client resolves to the
        # pair tooltip/<id>_background and <id>_frame. The background carries the
        # whole panel; the frame stays empty because the shader emits background
        # and border in a single draw.
        src = ORIGINALS / f"card_{tier}_frame.png"
        art = Image.open(src).convert("RGBA")

        # Measured per tier rather than assumed, so art with a taller bottom cap
        # still gets a frame that clears it.
        _, bot = measure(art)
        base = TEXT_TOP + bot + CAP_GAP
        bases[tier] = base

        for n in list(lengths) + [COMPAT_LINES]:
            height = base + LINE_PITCH * n
            # The unsuffixed id is emitted once; the loop also covers it when
            # COMPAT_LINES falls inside the range.
            name = f"card_{tier}_{n}"
            if n == COMPAT_LINES:
                compat = f"card_{tier}"
                build(art, height).save(args.out / f"{compat}_background.png")
                (args.out / f"{compat}_background.png.mcmeta").write_text(
                    json.dumps(STRETCH_MCMETA, indent=4) + "\n")
                Image.new("RGBA", art.size, (0, 0, 0, 0)).save(
                    args.out / f"{compat}_frame.png")
                (args.out / f"{compat}_frame.png.mcmeta").write_text(
                    json.dumps(STRETCH_MCMETA, indent=4) + "\n")

            build(art, height).save(args.out / f"{name}_background.png")
            (args.out / f"{name}_background.png.mcmeta").write_text(
                json.dumps(STRETCH_MCMETA, indent=4) + "\n")

            # No marker: the vanilla path samples it, finds alpha 0 and discards,
            # so it costs nothing and needs no height.
            empty = Image.new("RGBA", art.size, (0, 0, 0, 0))
            empty.save(args.out / f"{name}_frame.png")
            (args.out / f"{name}_frame.png.mcmeta").write_text(
                json.dumps(STRETCH_MCMETA, indent=4) + "\n")

        print(f"  card_{tier}  art {art.size[0]}x{art.size[1]}  bottom cap {bot}  "
              f"base {base}  heights {base + LINE_PITCH * MIN_LINES}"
              f"..{base + LINE_PITCH * MAX_LINES}")

    # CardTooltipStyle carries one FRAME_BASE, so every tier has to agree or the
    # java side would pick a height that is wrong for the others.
    if len(set(bases.values())) != 1:
        raise SystemExit(f"tiers disagree on the frame base {bases}; "
                         "CardTooltipStyle.FRAME_BASE assumes they match")


if __name__ == "__main__":
    main()