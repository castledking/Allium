#!/usr/bin/env python3
"""S10 core tool carrier suppression live gate. Disposable lanes only.

Real stations do the work: hoppers feed a brewing stand, a furnace, a blast furnace, a smoker (and
a crafter on 1.21+); a fueled blast furnace and a fueled brewing stand already hold the item, as a
hotbar swap would put it. Each station has a twin with the plain item. The anvil, grindstone and
smithing table are real block menus the bot right-clicks open (vanilla has no smithing recipe for
the carriers, so the fixture registers one: golden hoe to netherite hoe); the crafting table and enchanting
table are real menus the server opens for it. The fixture puts items into a menu's input slots
(as a hotbar swap would); the server computes the result. On --full (Spigot 1.20.1, where the
protocol client can click) the bot then takes the anvil result, enchants on the table, and tries
to take the grindstone result. Exits nonzero on any failed check.
"""
import argparse, re, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from lanes import Gate, LANES, REPO  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s10-20261112/suppression'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--full', action='store_true', help='also take results with real clicks (the 1.20.1 protocol client)')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s10-\d{8}/suppression', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s10-YYYYMMDD/suppression root')
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
g = Gate(a.lane, run, a.allium_jar)
require, emit = g.require, g.emit
modern = a.lane != 'spigot-1.20.1'

lazy = (REPO / 'src/main/resources/tools/lazy_axe.yml').read_text().replace('\nenabled: false\n', '\nenabled: true\n', 1)
mob = (REPO / 'src/main/resources/tools/mob_disarmer.yml').read_text()
tools = {'lazy_axe.yml': lazy, 'mob_disarmer.yml': mob}
TOOLS = {'membrane-tool': 'allium:phantom_obliterator', 'hoe-tool': 'allium:mob_disarmer', 'axe-tool': 'allium:lazy_axe'}


def item(slot):
    return None if not slot or slot.get('empty') else slot


def stations():
    g.ask('sp-stations st', 'st')
    time.sleep(3.0)
    hop = g.ask('sp-observe-stations st-hoppers', 'st-hoppers')
    types = ['BREWING_STAND', 'FURNACE', 'BLAST_FURNACE', 'SMOKER'] + (['CRAFTER'] if modern else [])
    for t in types:
        tool, plain = hop[f'hopper:{t}:tool'], hop[f'hopper:{t}:plain']
        station_has_tool = any(x and x.get('tool') for x in tool['station'])
        hopper_has_tool = any(x and x.get('tool') for x in tool['hopper'])
        cancelled = [m for m in hop['moves'] if m['tool'] and m['destination'] == ('CRAFTER' if t == 'CRAFTER' else
                                                                                    {'BREWING_STAND': 'BREWING'}.get(t, t))]
        require(hopper_has_tool and not station_has_tool and cancelled and all(m['cancelled'] for m in cancelled),
                f'hopper -> {t}: the tool stays in the hopper; its {len(cancelled)} transfer attempt(s) were cancelled')
        moved = any(x for x in plain['station']) and not any(x for x in plain['hopper'])
        require(moved, f'hopper -> {t}: the plain item moved in')
    if modern:
        tool, plain = hop['craft:CRAFTER:tool']['station'], hop['craft:CRAFTER:plain']['station']
        require(tool[0] and tool[0].get('tool') == 'allium:mob_disarmer' and tool[1],
                'powered crafter: a Mob Disarmer and a golden hoe are not crafted together; both stay in the grid')
        require(not any(plain), f'powered crafter: two plain golden hoes are crafted (the grid emptied: {plain[:2]})')
    emit('waiting for the blast furnace (5 s) and the brewing stand (20 s) to finish a cycle')
    time.sleep(21.0)
    done = g.ask('sp-observe-stations st-done', 'st-done')
    tool, plain = done['smelt:BLAST_FURNACE:tool']['station'], done['smelt:BLAST_FURNACE:plain']['station']
    require(tool[0] and tool[0].get('tool') == 'allium:mob_disarmer' and not tool[2],
            f'blast furnace: the Mob Disarmer inside is never smelted (input {tool[0] and tool[0]["material"]}, output {tool[2]})')
    require(plain[2] and plain[2]['material'] == 'GOLD_NUGGET', f'blast furnace: the plain golden hoe smelted into {plain[2]}')
    tool, plain = done['brew:BREWING_STAND:tool']['station'], done['brew:BREWING_STAND:plain']['station']
    require(tool[3] and tool[3].get('tool') == 'allium:phantom_obliterator' and all(x and x.get('potion') == 'AWKWARD' for x in tool[:3]),
            f'brewing stand: the Phantom Obliterator is never brewed in: {[x and x.get("potion") for x in tool[:3]]}')
    require(not plain[3] and all(x and x.get('potion') == 'SLOW_FALLING' for x in plain[:3]),
            f'brewing stand: a plain membrane brews slow falling: {[x and x.get("potion") for x in plain[:3]]}')


def crafting():
    r = g.ask('sp-craft craft-tool hoe-tool hoe', 'craft-tool')
    require(r['type'] == 'WORKBENCH' and r['result'] is None, f'crafting table: a Mob Disarmer and a golden hoe make nothing ({r["result"]})')
    r = g.ask('sp-craft craft-plain hoe hoe', 'craft-plain')
    require(r['result'] == 'GOLDEN_HOE', f'crafting table: two plain golden hoes still repair-combine ({r["result"]})')


