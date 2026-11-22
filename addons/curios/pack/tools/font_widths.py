"""Write src/main/resources/font-widths.json: the advance, in pixels, of every character the vanilla
client's default font draws from a bitmap, plus the Curios tier labels.

Curios measures tooltip lines with it to size frame panels. Only the numbers are stored, never
Mojang's textures. Rerun against a newer client jar if the default font changes:

    python3 pack/tools/font_widths.py ~/.minecraft/versions/<version>/<version>.jar
"""
import argparse
import io
import json
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
LABELS = ROOT / "pack/assets/curios/textures/labels"
BADGES = {"": "simple", "": "elite", "": "ultimate", "": "legendary", "": "fabled"}


def actual_width(image, x0, y0, w, h):
    for x in range(w - 1, -1, -1):
        if any(image.getpixel((x0 + x, y0 + y))[3] > 0 for y in range(h)):
            return x + 1
    return 0


def bitmap_advances(jar, provider):
    namespace, path = provider["file"].split(":", 1)
    image = Image.open(io.BytesIO(jar.read(f"assets/{namespace}/textures/{path}"))).convert("RGBA")
    rows = provider["chars"]
    cell_w, cell_h = image.width // len(rows[0]), image.height // len(rows)
    scale = provider.get("height", 8) / cell_h
    out = {}
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == "\u0000":
                continue
            # Vanilla: advance = round(actual width * scale) + 1; an empty cell is skipped.
            width = actual_width(image, c * cell_w, r * cell_h, cell_w, cell_h)
            out.setdefault(ch, int(0.5 + width * scale) + 1)
    return out


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("client_jar", type=Path)
    args = cli.parse_args()
    widths = {}
    with zipfile.ZipFile(args.client_jar) as jar:
        for name in ("assets/minecraft/font/include/space.json", "assets/minecraft/font/include/default.json"):
            for provider in json.loads(jar.read(name))["providers"]:
                if provider["type"] == "space":
                    for ch, advance in provider["advances"].items():
                        widths.setdefault(ch, advance)
                elif provider["type"] == "bitmap":
                    for ch, advance in bitmap_advances(jar, provider).items():
                        widths.setdefault(ch, advance)
    for ch, name in BADGES.items():
        widths[ch] = Image.open(LABELS / f"{name}.png").width + 1
    target = ROOT / "src/main/resources/font-widths.json"
    target.write_text(json.dumps(dict(sorted(widths.items())), ensure_ascii=True, separators=(",", ":")) + "\n")
    print(f"{len(widths)} widths -> {target}")


if __name__ == "__main__":
    main()
