#!/usr/bin/env python3
"""Extract SpawnerCraft's head catalog from Vanilla Tweaks' More Mob Heads v2.20."""
import argparse
import base64
import json
import re
from zipfile import ZipFile


def local(value):
    return value.removeprefix("minecraft:")


def list_of(value):
    return value if isinstance(value, list) else [value] if isinstance(value, dict) else []


def condition_terms(condition):
    for node in list_of(condition):
        if local(node["type"]) == "all_of":
            yield from condition_terms(node["terms"])
        else:
            yield node


def heads(tables, path, terms=(), variant=None):
    def visit(node, inherited, variant):
        conditions = (*inherited, *condition_terms(node.get("condition", node.get("conditions"))))
        entry_type = local(node.get("type", ""))
        if entry_type == "loot_table":
            target = node.get("value", "")
            if target.startswith("more_mob_heads:entities/"):
                subpath = target.split("entities/", 1)[1]
                yield from heads(tables, subpath, conditions, variant or subpath.split("/")[-1])
        for child in node.get("children", node.get("entries", [])):
            yield from visit(child, conditions, variant)
        if entry_type != "item" or local(node.get("name", "")) != "player_head":
            return
        for modifier in list_of(node.get("modifier", node.get("functions"))):
            components = modifier.get("components", {})
            profile = components.get("minecraft:profile", {})
            for prop in profile.get("properties", []):
                if prop["name"] != "textures":
                    continue
                url = json.loads(base64.b64decode(prop["value"]))["textures"]["SKIN"]["url"]
                modifier_terms = tuple(condition_terms(modifier.get("condition", modifier.get("conditions"))))
                yield components["minecraft:item_name"], url.rsplit("/", 1)[-1], components.get(
                    "minecraft:note_block_sound", ""), conditions + modifier_terms, variant

    for pool in tables[path].get("pools", []):
        yield from visit(pool, terms, variant)


def catalog(tables):
    for mob in sorted(path for path in tables if "/" not in path):
        seen = set()
        ids = set()
        for name, texture, sound, terms, variant in heads(tables, mob):
            if texture in seen:
                continue
            seen.add(texture)
            chance, looting, bonus = 1, 1, 0
            predicates = []
            for term in terms:
                kind = local(term["type"])
                if kind == "random_chance_with_enchanted_bonus":
                    chance = term["unenchanted_chance"]
                    looting = term["enchanted_chance"]["base"]
                    bonus = term["enchanted_chance"]["per_level_above_first"]
                elif kind == "random_chance":
                    chance = looting = term["chance"]
                elif kind == "entity_properties" and term.get("entity") == "this":
                    p = term["predicate"]
                    predicates.extend(local(str(v)) for v in p.get("minecraft:components", {}).values())
                    nbt = p.get("minecraft:nbt", "")
                    if nbt:
                        predicates.append(nbt)
            key = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
            if key in ids:
                key += "_alt"
            ids.add(key)
            selector = "|".join(([variant] if variant and not predicates else []) + predicates)
            yield mob, key, name, texture, sound, chance, looting, bonus, selector


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("datapack")
    parser.add_argument("output")
    args = parser.parse_args()
    with ZipFile(args.datapack) as pack:
        marker = "data/more_mob_heads/loot_table/entities/"
        tables = {path.split(marker, 1)[1][:-5]: json.loads(pack.read(path))
                  for path in pack.namelist() if path.startswith(marker) and path.endswith(".json")}
        for path in sorted(pack.namelist()):
            if path.startswith("overlay_") and marker in path and path.endswith(".json"):
                tables[path.split(marker, 1)[1][:-5]] = json.loads(pack.read(path))
    if not tables:
        parser.error("No More Mob Heads entity tables found")
    with open(args.output, "w", encoding="utf-8") as out:
        out.write("# Includes assets from VanillaTweaks.net, used in accordance with their terms and conditions.\n")
        out.write("# mob, item ID, name, texture hash, note sound, base chance, Looting I chance, per-level bonus, variant\n")
        for row in catalog(tables):
            out.write("\t".join(map(str, row)).rstrip("\t") + "\n")


if __name__ == "__main__":
    main()
