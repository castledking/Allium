#!/usr/bin/env python3
"""S10 Lazy Axe live gate with a real protocol bot. Disposable lanes only.

The bot mines a log for real (start, wait, finish: the server's own break progress). The fixture
grows vanilla trees with a seeded random, builds player-style log structures and records what the
server did: every BlockBreakEvent the bot caused (and whether Allium's re-entry guard was set
around it, i.e. the break came from a fell), each LazyAxeFellEvent, the server tick of each break,
the drops, the leaves and the axe. Interruptions are real: the bot swaps its hotbar slot or
disconnects, or the fixture takes it away and unloads the tree's chunk. Exits nonzero on any
failed check.
"""
import argparse, json, math, re, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from lanes import Gate, LANES, REPO, GRIEFPREVENTION, nexo_items  # noqa: E402

AXE = 'allium:lazy_axe'
MINE_MS = 1500

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s10-20261112/lazyaxe'))  # any /tmp/allium-s10-YYYYMMDD/lazyaxe
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--griefprevention', action='store_true', help="add SF's GriefPrevention3D 19.0.5 and the claim case")
p.add_argument('--quick', action='store_true', help='one provider appearance check (1.21.8)')
p.add_argument('--natural', action='store_true',
               help='a fresh install on vanilla terrain: /give, a natural tree, disabled by default, then enabled')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s10-\d{8}/lazyaxe', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s10-YYYYMMDD/lazyaxe root')
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
g = Gate(a.lane, run, a.allium_jar, GRIEFPREVENTION if a.griefprevention else None)
require, emit = g.require, g.emit
modern = a.lane == 'paper-26.3'
spigot = a.lane == 'spigot-1.20.1'

shipped = (REPO / 'src/main/resources/tools/lazy_axe.yml').read_text()
assert '\nenabled: false\n' in shipped, 'the shipped Lazy Axe is off by default'
lane_definition = shipped.replace('\nenabled: false\n', '\nenabled: true\n', 1)
if a.quick:
    lane_definition = lane_definition.replace('  aliases: []', '  aliases: [nexo:gate_axe]', 1)


def gate_axe(local, **settings):
    s = {'blocks-per-tick': 6, 'mode': 'break-as-player', 'stop-before-break': 'true', 'sneak': 'disables', 'max-blocks': 256}
    s.update(settings)
    return f'''schema: 1
id: allium:{local}
name: Gate Axe {local}
behavior: allium:fell_tree
item:
  material: netherite_axe
  name: Gate Axe {local}
families: [overworld_logs, mangrove, cherry, pale_oak]
search:
  max-blocks: {s['max-blocks']}
breaking:
  blocks-per-tick: {s['blocks-per-tick']}
  mode: {s['mode']}
  stop-before-break: {s['stop-before-break']}
sneak: {s['sneak']}
permissions:
  use: allium.treeaxe.use
'''


tools = {'lazy_axe.yml': lane_definition}
if not a.quick:
    tools.update({
        'gate_axe_slow.yml': gate_axe('gate_axe_slow', **{'blocks-per-tick': 1}),
        'gate_axe_break.yml': gate_axe('gate_axe_break', **{'stop-before-break': 'false'}),
        'gate_axe_required.yml': gate_axe('gate_axe_required', sneak='required'),
        'gate_axe_ignored.yml': gate_axe('gate_axe_ignored', sneak='ignored'),
        'gate_axe_direct.yml': gate_axe('gate_axe_direct', mode='direct'),
    })

arenas = iter(range(5000, 9000, 40))


def arena(name, keep=None):
    x = keep if keep is not None else next(arenas)
    g.ask(f'la-arena {name} {x} 0' + (' keep' if keep is not None else ''), name + '-arena')
    return x


def grow(name, kind, seed):
    x = arena(name)
    tree = g.ask(f'la-tree {name} {kind} {x} 0 {seed}', name + '-tree')
    require(tree['grown'] and tree['logs'] > 0, f'{name}: a {kind} grew ({tree["logs"]} logs, {tree["leaves"]} leaves, {tree["materials"]})')
    return x, tree


def give(name, spec, left=-1, enchants='-'):
    return g.ask(f'la-give {name} {spec} {left} {enchants}', name)


