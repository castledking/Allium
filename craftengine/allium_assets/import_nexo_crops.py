#!/usr/bin/env python3
"""Stage a plant-stage-only Nexo -> CraftEngine migration; never edit live files.

Requires PyYAML. Input is a pre-migration snapshot of the plugins folder:
  import_nexo_crops.py /path/to/plugins --output /tmp/crop-migration

Seed, produce and loot IDs are preserved. Output includes replacement configs,
CE art and a list of unused Nexo stage assets to archive after deployment.
"""
import argparse
from copy import deepcopy
from itertools import product
import json
import math
from pathlib import Path
import re
import shutil
import yaml


def mapping(node):
    return {k.value: v for k, v in node.value}


def edit_text(text, edits):
    for a, b, replacement in sorted(edits, reverse=True):
        text = text[:a] + replacement + text[b:]
    return text


def remove_entry(text, key, value):
    end = value.end_mark.index
    line_start = text.rfind('\n', 0, end) + 1
    if not text[line_start:end].strip():
        end = line_start
    return (text.rfind('\n', 0, key.start_mark.index) + 1,
            end, '')


def replace_mapping(node, values):
    lines = yaml.safe_dump(values, sort_keys=False).rstrip().splitlines()
    return (node.start_mark.index, node.end_mark.index,
            ('\n' + ' ' * node.start_mark.column).join(lines)
            + '\n' + ' ' * node.end_mark.column)


def rotate(point, axis, degrees):
    x, y, z = point
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    if axis == 'x': return x, c*y-s*z, s*y+c*z
    if axis == 'y': return c*x+s*z, y, -s*x+c*z
    return c*x-s*y, s*x+c*y, z


def element_vertices(element):
    """Minecraft element rotations: one axis, or Euler XYZ (Rz Ry Rx)."""
    rotation = element.get('rotation', {})
    origin = rotation.get('origin', [8, 8, 8])
    for point in product(*zip(element['from'], element['to'])):
        point = tuple(p-o for p, o in zip(point, origin))
        if 'axis' in rotation:
            axis, angle = rotation['axis'], rotation.get('angle', 0)
            point = rotate(point, axis, angle)
            if rotation.get('rescale'):
                factor = 1 / math.cos(math.radians(angle))
                point = tuple(v if a == axis else v*factor for a, v in zip('xyz', point))
        else:
            for axis in 'xyz': point = rotate(point, axis, rotation.get(axis, 0))
        yield tuple(p+o for p, o in zip(point, origin))


def context_vertices(model, context):
    ground = model.get('display', {}).get(context, {})
    scale, translation = ground.get('scale', [1, 1, 1]), ground.get('translation', [0, 0, 0])
    angles = ground.get('rotation', [0, 0, 0])
    for element in model.get('elements', []):
        if not element.get('faces'): continue
        for point in element_vertices(element):
            point = tuple((p-8)*s for p, s in zip(point, scale))
            # ItemTransform uses Quaternionf.rotationXYZ (Rx Ry Rz).
            for axis, angle in reversed(list(zip('xyz', angles))):
                point = rotate(point, axis, angle)
            yield tuple((p+t)/16 for p, t in zip(point, translation))


def ground_vertices(model):
    return context_vertices(model, 'ground')


def gui_transform(effective):
    """Center each growth stage in its GUI icon without changing the world model."""
    measured = deepcopy(effective)
    gui = {'rotation': [30, 225, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]}
    measured.setdefault('display', {})['gui'] = gui
    vertices = list(context_vertices(measured, 'gui'))
    if not vertices: raise ValueError('stage icon has no geometry')
    low = [min(p[a] for p in vertices) for a in range(3)]
    high = [max(p[a] for p in vertices) for a in range(3)]
    scale = .875 / max(b-a for a, b in zip(low, high))
    gui['scale'] = [round(scale, 8)] * 3
    gui['translation'] = [round(-8 * (a+b) * scale, 8) for a, b in zip(low, high)]
    return gui


def effective_ce_model(assets, reference, seen=None):
    seen = set() if seen is None else seen
    if reference in seen: raise ValueError('cyclic CE model parent: ' + reference)
    seen.add(reference)
    namespace, name = reference.split(':', 1)
    relative = Path(namespace) / 'models' / (name + '.json')
    path = assets.output.parent / relative
    if not path.exists():
        path = assets.source.parents[2] / 'CraftEngine/resources/allium_assets/resourcepack/assets' / relative
    model = json.loads(path.read_text())
    parent = model.get('parent', '')
    if parent and ':' not in parent: parent = 'minecraft:' + parent
    result = effective_ce_model(assets, parent, seen) if parent and not parent.startswith('minecraft:') else {}
    for key, value in model.items():
        if key in ('textures', 'display'): result.setdefault(key, {}).update(value)
        else: result[key] = value
    return result


