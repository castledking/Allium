#!/usr/bin/env python3
"""S17 vanish and mute real-protocol gate on Spigot 1.20.1 and Paper 26.3.
Tests three bots (ModStaff vanished staff, ModNorm normal player, ModObs staff observer) plus console:
- /vanish [on|off] [player], hidePlayer, tab list, ServerListPingEvent player count, join/quit
  message suppression, mob targeting suppression, item pickup suppression, pressure plate suppression,
  silent container view, and relog/restart persistence without revealing the player.
- Staff with allium.vanish.see see vanished players; normal players cannot discover vanished players
  through tab completion (/msg, /tpa, /mail send, /heal, /kit), /msg errors, /reply, /ignore,
  /msgtoggle, /tpa, /tpahere, /tpaccept, /tpdeny, or SocialSpy.
- /mute, /unmute, /mutes (/mute list) covering public chat, /msg, /reply, /mail send, and /mail gift
  with remaining duration, automatic timed mute expiration without restart, and SQL/console audit logs.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import zipfile
import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['spigot', 'paper'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='parent-confirmed frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--check', action='store_true')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261114/s17-moderation/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s17-moderation/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s17-moderation/runs')
A.allium_jar = A.allium_jar.resolve()
if not re.fullmatch('[0-9a-f]{64}', A.sha256) or hashlib.sha256(A.allium_jar.read_bytes()).hexdigest() != A.sha256:
    P.error('frozen jar SHA256 mismatch')
API = INFRA / 'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP = INFRA / 'client/node_modules/minecraft-protocol'
for p in [API, MCP / 'package.json', INFRA / 'servers/spigot-1.20.1.jar', A.paper_template / 'versions/26.3/paper-26.3.jar']:
    if not p.is_file():
        P.error('missing input ' + str(p))

A.run_root.parent.mkdir(parents=True, exist_ok=True)
lock = open(str(A.run_root.parent / 'ports.lock'), 'a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
for port in (25770, 25771):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25770-25771 free and gate lock held', flush=True)
if A.check:
    sys.exit(0)

RUN = A.run_root / A.run_name
RUN.mkdir(parents=True, exist_ok=False)
shutil.copy2(A.allium_jar, RUN / 'Allium-under-test.jar')
JAR = RUN / 'Allium-under-test.jar'
assert hashlib.sha256(JAR.read_bytes()).hexdigest() == A.sha256
checks, failures, observations, scopes = [], [], {}, []
lanes = []
SOURCE_FILES = [
    HERE / 'run.py',
    HERE / 'plugin/gate/ModGate.java',
    HERE / 'plugin/plugin.yml',
    HERE / 'client-mcp.cjs',
    HERE / 'client-raw26.cjs',
    HERE.parent / 'baseline/text-components.cjs',
    REPO / 'tests/live/providers/trigger/process_cleanup.py',
]
SOURCE_HASHES = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE_FILES}
for source in SOURCE_FILES:
    dest = RUN / 'harness' / source.relative_to(REPO)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
    os.chmod(dest, 0o444)
HERE = RUN / 'harness/tests/live/moderation'
started = time.time()


def interrupted(signum, frame):
    raise InterruptedError('gate received signal ' + str(signum))


signal.signal(signal.SIGTERM, interrupted)


def emit(s):
    print(s, flush=True)
    with (RUN / 'driver.log').open('a') as f:
        f.write(s + '\n')


def require(ok, label, detail=None):
    checks.append(dict(label=label, passed=bool(ok), detail=detail))
    if not ok:
        failures.append(label)
    emit(('PASS ' if ok else 'FAIL ') + label)
    return bool(ok)


def pump(proc, path, sink):
    with path.open('a') as f:
        for line in proc.stdout:
            f.write(line)
            f.flush()
            sink.append(line)


def wait(fn, label, timeout=15, monitor=True):
    deadline = time.monotonic() + timeout
    while True:
        if monitor:
            alive()
        value = fn()
        if value:
            return value
        if time.monotonic() > deadline:
            raise RuntimeError('timeout: ' + label)
        time.sleep(.025)


def alive():
    for lane in lanes:
        if lane.proc is not None and lane.proc.poll() is not None:
            raise RuntimeError(f'{lane.name} server unexpected exit {lane.proc.returncode}')
        for bot in lane.bots.values():
            if bot.proc is not None and bot.proc.poll() is not None:
                raise RuntimeError(f'{lane.name}/{bot.name} client unexpected exit {bot.proc.returncode}')




def server_list_ping(port, protocol=763):
    def vi(n):
        out = bytearray()
        while True:
            b = n & 0x7F
            n >>= 7
            if n:
                out.append(b | 0x80)
            else:
                out.append(b)
                return bytes(out)

    def pkt(pid, data=b""):
        body = vi(pid) + data
        return vi(len(body)) + body

    def read_varint(sock):
        num = 0
        for i in range(5):
            b = sock.recv(1)
            if not b:
                raise EOFError("unexpected EOF in varint")
            v = b[0]
            num |= (v & 0x7F) << (7 * i)
            if not (v & 0x80):
                return num
        raise ValueError("varint too long")

    def recv_exact(sock, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("unexpected EOF")
            buf.extend(chunk)
        return bytes(buf)

    host = b"127.0.0.1"
    hs = vi(protocol) + vi(len(host)) + host + struct.pack(">H", port) + vi(1)
    with socket.create_connection(("127.0.0.1", port), timeout=10) as s:
        s.sendall(pkt(0, hs) + pkt(0))
        _plen = read_varint(s)
        pid = read_varint(s)
        if pid != 0:
            raise RuntimeError(f"unexpected SLP packet id {pid}")
        slen = read_varint(s)
        payload = recv_exact(s, slen).decode("utf-8")
        return json.loads(payload)

def window(seconds=.4):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    subprocess.run([
        '/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-cp', f'{API}:{JAR}',
        '-d', str(classes), str(HERE / 'plugin/gate/ModGate.java')
    ], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/ModGate.jar'), '-C', str(classes), '.'], check=True)


class Bot:
    def __init__(self, lane, name):
        self.lane, self.name, self.proc, self.lines = lane, name, None, []
        self.last = 0

    def connect(self):
        start = len(self.lines)
        cmd = [
            'node', str(HERE / ('client-mcp.cjs' if self.lane.kind == 'spigot' else 'client-raw26.cjs')),
            str(self.lane.path), str(self.lane.port)
        ]
        if self.lane.kind == 'spigot':
            cmd.append('1.20.1')
        env = dict(os.environ, MC_PROTOCOL_MODULE=str(MCP), NODE_COMPILE_CACHE=str(RUN / '.node-cache'))
        self.proc = subprocess.Popen(cmd + [self.name], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / f'{self.lane.name}-{self.name}.log', self.lines), daemon=True).start()
        wait(lambda: any('BASEG-WIRE READY' in s for s in self.lines[start:]), self.name + ' wire READY', 120)
        window(.3)
        return start

    def write(self, s):
        alive()
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def cmd(self, text):
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = len(self.lines)
        self.write('cmd ' + text)
        wait(lambda: any(s == 'BASEG-WIRE SENT /' + text + '\n' for s in self.lines[start:]), self.name + ' wire command log')
        return start

    def say(self, text):
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = len(self.lines)
        self.write('say ' + text)
        wait(lambda: any(s == 'BASEG-WIRE SAY ' + text + '\n' for s in self.lines[start:]), self.name + ' wire say log')
        return start

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('BASEG-CHAT ')]

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def command(self, text, expected):
        start = self.cmd(text)
        return self.chat(start, expected)

    def tab(self, text):
        start, stamp = len(self.lines), time.monotonic()
        self.write('tab ' + text)
        req = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('MSGG-TABREQ ')), None),
                   'completion request sent')

        def response(tid):
            return next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:]
                         if s.startswith('MSGG-TAB ') and json.loads(s.split(' ', 1)[1])['id'] == tid), None)

        deadline = time.monotonic() + .5
        while time.monotonic() < deadline:
            alive()
            result = response(req['id'])
            if result is not None:
                return result['matches'], (time.monotonic() - stamp) * 1000
            time.sleep(.01)
        fence_start = len(self.lines)
        self.write('tab msgtoggle ')
        fence = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[fence_start:]
                                   if s.startswith('MSGG-TABREQ ')), None), 'completion positive-control request')
        positive = wait(lambda: response(fence['id']), 'completion positive-control response', 5)
        if not {'on', 'off'}.issubset(set(positive['matches'])):
            raise RuntimeError('completion fence did not return expected on/off suggestions')
        window(.15)
        result = response(req['id'])
        if result is None:
            return [], (time.monotonic() - stamp) * 1000
        return result['matches'], (time.monotonic() - stamp) * 1000

    def disconnect(self):
        if self.proc is None:
            return
        proc = self.proc
        self.proc = None
        stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=15)
        if self.lane.proc is not None and self.lane.proc.poll() is None:
            wait(lambda: self.name not in self.lane.fixture('online')['players'], self.name + ' server observed offline', 10)


class Lane:
    def __init__(self, name, kind, port):
        self.name, self.kind, self.port = name, kind, port
        self.path = RUN / name
        self.proc, self.logs, self.bots = None, [], {}
        self.path.mkdir()
        (self.path / 'plugins/Allium').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/ModGate.jar', self.path / 'plugins/ModGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        for m in ['moderation', 'messaging', 'mail', 'teleport', 'travel', 'kits', 'preferences']:
            config.setdefault('modules', {})[m] = True
        (self.path / 'plugins/Allium/config.yml').write_text(yaml.safe_dump(config, sort_keys=False))
        os.chmod(self.path / 'plugins/Allium/config.yml', 0o600)

        if kind == 'paper':
            for d in ['libraries', 'versions']:
                shutil.copytree(A.paper_template / d, self.path / d)
        else:
            shutil.copy2(INFRA / 'servers/spigot-1.20.1.jar', self.path / 'server.jar')
        (self.path / 'eula.txt').write_text('eula=true\n')
        (self.path / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
        (self.path / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
difficulty=easy
spawn-monsters=false
view-distance=2
simulation-distance=2
level-name=world
level-seed=7012012
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
''')
        lanes.append(self)

    def argv(self):
        java = '/usr/lib/jvm/java-' + ('17' if self.kind == 'spigot' else '25') + '-openjdk/bin/java'
        cmd = [
            java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Djava.io.tmpdir=' + str(self.path / 'tmp')
        ]
        if self.kind == 'spigot':
            return cmd + ['-jar', 'server.jar', '--nogui']
        with zipfile.ZipFile(INFRA / 'servers/paper-26.3-141.jar') as z:
            catalog = [line.split('\t') for line in z.read('META-INF/libraries.list').decode().splitlines()]
        jars = []
        for expected, coordinate, relative in catalog:
            lib = self.path / 'libraries' / relative
            if hashlib.sha256(lib.read_bytes()).hexdigest() != expected:
                raise RuntimeError('Paper library checksum mismatch: ' + relative)
            jars.append(lib)
        cp = ':'.join(str(p.relative_to(self.path)) for p in [self.path / 'versions/26.3/paper-26.3.jar', *jars])
        return cmd + ['-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']

    def boot(self):
        start = len(self.logs)
        emit('BOOT ' + self.name)
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', self.port))
        self.proc = subprocess.Popen(self.argv(), cwd=self.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / (self.name + '-server.log'), self.logs), daemon=True).start()
        wait(lambda: any('Done (' in s for s in self.logs[start:]), self.name + ' startup', 600)
        require(any('Moderation enabled' in s for s in self.logs[start:]) and any('S17G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': moderation module and helper enabled')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('s17g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S17G-DONE ' + s + ' => ') in v or ('S17G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S17G-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def bot(self, name):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect()
        return b

    def perm(self, name, node, value):
        r = self.fixture(f'perm {name} {node} {str(value).lower()}')
        require(r['permission'] == value, self.name + ': effective ' + name + ' ' + node + '=' + str(value))

    def stop(self):
        errors = []
        for bot in self.bots.values():
            try:
                bot.disconnect()
            except Exception as error:
                errors.append(bot.name + ': ' + str(error))
        if self.proc is not None:
            proc, self.proc = self.proc, None
            try:
                stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=90)
            except Exception as error:
                errors.append('server: ' + str(error))
        if errors:
            raise RuntimeError('; '.join(errors))


def case(label, fn):
    emit('CASE ' + label)
    start = len(failures)
    scope = dict(label=label, status='RUNNING')
    scopes.append(scope)
    try:
        fn()
        scope['status'] = 'FAIL' if len(failures) > start else 'PASS'
    except Exception as e:
        scope['status'] = 'FAIL'
        require(False, label + ': ' + str(e))
        (RUN / 'exceptions.log').open('a').write(traceback.format_exc() + '\n')


def setup_bot_permissions(lane):
    # ModStaff: staff with vanish, vanish.others, vanish.see, mute, unmute
    for node in ['allium.vanish', 'allium.vanish.others', 'allium.vanish.see', 'allium.mute', 'allium.unmute']:
        lane.perm('ModStaff', node, True)
    # ModObs: staff observer with vanish.see and socialspy
    for node in ['allium.vanish.see', 'allium.socialspy', 'allium.heal', 'allium.heal.others', 'allium.kit.others', 'allium.kits.starter']:
        lane.perm('ModObs', node, True)
    # ModNorm: normal player (explicitly also give heal.others / kit.others / socialspy to prove vanish still hides ModStaff!)
    for node in ['allium.heal', 'allium.heal.others', 'allium.kit.others', 'allium.kits.starter', 'allium.socialspy']:
        lane.perm('ModNorm', node, True)
    lane.perm('ModNorm', 'allium.vanish.see', False)


def test_vanish_visibility_and_discovery(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']
    setup_bot_permissions(lane)

    # Enable socialspy on both ModObs (has vanish.see) and ModNorm (no vanish.see)
    obs.command('socialspy on', 'Social spy enabled')
    norm.command('socialspy on', 'Social spy enabled')

    # Initially ModStaff is visible to both ModNorm and ModObs and included in real SLP count
    st0 = lane.fixture('state')
    slp0 = server_list_ping(lane.port, 763 if lane.kind == 'spigot' else 777)
    require(not st0['staffVanished'] and st0['normCanSeeStaff'] and st0['obsCanSeeStaff']
            and slp0.get('players', {}).get('online') == 3,
            lane.name + ': before /vanish, ModStaff is visible to both ModNorm and ModObs (SLP online=3)',
            dict(state=st0, slpOnline=slp0.get('players', {}).get('online')))

    # Normal player creates a pending /tpa request to ModStaff before ModStaff vanishes
    norm.command('tpa ModStaff', 'Teleport request sent to ModStaff')

    # ModStaff runs /vanish
    norm_mark = len(norm.lines)
    staff.command('vanish', 'Vanish enabled')
    st1 = lane.fixture('state')
    require(st1['staffVanished'] and st1['staffMetadata'] and st1['staffSleepingIgnored']
            and not st1['normCanSeeStaff'] and st1['obsCanSeeStaff'],
            lane.name + ': /vanish hides ModStaff from ModNorm (canSee=false) while ModObs (allium.vanish.see) still sees ModStaff',
            st1)

    # Pending /tpa from ModNorm to ModStaff is silently cleared (no cancel broadcast leak) and /tpacancel sees no pending request
    window(.2)
    require(not any('Teleport request cancelled' in c['plain'] for c in norm.chats(norm_mark)),
            lane.name + ': /vanish does not leak a cancel notification to ModNorm')
    norm.command('tpacancel', 'You have no pending teleport requests')

    # ServerListPingEvent removes vanished ModStaff from real SLP ping player count (3 -> 2) and sample
    slp = server_list_ping(lane.port, 763 if lane.kind == 'spigot' else 777)
    sample_names = [entry['name'] for entry in slp.get('players', {}).get('sample', [])]
    online_count = slp.get('players', {}).get('online')
    require('ModStaff' not in sample_names and set(sample_names) == {'ModNorm', 'ModObs'} and online_count == 2,
            lane.name + ': ServerListPingEvent drops vanished ModStaff from real SLP count (3 -> 2) and sample',
            dict(online=online_count, sample=sample_names))

    # Tab completions: ModNorm never sees ModStaff in /msg, /tpa, /mail send, /heal, or /kit starter
    for cmd_prefix in ['msg Mod', 'tpa Mod', 'mail send Mod', 'heal Mod', 'kit starter Mod']:
        norm_matches, _ = norm.tab(cmd_prefix)
        obs_matches, _ = obs.tab(cmd_prefix)
        require('ModStaff' not in norm_matches and 'ModStaff' in obs_matches,
                f'{lane.name}: tab completion for "/{cmd_prefix}" hides ModStaff from ModNorm ({norm_matches}) and shows to ModObs ({obs_matches})')

    # Every feature with a "looks offline" rule treats ModStaff as offline to ModNorm:
    norm.command('msg ModStaff hello?', 'Player ModStaff not found')
    norm.command('ignore ModStaff', 'Player ModStaff not found')
    norm.command('tpa ModStaff', 'Player ModStaff not found')
    norm.command('tpahere ModStaff', 'Player ModStaff not found')
    norm.command('tpaccept ModStaff', 'Player ModStaff not found')
    norm.command('tpdeny ModStaff', 'Player ModStaff not found')
    norm.command('heal ModStaff', 'Player ModStaff not found')
    norm.command('kit starter ModStaff', 'Player ModStaff not found')

    # Meanwhile ModObs (with allium.vanish.see) CAN message and heal vanished ModStaff, and SocialSpy does NOT leak to ModNorm
    norm_spy_mark = len(norm.lines)
    staff_msg_mark = len(staff.lines)
    obs.command('msg ModStaff secret staff check', '[me \u2192 ModStaff] secret staff check')
    staff.chat(staff_msg_mark, '[ModObs \u2192 me] secret staff check')
    window(.3)
    require(not any('secret staff check' in c['plain'] for c in norm.chats(norm_spy_mark)),
            lane.name + ': SocialSpy does not leak messages involving vanished ModStaff to ModNorm without allium.vanish.see')

    # Verify /reply looks offline to ModNorm ("You have nobody to reply to.") while ModStaff is vanished, without clearing ModObs's reply partner
    norm_pm_mark = len(norm.lines)
    staff.command('msg ModNorm hi from shadows', '[me \u2192 ModNorm] hi from shadows')
    norm.chat(norm_pm_mark, '[ModStaff \u2192 me] hi from shadows')
    norm.command('reply who is there?', 'You have nobody to reply to.')
    obs_reply_mark = len(staff.lines)
    obs.command('reply still visible to staff', '[me \u2192 ModStaff] still visible to staff')
    staff.chat(obs_reply_mark, '[ModObs \u2192 me] still visible to staff')


def test_vanish_world_effects_relog_and_restart(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']

    # 1. Mob targeting, item pickup, pressure plate, and silent container opening
    fx = lane.fixture('effects ModStaff')
    require(fx['mobTargetCancelled'] and fx['mobTargetAfterEvent'] is None
            and fx['itemPickupCancelled']
            and fx['pressurePlateCancelled']
            and fx['chestInteractCancelled'] and fx['silentContainerOpened'] and fx['silentContainerDiamonds'] == 3,
            lane.name + ': vanished ModStaff suppresses mob targeting, item pickup, pressure plates, and opens lidded containers silently',
            fx)

    # 2. Relog while vanished: quit message suppressed, join message suppressed, and relog never reveals ModStaff to ModNorm
    norm_quit_mark = len(norm.lines)
    obs_quit_mark = len(obs.lines)
    staff.disconnect()
    window(.3)
    require(not any('ModStaff left the game' in c['plain'] for c in norm.chats(norm_quit_mark))
            and not any('ModStaff left the game' in c['plain'] for c in obs.chats(obs_quit_mark)),
            lane.name + ': quit message is suppressed when vanished ModStaff disconnects')

    norm_join_mark = len(norm.lines)
    obs_join_mark = len(obs.lines)
    staff = lane.bot('ModStaff')
    setup_bot_permissions(lane)
    window(.3)
    st_relog = lane.fixture('state')
    require(not any('ModStaff joined the game' in c['plain'] for c in norm.chats(norm_join_mark))
            and not any('ModStaff joined the game' in c['plain'] for c in obs.chats(obs_join_mark))
            and st_relog['staffVanished'] and not st_relog['normCanSeeStaff'] and st_relog['obsCanSeeStaff'],
            lane.name + ': relog suppresses join message and keeps ModStaff vanished and hidden from ModNorm',
            st_relog)

    # 3. Full server restart: vanish state survives restart in SQL and ModStaff rejoins vanished without revealing
    lane.stop()
    lane.boot()
    norm = lane.bot('ModNorm')
    obs = lane.bot('ModObs')
    norm_restart_mark = len(norm.lines)
    staff = lane.bot('ModStaff')
    setup_bot_permissions(lane)
    window(.3)
    st_restart = lane.fixture('state')
    require(st_restart['staffVanished'] and not st_restart['normCanSeeStaff'] and st_restart['obsCanSeeStaff']
            and not any('ModStaff joined the game' in c['plain'] for c in norm.chats(norm_restart_mark)),
            lane.name + ': vanish state survives full server restart and ModStaff rejoins hidden from ModNorm',
            st_restart)


def test_mute_chat_msg_reply_mail_and_expiry(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']

    # Prepare reply target on ModNorm (ModObs messages ModNorm so ModNorm has a valid reply partner)
    norm_mark = len(norm.lines)
    obs.command('msg ModNorm hello normal player', '[me \u2192 ModNorm] hello normal player')
    norm.chat(norm_mark, '[ModObs \u2192 me] hello normal player')

    # Give ModNorm emeralds in main hand to test /mail gift refusal while muted
    lane.fixture('give ModNorm EMERALD 4')

    # 1. Staff mutes ModNorm for 8s with a reason
    norm_mute_mark = len(norm.lines)
    staff.command('mute ModNorm 8s Spamming global chat', 'ModNorm muted for')
    norm.chat(norm_mute_mark, 'You have been muted')

    # 2. Admin lists active mutes via /mutes and /mute list
    staff.command('mutes', 'Active mutes (1)')
    staff.command('mute list', 'Spamming global chat')

    # 3. Public chat while muted is blocked and reports remaining time + reason
    obs_chat_mark = len(obs.lines)
    norm_chat_mark = len(norm.lines)
    norm.say('can anyone hear me while muted?')
    blocked_chat = norm.chat(norm_chat_mark, 'You are muted (')
    window(.25)
    require('Spamming global chat' in blocked_chat['plain']
            and not any('can anyone hear me while muted?' in c['plain'] for c in obs.chats(obs_chat_mark)),
            lane.name + ': muted public chat is blocked with remaining time and reason and not delivered to other players',
            blocked_chat)

    # 4. /msg while muted is blocked with remaining time + reason
    obs_pm_mark = len(obs.lines)
    blocked_msg = norm.command('msg ModObs secret pm while muted', 'You are muted (')
    require('Spamming global chat' in blocked_msg['plain']
            and not any('secret pm while muted' in c['plain'] for c in obs.chats(obs_pm_mark)),
            lane.name + ': muted /msg is blocked with remaining time and reason',
            blocked_msg)

    # 5. /reply while muted is blocked with remaining time + reason
    blocked_reply = norm.command('reply reply while muted', 'You are muted (')
    require('Spamming global chat' in blocked_reply['plain'],
            lane.name + ': muted /reply is blocked with remaining time and reason',
            blocked_reply)

    # 6. /mail send and /mail gift while muted are blocked with remaining time + reason
    blocked_mail = norm.command('mail send ModObs mail body while muted', 'You are muted (')
    blocked_gift = norm.command('mail gift ModObs 1 gift while muted', 'You are muted (')
    require('Spamming global chat' in blocked_mail['plain'] and 'Spamming global chat' in blocked_gift['plain'],
            lane.name + ': muted /mail send and /mail gift are blocked with remaining time and reason')

    # 7. Wait for timed mute (8s) to expire automatically without a server restart
    wait(lambda: not lane.fixture('state')['normMuted'], lane.name + ': timed mute expired automatically', 15)
    staff.command('mutes', 'There are no active mutes')

    # Verify ModNorm can now send public chat, /msg, /reply, and /mail send
    obs_after_mark = len(obs.lines)
    norm.say('chat after timed mute expiry')
    wait(lambda: any('chat after timed mute expiry' in s for s in lane.logs), lane.name + ': chat delivered after mute expiry')
    norm.command('msg ModObs pm after expiry', '[me \u2192 ModObs] pm after expiry')
    obs.chat(obs_after_mark, '[ModNorm \u2192 me] pm after expiry')
    norm.command('mail send ModObs mail after expiry', 'Mail sent to ModObs')

    # 8. Console permanent mute and staff /unmute, plus console /vanish off ModStaff
    log_mark = len(lane.logs)
    lane.console('mute ModNorm Permanent console mute')
    wait(lambda: any('ModNorm has been muted. Reason: Permanent console mute' in re.sub('\u00a7.', '', s) for s in lane.logs[log_mark:]),
         'console mute ack')
    require(lane.fixture('state')['normMuted'], lane.name + ': console permanent mute applied')

    staff.command('unmute ModNorm Appealed', 'ModNorm has been unmuted')
    require(not lane.fixture('state')['normMuted'], lane.name + ': /unmute clears active mute')

    # Console unvanishes ModStaff via /vanish off ModStaff
    log_mark = len(lane.logs)
    lane.console('vanish off ModStaff')
    wait(lambda: any('Disabled vanish for ModStaff' in re.sub('\u00a7.', '', s) for s in lane.logs[log_mark:]),
         'console vanish off ack')
    st_end = lane.fixture('state')
    require(not st_end['staffVanished'] and st_end['normCanSeeStaff'],
            lane.name + ': /vanish off ModStaff restores ModStaff visibility to ModNorm', st_end)

    # 9. Verify audit trail in SQL and server logs
    db = lane.fixture('db')
    audit_actions = {a['action'] for a in db['audits']}
    require({'VANISH_ON', 'VANISH_OFF', 'MUTE', 'MUTE_EXPIRED', 'UNMUTE'}.issubset(audit_actions)
            and any('Moderation audit action=MUTE' in s for s in lane.logs),
            lane.name + ': SQL allium_moderation_audit and server console record VANISH_ON, VANISH_OFF, MUTE, MUTE_EXPIRED, and UNMUTE',
            db['audits'][:8])


def run_lane(lane):
    lane.boot()
    for name in ('ModStaff', 'ModNorm', 'ModObs'):
        lane.bot(name)
    case(lane.name + ': vanish visibility, tab completion, and looks-offline contract', lambda: test_vanish_visibility_and_discovery(lane))
    case(lane.name + ': vanish world effects, relog, and server restart persistence', lambda: test_vanish_world_effects_relog_and_restart(lane))
    case(lane.name + ': mute chat/msg/reply/mail enforcement, timed expiry, unmute, and audit log', lambda: test_mute_chat_msg_reply_mail_and_expiry(lane))
    lane.stop()


try:
    build()
    if A.suite == 'spigot':
        run_lane(Lane('spigot-1.20.1', 'spigot', 25770))
    else:
        run_lane(Lane('paper-26.3', 'paper', 25771))
except BaseException:
    (RUN / 'exceptions.log').open('a').write(traceback.format_exc() + '\n')
    require(False, 'unhandled gate exception')
finally:
    for lane in lanes:
        try:
            lane.stop()
        except Exception as error:
            (RUN / 'exceptions.log').open('a').write(f'cleanup {lane.name}: {error}\n')
    manifest = dict(
        slice='S17',
        suite=A.suite,
        run=str(RUN),
        startedAt=int(started),
        finishedAt=int(time.time()),
        alliumJar=str(JAR),
        alliumSha256=A.sha256,
        sourceHashes=SOURCE_HASHES,
        passed=not failures,
        passedCount=sum(1 for c in checks if c['passed']),
        failedCount=len(failures),
        failures=failures,
        scopes=scopes,
        checks=checks,
        observations=observations,
    )
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    emit(f"SUMMARY passed={manifest['passed']} checks={manifest['passedCount']}/{len(checks)} manifest={RUN / 'manifest.json'}")
    sys.exit(0 if manifest['passed'] else 1)
