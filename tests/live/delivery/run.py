#!/usr/bin/env python3
"""S06c gate: core pack delivery on Spigot 1.20.1, Paper 1.21.8 and Paper 26.3.

standalone (every lane):
  optional  a bot accepts (download + SHA-1 check) and loads; a bot declines and stays; a hosted file
            swapped after the startup check fails the bot's hash check, and the bot stays
  required  accept still loads; decline and a hash mismatch are kicked with kick-message
  startup   a stale hosted file fails Allium's startup check, and Allium sends nothing
  1.20.3+   server.properties sends a second pack; Allium adds its pack beside it by its own UUID,
            and nothing removes the other
  1.20.1    the one-owner warning when server.properties also sets a pack
rpm-export (--scenario rpm, one lane): a dry run, then an export, into a copy of SF's mixer directory.
The harness hosts the files on a loopback HTTP port (Allium has no web server). Disposable lanes
only; exits nonzero on any failed check.
"""
import argparse, hashlib, http.server, io, json, os, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'port': 25686, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'),
                      'jar': Path('/tmp/allium-server-cache/spigot-1.20.1.jar'), 'multi': False},
    'paper-1.21.8': {'port': 25687, 'java': '/usr/lib/jvm/java-21-openjdk/bin/java', 'client': ('mcp', '1.21.8'),
                     'jar': Path('/tmp/allium-s05-20261106/lanes/artifacts/paper-1.21.8-60.jar'), 'multi': True,
                     'copy': Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-1.21.8-coexist')},
    'paper-26.3': {'port': 25688, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane'), 'multi': True},
}
HTTP_PORT = 25689
BOT = 'PackBot'
SECOND_PACK_ID = '5e3c0d1e-0000-4000-8000-000000000002'
SF_MIXER = Path('/var/lib/pterodactyl/volumes/db85ca1b-2fae-4f63-848f-2870a86f6ed7/plugins/ResourcePackManager/mixer')

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--scenario', choices=['standalone', 'rpm'], default='standalone')
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s06c-20261108/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s06c-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s06c-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
port = spec['port']
lane = run / 'lane'
driver, failures, evidence = [], [], {}
logs, client_logs, http_log = [], [], []
server = client = httpd = None
with zipfile.ZipFile(a.allium_jar) as jar:
    PACK = jar.read('pack/allium-core-pack.zip')
PACK_SHA1 = hashlib.sha1(PACK).hexdigest()


def second_pack():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        info = zipfile.ZipInfo('pack.mcmeta', (1980, 1, 1, 0, 0, 0))
        z.writestr(info, json.dumps({'pack': {'pack_format': 15, 'description': 'S06c second pack'}}))
    return buf.getvalue()


SECOND = second_pack()
SECOND_SHA1 = hashlib.sha1(SECOND).hexdigest()
hosting = {'mode': 'exported'}  # exported: serve the lane's exported file; tampered: serve different bytes


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


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        http_log.append({'path': self.path, 'agent': self.headers.get('User-Agent'), 'time': time.time()})
        if self.path == '/allium-core-pack.zip':
            exported = lane / 'plugins/Allium/pack/allium-core-pack.zip'
            body = exported.read_bytes() if exported.exists() else b''
            if hosting['mode'] == 'tampered':
                body = body + b'not the pack Allium exported'
        elif self.path == '/second-pack.zip':
            body = SECOND
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/zip')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start_http():
    global httpd
    httpd = http.server.ThreadingHTTPServer(('127.0.0.1', HTTP_PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/DeliveryGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'DeliveryGate.jar'), '-C', str(classes), '.'], check=True)


def write_lane():
    lane.mkdir()
    if 'template' in spec:
        for d in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / d, lane / d)
    else:
        if 'copy' in spec:
            for d in ['cache', 'libraries', 'versions']:
                shutil.copytree(spec['copy'] / d, lane / d)
        shutil.copy2(spec['jar'], lane / 'server.jar')
    (lane / 'plugins/Allium').mkdir(parents=True)
    shutil.copy2(a.allium_jar, lane / 'plugins/Allium.jar')
    shutil.copy2(run / 'fixture/DeliveryGate.jar', lane / 'plugins/DeliveryGate.jar')
    (lane / 'eula.txt').write_text('eula=true\n')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')


