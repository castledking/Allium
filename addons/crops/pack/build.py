#!/usr/bin/env python3
"""Build the Crops pack from its bundled catalogue and SF model assets."""
import json
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

import yaml

ROOT = Path(__file__).resolve().parent
ITEMS = yaml.safe_load((ROOT.parent / "src/main/resources/items.yml").read_text())["items"]
CROPS = yaml.safe_load((ROOT.parent / "src/main/resources/crops.yml").read_text())["crops"]
PAPER = {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/paper"}}
JADE_SEED_CMD_OFFSET = 1_000_000


def model(name):
    return {"type": "minecraft:model", "model": name}


def stage_seed_models():
    mapping = {}
    for crop in CROPS.values():
        seed_id = crop["seed"]["item"].split(":")[-1]
        if seed_id not in ITEMS:
            raise ValueError(f"Missing seed item {seed_id} for crop {crop.get('id')}")
        seed_model = ITEMS[seed_id]["model"]
        for path in crop["paths"]["entries"].values():
            for stage in path["stages"]:
                stage_id = stage["model"].split(":")[-1]
                mapping[stage_id] = seed_model
    return mapping


def kitchen_icons():
    """Kitchen's placed models -> the item each one stands for, so GUIs (Jade) show that item's icon."""
    kitchen = yaml.safe_load((ROOT.parent / "src/main/resources/kitchen.yml").read_text())
    pies = kitchen.get("pies") or {}
    models = pies.get("models") or {}
    bare = lambda ref: ref.split(":", 1)[-1]
    slices = ("", "_slice1", "_slice2", "_slice3")
    icons = {}
    crust = pies.get("crust") or {}
    if crust.get("model") and crust.get("item"):
        icons[bare(crust["model"])] = bare(crust["item"])
    for type_id, row in (pies.get("types") or {}).items():
        row = row or {}
        named = lambda key, state: row.get(key) or models.get("item", "").replace("{state}", state).replace("{type}", type_id)
        cold, baked = bare(named("cold-item", "cold")), bare(named("baked-item", "baked"))
        if row.get("crust-model") and row.get("crust-item"):
            icons[bare(row["crust-model"])] = bare(row["crust-item"])
        filled = row.get("filled-model") or models.get("filled", "").replace("{type}", type_id)
        for ref in [filled, *(row.get("progress-models") or [])]:
            icons[bare(ref)] = cold
        for state, item in (("cold", cold), ("baked", baked)):
            placed = row.get(state + "-models") or [
                models.get("placed", "").replace("{state}", state).replace("{type}", type_id).replace("{slice}", s)
                for s in slices]
            for ref in placed:
                icons[bare(ref)] = item
    return {model: item for model, item in icons.items() if model in ITEMS and item in ITEMS}


def make(output):
    files = {str(p.relative_to(ROOT)): p.read_bytes() for p in (ROOT / "assets").rglob("*") if p.is_file()}
    files.update({
        "pack.mcmeta": {"pack": {"description": "§aCrops Resources §7v1.0", "pack_format": 15,
                                 "supported_formats": [15, 97], "min_format": 15, "max_format": [97, 1]}},
        "assets/crops/models/item/plain_paper.json": PAPER,
        # The pack's icon: the Crops addon art from CASTLEDCODEX (castled.codes/assets/allium-crops-addon.png).
        "pack.png": (ROOT / "pack.png").read_bytes(),
    })
    seed_models = stage_seed_models()
    icons = kitchen_icons()
    routes = []
    for item_id, item in ITEMS.items():
        cmd, ref = item["cmd"], item["model"]
        gui = item.get("gui") or (ITEMS[icons[item_id]]["model"] if item_id in icons else None)
        routes.append((cmd, ref, gui))
        if item_id in seed_models:
            # Used by default when crop-model-in-jade is false: renders the crop's 3D stage model in-world
            # and its seed item in Jade's GUI overlay (matching vanilla crops in Jade).
            routes.append((cmd + JADE_SEED_CMD_OFFSET, ref, seed_models[item_id]))
    routes.sort(key=lambda entry: entry[0])
    used_cmds = set()
    for cmd, _, _ in routes:
        if cmd in used_cmds or (cmd + 1) in used_cmds or (cmd - 1) in used_cmds:
            raise ValueError(f"Colliding custom_model_data {cmd}")
        used_cmds.add(cmd)

    overrides, entries = [], []
    for cmd, ref, gui in routes:
        for route in (ref, gui or ref):
            ns, path = route.split(":", 1)
            if f"assets/{ns}/models/{path}.json" not in files:
                raise ValueError(f"Missing crop model {route}")
        overrides.extend({"predicate": {"custom_model_data": value}, "model": target}
                         for value, target in ((cmd, ref), (cmd + 1, "crops:item/plain_paper")))
        modern = model(ref)
        if gui:
            modern = {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": "gui", "model": model(gui)}], "fallback": modern}
        entries.extend([{"threshold": cmd, "model": modern},
                        {"threshold": cmd + 1, "model": model("minecraft:item/paper")}])
    files["assets/minecraft/models/item/paper.json"] = {**PAPER, "overrides": overrides}
    files["assets/minecraft/items/paper.json"] = {
        "model": {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
                  "entries": entries, "fallback": model("minecraft:item/paper")}}
    validate_single_atlas(files)
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w") as zip_file:
        for name, value in sorted(files.items()):
            if not isinstance(value, bytes):
                value = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
            zip_file.writestr(ZipInfo(name, (2000, 1, 1, 0, 0, 0)), value, compress_type=ZIP_DEFLATED)


def validate_single_atlas(files):
    """Minecraft 1.21.11+ rejects any single item model that references both blocks and items atlases."""
    parsed = {}
    for name, value in files.items():
        if "/models/" in name and name.endswith(".json"):
            parsed[name] = value if isinstance(value, dict) else json.loads(value)

    def resolve(path, seen=None):
        seen = set() if seen is None else seen
        if path in seen or path not in parsed:
            return {}
        seen.add(path)
        data = parsed[path]
        merged = {}
        parent = data.get("parent")
        if isinstance(parent, str) and ":" in parent and not parent.startswith(("minecraft:", "builtin/")):
            ns, rel = parent.split(":", 1)
            merged.update(resolve(f"assets/{ns}/models/{rel}.json", seen))
        merged.update(data.get("textures") or {})
        return merged

    for name in sorted(parsed):
        atlases = set()
        for tex in resolve(name).values():
            if not isinstance(tex, str) or tex.startswith("#"):
                continue
            rel = tex.split(":", 1)[1] if ":" in tex else tex
            if rel.startswith("block/"):
                atlases.add("blocks")
            elif rel.startswith("item/"):
                atlases.add("items")
        if len(atlases) > 1:
            raise ValueError(f"Mixed block/item atlases in model {name}: {sorted(atlases)}")


if __name__ == "__main__":
    make(Path(sys.argv[1]))
