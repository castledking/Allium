#!/usr/bin/env python3
"""S04 item-library gate: the M1 /give harness against real servers with a real protocol client.

Every give is a console command; the GiveGate fixture records what the server holds afterwards and
arms test-only faults (slow or hanging skull lookups, a cancelled ItemGiveEvent). Disposable lanes
only: binaries, immutable library caches and retained provider settings are copied; worlds, player
data and keys are fresh. Exits nonzero on any failed check.
"""
import argparse, hashlib, json, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

COEXIST = Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-1.21.8-coexist')
LANES = {
    'spigot-1.20.1': {'port': 25670, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': '1.20.1',
                      'server': Path('/tmp/allium-server-cache/spigot-1.20.1.jar'), 'providers': [], 'components': False},
    'paper-1.21.8': {'port': 25671, 'java': '/usr/lib/jvm/java-21-openjdk/bin/java', 'client': '1.21.8',
                     'server': Path('/tmp/allium-s05-20261106/lanes/artifacts/paper-1.21.8-60.jar'),
                     'providers': ['nexo', 'craftengine', 'oraxen'], 'components': True},
    'paper-26.3': {'port': 25672, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': 'raw26',
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane'), 'providers': [], 'components': True},
}
PROVIDER_JARS = {
    'nexo': Path('/tmp/allium-s05-20261106/supplied/nexo.jar'),
    'craftengine': Path('/tmp/allium-s05-20261106/pins/ce/craft-engine-paper-plugin-26.9.1.jar'),
    'oraxen': Path('/tmp/allium-s05-20261106/supplied/oraxen.jar'),
}
APPEARANCES = {
    'allium': 'allium:mob_disarmer',
    'nexo': 'nexo:mob_disarmer',
    'craftengine': 'craftengine:allium_gate:mob_disarmer',
    'oraxen': 'oraxen:Gate_Disarmer',
}
NEXO_VARIANTS = {'3': 'nexo:mob_disarmer', '2': 'nexo:mob_disarmer_2-3', '1': 'nexo:mob_disarmer_1-3', '0': 'nexo:mob_disarmer_0-3'}
FAULTS = '-Dallium.fault-injection=true'
NAME = 'Blade, of Testing; Mk II'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s04g-20261107/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s04g-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s04g-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
lane = run / 'lane'
lane.mkdir(parents=True)
data = lane / 'plugins/GiveGate'
port = spec['port']
providers = spec['providers']

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


def console(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def gg(text):
    start = len(logs)
    console('gg ' + text)
    wait(lambda: any(('S04G-DONE ' + text) in x or ('S04G-FAIL ' + text) in x for x in logs[start:]), 'gg ' + text, 30)
    if any(('S04G-FAIL ' + text) in x for x in logs[start:]):
        raise RuntimeError('fixture failed: gg ' + text)


def read_json(name):
    path = data / (name + '.json')
    path.unlink(missing_ok=True)
    return path


def status():
    path = read_json('status')
    gg('status')
    wait(path.exists, 'status', 30)
    return json.loads(path.read_text())


def snapshot(name):
    path = read_json('snapshot-' + name)
    gg('snapshot ' + name)
    wait(path.exists, 'snapshot ' + name, 30)
    out = json.loads(path.read_text())
    (run / 'cases').mkdir(exist_ok=True)
    (run / 'cases' / (name + '.json')).write_text(json.dumps(out, indent=2))
    return out


def give(args, expect, timeout=15):
    """Runs a console give and returns the first Allium reply line containing any of the expected fragments."""
    start = len(logs)
    console('give ' + args)
    fragments = expect if isinstance(expect, list) else [expect]
    line = wait(lambda: next((x for x in logs[start:] for f in fragments if f in x), None), 'reply to give ' + args, timeout)
    cases.append({'give': args, 'reply': line.strip()})
    return line, logs[start:]


def write_lane():
    if a.lane == 'paper-26.3':
        for name in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / name, lane / name)
    elif a.lane == 'paper-1.21.8':
        for name in ['cache', 'libraries', 'versions']:
            shutil.copytree(COEXIST / name, lane / name)
        shutil.copy2(spec['server'], lane / 'server.jar')
    else:
        shutil.copy2(spec['server'], lane / 'server.jar')
    plugins = lane / 'plugins'
    plugins.mkdir(exist_ok=True)
    shutil.copy2(a.allium_jar, plugins / 'Allium.jar')
    shutil.copy2(run / 'fixture/GiveGate.jar', plugins / 'GiveGate.jar')
    allium_config = (REPO / 'src/main/resources/config.yml').read_text()
    if providers:
        # Bind each provider's gate disarmer to the Mob Disarmer behavior, so /give stamps and signs it.
        aliases = '[' + ', '.join(APPEARANCES[p] for p in providers) + ']'
        allium_config = allium_config.replace('    aliases: []\n', f'    aliases: {aliases}\n')
        if APPEARANCES['oraxen'] not in allium_config:
            raise RuntimeError('alias injection failed')
    (plugins / 'Allium').mkdir()
    (plugins / 'Allium/config.yml').write_text(allium_config)
    for provider in providers:
        shutil.copy2(PROVIDER_JARS[provider], plugins / (provider + '.jar'))
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
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/GiveGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'GiveGate.jar'), '-C', str(classes), '.'], check=True)


