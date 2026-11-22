#!/usr/bin/env python3
"""Build the SpawnerCraft resource pack from the SF changer model and texture."""
import argparse
import copy
import json
import math
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

import yaml


ROOT = Path(__file__).resolve().parent
MODEL = "spawnercraft:item/spawner_changer"
PLAIN = "spawnercraft:item/plain_blaze_rod"
CARRIER = "minecraft:item/blaze_rod"
MODEL_FILE = "assets/spawnercraft/models/item/spawner_changer.json"
OVERLAY = "spawnercraft_model_1_21_11"
# Normal, U direction, and V direction for each face of an unrotated Minecraft element.
FACES = {
    "down": ((0, -1, 0), (1, 0, 0), (0, 0, -1)),
    "up": ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    "north": ((0, 0, -1), (-1, 0, 0), (0, -1, 0)),
    "south": ((0, 0, 1), (1, 0, 0), (0, -1, 0)),
    "west": ((-1, 0, 0), (0, 0, 1), (0, -1, 0)),
    "east": ((1, 0, 0), (0, 0, -1), (0, -1, 0)),
}
FACE_NAMES = {normal: name for name, (normal, _, _) in FACES.items()}


def transpose(matrix):
    return tuple(zip(*matrix))


def transform(matrix, vector):
    return tuple(sum(a * b for a, b in zip(row, vector)) for row in matrix)


def multiply(left, right):
    return transpose(transform(left, column) for column in transpose(right))


def rotation(axis, degrees):
    sin, cos = math.sin(math.radians(degrees)), math.cos(math.radians(degrees))
    return {
        "x": ((1, 0, 0), (0, cos, -sin), (0, sin, cos)),
        "y": ((cos, 0, sin), (0, 1, 0), (-sin, 0, cos)),
        "z": ((cos, -sin, 0), (sin, cos, 0), (0, 0, 1)),
    }[axis]


def rebase_element(element):
    rot = element["rotation"]
    if "axis" in rot and rot["angle"] in (-45, -22.5, 0, 22.5, 45):
        return
    axes = {rot["axis"]: rot["angle"]} if "axis" in rot else rot
    authored = multiply(rotation("z", axes.get("z", 0)), multiply(
        rotation("y", axes.get("y", 0)), rotation("x", axes.get("x", 0))))
    # Older clients can apply only one small rotation per element. Bake the right-angle part
    # into the box and face layout, and keep the residual as the element's legacy rotation.
    for axis, angle in [(a, b) for b in (0, -45, 45, -22.5, 22.5) for a in "xyz"]:
        basis = multiply(rotation(axis, -angle), authored)
        snapped = tuple(tuple(round(n) for n in row) for row in basis)
        if any(sum(abs(n) for n in row) != 1 for row in (*snapped, *transpose(snapped))):
            continue
        # The SF model also uses -92.5°. Its closest legacy orientation is -90°.
        if max(abs(a - b) for row, near in zip(basis, snapped) for a, b in zip(row, near)) <= 0.045:
            break
    else:
        raise ValueError(f"Unsupported legacy spawner changer rotation: {rot}")

    origin = rot["origin"]

    def corner(point):
        offset = transform(snapped, tuple(p - o for p, o in zip(point, origin)))
        return tuple(round(o + d, 6) for o, d in zip(origin, offset))

    bounds = list(zip(corner(element["from"]), corner(element["to"])))
    element["from"], element["to"] = [min(pair) for pair in bounds], [max(pair) for pair in bounds]
    if any(n < -16 or n > 32 for point in (element["from"], element["to"]) for n in point):
        raise ValueError("Legacy spawner changer element exceeds Minecraft's box bounds")
    element["rotation"] = {"origin": origin, "axis": axis, "angle": angle}
    faces = {}
    for name, face in element["faces"].items():
        normal, u, _ = FACES[name]
        new_name = FACE_NAMES[transform(snapped, normal)]
        _, new_u, new_v = FACES[new_name]
        directions = (new_u, new_v, tuple(-n for n in new_u), tuple(-n for n in new_v))
        turn = directions.index(transform(snapped, u)) * 90
        if turn:
            face["rotation"] = (face.get("rotation", 0) + turn) % 360
        faces[new_name] = face
    element["faces"] = faces


def model_ref(model):
    return {"type": "minecraft:model", "model": model}


def make(output, sf):
    model_data = yaml.safe_load((ROOT.parent / "src/main/resources/spawner_changer.yml").read_text())["item"]["custom-model-data"]
    if sf is not None:
        assets = sf / "Nexo/pack/assets/sf"
        modern = json.loads((assets / "models/items/spawner_changer.json").read_text())
        modern["textures"] = {key: MODEL for key in modern["textures"]}
        texture = (assets / "textures/items/spawner_changer.png").read_bytes()
        legacy = copy.deepcopy(modern)
        legacy.pop("format_version", None)
        legacy["display"].pop("on_shelf", None)
        for element in legacy["elements"]:
            rebase_element(element)
    carrier = {"parent": "minecraft:item/handheld", "textures": {"layer0": CARRIER}}
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w") as archive:
        def write(path, data):
            entry = ZipInfo(path, date_time=(2000, 1, 1, 0, 0, 0))
            if not isinstance(data, bytes):
                data = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            archive.writestr(entry, data, compress_type=ZIP_DEFLATED)

        metadata = {"pack": {"description": "§bSpawnercraft Resources §7v1.0", "pack_format": 15,
                             "supported_formats": [15, 97], "min_format": 15, "max_format": [97, 1]}}
        if sf is not None:
            metadata["overlays"] = {"entries": [{"directory": OVERLAY, "formats": [75, 97],
                                                  "min_format": 75, "max_format": [97, 1]}]}
        write("pack.mcmeta", metadata)
        write("pack.png", (ROOT / "pack.png").read_bytes())
        if sf is None:
            return
        write("assets/spawnercraft/textures/item/spawner_changer.png", texture)
        write(MODEL_FILE, legacy)
        write(f"{OVERLAY}/{MODEL_FILE}", modern)
        write("assets/spawnercraft/models/item/plain_blaze_rod.json", carrier)
        write("assets/minecraft/models/item/blaze_rod.json", {
            **carrier,
            "overrides": [{"predicate": {"custom_model_data": value}, "model": model}
                          for value, model in ((model_data, MODEL), (model_data + 1, PLAIN))],
        })
        write("assets/minecraft/items/blaze_rod.json", {"model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "index": 0,
            "fallback": model_ref(CARRIER),
            "entries": [{"threshold": value, "model": model_ref(model)}
                        for value, model in ((model_data, MODEL), (model_data + 1, CARRIER))],
        }})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sf", type=Path, help="SF plugins directory containing Nexo")
    args = parser.parse_args()
    make(args.output, args.sf)
