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

# Must match CardTooltipStyle.FRAME_BASE and .LINE_PITCH.
HEIGHT_BASE = 32
LINE_PITCH = 10
MIN_LINES = 15
MAX_LINES = 30
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
    ap.add_argument("--height", type=int,
                    help="fixed frame height; overrides the per-length variants")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    lengths = [None] if args.height else range(MIN_LINES, MAX_LINES + 1)
    for tier in TIERS:
        # The style id is sf:card_<tier>_<lines>, which the client resolves to the
        # pair tooltip/<id>_background and <id>_frame. The background carries the
        # whole panel; the frame stays empty because the shader emits background
        # and border in a single draw.
        src = ORIGINALS / f"card_{tier}_frame.png"
        art = Image.open(src).convert("RGBA")

        for n in lengths:
            height = args.height if n is None else HEIGHT_BASE + LINE_PITCH * n
            name = f"card_{tier}" if n is None else f"card_{tier}_{n}"

            build(art, height).save(args.out / f"{name}_background.png")
            (args.out / f"{name}_background.png.mcmeta").write_text(
                json.dumps(STRETCH_MCMETA, indent=4) + "\n")

            # No marker: the vanilla path samples it, finds alpha 0 and discards,
            # so it costs nothing and needs no height.
            empty = Image.new("RGBA", art.size, (0, 0, 0, 0))
            empty.save(args.out / f"{name}_frame.png")
            (args.out / f"{name}_frame.png.mcmeta").write_text(
                json.dumps(STRETCH_MCMETA, indent=4) + "\n")

        span = f"{args.height}" if args.height else \
            f"{HEIGHT_BASE + LINE_PITCH * MIN_LINES}..{HEIGHT_BASE + LINE_PITCH * MAX_LINES}"
        print(f"  card_{tier}  art {art.size[0]}x{art.size[1]}  frame heights {span}")


if __name__ == "__main__":
    main()