def mine(x, y=None, z=0):
    g.packet(f'mine {x} {y if y is not None else ground + 1} {z} {MINE_MS}')
    time.sleep(MINE_MS / 1000 + 0.3)


def settle(name, timeout=30):
    """Waits until no fell is running and the break count is stable, then returns the observation."""
    end, last, stable = time.monotonic() + timeout, None, 0
    while True:
        obs = g.ask(f'la-observe {name}', name + '-observe')
        key = (obs['breakEvents'], obs['felling'])
        stable = stable + 1 if key == last and not obs['felling'] else 0
        last = key
        if stable >= 2 or time.monotonic() > end:
            break
        time.sleep(0.4)
    (run / 'cases').mkdir(exist_ok=True)
    (run / 'cases' / (name + '.json')).write_text(json.dumps(obs, indent=2))
    return obs


def felled(name, obs, tree, tool=AXE, budget=6):
    L = tree['logs']
    require(obs['logsLeft'] == 0, f'{name}: every log is gone ({L} logs, {obs["logsLeft"]} left)')
    require(obs['leaves'] == obs['leavesBefore'], f'{name}: no leaf was broken ({obs["leaves"]} of {obs["leavesBefore"]})')
    require(len(obs['fells']) == 1 and obs['fells'][0]['tool'] == tool and obs['fells'][0]['logs'] == L - 1,
            f'{name}: one fell by {tool} for the {L - 1} other logs: {obs["fells"]}')
    require(obs['breakEvents'] == L and obs['breaksByFell'] == L - 1,
            f'{name}: one real BlockBreakEvent per log ({obs["breakEvents"]}), {obs["breaksByFell"]} of them inside the fell')
    require(obs['maxPerTick'] <= budget and obs['fellTicks'] >= math.ceil((L - 1) / budget),
            f'{name}: spread over {obs["fellTicks"]} ticks, at most {obs["maxPerTick"]} per tick (budget {budget})')
    require(obs['dropTotal'] == L, f'{name}: the drops equal the logs ({obs["drops"]})')


def stood(name, obs, tree, label):
    require(obs['breakEvents'] == 1 and not obs['fells'] and obs['logsLeft'] == tree['logs'] - 1,
            f'{name}: {label}: only the mined log broke ({obs["breakEvents"]} breaks, {len(obs["fells"])} fells, '
            f'{obs["logsLeft"]} of {tree["logs"]} logs left)')


def natural(name, kind, seed, extra=None):
    x, tree = grow(name, kind, seed)
    before = give(name + '-axe', AXE)
    mine(x)
    obs = settle(name)
    felled(name, obs, tree)
    require(obs['main']['damage'] - before['damage'] == tree['logs'] and obs['main']['uuid'] == before['uuid'],
            f'{name}: Unbreaking 0 costs one durability per log ({obs["main"]["damage"]} damage, same axe)')
    return x, tree, obs


