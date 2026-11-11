#!/usr/bin/env python3
"""S10 Phantom Obliterator live gate with a real protocol client. Disposable lanes only.

A real bot right-clicks air (the vanilla use-item packet). The fixture spawns marked targets and
records what the server did: damage events from the bot, deaths and their killer, the protection
check events and the tool's signed state. Targets:
- unnamed phantoms near and far inside the radius;
- a named phantom;
- a phantom a damage listener spares (guard);
- a phantom the protection check event denies (claimed);
- a zombie.
The fixture also counts chunk loads during each use (there must be none: the scan reads only
loaded entities). Exits nonzero on any failed check.
"""
import argparse, json, re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from lanes import Gate, LANES, REPO, nexo_items  # noqa: E402

OB = 'allium:phantom_obliterator'
GATE_OB = 'allium:gate_obliterator'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s10-20261109/obliterator'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--griefprevention', type=Path, help='GriefPrevention3D jar; adds the claim case')
p.add_argument('--quick', action='store_true', help='one provider appearance check (1.21.8)')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s10-\d{8}/obliterator', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s10-YYYYMMDD/obliterator root')
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
g = Gate(a.lane, run, a.allium_jar, a.griefprevention)
require, emit = g.require, g.emit

shipped = (REPO / 'src/main/resources/tools/phantom_obliterator.yml').read_text()
lane_definition = shipped
tools = {'phantom_obliterator.yml': lane_definition, 'gate_obliterator.yml': '''schema: 1
id: allium:gate_obliterator
name: Gate Obliterator
behavior: allium:obliterate_phantoms
item:
  material: echo_shard
  name: Gate Obliterator
targets:
  radius: 16.0
permissions:
  use: allium.gateobliterator.use
  bypass: allium.gateobliterator.bypass
'''}
if a.quick:
    tools['phantom_obliterator.yml'] = lane_definition.replace('  aliases: []', '  aliases: [nexo:gate_obliterator]', 1)


def play(name, tool, spec, fault='none'):
    base = g.ask(f'ob-setup {name} {tool} {spec} {fault}', name + '-baseline')
    g.packet('use')
    import time
    time.sleep(2.0)
    obs = g.ask(f'ob-observe {name}', name + '-observe')
    (run / 'cases').mkdir(exist_ok=True)
    (run / 'cases' / (name + '.json')).write_text(json.dumps({'baseline': base, 'observed': obs}, indent=2))
    return base, obs


def target(obs, kind):
    return [t for t in obs['targets'] if t['kind'] == kind]


def killed_by_bot(rows):
    return all(not t['alive'] and t['killer'] == 'GateBot' and t['damage'] >= 1 for t in rows)


def untouched(rows):
    return all(t['damage'] == 0 and t['killer'] is None for t in rows)


def spent(before, after, label, charges, uses, identity=OB):
    require(after.get('behavior') == identity and after['state'].get('charges') == str(charges) and after['state'].get('uses') == str(uses),
            f'{label} exactly one charge spent: {identity} {after["state"]}')
    require(after['uuid'] == before['uuid'] and after['signature'] != before['signature'], label + ' instance UUID kept, re-signed')


def unchanged(before, after, label):
    for key in ['behavior', 'appearance', 'state', 'uuid', 'signature', 'alliumPdc', 'material']:
        require(before.get(key) == after.get(key), f'{label} unchanged {key}')