def configure(owner='standalone', required=False, server_pack=False, dry_run=False, mixer=None, pack_file=None):
    text = (REPO / 'src/main/resources/config.yml').read_text()
    replacements = [('  owner: none\n', f'  owner: {owner}\n'),
                    ('    url: ""\n', f'    url: "http://127.0.0.1:{HTTP_PORT}/allium-core-pack.zip"\n'),
                    ('  required: false\n', f'  required: {str(required).lower()}\n'),
                    ('  debug: false\n', '  debug: true\n'),
                    ('    dry-run: false\n', f'    dry-run: {str(dry_run).lower()}\n')]
    if mixer is not None:
        replacements.append(('    mixer-directory: ResourcePackManager/mixer\n', f'    mixer-directory: "{mixer}"\n'))
    if pack_file is not None:
        replacements.append(('  pack-file: ""\n', f'  pack-file: "{pack_file}"\n'))
    for old, new in replacements:
        if old not in text:
            raise RuntimeError('config injection failed: ' + old.strip())
        text = text.replace(old, new, 1)
    (lane / 'plugins/Allium/config.yml').write_text(text)
    pack_lines = (f'resource-pack=http://127.0.0.1:{HTTP_PORT}/second-pack.zip\nresource-pack-sha1={SECOND_SHA1}\n'
                  f'resource-pack-id={SECOND_PACK_ID}\nrequire-resource-pack=false\n') if server_pack else ''
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
level-seed=6031108
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
{pack_lines}''')


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
    threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True).start()
    wait(lambda: any('Done (' in x for x in logs[start:]), label + ' Done', 600)
    return start


def stop_server():
    global server
    if server is not None and server.poll() is None:
        stop_process(server, lambda: (server.stdin.write('stop\n'), server.stdin.flush()), timeout=90)
    server = None


def console(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def line_with(fragment, start=0):
    return next((x.strip() for x in logs[start:] if fragment in x), None)


def state():
    start = len(logs)
    console('dg')
    line = wait(lambda: line_with('DELIVG-STATE ', start), 'fixture state', 15)
    return json.loads(line.split('DELIVG-STATE ', 1)[1])


def join(mode, label, settle=3.0):
    """Connects a bot, waits for it to be in the game, lets pack responses settle, and returns what it saw."""
    global client
    kind, version = spec['client']
    script = 'client-raw26.cjs' if kind == 'raw26' else 'client-mcp.cjs'
    args = ['node', str(HERE / script), str(lane), str(port)] + ([version] if version else []) + [mode, BOT]
    start = len(client_logs)
    log_start = len(logs)
    client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    threading.Thread(target=pump, args=(client, run / 'client.log', client_logs), daemon=True).start()
    wait(lambda: any('DELIVG-WIRE READY' in x or 'DELIVG-KICK' in x or 'DELIVG-WIRE END' in x for x in client_logs[start:]),
         label + ' client in game', 120)
    time.sleep(settle)
    lines = client_logs[start:]
    packs = [json.loads(x.split(' ', 1)[1]) for x in lines if x.startswith('DELIVG-PACK ')]
    kicks = [json.loads(x.split(' ', 1)[1]) for x in lines if x.startswith('DELIVG-KICK ')]
    return {'offers': [p for p in packs if p['event'] == 'offer'], 'sent': [p for p in packs if p['event'] == 'sent'],
            'removed': [p for p in packs if p['event'] == 'removed'], 'kicks': kicks,
            'connected': client.poll() is None, 'log_start': log_start}


def leave():
    global client
    if client is not None and client.poll() is None:
        stop_process(client, lambda: (client.stdin.write('stop\n'), client.stdin.flush()), timeout=15)
    elif client is not None:
        client.wait(timeout=10)
    client = None
    time.sleep(0.5)


def allium_offer(seen):
    url = f'http://127.0.0.1:{HTTP_PORT}/allium-core-pack.zip'
    return next((o for o in seen['offers'] if o['url'] == url), None)


def optional_boot():
    multi = spec['multi']
    configure(required=False, server_pack=multi)
    start = boot('standalone, optional' + (', with a server.properties pack' if multi else ''))
    exported = lane / 'plugins/Allium/pack/allium-core-pack.zip'
    require(exported.exists() and hashlib.sha1(exported.read_bytes()).hexdigest() == PACK_SHA1
            and (lane / 'plugins/Allium/pack/allium-core-pack.zip.sha1').read_text().strip() == PACK_SHA1,
            f'Allium exported the bundled pack to plugins/Allium/pack/ ({len(PACK)} bytes, SHA-1 {PACK_SHA1[:12]}…)')
    verified = wait(lambda: line_with("Sending Allium's resource pack from", start), 'hosted check', 30)
    check = [h for h in http_log if h['agent'] == 'Allium resource pack check']
    require('the hosted file matches' in verified and check,
            f'at startup Allium downloaded the hosted URL once and verified its SHA-1: {verified[-150:]}')
    s = state()
    require(s['hosted'] == 'VERIFIED' and s['multiplePacks'] == multi,
            f'delivery is VERIFIED, {"adding packs by UUID (1.20.3+)" if multi else "as the one server pack (before 1.20.3)"}')

    seen = join('accept', 'accept')
    offer = allium_offer(seen)
    loaded = line_with(f"{BOT} loaded Allium's resource pack.", seen['log_start'])
    require(offer is not None and offer['hash'] == PACK_SHA1 and offer['forced'] is False
            and 'custom tool models' in (offer.get('prompt') or ''),
            f'the bot was offered Allium\'s pack with its SHA-1, not forced, with the prompt: {offer}')
    require(any(x['uuid'] == (offer or {}).get('uuid') and x['sent'][-1] == 'SUCCESSFULLY_LOADED' and x.get('matches') for x in seen['sent'])
            and loaded is not None and seen['connected'],
            'accept: the bot downloaded it, the SHA-1 matched, it loaded, and Allium logged it; the bot stays')
    if multi:
        second = next((o for o in seen['offers'] if o['url'].endswith('/second-pack.zip')), None)
        require(offer is not None and offer['uuid'] == s['packId'] and second is not None and second['uuid'] == SECOND_PACK_ID
                and not seen['removed'],
                f'coexistence: the server.properties pack ({second and second["phase"]}, {SECOND_PACK_ID[:8]}…) and Allium\'s '
                f'({offer and offer["phase"]}, {s["packId"][:8]}…) both arrived; nothing removed either')
        status = state()['statuses'].get(BOT)
        require(status == 'SUCCESSFULLY_LOADED', f'Allium tracked only its own pack\'s status ({status})')
    evidence['accept'] = seen
    leave()

    http_before = len(http_log)
    seen = join('decline', 'decline')
    declined = line_with(f"{BOT} declined Allium's resource pack; they keep the named vanilla items.", seen['log_start'])
    downloads = [h for h in http_log[http_before:] if h['path'] == '/allium-core-pack.zip']
    require(allium_offer(seen) is not None and declined is not None and seen['connected'] and not seen['kicks'] and not downloads,
            'decline (optional): the bot downloaded nothing, Allium logged it, and the bot stays with the vanilla fallback items')
    evidence['decline'] = seen
    leave()

    hosting['mode'] = 'tampered'
    seen = join('accept', 'hash mismatch')
    hosting['mode'] = 'exported'
    failed = line_with(f"{BOT} could not load (FAILED_DOWNLOAD) Allium's resource pack; they keep the named vanilla items.", seen['log_start'])
    bad = next((x for x in seen['sent'] if x['uuid'] == (allium_offer(seen) or {}).get('uuid')), None)
    require(bad is not None and bad.get('matches') is False and bad['sent'][-1] == 'FAILED_DOWNLOAD' and failed is not None
            and seen['connected'],
            'hash mismatch (optional): the hosted file was swapped after the startup check; the bot found the wrong SHA-1, '
            'reported FAILED_DOWNLOAD, and stays')
    evidence['mismatch'] = seen
    leave()
    stop_server()


def required_boot():
    configure(required=True, server_pack=spec['multi'])
    boot('standalone, required')
    seen = join('accept', 'required accept')
    offer = allium_offer(seen)
    require(offer is not None and offer['forced'] is True and seen['connected']
            and line_with(f"{BOT} loaded Allium's resource pack.", seen['log_start']) is not None,
            'required: the offer is forced, and a bot that loads it stays')
    leave()
    seen = join('decline', 'required decline')
    kick = seen['kicks'][-1]['reason'] if seen['kicks'] else ''
    require('requires its resource pack' in kick and not seen['connected']
            and line_with(f"{BOT} declined Allium's required resource pack; kicking them.", seen['log_start']) is not None,
            f'required: a bot that declines is kicked with kick-message: "{kick}"')
    evidence['requiredDecline'] = seen
    leave()
    hosting['mode'] = 'tampered'
    seen = join('accept', 'required mismatch')
    hosting['mode'] = 'exported'
    kick = seen['kicks'][-1]['reason'] if seen['kicks'] else ''
    require('requires its resource pack' in kick and not seen['connected']
            and line_with(f"{BOT} could not load (FAILED_DOWNLOAD) Allium's required resource pack; kicking them.", seen['log_start']) is not None,
            f'required: a hash mismatch is kicked the same way: "{kick}"')
    evidence['requiredMismatch'] = seen
    leave()
    stop_server()


def reload_cmd(expect, timeout=30):
    """Runs /allium reload from the console and waits for the given reply."""
    start = len(logs)
    console('allium reload')
    return wait(lambda: line_with(expect, start), 'reload: ' + expect, timeout), start


def startup_mismatch_boot():
    hosting['mode'] = 'tampered'
    configure(required=False, server_pack=spec['multi'])
    start = boot('standalone, stale hosted file')
    refused = wait(lambda: line_with("Not sending Allium's resource pack:", start), 'startup mismatch', 30)
    require(f"but Allium's bundled pack has SHA-1 {PACK_SHA1}" in refused,
            f'a stale hosted file fails the startup check, with both SHA-1s: {refused[-170:]}')
    seen = join('accept', 'join after the failed check')
    require(allium_offer(seen) is None and seen['connected'] and state()['hosted'] == 'MISMATCH',
            'Allium then sends nothing (the player keeps the vanilla items)'
            + ('; the server.properties pack still arrives' if spec['multi'] and any(o['url'].endswith('/second-pack.zip') for o in seen['offers']) else ''))
    evidence['startupMismatch'] = {'log': refused, 'seen': seen}
    leave()

    # /allium reload re-checks the hosted file: fixed host -> sending; stale again -> not sending.
    hosting['mode'] = 'exported'
    matched, _ = reload_cmd('The hosted resource pack matches; sending it.')
    require(state()['hosted'] == 'VERIFIED', f'after the host is fixed, /allium reload re-checks the file and Allium sends again: {matched[-60:]}')
    seen = join('accept', 'join after the reload fixed it')
    require(allium_offer(seen) is not None and line_with(f"{BOT} loaded Allium's resource pack.", seen['log_start']) is not None,
            'a player joining after that reload gets and loads the pack')
    leave()
    hosting['mode'] = 'tampered'
    stale, _ = reload_cmd('Not sending the resource pack: the file at')
    seen = join('accept', 'join after the reload found it stale')
    hosting['mode'] = 'exported'
    require(state()['hosted'] == 'MISMATCH' and allium_offer(seen) is None,
            f'a reload that finds a stale hosted file stops sending: {stale[-90:]}')
    evidence['reload'] = {'matched': matched, 'stale': stale}
    leave()
    stop_server()


def tampered_pack(source, entry, replacement_entry):
    """The bundled pack with one texture replaced by another (a stand-in for a local art build)."""
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
        swap = zin.read(replacement_entry)
        for info in zin.infolist():
            zout.writestr(info, swap if info.filename == entry else zin.read(info.filename))
    return out.getvalue()


def override_boot():
    texture = 'assets/allium/textures/item/mob_disarmer/'
    override = tampered_pack(PACK, texture + 'full.png', texture + 'empty.png')
    override_sha1 = hashlib.sha1(override).hexdigest()
    target = lane / 'plugins/Allium/pack/override-test.zip'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(override)
    configure(required=False, server_pack=False, pack_file='pack/override-test.zip')
    start = boot('standalone with resource-pack.pack-file')
    chosen = line_with('instead of the bundled core pack', start)
    exported = (lane / 'plugins/Allium/pack/allium-core-pack.zip').read_bytes()
    require(chosen is not None and override_sha1 in chosen and exported == override,
            f'pack-file: Allium exported the local build (SHA-1 {override_sha1[:12]}…) instead of the bundled pack')
    verified = wait(lambda: line_with("Sending Allium's resource pack from", start), 'override hosted check', 30)
    seen = join('accept', 'accept the local build')
    offer = allium_offer(seen)
    require(f'SHA-1 {override_sha1}' in verified and offer is not None and offer['hash'] == override_sha1
            and line_with(f"{BOT} loaded Allium's resource pack.", seen['log_start']) is not None,
            'the hosted check and the offer use the local build\'s SHA-1, and the bot loads it')
    leave()
    # A pack-file without the core structure falls back to the bundled pack (here applied by reload).
    bad = lane / 'plugins/Allium/pack/not-a-core-pack.zip'
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('pack.mcmeta', '{}')
    bad.write_bytes(buf.getvalue())
    configure(required=False, server_pack=False, pack_file='pack/not-a-core-pack.zip')
    _, start = reload_cmd('The hosted resource pack matches; sending it.')
    warned = line_with('lacks the core pack', start)
    seen = join('accept', 'accept after falling back')
    offer = allium_offer(seen)
    require(warned is not None and (lane / 'plugins/Allium/pack/allium-core-pack.zip').read_bytes() == PACK
            and offer is not None and offer['hash'] == PACK_SHA1,
            f'a pack-file without the core models falls back to the bundled pack, with a warning: {(warned or "")[-120:]}')
    evidence['override'] = {'sha1': override_sha1, 'chosen': chosen, 'fallbackWarning': warned}
    leave()
    stop_server()


def one_owner_boot():
    configure(required=False, server_pack=True)
    start = boot('standalone with a server.properties pack, before 1.20.3')
    warning = line_with('needs exactly one delivery owner', start)
    require(warning is not None and 'server.properties also sets resource-pack' in warning,
            f'before 1.20.3 Allium warns that one delivery owner is mandatory: {(warning or "")[-140:]}')
    stop_server()


def snapshot(directory):
    return {f.name: (f.stat().st_size, hashlib.sha256(f.read_bytes()).hexdigest()) for f in sorted(directory.iterdir()) if f.is_file()}


def live_stat(directory):
    return {f.name: (f.stat().st_size, f.stat().st_mtime_ns) for f in sorted(directory.iterdir())}


def rpm_scenario():
    copy = run / 'sf-mixer-copy'
    copy.mkdir()
    live_before = live_stat(SF_MIXER)
    skipped = []
    for f in sorted(SF_MIXER.iterdir()):
        try:
            shutil.copyfile(f, copy / f.name)
        except PermissionError:
            skipped.append(f.name)
    emit(f'COPY {len(live_before) - len(skipped)} of {len(live_before)} files from SF\'s mixer (unreadable, not copied: {skipped})')
    before = snapshot(copy)
    configure(owner='rpm-export', dry_run=True, mixer=copy)
    start = boot('rpm-export dry run into a copy of SF\'s mixer')
    lines = logs[start:]
    checked = line_with('[rpm-export dry run] Checked', start)
    ahead = [x.strip() for x in lines if 'Carrier collision ahead' in x]
    current = [x.strip() for x in lines if 'Carrier collision:' in x]
    would = line_with('[rpm-export dry run] Would write', start)
    require(checked is not None and f'Checked {len(before)} pack(s)' in checked,
            f'the dry run read all {len(before)} copied packs: {checked[-90:] if checked else None}')
    # S10 moved the planned Phantom Obliterator off blaze_rod (which SF's Nexo pack overrides) to
    # phantom_membrane, which no readable SF pack touches; the planned carriers report no collision now.
    require(not any('phantom_obliterator' in x for x in ahead) and not any('blaze_rod' in x for x in ahead) and not current,
            f'no collision for the shipped golden hoe, and none for the planned Phantom Obliterator\'s new carrier '
            f'(phantom_membrane): {[x[-150:] for x in ahead]}')
    require(would is not None and 'no such file exists yet' in would and 'Nothing was written.' in would and snapshot(copy) == before,
            'the dry run wrote nothing: every file in the copy is byte-identical')
    stop_server()
    configure(owner='rpm-export', dry_run=False, mixer=copy)
    start = boot('rpm-export into the copy')
    wrote = line_with("Wrote the core resource pack in ResourcePackManager's mixer", start)
    after = snapshot(copy)
    added = sorted(set(after) - set(before))
    changed = sorted(k for k in before if after.get(k) != before[k])
    target = copy / 'Allium_core_resource_pack.zip'
    require(wrote is not None and added == ['Allium_core_resource_pack.zip'] and not changed
            and hashlib.sha1(target.read_bytes()).hexdigest() == PACK_SHA1,
            'the export wrote only Allium_core_resource_pack.zip (the bundled pack, byte for byte); every other file, including SF\'s '
            'legacy Allium_resource_pack.zip, is unchanged')
    require(state()['delivery'] is False, 'with rpm-export Allium does not send the pack itself')
    stop_server()
    start = boot('rpm-export again, unchanged')
    require(line_with("Kept the core resource pack in ResourcePackManager's mixer", start) is not None and snapshot(copy) == after,
            'a second start keeps the identical file')
    stop_server()
    require(live_stat(SF_MIXER) == live_before, 'SF\'s live mixer directory is untouched (same files, sizes and modification times)')
    # The copy holds SF's own packs: it is not evidence, so it does not outlive the run.
    shutil.rmtree(copy)
    emit('REMOVED the copy of SF\'s mixer')
    evidence['rpm'] = {'copied': len(before), 'skippedUnreadable': skipped, 'checked': checked, 'carrierAhead': ahead, 'wouldWrite': would,
                       'wrote': wrote, 'added': added}


summary = {}
try:
    for prt in (port, HTTP_PORT):
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', prt))
    start_http()
    build_fixture()
    write_lane()
    if a.scenario == 'rpm':
        rpm_scenario()
    else:
        optional_boot()
        required_boot()
        startup_mismatch_boot()
        override_boot()
        if not spec['multi']:
            one_owner_boot()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    try:
        leave()
    except Exception as e:
        failures.append('client cleanup ' + repr(e))
    try:
        stop_server()
    except Exception as e:
        failures.append('server cleanup ' + repr(e))
    if httpd is not None:
        httpd.shutdown()
    expected = lambda x: "Not sending Allium's resource pack" in x or 'Not sending the resource pack' in x
    errors = [x.strip() for x in logs if not expected(x) and ('Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x)))]
    require(not errors, 'no listener or Allium exceptions in the server log: ' + json.dumps(errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    summary = {'lane': a.lane, 'scenario': a.scenario, 'port': port, 'server': version, 'client': spec['client'],
               'packSha1': PACK_SHA1, 'packBytes': len(PACK), 'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))),
               'failures': failures, 'evidence': evidence, 'http': http_log,
               'hashes': {'Allium.jar': hashlib.sha256(a.allium_jar.read_bytes()).hexdigest()},
               'testSources': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in sorted(HERE.rglob('*')) if f.is_file() and f.suffix in ('.py', '.java', '.yml', '.cjs')}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2, default=str))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
        target = lane / name
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.exists():
            target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'scenario', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