def cases():
    global ground
    # Natural trees.
    x, tree, obs = natural('oak', 'tree', 101)
    if spigot:
        chat = [l for l in g.client_logs if 'S06A-WIRE CHAT' in l and 'already felling' in l]
        require(not chat, 'oak: the re-entrant break events never reported "busy" to the player')
    for name, kind, seed in [('birch', 'birch', 102), ('spruce', 'redwood', 103), ('cherry', 'cherry', 104)]:
        natural(name, kind, seed)
    if modern:
        natural('pale-oak', 'pale_oak', 105)
        x, tree = grow('pale-oak-creaking', 'pale_oak_creaking', 106)
        require(tree['creakingHearts'] >= 1, f'pale-oak-creaking: the tree has a creaking heart in its trunk ({tree["creakingHearts"]}'
                + (', set by the fixture: the seeded tree grew without one)' if tree['heartPlacedByFixture'] else ', grown)'))
        give('pale-oak-creaking-axe', AXE)
        mine(x)
        stood('pale-oak-creaking', settle('pale-oak-creaking'), tree, 'a pale oak with a creaking heart is not felled')

    # Unbreaking III on the largest vanilla tree, under the default 256-block cap.
    x, tree = grow('jungle', 'jungle', 107)
    give('jungle-axe', AXE, -1, 'unbreaking:3')
    mine(x)
    obs = settle('jungle')
    felled('jungle', obs, tree)
    L = tree['logs']
    require(0 < obs['main']['damage'] < 0.6 * L, f'jungle: with Unbreaking III the axe took {obs["main"]["damage"]} damage for {L} logs')

    # Must not fell.
    x = arena('build-logs')
    tree = g.ask('la-build build-logs logs', 'build-logs-tree')
    give('build-logs-axe', AXE)
    mine(x)
    stood('build-logs', settle('build-logs'), tree, 'a bare player-built log frame')
    x = arena('build-persistent')
    tree = g.ask('la-build build-persistent persistent', 'build-persistent-tree')
    require(tree['persistentLeaves'] > 0, f'build-persistent: its {tree["persistentLeaves"]} leaves are persistent (player-placed)')
    give('build-persistent-axe', AXE)
    mine(x)
    stood('build-persistent', settle('build-persistent'), tree, 'a log trunk with player-placed leaves')
    x, tree = grow('wall', 'tree', 108)
    tree = g.ask('la-build wall wall', 'wall-tree')
    give('wall-axe', AXE)
    mine(x)
    stood('wall', settle('wall'), tree, 'a natural oak with planks touching its trunk (the structure guard)')

    # stop-before-break, both ways, on a dark oak (enough logs).
    x, tree = grow('keep-axe', 'dark_oak', 109)
    give('keep-axe-axe', AXE, 4)
    mine(x)
    obs = settle('keep-axe')
    left = obs['main'].get('maxDurability', 0) - obs['main'].get('damage', 0)
    require(not obs['main'].get('empty') and left == 1 and obs['breakEvents'] == 3 and obs['logsLeft'] == tree['logs'] - 3
            and obs['itemBreakSeq'] < 0,
            f'keep-axe: stop-before-break true stops with 1 durability left: {obs["breakEvents"]} breaks, {left} left, axe kept')
    x, tree = grow('break-axe', 'dark_oak', 110)
    give('break-axe-axe', 'allium:gate_axe_break', 4)
    mine(x)
    obs = settle('break-axe')
    require(obs['main'].get('empty') and obs['itemBreakSeq'] > 0 and obs['breakEvents'] == 4
            and obs['logsLeft'] == tree['logs'] - 4,
            f'break-axe: stop-before-break false lets the axe break on its last log: {obs["breakEvents"]} breaks, then the job stopped')
    require(all(b['seq'] < obs['itemBreakSeq'] or not b['byFell'] for b in obs['breaks'][4:]),
            'break-axe: nothing was broken after the axe broke')

    # Sneaking and the permission.
    x, tree = grow('sneak-disables', 'tree', 111)
    give('sneak-disables-axe', AXE)
    g.packet('sneak on')
    time.sleep(0.3)
    mine(x)
    obs = settle('sneak-disables')
    require(obs['sneaking'], 'sneak-disables: the server sees the bot sneaking')
    stood('sneak-disables', obs, tree, 'sneak: disables, sneaking')
    x, tree = grow('sneak-ignored', 'tree', 112)
    give('sneak-ignored-axe', 'allium:gate_axe_ignored')
    mine(x)
    felled('sneak-ignored', settle('sneak-ignored'), tree, 'allium:gate_axe_ignored')
    x, tree = grow('sneak-required', 'tree', 113)
    give('sneak-required-axe', 'allium:gate_axe_required')
    mine(x)
    felled('sneak-required', settle('sneak-required'), tree, 'allium:gate_axe_required')
    g.packet('sneak off')
    time.sleep(0.3)
    x, tree = grow('sneak-required-standing', 'tree', 114)
    give('sneak-required-standing-axe', 'allium:gate_axe_required')
    mine(x)
    obs = settle('sneak-required-standing')
    require(not obs['sneaking'], 'sneak-required-standing: the bot stopped sneaking')
    stood('sneak-required-standing', obs, tree, 'sneak: required, standing')

    g.cmd('dg perm allium.treeaxe.use false')
    x, tree = grow('no-permission', 'tree', 115)
    give('no-permission-axe', AXE)
    mine(x)
    stood('no-permission', settle('no-permission'), tree, 'without allium.treeaxe.use')
    g.cmd('dg perm allium.treeaxe.use unset')

    # Interruptions, at one log per tick on mega jungle trees.
    x, tree = grow('swap', 'jungle', 116)
    give('swap-axe', 'allium:gate_axe_slow')
    mine(x)
    time.sleep(1.0)
    g.packet('slot 1')
    obs = settle('swap')
    after = [b for b in obs['breaks'] if b['seq'] > obs['heldSeq']]
    require(obs['heldSeq'] > 0 and not after and 0 < obs['logsLeft'] < tree['logs'] and obs['breaksByFell'] > 0,
            f'swap: the bot switched hotbar slots mid-fell ({obs["breaksByFell"]} logs in); nothing broke after, {obs["logsLeft"]} logs left')
    x, tree = grow('quit', 'jungle', 117)
    give('quit-axe', 'allium:gate_axe_slow')
    mine(x)
    time.sleep(1.0)
    g.packet('stop')
    time.sleep(2.0)
    g.wait(lambda: g.client.poll() is not None, 'client exit', 15)
    g.client = None
    time.sleep(2.0)  # what the fell would have broken while the bot was away
    g.connect('after-quit')
    obs = settle('quit')
    after = [b for b in obs['breaks'] if b['seq'] > obs['quitSeq']]
    require(obs['quitSeq'] > 0 and not after and 0 < obs['logsLeft'] < tree['logs'],
            f'quit: the bot disconnected mid-fell ({obs["breaksByFell"]} logs in); nothing broke after, {obs["logsLeft"]} logs left')

    # mode: direct.
    x, tree = grow('direct', 'tree', 119)
    before = give('direct-axe', 'allium:gate_axe_direct')
    mine(x)
    obs = settle('direct')
    L = tree['logs']
    require(obs['logsLeft'] == 0 and obs['dropTotal'] == L, f'direct: every log is gone and dropped ({obs["drops"]})')
    require(obs['breakEvents'] == 1 and len(obs['fells']) == 1 and obs['fells'][0]['breakAsPlayer'] is False,
            f'direct: only the first log fired a BlockBreakEvent ({obs["breakEvents"]}); one LazyAxeFellEvent says direct')
    require(obs['main']['damage'] - before['damage'] == L, f'direct: one durability per log with Unbreaking 0 ({obs["main"]["damage"]})')

    # A pre-recode tree_axe.
    x, tree = grow('legacy', 'tree', 120)
    before = give('legacy-axe', 'legacy', 2031 - 100, 'efficiency:5,unbreaking:3,fortune:3,mending:1')
    require(before['behavior'] is None and before['legacyId'] == 'tree_axe', 'legacy: a pre-recode tree_axe has no Allium identity yet')
    start = len(g.logs)
    mine(x)
    obs = settle('legacy')
    m = obs['slot0']
    require(m['behavior'] == AXE and m['uuid'] and m['signature'] and m['material'] == 'NETHERITE_AXE',
            f'legacy: adopted in place as a signed {AXE}: {m["behavior"]}, {m["material"]}')
    require(m['enchants'] == before['enchants'] and m['name'] == 'Old Tree Axe' and m['repairCost'] == 7 and m['damage'] >= 100,
            f'legacy: its enchantments {m["enchants"]}, name, repair cost and damage ({m["damage"]}) are kept')
    felled('legacy', obs, tree)
    require(any('Imported legacy Lazy Axe' in x for x in g.logs[start:]), 'legacy: Allium logged the import')


