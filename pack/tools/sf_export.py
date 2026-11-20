#!/usr/bin/env python3
"""Local export recipe: build the core pack with SF's server-only art for the core tools.

SF's textures are not distributable, so they are never committed. This script copies them from
$SF (read-only) into build/sf-export/ (git-ignored) and writes a derived manifest there, in which
the Phantom Obliterator (and the Lazy Axe only with --include-lazy-axe) become
ready tools whose textures are SF's. It then runs the same generator with --texture-root, producing
build/sf-export/allium-core-pack-sf.zip for SF's own use only.

    ./gradlew shadowJar
    python3 pack/tools/sf_export.py --plugins "$SF"

- Mob Disarmer: SF's four charge textures; SF has no broken-state art, so the broken model
  reuses the empty (_0-3) texture.
- Phantom Obliterator: the same five states, from SF's phantom_obliterator textures, the broken
  one again reusing _0-3.
- Lazy Axe: SF's tree_axe texture, only with --include-lazy-axe. Its netherite-axe
  dispatch conflicts with Nexo's, so it is never included in the default export.

The provenance file records every source and hash. The committed manifest and pack are unchanged.
"""
import argparse
import copy
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SPECIALS = 'Nexo/pack/assets/sf/textures/items/specials'
CHARGES = {'full': '', 'two_thirds': '_2-3', 'one_third': '_1-3', 'empty': '_0-3', 'broken': '_0-3'}
# tool id -> {state: SF texture path relative to the plugins directory}
SOURCES = {
    'mob_disarmer': {state: f'{SPECIALS}/mob_disarmer{suffix}.png' for state, suffix in CHARGES.items()},
    'phantom_obliterator': {state: f'{SPECIALS}/phantom_obliterator{suffix}.png' for state, suffix in CHARGES.items()},
    'lazy_axe': {'default': 'Nexo/pack/assets/sf/textures/items/tree_axe.png'},
}
VANILLA_SLOT_SPRITES = {
    'helmet': ('gui/sprites/container/slot/helmet.png', 'item/empty_armor_slot_helmet.png'),
    'chestplate': ('gui/sprites/container/slot/chestplate.png', 'item/empty_armor_slot_chestplate.png'),
    'leggings': ('gui/sprites/container/slot/leggings.png', 'item/empty_armor_slot_leggings.png'),
    'boots': ('gui/sprites/container/slot/boots.png', 'item/empty_armor_slot_boots.png'),
    'shield': ('gui/sprites/container/slot/shield.png', 'item/empty_armor_slot_shield.png'),
    'sword': ('gui/sprites/container/slot/sword.png', 'item/empty_slot_sword.png'),
}
LICENSE = 'SF server-only art: not distributable, never commit'
VANILLA_LICENSE = 'Mojang asset copied from a local client jar: not distributable, never commit'
COLOR_CODE_RE = re.compile(r'&([0-9a-fk-orA-FK-OR])')