def cases():
    name = 'authorized'
    base, obs = play(name, OB, 'p@6,p@20,named@8,guard@10,claimed@12,zombie@5')
    b, o = base['main'], obs['main']
    require(b['behavior'] == OB and b['uuid'] and b['signature'] and b['material'] == 'PHANTOM_MEMBRANE',
            f'{name} a fresh tool is a signed {OB} on the phantom membrane carrier: {b["material"]}')
    require(killed_by_bot(target(obs, 'p')), f'{name} both unnamed phantoms inside the radius died, killed as the bot: {target(obs, "p")}')
    require(untouched(target(obs, 'named')) and target(obs, 'named')[0]['alive'], name + ' the named phantom was never damaged')
    guard = target(obs, 'guard')[0]
    require(guard['alive'] and guard['damage'] == 1 and guard['spared'] == 1, f'{name} the guarded phantom was damaged once and spared: {guard}')
    require(untouched(target(obs, 'claimed')) and target(obs, 'claimed')[0]['alive'], name + ' the claimed phantom (protection denied) was never damaged')
    require(untouched(target(obs, 'zombie')), name + ' the zombie is not a target')
    require(obs['chunkLoads'] == 0, f'{name} the scan loaded no chunk (it reads only loaded entities): {obs["chunkLoads"]} chunk loads during the use')
    require(obs['checkEvents'] == 4, f'{name} protection was asked once per scanned target (2 unnamed, guard, claimed): {obs["checkEvents"]}')
    spent(b, o, name, 2, 1)

    name = 'empty'
    base, obs = play(name, OB, 'named@6,zombie@5')
    unchanged(base['main'], obs['main'], name)
    require(untouched(obs['targets']) and obs['checkEvents'] == 0, name + ' no target: nothing spent, nothing asked, nothing damaged')

    name = 'spared'
    base, obs = play(name, OB, 'guard@6')
    st = obs['main']['state']
    require(target(obs, 'guard')[0]['alive'] and target(obs, 'guard')[0]['spared'] == 1
            and st.get('charges') == '3' and st.get('uses', '0') == '0',
            f'{name} every target spared by a damage listener: the charge is refunded ({st})')

    name = 'write-fault'
    start = len(g.logs)
    base, obs = play(name, OB, 'p@6', 'write-fault')
    require(obs['stateWriteFault'] == {'enabled': True, 'armed': 0, 'tripped': 1}, f'{name} the state write was rejected once: {obs["stateWriteFault"]}')
    require(untouched(obs['targets']) and target(obs, 'p')[0]['alive'], name + ' a rejected write kills nothing (spend first)')
    unchanged(base['main'], obs['main'], name)
    require(any('state write was rejected' in x for x in g.logs[start:]), name + ' Allium logged the rejected write')

    name = 'fake'
    base, obs = play(name, 'fake', 'p@6')
    require(base['main']['behavior'] is None and untouched(obs['targets']) and obs['checkEvents'] == 0,
            f'{name} a phantom membrane with the model data and name, but no signature, does nothing')

    name = 'tampered'
    start = len(g.logs)
    base, obs = play(name, 'tampered:' + OB, 'p@6')
    require(base['main']['behavior'] is None and untouched(obs['targets']),
            f'{name} a signed tool with its charges edited is rejected and does nothing')
    require(any('Rejected item claiming behavior' in x and OB in x for x in g.logs[start:]), name + ' Allium logged the rejected signature')

    g.cmd('dg perm allium.phantomobliterator.use false')
    name = 'no-permission'
    base, obs = play(name, OB, 'p@6')
    g.cmd('dg perm allium.phantomobliterator.use unset')
    unchanged(base['main'], obs['main'], name)
    require(untouched(obs['targets']), name + ' without allium.phantomobliterator.use nothing happens')

    name = 'legacy-recharging'
    start = len(g.logs)
    base, obs = play(name, 'legacy:phantom_obliterator_0-3:0:30:7:both', 'p@6')
    st, o = obs['main']['state'], obs['main']
    remaining = int(st.get('recharge_at', '0')) - obs['now']
    require(o['behavior'] == OB and o['material'] == 'NETHERITE_SWORD' and st.get('charges') == '0' and st.get('uses') == '7'
            and 20_000 < remaining <= 30_000 and obs['mainEnchantments'].get('unbreaking') == 3,
            f'{name} adopted in place: 0 charges, the depleted timestamp ({remaining} ms left), 7 uses, its Unbreaking III kept ({st})')
    require(untouched(obs['targets']), name + ' and it is still recharging, so nothing died')
    require(any('Imported legacy Phantom Obliterator' in x for x in g.logs[start:]), name + ' Allium logged the import')

    name = 'legacy-full'
    base, obs = play(name, 'legacy:phantom_obliterator:3:0:4:both', 'p@6')
    st = obs['main']['state']
    require(obs['main']['behavior'] == OB and st.get('charges') == '2' and st.get('uses') == '5' and killed_by_bot(target(obs, 'p')),
            f'{name} a charged legacy tool imports and works: 3 -> 2 charges, uses 4 -> 5 ({st})')

    name = 'legacy-model-only'
    base, obs = play(name, 'legacy:phantom_obliterator:3:0:0:custom-only', 'p@6')
    require(obs['main']['behavior'] is None and untouched(obs['targets']),
            f'{name} without a matching nexo:id it is never imported and does nothing')

    name = 'radius'
    base, obs = play(name, GATE_OB, 'p@10,p@20')
    p10, p20 = target(obs, 'p')
    require(not p10['alive'] and p20['alive'] and untouched([p20]),
            f'{name} a second definition\'s own 16-block radius: the phantom at 10 died, the one at 20 was never touched')
    spent(base['main'], obs['main'], name, 2, 1, GATE_OB)

    # Three uses empty a fresh tool; the fourth finds it recharging.
    for i, charges in enumerate([2, 1, 0], 1):
        name = f'charge-{i}'
        base, obs = play(name, OB if i == 1 else 'held', 'p@6')
        st = obs['main']['state']
        require(st.get('charges') == str(charges) and killed_by_bot(target(obs, 'p')), f'{name} -> {charges} charges ({st})')
    remaining = int(st.get('recharge_at', '0')) - obs['now']
    require(110_000 < remaining <= 120_000, f'spending the last charge started the 120 s recharge ({remaining} ms)')
    name = 'recharging'
    base, obs = play(name, 'held', 'p@6')
    require(untouched(obs['targets']) and obs['main']['state'] == base['main']['state'], name + ' an empty tool kills nothing until it recharges')


