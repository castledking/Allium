#!/usr/bin/env python3
"""Build the trading-card tooltip frame as font glyphs, with no core shader.

Replaces tools/generate_tooltip_sprites.py and the position_tex_color shader it
fed. Only bitmap and space font providers and the tooltip_style component are
used, all of which are stable formats, so nothing here needs touching when the
client's shader pipeline changes between versions.

How the frame is drawn

  Every lore line starts with  IN + <frame glyph> + OUT  in the font
  sf:card_frame. IN steps left to the edge of the tooltip box, the glyph draws a
  full-width slice of the frame, and OUT steps back to where the text starts.
  The frame never moves the text, and the right border is part of the bitmap,
  so no line needs padding to a width.

  The client draws a tooltip's glyphs in the order it was given them, line by
  line, so a line's text always lands on top of its own frame slice and a later
  line's slice lands on top of anything above it. Nothing overlaps the text
  except the art this generator lays out to clear it.

Vertical layout, with y the top of the tooltip's first line (the item name):

  y - 3        top of the box vanilla reserves for the tooltip. Nothing may be
               drawn above it: the client positions the tooltip from its line
               count alone, so art above the box is what gets cut off at the top
               of the screen.
  y            the item name, blanked by the pack's lang file.
  y + 12       lore 0, the HEADER line. Its glyph is the art's top cap, ornament
               included, and holds no text. It reaches up through the blank name.
  y + 22       lore 1, the title, then one BODY glyph per line.
  ...          the BOTTOM line holds the bottom cap; one blank line follows it so
               the cap sits inside the box and F3+H lines start below it.

  The HEADER line is what makes room for the ornament. The name slot alone
  leaves 15px between the box top and the first lore line, and the top cap is 19
  rows before the title even starts, so with the title as lore 0 the ornament
  either overlaps the name or rises out of the box.

Glyph chars: 0xE100 + tierIndex*0x10 + {0 header, 1 body, 2 bottom}
Spaces:      \\uE000 = IN,  \\uE001 = OUT

CardFrame.java hardcodes the font key, the chars, and BOTTOM_EXTRA_LINES; this
script fails rather than write assets that disagree with it.

Usage:
  python3 tools/generate_card_glyphs.py --out <pack root>               # real art
  python3 tools/generate_card_glyphs.py --out <pack root> --calibrate   # seam test
  python3 tools/generate_card_glyphs.py --out <pack root> --width 230   # wider cards
"""

import argparse
import json
import math
import pathlib
from collections import Counter

from PIL import Image

TIERS = ("simple", "elite", "ultimate", "legendary", "fabled")  # order == Tier.ordinal()
HERE = pathlib.Path(__file__).resolve().parent.parent
ORIGINALS = HERE / "src/main/resources/tradingcards/tooltip-originals"

NS = "sf"
FONT_NAME = "card_frame"          # assets/sf/font/card_frame.json  ->  sf:card_frame
TEX_SUBDIR = "font/card_frame"    # assets/sf/textures/font/card_frame/
STYLE_ID = "card"                 # tooltip_style sf:card -> tooltip/card_background
NAME_KEY = "allium.tradingcard.name"

# The client's tooltip metrics. Read off GuiGraphicsExtractor.tooltip and
# TooltipRenderUtil in the 26.3 client; unchanged since 1.21.2.
LINE = 10          # ClientTextTooltip.getHeight
NAME_GAP = 2       # extra space after the first line of an item tooltip
PAD = 3            # box padding around the text block
ASCENT_BASE = 7    # a bitmap glyph's top row lands at line top + 7 - ascent

# The layout. These are the knobs; everything else is derived.
TEXT_X = 12        # art column the text starts at, counted from the frame's left edge
TITLE_ROW = 20     # art row the title's line starts on (FishOnMC's frame uses 20)
BOTTOM_LEAD = 1    # plain rows between the last text line's slot and the bottom cap

# Mirrored in CardFrame.java. Checked below, not just documented.
BOTTOM_EXTRA_LINES = 1

CAP_X = 9          # side border + corner flourish columns kept 1:1 when widening
CENTER = 30        # centre ornament columns kept 1:1 when widening

