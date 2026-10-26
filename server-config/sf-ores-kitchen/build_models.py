#!/usr/bin/env python3
"""Stage native Minecraft models and copy existing source assets; no live writes."""
import argparse
import copy
import json
import shutil
import zipfile
from pathlib import Path

import yaml


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def cube(lo, hi, texture):
    return {"from": lo, "to": hi, "faces": {
        face: {"texture": texture, "uv": [0, 0, 16, 16]}
        for face in ("up", "down", "north", "south", "east", "west")}}


SAUCE_TEXTURE = "sf:pies/cranberry_filled_top"


def open_pizza_model(crust, texture, height, state="cold", regions=((2, 2, 14, 14),)):
    """Fill the existing hollow crust, clipping it to the original pie bite shapes."""
    model = copy.deepcopy(crust)
    model.pop("groups", None)
    model["textures"].update({"0": f"sf:pies/{state}_side",
                              "1": f"sf:pies/{state}_bottom",
                              "2": SAUCE_TEXTURE,
                              "filling": texture,
                              "particle": texture})
    filling = cube([3, 1, 3], [13, height, 13], "#filling")
    filling["name"] = "inset_filling"
    if texture == SAUCE_TEXTURE:
        model["textures"]["inner"] = f"sf:pies/{state}_cranberry_inner"
        for face in ("north", "south", "east", "west"):
            filling["faces"][face]["texture"] = "#inner"
    elements = model["elements"] + [filling]
    model["elements"] = []
    for x1, z1, x2, z2 in regions:
        for element in elements:
            low, high = element["from"].copy(), element["to"].copy()
            low[0], low[2] = max(low[0], x1), max(low[2], z1)
            high[0], high[2] = min(high[0], x2), min(high[2], z2)
            if low[0] >= high[0] or low[2] >= high[2]:
                continue
            clipped = {"name": element.get("name", "crust"), "from": low, "to": high, "faces": {}}
            sides = [element["faces"][face] for face in ("north", "south", "east", "west")
                     if face in element["faces"]]
            for face in ("up", "down", "north", "south", "east", "west"):
                source = element["faces"].get(face)
                if source is None and face in ("up", "down"):
                    continue
                # A bite exposes new crust edges, including edges omitted on
                # the hollow crust where they originally touched another wall.
                source = source or sides[0]
                uv = ([low[0], low[2], high[0], high[2]] if face in ("up", "down") else
                      [low[0], 16 - high[1], high[0], 16 - low[1]] if face in ("north", "south") else
                      [low[2], 16 - high[1], high[2], 16 - low[1]])
                clipped["faces"][face] = {"texture": source["texture"], "uv": uv}
            model["elements"].append(clipped)
    return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sf", type=Path, required=True, help="Existing SF plugins directory, read only")
    parser.add_argument("--croptopia", type=Path, required=True)
    parser.add_argument("--farmers-delight", type=Path, required=True)
    parser.add_argument("--metals", type=Path, required=True, help="Recovered silver and galena asset directory")
    parser.add_argument("--vanilla-assets", type=Path, help="Minecraft client JAR; copies five unchanged vanilla textures for Bedrock export")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    assets = root / "Nexo/pack/assets/sf"
    source_models = args.sf / "Nexo/pack/assets/sf/models/block"
    item_file = root / "Nexo/items/sf/food/pizza.yml"
    entries = yaml.safe_load(item_file.read_text())
    # Idempotent when regenerating: retain only the hand-written ingredients.
    entries = {key: entries[key] for key in ("tomato_sauce", "cheese", "pizza_base", "pizza_base_placed")}
    entries["pizza_base"]["lore"] = ["<gray>Place it, add spinach, cheese, then tomato sauce."]
    crust = json.loads((source_models / "pie_crust_placed.json").read_text())
    for state in ("cold", "baked"):
        for bite in range(4):
            suffix = "" if bite == 0 else f"_slice{bite}"
            source = json.loads((source_models / f"{state}_cranberry_pie{suffix}.json").read_text())
            regions = [(part["from"][0], part["from"][2], part["to"][0], part["to"][2])
                       for part in source["elements"]]
            model = open_pizza_model(crust, SAUCE_TEXTURE, 3.25, state, regions)
            name = f"{state}_deep_dish_pizza{suffix}"
            write_json(assets / f"models/block/{name}.json", model)
            item_id = f"{state}_deep_dish_pizza_placed{suffix}"
            entries[item_id] = {"itemname": f"Deep-Dish Pizza {state} {bite}", "material": "PAPER",
                                "excludeFromInventory": True, "Pack": {"model": f"sf:block/{name}"}}
            if bite == 0:
                whole = f"{state}_deep_dish_pizza"
                entries[whole] = {"itemname": "<#e8c07a>Deep Dish Pizza <gray>(Sauce on Top)",
                                  "material": "PAPER", "lore": ["<gray>Place to share four slices."],
                                  "Pack": {"model": f"sf:block/{name}"}}
    # Only the filling rises: the original rim remains visible above every
    # layer. The sauce state is also the final open pizza, without a dough cap.
    for progress, texture, height, label in (
        ("spinach", "sf:food/pizza_spinach", 1.5, "Spinach"),
        ("cheese", "sf:food/pizza_cheese", 2.25, "Cheese"),
        ("sauce_topped", SAUCE_TEXTURE, 3.25, "Sauce Topped")):
        model = open_pizza_model(crust, texture, height)
        name = f"deep_dish_pizza_{progress}"
        write_json(assets / f"models/block/{name}.json", model)
        item_id = f"deep_dish_pizza_{progress}"
        entries[item_id] = {"itemname": f"Deep Dish Pizza {label}", "material": "PAPER",
                            "excludeFromInventory": True, "Pack": {"model": f"sf:block/{name}"}}
    filled = open_pizza_model(crust, SAUCE_TEXTURE, 3.25)
    write_json(assets / "models/block/deep_dish_pizza_filled.json", filled)
    entries["deep_dish_pizza_filled"] = {"itemname": "Deep Dish Pizza with Sauce", "material": "PAPER",
        "excludeFromInventory": True, "Pack": {"model": "sf:block/deep_dish_pizza_filled"}}
    item_file.write_text("# Generated by build_models.py from the existing SF pie geometry.\n" + yaml.safe_dump(entries, sort_keys=False, allow_unicode=True))
    ce = root / "CraftEngine/resources/sf_ores/resourcepack/assets/sf"
    copies = [
        (args.croptopia / "common/src/main/resources/assets/croptopia/textures/item/salt.png", ce / "textures/item/salt.png"),
        (args.croptopia / "common/src/main/resources/assets/croptopia/textures/block/salt_ore.png", ce / "textures/block/salt_ore.png"),
        (args.croptopia / "common/src/main/resources/assets/croptopia/textures/item/cheese.png", assets / "textures/food/cheese.png"),
        (args.farmers_delight / "src/main/resources/assets/farmersdelight/textures/item/tomato_sauce.png", assets / "textures/food/tomato_sauce.png")]
    for source, dest in copies:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)
    write_json(ce / "models/block/salt_ore.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "sf:block/salt_ore"}})
    deepslate = {"textures": {"base": "sf:block/deepslate_base", "salt": "sf:block/salt_crystals", "particle": "sf:block/deepslate_base"},
                 "elements": [cube([0,0,0],[16,16,16],"#base")]}
    # Native model faces overlay small salt deposits on vanilla deepslate.
    for face, axis, edge in [("up",1,16), ("down",1,0), ("east",0,16), ("west",0,0), ("south",2,16), ("north",2,0)]:
        for u, v in [(3,4),(10,3),(7,10),(12,12)]:
            others = [i for i in range(3) if i != axis]
            lower, upper = [0,0,0], [0,0,0]
            # Give every crystal positive depth: Bedrock's model conversion
            # cannot stitch zero-thickness planes. Most of the crystal is
            # inset in the deepslate cube; its outer face sits just above it.
            lower[axis] = edge - 1 if edge else -.01
            upper[axis] = edge + .01 if edge else 1
            lower[others[0]], lower[others[1]] = u,v
            upper[others[0]], upper[others[1]] = u+2,v+2
            deepslate["elements"].append(cube(lower,upper,"#salt"))
    write_json(ce / "models/block/deepslate_salt_ore.json", deepslate)
    vanilla_copies = [
        ("red_terracotta", assets / "textures/food/pizza_sauce.png"),
        ("quartz_block_top", assets / "textures/food/pizza_cheese.png"),
        ("moss_block", assets / "textures/food/pizza_spinach.png"),
        ("deepslate", ce / "textures/block/deepslate_base.png"),
        ("quartz_block_top", ce / "textures/block/salt_crystals.png")]
    if args.vanilla_assets:
        with zipfile.ZipFile(args.vanilla_assets) as jar:
            for name, destination in vanilla_copies:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(jar.read(f"assets/minecraft/textures/block/{name}.png"))
    elif any(not destination.exists() for _, destination in vanilla_copies):
        parser.error("Supply --vanilla-assets /path/to/minecraft-client.jar to copy the required vanilla textures")
    metal_config = {"items": {}, "blocks": {}, "recipes": {}}
    for metal in ("silver", "galena"):
        title = metal.title()
        for item in (f"raw_{metal}", f"{metal}_ingot"):
            dest = ce / f"textures/item/{item}.png"
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.metals / f"{item}.png", dest)
            metal_config["items"][f"sf:{item}"] = {"data": {"item_name": f"<!i><white>{'Raw ' + title if item.startswith('raw_') else title + ' Ingot'}"},
                                                        "texture": f"sf:item/{item}"}
        for deep in (False, True):
            ore = f"{'deepslate_' if deep else ''}{metal}_ore"
            for filename in ([ore, f"{ore}_top"] if deep else [ore]):
                dest = ce / f"textures/block/{filename}.png"
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(args.metals / f"{filename}.png", dest)
            model = {"parent": "minecraft:block/cube_column", "textures": {"side": f"sf:block/{ore}", "end": f"sf:block/{ore}_top"}} if deep else {
                "parent": "minecraft:block/cube_all", "textures": {"all": f"sf:block/{ore}"}}
            write_json(ce / f"models/block/{ore}.json", model)
            metal_config["items"][f"sf:{ore}"] = {"data": {"item_name": f"<!i><white>{'Deepslate ' if deep else ''}{title} Ore"},
                "model": f"sf:block/{ore}", "behavior": {"type": "block_item", "block": f"sf:{ore}"}}
            metal_config["blocks"][f"sf:{ore}"] = {
                "loot": {"template": "default:loot_table/ore", "arguments": {"ore_drop": f"sf:raw_{metal}", "ore_block": f"sf:{ore}"}},
                "settings": {"template": "default:settings/deepslate_ore" if deep else "default:settings/ore", "arguments": {"break_power": 3},
                    "overrides": {"hardness": 5 if deep else 4, "require_correct_tools": True, "destroy_stages": {"template": "internal:destroy_stages"}}},
                "state": {"auto_state": "mushroom", "model": f"sf:block/{ore}"}}
        for ingredient in (f"raw_{metal}", f"{metal}_ore", f"deepslate_{metal}_ore"):
            for kind, ticks in (("smelting",200),("blasting",100)):
                metal_config["recipes"][f"sf:{metal}_from_{kind}_{ingredient}"] = {"type": kind, "experience": .7, "time": ticks,
                    "ingredient": f"sf:{ingredient}", "result": {"id": f"sf:{metal}_ingot", "count": 1}}
    (root / "CraftEngine/resources/sf_ores/configuration/metals.yml").write_text(yaml.safe_dump(metal_config,sort_keys=False))
    print(f"Staged {len(entries)} Nexo items, pizza/salt/metal models, recovered metal textures and twelve smelting/blasting recipes in {root}")


if __name__ == "__main__":
    main()
