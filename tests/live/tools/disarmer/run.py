#!/usr/bin/env python3
"""S06a Mob Disarmer live gate with a real protocol client. Disposable lanes only.

Never reads production data: only server/provider binaries, immutable library caches and the
retained provider settings from the passing S05 coexist lane are copied. Fresh world, player data,
Allium database and test keyring every run. Exits nonzero on any failed assertion.
"""
import argparse, hashlib, json, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

sys.path.insert(0, str(HERE.parent))
from lanes import COEXIST, LANES, PROVIDER_JARS  # noqa: E402  (shared with the other S10 tool gates)

APPEARANCES = {
    'allium': 'allium:mob_disarmer',
    'nexo': 'nexo:mob_disarmer',
    'craftengine': 'craftengine:allium_gate:mob_disarmer',
    'oraxen': 'oraxen:Gate_Disarmer',
}
# Every problem in tools/invalid_tool.yml, each reported with the file and the setting's path.
INVALID_DIAGNOSTICS = [
    'Tool definition tools/invalid_tool.yml is invalid and was not loaded:',
    'tools/invalid_tool.yml: schema: must be 1; this Allium reads tool definition schema 1 (got 99)',
    'tools/invalid_tool.yml: item: is required: allium:invalid_tool is not a core pack item',
    'tools/invalid_tool.yml: charges.max: must be between 1 and 1000 (got 0)',
    "tools/invalid_tool.yml: targets.excluded-mobs[0]: 'zombi' is not an entity type",
    'tools/invalid_tool.yml: targets.radious: unknown setting; expected one of radius, excluded-mobs, exclude-npcs',
]
NEXO_VARIANTS = {'3': 'nexo:mob_disarmer', '2': 'nexo:mob_disarmer_2-3', '1': 'nexo:mob_disarmer_1-3', '0': 'nexo:mob_disarmer_0-3'}
SLOTS = ['HAND', 'OFF_HAND', 'HEAD', 'CHEST', 'LEGS', 'FEET']
DROPPED = ['CHEST', 'HAND', 'LEGS']  # chances 2.0, 1.0, 1.0; the others are 0.0
FULL_CHANCES = {'HAND': 1.0, 'OFF_HAND': 0.0, 'HEAD': 0.0, 'CHEST': 2.0, 'LEGS': 1.0, 'FEET': 0.0}
# Disposable lanes only: lets the fixture arm one rejected item state write. Inert unless armed.
FAULTS = '-Dallium.fault-injection=true'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s06a-20261106/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--griefprevention', type=Path, help='GriefPrevention or GriefPrevention3D jar; adds the live claim check')
p.add_argument('--upgrade-from', type=Path, help='an Allium jar from before tool definitions: run the upgrade scenario instead')
p.add_argument('--quick', action='store_true', help='one authorized disarm per appearance, no persistence (provider appearance check)')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s06a-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s06a-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
lane = run / 'lane'
lane.mkdir(parents=True)
data = lane / 'plugins/DisarmerGate'
port = spec['port']
providers = spec['providers']
labels = ['allium'] + providers

server = client = None
server_thread = client_thread = None
logs, client_logs, driver, failures, cases = [], [], [], [], []


def emit(message):
    driver.append(message)
    print(message, flush=True)


def require(ok, label):
    if not ok:
        failures.append(label)
    emit(('PASS ' if ok else 'FAIL ') + label)
    return ok


def pump(proc, path, sink):
    with path.open('a') as f:
        for line in proc.stdout:
            f.write(line)
            f.flush()
            sink.append(line)


def wait(fn, label, timeout=90):
    end = time.monotonic() + timeout
    while True:
        value = fn()
        if value:
            return value
        if server and server.poll() is not None:
            raise RuntimeError(f'server exited {server.returncode} waiting for {label}')
        if time.monotonic() > end:
            raise RuntimeError('timeout ' + label)
        time.sleep(0.05)