def claim_case():
    claim = g.ask('gpclaim 4000', 'gpclaim')
    emit('CLAIM ' + json.dumps(claim))
    require(claim['succeeded'], 'an admin claim was created over the arena')
    name = 'gp-claimed'
    base, obs = play(name, OB, 'p@6,p@10')
    require(untouched(obs['targets']) and all(t['alive'] for t in obs['targets']) and obs['checkEvents'] == 0,
            f'{name} phantoms inside a claim the bot has no trust in are protected by the GriefPrevention hook')
    unchanged(base['main'], obs['main'], name)


def persistence():
    name = 'keep'
    base, obs = play(name, OB, 'p@6')
    require(obs['main']['state'].get('charges') == '2' and killed_by_bot(target(obs, 'p')), name + ' a fresh keeper spent one charge')
    g.cmd('dg keep 9')
    before = g.ask('snapshot before-restart', 'before-restart')['9']
    g.shutdown()
    g.boot('restart', ['Phantom Obliterator enabled'])
    g.connect('restart')
    after = g.ask('snapshot after-restart', 'after-restart')['9']
    unchanged(before, after, 'restart keeper')
    g.cmd('dg take 9')
    base, obs = play('reuse', 'held', 'p@6')
    require(base['main']['uuid'] == before['uuid'] and obs['main']['state'].get('charges') == '1' and killed_by_bot(target(obs, 'p')),
            'after a restart the kept tool works on: 2 -> 1 charges')


def quick():
    nexo_tool = 'nexo:gate_obliterator'
    base, obs = play('nexo', nexo_tool, 'p@6')
    b = base['main']
    require(b['behavior'] == OB and b['native'].get('nexo') == 'gate_obliterator' and b['signature'],
            f'nexo a Nexo-issued tool is a signed {OB} with its native ID: {b.get("native")}')
    require(killed_by_bot(target(obs, 'p')), 'nexo it obliterates')
    spent(b, obs['main'], 'nexo', 2, 1)


summary = {}
try:
    g.check_port()
    g.build_fixture()
    setup = None
    if a.quick:
        setup = lambda plugins: nexo_items(plugins, 'gate_obliterator:\n  material: PHANTOM_MEMBRANE\n  itemname: Gate Nexo Obliterator\n')
    g.write_lane(tools, providers_setup=setup)
    g.boot('initial', ['Phantom Obliterator enabled', 'Gate Obliterator enabled', 'Fault injection is enabled']
           + (['Protection hook enabled: GriefPrevention'] if a.griefprevention else []))
    g.cmd('dg prepare')
    g.connect('initial')
    if a.quick:
        quick()
    else:
        cases()
        persistence()
        if a.griefprevention:
            claim_case()  # last: the admin claim stays over the arena
except BaseException as e:
    g.failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    if g.server is not None or g.client is not None:
        g.shutdown()
    summary = g.finish({'tool': OB})
sys.exit(1 if g.failures else 0)
