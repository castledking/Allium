#!/usr/bin/env python3
"""Stage Jade names/icons and remove legacy block carriers from CE crop stages.

Input is a current plugins folder. Output contains only CE stage definitions
and icon art plus crop YAML with only carrier entries removed. Seed/drop
references, growth settings and all other crop data stay untouched.
"""
import argparse
import json
from pathlib import Path
import yaml
from import_nexo_crops import Assets, crop_hud_items, nexo_sections, remove_crop_carriers


def stage_hud(source, output):
    source, output = Path(source).resolve(), Path(output).resolve()
    if output == source or source in output.parents:
        raise ValueError('output must be outside the source plugins folder')
    if output.exists() and any(output.iterdir()):
        raise ValueError('output must be empty')
    pack = Path('CraftEngine/resources/allium_assets')
    path = pack / 'configuration/items/crop_stages.yml'
    items = yaml.safe_load((source / path).read_text())['items']
    crops, carriers = [], 0
    for crop_file in sorted((source / 'Allium/harvest/crops').glob('*.yml')):
        original = crop_file.read_text()
        updated, count = remove_crop_carriers(original)
        crops.append(yaml.safe_load(updated))
        carriers += count
        if count:
            destination = output / crop_file.relative_to(source)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(updated)
    items, icons = crop_hud_items(Assets(source, output), nexo_sections(source), crops, items)
    target = output / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('# Plant displays with plain crop names and growth-stage GUI icons.\n' +
                      yaml.safe_dump({'items': items}, sort_keys=False))
    lang_path = pack / 'configuration/langs/en_us.yml'
    lang_text = (source / lang_path).read_text()
    langs = yaml.safe_load(lang_text)
    english = langs['lang#items']['en_us']
    if english.get('jade.modName.ce') != 'Crops':
        english['jade.modName.ce'] = 'Crops'
        destination = output / lang_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(yaml.safe_dump(langs, sort_keys=False, allow_unicode=True))
    result = {'ce_stage_items': len(items), 'crop_hud_icons': icons, 'carrier_stages_removed': carriers}
    (output / 'crop-hud.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plugins', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(stage_hud(args.plugins, args.output), indent=2))