def normalize_ground(model, effective):
    """Keep authored scale/orientation, place the rendered base at zero."""
    ground = deepcopy(effective.get('display', {}).get('ground', {}))
    ground.setdefault('translation', [0, 0, 0])
    ground['translation'][1] = 0
    measured = deepcopy(effective)
    measured.setdefault('display', {})['ground'] = ground
    vertices = list(ground_vertices(measured))
    if not vertices: raise ValueError('stage model has no geometry')
    ground['translation'][1] = round(-16*min(v[1] for v in vertices), 8)
    model.setdefault('display', {})['ground'] = ground
    assert abs(min(v[1] for v in ground_vertices(measured))) < 1e-8
    return {'previous_base': round(min(v[1] for v in ground_vertices(effective)), 8),
            'translation': ground['translation'],
            'height': round(max(v[1] for v in ground_vertices(measured)), 8)}


class Assets:
    def __init__(self, source, output):
        self.source = source / 'Nexo/pack/assets'
        self.output = output / 'CraftEngine/resources/allium_assets/resourcepack/assets/ce'
        self.models, self.cache = set(), {}

    def path(self, reference, kind, suffix):
        namespace, name = reference.split(':', 1)
        return self.source / namespace / kind / (name + suffix)

    @staticmethod
    def ce_path(reference):
        namespace, name = reference.split(':', 1)
        return 'item/' + ('' if namespace == 'sf' else namespace + '/') + name

    def read(self, reference):
        if reference not in self.cache:
            self.cache[reference] = json.loads(self.path(reference, 'models', '.json').read_text())
        return deepcopy(self.cache[reference])

    def effective(self, reference, seen=None):
        seen = set() if seen is None else seen
        if reference in seen: raise ValueError('cyclic parent: ' + reference)
        seen.add(reference)
        model = self.read(reference)
        parent = model.get('parent')
        if parent and ':' not in parent:
            parent = 'minecraft:' + parent
        result = self.effective(parent, seen) if parent and not parent.startswith('minecraft:') else {}
        for key, value in model.items():
            if key in ('textures', 'display'): result.setdefault(key, {}).update(value)
            else: result[key] = value
        return result

    def copy_texture(self, reference, atlas=None):
        if reference.startswith(('#', 'minecraft:')): return reference
        if ':' not in reference: return 'minecraft:' + reference
        source = self.path(reference, 'textures', '.png')
        target_path = self.ce_path(reference)
        if atlas:
            target_path = atlas + '/' + target_path.removeprefix('item/')
        target = self.output / 'textures' / (target_path + '.png')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        if Path(str(source) + '.mcmeta').exists():
            shutil.copyfile(str(source) + '.mcmeta', str(target) + '.mcmeta')
        return 'ce:' + target_path

    def copy_model(self, reference, normalize=False, target_path=None):
        model = self.read(reference)
        parent = model.get('parent')
        if parent and ':' not in parent:
            parent = 'minecraft:' + parent
            model['parent'] = parent
        if reference not in self.models:
            self.models.add(reference)
            if parent and not parent.startswith('minecraft:'): self.copy_model(parent)
        if parent and not parent.startswith('minecraft:'):
            model['parent'] = 'ce:' + self.ce_path(parent)
        effective = self.effective(reference)
        # A model which uses vanilla block sprites (pitaya's supporting log)
        # must keep every face in the blocks atlas. Use physical block textures,
        # rather than an atlas overlay which another plugin can shadow. Inherited
        # custom textures need child overrides too; the parent may still be used
        # by an ordinary item model.
        block_atlas = (bool(parent and parent.startswith('minecraft:block/')) or
                       any(isinstance(texture, str) and texture.startswith('minecraft:block/')
                           for texture in effective.get('textures', {}).values()))
        if block_atlas:
            model['textures'] = deepcopy(effective.get('textures', {}))
        for key, texture in model.get('textures', {}).items():
            atlas = 'block' if block_atlas else None
            if isinstance(texture, str): model['textures'][key] = self.copy_texture(texture, atlas)
            else: model['textures'][key]['sprite'] = self.copy_texture(texture['sprite'], atlas)
        measurement = normalize_ground(model, self.effective(reference)) if normalize else None
        target_path = target_path or self.ce_path(reference)
        target = self.output / 'models' / (target_path + '.json')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(model, ensure_ascii=False, indent=2) + '\n')
        return 'ce:' + target_path, measurement


