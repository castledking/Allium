#!/usr/bin/env python3
"""Build the trading-card tooltip sprites consumed by the position_tex_color shader.

The shader draws these 1:1 from the tooltip quad's top-left and stops at the
height encoded in the sprite, so the panel does not grow over the F3+H lines.

Sprite layout (row 0 is metadata and is never drawn):

    (0,0) marker  rgba(255,0,255,255)  identifies the quad as a trading card
    (1,0) height  R + 256*G             frame height in GUI pixels
    (2,0) caps    R = top, G = bottom   cap heights, measured from the artwork
    rows 1..n     the artwork, unchanged

Usage:
    python3 tools/generate_tooltip_sprites.py --out <dir> --height 235
"""

import argparse
import json
import pathlib
from collections import Counter

from PIL import Image

TIERS = ("simple", "elite", "ultimate", "legendary", "fabled")
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
    ap.add_argument("--height", required=True, type=int)
    ap.add_argument("--suffix", default="",
                    help="appended to the sprite name, e.g. '_21' for a lore length")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    for tier in TIERS:
        # The style id is sf:card_<tier>, which the client resolves to the pair
        # tooltip/card_<tier>_background and tooltip/card_<tier>_frame. The
        # background carries the whole panel now; the frame stays empty because
        # the shader emits background and border in a single draw.
        src = ORIGINALS / f"card_{tier}_frame.png"
        art = Image.open(src).convert("RGBA")
        name = f"card_{tier}{args.suffix}"

        build(art, args.height).save(args.out / f"{name}_background.png")
        (args.out / f"{name}_background.png.mcmeta").write_text(
            json.dumps(STRETCH_MCMETA, indent=4) + "\n")

        # No marker: the vanilla path samples it, finds alpha 0 and discards, so
        # it costs nothing and needs no height.
        empty = Image.new("RGBA", art.size, (0, 0, 0, 0))
        empty.save(args.out / f"{name}_frame.png")
        (args.out / f"{name}_frame.png.mcmeta").write_text(
            json.dumps(STRETCH_MCMETA, indent=4) + "\n")

        print(f"  {name}  art {art.size[0]}x{art.size[1]}  frame height {args.height}")


if __name__ == "__main__":
    main()