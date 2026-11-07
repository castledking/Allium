#!/usr/bin/env python3
"""S06b core pack gate: a real protocol client loads the generated pack on a disposable lane.

The lane's own server.properties offers the pack from a loopback HTTP server (Allium itself never
sends packs; its delivery owner stays none). The bot accepts, downloads, checks the SHA-1, runs
Allium's validator for its client format against Mojang's client jar, and reports the vanilla
statuses. A second connection declines. The fixture records the statuses the server receives and
what Allium stamps on real built-in disarmers. Exits nonzero on any failed check.
"""
import argparse, hashlib, http.server, json, re, shutil, socket, subprocess, sys, threading, time, uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

COEXIST = Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-1.21.8-coexist')
CLIENTS = Path('/tmp/allium-s06b-20261106/clients')
LANES = {
    'spigot-1.20.1': {'port': 25660, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': '1.20.1', 'format': '15',
                      'server': Path('/tmp/allium-server-cache/spigot-1.20.1.jar'), 'statuses': ['ACCEPTED', 'SUCCESSFULLY_LOADED'],
                      'overlays': []},
    'paper-1.21.8': {'port': 25661, 'java': '/usr/lib/jvm/java-21-openjdk/bin/java', 'client': '1.21.8', 'format': '64',
                     'server': Path('/tmp/allium-s05-20261106/lanes/artifacts/paper-1.21.8-60.jar'),
                     'statuses': ['ACCEPTED', 'DOWNLOADED', 'SUCCESSFULLY_LOADED'], 'overlays': ['allium_item_definitions']},
    'paper-26.3': {'port': 25662, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': 'raw26', 'format': '97.1',
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane'),
                   'statuses': ['ACCEPTED', 'DOWNLOADED', 'SUCCESSFULLY_LOADED'], 'overlays': ['allium_item_definitions']},
}
CLIENT_JARS = {'spigot-1.20.1': '1.20.1.jar', 'paper-1.21.8': '1.21.8.jar', 'paper-26.3': '26.3.jar'}
HTTP_PORT = 25669
PACK_ID = str(uuid.uuid5(uuid.NAMESPACE_URL, 'allium:s06b-core-pack-gate'))

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s06b-20261106/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
p.add_argument('--pack', type=Path, default=REPO / 'build/pack/allium-core-pack.zip')
p.add_argument('--manual', action='store_true', help='boot the lane for a real client; type console commands, "stop" to end')
p.add_argument('--scenario', choices=['load', 'recharge'], default='load',
               help='load: pack accept/decline gate; recharge: event-driven recharge visual refresh (raw26 lane)')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s06b-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s06b-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
lane = run / 'lane'
lane.mkdir(parents=True)
data = lane / 'plugins/PackGate'
port = spec['port']
pack_bytes = a.pack.read_bytes()
pack_sha1 = hashlib.sha1(pack_bytes).hexdigest()
manifest = json.loads((REPO / 'pack/manifest.json').read_text())
disarmer_states = {s['key']: s['cmd'] for s in next(t for t in manifest['tools'] if t['id'] == 'mob_disarmer')['states']}

server = client = httpd = None
accepted_count = [0]
server_thread = client_thread = None
logs, client_logs, driver, failures, http_log = [], [], [], [], []


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
    if text.startswith('pg '):
        tail = text[3:]
        wait(lambda: any(('S06B-DONE ' + tail) in x or ('S06B-FAIL ' + tail) in x for x in logs[start:]), text, 30)
        if any(('S06B-FAIL ' + tail) in x for x in logs[start:]):
            raise RuntimeError('fixture failed: ' + text)


def read_json(name):
    path = data / (name + '.json')
    wait(path.exists, path.name, 30)
    return json.loads(path.read_text())


def fixture(command, name):
    (data / (name + '.json')).unlink(missing_ok=True)
    cmd('pg ' + command)
    return read_json(name)


class PackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        http_log.append({'path': self.path, 'time': time.time()})
        if self.path != '/allium-core-pack.zip':
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/zip')
        self.send_header('Content-Length', str(len(pack_bytes)))
        self.end_headers()
        self.wfile.write(pack_bytes)

    def log_message(self, *args):
        pass


