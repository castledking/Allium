import copy
from pathlib import Path
import tempfile
import unittest
import yaml
from import_nexo_crops import Assets, element_vertices, ground_vertices, context_vertices, gui_transform, normalize_ground, migrate, remove_crop_carriers


class CropAtlasTest(unittest.TestCase):
    def test_crop_with_vanilla_log_uses_one_atlas_including_inherited_textures(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            source, output = Path(tmp)/'source', Path(tmp)/'output'
            assets = source/'Nexo/pack/assets/sf'
            (assets/'models/crops').mkdir(parents=True)
            (assets/'textures/crops').mkdir(parents=True)
            (assets/'models/crops/base.json').write_text(json.dumps({
                'textures': {'plant': 'sf:crops/plant'},
                'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16],
                              'faces': {'up': {'texture': '#plant'}}}]}))
            (assets/'models/crops/pitaya.json').write_text(json.dumps({
                'parent': 'sf:crops/base', 'textures': {'log': 'minecraft:block/spruce_log'}}))
            (assets/'textures/crops/plant.png').write_bytes(b'unchanged pixels')
            (assets/'textures/crops/plant.png.mcmeta').write_text('{"animation":{"frametime":4}}')
            importer = Assets(source, output)
            model, _ = importer.copy_model('sf:crops/pitaya')
            result = json.loads((importer.output/'models/item/crops/pitaya.json').read_text())
            self.assertEqual('ce:block/crops/plant', result['textures'].get('plant'))
            self.assertEqual('minecraft:block/spruce_log', result['textures']['log'])
            self.assertEqual(b'unchanged pixels', (importer.output/'textures/block/crops/plant.png').read_bytes())
            self.assertEqual('{"animation":{"frametime":4}}',
                             (importer.output/'textures/block/crops/plant.png.mcmeta').read_text())
            parent = json.loads((importer.output/'models/item/crops/base.json').read_text())
            self.assertEqual('ce:item/crops/plant', parent['textures']['plant'])


