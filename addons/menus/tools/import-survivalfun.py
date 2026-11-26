#!/usr/bin/env python3
"""Convert the SurvivalFun ExcellentShop catalog and CommandPanels message pack definitions."""
import argparse
import base64
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import re

import yaml

ADDON = Path(__file__).resolve().parents[1]
RESOURCES = ADDON / "src/main/resources"
CROP_ITEMS = ADDON.parent / "crops/src/main/resources/items.yml"


class Inline(dict):
    pass


def quoted_map(dumper, data):
    return dumper.represent_mapping("tag:yaml.org,2002:map", data, flow_style=True)


yaml.SafeDumper.add_representer(Inline, quoted_map)


def read(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def write(path, value):
    path.write_text(yaml.safe_dump(value, sort_keys=False, allow_unicode=True, width=1000), encoding="utf-8")


def parts(value):
    """Split one level of an SNBT compound without changing nested component values."""
    stack = []
    quote = None
    escaped = False
    start = 0
    for i, char in enumerate(value):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char in "\"'":
            quote = char
        elif char in "{[(":
            stack.append(char)
        elif char in "}])":
            stack.pop()
        elif char == "," and not stack:
            yield value[start:i]
            start = i + 1
    yield value[start:]


def item_argument(reference, snbt):
    component = next((field.partition(":")[2].strip() for field in parts(snbt[1:-1])
                      if field.partition(":")[0].strip() == "components"), None)
    if component is None:
        return None
    properties = []
    for prop in parts(component[1:-1]):
        key, value = re.fullmatch(r'\s*"([a-z0-9_:]+)"\s*:\s*(.+)', prop).groups()
        properties.append(f"{key}={value}")
    return reference + "[" + ",".join(properties) + "]"


def price(value):
    return float(Decimal(str(max(0, value))).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def shop(source):
    crops = read(CROP_ITEMS)["items"]
    categories = []
    for file in sorted(source.glob("*.yml")):
        original = read(file)
        settings = original["Settings"]
        icon = settings["Icon"]
        icon_reference = icon["Material"]
        crop_icon = icon_reference.rsplit(":", 1)[-1]
        if file.stem in {"crops", "food"} and crop_icon in crops:
            icon_reference = f"allium:{crop_icon}"
        category = dict(title=settings["Name"], icon=icon_reference, slot=int(settings["MainMenu"]["Slot"]),
                        pages=settings["Pages"], lore=settings.get("Description", []), products={})
        if icon.get("Display_Name"):
            category["display-name"] = icon["Display_Name"]
        if icon.get("SkinURL"):
            category["texture"] = icon["SkinURL"]
        for key, product in original["Items"].items():
            if key == "weed_nugget":
                continue
            if product["Currency"] != "vault" or product["Rotating"] or any(
                    product.get(key) for key in ("Required_Permissions", "Forbidden_Permissions", "Allowed_Ranks")):
                raise ValueError(f"Unexpected currency, rotation or permissions for {file.stem}/{key}")
            kind = product["Type"]
            argument = None
            if kind == "COMMAND":
                command, = product["Content"]["Commands"]
                entity = re.fullmatch(r"spawnercore give %player_name% ([a-z0-9_]+)", command)[1]
                reference = f"allium:spawner_core;{entity}"
            elif kind == "ITEM":
                data = product["Item"]
                provider = data["Provider"]
                if provider == "vanilla":
                    snbt = data["Data"]["Value"]
                    reference = re.search(r'\bid:"(minecraft:[a-z0-9_]+)"', snbt)[1]
                    argument = item_argument(reference, snbt)
                else:
                    ident = data["Data"]["ID"]
                    local = ident.rsplit(":", 1)[-1]
                    if file.stem in {"crops", "food"} and local in crops:
                        reference = f"allium:{local}"
                    else:
                        reference = f"{provider}:{ident}"
            else:
                raise ValueError(f"Unknown item type {kind} for {file.stem}/{key}")
            positioning = product["Shop_View"]
            row = Inline(item=reference, slot=positioning["Slot"], page=positioning["Page"])
            if argument:
                row["item-argument"] = argument
            prices = product["Price"]
            buy = prices["BUY"]
            if isinstance(buy, dict):
                if buy["Min"] != buy["Max"]:
                    raise ValueError(f"Unexpected floating buy price for {file.stem}/{key}")
                buy = buy["Min"]
            row["buy"] = price(buy) if settings.get("Buying", True) else 0
            sell = prices["SELL"]
            if isinstance(sell, dict):
                # Some of the source ranges have their endpoints reversed.
                low, high = sorted((price(sell["Min"]), price(sell["Max"])))
                row["sell-price"] = Inline(min=low, max=high,
                                           interval=prices["Refresh"]["Interval"])
                row["sell"] = settings.get("Selling", True)
            else:
                row["sell"] = sell > 0 and settings.get("Selling", True)
                if row["sell"]:
                    row["sell-price"] = price(sell)
            category["products"][key] = row
        categories.append((file.stem, category))
    return dict(title="&8Shop → Main", rows=5, **{"auction-command": "auction"},
                categories=dict(sorted(categories, key=lambda entry: entry[1]["slot"])))


def messages(file):
    data = read(file)
    packs = {}
    for slot, variants in data["layout"].items():
        if not str(slot).isdigit() or not variants[0].endswith("_selected"):
            continue
        key = variants[0].removesuffix("_selected")
        button = data["items"][key + "_unlocked"]
        choices = {}
        for command in button["actions"]["commands"]:
            match = re.fullmatch(r'\[data\] set selected(Join|Quit)Message "(.*)"', command)
            if match:
                choices[match[1].lower()] = match[2]
        if len(choices) != 2:
            raise ValueError(f"Missing messages for {key}")
        raw = button["material"].split(" ", 1)[1]
        texture = json.loads(base64.b64decode(raw))["textures"]["SKIN"]["url"].rsplit("/", 1)[1]
        permission = re.search(r'\$HASPERM ([\w.]+)', button["conditions"])[1]
        packs[key] = dict(slot=int(slot), title=button["name"], texture=texture, permission=permission, **choices)
    return dict(title="&8Join and Leave Messages", rows=data["rows"], packs=packs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("sf_plugins", type=Path)
    args = parser.parse_args()
    root = args.sf_plugins
    write(RESOURCES / "shop.yml", shop(root / "ExcellentShop/virtual_shop/shops"))
    write(RESOURCES / "message-packs.yml", messages(root / "CommandPanels/panels/joinmessages.yml"))