def argv():
    if a.lane == 'paper-26.3':
        libs = {}

        def version(path):
            return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
        for jar in (lane / 'libraries').rglob('*.jar'):
            artifact = jar.parent.parent
            if artifact not in libs or version(jar) > version(libs[artifact]):
                libs[artifact] = jar
        cp = ':'.join(str(x.relative_to(lane)) for x in [lane / 'versions/26.3/paper-26.3.jar', *libs.values()])
        return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                FAULTS, '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            FAULTS, '-jar', 'server.jar', '--nogui']



def boot(label):
    global server, server_thread
    emit('BOOT ' + label)
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, bufsize=1)
    server_thread = threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True)
    server_thread.start()
    wait(lambda: any('Done (' in x for x in logs[start:]), 'server Done', 300)
    require(any('S04G-GATE READY' in x for x in logs[start:]), label + ' fixture enabled')
    require(any('Item library enabled' in x for x in logs[start:]), label + ' Allium item library enabled')


def connect(label):
    global client, client_thread
    start = len(client_logs)
    script = 'client-raw26.cjs' if spec['client'] == 'raw26' else 'client-mcp.cjs'
    args = ['node', str(HERE / script), str(lane), str(port)] + ([] if spec['client'] == 'raw26' else [spec['client']])
    client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    client_thread = threading.Thread(target=pump, args=(client, run / 'client.log', client_logs), daemon=True)
    client_thread.start()
    wait(lambda: any('S04G-WIRE READY' in x for x in client_logs[start:]), label + ' client READY', 90)
    wait(lambda: status()['botOnline'], 'bot online', 30)
    if providers:
        wait(lambda: (lambda s: s if all(s[x] == 'READY' for x in providers) else None)(status()), 'providers READY', 120)


def disconnect():
    global client
    stop_process(client, lambda: (client.stdin.write('stop\n'), client.stdin.flush()), timeout=10)
    if client_thread:
        client_thread.join(timeout=5)
    client = None


def slots_of(snap, material):
    return [s for s in snap['slots'] if s['material'] == material]


def disarmer_cases():
    labels = ['allium'] + providers
    for label in labels:
        appearance = APPEARANCES[label]
        gg('clear')
        line, _ = give(f'GateBot {appearance}', ['Gave 1x ' + appearance, 'Could not give', 'Invalid item spec'])
        require(f'Gave 1x {appearance} to GateBot' in line, f'{label}: Allium handled the give ({line.strip()[-90:]})')
        snap = snapshot('disarmer-' + label)
        tools = [s for s in snap['slots'] if s['behavior'] == 'allium:mob_disarmer']
        require(len(tools) == 1, f'{label}: one verified allium:mob_disarmer in the inventory')
        if tools:
            tool = tools[0]
            require(tool['appearance'] == appearance.lower() or tool['appearance'] == appearance,
                    f'{label}: appearance {tool["appearance"]}')
            require(bool(tool['instanceUuid']) and str(tool['signature']).startswith('v2:'),
                    f'{label}: signed (instance UUID and v2 MAC; verified by identify)')
            if label == 'allium':
                require(tool['customModelData'] == 2577020 and tool['material'] == 'GOLDEN_HOE',
                        f'allium: built-in golden hoe with full-charge model ({tool["customModelData"]})')
    gg('clear')
    line, _ = give('GateBot allium:mob_disarmer 3', 'Gave 3x allium:mob_disarmer')
    snap = snapshot('disarmer-three')
    tools = [s for s in snap['slots'] if s['behavior'] == 'allium:mob_disarmer']
    require(len(tools) == 3 and all(t['amount'] == 1 for t in tools) and len({t['instanceUuid'] for t in tools}) == 3,
            'amount 3 mints three single disarmers with distinct instance UUIDs')