def unload_case():
    """Run after the restart: on Paper the lane's delay-chunk-unloads-by is then 0s (see cap_setup)."""
    x, tree = grow('unload', 'jungle', 118)
    give('unload-axe', 'allium:gate_axe_slow')
    g.cmd('dg la-unload-after 5')
    mine(x)
    obs = settle('unload', 40)
    after = [b for b in obs['breaks'] if b['byFell'] and b['seq'] > obs['unloadSeq']]
    emit('UNLOAD ' + json.dumps(obs['unloadLog']))
    require(obs['unloadSeq'] > 0 and not after and 0 < obs['logsLeft'] < tree['logs'],
            f'unload: a chunk of the tree unloaded mid-fell; nothing broke after it, {obs["logsLeft"]} logs left ({obs["unloadLog"][-2:]})')
    g.cmd('dg la-return')


def cap_setup():
    """Grows two more mega jungles before the restart and writes caps at their exact log counts."""
    xb, tb = grow('cap-full', 'jungle', 121)
    xc, tc = grow('cap-over', 'jungle', 122)
    tools_dir = g.lane / 'plugins/Allium/tools'
    (tools_dir / 'gate_axe_cap_full.yml').write_text(gate_axe('gate_axe_cap_full', **{'max-blocks': tb['logs']}))
    (tools_dir / 'gate_axe_cap_over.yml').write_text(gate_axe('gate_axe_cap_over', **{'max-blocks': tc['logs'] - 1}))
    paper = g.lane / 'config/paper-world-defaults.yml'
    if paper.exists():
        # Paper holds a chunk for delay-chunk-unloads-by (10s) after the last player leaves, longer
        # than any fell lasts; with 0s the tree's chunk unloads while the fell is still running.
        text = paper.read_text()
        assert 'delay-chunk-unloads-by: 10s' in text
        paper.write_text(text.replace('delay-chunk-unloads-by: 10s', 'delay-chunk-unloads-by: 0s', 1))
        emit('PAPER delay-chunk-unloads-by set to 0s for the unload case')
    return (xb, tb), (xc, tc)