SPACE_IN, SPACE_OUT = "\uE000", "\uE001"
HEADER, BODY, BOTTOM = 0, 1, 2


def glyph_char(tier_idx: int, kind: int) -> str:
    return chr(0xE100 + tier_idx * 0x10 + kind)


def measure(art: Image.Image) -> tuple[int, int, int]:
    """Top cap rows, bottom cap rows, and the index of a plain panel row.

    The plain row is the most common row in the art, and the plain band is the
    longest run of it, so double rules with plain rows between them stay inside
    the caps rather than being mistaken for the middle.
    """
    w, h = art.size
    px = art.load()
    rows = [tuple(px[x, y] for x in range(w)) for y in range(h)]
    ref = Counter(rows).most_common(1)[0][0]
    best, start = (0, 0), None
    for y in range(h + 1):
        if y < h and rows[y] == ref:
            start = y if start is None else start
        elif start is not None:
            if y - start > best[1] - best[0]:
                best = (start, y)
            start = None
    first, end = best
    if end - first < 1:
        raise SystemExit("no plain panel row found")
    return first, h - end, first


def widen(art: Image.Image, width: int) -> Image.Image:
    """Horizontal 5-slice: caps and centre ornament 1:1, plain columns repeated."""
    w, h = art.size
    if width == w:
        return art.copy()
    if width < w:
        raise SystemExit(f"--width {width} is narrower than the art ({w}); only widening is supported")
    out = Image.new("RGBA", (width, h))
    src, dst = art.load(), out.load()
    scx, dcx = (w - CENTER) // 2, (width - CENTER) // 2
    for x in range(width):
        if x < CAP_X:
            sx = x
        elif x >= width - CAP_X:
            sx = w - (width - x)
        elif dcx <= x < dcx + CENTER:
            sx = scx + (x - dcx)
        elif x < dcx:
            sx = CAP_X
        else:
            sx = w - CAP_X - 1
        for y in range(h):
            dst[x, y] = src[sx, y]
    return out


def rows(img: Image.Image, y0: int, y1: int) -> Image.Image:
    return img.crop((0, y0, img.size[0], y1))


def repeat_row(img: Image.Image, y: int, n: int) -> Image.Image:
    out = Image.new("RGBA", (img.size[0], n))
    row = rows(img, y, y + 1)
    for i in range(n):
        out.paste(row, (0, i))
    return out


def calibration_tile(width: int) -> Image.Image:
    """Red top row, blue bottom row: at every line seam red must touch blue exactly."""
    im = Image.new("RGBA", (width, LINE), (40, 40, 40, 255))
    for x in range(width):
        im.putpixel((x, 0), (255, 0, 0, 255))
        im.putpixel((x, LINE - 1), (0, 80, 255, 255))
    return im


def opaque_width(img: Image.Image) -> int:
    """The width the client advances by: it trims transparent columns on the right."""
    bbox = img.getchannel("A").getbbox()
    return 0 if bbox is None else bbox[2]