def overflow_cases():
    gg('fill 1')
    line, _ = give('GateBot diamond 100 --overflow=refuse', ['cannot hold', 'Gave'])
    snap = snapshot('overflow-refuse')
    require('cannot hold' in line and 'nothing was given' in line, f'refuse reported: {line.strip()[-90:]}')
    require(not slots_of(snap, 'DIAMOND') and not snap['ground'], 'refuse gave and dropped nothing')

    gg('fill 1')
    line, _ = give('GateBot diamond 100 --overflow=partial', ['did not fit and were not given', 'Gave'])
    snap = snapshot('overflow-partial')
    given = sum(s['amount'] for s in slots_of(snap, 'DIAMOND'))
    require('Gave 64 of 100x diamond' in line and '36 did not fit and were not given' in line, f'partial reported: {line.strip()[-110:]}')
    require(given == 64 and not snap['ground'], f'partial gave {given} and dropped nothing')

    gg('fill 1')
    line, _ = give('GateBot diamond 100 --overflow=drop', ['dropped at their feet', 'Gave'])
    time.sleep(1.0)
    snap = snapshot('overflow-drop')
    given = sum(s['amount'] for s in slots_of(snap, 'DIAMOND'))
    on_ground = sum(g['amount'] for g in snap['ground'] if g['material'] == 'DIAMOND')
    require('Gave 64 of 100x diamond' in line and '36 did not fit and dropped' in line, f'drop reported: {line.strip()[-110:]}')
    require(given == 64 and on_ground == 36, f'drop gave {given} and dropped {on_ground} at the feet')

    gg('clear')
    gg('cancel-next')
    line, _ = give('GateBot diamond', ['cancelled', 'Gave'])
    snap = snapshot('cancelled')
    require('cancelled' in line and not slots_of(snap, 'DIAMOND'), 'a cancelled ItemGiveEvent gives nothing')


def skull_cases():
    gg('clear')
    line, _ = give('GateBot player_head owner:GateBot', 'Gave 1x player_head')
    snap = snapshot('skull-online')
    heads = slots_of(snap, 'PLAYER_HEAD')
    require(len(heads) == 1 and heads[0].get('skullOwner') == 'GateBot', 'an online owner resolves at once')

    line, _ = give('NoSuchPlayer stone', 'not found')
    require('NoSuchPlayer' in line, 'an offline target is refused before anything is built')

    gg('clear')
    gg('fault skull.lookup-slow 1')
    start = len(logs)
    console('give GateBot player_head owner:Notch')
    wait(lambda: any('Resolving skull profiles for GateBot' in x for x in logs[start:]), 'pending skull', 10)
    disconnect()
    line = wait(lambda: next((x for x in logs[start:] if 'left before the items were ready' in x or 'Gave 1x player_head' in x), None),
                'skull outcome after disconnect', 15)
    cases.append({'give': 'GateBot player_head owner:Notch (bot disconnects during a 2 s lookup)', 'reply': line.strip()})
    require('left before the items were ready' in line, f'a target who disconnects during async resolution gets nothing: {line.strip()[-90:]}')
    connect('rejoin')
    snap = snapshot('skull-disconnected')
    require(not slots_of(snap, 'PLAYER_HEAD') and not snap['ground'], 'after rejoining: no head in the inventory or on the ground')

    gg('clear')
    gg('fault skull.lookup-hang 1')
    started = time.monotonic()
    start = len(logs)
    # A name the earlier slow lookup did not cache.
    line, _ = give('GateBot player_head owner:Dinnerbone', 'Gave 1x player_head', timeout=20)
    elapsed = time.monotonic() - started
    warned = wait(lambda: next((x for x in logs[start:] if 'timed out after 3000 ms' in x), None), 'skull timeout warning', 5)
    cases.append({'give': 'warning', 'reply': warned.strip()})
    snap = snapshot('skull-timeout')
    require(len(slots_of(snap, 'PLAYER_HEAD')) == 1 and warned and elapsed >= 2.5,
            f'a lookup that never answers times out after 3 s ({elapsed:.1f} s) and gives an untextured head with a warning')


