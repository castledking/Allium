#!/usr/bin/env python3
"""Local export recipe: build the core pack with SF's server-only Mob Disarmer art.

SF's textures are not distributable, so they are never committed. This script copies them from
$SF (read-only) into build/sf-export/ (git-ignored), then runs the same generator with
--texture-root, producing build/sf-export/allium-core-pack-sf.zip for SF's own use only.

    ./gradlew shadowJar
    python3 pack/tools/sf_export.py --plugins "$SF"

SF has no broken-state art, so the broken model reuses the empty (_0-3) texture; the provenance
file records every source.
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCES = {
    'full': 'mob_disarmer.png',
    'two_thirds': 'mob_disarmer_2-3.png',
    'one_third': 'mob_disarmer_1-3.png',
    'empty': 'mob_disarmer_0-3.png',
    'broken': 'mob_disarmer_0-3.png',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plugins', default=os.environ.get('SF'), help='SF plugins directory (read-only); defaults to $SF')
    parser.add_argument('--out', type=Path, default=REPO / 'build/sf-export')
    parser.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
    args = parser.parse_args()
    if not args.plugins:
        parser.error('pass --plugins or set $SF')
    out = args.out.resolve()
    if REPO / 'build' not in out.parents:
        parser.error('--out must stay under the git-ignored build/ directory')
    source_dir = Path(args.plugins) / 'Nexo/pack/assets/sf/textures/items/specials'
    if out.exists():
        shutil.rmtree(out)
    textures = out / 'textures/assets/allium/textures/item/mob_disarmer'
    textures.mkdir(parents=True)
    provenance = []
    for state, name in SOURCES.items():
        data = (source_dir / name).read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (16, 16):
            sys.exit(f'{name} is not a 16x16 PNG')
        (textures / (state + '.png')).write_bytes(data)
        provenance.append({'state': state, 'source': f'$SF/Nexo/pack/assets/sf/textures/items/specials/{name}',
                           'sha256': hashlib.sha256(data).hexdigest(),
                           'license': 'SF server-only art: not distributable, never commit'})
    result = subprocess.run(['java', '-cp', str(args.allium_jar), 'codes.castled.allium.pack.CorePackMain', 'build',
                             str(REPO / 'pack/manifest.json'), str(REPO / 'pack'), str(out),
                             '--texture-root', str(out / 'textures'), '--name', 'allium-core-pack-sf.zip'],
                            capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        sys.exit(result.returncode)
    (out / 'provenance.json').write_text(json.dumps({
        'note': 'Local SF export. Not distributable; never commit or publish.',
        'manifest': 'pack/manifest.json',
        'textures': provenance,
    }, indent=2) + '\n')
    print('wrote', out / 'allium-core-pack-sf.zip', 'and', out / 'provenance.json')


if __name__ == '__main__':
    main()