def open_block(name, material):
    pos = g.ask(f'sp-block {name} {material} 0 0', name)['pos']
    g.packet('useon {} {} {}'.format(*pos))
    time.sleep(1.0)
    v = g.ask(f'sp-view {name}-view', name + '-view')
    require(v['type'] in (material, 'SMITHING' if material == 'SMITHING_TABLE' else material),
            f'{material.lower()}: the bot right-clicked it open ({v["type"]})')
    return v


def anvil():
    open_block('anvil', 'ANVIL')
    v = g.ask('sp-menu anvil-material elytra-damaged membrane-tool', 'anvil-material')
    require(item(v['slot2']) is None, 'anvil: the Phantom Obliterator is refused as the material that repairs an elytra')
    v = g.ask('sp-menu anvil-plain elytra-damaged membrane', 'anvil-plain')
    require(item(v['slot2']) and v['slot2']['material'] == 'ELYTRA', 'anvil: a plain membrane still repairs the elytra')
    v = g.ask('sp-menu anvil-book axe-tool-damaged book-sharpness', 'anvil-book')
    r = item(v['slot2'])
    require(r and r['behavior'] == 'allium:lazy_axe' and r['enchants'].get('sharpness') == 3 and r['uuid'] == v['slot0']['uuid'],
            f'anvil: the Lazy Axe in its own (first) slot takes a book\'s Sharpness III and stays the same signed tool')
    v = g.ask('sp-menu anvil-repair axe-tool-damaged netherite-ingot', 'anvil-repair')
    r = item(v['slot2'])
    require(r and r['behavior'] == 'allium:lazy_axe' and r['damage'] < 500 and r['uuid'] == v['slot0']['uuid'],
            f'anvil: the Lazy Axe in its own slot is repaired by a netherite ingot (damage 500 -> {r and r["damage"]})')
    if a.full:
        g.cmd('dg la-level 30')
        uuid = v['slot0']['uuid']
        g.packet('wclick 2 1')
        time.sleep(1.0)
        inv = g.ask('sp-snapshot anvil-taken', 'anvil-taken')
        got = [s for k, s in inv.items() if isinstance(s, dict) and s.get('uuid') == uuid]
        require(got and got[0]['behavior'] == 'allium:lazy_axe' and got[0]['damage'] < 500 and inv['level'] < 30,
                f'anvil: the bot took the repaired Lazy Axe (a real click, {30 - inv["level"]} levels): still the same signed tool')


def grindstone():
    open_block('grindstone', 'GRINDSTONE')
    v = g.ask('sp-menu grind axe-tool-enchanted', 'grind')
    if a.full:
        uuid = v['slot0']['uuid']
        g.packet('wclick 2 1')
        time.sleep(1.0)
        after = g.ask('sp-view grind-after', 'grind-after')
        inv = g.ask('sp-snapshot grind-inv', 'grind-inv')
        taken = [s for k, s in inv.items() if isinstance(s, dict) and s.get('uuid') == uuid]
        require(after['slot0'].get('uuid') == uuid and not taken,
                'grindstone: taking the result of a Lazy Axe was refused; the tool stays in the grindstone, enchanted')


def smithing():
    open_block('smithing', 'SMITHING_TABLE')
    v = g.ask('sp-menu smith-tool template hoe-tool netherite-ingot', 'smith-tool')
    require(item(v['slot3']) is None, 'smithing table: a Mob Disarmer is never upgraded to netherite')
    v = g.ask('sp-menu smith-plain template hoe netherite-ingot', 'smith-plain')
    require(item(v['slot3']) and v['slot3']['material'] == 'NETHERITE_HOE',
            'smithing table: a plain golden hoe still upgrades (the gate\'s own smithing recipe; vanilla has none for these carriers)')


def enchanting():
    g.cmd('dg la-level 30')
    v = g.ask('sp-enchanting ench', 'ench')
    offers = v['offers'][-1] if v['offers'] else {}
    require(offers and not offers['cancelled'] and offers['offers'] > 0,
            f'enchanting table: the Lazy Axe gets enchantment offers ({offers})')
    if a.full:
        uuid = v['slot0']['uuid']
        g.packet('enchant 0')
        time.sleep(1.0)
        e = g.ask('sp-view ench-after', 'ench-after')
        s = e['slot0']
        require(s.get('uuid') == uuid and s.get('behavior') == 'allium:lazy_axe' and s.get('enchants'),
                f'enchanting table: the bot enchanted it (a real click): {s.get("enchants")}, still the same signed tool')


try:
    g.check_port()
    g.build_fixture()
    g.write_lane(tools)
    g.boot('initial', ['Lazy Axe enabled', 'Mob Disarmer enabled'])
    g.cmd('dg prepare')
    g.connect('initial')
    stations()
    crafting()
    anvil()
    grindstone()
    smithing()
    enchanting()
except BaseException as e:
    g.failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    if g.server is not None or g.client is not None:
        g.shutdown()
    g.finish({'gate': 'carrier suppression'})
sys.exit(1 if g.failures else 0)
