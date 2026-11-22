#!/usr/bin/env python3
"""Import the configured SF Harvest and Kitchen definitions and their pack assets."""
import argparse
import json
import re
from pathlib import Path

import yaml


MODULE = Path(__file__).resolve().parent.parent
RESOURCES = MODULE / "src/main/resources"
PACK = MODULE / "pack/assets/crops"
CUSTOM = {"ce", "craftengine", "nexo", "sf"}


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def write_yaml(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=True, width=110), encoding="utf-8")


def clean(data):
    if isinstance(data, dict):
        return {k: clean(v) for k, v in data.items()}
    if isinstance(data, list):
        return [clean(v) for v in data]
    if isinstance(data, str) and ":" in data and data.split(":")[0] in CUSTOM:
        return "allium:" + data.split(":")[-1]
    return data


def strip_colors(text):
    if not isinstance(text, str):
        return text
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[&§](?:x(?:[&§][0-9a-fA-F]){6}|#[0-9a-fA-F]{6}|[0-9a-fA-Fk-oK-OrR])", "", text)
    return text.strip()


def non_italic_name(name):
    if not name or name.startswith(("&", "§", "<")):
        return name
    return "&f" + name


def references(data):
    if isinstance(data, dict):
        for value in data.values():
            yield from references(value)
    if isinstance(data, list):
        for value in data:
            yield from references(value)
    if isinstance(data, str) and re.fullmatch(r"(?:ce|nexo|sf|craftengine):[a-z0-9_:]+", data):
        yield data


class Assets:
    def __init__(self, roots):
        self.roots = roots
        self.seen = set()

    def asset(self, reference, kind, extension, atlas="item"):
        namespace, path = reference.split(":", 1) if ":" in reference else ("minecraft", reference)
        if namespace == "minecraft" or path.startswith("#"):
            return reference
        # Newer clients separate block and item atlases; match the model's atlas so no model mixes both.
        target = f"{atlas}/{namespace}/{path}" if kind == "textures" else f"sf/{namespace}/{path}"
        output = f"crops:{target}"
        if (kind, atlas, reference) in self.seen:
            return output
        source = next((p for base in self.roots
                       if (p := base / namespace / kind / f"{path}{extension}").is_file()), None)
        if source is None:
            raise FileNotFoundError(reference)
        self.seen.add((kind, atlas, reference))
        destination = PACK / kind / f"{target}{extension}"
        destination.parent.mkdir(parents=True, exist_ok=True)
        if kind == "models":
            model = json.loads(source.read_text())
            if "parent" in model:
                model["parent"] = self.asset(model["parent"], "models", ".json")
            if "textures" in model:
                uses_block_atlas = any(
                    isinstance(v, str) and (v.startswith("minecraft:block/") or v.startswith("block/"))
                    for v in model["textures"].values()
                )
                tex_atlas = "block" if uses_block_atlas else "item"
                model["textures"] = {k: v if v.startswith("#") else self.asset(v, "textures", ".png", tex_atlas)
                                     for k, v in model["textures"].items()}
            for element in model.get("elements", []):
                rotation = element.get("rotation", {})
                axes = [(axis, rotation[axis]) for axis in "xyz" if rotation.get(axis)]
                if any(axis in rotation for axis in "xyz") and len(axes) <= 1:
                    axis, angle = axes[0] if axes else ("y", 0)
                    element["rotation"] = {k: v for k, v in rotation.items() if k not in "xyz"}
                    element["rotation"].update(axis=axis, angle=angle)
            destination.write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")) + "\n")
        else:
            destination.write_bytes(source.read_bytes())
            animation = source.with_suffix(source.suffix + ".mcmeta")
            if animation.is_file():
                destination.with_suffix(destination.suffix + ".mcmeta").write_bytes(animation.read_bytes())
        return output

    def generated(self, item, texture):
        target = PACK / "models/item" / f"{item}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        model = {"parent": "minecraft:item/generated", "textures": {"layer0": self.asset(texture, "textures", ".png")}}
        target.write_text(json.dumps(model, separators=(",", ":")) + "\n")
        return f"crops:item/{item}"


def read_index(paths, child=None):
    index = {}
    for path in sorted(paths):
        try:
            doc = load(path)
        except (yaml.YAMLError, OSError):
            continue
        if not isinstance(doc, dict):
            continue
        for key, row in (doc.get(child, {}) if child else doc).items():
            if isinstance(row, dict):
                index[key] = row
    return index


def ingredient(row):
    if "nexo_item" in row:
        return "nexo:" + row["nexo_item"]
    if "minecraft_type" in row:
        return "minecraft:" + row["minecraft_type"].lower()
    raise ValueError(f"Unsupported recipe ingredient {row}")


def food_recipes(nexo, allowed):
    recipes = {}
    for category, name in (("shaped", "kitchen"), ("shapeless", "crops"), ("furnace", "pies")):
        for recipe_id, definition in load(nexo / f"recipes/{category}/{name}.yml").items():
            result = ingredient(definition["result"])
            if result.split(":")[-1] not in allowed and category != "shaped":
                continue
            if "weed" in recipe_id:
                continue
            result = {"item": result, "amount": definition["result"].get("amount", 1)}
            recipe = {"result": result}
            if category == "furnace":
                recipe.update(ingredient=ingredient(definition["input"]), time=definition["cookingTime"],
                              experience=definition.get("experience", 0))
            elif category == "shaped":
                recipe.update(shape=definition["shape"], keys={key: ingredient(v)
                              for key, v in definition["ingredients"].items()})
            else:
                recipe["ingredients"] = [ingredient(v) for v in definition["ingredients"].values()
                                         for _ in range(v.get("amount", 1))]
            recipes[recipe_id] = recipe
    return recipes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sf", type=Path)
    args = parser.parse_args()
    harvest = args.sf / "Allium/harvest"
    nexo = args.sf / "Nexo"
    ce = args.sf / "CraftEngine/resources/allium_assets"
    ores = args.sf / "CraftEngine/resources/sf_ores"
    sources = [ce / "resourcepack/assets", nexo / "pack/assets", ores / "resourcepack/assets"]
    assets = Assets(sources)
    ce_index = read_index((ce / "configuration/items").glob("*.yml"), "items")
    ce_index.update(read_index([ores / "configuration/salt.yml"], "items"))
    sf_items = nexo / "items/sf"
    nexo_index = read_index([*(sf_items / "crops").rglob("*.yml"), *(sf_items / "food").rglob("*.yml"),
                             *(sf_items / "furniture").rglob("*.yml"), *(sf_items.glob("*.yml"))])

    crops = [load(path) for path in sorted((harvest / "crops").glob("*.yml")) if path.stem != "weed"]
    kitchen = load(harvest / "kitchen.yml")
    refs = set(references([crops, kitchen]))
    pies = kitchen["pies"]
    for pie, row in pies["types"].items():
        for key, pattern in pies["models"].items():
            if key == "filled":
                refs.add(row.get("filled-model", pattern.format(type=pie)))
                continue
            for state in ("cold", "baked"):
                if key == "item":
                    refs.add(row.get(f"{state}-item", pattern.format(type=pie, state=state)))
                else:
                    refs.update(row.get(f"{state}-models", [pattern.format(type=pie, state=state,
                                slice=suffix) for suffix in ("", "_slice1", "_slice2", "_slice3")]))
    refs.add("nexo:kitchen_stove")
    recipes = food_recipes(nexo, {r.split(":")[-1] for r in refs})
    refs.update(references(recipes))

    for crop in crops:
        if isinstance(crop.get("display-name"), str):
            crop["display-name"] = strip_colors(crop["display-name"])
    displays = {stage["model"].split(":")[-1]: strip_colors(crop["display-name"]) for crop in crops
                for path in crop["paths"]["entries"].values() for stage in path["stages"] if "model" in stage}
    giant_visuals = {f"gigantic_{crop['id']}": crop["paths"]["entries"]["gigantic"]["stages"][-1]["model"]
                     for crop in crops if "gigantic" in crop["paths"]["entries"]}
    by_id = {}
    for reference in sorted(refs):
        name = reference.split(":")[-1]
        by_id.setdefault(name, reference)
    for name in by_id:
        if "weed" in name:
            raise ValueError(f"Unexpected excluded crop {name}")

    items = {}
    missing = []
    for name, ref in sorted(by_id.items()):
        cr = ce_index.get(ref) or ce_index.get("ce:" + name)
        nr = nexo_index.get(name)
        # Crop stage definitions come from the SF CraftEngine display library; pies use the Nexo models.
        model, texture, gui = None, None, None
        if cr is not None and (ref.startswith(("ce:", "craftengine:")) or nr is None):
            selector = cr.get("model", {})
            if isinstance(selector, dict):
                model = selector.get("path") or selector.get("fallback", {}).get("path")
                gui = next((row["model"]["path"] for row in selector.get("cases", []) if row["when"] == "gui"), None)
            texture = cr.get("texture")
        elif nr is not None:
            pack = nr.get("Pack", {})
            model = pack.get("model")
            texture = pack.get("texture")
            textures = pack.get("textures")
            if isinstance(textures, list) and textures:
                texture = textures[0]
            elif isinstance(textures, dict):
                texture = textures.get("layer0")
            if texture and ":" not in texture:
                texture = "minecraft:" + texture if texture.startswith("item/") and texture.split("/")[-1] in {"paper", "stick"} else "sf:" + texture
        try:
            if name == "golden_cabbage":
                model, texture = None, "ce:item/crops/cabbage/cabbage"
            if name.startswith("gigantic_") and not model and not texture:
                visual = ce_index.get(giant_visuals.get(name), {}).get("model", {})
                model = visual.get("fallback", {}).get("path")
                gui = next((row["model"]["path"] for row in visual.get("cases", []) if row["when"] == "gui"), None)
            if model:
                target = assets.asset(model, "models", ".json")
            elif texture:
                target = assets.generated(name, texture)
            else:
                missing.append((name, "no item model"))
                continue
            raw_name = (displays.get(name) or (nr or {}).get("itemname")
                        or (nr or {}).get("item_name") or name.replace("_", " ").title())
            row = {"model": target, "name": raw_name if name in displays else non_italic_name(raw_name)}
            if gui:
                row["gui"] = assets.asset(gui, "models", ".json")
            items[name] = row
        except FileNotFoundError as problem:
            missing.append((name, str(problem)))
    if missing:
        raise SystemExit("Missing assets:\n" + "\n".join(f"{name}: {problem}" for name, problem in missing))

    # Stable IDs across import order and additions. Check for collisions before writing.
    import zlib
    ids = {}
    for name, item in items.items():
        stamp = 3000000 + zlib.crc32(name.encode()) % 1000000
        if stamp in ids or stamp - 1 in ids or stamp + 1 in ids:
            stamp += 2
            while stamp in ids or stamp - 1 in ids or stamp + 1 in ids:
                stamp += 2
        ids[stamp] = name
        item["cmd"] = stamp
    prices = {}
    for crop in crops:
        seed = crop["seed"]["item"].split(":")[-1]
        for path in crop["paths"]["entries"].values():
            for ref in references(path["mature-harvest"]):
                name = ref.split(":")[-1]
                if (name == seed and name.endswith("_seeds")) or name not in items:
                    continue
                price = 25 if name.startswith("gigantic_") else 15 if name.startswith("golden_") else 5
                if name.endswith("_golden_star"): price = 10
                if name.endswith("_silver_star"): price = 7.5
                prices[name] = price
    write_yaml(RESOURCES / "prices.yml", {"prices": dict(sorted(prices.items()))})
    write_yaml(RESOURCES / "items.yml", {"items": items})
    write_yaml(RESOURCES / "crops.yml", {"crops": {row["id"]: clean(row) for row in crops}})
    write_yaml(RESOURCES / "kitchen.yml", clean(kitchen))
    write_yaml(RESOURCES / "recipes.yml", {"recipes": clean(recipes)})
    lang = PACK / "lang/en_us.json"
    lang.parent.mkdir(parents=True, exist_ok=True)
    lang.write_text('{"jade.modName.crops":"Crops"}\n')
    print(f"Imported {len(crops)} crops, {len(pies['types'])} pie types, {len(items)} items, {len(recipes)} external recipes")


if __name__ == "__main__":
    main()
