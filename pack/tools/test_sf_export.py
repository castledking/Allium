"""Regression checks for the local SF export; uses temporary copies of project art only."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import sf_export


class SfExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = json.loads((sf_export.REPO / 'pack/manifest.json').read_text())
        png = next((sf_export.REPO / 'pack/assets').rglob('*.png')).read_bytes()
        for tool, sources in sf_export.SOURCES.items():
            if tool == 'lazy_axe':
                continue
            for relative in sources.values():
                source = self.root / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_bytes(png)

    def test_default_export_never_reads_or_enables_the_colliding_lazy_axe(self):
        derived, provenance = sf_export.derive(self.manifest, self.root, self.root / 'output')
        axe = next(t for t in derived['tools'] if t['id'] == 'lazy_axe')
        self.assertEqual('planned', axe['status'])
        self.assertNotIn('texture', axe['states'][0])
        self.assertNotIn('lazy_axe', {p['tool'] for p in provenance})
        self.assertEqual('planned', next(t for t in self.manifest['tools'] if t['id'] == 'phantom_obliterator')['status'])

    def test_explicit_opt_in_includes_the_lazy_axe_texture_with_server_only_provenance(self):
        source = self.root / sf_export.SOURCES['lazy_axe']['default']
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(next((sf_export.REPO / 'pack/assets').rglob('*.png')).read_bytes())
        derived, provenance = sf_export.derive(self.manifest, self.root, self.root / 'output', True)
        self.assertEqual('ready', next(t for t in derived['tools'] if t['id'] == 'lazy_axe')['status'])
        texture = next(t for t in derived['textures'] if t['id'] == 'allium:item/lazy_axe/default')
        self.assertFalse(texture['distributable'])
        self.assertEqual(source.read_bytes(), (self.root / 'output' / texture['file']).read_bytes())
        self.assertEqual(1, len([p for p in provenance if p['tool'] == 'lazy_axe']))

    def test_missing_client_input_fails_before_touching_the_existing_export(self):
        with tempfile.TemporaryDirectory(dir=sf_export.REPO / 'build') as output:
            marker = Path(output) / 'keep.txt'
            marker.write_text('existing export')
            clients = self.root / 'clients'
            clients.mkdir()
            (clients / 'client-1.20.1.jar').touch()
            result = subprocess.run([sys.executable, str(Path(sf_export.__file__)), '--plugins', str(self.root),
                                     '--out', output, '--clients', str(clients)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('missing required client input', result.stderr)
            self.assertEqual('existing export', marker.read_text())


if __name__ == '__main__':
    unittest.main()