def check_layout(top: int, bot: int) -> None:
    """Fail if the art no longer fits the layout CardFrame assumes."""
    # The header covers art rows [0, TITLE_ROW), so the whole top cap must be in it.
    if TITLE_ROW < top:
        raise SystemExit(f"TITLE_ROW {TITLE_ROW} cuts the top cap ({top} rows) short")
    # Art row 0 sits TITLE_ROW above the title's line, which is two lines and the
    # name gap below the name. It must not rise above the box.
    room = PAD + LINE + NAME_GAP + LINE
    if TITLE_ROW > room:
        raise SystemExit(f"TITLE_ROW {TITLE_ROW} puts the ornament {TITLE_ROW - room}px "
                         f"above the tooltip box, where it gets cut off")
    # The bottom cap starts on the BOTTOM line; the blank lines after it have to
    # cover the rest, or F3+H lines print on top of the cap.
    extra = math.ceil((BOTTOM_LEAD + bot) / LINE) - 1
    if extra != BOTTOM_EXTRA_LINES:
        raise SystemExit(f"the bottom cap needs {extra} blank line(s) after it; "
                         f"update BOTTOM_EXTRA_LINES here and in CardFrame.java")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=pathlib.Path, help="pack root (contains assets/)")
    ap.add_argument("--originals", type=pathlib.Path, default=ORIGINALS)
    ap.add_argument("--width", type=int, default=0, help="frame width; default = art width")
    ap.add_argument("--calibrate", action="store_true", help="body glyph becomes a seam test tile")
    args = ap.parse_args()

    tex_dir = args.out / "assets" / NS / "textures" / TEX_SUBDIR
    font_dir = args.out / "assets" / NS / "font"
    sprite_dir = args.out / "assets" / NS / "textures/gui/sprites/tooltip"
    lang_dir = args.out / "assets" / NS / "lang"
    for d in (tex_dir, font_dir, sprite_dir, lang_dir):
        d.mkdir(parents=True, exist_ok=True)

    providers = []
    width = None
    for i, tier in enumerate(TIERS):
        art = Image.open(args.originals / f"card_{tier}_frame.png").convert("RGBA")
        width = width or (args.width or art.size[0])
        art = widen(art, width)
        top, bot, plain = measure(art)
        check_layout(top, bot)
        h = art.size[1]

        header = rows(art, 0, TITLE_ROW)
        body = calibration_tile(width) if args.calibrate else repeat_row(art, plain, LINE)
        bottom = rows(art, h - bot - BOTTOM_LEAD, h)

        # Each glyph's ascent places its top row; see the module docstring for
        # where each one has to land.
        glyphs = {
            # Drawn on the HEADER line, ending exactly where the title's line starts.
            "header": (header, HEADER, ASCENT_BASE + TITLE_ROW - LINE),
            "body": (body, BODY, ASCENT_BASE),
            "bottom": (bottom, BOTTOM, ASCENT_BASE),
        }
        for kind, (img, k, ascent) in glyphs.items():
            if ascent > img.size[1]:
                # Both the client and Nexo's pack reader reject the whole font.
                raise SystemExit(f"{tier} {kind}: ascent {ascent} > height {img.size[1]}")
            if opaque_width(img) != width:
                # The client trims transparent columns off a glyph's advance,
                # which would shift every line's text by the difference.
                raise SystemExit(f"{tier} {kind}: rightmost column is transparent")
            img.save(tex_dir / f"{tier}_{kind}.png")
            providers.append({
                "type": "bitmap",
                "file": f"{NS}:{TEX_SUBDIR}/{tier}_{kind}.png",
                "height": img.size[1],
                "ascent": ascent,
                "chars": [glyph_char(i, k)],
            })
        print(f"  {tier:<10} header {width}x{header.size[1]}  body {width}x{LINE}  "
              f"bottom {width}x{bottom.size[1]}  caps top {top} bottom {bot}")

    # IN lands on the box's left edge, so a tooltip clamped against the left of
    # the screen keeps its border. OUT returns to the text column. A bitmap glyph
    # advances by its width plus one.
    advance_in = -PAD
    advance_out = TEXT_X - (width + 1)
    providers.append({"type": "space", "advances": {SPACE_IN: advance_in, SPACE_OUT: advance_out}})
    (font_dir / f"{FONT_NAME}.json").write_text(json.dumps({"providers": providers}, indent=2) + "\n")

    # tooltip_style sf:card: vanilla's panel is replaced by the glyphs, so both
    # sprites are empty. A missing sprite would draw the missing texture instead.
    for part in ("background", "frame"):
        Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(sprite_dir / f"{STYLE_ID}_{part}.png")

    # The item name is a translatable with the card title as its fallback. The
    # pack maps it to nothing, so the name line is blank with the pack and
    # readable without it. Merged, so other keys in the file survive.
    lang_file = lang_dir / "en_us.json"
    lang = json.loads(lang_file.read_text()) if lang_file.exists() else {}
    lang[NAME_KEY] = ""
    lang_file.write_text(json.dumps(lang, indent=2, ensure_ascii=False) + "\n")

    usable = width - 2 * TEXT_X
    print(f"  font {NS}:{FONT_NAME}  width {width}  spaces {advance_in} / {advance_out}  "
          f"text column {TEXT_X}..{width - TEXT_X} ({usable}px)"
          + ("  [CALIBRATION BODY]" if args.calibrate else ""))


if __name__ == "__main__":
    main()
