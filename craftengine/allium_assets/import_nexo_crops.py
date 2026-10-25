#!/usr/bin/env python3
"""Imports the server's Nexo crop items into the CraftEngine pack.

The server's crops are written against Nexo items. CraftEngine crops are
written against CraftEngine items, and the two packs are separate: a CraftEngine
item and a Nexo item never share an id, and a Nexo item's model does not
travel with it.

This script reads the Nexo item files and the crop definitions, and writes:

  * a CraftEngine item per referenced Nexo item, with the same name and the
    same model (the model file and its textures are copied into the pack, in
    the item atlas, because an item model can only use item-atlas sprites);
  * a lang entry per item, so the names can be translated client-side;
  * the crop definitions, rewritten to name the CraftEngine items, with every
    stage model turned into a stage that has both a block and a model — the
    block is the crop's physical presence in the cell, the model is what
    Allium draws, and 'visual' then applies the way it does for a model
    stage.

Usage:

    import_nexo_crops.py <server-root> <pack-dir> [--write-crops]

    <server-root>   the server's plugins folder (Nexo/ and Allium/ live in it)
    <pack-dir>      the CraftEngine pack folder to write into
    --write-crops   rewrite the crop files in place (backed up first)
"""

import argparse
import json
import os
import re
import shutil
import sys

NEXO_NAMESPACE = "sf"
CROPS_DIR = os.path.join("Allium", "harvest", "crops")


def read_nexo_items(items_root):
    """Every Nexo item that carries a model: id -> (name, model path)."""
    items = {}
    for directory, _, files in os.walk(items_root):
        for name in files:
            if not name.endswith(".yml"):
                continue
            path = os.path.join(directory, name)
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            for match in re.finditer(
                r"^([a-z0-9_]+):\n((?:[ \t]+.*\n|\n)*)", text, re.M
            ):
                ident, body = match.group(1), match.group(2)
                model = re.search(r"^\s*model:\s*([a-z0-9_:/]+)\s*$", body, re.M)
                label = re.search(r'^\s*itemname:\s*"(.*?)"', body, re.M)
                if model:
                    items[ident] = (label.group(1) if label else ident, model.group(1))
    return items


def read_referenced_ids(crops_root):
    """Every nexo: id the crop files mention, as a set of ids."""
    wanted = set()
    for name in sorted(os.listdir(crops_root)):
        if not name.endswith(".yml"):
            continue
        with open(os.path.join(crops_root, name), encoding="utf-8") as handle:
            for match in re.finditer(r"(?:item|model):\s*nexo:([a-z0-9_]+)", handle.read()):
                wanted.add(match.group(1))
    return wanted


def copy_model(sf_root, pack_dir, model_path, atlas="item"):
    """Copies a model and every texture it names into the pack's atlas."""
    namespace, _, path = model_path.partition(":")
    if namespace != NEXO_NAMESPACE:
        return None
    source = os.path.join(sf_root, "models", path + ".json")
    if not os.path.exists(source):
        return None
    target = os.path.join(pack_dir, "resourcepack", "assets", "ce", "models",
                          atlas, path + ".json")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(source, encoding="utf-8") as handle:
        text = handle.read()
    text = re.sub(r'"%s:([a-z0-9_/]+)"' % NEXO_NAMESPACE, r'"ce:%s/\1"' % atlas, text)
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(text)
    for reference in set(re.findall(r'"%s:([a-z0-9_/]+)"' % NEXO_NAMESPACE, text)):
        texture = os.path.join(sf_root, "textures", reference + ".png")
        if not os.path.exists(texture):
            continue
        destination = os.path.join(pack_dir, "resourcepack", "assets", "ce",
                                   "textures", atlas, reference + ".png")
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(texture, destination)
    return "ce:%s/%s" % (atlas, path)


