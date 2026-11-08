#!/usr/bin/env python3
"""Baseline gate (M1 evidence): the static text formatter's boot and localized-message check, and the
S04 item contract, on Spigot 1.20.1, Paper 1.20, Paper 1.20.6, Spigot 26.3 and Paper 26.3.

Text: Allium runs with `settings.locale: es_ES`. A real protocol client receives the shipped Spanish
`/feed` message, and one test entry in the lane's own es_ES.yml (a gradient, a hex colour and a message
sound at volume 0.5). The client decodes every chat packet into visible text and one colour per
character, so the Adventure (Paper) and legacy (Spigot) paths can be compared exactly.

Item contract: the BaseGate fixture runs Allium's contract on the server's real item and PDC
implementation, stores signed items in a chest, and the harness verifies them again after a restart.
Disposable lanes only; exits nonzero on any failed check.
"""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

CACHE = Path('/tmp/allium-server-cache')
LANES = {
    'spigot-1.20.1': {'port': 25660, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'), 'jar': CACHE / 'spigot-1.20.1.jar'},
    'paper-1.20': {'port': 25661, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'), 'jar': CACHE / 'paper-1.20-17.jar'},
    'paper-1.20.6': {'port': 25662, 'java': '/usr/lib/jvm/java-21-openjdk/bin/java', 'client': ('mcp', '1.20.6'), 'jar': CACHE / 'paper-1.20.6-151.jar'},
    'spigot-26.3': {'port': 25663, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None), 'jar': CACHE / 'spigot-26.3.jar'},
    'paper-26.3': {'port': 25664, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane')},
}
BOT = 'BaseBot'
SOUND = 'minecraft:entity.experience_orb.pickup'
HEAL_TEXT = '{prefix} <gradient:#ff0000:#0000ff>Curado por completo</gradient> &#00ff88ok'
LEGACY = {'0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa', '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00',
          '7': '#aaaaaa', '8': '#555555', '9': '#5555ff', 'a': '#55ff55', 'b': '#55ffff', 'c': '#ff5555', 'd': '#ff55ff',
          'e': '#ffff55', 'f': '#ffffff'}

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-base-20261107/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-base-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-base-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
lane = run / 'lane'
lane.mkdir(parents=True)
port = spec['port']
logs, client_logs, driver, failures = [], [], [], []
evidence = {}
server = None
client = None


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


def bg(text, timeout=60):
    start = len(logs)
    console('bg ' + text)
    line = wait(lambda: next((x for x in logs[start:] if f'BASEG-DONE {text} => ' in x or f'BASEG-FAIL {text} => ' in x), None),
                'bg ' + text, timeout)
    if 'BASEG-FAIL' in line:
        raise RuntimeError('fixture failed: ' + line.strip())
    return json.loads(line.split(' => ', 1)[1])


def legacy_colors(text):
    """Expected colour per visible character of an &-coded string (the shipped lang entries use only &x)."""
    out, color, i = [], None, 0
    while i < len(text):
        if text[i] == '&' and i + 1 < len(text) and text[i + 1].lower() in LEGACY:
            color = LEGACY[text[i + 1].lower()]
            i += 2
            continue
        out.append((text[i], color))
        i += 1
    return out


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/BaseGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'BaseGate.jar'), '-C', str(classes), '.'], check=True)


def write_lane():
    if 'template' in spec:
        for name in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / name, lane / name)
    else:
        shutil.copy2(spec['jar'], lane / 'server.jar')
    plugins = lane / 'plugins'
    (plugins / 'Allium/lang').mkdir(parents=True)
    shutil.copy2(a.allium_jar, plugins / 'Allium.jar')
    shutil.copy2(run / 'fixture/BaseGate.jar', plugins / 'BaseGate.jar')
    config = (REPO / 'src/main/resources/config.yml').read_text()
    if '  locale: en_US\n' not in config:
        raise RuntimeError('locale injection failed')
    (plugins / 'Allium/config.yml').write_text(config.replace('  locale: en_US\n', '  locale: es_ES\n'))
    # The shipped es_ES file, with one test entry: a gradient, a hex colour and a sound at volume 0.5.
    es = (REPO / 'src/main/resources/lang/es_ES.yml').read_text()
    old = '  heal: "{prefix} &aHas sido curado."\n'
    if old not in es:
        raise RuntimeError('heal entry not found in es_ES.yml')
    es = es.replace(old, f'  heal:\n    text: "{HEAL_TEXT}"\n    sound: {SOUND}\n    volume: 0.5\n')
    (plugins / 'Allium/lang/es_ES.yml').write_text(es)
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
level-seed=5011107
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
    return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-jar', 'server.jar', '--nogui']


def boot(label):
    global server
    emit('BOOT ' + label)
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1)
    threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True).start()
    wait(lambda: any('Done (' in x for x in logs[start:]), 'server Done', 600)
    lines = logs[start:]
    require(any('Allium v' in x and 'enabled' in x for x in lines) and any('BASEG-GATE READY' in x for x in lines),
            f'{label}: Allium and the fixture enabled')
    require(any('Loaded locale: es_ES' in x for x in lines), f'{label}: the es_ES locale loaded')
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


def chats_after(start, fragment, timeout=10):
    def find():
        for x in client_logs[start:]:
            if x.startswith('BASEG-CHAT ') and fragment in x:
                return json.loads(x.split(' ', 1)[1])
        return None
    return wait(find, 'chat containing ' + fragment, timeout)