def stage_nodes(root):
    for path in mapping(mapping(mapping(root)['paths'])['entries']).values():
        stages = mapping(path).get('stages')
        if stages and isinstance(stages.value, list): yield from stages.value


def remove_crop_carriers(text):
    """Use the existing display/hitbox instead of the two legacy CE crop carriers."""
    carriers = {'ce:crop', 'ce:tomato_crop'}
    expected = deepcopy(yaml.safe_load(text))
    edits, count = [], 0
    for stage in stage_nodes(yaml.compose(text)):
        values = mapping(stage)
        if 'model' not in values or values.get('block') is None or values['block'].value not in carriers:
            continue
        if stage.flow_style:
            data = yaml.safe_load(text[stage.start_mark.index:stage.end_mark.index])
            data.pop('block')
            replacement = yaml.safe_dump(data, default_flow_style=True, sort_keys=False, width=100000).strip()
            edits.append((stage.start_mark.index, stage.end_mark.index, replacement))
        else:
            key = next(key for key, _ in stage.value if key.value == 'block')
            # Retain a leading list dash when block was the first stage key.
            line_start = text.rfind('\n', 0, key.start_mark.index) + 1
            prefix = text[line_start:key.start_mark.index]
            start = line_start + prefix.index('-') + 1 if prefix.strip() == '-' else line_start
            edits.append((start, values['block'].end_mark.index, ''))
        count += 1
    for path in expected['paths']['entries'].values():
        for stage in path.get('stages', []) or []:
            if stage.get('model') and stage.get('block') in carriers:
                stage.pop('block')
    updated = edit_text(text, edits)
    assert yaml.safe_load(updated) == expected, 'non-carrier crop settings changed'
    return updated, count


def nexo_sections(source):
    result = {}
    for path in sorted((source / 'Nexo/items/sf/crops').glob('*.yml')):
        text = path.read_text()
        data = yaml.safe_load(text)
        for key, value in yaml.compose(text).value:
            result[key.value] = (path, key, value, data[key.value])
    return result


def archive_candidates(assets, removed_models, kept_roots):
    """Retire art only if no remaining Nexo model or crop item needs it."""
    def visit(reference, output):
        if reference in output or ':' not in reference or reference.startswith('minecraft:'): return
        if not assets.path(reference, 'models', '.json').exists(): return
        output.add(reference)
        parent = assets.read(reference).get('parent')
        if parent: visit(parent, output)
    candidates, kept = set(), set()
    for reference in removed_models: visit(reference, candidates)
    # All other models are retained, including decorative assets.
    for path in assets.source.glob('*/models/**/*.json'):
        namespace = path.relative_to(assets.source).parts[0]
        name = path.relative_to(assets.source / namespace / 'models').with_suffix('').as_posix()
        reference = namespace + ':' + name
        if reference not in candidates or reference in kept_roots: visit(reference, kept)
    retired = candidates - kept
    needed_textures, retired_textures = set(), set()
    for reference in kept | retired:
        target = retired_textures if reference in retired else needed_textures
        for value in assets.read(reference).get('textures', {}).values():
            texture = value if isinstance(value, str) else value.get('sprite', '')
            if ':' in texture and not texture.startswith('minecraft:'): target.add(texture)
    paths = {assets.path(ref, 'models', '.json') for ref in retired}
    for texture in retired_textures - needed_textures:
        path = assets.path(texture, 'textures', '.png')
        if path.exists():
            paths.add(path)
            if Path(str(path) + '.mcmeta').exists(): paths.add(Path(str(path) + '.mcmeta'))
    return sorted(path.relative_to(assets.source.parents[2]).as_posix() for path in paths)


