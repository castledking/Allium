#!/usr/bin/env python3
"""S11 socialspy gate. Reuses S07 protocol clients and disposable runtime layout; no production writes."""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'port': 25710, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'),
                      'jar': Path('/mnt/storage/.allium-validation/infra/servers/spigot-1.20.1.jar')},
    'paper-26.3': {'port': 25711, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3')},
}
A, B, C = 'SpyA', 'SpyB', 'SpyC'
SOUND = 'minecraft:entity.experience_orb.pickup'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--check', action='store_true', help='read-only preflight; no build or servers')
p.add_argument('--prepare-only', action='store_true', help='build helper and copy runtime; no servers')
p.add_argument('--keep-runtime', action='store_true')
p.add_argument('--spigot-jar', type=Path, default=LANES['spigot-1.20.1']['jar'])
p.add_argument('--paper-template', type=Path, default=LANES['paper-26.3']['template'])
p.add_argument('--spigot-api', type=Path, default=Path('/mnt/storage/.allium-validation/infra/api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'))
p.add_argument('--mc-protocol-module', type=Path, default=Path('/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol'))
p.add_argument('--console-log-key', default='messaging.socialspy.log-to-console')
p.add_argument('--skip-console-opt-in', action='store_true')
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s11-20261112/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_][a-zA-Z0-9_.-]*', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s11-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s11-YYYYMMDD/runs root')
LANES['spigot-1.20.1']['jar'] = a.spigot_jar.resolve()
LANES['paper-26.3']['template'] = a.paper_template.resolve()
a.allium_jar = a.allium_jar.resolve()
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
# Preflight never mutates input caches, starts a server, or allocates a run directory.
for target in (a.allium_jar, a.spigot_api, Path(spec['java'])):
    if not target.is_file():
        p.error('missing file: ' + str(target))
if 'template' in spec:
    for target in (spec['template'] / 'libraries', spec['template'] / 'versions/26.3/paper-26.3.jar'):
        if not target.exists():
            p.error('missing Paper runtime input: ' + str(target))
else:
    if not spec['jar'].is_file():
        p.error('missing Spigot jar: ' + str(spec['jar']))
    if not (a.mc_protocol_module / 'package.json').is_file():
        p.error('missing read-only minecraft-protocol module: ' + str(a.mc_protocol_module))
for owned_port in ((25710, 25711) if a.check else (spec['port'],)):
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', owned_port))
print('PREFLIGHT inputs exist; owned port bind check passed; runtime=' + str(run), flush=True)
if a.check:
    sys.exit(0)
run.mkdir(parents=True)
allium_input = a.allium_jar
shutil.copy2(allium_input, run / 'Allium-under-test.jar')
a.allium_jar = run / 'Allium-under-test.jar'
lane = run / 'lane'
port = spec['port']
driver, failures, evidence = [], [], {}
checks = []
allium_sha = hashlib.sha256(a.allium_jar.read_bytes()).hexdigest()
notification_sound = False
last_command = {}
logs = []
server = None
bots = {}


def emit(message):
    driver.append(message)
    print(message, flush=True)


def require(ok, label):
    checks.append({'label': label, 'claim': 'verifiedlive' if ok else 'unverified', 'passed': bool(ok)})
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


def wait(fn, label, timeout=30):
    end = time.monotonic() + timeout
    while True:
        alive()
        value = fn()
        if value:
            return value
        if server is not None and server.poll() is not None:
            raise RuntimeError(f'server exited {server.returncode} waiting for {label}')
        if time.monotonic() > end:
            raise RuntimeError('timeout ' + label)
        time.sleep(0.05)


def strip(text):
    return re.sub('§.', '', text)