def read_png(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (16, 16):
        sys.exit(f'{path.name} is not a 16x16 PNG')
    return data


def to_rgba_png(data):
    try:
        from PIL import Image
    except ImportError:
        sys.exit('--vanilla-client needs Pillow (pip install pillow) to convert Mojang slot icons to RGBA')
    out = io.BytesIO()
    Image.open(io.BytesIO(data)).convert('RGBA').save(out, format='PNG')
    return out.getvalue()


def read_vanilla_slot_icon(jar, state):
    for relative in VANILLA_SLOT_SPRITES[state]:
        name = 'assets/minecraft/textures/' + relative
        if name in jar.namelist():
            data = jar.read(name)
            if data[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', data[16:24]) != (16, 16):
                sys.exit(f'{name} is not a 16x16 PNG')
            return name, to_rgba_png(data)
    sys.exit(f'the client jar has no slot icon for {state}')


def apply_vanilla_slot_icons(derived, client_jar, textures_root, provenance):
    tool = next((t for t in derived['tools'] if t['id'] == 'gui_slot'), None)
    if tool is None:
        return
    with zipfile.ZipFile(client_jar) as jar:
        for state in tool['states']:
            if state['key'] not in VANILLA_SLOT_SPRITES:
                continue
            source, data = read_vanilla_slot_icon(jar, state['key'])
            texture_id = f"{derived['namespace']}:item/gui_slot/{state['key']}"
            relative = f"assets/{derived['namespace']}/textures/item/gui_slot/{state['key']}.png"
            target = textures_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            state['texture'] = texture_id
            sha = hashlib.sha256(data).hexdigest()
            existing = next((t for t in derived['textures'] if t['id'] == texture_id), None)
            entry = {'id': texture_id, 'file': relative, 'sha256': sha, 'author': 'Mojang',
                     'origin': f'{client_jar.name}!{source}', 'license': VANILLA_LICENSE, 'distributable': False}
            if existing:
                existing.update(entry)
            else:
                derived['textures'].append(entry)
            provenance.append({'tool': 'gui_slot', 'state': state['key'], 'source': f'{client_jar.name}!{source}',
                               'sha256': sha, 'license': VANILLA_LICENSE})


def translate_colors(text):
    return COLOR_CODE_RE.sub(r'§\1', text)


def derive(manifest, plugins, textures_root, include_lazy_axe=False, vanilla_client=None, description=None):
    """The committed manifest with each SF tool made ready and pointed at SF's textures."""
    derived = copy.deepcopy(manifest)
    if description is not None:
        derived['pack']['description'] = translate_colors(description)
    provenance = []
    for tool in derived['tools']:
        if tool['id'] == 'lazy_axe' and not include_lazy_axe:
            continue
        sources = SOURCES.get(tool['id'])
        if sources is None:
            continue
        for state in tool['states']:
            source = plugins / sources[state['key']]
            data = read_png(source)
            texture_id = f"{derived['namespace']}:item/{tool['id']}/{state['key']}"
            relative = f"assets/{derived['namespace']}/textures/item/{tool['id']}/{state['key']}.png"
            target = textures_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            state['texture'] = texture_id
            sha = hashlib.sha256(data).hexdigest()
            existing = next((t for t in derived['textures'] if t['id'] == texture_id), None)
            entry = {'id': texture_id, 'file': relative, 'sha256': sha, 'author': 'SF',
                     'origin': f'$SF/{sources[state["key"]]}', 'license': LICENSE, 'distributable': False}
            if existing:
                existing.update(entry)
            else:
                derived['textures'].append(entry)
            provenance.append({'tool': tool['id'], 'state': state['key'], 'source': f'$SF/{sources[state["key"]]}',
                               'sha256': sha, 'license': LICENSE})
        tool['status'] = 'ready'
        tool.setdefault('modelParent', 'minecraft:item/handheld')
    if vanilla_client is not None:
        apply_vanilla_slot_icons(derived, Path(vanilla_client), textures_root, provenance)
    return derived, provenance


def resolve_jar(path):
    if path.is_file():
        return path
    core = REPO / 'allium-core/build/libs/allium-core-1.0.0-SNAPSHOT.jar'
    if core.is_file():
        return core
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plugins', default=os.environ.get('SF'), help='SF plugins directory (read-only); defaults to $SF')
    parser.add_argument('--out', type=Path, default=REPO / 'build/sf-export')
    parser.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
    parser.add_argument('--clients', type=Path, help='a directory of vanilla client-<version>.jar files (1.20.1, 1.21.8, 26.3) to validate against')
    parser.add_argument('--vanilla-client', type=Path,
                        help='a vanilla client jar to pull Minecraft empty-slot icons for /invsee & /restore (gui_slot)')
    parser.add_argument('--pack-icon', type=Path, help='PNG to embed as pack.png (defaults to pack/pack.png if present)')
    parser.add_argument('--description', help='override pack.mcmeta description (& color codes become §)')
    parser.add_argument('--name', default='allium-core-pack-sf.zip', help='output zip filename inside --out')
    parser.add_argument('--include-lazy-axe', action='store_true',
                        help='opt in to the netherite-axe override; conflicts with SF Nexo tree_axe dispatch')
    args = parser.parse_args()
    if not args.plugins:
        parser.error('pass --plugins or set $SF')
    lanes = []
    if args.clients:
        for label, fmt in [('1.20.1', '15'), ('1.21.8', '64'), ('26.3', '97.1')]:
            jar = args.clients / f'client-{label}.jar'
            if not jar.is_file():
                parser.error(f'missing required client input: {jar}')
            lanes += ['--lane', label, fmt, str(jar)]
    out = args.out.resolve()
    if REPO / 'build' not in out.parents:
        parser.error('--out must stay under the git-ignored build/ directory')
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    # Seed the texture root with Allium's committed assets (including gui_slot icons for /invsee & /restore).
    shutil.copytree(REPO / 'pack/assets', out / 'textures/assets')
    manifest = json.loads((REPO / 'pack/manifest.json').read_text())
    derived, provenance = derive(manifest, Path(args.plugins), out / 'textures',
                                 args.include_lazy_axe, args.vanilla_client, args.description)
    derived_path = out / 'manifest-sf.json'
    derived_path.write_text(json.dumps(derived, indent=2, ensure_ascii=False) + '\n')
    jar_path = resolve_jar(args.allium_jar)
    build_cmd = ['java', '-cp', str(jar_path), 'codes.castled.allium.pack.CorePackMain', 'build',
                 str(derived_path), str(REPO / 'pack'), str(out),
                 '--texture-root', str(out / 'textures'), '--name', args.name]
    if args.pack_icon:
        build_cmd += ['--pack-icon', str(args.pack_icon)]
    result = subprocess.run(build_cmd, capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        sys.exit(result.returncode)
    (out / 'provenance.json').write_text(json.dumps({
        'note': 'Local SF export. Not distributable; never commit or publish.',
        'manifest': 'manifest-sf.json (derived from pack/manifest.json)',
        'textures': provenance,
    }, indent=2) + '\n')
    print('wrote', out / args.name, 'and', out / 'provenance.json')
    if args.clients:
        check = subprocess.run(['java', '-cp', str(jar_path), 'codes.castled.allium.pack.CorePackMain', 'validate',
                                str(out / args.name), str(derived_path), *lanes, '--local-export',
                                '--report', str(out / 'validation.json')], capture_output=True, text=True)
        print(check.stdout.strip().splitlines()[-1][:200] if check.stdout.strip() else check.stderr.strip()[-400:])
        if check.returncode != 0:
            sys.exit(check.returncode)


if __name__ == '__main__':
    main()