def crop_hud_items(assets, nexo, crops, items):
    """Give plant items a Jade identity and a fitted growth-stage GUI preview.

    The GROUND fallback remains the normalized stage model. The GUI child
    inherits that same stage geometry with a centered, fitted transform.
    """
    items, icons, owners = deepcopy(items), {}, {}
    for crop in crops:
        name = re.sub(r'<[^>]+>|&[0-9a-fklmnor]', '', crop.get('display-name', crop['id']), flags=re.I)
        for path in crop['paths']['entries'].values():
            for stage in path.get('stages', []) or []:
                item_id = stage.get('model')
                if item_id not in items:
                    continue
                if item_id in owners and owners[item_id] != crop['id']:
                    raise ValueError('stage shared by different crops: ' + item_id)
                owners[item_id] = crop['id']
                item = items[item_id]
                item.setdefault('data', {})['item_name'] = '<!i>' + name
                item['data'].setdefault('nbt', {})['$jade:stack'] = {'id': item_id}
                model = item['model']
                # Idempotent for repeated references and existing HUD models.
                if model.get('property') == 'minecraft:display_context':
                    model = model['fallback']
                icon = 'ce:item/crop_stage_icons/' + item_id.split(':', 1)[-1]
                if item_id not in icons:
                    effective = effective_ce_model(assets, model['path'])
                    preview = {'parent': model['path'], 'display': {'gui': gui_transform(effective)}}
                    target = assets.output / 'models' / (icon.split(':', 1)[-1] + '.json')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(json.dumps(preview, indent=2) + '\n')
                    icons[item_id] = icon
                gui_model = {'type': 'minecraft:model', 'path': icon}
                item['model'] = {
                    'type': 'minecraft:select', 'property': 'minecraft:display_context',
                    'cases': [{'when': 'gui', 'model': deepcopy(gui_model)}],
                    'fallback': deepcopy(model)}
    if set(owners) != set(items):
        raise ValueError('stage items without a crop: ' + ', '.join(sorted(set(items) - set(owners))))
    return items, icons