class Bot:
    def __init__(self, name):
        self.name = name
        self.proc = None
        self.lines = []

    def connect(self):
        kind, version = spec['client']
        script = 'client-raw26.cjs' if kind == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(HERE / script), str(lane), str(port)] + ([version] if version else []) + [self.name]
        start = len(self.lines)
        env = dict(os.environ, MC_PROTOCOL_MODULE=str(a.mc_protocol_module.resolve()), NODE_COMPILE_CACHE=str(lane / '.node-cache'))
        self.proc = subprocess.Popen(args, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, run / f'client-{self.name}.log', self.lines), daemon=True).start()
        wait(lambda: any('BASEG-WIRE READY' in x for x in self.lines[start:]), self.name + ' READY', 120)
        time.sleep(0.5)

    def disconnect(self):
        if self.proc is not None:
            stop_process(self.proc, lambda: (self.proc.stdin.write('stop\n'), self.proc.stdin.flush()), timeout=15)
        self.proc = None
        time.sleep(0.7)

    def write(self, line):
        self.proc.stdin.write(line + '\n')
        self.proc.stdin.flush()

    def cmd(self, command):
        pause = 1.1 - (time.monotonic() - last_command.get(self.name, 0))
        if pause > 0:
            time.sleep(pause)
        last_command[self.name] = time.monotonic()
        start = len(self.lines)
        self.write('cmd ' + command)
        return start

    def chats(self, start):
        return [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-CHAT ')]

    def sounds(self, start):
        return [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-SOUND ')]

    def chat(self, start, fragment, timeout=10):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), f'{self.name} chat "{fragment}"', timeout)

    def tab(self, text):
        start = len(self.lines)
        self.write('tab ' + text)
        line = wait(lambda: next((x for x in self.lines[start:] if x.startswith('MSGG-TAB ')), None), f'{self.name} tab "{text}"', 10)
        return json.loads(line.split(' ', 1)[1])['matches']


def console(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def fixture(text):
    start = len(logs)
    console('s11g ' + text)
    line = wait(lambda: next((x for x in logs[start:] if f'S11G-DONE {text} => ' in x or f'S11G-FAIL {text} => ' in x), None), 's11g ' + text)
    if 'S11G-FAIL' in line:
        raise RuntimeError(line.strip())
    time.sleep(0.1)
    return json.loads(line.split(' => ', 1)[1])


def console_says(fragment, start, timeout=10):
    return wait(lambda: next((strip(x).strip() for x in logs[start:] if fragment in strip(x)), None), 'console "' + fragment + '"', timeout)


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{a.spigot_api}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/SpyGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'SpyGate.jar'), '-C', str(classes), '.'], check=True)