def raw_cases():
    gg('clear')
    if spec['components']:
        line, _ = give(f'GateBot diamond_sword[custom_name={{text:"{NAME}",italic:false}},unbreakable={{}}]', ['Gave', 'Could not give'])
        snap = snapshot('raw-components')
        swords = slots_of(snap, 'DIAMOND_SWORD')
        require('Gave 1x' in line and len(swords) == 1 and swords[0]['unbreakable'] and NAME in str(swords[0]['name']),
                f'[...] components apply, commas and semicolons included: {swords[0]["name"] if swords else line.strip()[-80:]}')
        line, _ = give('GateBot diamond_sword{Unbreakable:1b}', ['Could not give', 'Gave'])
        require('uses [...] data components' in line, f'{{...}} NBT is rejected: {line.strip()[-90:]}')
    else:
        line, _ = give('GateBot minecraft:diamond_sword[unbreakable={}]', ['Could not give', 'Gave'])
        require('require Minecraft 1.20.5+' in line, f'[...] components are rejected on 1.20.1: {line.strip()[-90:]}')
        line, _ = give("GateBot diamond_sword{display:{Name:'{\"text\":\"" + NAME + "\"}'},Unbreakable:1b}", ['Gave', 'Could not give'])
        snap = snapshot('raw-nbt')
        swords = slots_of(snap, 'DIAMOND_SWORD')
        require('Gave 1x' in line and len(swords) == 1 and swords[0]['unbreakable'] and NAME in str(swords[0]['name']),
                f'{{...}} NBT applies on 1.20.1, commas and semicolons included: {swords[0]["name"] if swords else line.strip()[-80:]}')
    line, _ = give('GateBot allium:mob_disarmer[unbreakable={}]', ['Could not give', 'Gave'])
    require('applies only to vanilla items' in line, 'raw data on an Allium item is refused')


def token_cases():
    gg('clear')
    give('GateBot diamond_sword sharpness:5 name:&6Gate_Blade lore:One|"Two words" unbreakable', 'Gave 1x diamond_sword')
    give('GateBot potion potion:swiftness effect:speed:2:60', 'Gave 1x potion')
    snap = snapshot('tokens')
    sword = slots_of(snap, 'DIAMOND_SWORD')
    potion = slots_of(snap, 'POTION')
    require(sword and sword[0]['enchants'].get('sharpness') == 5 and 'Gate Blade' in str(sword[0]['name'])
            and sword[0]['lore'] == ['One', 'Two words'] and sword[0]['unbreakable'],
            f'enchant, name, lore and unbreakable tokens apply: {sword[0] if sword else None}')
    require(potion and str(potion[0].get('potion')).upper() in ('SWIFTNESS', 'SPEED'), f'potion: base type set: {potion[0] if potion else None}')
    if providers:
        line, _ = give('GateBot nexo:does_not_exist', ['Could not give', 'Gave'])
        require('UNKNOWN_ID' in line, f'an unknown provider id is reported as UNKNOWN_ID: {line.strip()[-80:]}')
    else:
        line, _ = give('GateBot oraxen:gate_disarmer', ['Could not give', 'Gave'])
        require('PROVIDER_ABSENT' in line, f'an absent provider is reported as PROVIDER_ABSENT: {line.strip()[-80:]}')


summary = {}
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    build_fixture()
    write_lane()
    boot('initial')
    connect('initial')
    disarmer_cases()
    overflow_cases()
    raw_cases()
    token_cases()
    skull_cases()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    if client is not None:
        try:
            disconnect()
        except Exception as e:
            failures.append('client cleanup ' + repr(e))
    if server is not None:
        try:
            stop_process(server, lambda: console('stop'))
        except Exception as e:
            failures.append('server cleanup ' + repr(e))
    errors = [x.rstrip() for x in logs if re.search(r'Exception|SEVERE|ERROR\]', x) and 'S04G' not in x]
    allium_errors = [x for x in logs if 'Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
    require(not allium_errors, 'no listener or Allium exceptions in server log: ' + json.dumps(allium_errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane / 'plugins').glob('*.jar')} if (lane / 'plugins').exists() else {}
    summary = {'lane': a.lane, 'port': port, 'server': version, 'client': spec['client'], 'providers': providers,
               'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures,
               'serverLogErrors': errors[:40], 'hashes': hashes, 'gives': cases}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
        target = lane / name
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.exists():
            target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
