#!/usr/bin/env python3
"""S09 gate: the minimal versioned addon API on Spigot 1.20.1 and Paper 26.3.

Three test addons are compiled against Allium-API-<version>.jar and spigot-api only (never against
Allium.jar), so they can use nothing but the API:
  AddonGate    allium-api "^1.0": one item definition, one command spec, server and async scheduling.
  FutureAddon  allium-api "^2.0": must be refused and disabled while everything else keeps running.
  ShadedAddon  allium-api "^1.0", but its jar bundles the API classes: must be refused.
A second boot without Allium checks Bukkit's own missing-dependency message for AddonGate.
Disposable lanes only; exits nonzero on any failed check.
"""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'port': 25665, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'),
                      'jar': Path('/tmp/allium-server-cache/spigot-1.20.1.jar')},
    'paper-26.3': {'port': 25666, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane')},
}
SPIGOT_API = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
BOT = 'AddonBot'
API_PREFIXES = ('META-INF/', 'codes/castled/allium/api/', 'codes/castled/allium/command/spec/',
                'codes/castled/allium/scheduler/TaskScheduler', 'codes/castled/allium/scheduler/TaskHandle')

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s09-20261108/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--api-jar', type=Path, default=REPO / 'build/libs/Allium-API-1.0.0.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s09-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s09-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
port = spec['port']
driver, failures, evidence = [], [], {}
server = None
logs = []
client = None
client_logs = []
lane = None


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


def wait(fn, label, timeout=60):
    end = time.monotonic() + timeout
    while True:
        value = fn()
        if value:
            return value
        if server is not None and server.poll() is not None:
            raise RuntimeError(f'server exited {server.returncode} waiting for {label}')
        if time.monotonic() > end:
            raise RuntimeError('timeout ' + label)
        time.sleep(0.05)


def console(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def line_with(fragment, start=0):
    return next((x.strip() for x in logs[start:] if fragment in x), None)


def addon(action, timeout=20):
    start = len(logs)
    console('addongate ' + action)
    tag = 'ADDONG-' + action.split()[0].upper() + ' '
    line = wait(lambda: line_with(tag, start), 'addongate ' + action, timeout)
    return json.loads(line.split(tag, 1)[1])


def build_addons():
    """Compiles each addon against the API jar and spigot-api only; ShadedAddon also bundles the API classes."""
    out = run / 'addons'
    for name, source in [('AddonGate', 'good'), ('FutureAddon', 'future'), ('ShadedAddon', 'shaded')]:
        classes = out / name
        classes.mkdir(parents=True)
        sources = [str(f) for f in (HERE / source).rglob('*.java')]
        subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{SPIGOT_API}:{a.api_jar}',
                        '-d', str(classes), *sources], check=True)
        shutil.copy2(HERE / source / 'plugin.yml', classes / 'plugin.yml')
        if name == 'ShadedAddon':
            with zipfile.ZipFile(a.api_jar) as api:
                for entry in api.namelist():
                    if entry.endswith('.class'):
                        target = classes / entry
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(api.read(entry))
        subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / f'{name}.jar'), '-C', str(classes), '.'], check=True)


