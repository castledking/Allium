#!/usr/bin/env python3
"""Derive a purple set from the epic set by rotating hue.

The five tiers each get one of the coloured sets in
~/Documents/tradingcards/lore_backgrounds. ULTIMATE needed a purple and none of
the sets was purple, so the epic (green) set is recoloured rather than a new one
drawn from scratch.

Hue is rotated and saturation/value left alone, which preserves the shading
ramp exactly: the epic set has a main accent, a lighter and a darker shade of it,
and hand-picking three purples would have let the relationships drift. Rotate hue
on every pixel except the panel colour and the transparent ones, so antialiased
edges between the two come out as blends of purple and panel rather than as
fringes.

Usage:
    python3 tools/recolour_epic_to_purple.py [--hue 280]
"""

import argparse
import colorsys
import pathlib

from PIL import Image

# Shared by every set. Rotating it would tint the whole panel, and it is the one
# colour in the art that must survive untouched.
PANEL = (22, 30, 25)
HUE_SHIFT_TARGET = 280.0


def recolour(png: pathlib.Path, out: pathlib.Path, target_hue: float) -> None:
    im = Image.open(png).convert("RGBA")
    px = im.load()
    shifted = 0
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            r, g, b, a = px[x, y]
            if a == 0 or (r, g, b) == PANEL:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            h = (target_hue / 360.0) % 1.0
            nr, ng, nb = colorsys.hsv_to_rgb(h, s, v)
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
            shifted += 1
    im.save(out)
    print(f"  {png.name:<22} -> {out.name:<22} {shifted} px recoloured")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", type=pathlib.Path,
                    default=pathlib.Path.home() / "Documents/tradingcards/lore_backgrounds")
    ap.add_argument("--out", type=pathlib.Path,
                    default=pathlib.Path.home() / "Documents/tradingcards/lore_backgrounds")
    ap.add_argument("--hue", type=float, default=HUE_SHIFT_TARGET,
                    help="target hue in degrees; 280 is a violet-leaning purple")
    ap.add_argument("--name", default="purple")
    args = ap.parse_args()

    for part in ("top", "middle", "bottom"):
        src = args.src / f"epic_{part}.png"
        if not src.exists():
            raise SystemExit(f"missing {src}")
        recolour(src, args.out / f"{args.name}_{part}.png", args.hue)


if __name__ == "__main__":
    main()