def write_lane():
    lane.mkdir()
    if 'template' in spec:
        for d in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / d, lane / d)
    else:
        shutil.copy2(spec['jar'], lane / 'server.jar')
    (lane / 'plugins/Allium').mkdir(parents=True)
    shutil.copy2(a.allium_jar, lane / 'plugins/Allium.jar')
    shutil.copy2(run / 'fixture/SpyGate.jar', lane / 'plugins/SpyGate.jar')
    with zipfile.ZipFile(a.allium_jar) as jar:
        (lane / 'plugins/Allium/config.yml').write_bytes(jar.read('config.yml'))
    (lane / 'tmp').mkdir()
    (lane / 'eula.txt').write_text('eula=true\n')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
    (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
difficulty=peaceful
spawn-monsters=false
view-distance=2
simulation-distance=2
level-name=world
level-seed=7011108
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
''')


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
                '-Dallium.fault-injection=true', '-Djava.io.tmpdir=' + str(lane / 'tmp'), '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dallium.fault-injection=true', '-Djava.io.tmpdir=' + str(lane / 'tmp'), '-jar', 'server.jar', '--nogui']


def boot(label):
    global server
    emit('BOOT ' + label)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1)
    threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True).start()
    wait(lambda: any('Done (' in x for x in logs[start:]), label + ' Done', 600)
    require(any('Private messaging enabled' in x for x in logs[start:]) and any('S11G-GATE READY' in x for x in logs[start:]),
            f'{label}: Allium\'s messaging module and the fixture enabled')


def stop_server():
    global server
    if server is not None:
        stop_process(server, lambda: console('stop'), timeout=90)
    server = None


def alive():
    for bot in bots.values():
        if bot.proc is not None and bot.proc.poll() is not None:
            raise RuntimeError(f'{bot.name} exited unexpectedly: {bot.proc.returncode}')


def window(seconds=1.6):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        if server is not None and server.poll() is not None:
            raise RuntimeError('server exited during observation')
        time.sleep(0.05)
    alive()


def ready():
    for name in (A, B, C):
        wait(lambda: fixture('state ' + name)['loaded'], name + ' loaded preferences', 30)


def perm(name, node, state):
    result = fixture(f'perm {name} {node} {str(state).lower()}')
    if state != 'unset':
        require(result['permission'] == state, f'{name}: effective {node}={state}')
    return result


def spy(state, name=C):
    start = bots[name].cmd('socialspy' + (' ' + state if state else ''))
    response = bots[name].chat(start, 'Social spy')
    expected = {'on': True, 'off': False}.get(state)
    current = fixture('state ' + name)['socialSpy']
    require(expected is None or current == expected, f'{name} /socialspy {state}: {response["plain"]}')
    return current


def mark():
    return {name: len(bot.lines) for name, bot in bots.items()}


def matching(name, offsets, token):
    return [c for c in bots[name].chats(offsets[name]) if token in c['plain']]


def sounds(name, offsets):
    # This known inline named event is used by the received and configured spy notifications.
    # Other world/registry sounds are retained in logs but not claimed absent.
    return [s for s in bots[name].sounds(offsets[name]) if s['name'] == SOUND]


def console_copies(start, token, sender=A, recipient=B):
    return [strip(line).strip() for line in logs[start:] if token in strip(line)
            and sender in strip(line) and recipient in strip(line)
            and not re.search(r'issued (server )?command|S11G-|\bCONSOLE issued', strip(line), re.I)]


serial = 0
console_enabled = False


def delivered(label, sender=A, recipient=B, observed=1, command='msg', body=None, spy_sound=None):
    global serial
    serial += 1
    token = f'S11_{serial:03d}_{label}'
    text = token + ((' ' + body) if body else '')
    offsets, log_start = mark(), len(logs)
    bots[sender].cmd((f'msg {recipient} ' if command == 'msg' else command + ' ') + text)
    bots[recipient].chat(offsets[recipient], token)
    bots[sender].chat(offsets[sender], token)
    if observed and C not in (sender, recipient):
        bots[C].chat(offsets[C], token)
    window()
    captured = {name: matching(name, offsets, token) for name in bots}
    for name in (sender, recipient):
        require(len(captured[name]) == 1 and not captured[name][0]['overlay'],
                f'{label}: {name} gets exactly one ordinary PM, no own spy duplicate')
    require(captured[sender] and captured[sender][0]['plain'] == f'[me → {recipient}] {text}',
            label + ': sender confirmation is correctly addressed')
    require(captured[recipient] and captured[recipient][0]['plain'] == f'[{sender} → me] {text}',
            label + ': recipient PM is correctly addressed')
    if C not in (sender, recipient):
        copies = captured[C]
        require(len(copies) == observed and all(sender in c['plain'] and recipient in c['plain']
                and c['plain'].endswith(text) and not c['overlay'] for c in copies),
                f'{label}: observer gets {observed} correctly addressed spy copies')
        expected_sound = observed if (notification_sound if spy_sound is None else spy_sound) else 0
        require(len(sounds(C, offsets)) == expected_sound,
                f'{label}: observer receives exactly {expected_sound} configured notification sound events')
    else:
        require(len(sounds(C, offsets)) == (1 if C == recipient else 0),
                label + ': own observer conversation has only the ordinary received sound')
    copies = console_copies(log_start, token, sender, recipient)
    require(len(copies) == (1 if console_enabled else 0),
            f'{label}: console spy copies={len(copies)} (opt-in={console_enabled})')
    evidence[token] = {'kind': 'delivered', 'label': label, 'sender': sender, 'recipient': recipient,
                       'captures': captured, 'spySounds': sounds(C, offsets), 'consoleCopies': copies}
    return captured, offsets


def blocked(label, command='msg', recipient=B, expect="can't receive"):
    global serial
    serial += 1
    token = f'S11_{serial:03d}_{label}'
    offsets, log_start = mark(), len(logs)
    bots[A].cmd((f'msg {recipient} ' if command == 'msg' else command + ' ') + token)
    refusal = bots[A].chat(offsets[A], expect)
    window()
    require(not bots[B].chats(offsets[B]) and not sounds(B, offsets),
            label + ': recipient has no chat or message notification sound')
    require(not bots[C].chats(offsets[C]) and not sounds(C, offsets),
            label + ': observer has no chat or configured spy sound')
    require(not console_copies(log_start, token), label + ': console has no spy copy')
    evidence[token] = {'kind': 'blocked', 'refusal': refusal, 'captures': {n: bots[n].chats(offsets[n]) for n in bots}}
    return refusal['plain']


def command(name, text, fragment):
    start = bots[name].cmd(text)
    return bots[name].chat(start, fragment)


def basic():
    # Base permission is default op, but the persisted opt-in is false for a fresh player.
    perm(C, 'allium.socialspy', True)
    require(not fixture('state ' + C)['socialSpy'], 'new player socialspy preference is off')
    delivered('default_off', observed=0)
    spy('on')
    spy('on')
    delivered('msg')
    delivered('reply', sender=B, recipient=A, command='reply')
    delivered('own_sender', sender=C, recipient=A)
    delivered('own_recipient', sender=A, recipient=C)
    # C's real reply target is A. A subsequent observed A/B delivery must not change it.
    delivered('partner_control')
    delivered('observer_reply_partner', sender=C, recipient=A, command='reply')
    spy('off')
    spy('off')
    delivered('explicit_off', observed=0)
    require(spy('') is True, 'bare /socialspy toggles off to on')
    delivered('bare_on')
    command(C, 'socialspy invalid', 'Usage')
    require(fixture('state ' + C)['socialSpy'], 'invalid /socialspy does not change enabled state')
    perm(C, 'allium.socialspy', False)
    delivered('permission_removed', observed=0)
    # Allium hides a command from a player without its permission, so the server answers as for an
    # unknown command (the vanilla translation key, flattened by the clients).
    start = bots[C].cmd('socialspy off')
    refusal = wait(lambda: next((c for c in bots[C].chats(start) if 'unknown' in c['plain'].lower() or 'permission' in c['plain'].lower()), None),
                   'C refusal', 10)
    require(refusal is not None, f'without allium.socialspy, /socialspy is refused as an unknown command: {refusal["plain"]}')
    require(fixture('state ' + C)['socialSpy'], 'unauthorized command does not change stored preference')
    perm(C, 'allium.socialspy', True)
    delivered('permission_restored')


def block_paths(suffix=''):
    command(B, 'msgtoggle off', 'no longer accept')
    toggle = blocked('toggle' + suffix)
    # /reply rechecks policy after a previously delivered A/B exchange.
    blocked('reply_toggle' + suffix, command='reply')
    command(B, 'msgtoggle on', 'now accept')
    command(B, 'ignore ' + A, 'now ignoring')
    ignored = blocked('ignore' + suffix)
    require(toggle == ignored, 'toggle and ignore give identical generic refusals' + suffix)
    command(B, 'unignore ' + A, 'no longer ignoring')
    fixture('mute ' + A + ' on')
    blocked('muted' + suffix, expect='muted')
    fixture('mute ' + A + ' off')
    blocked('offline' + suffix, recipient='Nobody123', expect='not found')
    blocked('self' + suffix, recipient=A, expect="can't message yourself")
    delivered('blocked_recovery' + suffix)


def exemption():
    for name in (A, B, C):
        effective = fixture(f'inspect {name} allium.socialspy.exempt')
        require(effective['registered'] and effective['default'] == 'FALSE' and not effective['permission'],
                name + ': exemption is registered default false and ordinary players are not exempt')
    fixture('op ' + A + ' true')
    require(not fixture(f'inspect {A} allium.socialspy.exempt')['permission'], 'operator is not implicitly exempt')
    fixture('op ' + A + ' false')
    perm(A, 'allium.*', True)
    require(not fixture(f'inspect {A} allium.socialspy.exempt')['permission'], 'allium.* does not implicitly grant exemption')
    perm(A, 'allium.*', 'unset')
    for name in (A, B):
        perm(name, 'allium.socialspy.exempt', True)
        delivered('exempt_' + name, observed=0)
        perm(name, 'allium.socialspy.exempt', False)
        delivered('unexempt_' + name)


def visibility():
    for hidden in (A, B):
        fixture('vanish ' + hidden + ' on')
        # The underlying PM must deliver when the recipient is vanished.
        perm(A, 'allium.vanish.see', True)
        perm(C, 'allium.vanish.see', False)
        delivered('hidden_' + hidden, observed=0)
        perm(C, 'allium.vanish.see', True)
        delivered('visible_' + hidden)
        perm(C, 'allium.vanish.see', False)
        delivered('visibility_revoked_' + hidden, observed=0)
        fixture('vanish ' + hidden + ' off')
        perm(A, 'allium.vanish.see', False)
    for hidden in (A, B):
        fixture(f'hide {C} {hidden} on')
        delivered('bukkit_hidden_' + hidden, observed=0)
        fixture(f'hide {C} {hidden} off')
        delivered('bukkit_visible_' + hidden)


def untrusted_input():
    # Spy formatting permissions must not reinterpret the sender's untrusted body.
    for node in ('allium.msg.color', 'allium.msg.hex', 'allium.msg.gradient'):
        perm(A, node, False)
        perm(C, node, True)
    typed = '&cRed #00ff00hex <b>bold</b> <click:run_command:/op SpyA>op</click> https://example.org'
    captured, _ = delivered('untrusted', body=typed)
    for name in (B, C):
        got = captured[name][0]
        index = got['plain'].index(typed)
        require(not got['interactive'] and set(got['colors'][index:]) == {'#ffffff'},
                name + ': untrusted body is literal, white, with no click/hover/insertion data')
    perm(A, 'allium.msg.color', True)
    # This body is deliberately transformed, so inspect outside delivered()'s literal helper.
    offsets = mark()
    bots[A].cmd(f'msg {B} S11_color &cRed')
    b = bots[B].chat(offsets[B], 'S11_color')
    c = bots[C].chat(offsets[C], 'S11_color')
    window()
    require(c['plain'].endswith('S11_color Red') and c['colors'][-3:] == ['#ff5555'] * 3
            and b['colors'][-3:] == c['colors'][-3:] and not c['interactive']
            and len(matching(C, offsets, 'S11_color')) == 1,
            'sender-authorized color reaches the spy exactly once using sender permissions')
    perm(A, 'allium.msg.color', False)


def restart(label):
    for bot in bots.values():
        bot.disconnect()
    stop_server()
    boot(label)
    for bot in bots.values():
        bot.connect()
    ready()
    perm(C, 'allium.socialspy', True)


def persistence():
    # A is an explicitly authorized spy with persisted off; C has persisted on.
    perm(A, 'allium.socialspy', True)
    spy('off', A)
    restart('persisted_on_and_off')
    perm(A, 'allium.socialspy', True)
    require(fixture('state ' + C)['socialSpy'] and not fixture('state ' + A)['socialSpy'],
            'restart retained both enabled and disabled preferences')
    delivered('persisted_on_without_command')
    # B/C conversation is visible to A, whose off setting must survive restart.
    offsets = mark()
    bots[B].cmd(f'msg {C} S11_persisted_off')
    bots[C].chat(offsets[C], 'S11_persisted_off')
    window()
    require(not matching(A, offsets, 'S11_persisted_off'), 'persisted-off authorized A gets no B/C spy copy')


def sound_gate():
    global notification_sound
    # The helper edits only the disposable lane's lang file, preserving the authored spy text.
    fixture('sound on')
    start = len(logs)
    console('allium reload')
    console_says('Configuration reloaded.', start)
    window(1)
    notification_sound = True
    command(C, 'mfx volume 0.25', '0.25')
    _, offsets = delivered('audible_spy')
    events = sounds(C, offsets)
    require(len(events) == 1 and abs(events[0]['volume'] - 0.15) < 1e-6 and abs(events[0]['pitch'] - 1) < 1e-6,
            'configured spy sound occurs once at lang 0.6 × /mfx 0.25 = 0.15, pitch 1')
    block_paths('_audible')
    # Exempt and visibility suppressions must suppress configured sounds as well as chat.
    perm(A, 'allium.socialspy.exempt', True)
    delivered('exempt_audible', observed=0)
    perm(A, 'allium.socialspy.exempt', False)
    fixture('vanish ' + A + ' on')
    perm(C, 'allium.vanish.see', False)
    delivered('hidden_audible', observed=0)
    fixture('vanish ' + A + ' off')
    delivered('own_recipient_audible', sender=A, recipient=C)
    delivered('own_sender_audible', sender=C, recipient=A)
    command(C, 'mfx toggle', 'muted')
    delivered('spy_mfx_muted', spy_sound=False)
    command(C, 'mfx toggle', 'back on')
    delivered('spy_mfx_restored')


def console_gate():
    global console_enabled
    if a.skip_console_opt_in:
        checks.append({'label': 'console opt-in explicitly skipped', 'claim': 'unverified', 'passed': None})
        return
    config = fixture('config ' + a.console_log_key + ' true')
    require(not config['before'] and config['after'], 'console spy logging was false before explicit config opt-in')
    restart('console_opt_in')
    console_enabled = True
    delivered('console_optin_msg')
    delivered('console_optin_reply', sender=B, recipient=A, command='reply')
    command(B, 'msgtoggle off', 'no longer accept')
    blocked('console_optin_blocked')
    command(B, 'msgtoggle on', 'now accept')
    # Exemption applies to the console too; normal PM still succeeds.
    perm(B, 'allium.socialspy.exempt', True)
    offsets, start = mark(), len(logs)
    bots[A].cmd(f'msg {B} S11_console_exempt')
    bots[B].chat(offsets[B], 'S11_console_exempt')
    window()
    require(not console_copies(start, 'S11_console_exempt') and not matching(C, offsets, 'S11_console_exempt')
            and not sounds(C, offsets), 'exemption suppresses opted-in console and configured player spy notification')
    perm(B, 'allium.socialspy.exempt', False)
    # Console is a normal conversation endpoint, so it must receive exactly its normal PM.
    offsets, start = mark(), len(logs)
    bots[A].cmd('msg console S11_console_endpoint')
    console_says(f'[{A} → me] S11_console_endpoint', start)
    window()
    normal = [strip(x).strip() for x in logs[start:] if f'[{A} → me] S11_console_endpoint' in strip(x)]
    require(len(normal) == 1 and not console_copies(start, 'S11_console_endpoint', A, 'Console'),
            'console conversation prints one ordinary PM without an extra console spy copy')
    require(len(matching(C, offsets, 'S11_console_endpoint')) == 1, 'player observer can see delivered console conversation')


summary = {}
prepared = False
try:
    build_fixture()
    write_lane()
    if a.prepare_only:
        prepared = True
        emit('PREPARED unverified: helper compiled and disposable runtime copied; no servers started')
    else:
        boot('initial')
        for name in (A, B, C):
            bots[name] = Bot(name)
            bots[name].connect()
        ready()
        basic()
        block_paths()
        exemption()
        visibility()
        untrusted_input()
        persistence()
        sound_gate()
        console_gate()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    for bot in bots.values():
        try:
            bot.disconnect()
        except Exception as e:
            failures.append('client cleanup ' + repr(e))
    try:
        stop_server()
    except Exception as e:
        failures.append('server cleanup ' + repr(e))
    errors = [x.strip() for x in logs if 'Could not pass event' in x
              or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
    if logs:
        require(not errors, 'no listener or Allium exceptions: ' + json.dumps(errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    summary = {'lane': a.lane, 'port': port, 'host': '127.0.0.1', 'server': version, 'client': spec['client'],
               'claim': 'verifiedlive' if logs and not failures and not prepared else 'unverified',
               'checks': checks, 'failures': failures, 'evidence': evidence, 'preparedOnly': prepared,
               'scope': 'S11 socialspy protocol-bot gate; named notification sound only; no human visual claim',
               'hashes': {'Allium.jar': allium_sha},
               'inputs': {'spigotJar': str(a.spigot_jar), 'paperTemplate': str(a.paper_template),
                          'spigotApi': str(a.spigot_api), 'mcProtocolModule': str(a.mc_protocol_module)},
               'testSources': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in sorted(HERE.rglob('*')) if f.is_file() and f.suffix in ('.py', '.java', '.yml', '.cjs')}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    if not a.keep_runtime and not prepared:
        for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar', '.node-cache', 'tmp']:
            target = lane / name
            if target.is_dir():
                shutil.rmtree(target)
            elif target.exists():
                target.unlink()
    emit('SUMMARY ' + json.dumps({'lane': a.lane, 'claim': summary['claim'], 'server': version,
                                 'checks': len(checks), 'failures': failures, 'jarSha256': allium_sha, 'run': str(run)}))
sys.exit(1 if failures else 0)