def write_pack(pack_dir, imported):
    """Writes the CraftEngine item definitions and the lang file."""
    items_dir = os.path.join(pack_dir, "configuration", "items")
    langs_dir = os.path.join(pack_dir, "configuration", "langs")
    os.makedirs(items_dir, exist_ok=True)
    os.makedirs(langs_dir, exist_ok=True)

    lines = ["# Imported from the server's Nexo pack — see import_nexo_crops.py.",
             "items:"]
    langs = []
    for ident in sorted(imported):
        name, model = imported[ident]
        lines.append("  ce:%s:" % ident)
        lines.append("    data:")
        lines.append("      item_name: <!i><lang:item.ce.%s>" % ident)
        lines.append("    model:")
        lines.append("      type: minecraft:model")
        lines.append("      path: %s" % model)
        langs.append("    item.ce.%s: %s" % (ident, name))
    with open(os.path.join(items_dir, "nexo_crops.yml"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    with open(os.path.join(langs_dir, "nexo_crops.yml"), "w", encoding="utf-8") as handle:
        handle.write("# Imported from the server's Nexo pack.\n")
        handle.write("lang#items:\n  en_us:\n")
        handle.write("\n".join(langs) + "\n")


def rewrite_crops(crops_root, imported, hybrid_only=False):
    """Rewrites the crop files: nexo: ids become ce: ids, stages get a block.

    With hybrid_only the item references are left as they are — the crops keep
    their Nexo items — and only the stage models gain the block that makes
    the crop a real block in its cell.
    """
    changed = []
    for name in sorted(os.listdir(crops_root)):
        if not name.endswith(".yml"):
            continue
        path = os.path.join(crops_root, name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        original = text
        if not hybrid_only:
            # Drops and seeds: the items the crop hands out and plants with.
            text = re.sub(r"item:\s*nexo:([a-z0-9_]+)", r"item: ce:\1", text)
        # Stage models: a stage that has a model becomes a stage that has a
        # block as well, so the crop is a real block in its cell and the model
        # is drawn by Allium — which is what makes 'visual' apply.
        def stage(match):
            indent, ident = match.group(1), match.group(2)
            if ident not in imported and not hybrid_only:
                return match.group(0)
            return ("%s- block: ce:crop\n%s  model: nexo:%s" % (indent, indent, ident))
        text = re.sub(r"^(\s*)- model:\s*nexo:([a-z0-9_]+)\s*$", stage, text, flags=re.M)
        if text != original:
            shutil.copyfile(path, path + ".bak-nexo-import")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(text)
            changed.append(name)
    return changed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("server_root", help="the server's plugins folder")
    parser.add_argument("pack_dir", help="the CraftEngine pack folder")
    parser.add_argument("--write-crops", action="store_true",
                        help="rewrite the crop files in place")
    parser.add_argument("--hybrid-only", action="store_true",
                        help="only give the stage models a block; leave the item "
                             "references alone (the crops keep their Nexo items)")
    parser.add_argument("--assets", default=None,
                        help="the Nexo pack assets folder (defaults to "
                             "<server_root>/Nexo/pack/assets/sf)")
    arguments = parser.parse_args()

    items_root = os.path.join(arguments.server_root, "Nexo", "items")
    assets_root = arguments.assets or os.path.join(
        arguments.server_root, "Nexo", "pack", "assets", NEXO_NAMESPACE)
    crops_root = os.path.join(arguments.server_root, CROPS_DIR)

    items = read_nexo_items(items_root)
    wanted = read_referenced_ids(crops_root)
    print("referenced nexo ids: %d, known to nexo: %d" % (len(wanted), len(wanted & set(items))))
    missing = sorted(wanted - set(items))
    if missing:
        print("not in the nexo pack (left alone): %s" % ", ".join(missing))

    imported = {}
    for ident in sorted(wanted & set(items)):
        name, model = items[ident]
        copied = copy_model(assets_root, arguments.pack_dir, model)
        if copied is None:
            print("model of %s not found in the pack assets, skipped" % ident)
            continue
        imported[ident] = (name, copied)

    write_pack(arguments.pack_dir, imported)
    print("imported %d items into %s" % (len(imported), arguments.pack_dir))

    if arguments.write_crops:
        changed = rewrite_crops(crops_root, imported, arguments.hybrid_only)
        print("rewrote %d crop files" % len(changed))


if __name__ == "__main__":
    main()
