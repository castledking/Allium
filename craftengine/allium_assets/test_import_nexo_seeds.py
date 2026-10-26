import json
from pathlib import Path
import shutil
import tempfile
import unittest
import yaml

from import_nexo_seeds import LANG, PACK, SEEDS, SEED_IDS, SHOP, digest, migrate


class SeedMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.output = self.root / 'plugins', self.root / 'output'

        def write(relative, data):
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(yaml.safe_dump(data, sort_keys=False))
        self.write = write
        self.crops = {}
        products = {'berries': {'Item': {'Provider': 'nexo', 'Data': {'ID': 'blackberries'}}}}
        for seed in SEED_IDS:
            crop = seed.removesuffix('_seeds')
            products[seed] = {'Item': {'Provider': 'craftengine' if crop == 'banana' else 'nexo',
                                      'Data': {'ID': seed, 'Amount': 3}, 'CompareNBT': False},
                              'BuyPrice': 5.75, 'SellPrice': -1, 'Slot': 12}
            self.crops[crop] = {'seed': {'item': 'ce:' + seed if crop == 'tomato' else 'nexo:' + seed},
                                'display-name': crop.title(),
                                'stages': [{'model': 'ce:' + crop + '_stage_1', 'block': 'ce:crop',
                                            'visual': {'align-to-soil': True, 'y-offset': 0.001}}],
                                'drops': [{'item': 'nexo:' + crop}, {'item': 'nexo:' + seed}],
                                'commands': ['say nexo:' + seed]}
            write(Path('Allium/harvest/crops') / (crop + '.yml'), self.crops[crop])
            texture_name = crop + '_seed_bag' if crop in ('cotton', 'onion', 'soybean') else seed
            write(Path('Nexo/items/sf/crops') / (crop + '.yml'), {
                seed: {'material': 'PAPER', 'itemname': crop.title() + ' Seeds',
                       'Pack': {'model': 'sf:crops/' + crop + '/' + seed}}})
            model = self.source / 'Nexo/pack/assets/sf/models/crops' / crop / (seed + '.json')
            model.parent.mkdir(parents=True, exist_ok=True)
            texture = 'sf:crops/' + crop + '/' + texture_name
            model.write_text(json.dumps({'parent': 'item/generated',
                                         'textures': {'layer0': texture, 'particle': texture}}))
            image = self.source / 'Nexo/pack/assets/sf/textures/crops' / crop / (texture_name + '.png')
            image.parent.mkdir(parents=True, exist_ok=True)
            image.write_bytes(('art:' + texture_name).encode())
        self.products = products
        write(SHOP, {'Settings': {'Pages': 4}, 'Items': products})
        write(PACK / 'configuration/items/tomato.yml', {'items': {'ce:tomato_seeds': {'texture': 'ce:tomato'}}})
        write(LANG, {'lang#items': {'en_us': {'item.ce.crop': 'Crop', 'item.ce.tomato_seeds': 'Tomato Seeds'}}})
        write(Path('Allium/harvest/crops/weed.yml'), {'seed': {'item': 'nexo:weed_nugget'}})
        write(Path('Allium/harvest/crops/blackberry.yml'), {'seed': {'item': 'nexo:blackberries'}})

    def test_exact_twenty_seed_migration_preserves_other_values_and_inputs(self):
        before = {p.relative_to(self.source): digest(p) for p in self.source.rglob('*') if p.is_file()}
        manifest = migrate(self.source, self.output)
        self.assertEqual(20, len(manifest['native_seed_ids']))
        self.assertEqual(19, manifest['new_seed_definitions'])
        shop = yaml.safe_load((self.output / SHOP).read_text())
        for seed in SEED_IDS:
            self.products[seed]['Item']['Provider'] = 'craftengine'
            self.products[seed]['Item']['Data']['ID'] = 'ce:' + seed
        self.assertEqual(self.products, shop['Items'])
        for crop, expected in self.crops.items():
            expected['seed']['item'] = 'ce:' + crop + '_seeds'
            expected['drops'][1]['item'] = expected['seed']['item']
            result = yaml.safe_load((self.output / 'Allium/harvest/crops' / (crop + '.yml')).read_text())
            self.assertEqual(expected, result)
        self.assertFalse((self.output / 'Allium/harvest/crops/weed.yml').exists())
        self.assertFalse((self.output / 'Allium/harvest/crops/blackberry.yml').exists())
        self.assertFalse((self.output / 'Nexo').exists())
        self.assertEqual(before, {p.relative_to(self.source): digest(p) for p in self.source.rglob('*') if p.is_file()})
        lang = yaml.safe_load((self.output / LANG).read_text())['lang#items']['en_us']
        self.assertEqual('Crop', lang['item.ce.crop'])
        self.assertEqual('Onion Seeds', lang['item.ce.onion_seeds'])

    def test_bag_textures_follow_model_references_and_are_byte_identical(self):
        migrate(self.source, self.output)
        for crop in ('cotton', 'onion', 'soybean'):
            copied = self.output / PACK / 'resourcepack/assets/ce/textures/item/crops/seeds' / (crop + '_seeds.png')
            self.assertEqual(('art:' + crop + '_seed_bag').encode(), copied.read_bytes())

    def test_rerun_does_not_rewrite_migrated_shop_or_crop_files(self):
        migrate(self.source, self.output)
        for path in self.output.rglob('*'):
            if path.is_file() and path.name != 'seed-migration.json':
                target = self.source / path.relative_to(self.output)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        again = self.root / 'again'
        manifest = migrate(self.source, again)
        self.assertEqual(0, manifest['shop_scalar_changes'])
        self.assertEqual(0, sum(manifest['crop_scalar_changes'].values()))
        self.assertEqual((self.output / SEEDS).read_bytes(), (again / SEEDS).read_bytes())
        self.assertEqual((self.output / LANG).read_bytes(), (again / LANG).read_bytes())

    def test_unexpected_shop_seed_set_fails_closed(self):
        self.products.pop('banana_seeds')
        self.write(SHOP, {'Items': self.products})
        with self.assertRaisesRegex(ValueError, 'unexpected shop seed set'):
            migrate(self.source, self.output)

    def test_does_not_flatten_complex_models(self):
        path = self.source / 'Nexo/pack/assets/sf/models/crops/banana/banana_seeds.json'
        model = json.loads(path.read_text())
        model['display'] = {'gui': {'scale': [2, 2, 2]}}
        path.write_text(json.dumps(model))
        with self.assertRaisesRegex(ValueError, 'no longer a simple'):
            migrate(self.source, self.output)

    def test_refuses_stale_output_and_in_place_migration(self):
        migrate(self.source, self.output)
        with self.assertRaisesRegex(ValueError, 'empty'):
            migrate(self.source, self.output)
        with self.assertRaisesRegex(ValueError, 'outside'):
            migrate(self.source, self.source / 'output')


if __name__ == '__main__':
    unittest.main()