def cap_cases(full, over):
    (xb, tb), (xc, tc) = full, over
    arena('cap-full', keep=xb)
    tree = g.ask(f'la-tree cap-full existing {xb} 0 0', 'cap-full-tree')
    require(tree['logs'] == tb['logs'], f'cap-full: the tree survived the restart ({tree["logs"]} logs)')
    give('cap-full-axe', 'allium:gate_axe_cap_full')
    mine(xb)
    felled('cap-full', settle('cap-full'), tree, 'allium:gate_axe_cap_full')
    arena('cap-over', keep=xc)
    tree = g.ask(f'la-tree cap-over existing {xc} 0 0', 'cap-over-tree')
    give('cap-over-axe', 'allium:gate_axe_cap_over')
    mine(xc)
    stood('cap-over', settle('cap-over'), tree, f'max-blocks {tc["logs"] - 1}, one under the tree\'s {tc["logs"]} logs')
    emit(f'CAP mega jungle L = {tb["logs"]} (full) and {tc["logs"]} (over)')


def claim_case():
    x, tree = grow('claim', 'dark_oak', 123)
    xs = sorted({p[0] for p in tree['logPositions']})
    border = x + 1
    claim = g.ask(f'la-claim claim-gp {border} {x + 20} -20 20', 'claim-gp')
    emit('CLAIM ' + json.dumps(claim))
    require(claim['succeeded'], f'claim: an admin claim over x >= {border} cuts the dark oak (log columns {xs})')
    inside = [p for p in tree['logPositions'] if p[0] >= border]
    outside = [p for p in tree['logPositions'] if p[0] < border]
    give('claim-axe', AXE)
    mine(x)
    obs = settle('claim')
    left = {tuple(p) for p in obs['leftPositions']}
    require(inside and outside and all(tuple(p) in left for p in inside),
            f'claim: all {len(inside)} logs inside the claim are left')
    require(not any(tuple(p) in left for p in outside) and len(obs['fells']) == 1,
            f'claim: all {len(outside)} logs outside it are felled')


def natural_trial(label):
    """Finds the nearest natural tree, gives the axe with /give as an admin would, and mines its base."""
    tree = g.ask('la-find ' + label + ' 40', label + '-tree')
    require(tree['found'] and tree['persistentLeaves'] == 0,
            f'{label}: a natural tree near spawn: {tree.get("species")} at {tree.get("trunk")} in {tree.get("biome")}, '
            f'{tree.get("logs")} logs in its area')
    g.cmd('dg la-clear')  # an empty inventory, so /give fills the held slot 0
    g.cmd_raw('give GateBot allium:lazy_axe')
    time.sleep(1.5)
    x, y, z = tree['trunk']
    g.packet(f'mine {x} {y} {z} {MINE_MS}')
    time.sleep(MINE_MS / 1000 + 0.3)
    return tree, settle(label)


