#!/usr/bin/env python3
"""Stage the twenty SF shop seeds from a fresh plugins snapshot; never edit input.

Only seed provider/ID scalars change in shop/crop configs. Stage models, Jade
names, produce, berries, weed, prices and Nexo files are left untouched.
Requires PyYAML. See SEEDS.md for deployment and concurrency checks.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import yaml

SEED_IDS = tuple(name + '_seeds' for name in (
    'banana', 'cabbage', 'corn', 'cotton', 'eggplant', 'garlic', 'grape',
    'hop', 'lemon', 'lettuce', 'onion', 'orange', 'pepper', 'pineapple',
    'pitaya', 'pomegranate', 'soybean', 'spinach', 'strawberry', 'tomato'))
PACK = Path('CraftEngine/resources/allium_assets')
SHOP = Path('ExcellentShop/virtual_shop/shops/crops.yml')
SEEDS = PACK / 'configuration/items/crop_seeds.yml'
LANG = PACK / 'configuration/langs/en_us.yml'


def scalars(node, path=()):
    if isinstance(node, yaml.MappingNode):
        for key, value in node.value:
            yield from scalars(value, path + (key.value,))
    elif isinstance(node, yaml.SequenceNode):
        for i, value in enumerate(node.value):
            yield from scalars(value, path + (i,))
    else:
        yield path, node


def rewrite(text, replacement):
    edits = []
    for path, node in scalars(yaml.compose(text)):
        value = replacement(path, node.value)
        if value is not None and value != node.value:
            edits.append((node.start_mark.index, node.end_mark.index, value))
    for start, end, value in reversed(edits):
        text = text[:start] + value + text[end:]
    return text, len(edits)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None


def migrate(source, output, seed_ids=SEED_IDS):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output or source in output.parents:
        raise ValueError('output must be outside the source plugins directory')
    if output.exists() and any(output.iterdir()):
        raise ValueError('output must be empty')
    output.mkdir(parents=True, exist_ok=True)
    inputs = {}

    def read(relative):
        inputs[str(relative)] = digest(source / relative)
        return (source / relative).read_text()

    def write(relative, text):
        inputs.setdefault(str(relative), digest(source / relative))
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    shop_text = read(SHOP)
    products = yaml.safe_load(shop_text)['Items']
    actual = {key for key, value in products.items()
              if str(value.get('Item', {}).get('Data', {}).get('ID', '')).split(':')[-1].endswith('_seeds')}
    if actual != set(seed_ids):
        raise ValueError(f'unexpected shop seed set: {sorted(actual)}')
    for seed in seed_ids:
        item = products[seed]['Item']
        if item['Provider'] not in ('ce', 'craftengine', 'nexo') or item['Data']['ID'] not in (seed, 'ce:' + seed):
            raise ValueError(f'unexpected seed item: {seed} {item}')

    def shop_change(path, value):
        if len(path) >= 4 and path[:1] == ('Items',) and path[1] in seed_ids:
            if path[2:] == ('Item', 'Provider'): return 'craftengine'
            if path[2:] == ('Item', 'Data', 'ID'): return 'ce:' + path[1]
        return None

    updated, shop_changes = rewrite(shop_text, shop_change)
    if updated != shop_text: write(SHOP, updated)

    # Tomato already has a native CE definition and keeps its existing art/name.
    tomato = yaml.safe_load(read(PACK / 'configuration/items/tomato.yml'))['items']
    if 'tomato_seeds' in seed_ids and 'ce:tomato_seeds' not in tomato:
        raise ValueError('existing ce:tomato_seeds definition is required')
    definitions, names, crop_changes = {}, {}, {}
    for seed in seed_ids:
        crop = seed.removesuffix('_seeds')
        crop_path = Path('Allium/harvest/crops') / (crop + '.yml')
        text = read(crop_path)
        if yaml.safe_load(text)['seed']['item'] not in ('nexo:' + seed, 'ce:' + seed):
            raise ValueError('unexpected planting item in ' + str(crop_path))
        updated, count = rewrite(text, lambda path, value:
            'ce:' + seed if value == 'nexo:' + seed and path[-1] == 'item' else None)
        crop_changes[crop] = count
        if count: write(crop_path, updated)
        if seed == 'tomato_seeds': continue

        nexo = yaml.safe_load(read(Path('Nexo/items/sf/crops') / (crop + '.yml')))[seed]
        namespace, model_name = nexo['Pack']['model'].split(':', 1)
        model_path = Path('Nexo/pack/assets') / namespace / 'models' / (model_name + '.json')
        model = json.loads(read(model_path))
        # All twenty SF seeds are simple sprites. Fail rather than discard any
        # newly authored geometry, extra layers or transforms on a future rerun.
        texture = model.get('textures', {}).get('layer0')
        if (model.get('parent') not in ('item/generated', 'minecraft:item/generated')
                or set(model) - {'parent', 'textures'}
                or set(model.get('textures', {})) - {'layer0', 'particle'}
                or model.get('textures', {}).get('particle', texture) != texture
                or not texture or ':' not in texture or nexo['material'] != 'PAPER'):
            raise ValueError('seed is no longer a simple PAPER sprite: ' + seed)
        ns, texture_name = texture.split(':', 1)
        original = Path('Nexo/pack/assets') / ns / 'textures' / (texture_name + '.png')
        target = PACK / 'resourcepack/assets/ce/textures/item/crops/seeds' / (seed + '.png')
        for suffix in ('', '.mcmeta'):
            src = source / (str(original) + suffix)
            if suffix and not src.exists(): continue
            dst = output / (str(target) + suffix)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
            inputs[str(original) + suffix] = digest(src)
            inputs[str(target) + suffix] = digest(source / (str(target) + suffix))
        definitions['ce:' + seed] = {
            'data': {'item_name': '<!i><lang:item.ce.' + seed + '>'},
            'texture': 'ce:item/crops/seeds/' + seed}
        names['item.ce.' + seed] = nexo['itemname']

    write(SEEDS, '# Generated by import_nexo_seeds.py; tomato is defined separately.\n'
          + yaml.safe_dump({'items': definitions}, sort_keys=False))
    lang_text = read(LANG)
    langs = yaml.safe_load(lang_text)
    english = langs['lang#items']['en_us']
    missing = {key: value for key, value in names.items() if key not in english}
    for key, value in names.items():
        if key in english and english[key] != value:
            raise ValueError('existing seed translation differs: ' + key)
    if missing:
        # Insert into the existing mapping without changing unrelated names.
        root = yaml.compose(lang_text)
        mapping = dict((k.value, v) for k, v in root.value)['lang#items']
        en = dict((k.value, v) for k, v in mapping.value)['en_us']
        last = en.value[-1][1]
        end = lang_text.find('\n', last.end_mark.index)
        end = len(lang_text) if end < 0 else end + 1
        additions = ''.join(' ' * en.start_mark.column + line + '\n'
                            for line in yaml.safe_dump(missing, sort_keys=False).splitlines())
        lang_text = lang_text[:end] + additions + lang_text[end:]
    write(LANG, lang_text)
    manifest = {'native_seed_ids': ['ce:' + key for key in seed_ids],
                'new_seed_definitions': len(definitions), 'shop_scalar_changes': shop_changes,
                'crop_scalar_changes': crop_changes, 'input_sha256': inputs,
                'output_sha256': {str(p.relative_to(output)): digest(p)
                                  for p in sorted(output.rglob('*')) if p.is_file()}}
    (output / 'seed-migration.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plugins', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = migrate(args.plugins, args.output)
    print(f"Staged {len(report['native_seed_ids'])} native seeds; "
          f"{report['new_seed_definitions']} definitions; "
          f"{sum(report['crop_scalar_changes'].values())} crop seed references.")
