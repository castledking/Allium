#!/usr/bin/env python3
"""Local recipe: build the core pack with Minecraft's own empty-slot icons in the GUI menus.

The committed pack draws its own slot icons (pack/tools/draw_textures.py). Mojang's textures are
not Allium's to distribute, so this recipe copies them from a vanilla client jar you already have
into build/vanilla-icons/ (git-ignored), writes a derived manifest there and builds
build/vanilla-icons/allium-core-pack-vanilla-icons.zip. Never commit or publish the result.

    ./gradlew :allium-core:jar
    python3 pack/tools/vanilla_slot_icons.py --client ~/.minecraft/versions/26.3/26.3.jar

The textures come from gui/sprites/container/slot/ (1.20.2 and later) or, on 1.20.1 jars,
item/empty_armor_slot_*.png and item/empty_slot_sword.png. The filler pane stays Allium's.
"""
import argparse
import copy
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SPRITES = {
    'helmet': ('gui/sprites/container/slot/helmet.png', 'item/empty_armor_slot_helmet.png'),
    'chestplate': ('gui/sprites/container/slot/chestplate.png', 'item/empty_armor_slot_chestplate.png'),
    'leggings': ('gui/sprites/container/slot/leggings.png', 'item/empty_armor_slot_leggings.png'),
    'boots': ('gui/sprites/container/slot/boots.png', 'item/empty_armor_slot_boots.png'),
    'shield': ('gui/sprites/container/slot/shield.png', 'item/empty_armor_slot_shield.png'),
    'sword': ('gui/sprites/container/slot/sword.png', 'item/empty_slot_sword.png'),
}
LICENSE = 'Mojang asset copied from a local client jar: not distributable, never commit'


def rgba(data):
    """Newer jars store some icons as 2-bit grayscale; the pack builder reads 8-bit RGBA."""
    try:
        from PIL import Image
    except ImportError:
        sys.exit('this recipe needs Pillow (pip install pillow) to convert the icons to RGBA')
    import io
    out = io.BytesIO()
    Image.open(io.BytesIO(data)).convert('RGBA').save(out, format='PNG')
    return out.getvalue()


def read_icon(jar, state):
    for relative in SPRITES[state]:
        name = 'assets/minecraft/textures/' + relative
        if name in jar.namelist():
            data = jar.read(name)
            if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (16, 16):
                sys.exit(f'{name} is not a 16x16 PNG')
            return name, rgba(data)
    sys.exit(f'the client jar has no slot icon for {state}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--client', type=Path, required=True, help='a vanilla client jar (read-only)')
    parser.add_argument('--out', type=Path, default=REPO / 'build/vanilla-icons')
    parser.add_argument('--core-jar', type=Path, default=REPO / 'allium-core/build/libs/allium-core-1.0.0-SNAPSHOT.jar')
    args = parser.parse_args()
    out = args.out.resolve()
    if REPO / 'build' not in out.parents:
        parser.error('--out must stay under the git-ignored build/ directory')
    if out.exists():
        shutil.rmtree(out)
    # The texture root replaces the committed one: start from Allium's own textures.
    shutil.copytree(REPO / 'pack/assets', out / 'textures/assets')
    manifest = json.loads((REPO / 'pack/manifest.json').read_text())
    derived = copy.deepcopy(manifest)
    tool = next(t for t in derived['tools'] if t['id'] == 'gui_slot')
    provenance = []
    with zipfile.ZipFile(args.client) as jar:
        for state in tool['states']:
            if state['key'] not in SPRITES:
                continue
            source, data = read_icon(jar, state['key'])
            relative = f"assets/allium/textures/item/gui_slot_vanilla/{state['key']}.png"
            target = out / 'textures' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            texture_id = f"allium:item/gui_slot_vanilla/{state['key']}"
            state['texture'] = texture_id
            sha = hashlib.sha256(data).hexdigest()
            derived['textures'].append({'id': texture_id, 'file': relative, 'sha256': sha, 'author': 'Mojang',
                                        'origin': f'{args.client.name}!{source}', 'license': LICENSE, 'distributable': False})
            provenance.append({'state': state['key'], 'source': source, 'sha256': sha})
    derived_path = out / 'manifest-vanilla-icons.json'
    derived_path.write_text(json.dumps(derived, indent=2, ensure_ascii=False) + '\n')
    result = subprocess.run(['java', '-cp', str(args.core_jar), 'codes.castled.allium.pack.CorePackMain', 'build',
                             str(derived_path), str(REPO / 'pack'), str(out), '--texture-root', str(out / 'textures'),
                             '--name', 'allium-core-pack-vanilla-icons.zip'], capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        sys.exit(result.returncode)
    (out / 'provenance.json').write_text(json.dumps({'note': 'Local build with Mojang slot icons. Never commit or publish.',
                                                     'client': args.client.name, 'icons': provenance}, indent=2) + '\n')
    print('wrote', out / 'allium-core-pack-vanilla-icons.zip')


if __name__ == '__main__':
    main()
