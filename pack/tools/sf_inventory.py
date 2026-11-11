#!/usr/bin/env python3
"""Read-only inventory of SF's custom-item inputs, as migration input for the core pack.

Reads (never writes) under the SF plugins directory:
  Nexo/items/sf/items.yml                     item definitions (duplicate keys are recorded)
  Nexo/pack/assets/sf/                        Nexo pack assets (core-tool textures hashed)
  ResourcePackManager/mixer/*.zip             the Allium pack and other packs' carrier overrides

    python3 pack/tools/sf_inventory.py --plugins "$SF" --json out.json --markdown out.md

Requires PyYAML. Bukkit's YAML loader keeps the last value of a duplicated key, so the normalized
name is the last itemname; every duplicate is reported.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
CARRIERS = ['golden_hoe', 'iron_shovel', 'phantom_membrane', 'blaze_rod', 'name_tag', 'netherite_axe']
# Legacy SF Nexo ids that are Allium core tools, with the core state each one corresponds to.
CORE_MAPPING = {
    'mob_disarmer': ('mob_disarmer', 'full'),
    'mob_disarmer_2-3': ('mob_disarmer', 'two_thirds'),
    'mob_disarmer_1-3': ('mob_disarmer', 'one_third'),
    'mob_disarmer_0-3': ('mob_disarmer', 'empty'),
    'phantom_obliterator': ('phantom_obliterator', 'full (planned)'),
    'phantom_obliterator_2-3': ('phantom_obliterator', 'two_thirds (planned)'),
    'phantom_obliterator_1-3': ('phantom_obliterator', 'one_third (planned)'),
    'phantom_obliterator_0-3': ('phantom_obliterator', 'empty (planned)'),
    'handcuffs': ('handcuffs', 'default (planned)'),
    'handcuffs_restrained': ('handcuffs', 'restraining (planned)'),
    'claim_handcuffs': ('handcuffs', 'claim variant (planned)'),
    'claim_handcuffs_restrained': ('handcuffs', 'claim restraining (planned)'),
}


class DuplicateTrackingLoader(yaml.SafeLoader):
    """SafeLoader that keeps the last value like Bukkit and records every duplicated key."""
    duplicates = []

    def construct_mapping(self, node, deep=False):
        seen = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            seen.setdefault(key, []).append((self.construct_object(value_node, deep=True), key_node.start_mark.line + 1))
        for key, values in seen.items():
            if len(values) > 1:
                DuplicateTrackingLoader.duplicates.append({'line': node.start_mark.line + 1, 'key': key,
                                                           'values': [{'value': v, 'line': ln} for v, ln in values]})
        return super().construct_mapping(node, deep=deep)


def mcmeta_on_26_3(packs, allium_jar):
    """Reads each mixer pack.mcmeta the way a 26.3 client does, through Allium's PackFormatRules."""
    if not allium_jar or not Path(allium_jar).exists():
        return 'skipped: build Allium first (build/libs/Allium.jar) to check pack.mcmeta files'
    import subprocess
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        files = []
        for p in packs:
            if isinstance(p.get('rawMcmeta'), str):
                f = Path(tmp) / (p['pack'] + '.mcmeta')
                f.write_text(p['rawMcmeta'])
                files.append(str(f))
        out = subprocess.run(['java', '-cp', str(allium_jar), 'codes.castled.allium.pack.CorePackMain', 'mcmeta', '97.1', *files],
                             capture_output=True, text=True, check=True).stdout
    results = {}
    for line in out.splitlines():
        r = json.loads(line)
        results[Path(r['file']).name[:-len('.mcmeta')]] = r
    for p in packs:
        r = results.get(p['pack'])
        if r:
            p['on26_3'] = {'compatible': r['compatible'], 'declared': r.get('declared'), 'errors': r['errors']}
        p.pop('rawMcmeta', None)
    return None


CARRIER_FILE = re.compile(r'^(?:(?P<prefix>.*?)/)?assets/minecraft/(?P<kind>models/item|items)/(?P<carrier>[a-z0-9_]+)\.json$')