def start_http():
    global httpd
    httpd = http.server.ThreadingHTTPServer(('127.0.0.1', HTTP_PORT), PackHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/PackGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'PackGate.jar'), '-C', str(classes), '.'], check=True)


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
    shutil.copy2(run / 'fixture/PackGate.jar', plugins / 'PackGate.jar')
    (plugins / 'Allium').mkdir()
    shutil.copy2(REPO / 'src/main/resources/config.yml', plugins / 'Allium/config.yml')
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
resource-pack=http://127.0.0.1:{HTTP_PORT}/allium-core-pack.zip
resource-pack-sha1={pack_sha1}
resource-pack-id={PACK_ID}
require-resource-pack=false
''')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')
    bot = run / 'bot'
    bot.mkdir()
    (run / 'bot-config.json').write_text(json.dumps({
        'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'alliumJar': str(a.allium_jar), 'manifest': str(REPO / 'pack/manifest.json'),
        'label': a.lane, 'format': spec['format'], 'clientJar': str(CLIENTS / CLIENT_JARS[a.lane]), 'workDir': str(bot)}, indent=2))


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
                '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'server.jar', '--nogui']


def boot():
    global server, server_thread
    emit('BOOT')
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, bufsize=1)
    server_thread = threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True)
    server_thread.start()
    wait(lambda: any('Done (' in x for x in logs), 'server Done', 300)
    require(any('S06B-GATE READY' in x for x in logs), 'fixture enabled')
    require(any('Mob Disarmer enabled' in x for x in logs), 'Allium Mob Disarmer enabled')
    line = next((x for x in logs if 'Core resource pack ' in x), '')
    require(pack_sha1 in line and f'({len(pack_bytes)} bytes)' in line and 'delivery owner none' in line,
            f'Allium reports the bundled pack {pack_sha1} and delivery owner none: {line.strip()}')


def connect(mode):
    global client, client_thread
    start = len(client_logs)
    if spec['client'] == 'raw26':
        args = ['node', str(HERE / 'client-raw26.cjs'), str(lane), str(port), mode, str(run / 'bot-config.json')]
    else:
        args = ['node', str(HERE / 'client-mcp.cjs'), str(lane), str(port), spec['client'], mode, str(run / 'bot-config.json')]
    client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    client_thread = threading.Thread(target=pump, args=(client, run / f'client-{mode}.log', client_logs), daemon=True)
    client_thread.start()
    wait(lambda: any('S06B-WIRE READY' in x for x in client_logs[start:]), mode + ' client READY', 120)
    return start


def disconnect():
    global client
    stop_process(client, lambda: (client.stdin.write('stop\n'), client.stdin.flush()), timeout=10)
    if client_thread:
        client_thread.join(timeout=5)
    client = None
    time.sleep(1.0)


def pack_events(start):
    out = []
    for x in client_logs[start:]:
        if x.startswith('S06B-PACK '):
            out.append(json.loads(x[len('S06B-PACK '):]))
    return out


def slots(start):
    return [json.loads(x[len('S06B-SLOT '):]) for x in client_logs[start:] if x.startswith('S06B-SLOT ')]


def accept_case():
    http_before = len(http_log)
    start = connect('accept')
    events = wait(lambda: [e for e in pack_events(start) if e['event'] == 'result'], 'bot pack result', 60)
    result = events[0]
    offer = next(e for e in pack_events(start) if e['event'] == 'offer')
    require(offer['hash'].lower() == pack_sha1, f'server offered SHA-1 {offer["hash"]} == built {pack_sha1}')
    if a.lane != 'spigot-1.20.1':
        require(str(offer.get('uuid', '')).replace('-', '') == PACK_ID.replace('-', ''), f'server offered pack id {offer.get("uuid")}')
    require(result['bytes'] == len(pack_bytes) and result['sha1'] == pack_sha1 and result['hashMatches'],
            f'bot downloaded {result["bytes"]} bytes with SHA-1 {result["sha1"]} matching the offer')
    report = json.loads(Path(result['report']).read_text())
    lane_report = report['lanes'][0]
    require(report['clean'] and report['structure'] == [] and lane_report['errors'] == [],
            f'bot validator clean for format {spec["format"]} against {lane_report["vanilla"]}: {lane_report["errors"]}')
    require(lane_report['compatible'], f'pack.mcmeta compatible with format {spec["format"]} (declared {lane_report["declared"]})')
    require(lane_report['overlays'] == spec['overlays'], f'overlays applied {lane_report["overlays"]} == {spec["overlays"]}')
    for key, cmd_value in disarmer_states.items():
        rendered = lane_report['renders'].get(f'golden_hoe cmd={cmd_value}')
        require(rendered == f'allium:item/mob_disarmer/{key}', f'golden_hoe custom_model_data {cmd_value} renders {rendered}')
    require(lane_report['renders'].get('golden_hoe cmd=none') in ('minecraft:item/golden_hoe', 'allium:item/vanilla/golden_hoe'),
            'golden_hoe without model data stays vanilla')
    require(result['sent'] == (['ACCEPTED', 'SUCCESSFULLY_LOADED'] if a.lane == 'spigot-1.20.1' else ['ACCEPTED', 'DOWNLOADED', 'SUCCESSFULLY_LOADED']),
            f'bot sent {result["sent"]}')
    if a.lane == 'spigot-1.20.1':
        # 1.20.1 offers the pack in play, so Bukkit fires PlayerResourcePackStatusEvent for each status.
        status = wait(lambda: (lambda s: s if len(s['statuses']) >= len(spec['statuses']) else None)(fixture('status accept', 'status-accept')),
                      'server statuses', 30)
        names = [s['status'] for s in status['statuses']]
        accepted_count[0] = len(names)
        require(names == spec['statuses'], f'server received statuses {names}')
    else:
        # Paper offers server.properties packs during configuration and finishes that task only on a terminal
        # status for the configured id; it fires no Bukkit event there but keeps the last status on the connection.
        status = wait(lambda: (lambda s: s if s['botOnline'] and s['finalStatus'] else None)(fixture('status accept', 'status-accept')),
                      'server final status', 30)
        names = [status['finalStatus']]
        require(status['finalStatus'] == 'SUCCESSFULLY_LOADED' and not status['statuses'],
                f'server kept final status {status["finalStatus"]} after the configuration-phase pack task (no Bukkit events: {status["statuses"]})')
    gets = [h for h in http_log[http_before:] if h['path'] == '/allium-core-pack.zip']
    require(len(gets) == 1, f'exactly one pack download from the loopback host ({len(gets)})')
    require(status['botOnline'], 'bot is in the world after loading the pack')

    stamps = fixture('stamps', 'stamps')
    for key, cmd_value in disarmer_states.items():
        row = stamps[key]
        require(row['material'] == 'GOLDEN_HOE' and row['customModelData'] == cmd_value and row['behavior'] == 'allium:mob_disarmer',
                f'Allium stamps {key}: GOLDEN_HOE, custom_model_data {row["customModelData"]}, verified behavior')
        require(row['displayName'] == '§6Mob Disarmer', f'{key} keeps the vanilla-fallback name {row["displayName"]!r}')
        require(row['itemModel'] in ('none', 'api-absent'), f'{key} has no item_model component ({row["itemModel"]}), so decliners see a golden hoe')
    time.sleep(1.5)
    seen = json.dumps(slots(start))
    if spec['client'] != 'raw26':
        for cmd_value in disarmer_states.values():
            require(str(cmd_value) in seen, f'client received a slot carrying custom_model_data {cmd_value}')
    disconnect()
    return {'offer': offer, 'result': result, 'statuses': names, 'stamps': stamps, 'renders': lane_report['renders'],
            'notes': lane_report['notes'], 'clientSlots': slots(start)}


def decline_case():
    http_before = len(http_log)
    start = connect('decline')
    wait(lambda: [e for e in pack_events(start) if e['event'] == 'sent'], 'bot declined', 60)
    if a.lane == 'spigot-1.20.1':
        status = wait(lambda: (lambda s: s if len(s['statuses']) > accepted_count[0] else None)(fixture('status decline', 'status-decline')),
                      'decline status', 30)
        names = [s['status'] for s in status['statuses']][accepted_count[0]:]
    else:
        status = wait(lambda: (lambda s: s if s['botOnline'] and s['finalStatus'] else None)(fixture('status decline', 'status-decline')),
                      'decline final status', 30)
        names = [status['finalStatus']]
    require(names == ['DECLINED'], f'server recorded {names} from the declining client')
    require(status['botOnline'], 'declining client still plays')
    require(not [h for h in http_log[http_before:] if h['path'] == '/allium-core-pack.zip'], 'declining client downloaded nothing')
    time.sleep(1.5)
    if spec['client'] != 'raw26':
        seen = json.dumps(slots(start))
        require(str(disarmer_states['full']) in seen, 'declining client still holds the disarmers (golden hoes with their name)')
    disconnect()
    return {'statuses': names}


def bot(command):
    client.stdin.write(command + '\n')
    client.stdin.flush()


def slot(n):
    return fixture(f'slot {n}', f'slot-{n}')


def recharge_case():
    """A finished recharge refreshes only on an event: held-slot change, hand swap, join."""
    if spec['client'] != 'raw26':
        raise RuntimeError('the recharge scenario drives held-slot and swap packets with the raw 26.3 client')
    full, empty = disarmer_states['full'], disarmer_states['empty']
    out = {}
    connect('accept')
    wait(lambda: [e for e in pack_events(0) if e['event'] == 'result'], 'pack loaded', 60)
    bot('hold 0')
    time.sleep(0.5)

    cmd('pg recharge 1 3')
    before = slot(1)
    require(before.get('customModelData') == empty and before['state']['charges'] == '0', f'slot 1 starts empty ({before.get("customModelData")})')
    time.sleep(4.0)
    idle = slot(1)
    require(idle.get('customModelData') == empty and idle['state']['charges'] == '0',
            'after the recharge time passes with no event, nothing polls: slot 1 is still empty')
    bot('hold 1')
    held = wait(lambda: (lambda s: s if s['heldSlot'] == 1 else None)(slot(1)), 'held slot 1', 10)
    require(held.get('customModelData') == full and held['state']['charges'] == '3' and held['behavior'] == 'allium:mob_disarmer'
            and held['instanceUuid'] == before['instanceUuid'],
            f'holding it settles the recharge on the same stack: model {held.get("customModelData")}, charges {held["state"]["charges"]}')
    out['held'] = {'before': before, 'idle': idle, 'after': held}

    cmd('pg recharge 2 3')
    bot('hold 2')
    early = wait(lambda: (lambda s: s if s['heldSlot'] == 2 else None)(slot(2)), 'held slot 2', 10)
    require(early.get('customModelData') == empty, 'holding it before the recharge ends changes nothing')
    time.sleep(4.0)
    bot('swap')
    swapped = wait(lambda: (lambda s: s if not s.get('empty') else None)(slot(40)), 'off hand filled', 10)
    require(swapped.get('customModelData') == full and swapped['state']['charges'] == '3'
            and swapped['instanceUuid'] == early['instanceUuid'], f'swapping it to the off hand refreshes it ({swapped.get("customModelData")})')
    out['swap'] = {'early': early, 'offHand': swapped}

    cmd('pg recharge 3 3')
    time.sleep(4.0)
    offline_before = slot(3)
    require(offline_before.get('customModelData') == empty, 'slot 3 is still empty before the reconnect')
    disconnect()
    connect('accept')
    joined = wait(lambda: (lambda s: s if s.get('customModelData') == full else None)(slot(3)), 'slot 3 refreshed on join', 20)
    require(joined['state']['charges'] == '3' and joined['instanceUuid'] == offline_before['instanceUuid'], 'rejoining refreshes it')
    out['join'] = {'before': offline_before, 'after': joined}
    disconnect()
    return out


def manual():
    emit(f'MANUAL lane {a.lane} is up at 127.0.0.1:{port} (offline mode). The server offers pack {pack_sha1} from '
         f'http://127.0.0.1:{HTTP_PORT}/. Console commands are forwarded; useful ones: "pg give <name>" (five real '
         'disarmers, one per state), "pg arena <name>" (four armed zombies), "op <name>". Type "stop" to end.')
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line == 'stop':
            break
        server.stdin.write(line + '\n')
        server.stdin.flush()


summary = {}
try:
    for p_ in [port, HTTP_PORT]:
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', p_))
    build_fixture()
    write_lane()
    start_http()
    boot()
    if a.manual:
        manual()
    elif a.scenario == 'recharge':
        summary['recharge'] = recharge_case()
    else:
        accepted = accept_case()
        declined = decline_case()
        summary.update({'accept': accepted, 'decline': declined})
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
            stop_process(server, lambda: (server.stdin.write('stop\n'), server.stdin.flush()))
        except Exception as e:
            failures.append('server cleanup ' + repr(e))
    if httpd is not None:
        httpd.shutdown()
    errors = [x.rstrip() for x in logs if re.search(r'Exception|SEVERE|ERROR\]', x) and 'S06B' not in x]
    allium_errors = [x for x in logs if 'Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
    require(not allium_errors, 'no listener or Allium exceptions in server log: ' + json.dumps(allium_errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane / 'plugins').glob('*.jar')} if (lane / 'plugins').exists() else {}
    if (lane / 'server.jar').exists():
        hashes['server.jar'] = hashlib.sha256((lane / 'server.jar').read_bytes()).hexdigest()
    summary.update({'lane': a.lane, 'port': port, 'server': version, 'client': spec['client'], 'clientFormat': spec['format'],
                    'clientJar': CLIENT_JARS[a.lane], 'pack': {'sha1': pack_sha1, 'bytes': len(pack_bytes)},
                    'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures,
                    'serverLogErrors': errors[:40], 'hashes': hashes, 'http': http_log})
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    # Keep logs, fixture output and bot reports; drop the copied server binaries, libraries and world.
    for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
        target = lane / name
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.exists():
            target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary.get(k) for k in ['lane', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
