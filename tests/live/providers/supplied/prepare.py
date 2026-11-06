#!/usr/bin/env python3
"""Prepare a fresh owned fixture from explicit artifact inputs; never copy live data."""
import json
import shutil
import sys
from pathlib import Path
import yaml

spec = json.loads(Path(sys.argv[1]).read_text())
lane = Path(spec["directory"]).resolve()
if not str(lane).startswith("/tmp/allium-s05-") or "/supplied/runs/" not in str(lane):
    raise ValueError("Use a fresh owned /tmp/allium-s05-YYYYMMDD/supplied/runs/ lane")
if spec["port"] not in (25621, 25622) or lane.exists():
    raise ValueError("Use a free reserved port and a new directory")
plugins = lane / "plugins"
(plugins / "Oraxen/items").mkdir(parents=True)
for key, name in [("allium", "Allium.jar"), ("oraxen", "Oraxen.jar"), ("oraxen_fixture", "OraxenGate.jar")]:
    shutil.copy2(spec[key], plugins / name)
if spec.get("library_cache"):
    shutil.copytree(spec["library_cache"], lane / "libraries")
settings = yaml.safe_load(Path(spec["oraxen_settings"]).read_text())
settings["Plugin"]["keep_this_up_to_date"] = False
settings["Plugin"]["auto_update_paper_config"] = False
settings["Plugin"]["generation"] = {"default_assets": False, "default_configs": False}
settings["Pack"]["generation"]["generate"] = False
settings["Pack"]["upload"]["enabled"] = False
settings["Pack"]["dispatch"].update({"send_pack": False, "send_on_reload": False, "mandatory": False})
(plugins / "Oraxen/settings.yml").write_text(yaml.safe_dump(settings, sort_keys=False))
(plugins / "Oraxen/items/allium_gate.yml").write_text("Gate_Oraxen:\n  material: PAPER\n  itemname: Allium Oraxen Gate\n")
if spec["combination"]:
    template = Path(spec["native_template"]).resolve()
    if not (str(template).startswith("/tmp/allium-") or str(template).startswith("/mnt/storage/.allium-validation/")):
        raise ValueError("Native settings template must be an owned disposable fixture")
    for key, name in [("nexo", "Nexo.jar"), ("craftengine", "CraftEngine.jar"), ("rpm", "ResourcePackManager.jar"), ("provider_fixture", "ProviderGate.jar")]:
        shutil.copy2(spec[key], plugins / name)
    for plugin, config in [("Nexo", "settings.yml"), ("CraftEngine", "config.yml"), ("ResourcePackManager", "config.yml")]:
        target = plugins / plugin
        target.mkdir()
        shutil.copy2(template / "plugins" / plugin / config, target / config)
        libs = template / "plugins" / plugin / "libs"
        if libs.exists():
            shutil.copytree(libs, target / "libs")
    (plugins / "Nexo/converter.yml").write_text("oraxenConverter:\n  convertItems: false\n  convertResourcePack: false\n  convertSettings: false\n  convertFurnitureOnLoad: false\n")
    (plugins / "Nexo/items").mkdir()
    (plugins / "Nexo/items/allium_gate.yml").write_text("gate_wand:\n  material: PAPER\n  itemname: Allium Nexo Gate\n")
    pack = plugins / "CraftEngine/resources/allium_gate"
    (pack / "configuration").mkdir(parents=True)
    (pack / "pack.yml").write_text("namespace: sf_ores\nauthor: AlliumGate\nversion: 1.0\n")
    (pack / "configuration/items.yml").write_text("items:\n  sf_ores:silver_ingot:\n    material: iron_ingot\n    updater:\n      '1':\n        - type: reset\n          keep_components: [minecraft:custom_data]\n          keep_tags: [PublicBukkitValues]\n")
(lane / "eula.txt").write_text("eula=true\n")
(lane / "server.properties").write_text(
    f"server-ip=127.0.0.1\nserver-port={spec['port']}\nonline-mode=false\nenforce-secure-profile=false\n"
    "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\n"
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":2}],"biome":"minecraft:plains"}\n'
    "allow-nether=false\n")
print(lane)
