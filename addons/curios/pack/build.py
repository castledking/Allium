"""Build the Curios resource pack from the ported source artwork and bundled item definitions."""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

import yaml
from PIL import Image

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets"
FRAME_GLYPHS = ROOT.parent / "src/main/resources/frame-glyphs.json"
PAPER = {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/paper"}}
ICONS = {
    "charm": "slot/charm", "head": "slot/head", "chest": "slot/chest", "belt": "slot/belt",
    "ring": "slot/ring", "card": "uis/inventory/card", "prev": "relique/item/prev",
    "next": "relique/item/next", "up": "relique/item/up", "down": "relique/item/down",
    "card_menu": "uis/inventory/card", "card_locked": "uis/inventory/locked",
    "card_prev": "uis/inventory/arrow_left_red", "card_next": "uis/inventory/arrow_right_green",
    "card_back": "uis/inventory/back_red", "blank": "uis/inventory/blank",
}


TIERS = ("simple", "elite", "ultimate", "legendary", "fabled")
PARTS = ("header", "body", "bottom")
FILLS = tuple(1 << k for k in range(8))  # fill strips 1..128 px, combined to any width
PANEL_FONT = "curios:card_panel"
PANEL_BASE = 0xE200      # tier * 48 + part * 16 + piece: 0 left, 1 centre, 2 right, 3+ fills
NEGATIVE_BASE = 0xE301   # advances -1, -2, -4 ... -512
PANEL_IN = 0xE310        # advance -3: back from the text column to the tooltip's left edge


def slice_panel(image):
    """Cuts a panel strip into left cap, optional centre ornament, right cap and its fill column.

    The fill column is the one repeated longest; every column equal to it can be repeated or
    dropped, so the panel can be drawn at any width with its caps and ornament intact.
    """
    w = image.width
    cols = [tuple(image.getpixel((x, y)) for y in range(image.height)) for x in range(w)]
    best, start = (0, 0), 0
    for x in range(1, w + 1):
        if x == w or cols[x] != cols[start]:
            best = max(best, (x - start, start))
            start = x
    fill = cols[best[1]]
    # The caps end where the first and last runs of fill columns begin; a lone column that happens
    # to match the fill (dark background outside a 1px border) stays part of its cap.
    runs, x = [], 0
    while x < w:
        if cols[x] == fill:
            end = x
            while end + 1 < w and cols[end + 1] == fill:
                end += 1
            if end - x + 1 >= 4:
                runs.append((x, end))
            x = end + 1
        else:
            x += 1
    first, last = runs[0][0], runs[-1][1]
    inner = [x for x in range(first, last + 1) if cols[x] != fill]
    centre = (inner[0], inner[-1] + 1) if inner else None
    return first, last, centre


def glyph(image, x0, x1):
    piece = image.crop((x0, 0, x1, image.height))
    # The client advances a glyph by its rightmost visible column; an alpha-1 pixel keeps every
    # piece its full width.
    if all(piece.getpixel((piece.width - 1, y))[3] == 0 for y in range(piece.height)):
        piece.putpixel((piece.width - 1, 0), (0, 0, 0, 1))
    return piece


def card_panels(archive, manifest_out):
    """Writes the resizable tier panels into the pack and their layout for the plugin."""
    frame_font = json.loads((ASSETS / "curios/font/card_frame.json").read_text())
    metrics = {p["file"]: (p["height"], p["ascent"]) for p in frame_font["providers"] if p["type"] == "bitmap"}
    providers, tiers = [], {}
    for t, tier in enumerate(TIERS):
        tiers[tier] = {}
        for q, part in enumerate(PARTS):
            source = f"curios:font/card_frame/{tier}_{part}.png"
            height, ascent = metrics[source]
            image = Image.open(ASSETS / "curios/textures" / source.split(":", 1)[1]).convert("RGBA")
            first, last, centre = slice_panel(image)
            base = PANEL_BASE + t * 48 + q * 16
            pieces = {"left": (0, first), "right": (last + 1, image.width)}
            if centre:
                pieces["centre"] = centre
            layout = {}
            for index, name in enumerate(("left", "centre", "right")):
                if name not in pieces or pieces[name][0] >= pieces[name][1]:
                    continue
                x0, x1 = pieces[name]
                layout[name] = [chr(base + index), x1 - x0]
                providers.append((f"{tier}_{part}_{name}", glyph(image, x0, x1), height, ascent, chr(base + index)))
            if centre:
                # The art sits its ornament a pixel off centre; keep that at every width.
                left_fill, right_fill = centre[0] - first, last - centre[1] + 1
                layout["bias"] = left_fill - (left_fill + right_fill) // 2
            layout["fills"] = {}
            for k, size in enumerate(FILLS):
                strip = Image.new("RGBA", (size, image.height))
                for x in range(size):
                    strip.paste(image.crop((first, 0, first + 1, image.height)), (x, 0))
                providers.append((f"{tier}_{part}_fill{size}", glyph(strip, 0, size), height, ascent, chr(base + 3 + k)))
                layout["fills"][str(size)] = chr(base + 3 + k)
            tiers[tier][part] = layout
    font = {"providers": []}
    for name, image, height, ascent, char in providers:
        data = io.BytesIO()
        image.save(data, "PNG")
        archive.writestr(f"assets/curios/textures/font/card_panel/{name}.png", data.getvalue())
        font["providers"].append({"type": "bitmap", "file": f"curios:font/card_panel/{name}.png",
                                  "height": height, "ascent": ascent, "chars": [char]})
    negatives = {chr(NEGATIVE_BASE + k): -(1 << k) for k in range(10)}
    font["providers"].append({"type": "space", "advances": {**negatives, chr(PANEL_IN): -3}})
    archive.writestr("assets/curios/font/card_panel.json", json.dumps(font, ensure_ascii=False, separators=(",", ":")))
    manifest = {"font": PANEL_FONT, "in": chr(PANEL_IN),
                "negative": {str(-advance): char for char, advance in negatives.items()}, "tiers": tiers}
    if manifest_out:
        manifest_out.parent.mkdir(parents=True, exist_ok=True)
        manifest_out.write_text(json.dumps(manifest, ensure_ascii=True, separators=(",", ":")))


def check_frame_glyphs(config):
    """Every non-tier frame needs a lore panel in frame-glyphs.json drawn from font/frames.json."""
    manifest = json.loads(FRAME_GLYPHS.read_text())
    font = json.loads((ASSETS / "curios/font/frames.json").read_text())
    if manifest["font"] != "curios:frames":
        raise ValueError(f"frame-glyphs.json: unexpected font {manifest['font']}")
    known = set()
    for provider in font["providers"]:
        known.update(provider.get("advances", {}))
        for row in provider.get("chars", []):
            known.update(row)
    for style in config["frames"]:
        if style in TIERS:
            continue
        frame = manifest["frames"].get(style)
        if frame is None:
            raise ValueError(f"frame-glyphs.json: no lore panel for frame {style}")
        item = frame["item"]
        used = set(manifest["in"] + frame["out"])
        for key in ("header", "top", "body", "bottom", "final"):
            used.update(item[key])
        for cut in item["fixed"].values():
            used.update(cut["header"] + cut["lines"] + cut["final"])
        missing = sorted(f"U+{ord(c):04X}" for c in used - known)
        if missing:
            raise ValueError(f"frame-glyphs.json: {style} uses glyphs missing from font/frames.json: {missing}")


def make(config, output, manifest_out=None):
    routes = [(definition["model"], name, definition["texture"]) for name, definition in config["items"].items()]
    routes += [(2679900 + index, "icon_" + name, texture) for index, (name, texture) in enumerate(ICONS.items())]
    routes.sort()
    if len({route[0] for route in routes}) != len(routes):
        raise ValueError("Duplicate custom model data")

    # The reset after each run of Curios values prevents its last model taking over unrelated items.
    selectors = {}
    for number, name, texture in routes:
        if not (ASSETS / "curios/textures" / (texture + ".png")).is_file():
            raise ValueError(f"{name}: missing texture {texture}")
        selectors[number] = f"curios:item/{name}"
        selectors.setdefault(number + 1, "curios:item/plain_paper")

    for style in config["frames"]:
        for part in ("background", "frame"):
            path = ASSETS / f"curios/textures/gui/sprites/tooltip/{style}_{part}.png"
            if not path.is_file():
                raise ValueError(f"Missing tooltip sprite: {path.name}")
    check_frame_glyphs(config)

    for font in (ASSETS / "curios/font").glob("*.json"):
        for provider in json.loads(font.read_text())["providers"]:
            if provider["type"] != "bitmap":
                continue
            namespace, texture = provider["file"].split(":", 1)
            if not (ASSETS / namespace / "textures" / texture).is_file():
                raise ValueError(f"{font.name}: missing {provider['file']}")

    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        def write(path, value):
            archive.writestr(path, json.dumps(value, ensure_ascii=False, separators=(",", ":")))

        card_panels(archive, manifest_out)
        write("pack.mcmeta", {"pack": {"pack_format": 15, "supported_formats": [15, 97],
              "min_format": 15, "max_format": [97, 1], "description": "Allium Curios"}})
        for path in sorted(ASSETS.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(ROOT))
        for texture in sorted({route[2] for route in routes}):
            for extension in (".png", ".png.mcmeta"):
                source = ASSETS / "curios/textures" / (texture + extension)
                if source.is_file():
                    archive.write(source, f"assets/curios/textures/item/{texture}{extension}")
        for _, name, texture in routes:
            write(f"assets/curios/models/item/{name}.json", {"parent": "minecraft:item/generated",
                  "textures": {"layer0": f"curios:item/{texture}"}})
        write("assets/curios/models/item/plain_paper.json", PAPER)
        write("assets/minecraft/models/item/paper.json", {**PAPER, "overrides": [
            {"predicate": {"custom_model_data": number}, "model": model} for number, model in sorted(selectors.items())]})
        write("assets/minecraft/items/paper.json", {"model": {
            "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
            "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
            "entries": [{"threshold": number, "model": {"type": "minecraft:model", "model": model}}
                        for number, model in sorted(selectors.items())]}})


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("output", type=Path)
    cli.add_argument("--definitions", type=Path, default=ROOT.parent / "src/main/resources/config.yml")
    cli.add_argument("--panel-layout", type=Path, help="where to write card-panels.json for the plugin jar")
    args = cli.parse_args()
    make(yaml.safe_load(args.definitions.read_text()), args.output, args.panel_layout)