def audit_sources(plugins):
    """Every SF pack source that can replace vanilla item files: (label, kind, path)."""
    sources = [('Nexo source pack', 'dir', plugins / 'Nexo/pack', ['assets'])]
    for extra in ['sf_cit_components', 'sf_optifine_26_2']:
        sources.append((f'Nexo {extra}', 'dir', plugins / 'Nexo/pack' / extra, None))
    external = plugins / 'Nexo/pack/external_packs'
    if external.is_dir():
        for child in sorted(external.iterdir()):
            sources.append((f'Nexo external pack {child.name}', 'zip' if child.suffix == '.zip' else 'dir', child, None))
    sources.append(('Nexo generated pack.zip', 'zip', plugins / 'Nexo/pack/pack.zip', None))
    ce = plugins / 'CraftEngine/resources'
    if ce.is_dir():
        for child in sorted(ce.iterdir()):
            sources.append((f'CraftEngine resources/{child.name}', 'dir', child, None))
    sources.append(('CraftEngine generated resource_pack.zip', 'zip', plugins / 'CraftEngine/generated/resource_pack.zip', None))
    mixer = plugins / 'ResourcePackManager/mixer'
    for z in sorted(mixer.glob('*.zip')):
        sources.append((f'RPM mixer {z.name}', 'zip', z, None))
    output = plugins / 'ResourcePackManager/output'
    for z in sorted(output.glob('*.zip')):
        sources.append((f'RPM merged output {z.name}', 'zip', z, None))
    return sources


def source_files(kind, path, subdirs):
    """(relative path, reader) pairs for a directory or zip source."""
    if kind == 'zip':
        archive = zipfile.ZipFile(path)
        return [(n, (lambda n=n: archive.read(n))) for n in archive.namelist() if not n.endswith('/')]
    out = []
    roots = [path / d for d in subdirs] if subdirs else [path]
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in files:
                full = Path(dirpath) / f
                out.append((str(full.relative_to(path)), (lambda full=full: full.read_bytes())))
    return out


def describe_dispatch(kind, doc, carrier, allocation):
    """What a replaced carrier file dispatches, and what that does to Allium's values on this carrier."""
    vanilla = {f'item/{carrier}', f'minecraft:item/{carrier}'}
    effects = []
    if kind == 'models/item':
        values = sorted({o.get('predicate', {}).get('custom_model_data') for o in doc.get('overrides', []) or []
                         if isinstance(o, dict) and 'custom_model_data' in o.get('predicate', {})})
        other = sum(1 for o in doc.get('overrides', []) or [] if isinstance(o, dict) and 'custom_model_data' not in o.get('predicate', {}))
        summary = f"overrides on custom_model_data {values}" + (f' (+{other} other predicates)' if other else '') if values or other else 'no overrides'
        if allocation and values:
            first, last = allocation
            inside = [v for v in values if first <= v <= last]
            if inside:
                effects.append(f'uses {inside}, inside Allium {first}..{last}')
            below = [v for v in values if v < first]
            if below and not any(v > last for v in values):
                effects.append(f'its highest override ({max(below)}) applies to every larger value, so Allium values would show its model '
                               'if this file replaced Allium\'s')
        return summary, effects
    model = doc.get('model', {}) if isinstance(doc, dict) else {}
    mtype = str(model.get('type', '')).replace('minecraft:', '')
    if mtype == 'range_dispatch':
        entries = [e for e in model.get('entries', []) or [] if isinstance(e, dict)]
        thresholds = sorted(e.get('threshold') for e in entries if isinstance(e.get('threshold'), (int, float)))
        top = max(entries, key=lambda e: e.get('threshold', float('-inf')), default=None)
        top_model = (top or {}).get('model', {}).get('model') if isinstance((top or {}).get('model'), dict) else None
        closed = top_model in vanilla
        summary = f"range_dispatch on {str(model.get('property', '')).replace('minecraft:', '')} thresholds {thresholds}" + \
                  ('' if closed else ' (open-ended: the highest entry is not vanilla)')
        if allocation and thresholds:
            first, last = allocation
            inside = [t for t in thresholds if first <= t <= last]
            if inside:
                effects.append(f'uses {inside}, inside Allium {first}..{last}')
            if not closed and max(thresholds) < first:
                effects.append(f'every value from {max(thresholds):g} up shows its top model, so Allium values {first}..{last} would too '
                               'if this file replaced Allium\'s')
        return summary, effects
    if mtype in ('select', 'condition'):
        return f"{mtype} on {str(model.get('property', '')).replace('minecraft:', '')}", effects
    if mtype == 'model':
        target = model.get('model')
        return f"single model {target}" + ('' if target in vanilla else ' (not vanilla)'), effects
    return f'{mtype or "unreadable"} item model', effects