class StageAlignmentTest(unittest.TestCase):
    def test_stage_gui_preview_fits_and_keeps_the_ground_transform(self):
        model = {'elements': [{'from': [-8, -6, 0], 'to': [24, 72, 16], 'faces': {'up': {}}}],
                 'display': {'ground': {'translation': [0, 8, 0], 'scale': [.65] * 3}}}
        original = copy.deepcopy(model)
        model['display']['gui'] = gui_transform(model)
        vertices = list(context_vertices(model, 'gui'))
        for axis in range(3):
            low, high = min(p[axis] for p in vertices), max(p[axis] for p in vertices)
            self.assertAlmostEqual(0, (low + high) / 2, places=6)
            self.assertLessEqual(high - low, .875001)
        self.assertEqual(original['display']['ground'], model['display']['ground'])

    def test_model_only_conversion_preserves_seeds_and_foreign_block_stages(self):
        text = '''id: tomato
seed: {item: ce:tomato_seeds}
paths:
  entries:
    normal:
      stages:
      - {model: 'ce:tomato_stage_1', block: 'ce:tomato_crop', duration: 30m}
      - {model: 'ce:tomato_stage_2', block: 'minecraft:stone', duration: 45m}
'''
        result, count = remove_crop_carriers(text)
        expected = yaml.safe_load(text)
        expected['paths']['entries']['normal']['stages'][0].pop('block')
        self.assertEqual(1, count)
        self.assertEqual(expected, yaml.safe_load(result))

    def test_origin_scale_and_ground_translation_are_measured_together(self):
        for bottom, scale, translation in [(0, .65, 4.5), (-6, 1, 0), (.1, 1, 0), (0, .25, 3)]:
            model = {'elements': [{'from': [0, bottom, 0], 'to': [16, 16, 16], 'faces': {'up': {}}}],
                     'display': {'ground': {'translation': [0, translation, 0], 'scale': [scale]*3}}}
            normalize_ground(model, copy.deepcopy(model))
            self.assertAlmostEqual(0, min(v[1] for v in ground_vertices(model)))
            self.assertEqual([scale]*3, model['display']['ground']['scale'])

    def test_rotations_are_applied_before_measuring_the_base(self):
        element = {'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {'up': {}},
                   'rotation': {'origin': [8, 8, 8], 'axis': 'z', 'angle': 45}}
        self.assertAlmostEqual(8-8*2**.5, min(p[1] for p in element_vertices(element)))
        model = {'elements': [element]}
        normalize_ground(model, copy.deepcopy(model))
        self.assertAlmostEqual(0, min(v[1] for v in ground_vertices(model)))

    def test_migration_copies_inherited_textures_and_preserves_produce(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, output = Path(tmp)/'source', Path(tmp)/'output'
            def write(name, content):
                p = source/name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(content)
            write('Nexo/items/sf/crops/tomato.yml', '''tomato_seeds:
  material: PAPER
  Pack: {model: 'sf:crops/seeds'}
  Mechanics:
    furniture: {item: tomato_stage_1}
tomato:
  material: COOKIE
  Pack: {model: 'sf:crops/tomato'}
tomato_stage_1:
  material: PAPER
  Pack: {model: 'sf:crops/stage_1'}
gigantic_tomato:
  material: PAPER
  Pack: {model: 'sf:crops/giant'}''')  # No final newline: do not retain a trailing model key.
            write('Allium/harvest/crops/tomato.yml', '''id: tomato
display-name: '<red>Tomato'
seed: {item: 'nexo:tomato_seeds'}
paths:
  entries:
    normal:
      stages:
      - block: ce:crop
        model: nexo:tomato_stage_1
      - model: ce:gigantic_tomato
      harvest: {item: 'ce:gigantic_tomato'}
      mature-harvest:
        primary:
          entries:
            regular:
              drops: [{item: 'ce:tomato', amount: 1}]
''')
            write('CraftEngine/resources/allium_assets/configuration/items/tomato.yml', '''items:
  ce:tomato_stage_1: {data: {item_name: 'Tomato Ripe'}}
  ce:gigantic_tomato: {data: {item_name: 'Gigantic Tomato'}}
''')
            write('Nexo/pack/assets/sf/models/crops/base.json', '''{"textures":{"0":"sf:crops/stage"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#0"}}}]}''')
            write('Nexo/pack/assets/sf/models/crops/stage_1.json', '{"parent":"sf:crops/base"}')
            write('Nexo/pack/assets/sf/models/crops/giant.json', '{"parent":"sf:crops/base"}')
            write('Nexo/pack/assets/sf/models/crops/tomato.json', '{"parent":"item/generated","textures":{"layer0":"sf:crops/stage"}}')
            write('Nexo/pack/assets/sf/textures/crops/stage.png', 'fixture bytes copied unchanged')
            result = migrate(source, output)
            crop = yaml.safe_load((output/'Allium/harvest/crops/tomato.yml').read_text())
            self.assertEqual('nexo:tomato_seeds', crop['seed']['item'])
            path = crop['paths']['entries']['normal']
            self.assertNotIn('block', path['stages'][0])
            self.assertEqual('ce:gigantic_tomato', path['harvest']['item'])
            self.assertEqual('ce:gigantic_tomato_stage', path['stages'][1]['model'])
            ce = output/'CraftEngine/resources/allium_assets'
            stages = yaml.safe_load((ce/'configuration/items/crop_stages.yml').read_text())['items']
            for item_id, stage in stages.items():
                self.assertEqual('<!i>Tomato', stage['data']['item_name'])
                self.assertEqual(item_id, stage['data']['nbt']['$jade:stack']['id'])
                self.assertEqual('minecraft:display_context', stage['model']['property'])
                gui = stage['model']['cases'][0]
                self.assertEqual('gui', gui['when'])
                self.assertEqual('ce:item/crop_stage_icons/' + item_id.split(':')[1], gui['model']['path'])
                self.assertNotEqual(gui['model']['path'], stage['model']['fallback']['path'])
                preview = __import__('json').loads((ce/'resourcepack/assets/ce/models'/
                    (gui['model']['path'].split(':')[1] + '.json')).read_text())
                self.assertEqual(stage['model']['fallback']['path'], preview['parent'])
                self.assertIn('gui', preview['display'])
            items = yaml.safe_load((ce/'configuration/items/tomato.yml').read_text())['items']
            self.assertEqual({'ce:gigantic_tomato': {'data': {'item_name': 'Gigantic Tomato'}}}, items)
            items = yaml.safe_load((output/'Nexo/items/sf/crops/tomato.yml').read_text())
            self.assertEqual('sf:crops/seeds', items['tomato_seeds']['Pack']['model'])
            self.assertNotIn('Mechanics', items['tomato_seeds'])
            self.assertNotIn('tomato_stage_1', items)
            self.assertEqual('fixture bytes copied unchanged',
                (ce/'resourcepack/assets/ce/textures/item/crops/stage.png').read_text())
            self.assertNotIn('Nexo/pack/assets/sf/models/crops/base.json', result['nexo_asset_paths_to_archive'])
            self.assertIn('Nexo/pack/assets/sf/models/crops/stage_1.json', result['nexo_asset_paths_to_archive'])


if __name__ == '__main__': unittest.main()
