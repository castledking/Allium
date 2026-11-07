#!/usr/bin/env python3
"""Source of Allium's original core-tool textures.

Each texture is a 16x16 grid of palette characters below, drawn for Allium. Running this script
rewrites the PNGs under pack/assets/allium/textures/item/. The encoder is dependency-free and
deterministic (RGBA, filter 0, zlib level 9), so the committed PNGs are reproducible.

    python3 pack/tools/draw_textures.py          # rewrite the PNGs
    python3 pack/tools/draw_textures.py --check  # fail if a committed PNG differs
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'assets/allium/textures/item'

PALETTE = {
    '.': (0, 0, 0, 0),
    'K': (59, 42, 20, 255),      # handle outline
    'd': (156, 107, 26, 255),    # gold, dark
    'G': (217, 165, 32, 255),    # gold, mid
    'g': (246, 212, 90, 255),    # gold, light
    'S': (44, 54, 64, 255),      # steel outline
    'n': (95, 113, 130, 255),    # steel, dark
    'm': (154, 172, 188, 255),   # steel, mid
    's': (227, 236, 243, 255),   # steel, light
    'e': (111, 243, 255, 255),   # charge pip, lit
    'o': (60, 66, 73, 255),      # charge pip, spent
    'c': (20, 20, 20, 255),      # break
}

# Mob Disarmer: a gold-handled steel clamp with three charge pips along the handle.
DISARMER = """
........SSS.....
.......SsmnS....
.......SmnS..SS.
........SnS.SsmS
........SmnSSmnS
.......KSnmmmnS.
......KgdSnnnS..
.....KgGdKSSS...
....KgGdK.......
...KgedK........
..KgGdK.........
.KgedK..........
KgGdK...........
KedK............
KdK.............
.K..............
"""

# Broken: the right jaw has snapped off at a dark break.
DISARMER_BROKEN_HEAD = """
........SSS.....
.......SsmnS....
.......SmnS.....
........SnS.....
........SmnSS...
.......KSnmcS...
......KgdScS....
.....KgGdKS.....
"""

# Pips light from the bottom of the handle upward: (row, column).
PIPS = [(13, 1), (11, 3), (9, 5)]


def grid(art):
    rows = art.strip('\n').split('\n')
    if len(rows) != 16 or any(len(r) != 16 for r in rows):
        raise ValueError('textures are 16x16')
    return rows


def with_pips(rows, lit):
    rows = list(rows)
    for i, (y, x) in enumerate(PIPS):
        row = list(rows[y])
        row[x] = 'e' if i < lit else 'o'
        rows[y] = ''.join(row)
    return rows


def disarmer_states():
    full = grid(DISARMER)
    head = DISARMER_BROKEN_HEAD.strip('\n').split('\n')
    broken = head + full[len(head):]
    return {
        'mob_disarmer/full': with_pips(full, 3),
        'mob_disarmer/two_thirds': with_pips(full, 2),
        'mob_disarmer/one_third': with_pips(full, 1),
        'mob_disarmer/empty': with_pips(full, 0),
        'mob_disarmer/broken': with_pips(broken, 0),
    }


def png(rows):
    raw = b''.join(b'\x00' + b''.join(bytes(PALETTE[ch]) for ch in row) for row in rows)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    header = struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='verify committed PNGs instead of writing')
    args = parser.parse_args()
    stale = []
    for name, rows in disarmer_states().items():
        path = OUT / (name + '.png')
        data = png(rows)
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            print('wrote', path.relative_to(ROOT))
    if stale:
        print('stale textures (run without --check): ' + ', '.join(stale), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
