#!/usr/bin/env python3
"""Gives the server's Nexo crop items their names.

The stage items of a crop are models, not things a player holds, and they were
left without an ``itemname`` — so the tooltip said "Paper", the material they
happen to use, instead of the crop's name. One of them is still called
"Test Crop Name".

The names follow the convention the rest of the pack already uses: the crop's
name, then the stage in brackets — "Tomato", "Tomato (Silver Star)",
"Tomato Seeds", "Tomato (Stage 1)".

Usage:

    fill_nexo_names.py <items-dir> [--write]

    <items-dir>   the folder holding the Nexo item files
    --write       write the files back (they are backed up first)
"""

import argparse
import glob
import os
import re
import shutil
import sys

ITEM = re.compile(r"^([a-z0-9_]+):\n((?:[ \t]+.*\n|\n)*)", re.M)
NAME = re.compile(r'^(\s*)itemname:\s*"(.*?)"\s*$', re.M)
PLACEHOLDER = re.compile(r"^Test Crop Name$")


def title(identifier):
    return identifier.replace("_", " ").title()


def crop_name(identifier, names):
    """The crop's own name: its item if it has one, else the id, tidied."""
    base = re.sub(r"_(stage|bush)_\d+$", "", identifier)
    if base in names:
        return names[base]
    # The crop's item may be named differently — "blackberries" for a
    # "blackberry_bush_1" — so try the plural before giving up.
    for candidate in (base + "s", re.sub(r"y$", "ies", base)):
        if candidate in names:
            return names[candidate]
    return title(base)


def fill(text, names):
    """Gives every item the name it should have."""
    def item(match):
        ident, body = match.group(1), match.group(2)
        existing = NAME.search(body)
        stage = re.search(r"_(stage|bush)_\d+$", ident)
        if not stage and existing and not PLACEHOLDER.match(existing.group(2)):
            # A fruit, a seed or a star already says what it is.
            return match.group(0)
        # A stage is the crop, so it takes the crop's name — "Tomato", not
        # "Tomato (Stage 2)".
        label = crop_name(ident, names)
        if existing:
            body = body[:existing.start()] + '%sitemname: "%s"' % (
                existing.group(1), label) + body[existing.end():]
        else:
            # Right after the id line, before the rest of the item.
            body = '%sitemname: "%s"\n%s' % ("  ", label, body.lstrip("\n"))
        return "%s:\n%s" % (ident, body)

    return ITEM.sub(item, text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("items_dir", help="the folder holding the Nexo item files")
    parser.add_argument("--write", action="store_true", help="write the files back")
    arguments = parser.parse_args()

    files = sorted(glob.glob(os.path.join(arguments.items_dir, "**", "*.yml"),
                             recursive=True))
    changed = 0
    for path in files:
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        names = {m.group(1): NAME.search(m.group(2)).group(2)
                 for m in ITEM.finditer(text) if NAME.search(m.group(2))}
        filled = fill(text, names)
        if filled == text:
            continue
        changed += 1
        if arguments.write:
            shutil.copyfile(path, path + ".bak-names")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(filled)
    print("%d files %s" % (changed, "written" if arguments.write else "would change"))


if __name__ == "__main__":
    main()