def write_lane(name, plugins):
    global lane
    lane = run / name
    lane.mkdir()
    if 'template' in spec:
        for d in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / d, lane / d)
    else:
        shutil.copy2(spec['jar'], lane / 'server.jar')
    (lane / 'plugins').mkdir()
    for jar in plugins:
        shutil.copy2(jar, lane / 'plugins' / jar.name)
    (lane / 'eula.txt').write_text('eula=true\n')
    (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
level-name=world
level-seed=9011108
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
''')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')


def argv():
    if 'template' in spec:
        libs = {}

        def version(path):
            return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
        for jar in (lane / 'libraries').rglob('*.jar'):
            artifact = jar.parent.parent
            if artifact not in libs or version(jar) > version(libs[artifact]):
                libs[artifact] = jar
        cp = ':'.join(str(x.relative_to(lane)) for x in [lane / 'versions/26.3/paper-26.3.jar', *libs.values()])
        return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'server.jar', '--nogui']


def boot(label):
    global server
    emit('BOOT ' + label)
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1)
    threading.Thread(target=pump, args=(server, run / f'server-{lane.name}.log', logs), daemon=True).start()
    wait(lambda: any('Done (' in x for x in logs[start:]), label + ' Done', 600)
    return start


def stop_server():
    global server
    if server is not None and server.poll() is None:
        stop_process(server, lambda: console('stop'), timeout=90)
    server = None


def connect():
    global client
    kind, version = spec['client']
    script = 'client-raw26.cjs' if kind == 'raw26' else 'client-mcp.cjs'
    args = ['node', str(HERE / script), str(lane), str(port)] + ([version] if version else []) + [BOT]
    start = len(client_logs)
    client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    threading.Thread(target=pump, args=(client, run / 'client.log', client_logs), daemon=True).start()
    wait(lambda: any('BASEG-WIRE READY' in x for x in client_logs[start:]), 'client READY', 120)


def disconnect():
    global client
    if client is not None and client.poll() is None:
        stop_process(client, lambda: (client.stdin.write('stop\n'), client.stdin.flush()), timeout=15)
    client = None


def jar_checks():
    with zipfile.ZipFile(a.api_jar) as api:
        entries = [n for n in api.namelist() if not n.endswith('/')]
        manifest = api.read('META-INF/MANIFEST.MF').decode()
    stray = [n for n in entries if not n.startswith(API_PREFIXES)]
    internal = [n for n in entries if re.search(r'AlliumScheduler|BoundedPools|CoreCommandSpecs|CommandFamilyPolicy|/addon/|ItemService', n)]
    require(not stray and not internal and 'Allium-API-Version: 1.0.0' in manifest,
            f'{a.api_jar.name} holds only API types ({len(entries)} entries: the api package, command specs, TaskScheduler and TaskHandle), '
            f'no implementation classes')
    with zipfile.ZipFile(a.allium_jar) as core:
        names = core.namelist()
    addon_classes = [n for n in names if n.startswith(('addongate/', 'futureaddon/', 'shadedaddon/'))]
    require(not addon_classes and any(n.startswith('codes/castled/allium/api/') for n in names),
            f'Allium.jar contains no addon classes ({len(addon_classes)}) and provides the API at runtime')
    evidence['apiJar'] = {'entries': len(entries), 'sha256': hashlib.sha256(a.api_jar.read_bytes()).hexdigest()}


def with_allium():
    out = run / 'addons'
    write_lane('with-allium', [a.allium_jar, out / 'AddonGate.jar', out / 'FutureAddon.jar', out / 'ShadedAddon.jar'])
    start = boot('Allium with AddonGate, FutureAddon and ShadedAddon')
    ready = line_with('Allium API 1.0.0 ready', start)
    require(ready is not None and 'addons: AddonGate (^1.0)' in ready, f'Allium registered API 1.0.0 and accepted AddonGate: {ready}')
    future = line_with('Refusing addon FutureAddon', start)
    require(future is not None and 'needs Allium API ^2.0 (>=2.0.0 <3.0.0), but this Allium provides API 1.0.0' in future,
            f'FutureAddon was refused at startup with the version diagnostic: {(future or "")[-170:]}')
    shaded = line_with('Refusing addon ShadedAddon', start)
    require(shaded is not None and 'bundles its own copy of the Allium API' in shaded and 'compileOnly' in shaded,
            f'ShadedAddon was refused at startup as shaded: {(shaded or "")[-170:]}')
    future_base = line_with('[FutureAddon] Not enabling: FutureAddon needs Allium API ^2.0', start)
    shaded_base = line_with('[ShadedAddon] Not enabling: The Allium API service was not found', start)
    require(future_base is not None and line_with('Refused addon FutureAddon disabled itself', start) is not None,
            'FutureAddon (an AlliumAddon): the base class\'s connect was refused, so it logged Allium\'s reason and disabled itself')
    require(shaded_base is not None and line_with('Refused addon ShadedAddon disabled itself', start) is not None,
            'ShadedAddon (an AlliumAddon, shaded): its own copy of AlliumApi.get() found no service, so it disabled itself')
    time.sleep(2.5)
    reached = [x.strip() for x in logs[start:] if 'HOOK-REACHED' in x or 'FUTURE-TICK' in x
               or re.search(r'(FutureAddon|ShadedAddon) (defined|registered)', x)]
    require(not reached, 'neither refused addon reached its own onAddonEnable hook: no hook marker, no definition, '
                         'no command and no scheduled task in 2.5 s')
    require(line_with('ADDONG-READY api=1.0.0', start) is not None and line_with('AddonGate defined allium:gate_wand', start) is not None
            and line_with('AddonGate registered /addongate through Allium', start) is not None,
            'AddonGate connected, defined allium:gate_wand and registered /addongate')
    states = addon('plugins')
    require(states == {'Allium': True, 'AddonGate': True, 'FutureAddon': False, 'ShadedAddon': False},
            f'Allium and AddonGate keep running; the two refused addons are disabled: {states}')
    time.sleep(1.0)
    status = addon('status')
    require(status['serverRuns'] == 5 and status['serverThread'] is True and str(status['asyncThread']).startswith('Allium-Timer')
            and 'server' not in str(status['asyncThread']),
            f'the addon\'s scheduler ran its server task 5 times on the server thread, then it stopped itself; '
            f'its async task ran on {status["asyncThread"]}')
    console_reply = next((re.sub('§.', '', x).strip() for x in logs[-40:] if 'AddonGate runs=5' in re.sub('§.', '', x)), None)
    require(console_reply is not None, f'the reply went through Allium\'s text API: {console_reply}')
    connect()
    minted = addon(f'mint {BOT}')
    start = len(logs)
    console(f'give {BOT} allium:gate_wand')
    gave = wait(lambda: line_with('Gave 1x allium:gate_wand', start), "Allium's /give of the addon item", 15)
    found = addon(f'identify {BOT}')
    sticks = found['sticks']
    require(minted.get('status') == 'CREATED' and minted.get('given') == BOT, f'the addon created allium:gate_wand through the API: {minted}')
    require(gave is not None, f"Allium's own /give knows the addon's item: {gave[-70:]}")
    require(len(sticks) == 2 and all(s['appearance'] == 'allium:gate_wand' and s['behavior'] == 'addongate:gate_wand'
                                     and s['state'] == {'uses': '3'} and s['modelData'] == 7700001 and re.sub('§.', '', s['name'] or '') == 'Gate Wand'
                                     for s in sticks),
            f'both wands identify as allium:gate_wand with behavior addongate:gate_wand, state uses=3, model 7700001 '
            f'and the formatted name: {sticks}')
    require(found['plainStickBehavior'] is None and found['missing'] == 'UNKNOWN_ID',
            'a plain stick has no behavior, and an undefined allium id is UNKNOWN_ID (no air fallback)')
    require(found['futureItem'] == 'UNKNOWN_ID' and found['shadedItem'] == 'UNKNOWN_ID',
            'the items the refused addons\' hooks would have defined do not exist (allium:future_item and allium:shaded_item are UNKNOWN_ID)')
    start = len(client_logs)
    log_start = len(logs)
    client.stdin.write('cmd addongate status\n')
    client.stdin.flush()
    denied = wait(lambda: next((json.loads(x.split(' ', 1)[1]) for x in client_logs[start:]
                                if x.startswith('BASEG-CHAT ') and 'permission' in x), None), 'no-permission reply', 15)
    time.sleep(0.5)
    require(denied['plain'].endswith("You don't have permission to do that.") and line_with('ADDONG-STATUS', log_start) is None,
            f'a non-op player is refused by Allium\'s command spec before the handler runs: "{denied["plain"]}"')
    disconnect()
    stop_start = len(logs)
    stop_server()
    require(line_with("Released AddonGate's Allium connection (1 item(s), 1 command(s))", stop_start) is not None
            and line_with('ADDONG-DISABLED', stop_start) is not None,
            "on shutdown Allium released AddonGate's item, command and scheduled work")
    evidence['withAllium'] = {'ready': ready, 'future': future, 'shaded': shaded, 'plugins': states, 'status': status,
                              'minted': minted, 'identified': found, 'denied': denied['plain']}
    return start


def without_allium():
    write_lane('without-allium', [run / 'addons/AddonGate.jar'])
    start = boot('AddonGate without Allium')
    lines = logs[start:]
    message = next((x.strip() for x in lines if re.search(r'(missing|unknown).*depend', x, re.I) and 'Allium' in x), None)
    require(message is not None and line_with('ADDONG-READY', start) is None,
            f"without Allium, Bukkit refuses AddonGate with its depend message: {(message or '')[-150:]}")
    stop_server()
    evidence['withoutAllium'] = {'message': message}
    return start


summary = {}
quiet_from = None
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    jar_checks()
    build_addons()
    with_allium()
    quiet_from = len(logs)
    without_allium()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    try:
        disconnect()
    except Exception as e:
        failures.append('client cleanup ' + repr(e))
    try:
        stop_server()
    except Exception as e:
        failures.append('server cleanup ' + repr(e))
    expected = lambda x: 'Refusing addon' in x or 'Disabling refused addon' in x or 'Not enabling:' in x
    checked = logs[:quiet_from] if quiet_from is not None else logs
    errors = [x.strip() for x in checked if not expected(x) and ('Could not pass event' in x or 'Error occurred while enabling' in x
                                                               or ('Allium' in x and re.search(r'Exception|SEVERE', x)))]
    require(not errors, 'no listener, enable or Allium exceptions while Allium ran: ' + json.dumps(errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    summary = {'lane': a.lane, 'port': port, 'server': version, 'client': spec['client'],
               'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures, 'evidence': evidence,
               'hashes': {'Allium.jar': hashlib.sha256(a.allium_jar.read_bytes()).hexdigest(),
                          a.api_jar.name: hashlib.sha256(a.api_jar.read_bytes()).hexdigest()},
               'testSources': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in sorted(HERE.rglob('*')) if f.is_file() and f.suffix in ('.py', '.java', '.yml', '.cjs')}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    for d in run.glob('*-allium'):
        for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
            target = d / name
            if target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            elif target.exists():
                target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