def text_cases():
    # Shipped translation through the shipped &-codes: prefix "&7[&dAllium&7]" and "&aHas sido alimentado."
    start = len(client_logs)
    console(f'feed {BOT}')
    chat = chats_after(start, 'Has sido alimentado')
    expected = legacy_colors('&7[&dAllium&7] &aHas sido alimentado.')
    plain = ''.join(c for c, _ in expected)
    pairs = [(c, col) for c, col in zip(chat['plain'], chat['colors'])]
    visible = lambda items: [(c, col) for c, col in items if not c.isspace()]
    require(chat['plain'] == plain and visible(pairs) == visible(expected),
            f'the shipped Spanish /feed message arrives as "{chat["plain"]}" with the &7/&d/&a colours of every visible character')
    evidence['feed'] = chat

    # The test entry: gradient, hex colour and a message sound at volume 0.5 (pitch from sounds.defaults.pitch).
    start = len(client_logs)
    console(f'heal {BOT}')
    chat = chats_after(start, 'Curado por completo')
    sound = wait(lambda: next((json.loads(x.split(' ', 1)[1]) for x in client_logs[start:] if x.startswith('BASEG-SOUND ')), None),
                 'message sound', 10)
    text = chat['plain']
    begin = text.index('Curado')
    gradient = chat['colors'][begin:begin + len('Curado por completo')]
    hexes = [g for g, ch in zip(gradient, 'Curado por completo') if not ch.isspace()]
    rgb = [tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) for h in hexes if h and h.startswith('#')]
    ok_color = chat['colors'][text.index('ok')]
    require(text == '[Allium] Curado por completo ok', f'the gradient test entry arrives as "{text}"')
    require(len(rgb) == len(hexes) and hexes[0] == '#ff0000' and hexes[-1] == '#0000ff' and len(set(hexes)) == len(hexes)
            and all(x[0] >= y[0] and x[2] <= y[2] for x, y in zip(rgb, rgb[1:])),
            f'<gradient:#ff0000:#0000ff> gives each of the {len(hexes)} letters its own colour, from {hexes[0]} to {hexes[-1]}')
    require(ok_color == '#00ff88', f'&#00ff88 gives "ok" the hex colour {ok_color}')
    require(abs(sound['volume'] - 0.5) < 1e-6 and abs(sound['pitch'] - 1.0) < 1e-6 and sound['name'] == SOUND,
            f'Allium played the message sound itself: {sound}')
    evidence['heal'] = {'chat': chat, 'sound': sound}


def contract_cases():
    checks = bg('contract')
    for c in checks:
        require(c['ok'], 'item contract: ' + c['name'] + ('' if c['ok'] else ' ' + json.dumps(c['detail'])[:300]))
    evidence['contract'] = checks


FIELDS = ['material', 'appearance', 'behavior', 'state', 'instanceUuid', 'signature', 'schemaVersion']


def persistence_cases():
    stored = bg('store')
    disconnect()
    stop_server()
    boot('restart')
    found = bg('verify')
    labels = ['a full disarmer', 'a spent disarmer (state re-signed)', 'privileged handcuffs',
              'handcuffs with a swapped appearance', 'a disarmer whose state was edited without re-signing']
    for label, want, got in zip(labels, stored, found):
        same = all(want.get(k) == got.get(k) for k in FIELDS)
        verified = want['behavior'] is not None
        require(same and (verified == (got.get('behavior') is not None)),
                f'after a restart, {label} reads back identically '
                + ('and still verifies' if verified else 'and is still rejected')
                + ('' if same else f': {json.dumps({k: (want.get(k), got.get(k)) for k in FIELDS if want.get(k) != got.get(k)})[:300]}'))
    evidence['persistence'] = {'stored': stored, 'found': found}


summary = {}
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    with zipfile.ZipFile(a.allium_jar) as jar:
        names = jar.namelist()
    shaded = [n for n in names if n.startswith(('me/croabeast/', 'net/kyori/'))]
    require(not shaded, f'Allium.jar contains no Takion (me.croabeast) or Adventure (net.kyori) classes ({len(shaded)})')
    build_fixture()
    write_lane()
    boot('initial')
    connect()
    text_cases()
    contract_cases()
    persistence_cases()
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
    allium_errors = [x.strip() for x in logs if 'Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
    require(not allium_errors, 'no listener or Allium exceptions in the server log: ' + json.dumps(allium_errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    java = next((m.group(1) for x in logs for m in [re.search(r'Java (\d+[\d.]*)', x)] if m), None)
    hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane / 'plugins').glob('*.jar')} if (lane / 'plugins').exists() else {}
    server_jar = spec.get('jar')
    summary = {'lane': a.lane, 'port': port, 'server': version, 'java': java, 'client': spec['client'],
               'serverJarSha256': hashlib.sha256(server_jar.read_bytes()).hexdigest() if server_jar else 'template ' + str(spec.get('template')),
               'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures, 'hashes': hashes,
               'evidence': evidence,
               'testSources': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in [HERE / 'run.py', HERE / 'plugin/gate/BaseGate.java', HERE / 'client-mcp.cjs',
                                         HERE / 'client-raw26.cjs', HERE / 'text-components.cjs']}}
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