def carrier_audit(plugins, manifest):
    allocations = {}
    for t in manifest['tools']:
        allocations[t['carrier']] = (t['id'], t['allocation']['first'], t['allocation']['last'], t['status'])
    hits, unreadable, scanned = [], [], []
    for label, kind, path, subdirs in audit_sources(plugins):
        if not path.exists():
            continue
        try:
            files = source_files(kind, path, subdirs)
        except (OSError, zipfile.BadZipFile) as e:
            unreadable.append({'source': label, 'reason': type(e).__name__})
            continue
        scanned.append(label)
        for rel, read in files:
            m = CARRIER_FILE.match(rel.replace(os.sep, '/'))
            if not m or m.group('carrier') not in allocations:
                continue
            tool, first, last, status = allocations[m.group('carrier')]
            try:
                doc = json.loads(read().decode('utf-8-sig'))
                summary, effects = describe_dispatch(m.group('kind'), doc, m.group('carrier'), (first, last))
            except (ValueError, OSError) as e:
                summary, effects = f'unparseable ({type(e).__name__})', []
            hits.append({'carrier': m.group('carrier'), 'tool': tool, 'status': status, 'source': label, 'file': rel,
                         'overlay': m.group('prefix'), 'kind': m.group('kind'), 'dispatch': summary, 'effects': effects})
    return {'carriers': {c: {'tool': v[0], 'allocation': [v[1], v[2]], 'status': v[3]} for c, v in allocations.items()},
            'hits': hits, 'unreadable': unreadable, 'scanned': scanned}