def migrate(source, output):
    source, output = Path(source), Path(output)
    if output == source or source in output.parents:
        raise ValueError('output must be outside the source plugins folder')
    if output.exists() and any(output.iterdir()):
        raise ValueError('output must be empty (avoid deploying stale stage files)')
    assets, nexo = Assets(source, output), nexo_sections(source)
    imported, measurements, stage_ids, count, crops = {}, {}, {}, 0, []
    for path in sorted((source / 'Allium/harvest/crops').glob('*.yml')):
        text = path.read_text()
        crop, root, edits = yaml.safe_load(text), yaml.compose(text), []
        for stage in stage_nodes(root):
            values = mapping(stage)
            model_node = values.get('model')
            if model_node is None: continue
            provider, ident = model_node.value.split(':', 1)
            if provider not in ('nexo', 'ce'): continue
            if ident not in nexo: raise ValueError('missing original Nexo stage: ' + ident)
            reference = nexo[ident][3]['Pack']['model']
            if ident not in imported:
                normal_stage = re.search(r'(?:stage|bush)_\d+$', ident)
                # Giant produce can also be a stage: keep its item/model
                # unchanged and create a dedicated plant display variant.
                stage_ids[ident] = ident if normal_stage else ident + '_stage'
                target_path = None if normal_stage else assets.ce_path(reference) + '_stage'
                ce_model, measured = assets.copy_model(reference, normalize=True, target_path=target_path)
                name = re.sub(r'<[^>]+>|&[0-9a-fklmnor]', '', crop.get('display-name', crop['id']), flags=re.I)
                imported[ident] = {'data': {'item_name': '<!i>' + name},
                                   'model': {'type': 'minecraft:model', 'path': ce_model}}
                measurements[ident] = measured
            edits.append((model_node.start_mark.index, model_node.end_mark.index, 'ce:' + stage_ids[ident]))
            old_visual = {k.value: yaml.safe_load(text[v.start_mark.index:v.end_mark.index])
                          for k, v in values['visual'].value} if 'visual' in values else {}
            visual = {'transform': 'GROUND', 'scale': old_visual.get('scale', 1.0),
                      'y-offset': 0.001, 'align-to-soil': True}
            if 'visual' in values: edits.append(replace_mapping(values['visual'], visual))
            else:
                position = text.index('\n', model_node.end_mark.index) + 1
                indent = ' ' * stage.start_mark.column
                replacement = indent + 'visual:\n' + ''.join(
                    indent + '  ' + line + '\n' for line in yaml.safe_dump(visual, sort_keys=False).splitlines())
                edits.append((position, position, replacement))
            count += 1
        rewritten = edit_text(text, edits)
        expected = deepcopy(crop)
        # The retired Nexo pack had empty placeholders for disabled quality
        # and gigantic paths. The crop loader still requires every declared
        # path to have stages, even when its weight is zero, so keep those
        # paths valid by reusing the normal CraftEngine-grown appearance.
        normal_stages = deepcopy(expected['paths']['entries'].get('normal', {}).get('stages', []))
        empty_paths = [p for p in expected['paths']['entries'].values() if not p.get('stages')]
        for p in empty_paths:
            p['stages'] = deepcopy(normal_stages)
        if empty_paths:
            lines = yaml.safe_dump(normal_stages, sort_keys=False, allow_unicode=True).rstrip().splitlines()
            def fill_empty_stages(match):
                indent = match.group(1)
                return indent + 'stages:\n' + '\n'.join(indent + '  ' + line for line in lines)
            rewritten, replacements = re.subn(r'(?m)^(\s*)stages:\s*\[\]\s*$', fill_empty_stages, rewritten)
            assert replacements == len(empty_paths), 'failed to materialize empty zero-weight paths: ' + str(path)
        for p in expected['paths']['entries'].values():
            for s in p.get('stages', []) or []:
                if s.get('model', '').split(':', 1)[-1] in imported:
                    ident = s['model'].split(':', 1)[-1]
                    s['model'] = 'ce:' + stage_ids[ident]
                    s['visual'] = {'transform': 'GROUND', 'scale': s.get('visual', {}).get('scale', 1.0),
                                   'y-offset': 0.001, 'align-to-soil': True}
        rewritten, _ = remove_crop_carriers(rewritten)
        for p in expected['paths']['entries'].values():
            for s in p.get('stages', []) or []:
                if s.get('model') and s.get('block') in {'ce:crop', 'ce:tomato_crop'}:
                    s.pop('block')
        assert yaml.safe_load(rewritten) == expected, 'non-stage crop settings changed: ' + str(path)
        target = output / 'Allium/harvest/crops' / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rewritten)
        crops.append(expected)
    pack = output / 'CraftEngine/resources/allium_assets'
    items_path = pack / 'configuration/items/crop_stages.yml'
    items_path.parent.mkdir(parents=True, exist_ok=True)
    hud_items, icons = crop_hud_items(assets, nexo, crops,
                                    {'ce:'+stage_ids[k]: v for k, v in sorted(imported.items())})
    items_path.write_text('# Plant displays with plain crop names and growth-stage GUI icons.\n' +
        yaml.safe_dump({'items': hud_items}, sort_keys=False))
    tomato = source / 'CraftEngine/resources/allium_assets/configuration/items/tomato.yml'
    text = tomato.read_text()
    edits = [remove_entry(text, k, v) for k, v in mapping(yaml.compose(text))['items'].value
             if k.value.removeprefix('ce:') in imported and re.search(r'(?:stage|bush)_\d+$', k.value)]
    (pack / 'configuration/items/tomato.yml').write_text(edit_text(text, edits).rstrip() + '\n')
    removable = {k for k in imported if re.search(r'(?:stage|bush)_\d+$', k)}
    removed_models = {nexo[k][3]['Pack']['model'] for k in removable}
    kept_models = {v[3].get('Pack', {}).get('model') for k, v in nexo.items() if k not in removable}
    for path in sorted(set(v[0] for v in nexo.values())):
        text = path.read_text()
        root, edits = yaml.compose(text), []
        for key, value in root.value:
            if key.value in removable:
                edits.append(remove_entry(text, key, value))
                continue
            mechanics = mapping(value).get('Mechanics')
            if mechanics:
                furniture = mapping(mechanics).get('furniture')
                data = nexo[key.value][3].get('Mechanics', {}).get('furniture', {})
                if furniture and (data.get('item') in removable or data.get('evolution', {}).get('next_stage') in removable):
                    # Allium now owns planting and growth; keep other mechanics.
                    target_key = 'Mechanics' if len(mechanics.value) == 1 else 'furniture'
                    parent = value if target_key == 'Mechanics' else mechanics
                    k, v = next((k, v) for k, v in parent.value if k.value == target_key)
                    edits.append(remove_entry(text, k, v))
        rewritten = edit_text(text, edits)
        data, original = yaml.safe_load(rewritten) or {}, yaml.safe_load(text)
        assert set(data) == set(original) - removable
        for ident, value in data.items():
            before, after = deepcopy(original[ident]), deepcopy(value)
            before.pop('Mechanics', None)
            after.pop('Mechanics', None)
            assert before == after, 'Nexo item data changed: ' + ident
        if rewritten != text:
            target = output / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(rewritten)
    archive = archive_candidates(assets, removed_models, kept_models)
    manifest = {'crop_files': len(list((output / 'Allium/harvest/crops').glob('*.yml'))),
                'stage_occurrences': count, 'ce_stage_items': len(imported),
                'nexo_stage_items_archived': sorted(removable),
                'nexo_asset_paths_to_archive': archive, 'model_alignment': measurements,
                'crop_hud_icons': icons}
    (output / 'migration.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('server_root', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = migrate(args.server_root.resolve(), args.output.resolve())
    print(json.dumps({k: v for k, v in result.items() if k not in
        ('model_alignment', 'nexo_asset_paths_to_archive', 'nexo_stage_items_archived')}, indent=2))
    print('Nexo assets to archive:', len(result['nexo_asset_paths_to_archive']))


if __name__ == '__main__': main()