def cmd(text):
    start = len(logs)
    server.stdin.write(text + '\n')
    server.stdin.flush()
    if text.startswith('dg '):
        tail = text[3:]
        wait(lambda: any(('S06A-DONE ' + tail) in x or ('S06A-FAIL ' + tail) in x for x in logs[start:]), text, 30)
        if any(('S06A-FAIL ' + tail) in x for x in logs[start:]):
            raise RuntimeError('fixture failed: ' + text)


def packet(text):
    client.stdin.write(text + '\n')
    client.stdin.flush()


def result(name):
    path = data / (name + '.json')
    path.unlink(missing_ok=True)
    return path


def read(path):
    wait(path.exists, path.name, 30)
    return json.loads(path.read_text())


def status():
    path = result('status')
    cmd('dg status')
    return read(path)


def wait_providers_ready(timeout=120):
    def ready():
        s = status()
        return s if all(s[x] == 'READY' for x in providers) else None
    return wait(ready, 'providers READY', timeout)


def write_lane():
    if 'template' in spec:
        for name in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / name, lane / name)
    elif 'seed' in spec:
        for name in ['cache', 'libraries', 'versions']:
            shutil.copytree(spec['seed'] / name, lane / name)
        shutil.copy2(spec['server'], lane / 'server.jar')
    else:
        shutil.copy2(spec['server'], lane / 'server.jar')
    plugins = lane / 'plugins'
    plugins.mkdir(exist_ok=True)
    shutil.copy2(a.allium_jar, plugins / 'Allium.jar')
    shutil.copy2(run / 'fixture/DisarmerGate.jar', plugins / 'DisarmerGate.jar')
    allium_config = (REPO / 'src/main/resources/config.yml').read_text()
    (plugins / 'Allium').mkdir(parents=True, exist_ok=True)
    (plugins / 'Allium/config.yml').write_text(allium_config)
    tools_dir = plugins / 'Allium/tools'
    tools_dir.mkdir(exist_ok=True)
    disarmer_yaml = (REPO / 'src/main/resources/tools/mob_disarmer.yml').read_text()
    if 'nexo' in providers:
        variants = ''.join(f"\n      '{k}': {v}" for k, v in NEXO_VARIANTS.items())
        disarmer_yaml = disarmer_yaml.replace('  charge-variants: {}', '  charge-variants:\n    gate:' + variants)
        if 'nexo:mob_disarmer_2-3' not in disarmer_yaml:
            raise RuntimeError('charge-variant injection failed')
    (tools_dir / 'mob_disarmer.yml').write_text(disarmer_yaml)

    # Second definition to verify multiple definitions can bind to allium:disarm
    (tools_dir / 'wand_disarmer.yml').write_text('''schema: 1
id: allium:wand_disarmer
name: Disarm Wand
behavior: allium:disarm
enabled: true
item:
  material: stick
  name: Disarm Wand
appearance:
  default: allium:wand_disarmer
charges:
  max: 5
  recharge-seconds: 60
  break-chance:
    preset: none
targets:
  radius: 3.0
  excluded-mobs: []
  exclude-npcs: false
disarm:
  drop-rule: always
  pickup-block-seconds: 60
timing:
  click-guard-millis: 100
  status-cooldown-millis: 500
sounds:
  disarm:
    sound: minecraft:item.shield.break
    volume: 1.0
    pitch: 1.0
permissions:
  use: allium.wanddisarmer.use
  bypass: allium.wanddisarmer.bypass
messages: {}
''')

    # Invalid tool definition to verify invalid definition rejection without half-loading
    (tools_dir / 'invalid_tool.yml').write_text('''schema: 99
id: allium:invalid_tool
name: Invalid Tool
behavior: allium:disarm
enabled: true
appearance:
  aliases: [nexo:invalid_tool_alias]
charges:
  max: 0
targets:
  radious: 4
  excluded-mobs: [zombi]
''')
    for provider in providers:
        shutil.copy2(PROVIDER_JARS[provider], plugins / (provider + '.jar'))
    if a.griefprevention:
        shutil.copy2(a.griefprevention, plugins / a.griefprevention.name)
    if 'nexo' in providers:
        root = plugins / 'Nexo'
        (root / 'items').mkdir(parents=True)
        shutil.copy2(COEXIST / 'plugins/Nexo/settings.yml', root / 'settings.yml')
        (root / 'converter.yml').write_text('oraxenConverter:\n  convertItems: false\n  convertResourcePack: false\n'
                                            '  convertSettings: false\n  convertFurnitureOnLoad: false\n')
        items = ''.join(f'{v.split(":", 1)[1]}:\n  material: IRON_HOE\n  itemname: Gate Nexo Disarmer {k}/3\n'
                        for k, v in NEXO_VARIANTS.items())
        (root / 'items/allium_gate.yml').write_text(items)
    if 'craftengine' in providers:
        root = plugins / 'CraftEngine'
        root.mkdir()
        shutil.copytree(COEXIST / 'plugins/CraftEngine/libs', root / 'libs')
        with zipfile.ZipFile(PROVIDER_JARS['craftengine']) as archive:
            (root / 'commands.yml').write_bytes(archive.read('commands.yml'))
            config = archive.read('config.yml').decode()
        start = config.index('  workflows:')
        end = config.index('  # Players apply', start)
        config = config[:start] + '  workflows: {}\n  packs: {}\n\n' + config[end:]
        for old, new in [('send-on-join: true', 'send-on-join: false'), ('metrics: true', 'metrics: false'),
                         ('update-checker: true', 'update-checker: false')]:
            config = config.replace(old, new)
        (root / 'config.yml').write_text(config)
        pack = root / 'resources/allium_gate'
        (pack / 'configuration').mkdir(parents=True)
        (pack / 'pack.yml').write_text('namespace: allium_gate\nauthor: S06aTestOnly\nversion: 1.0\n')
        (pack / 'configuration/items.yml').write_text(
            'items:\n  allium_gate:mob_disarmer:\n    material: diamond_hoe\n    data:\n      item_name: "Gate CE Disarmer"\n')
    if 'oraxen' in providers:
        root = plugins / 'Oraxen'
        (root / 'items').mkdir(parents=True)
        shutil.copy2(COEXIST / 'plugins/Oraxen/settings.yml', root / 'settings.yml')
        (root / 'items/allium_gate.yml').write_text('Gate_Disarmer:\n  material: STONE_HOE\n  itemname: Gate Oraxen Disarmer\n')
    (lane / 'eula.txt').write_text('eula=true\n')
    (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
enable-query=false
enable-rcon=false
spawn-protection=0
difficulty=easy
view-distance=2
simulation-distance=2
level-name=world
level-seed=6061106
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
network-compression-threshold=256
''')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp',
                    f'{api}:{gson}:{a.allium_jar}', '-d', str(classes), *map(str, sorted((HERE / 'plugin/gate').glob('*.java')))], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'DisarmerGate.jar'), '-C', str(classes), '.'], check=True)


def argv():
    if 'template' in spec:
        libs = {}

        def version(path):
            return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
        for jar in (lane / 'libraries').rglob('*.jar'):
            artifact = jar.parent.parent
            if artifact not in libs or version(jar) > version(libs[artifact]):
                libs[artifact] = jar
        v = spec['version']
        cp = ':'.join(str(x.relative_to(lane)) for x in [lane / f'versions/{v}/paper-{v}.jar', *libs.values()])
        return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                FAULTS, '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            FAULTS, '-jar', 'server.jar', '--nogui']


def boot(label, old=False, migrated=False):
    global server, server_thread
    emit('BOOT ' + label)
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, bufsize=1)
    server_thread = threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True)
    server_thread.start()
    wait(lambda: any('Done (' in x for x in logs[start:]), 'server Done', 300)
    require(any('S06A-GATE READY' in x for x in logs[start:]), label + ' fixture enabled')
    require(any('Mob Disarmer enabled' in x for x in logs[start:]), label + ' Allium Mob Disarmer enabled')
    if not old and not migrated:
        require(any('Disarm Wand enabled' in x for x in logs[start:]), label + ' second disarmer definition enabled')
        for line in INVALID_DIAGNOSTICS:
            require(any(line in x for x in logs[start:]), f'{label} invalid definition diagnostic: "{line}"')
    require(any('Fault injection is enabled' in x for x in logs[start:]), label + ' Allium logged the fault-injection warning')
    if a.griefprevention:
        require(any('Protection hook enabled: GriefPrevention' in x for x in logs[start:]), label + ' GriefPrevention hook loaded')


def connect(label):
    global client, client_thread
    start = len(client_logs)
    script = 'client-raw26.cjs' if spec['client'] == 'raw26' else 'client-mcp.cjs'
    args = ['node', str(HERE / script), str(lane), str(port)] + ([] if spec['client'] == 'raw26' else [spec['client']])
    client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    client_thread = threading.Thread(target=pump, args=(client, run / 'client.log', client_logs), daemon=True)
    client_thread.start()
    wait(lambda: any('S06A-WIRE READY' in x for x in client_logs[start:]), label + ' client READY', 90)
    wait(lambda: status()['botOnline'], 'bot online', 30)
    if providers:
        wait_providers_ready()
    time.sleep(3.5)  # past the 1.21.4+ client-loaded grace period on every lane


def shutdown():
    global client, server
    try:
        stop_process(client, lambda: packet('stop'), timeout=10)
    except Exception as e:
        failures.append('client cleanup ' + repr(e))
    try:
        stop_process(server, lambda: cmd_raw('stop'))
    except Exception as e:
        failures.append('server cleanup ' + repr(e))
    if server_thread:
        server_thread.join(timeout=5)
    if client_thread:
        client_thread.join(timeout=5)
    client = server = None


def cmd_raw(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def interacts(observation, hand=None):
    return [x for x in observation['interactions'] if x['kind'] == 'interact' and (hand is None or x['hand'] == hand)]


def play(name, appearance, layout, kit, veto, action='click', distance=None):
    base_path = result(name + '-baseline')
    cmd(f'dg setup {name} {appearance} {layout} {kit} {veto}' + (f' {distance}' if distance is not None else ''))
    base = read(base_path)
    clicks = 2 if action == 'double' else 1
    packet(f'{action} {base["entityId"]}')

    def observed():
        path = result(name + '-observe')
        cmd('dg observe ' + name)
        obs = read(path)
        return obs if len(interacts(obs)) >= 2 * clicks else None
    wait(observed, name + ' real interaction events', 20)
    time.sleep(1.5)  # drops settle; a re-equip by pickup would happen within this window
    path = result(name + '-observe')
    cmd('dg observe ' + name)
    obs = read(path)
    (run / 'cases').mkdir(exist_ok=True)
    (run / 'cases' / (name + '.json')).write_text(json.dumps({'baseline': base, 'observed': obs}, indent=2))
    cases.append({'name': name, 'appearance': appearance, 'layout': layout, 'kit': kit, 'veto': veto, 'action': action})
    return base, obs


def same_tool(before, after, label):
    for key in ['behavior', 'appearance', 'state', 'uuid', 'signature', 'alliumPdc', 'material', 'amount']:
        require(before.get(key) == after.get(key), f'{label} unchanged {key}')


def spent_once(before, after, label, expected_appearance, charges=2, uses=1):
    require(after.get('behavior') == 'allium:mob_disarmer', label + ' still verifies as allium:mob_disarmer')
    require(after['state'].get('charges') == str(charges) and after['state'].get('uses') == str(uses)
            and after['state'].get('broken') == 'false', f'{label} exactly one charge spent (state {after["state"]})')
    require(after.get('uuid') and after['uuid'] == before['uuid'], label + ' instance UUID kept')
    require(after.get('signature') and after['signature'] != before['signature'], label + ' MAC re-signed')
    require(after.get('appearance') == expected_appearance, f'{label} appearance {after.get("appearance")} == {expected_appearance}')


def equipment_intact(obs, label):
    mob = obs['mob']
    require(all(mob[s]['marker'] == f'{label}:{s}' for s in SLOTS), label + ' every equipment piece still on the mob')
    require(all(abs(mob[s]['chance'] - FULL_CHANCES[s]) < 1e-6 for s in SLOTS), label + ' drop chances untouched')
    require(obs['ground'] == [] and mine(obs['playerInventory'], label) == [] and obs['spawnedMarkers'] == [], label + ' nothing dropped')


def mine(markers, label):
    # The bot's inventory may still hold pieces it picked up in earlier cases.
    return [x for x in markers if x.startswith(label + ':')]


def disarmed(obs, label):
    mob = obs['mob']
    require(all(mob[s]['item'] is None for s in SLOTS), label + ' every slot emptied')
    require(all(mob[s]['chance'] == 0.0 for s in SLOTS), label + ' every slot drop chance zeroed')
    expected = sorted(f'{label}:{s}' for s in DROPPED)
    located = sorted(obs['ground'] + mine(obs['playerInventory'], label))
    require(located == expected, f'{label} dropped pieces exist exactly once (ground or picked up by the player) {expected}: {located}')
    require(sorted(obs['spawnedMarkers']) == expected, label + ' each dropped piece spawned exactly once')


def main_hand_events(obs, label, cancelled):
    events = interacts(obs, 'HAND')
    require(len(events) >= 1 and all(e['cancelled'] == cancelled for e in events),
            f'{label} real main-hand PlayerInteractEntityEvent cancelled={cancelled}: {events}')
    require(len(interacts(obs, 'OFF_HAND')) >= 1, label + ' real off-hand interaction also arrived')


def kill_check(name):
    path = result(name + '-kill')
    cmd('dg kill ' + name)
    killed = read(path)
    require(killed['mobDead'], name + ' mob killed by the player')
    require(killed['deathMarkers'] == [], f'{name} death dropped no disarmed piece: {killed["deathMarkers"]}')
    expected = sorted(f'{name}:{s}' for s in DROPPED)
    located = sorted(killed['ground'] + mine(killed['playerInventory'], name))
    require(located == expected, f'{name} no duplicate after kill (ground and player inventory): {located}')


def appearance_after(label, charges):
    return NEXO_VARIANTS[str(charges)] if label == 'nexo' else APPEARANCES[label]


def run_cases():
    for label in labels:
        appearance = APPEARANCES[label]
        prefix = label + '-'
        emit('APPEARANCE ' + appearance)

        name = prefix + 'authorized'
        base, obs = play(name, appearance, 'main', 'full', 'none')
        require(base['main']['behavior'] == 'allium:mob_disarmer' and base['main']['uuid'] and base['main']['signature'],
                name + ' fresh tool is a signed allium:mob_disarmer')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 2))
        disarmed(obs, name)
        main_hand_events(obs, name, True)
        require(obs['checkEvents'] == 1, name + ' protection check event fired once')
        kill_check(name)

        name = prefix + 'protected'
        base, obs = play(name, appearance, 'main', 'full', 'check-event')
        same_tool(base['main'], obs['main'], name)
        equipment_intact(obs, name)
        main_hand_events(obs, name, False)
        require(obs['checkEvents'] == 1, name + ' cancelled protection check event consulted once')

        name = prefix + 'cancelled'
        base, obs = play(name, appearance, 'main', 'full', 'interact')
        same_tool(base['main'], obs['main'], name)
        equipment_intact(obs, name)
        main_hand_events(obs, name, True)
        require(obs['checkEvents'] == 0, name + ' cancelled interaction never reached Allium')

        name = prefix + 'empty'
        base, obs = play(name, appearance, 'main', 'empty', 'none')
        same_tool(base['main'], obs['main'], name)
        main_hand_events(obs, name, False)
        require(obs['checkEvents'] == 0 and obs['ground'] == [], name + ' empty target consulted nothing and dropped nothing')

        name = prefix + 'double'
        base, obs = play(name, appearance, 'main', 'full', 'none', 'double')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 2))
        disarmed(obs, name)
        require(len(interacts(obs, 'HAND')) >= 2, name + ' both rapid main-hand clicks arrived')
        kill_check(name)

        name = prefix + 'offhand'
        base, obs = play(name, appearance, 'offhand', 'full', 'none')
        same_tool(base['off'], obs['off'], name)
        equipment_intact(obs, name)
        require(obs['checkEvents'] == 0, name + ' off-hand tool never consulted protection')

        name = prefix + 'both-hands'
        base, obs = play(name, appearance, 'both', 'full', 'none')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 2))
        same_tool(base['off'], obs['off'], name + ' off-hand')
        disarmed(obs, name)

        if label == 'allium':
            write_fault()


def definition_cases():
    """A second definition bound to allium:disarm applies its own charges and radius; the invalid one registered nothing."""
    wand = 'allium:wand_disarmer'
    name = 'wand-authorized'
    base, obs = play(name, wand, 'main', 'full', 'none')
    require(base['main']['behavior'] == wand and base['main']['uuid'] and base['main']['signature'] and base['main']['material'] == 'STICK',
            f'{name} the wand is its own signed identity on its own built-in item: {base["main"]["behavior"]} {base["main"]["material"]}')
    require(obs['main'].get('behavior') == wand and obs['main']['state'].get('charges') == '4' and obs['main']['state'].get('uses') == '1',
            f'{name} its own 5 charges: one spent leaves 4 ({obs["main"]["state"]})')
    require(obs['main']['uuid'] == base['main']['uuid'] and obs['main']['signature'] != base['main']['signature'], name + ' UUID kept, re-signed')
    mob = obs['mob']
    everything = sorted(f'{name}:{s}' for s in SLOTS)
    located = sorted(obs['ground'] + mine(obs['playerInventory'], name))
    require(all(mob[s]['item'] is None for s in SLOTS) and located == everything,
            f'{name} its own drop-rule (always): all six pieces exist exactly once: {located}')

    name = 'wand-too-far'
    base, obs = play(name, wand, 'main', 'full', 'none', distance=4.5)
    same_tool(base['main'], obs['main'], name)
    equipment_intact(obs, name)
    require(obs['checkEvents'] == 0, name + ' its own 3-block radius refused a mob 4.5 blocks away before protection')

    name = 'mob-disarmer-4.5'
    base, obs = play(name, APPEARANCES['allium'], 'main', 'full', 'none', distance=4.5)
    spent_once(base['main'], obs['main'], name, APPEARANCES['allium'])
    disarmed(obs, name)
    require(obs['checkEvents'] == 1, name + ' the Mob Disarmer\'s 6-block radius reaches the same mob at 4.5')

    for identity in ('allium:invalid_tool', 'nexo:invalid_tool_alias'):
        path = result('probe')
        cmd('dg probe ' + identity)
        probe = read(path)
        require(not probe['builtInItem'] and probe['boundBehavior'] is None and not probe['signedBehavior']
                and not probe['useNodeRegistered'] and not probe['creates'],
                f'the rejected definition registered nothing for {identity}: {probe}')


def write_fault():
    # One rejected ItemService.updateState in the middle of a real disarm: after protection and
    # the recheck, with every piece already taken off the mob.
    name = 'allium-write-fault'
    start = len(logs)
    base, obs = play(name, APPEARANCES['allium'], 'main', 'full', 'write-fault')
    require(base['stateWriteFault'] == {'enabled': True, 'armed': 1, 'tripped': 0}, name + ' one state-write fault armed')
    require(obs['stateWriteFault'] == {'enabled': True, 'armed': 0, 'tripped': 1},
            f'{name} the disarm reached updateState and it was rejected exactly once: {obs["stateWriteFault"]}')
    require(obs['checkEvents'] == 1, name + ' protection was consulted once before the write')
    require(any('state write was rejected' in x for x in logs[start:]), name + ' Allium logged the rejected write')
    same_tool(base['main'], obs['main'], name)
    require(obs['mob'] == base['mob'], name + ' equipment and drop chances restored exactly (field-by-field equal to baseline)')
    equipment_intact(obs, name)
    main_hand_events(obs, name, False)
    path = result(name + '-kill')
    cmd('dg kill ' + name)
    killed = read(path)
    expected = sorted(f'{name}:{s}' for s in DROPPED)
    require(killed['mobDead'], name + ' mob killed by the player')
    require(sorted(killed['deathMarkers']) == expected,
            f'{name} death drops follow the restored chances (1.0/2.0/1.0 drop, 0.0 never): {killed["deathMarkers"]}')
    located = sorted(killed['ground'] + mine(killed['playerInventory'], name))
    require(located == expected, f'{name} each piece exists exactly once after the kill: {located}')


def compare_keepers(before, after, stage):
    for i, label in enumerate(labels):
        slot = str(9 + i)
        same_tool(before[slot], after[slot], f'{stage} keeper {label}')
        require(after[slot].get('behavior') == 'allium:mob_disarmer', f'{stage} keeper {label} verifies')


def keepers_and_persistence():
    for i, label in enumerate(labels):
        name = 'keep-' + label
        base, obs = play(name, APPEARANCES[label], 'main', 'full', 'none')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 2))
        cmd(f'dg keep {9 + i}')
    path = result('before-reload')
    cmd('dg snapshot before-reload')
    before = read(path)
    if providers:
        for command, marker in [('nexo reload items', None), ('ce reload', None), ('oraxen reload items', None)]:
            emit('RELOAD ' + command)
            cmd_raw(command)
            time.sleep(4)
        wait_providers_ready()
        path = result('after-reload')
        cmd('dg snapshot after-reload')
        compare_keepers(before, read(path), 'provider-reload')
    shutdown()
    boot('restart')
    connect('restart')
    path = result('after-restart')
    cmd('dg snapshot after-restart')
    compare_keepers(before, read(path), 'restart')
    for i, label in enumerate(labels):
        cmd(f'dg take {9 + i}')
        name = 'reuse-' + label
        base, obs = play(name, APPEARANCES[label], 'held', 'full', 'none')
        require(base['main']['uuid'] == before[str(9 + i)]['uuid'], name + ' the persisted keeper is in hand')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 1), charges=1, uses=2)
        disarmed(obs, name)
        cmd(f'dg keep {9 + i}')


def griefprevention_claim():
    path = result('gpclaim')
    cmd('dg gpclaim')
    claim = read(path)
    emit('CLAIM ' + json.dumps(claim))
    require(claim['succeeded'], 'admin claim created over the arena')
    name = 'gp-claimed'
    base, obs = play(name, APPEARANCES['allium'], 'main', 'full', 'none')
    same_tool(base['main'], obs['main'], name)
    equipment_intact(obs, name)
    require(obs['checkEvents'] == 0, name + ' claim hook denied before the generic check event')
    require(len(interacts(obs, 'HAND')) >= 1, name + ' real main-hand interaction arrived')
    return claim


def quick_cases():
    """One authorized disarm per appearance: the provider appearance check on 1.21.8."""
    for label in labels:
        name = label + '-authorized'
        base, obs = play(name, APPEARANCES[label], 'main', 'full', 'none')
        require(base['main']['behavior'] == 'allium:mob_disarmer' and base['main']['uuid'] and base['main']['signature'],
                f'{name} fresh {APPEARANCES[label]} tool is a signed allium:mob_disarmer (native {base["main"].get("native")})')
        spent_once(base['main'], obs['main'], name, appearance_after(label, 2))
        disarmed(obs, name)
        require(obs['checkEvents'] == 1, name + ' protection check event fired once')


OLD_CONFIG_RECHARGE = 90


def upgrade():
    """A disarmer made by a jar from before tool definitions keeps working on this jar.

    The old jar runs with its own config.yml, customized (recharge-seconds 90). It makes a spent
    keeper; then only Allium.jar is replaced. The keeper must be byte-for-byte the same tool
    (identity, state, UUID, MAC, all allium PDC), the config section must move into
    tools/mob_disarmer.yml, and both the tool and the moved setting must work.
    """
    plugins = lane / 'plugins'
    shutil.rmtree(plugins / 'Allium/tools')
    old_config = subprocess.run(['git', '-C', str(REPO), 'show', '6a74e7f:src/main/resources/config.yml'],
                                check=True, capture_output=True, text=True).stdout
    customized = old_config.replace('    recharge-seconds: 120\n', f'    recharge-seconds: {OLD_CONFIG_RECHARGE}\n', 1)
    if customized == old_config or '  mob-disarmer:' not in customized:
        raise RuntimeError('could not customize the old config')
    (plugins / 'Allium/config.yml').write_text(customized)
    shutil.copy2(a.upgrade_from, plugins / 'Allium.jar')
    emit('UPGRADE old jar ' + hashlib.sha256(a.upgrade_from.read_bytes()).hexdigest()[:12])
    boot('old-jar', old=True)
    cmd('dg prepare')
    connect('old-jar')
    name = 'keep-old'
    base, obs = play(name, APPEARANCES['allium'], 'main', 'full', 'none')
    spent_once(base['main'], obs['main'], name, APPEARANCES['allium'])
    cmd('dg keep 9')
    path = result('before-upgrade')
    cmd('dg snapshot before-upgrade')
    before = read(path)
    shutdown()
    shutil.copy2(a.allium_jar, plugins / 'Allium.jar')
    start = len(logs)
    boot('upgraded', old=False, migrated=True)
    moved = [x.strip() for x in logs[start:] if 'Mob Disarmer setting(s) from config.yml' in x]
    definition = (plugins / 'Allium/tools/mob_disarmer.yml').read_text()
    require(bool(moved) and f'recharge-seconds: {OLD_CONFIG_RECHARGE}' in definition,
            f'the old tools.mob-disarmer section moved into tools/mob_disarmer.yml with its customized recharge: {moved}')
    connect('upgraded')
    path = result('after-upgrade')
    cmd('dg snapshot after-upgrade')
    after = read(path)
    same_tool(before['9'], after['9'], 'upgraded keeper')
    require(after['9'].get('behavior') == 'allium:mob_disarmer', 'the old jar\'s keeper verifies on the new jar')
    cmd('dg take 9')
    name = 'reuse-upgraded'
    base, obs = play(name, APPEARANCES['allium'], 'held', 'full', 'none')
    require(base['main']['uuid'] == before['9']['uuid'], name + ' the old jar\'s keeper is in hand')
    spent_once(base['main'], obs['main'], name, APPEARANCES['allium'], charges=1, uses=2)
    disarmed(obs, name)
    name = 'reuse-upgraded-last'
    base, obs = play(name, APPEARANCES['allium'], 'held', 'full', 'none')
    state = obs['main']['state']
    remaining = int(state.get('recharge_at', '0')) - obs['now']
    require(state.get('charges') == '0' and abs(remaining - OLD_CONFIG_RECHARGE * 1000) < 5000,
            f'{name} the last charge starts the moved {OLD_CONFIG_RECHARGE}s recharge (recharge_at in {remaining} ms)')


summary = {}
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    build_fixture()
    write_lane()
    if a.upgrade_from:
        upgrade()
    else:
        boot('initial')
        cmd('dg prepare')
        connect('initial')
        if a.quick:
            quick_cases()
        else:
            run_cases()
            definition_cases()
            keepers_and_persistence()
            if a.griefprevention:
                griefprevention_claim()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    if server is not None or client is not None:
        shutdown()
    errors = [x.rstrip() for x in logs if re.search(r'Exception|SEVERE|ERROR\]', x) and 'S06A' not in x]
    listener_errors = [x for x in logs if 'Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
    require(not listener_errors, 'no listener or Allium exceptions in server log: ' + json.dumps(listener_errors[:5]))
    hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane / 'plugins').glob('*.jar')} if (lane / 'plugins').exists() else {}
    for name in ['server.jar']:
        if (lane / name).exists():
            hashes[name] = hashlib.sha256((lane / name).read_bytes()).hexdigest()
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    summary = {'lane': a.lane, 'port': port, 'java': spec['java'], 'client': spec['client'], 'server': version,
               'providers': providers, 'appearances': [APPEARANCES[x] for x in labels], 'caseCount': len(cases),
               'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures,
               'serverLogErrors': errors[:40], 'hashes': hashes, 'cases': cases}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'server', 'caseCount', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