def strip_minimessage(text):
    return re.sub(r'<[^>]+>', '', text or '').strip()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def png_size(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return None
    return struct.unpack('>II', data[16:24])


def inventory_items(plugins):
    path = plugins / 'Nexo/items/sf/items.yml'
    DuplicateTrackingLoader.duplicates = []
    data = yaml.load(path.read_text(encoding='utf-8'), Loader=DuplicateTrackingLoader)
    dupes_by_item = {}
    for d in DuplicateTrackingLoader.duplicates:
        dupes_by_item.setdefault(d['line'], []).append(d)
    raw_lines = path.read_text(encoding='utf-8').splitlines()
    item_lines = {line.split(':', 1)[0]: i + 1 for i, line in enumerate(raw_lines) if line and not line.startswith((' ', '#')) and ':' in line}
    items = []
    for item_id, spec in data.items():
        spec = spec or {}
        pdc = {e.get('key'): e.get('value') for e in spec.get('PersistentData', []) or [] if isinstance(e, dict)}
        pack = spec.get('Pack', {}) or {}
        line = item_lines.get(item_id)
        dupes = [d for d in DuplicateTrackingLoader.duplicates if line and d['line'] == line + 1]
        name_dupes = next((d for d in dupes if d['key'] == 'itemname'), None)
        anomalies = []
        if 'nexo:id' in pdc and pdc['nexo:id'] != item_id:
            anomalies.append(f"PersistentData sets nexo:id to '{pdc['nexo:id']}', not '{item_id}'")
        if 'allium:custom_item_id' not in pdc and item_id in CORE_MAPPING:
            extra = [k for k in pdc if k not in ('nexo:id',)]
            anomalies.append('no allium:custom_item_id; other keys: ' + ', '.join(extra))
        elif pdc.get('allium:custom_item_id') not in (None, item_id):
            anomalies.append(f"allium:custom_item_id is '{pdc['allium:custom_item_id']}'")
        mapping = CORE_MAPPING.get(item_id)
        items.append({
            'id': item_id,
            'material': spec.get('material'),
            'itemname': spec.get('itemname'),
            'plainName': strip_minimessage(spec.get('itemname')),
            'duplicateItemname': [v['value'] for v in name_dupes['values']] if name_dupes else [],
            'otherDuplicateKeys': [d['key'] for d in dupes if d['key'] != 'itemname'],
            'customModelData': pack.get('custom_model_data'),
            'texture': pack.get('texture'),
            'model': pack.get('model'),
            'parentModel': pack.get('parent_model'),
            'persistentData': pdc,
            'alliumCoreTool': {'tool': mapping[0], 'state': mapping[1]} if mapping else None,
            'anomalies': anomalies,
        })
    # Same custom_model_data on the same material inside this file.
    seen = {}
    for item in items:
        if item['customModelData'] is not None:
            seen.setdefault((item['material'], item['customModelData']), []).append(item['id'])
    collisions = [{'material': k[0], 'customModelData': k[1], 'items': v} for k, v in seen.items() if len(v) > 1]
    return {'file': str(path), 'sha256': sha256(path.read_bytes()), 'items': items, 'cmdCollisions': collisions}


def inventory_assets(plugins, items):
    root = plugins / 'Nexo/pack/assets/sf'
    counts = {}
    for dirpath, _, files in os.walk(root):
        rel = Path(dirpath).relative_to(root)
        top = rel.parts[0] if rel.parts else '.'
        if files:
            counts[top] = counts.get(top, 0) + len(files)
    core = []
    for item in items:
        tex = item['texture']
        if not item['alliumCoreTool'] or not tex or not tex.startswith('sf:'):
            continue
        path = root / 'textures' / (tex.split(':', 1)[1] + '.png')
        entry = {'item': item['id'], 'texture': tex, 'path': '$SF/' + str(path.relative_to(plugins)), 'exists': path.exists()}
        if path.exists():
            data = path.read_bytes()
            entry.update({'sha256': sha256(data), 'size': png_size(data), 'bytes': len(data)})
        core.append(entry)
    missing = []
    for item in items:
        tex = item['texture']
        if tex and tex.startswith('sf:') and not (root / 'textures' / (tex.split(':', 1)[1] + '.png')).exists():
            missing.append({'item': item['id'], 'texture': tex})
    return {'root': str(root), 'fileCounts': counts, 'coreToolTextures': core, 'missingTextures': missing}


def inventory_mixer(plugins, manifest, allium_jar):
    mixer = plugins / 'ResourcePackManager/mixer'
    allocations = {t['carrier']: [] for t in manifest['tools']}
    for t in manifest['tools']:
        allocations[t['carrier']].append((t['id'], t['allocation']['first'], t['allocation']['last']))
    packs, carrier_hits, unreadable = [], [], []
    allium = None
    for z in sorted(mixer.glob('*.zip')):
        try:
            data = z.read_bytes()
            archive = zipfile.ZipFile(z)
        except (OSError, zipfile.BadZipFile) as e:
            unreadable.append({'pack': z.name, 'reason': type(e).__name__ + ': ' + str(e).split(':')[0]})
            continue
        names = archive.namelist()
        meta = None
        raw_meta = None
        if 'pack.mcmeta' in names:
            raw_meta = archive.read('pack.mcmeta').decode('utf-8-sig')
            try:
                meta = json.loads(raw_meta).get('pack')
            except ValueError:
                meta = 'unparseable'
        packs.append({'pack': z.name, 'sha1': hashlib.sha1(data).hexdigest(), 'entries': len(names), 'mcmeta': meta,
                      'rawMcmeta': raw_meta})
        for n in names:
            m = re.match(r'(?:([^/]+)/)?assets/minecraft/(models/item|items)/(%s)\.json$' % '|'.join(CARRIERS), n)
            if not m:
                continue
            doc = json.loads(archive.read(n).decode('utf-8-sig'))
            if m.group(2) == 'models/item':
                values = [o.get('predicate', {}).get('custom_model_data') for o in doc.get('overrides', [])]
            else:
                values = [e.get('threshold') for e in (doc.get('model', {}) or {}).get('entries', [])]
            values = [v for v in values if v is not None]
            hit = {'pack': z.name, 'file': n, 'carrier': m.group(3), 'values': values, 'kind': m.group(2)}
            closes = doc.get('model', {}).get('type', '').endswith('range_dispatch') if m.group(2) == 'items' else None
            conflicts = []
            for tool, first, last in allocations.get(m.group(3), []):
                for v in values:
                    if first <= v <= last:
                        conflicts.append(f'{v} is inside Allium {tool} {first}..{last}')
                if m.group(2) == 'items' and values and max(values) < first:
                    conflicts.append(f'range_dispatch from {max(values)} has no closing entry, so Allium {tool} values '
                                     f'{first}..{last} would render as this pack\'s model if its file wins')
                if m.group(2) == 'models/item' and values and max(values) < first:
                    conflicts.append(f'overrides end at {max(values)}; a merge keeps both, but if one file replaces the other '
                                     f'either Allium {tool} or this pack loses its {m.group(3)} models')
            hit['conflicts'] = conflicts
            hit['rangeDispatch'] = closes
            carrier_hits.append(hit)
        if z.name == 'Allium_resource_pack.zip':
            namespaces = {}
            for n in names:
                parts = n.split('/')
                if len(parts) > 2 and parts[0] == 'assets':
                    namespaces[parts[1]] = namespaces.get(parts[1], 0) + 1
            allium = {'pack': z.name, 'sha1': hashlib.sha1(data).hexdigest(), 'mcmeta': meta, 'namespaces': namespaces,
                      'coreToolAssets': [n for n in names if re.search(r'disarm|handcuff|obliterat|renamer', n)],
                      'classification': 'Reliques/Antiquities addon assets (fonts, item definitions, models, textures); '
                                        'no core-tool assets, so it is addon migration input, not core-pack input'}
    note = mcmeta_on_26_3(packs, allium_jar)
    return {'mixer': str(mixer), 'packs': packs, 'alliumPack': allium, 'carrierFiles': carrier_hits, 'unreadable': unreadable,
            'mcmetaNote': note}


def markdown(report):
    out = ['# SF inventory for the core pack (read-only migration input)', '',
           f"Generated by `pack/tools/sf_inventory.py` from `$SF` (the SF plugins directory) on {report['generatedAt']}. "
           'No SF file was modified, and no SF art is committed.', '',
           '## `Nexo/items/sf/items.yml`', '',
           f"sha256 `{report['items']['sha256']}`. Bukkit keeps the last value of a duplicated YAML key, so the "
           'normalized name is the last `itemname`; the first value is reported beside it.', '',
           '| Nexo id | Material | CMD | Normalized `itemname` (last wins) | Dropped duplicate | Texture or model | Allium core tool | Anomalies |',
           '| --- | --- | --- | --- | --- | --- | --- | --- |']
    for i in report['items']['items']:
        dropped = ', '.join(f'`{v}`' for v in i['duplicateItemname'][:-1]) or '-'
        core = f"{i['alliumCoreTool']['tool']} / {i['alliumCoreTool']['state']}" if i['alliumCoreTool'] else '-'
        tex = i['texture'] or i['model'] or '-'
        out.append(f"| `{i['id']}` | {i['material']} | {i['customModelData']} | `{i['itemname']}` | {dropped} | `{tex}` | {core} | "
                   f"{'; '.join(i['anomalies']) or '-'} |")
    collisions = report['items']['cmdCollisions']
    out += ['', 'Same material and `custom_model_data` in this file: ' +
            ('; '.join(f"{c['material']} {c['customModelData']}: {', '.join(c['items'])}" for c in collisions) if collisions else 'none') + '.', '']
    a = report['assets']
    out += ['## `Nexo/pack/assets/sf/`', '', 'File counts by top-level folder: ' +
            ', '.join(f'{k} {v}' for k, v in sorted(a['fileCounts'].items())) + '.', '',
            'Core-tool textures (server-only art; hashes only, never copied into the repo):', '',
            '| Nexo id | Texture | Size | sha256 |', '| --- | --- | --- | --- |']
    for t in a['coreToolTextures']:
        size = 'x'.join(map(str, t['size'])) if t.get('size') else ('missing' if not t['exists'] else '?')
        out.append(f"| `{t['item']}` | `{t['texture']}` | {size} | `{t.get('sha256', '-')}` |")
    out += ['', 'Textures referenced by `items.yml` but missing: ' +
            (', '.join(f"`{m['item']}` → `{m['texture']}`" for m in a['missingTextures']) or 'none') + '.', '']
    m = report['mixer']
    al = m['alliumPack']
    out += ['## ResourcePackManager mixer', '']
    if al:
        out += [f"`{al['pack']}` sha1 `{al['sha1']}`, namespaces " + ', '.join(f'{k} ({v})' for k, v in al['namespaces'].items()) +
                f". {al['classification']}. Core-tool assets in it: {len(al['coreToolAssets'])}.", '',
                f"Its `pack.mcmeta`: `{json.dumps(al['mcmeta'])}`.", '']
    out += ['How a 26.3 client (format 97.1) reads each mixer `pack.mcmeta` on its own, through Allium\'s '
            '`PackFormatRules` (transcribed from 26.3\'s `PackFormat`). These matter only when a pack is served by '
            'itself; a merger such as ResourcePackManager writes its own `pack.mcmeta`.', '']
    if m.get('mcmetaNote'):
        out += [m['mcmetaNote'], '']
    else:
        out += ['| Pack | Reads as | Problems |', '| --- | --- | --- |']
        for p in m['packs']:
            r = p.get('on26_3')
            if r:
                state = ('compatible' if r['compatible'] else
                         'rejected: pack.mcmeta error' if r.get('declared') == 'invalid' else
                         'outside its range: the client warns before loading')
                out.append(f"| {p['pack']} | {r.get('declared')} ({state}) | {'; '.join(r['errors']) or '-'} |")
        out.append('')
    if m['unreadable']:
        out += ['', 'Not inventoried (unreadable to this user, left untouched): ' +
                ', '.join(f"{u['pack']} ({u['reason']})" for u in m['unreadable']) + '.']
    out.append('')
    au = report['carrierAudit']
    out += ['## Carrier collision audit (input to the delivery design)', '',
            'Every SF pack source that also defines a vanilla model file (`models/item/<carrier>.json`) or item definition '
            '(`items/<carrier>.json`) for a core carrier. A pack that replaces one of these files replaces the whole file, so '
            'two packs defining it need a merge, or one loses its models. This records the overlap only; no fix is chosen here.', '',
            'Sources scanned: ' + '; '.join(au['scanned']) + '.', '']
    if au['unreadable']:
        out += ['Not readable to this user (left untouched, not audited): ' +
                ', '.join(f"{u['source']} ({u['reason']})" for u in au['unreadable']) + '.', '']
    for carrier, info in au['carriers'].items():
        rows = [h for h in au['hits'] if h['carrier'] == carrier]
        out += [f"### `{carrier}`: {info['tool']} ({info['status']}, Allium {info['allocation'][0]}..{info['allocation'][1]})", '']
        if not rows:
            out += ['No scanned SF source defines this carrier\'s model or item definition.', '']
            continue
        out += ['| Source | File | Dispatch | Effect on Allium\'s values |', '| --- | --- | --- | --- |']
        for h in rows:
            out.append(f"| {h['source']} | `{h['file']}` | {h['dispatch']} | {'; '.join(h['effects']) or 'no overlap'} |")
        out.append('')
    return '\n'.join(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plugins', default=os.environ.get('SF'), help='SF plugins directory (read-only); defaults to $SF')
    parser.add_argument('--manifest', type=Path, default=REPO / 'pack/manifest.json')
    parser.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar',
                        help='used to read each mixer pack.mcmeta with the 26.3 rules')
    parser.add_argument('--json', type=Path)
    parser.add_argument('--markdown', type=Path)
    args = parser.parse_args()
    if not args.plugins:
        parser.error('pass --plugins or set $SF')
    plugins = Path(args.plugins)
    manifest = json.loads(args.manifest.read_text())
    items = inventory_items(plugins)
    import datetime
    report = {
        'plugins': str(plugins),
        'generatedAt': datetime.date.today().isoformat(),
        'items': items,
        'assets': inventory_assets(plugins, items['items']),
        'mixer': inventory_mixer(plugins, manifest, args.allium_jar),
        'carrierAudit': carrier_audit(plugins, manifest),
    }
    if args.json:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    text = markdown(report)
    if args.markdown:
        args.markdown.write_text(text)
    else:
        sys.stdout.write(text)


if __name__ == '__main__':
    main()