def natural_install():
    """The default install: tools/lazy_axe.yml ships enabled: false, so the axe is a plain axe."""
    installed = (g.lane / 'plugins/Allium/tools/lazy_axe.yml').read_text()
    require('\nenabled: false\n' in installed, 'natural: a fresh install creates tools/lazy_axe.yml with enabled: false')
    require(any('Lazy Axe is disabled (tools/lazy_axe.yml: enabled: false)' in x for x in g.logs),
            'natural: the startup log says the Lazy Axe is disabled and where to enable it')
    tree, obs = natural_trial('natural-disabled')
    require(obs['slot0'].get('behavior') == AXE and obs['slot0'].get('material') == 'NETHERITE_AXE',
            f'natural-disabled: /give allium:lazy_axe gave a signed {AXE} ({obs["slot0"].get("material")})')
    require(obs['breakEvents'] == 1 and not obs['fells'],
            f'natural-disabled: while disabled it breaks one log, as a plain axe ({obs["breakEvents"]} breaks, {len(obs["fells"])} fells)')
    require(any('so it works as a plain item until you set enabled: true and restart' in x for x in g.logs),
            'natural-disabled: /give warned the console that the Lazy Axe is disabled')
    g.shutdown()
    f = g.lane / 'plugins/Allium/tools/lazy_axe.yml'
    f.write_text(f.read_text().replace('\nenabled: false\n', '\nenabled: true\n', 1))
    g.boot('enabled', ['Lazy Axe enabled'])
    g.connect('enabled')
    tree, obs = natural_trial('natural-enabled')
    trunk = [p for p in tree['logPositions'] if p[0] == tree['trunk'][0] and p[2] == tree['trunk'][2]]
    left = {tuple(p) for p in obs['leftPositions']}
    require(len(obs['fells']) == 1 and obs['breaksByFell'] == obs['fells'][0]['logs'] > 0 and not any(tuple(p) in left for p in trunk),
            f'natural-enabled: with enabled: true and a restart, the natural {tree["species"]} fell: '
            f'{obs["breaksByFell"] + 1} logs, its whole trunk gone')
    require(obs['leaves'] == obs['leavesBefore'], 'natural-enabled: no leaf was broken')


def quick():
    x, tree = grow('nexo', 'tree', 124)
    b = give('nexo-axe', 'nexo:gate_axe')
    require(b['behavior'] == AXE and b['signature'] and b['appearance'] == 'nexo:gate_axe',
            f'nexo: a Nexo-issued axe is a signed {AXE} with the Nexo appearance: {b["appearance"]}')
    mine(x)
    felled('nexo', settle('nexo'), tree)


ground = None
try:
    g.check_port()
    g.build_fixture()
    setup = None
    if a.quick:
        setup = lambda plugins: nexo_items(plugins, 'gate_axe:\n  material: NETHERITE_AXE\n  itemname: Gate Nexo Axe\n')
    if a.natural:
        g.write_lane({}, terrain='normal')  # a fresh install: Allium writes its shipped defaults
    else:
        g.write_lane(tools, providers_setup=setup)
    markers = (['Fault injection is enabled'] if a.natural else
               ['Lazy Axe enabled', 'Fault injection is enabled'] + ([] if a.quick else ['Gate Axe gate_axe_slow enabled']))
    if a.griefprevention:
        markers.append('Protection hook enabled: GriefPrevention')
    g.boot('initial', markers)
    g.cmd('dg prepare')
    g.connect('initial')
    if a.natural:
        natural_install()
    elif a.quick:
        ground = g.ask('la-arena probe 4800 0', 'probe-arena')['ground']
        quick()
    else:
        ground = g.ask('la-arena probe 4800 0', 'probe-arena')['ground']
        cases()
        full, over = cap_setup()
        g.shutdown()
        g.boot('restart', ['Gate Axe gate_axe_cap_full enabled', 'Gate Axe gate_axe_cap_over enabled'])
        g.connect('restart')
        cap_cases(full, over)
        unload_case()
        if a.griefprevention:
            claim_case()
except BaseException as e:
    g.failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    if g.server is not None or g.client is not None:
        g.shutdown()
    g.finish({'tool': AXE})
sys.exit(1 if g.failures else 0)
