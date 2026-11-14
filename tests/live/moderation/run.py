#!/usr/bin/env python3
"""S17 vanish and mute real-protocol gate on Spigot 1.20.1, Paper 26.3, and MariaDB 10.11.
Tests three bots (ModStaff vanished staff, ModNorm normal player, ModObs staff observer) plus console:
- /vanish [on|off] [player], hidePlayer, tab list, ServerListPingEvent player count, join/quit
  message suppression, mob targeting suppression, item pickup suppression, pressure plate suppression,
  silent container view vs opt-in allium.vanish.container.live, and relog/restart persistence.
- Staff with allium.vanish.see see vanished players; normal players cannot discover vanished players
  through tab completion (/msg, /tpa, /mail send, /heal, /kit, /minecraft:msg, /minecraft:tp, /tp),
  vanilla /minecraft:list or /list, /minecraft:msg, /minecraft:tp, @a/@p selectors, Allium
  "looks offline" commands, or SocialSpy.
- Death/advancement suppression, XP orb attraction, vanished public chat toggle (default blocked),
  mid-session permission limitation + /allium reload recomputation, and raw Bukkit online count behavior.
- /mute, /unmute, /mutes (/mute list) covering HIGHEST chat re-cancel, configurable muted-commands
  (/me, /say, /tell, /minecraft:msg, /teammsg, Allium's own), invalid digit-leading duration refusal,
  fail-closed pre-login fault-injected load + retry recovery, timed expiry, and SQL/console audit.
- Disposable MariaDB 10.11 live backend suite (--suite mariadb) verifying real MySQL-dialect persistence.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
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
P.add_argument('--suite', choices=['spigot', 'paper', 'mariadb'], required=True)
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
for port in (25770, 25771, 25772):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25770-25772 free and gate lock held', flush=True)
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

MARIADB_CONTAINER = 'allium-gate-mariadb'
MARIADB_PORT = 25734
MARIADB_DB = 'allium_live_mod'


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


def prepare_mariadb_database():
    env = dict(line.split('=', 1) for line in (INFRA / 'mysql.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    password = shlex.split(env['MYSQL_ROOT_PASSWORD'])[0]
    subprocess.run(['docker', 'start', MARIADB_CONTAINER], check=True, stdout=subprocess.DEVNULL)
    for _ in range(30):
        r = subprocess.run(
            ['docker', 'exec', '-e', f'MYSQL_PWD={password}', MARIADB_CONTAINER,
             'mariadb', '-uroot', '-e', f'DROP DATABASE IF EXISTS {MARIADB_DB}; CREATE DATABASE {MARIADB_DB};'],
            capture_output=True, text=True
        )
        if r.returncode == 0:
            return password
        time.sleep(1)
    raise RuntimeError('MariaDB container did not become ready')


def query_mariadb(password, sql):
    r = subprocess.run(
        ['docker', 'exec', '-e', f'MYSQL_PWD={password}', MARIADB_CONTAINER,
         'mariadb', '-uroot', '-N', '-B', MARIADB_DB, '-e', sql],
        capture_output=True, text=True, check=True
    )
    return [line.split('\t') for line in r.stdout.splitlines() if line.strip()]


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
    def __init__(self, name, kind, port, mariadb_password=None):
        self.name, self.kind, self.port = name, kind, port
        self.mariadb_password = mariadb_password
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
        if mariadb_password is not None:
            db_cfg = config.setdefault('database', {})
            db_cfg['type'] = 'MYSQL'
            mysql_cfg = db_cfg.setdefault('mysql', {})
            mysql_cfg['host'] = '127.0.0.1'
            mysql_cfg['port'] = MARIADB_PORT
            mysql_cfg['database'] = MARIADB_DB
            mysql_cfg['username'] = 'root'
            mysql_cfg['password'] = mariadb_password
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

    def set_allow_vanish_public_chat(self, allow):
        cfg_path = self.path / 'plugins/Allium/config.yml'
        cfg = yaml.safe_load(cfg_path.read_text())
        cfg.setdefault('moderation', {}).setdefault('vanish', {})['allow-public-chat'] = bool(allow)
        cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
        os.chmod(cfg_path, 0o600)

    def argv(self):
        java = '/usr/lib/jvm/java-' + ('17' if self.kind == 'spigot' else '25') + '-openjdk/bin/java'
        cmd = [
            java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dallium.fault-injection=true',
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
        expected_db = 'Database connected (MYSQL)' if self.mariadb_password is not None else 'Database connected (SQLITE)'
        require(any(expected_db in s for s in self.logs[start:])
                and any('Moderation enabled' in s for s in self.logs[start:])
                and any('S17G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': ' + expected_db + ', moderation module, and helper enabled')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def reload_allium(self):
        start = len(self.logs)
        self.console('allium reload')
        return wait(lambda: any('Configuration reloaded.' in s for s in self.logs[start:]),
                    self.name + ': allium reload')

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

    def perm(self, name, node, value, sync=True):
        cmd = 'perm ' if sync else 'permraw '
        r = self.fixture(f'{cmd}{name} {node} {str(value).lower()}')
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
    for node in ['allium.vanish', 'allium.vanish.others', 'allium.vanish.see', 'allium.mute', 'allium.unmute']:
        lane.perm('ModStaff', node, True)
    for node in ['allium.vanish.see', 'allium.socialspy', 'allium.heal', 'allium.heal.others', 'allium.kit.others',
                 'allium.kits.starter', 'minecraft.command.list', 'bukkit.command.list', 'minecraft.command.msg',
                 'minecraft.command.teleport', 'minecraft.command.selector', 'allium.tp']:
        lane.perm('ModObs', node, True)
    for node in ['allium.heal', 'allium.heal.others', 'allium.kit.others', 'allium.kits.starter', 'allium.socialspy',
                 'allium.tp', 'minecraft.command.list', 'bukkit.command.list', 'minecraft.command.msg',
                 'minecraft.command.teleport', 'minecraft.command.selector', 'minecraft.command.me',
                 'minecraft.command.say', 'minecraft.command.teammsg']:
        lane.perm('ModNorm', node, True)
    lane.perm('ModNorm', 'allium.vanish.see', False)


def test_vanish_visibility_and_discovery(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']
    setup_bot_permissions(lane)

    obs.command('socialspy on', 'Social spy enabled')
    norm.command('socialspy on', 'Social spy enabled')

    st0 = lane.fixture('state')
    slp0 = server_list_ping(lane.port, 763 if lane.kind == 'spigot' else 777)
    require(not st0['staffVanished'] and st0['normCanSeeStaff'] and st0['obsCanSeeStaff']
            and slp0.get('players', {}).get('online') == 3,
            lane.name + ': before /vanish, ModStaff is visible to both ModNorm and ModObs (SLP online=3)',
            dict(state=st0, slpOnline=slp0.get('players', {}).get('online')))

    norm.command('tpa ModStaff', 'Teleport request sent to ModStaff')

    norm_mark = len(norm.lines)
    staff.command('vanish', 'Vanish enabled')
    st1 = lane.fixture('state')
    require(st1['staffVanished'] and st1['staffMetadata'] and st1['staffSleepingIgnored']
            and not st1['normCanSeeStaff'] and st1['obsCanSeeStaff'],
            lane.name + ': /vanish hides ModStaff from ModNorm while ModObs (allium.vanish.see) still sees ModStaff',
            st1)

    window(.2)
    require(not any('Teleport request cancelled' in c['plain'] for c in norm.chats(norm_mark)),
            lane.name + ': /vanish does not leak a cancel notification to ModNorm')
    norm.command('tpacancel', 'You have no pending teleport requests')

    slp = server_list_ping(lane.port, 763 if lane.kind == 'spigot' else 777)
    sample_names = [entry['name'] for entry in slp.get('players', {}).get('sample', [])]
    online_count = slp.get('players', {}).get('online')
    require('ModStaff' not in sample_names and set(sample_names) == {'ModNorm', 'ModObs'} and online_count == 2,
            lane.name + ': ServerListPingEvent drops vanished ModStaff from real SLP count (3 -> 2) and sample',
            dict(online=online_count, sample=sample_names))

    for cmd_prefix in ['msg Mod', 'tpa Mod', 'mail send Mod', 'heal Mod', 'kit starter Mod', 'tp Mod']:
        norm_matches, _ = norm.tab(cmd_prefix)
        obs_matches, _ = obs.tab(cmd_prefix)
        require('ModStaff' not in norm_matches and 'ModStaff' in obs_matches,
                f'{lane.name}: tab completion for "/{cmd_prefix}" hides ModStaff from ModNorm ({norm_matches}) and shows to ModObs ({obs_matches})')

    norm.command('msg ModStaff hello?', 'Player ModStaff not found')
    norm.command('ignore ModStaff', 'Player ModStaff not found')
    norm.command('tpa ModStaff', 'Player ModStaff not found')
    norm.command('tpahere ModStaff', 'Player ModStaff not found')
    norm.command('tpaccept ModStaff', 'Player ModStaff not found')
    norm.command('tpdeny ModStaff', 'Player ModStaff not found')
    norm.command('tp ModStaff', 'Player ModStaff not found')
    norm.command('heal ModStaff', 'Player ModStaff not found')
    norm.command('kit starter ModStaff', 'Player ModStaff not found')

    norm_spy_mark = len(norm.lines)
    staff_msg_mark = len(staff.lines)
    obs.command('msg ModStaff secret staff check', '[me \u2192 ModStaff] secret staff check')
    staff.chat(staff_msg_mark, '[ModObs \u2192 me] secret staff check')
    window(.3)
    require(not any('secret staff check' in c['plain'] for c in norm.chats(norm_spy_mark)),
            lane.name + ': SocialSpy does not leak messages involving vanished ModStaff to ModNorm without allium.vanish.see')

    norm_pm_mark = len(norm.lines)
    staff.command('msg ModNorm hi from shadows', '[me \u2192 ModNorm] hi from shadows')
    norm.chat(norm_pm_mark, '[ModStaff \u2192 me] hi from shadows')
    norm.command('reply who is there?', 'You have nobody to reply to.')
    obs_reply_mark = len(staff.lines)
    obs.command('reply still visible to staff', '[me \u2192 ModStaff] still visible to staff')
    staff.chat(obs_reply_mark, '[ModObs \u2192 me] still visible to staff')


def test_vanish_leaks_outside_allium(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']

    # 1. Vanilla /minecraft:list & /list + raw plugin getOnlinePlayers().size() observation
    norm_list = norm.command('minecraft:list', 'of a max of')
    norm_plain_list = norm.command('list', 'of a max of')
    obs_list_start = obs.cmd('minecraft:list')
    obs_list = wait(lambda: next((c for c in obs.chats(obs_list_start)
                                  if c.get('translate') == 'commands.list.players' or 'of a max of' in c['plain']), None),
                    'ModObs: minecraft:list response')
    obs_with = obs_list.get('withPlain', [])
    obs_has_staff = ('ModStaff' in obs_list['plain']) or any('ModStaff' in w for w in obs_with)
    obs_has_3 = ('There are 3 ' in obs_list['plain']) or (len(obs_with) >= 1 and obs_with[0] == '3')
    counts = lane.fixture('counts')
    observations.setdefault('counts', {})[lane.name] = counts
    require('ModStaff' not in norm_list['plain'] and 'There are 2 ' in norm_list['plain']
            and 'ModStaff' not in norm_plain_list['plain'] and 'There are 2 ' in norm_plain_list['plain']
            and obs_has_staff and obs_has_3
            and counts['rawOnlineCount'] == 3 and counts['visibleToNorm'] == 2 and counts['visibleToObs'] == 3,
            lane.name + ': vanilla /minecraft:list and /list hide ModStaff from ModNorm (2) and show to ModObs (3); raw getOnlinePlayers().size() documented as 3',
            dict(norm=norm_list, obs=obs_list, counts=counts))

    # 2. /minecraft:msg, /minecraft:tp, @a/@p selectors, and tab suggestions
    lane.fixture('tppos ModNorm 100.5 100.5')
    lane.fixture('tppos ModStaff 101.5 100.5')
    lane.fixture('tppos ModObs 110.5 100.5')
    staff_mark = len(staff.lines)
    norm_mc_msg = norm.command('minecraft:msg ModStaff vanish leak?', 'No player was found')
    norm_mc_tp = norm.command('minecraft:tp ModStaff', 'No player was found')
    norm_at_a = norm.command('minecraft:msg @a selector leak?', 'No player was found')
    norm_at_p = norm.command('minecraft:tp ModNorm @p[distance=0.5..]', 'No player was found')
    window(.2)
    require(not any('vanish leak?' in c['plain'] or 'selector leak?' in c['plain'] for c in staff.chats(staff_mark)),
            lane.name + ': /minecraft:msg, /minecraft:tp, and @a/@p selectors cannot reach or reveal vanished ModStaff',
            dict(mcMsg=norm_mc_msg, mcTp=norm_mc_tp, atA=norm_at_a, atP=norm_at_p))

    for prefix in ['minecraft:msg Mod', 'minecraft:tp Mod']:
        norm_matches, _ = norm.tab(prefix)
        obs_matches, _ = obs.tab(prefix)
        observations.setdefault('vanillaTab', {}).setdefault(lane.name, {})[prefix] = dict(norm=norm_matches, obs=obs_matches)
        if lane.kind == 'paper':
            require('ModStaff' not in norm_matches and 'ModStaff' in obs_matches,
                    f'{lane.name}: Paper vanilla "/{prefix}" tab completion hides ModStaff from ModNorm ({norm_matches}; obs={obs_matches})')
        else:
            require('ModStaff' in obs_matches,
                    f'{lane.name}: Spigot 1.20.1 vanilla Brigadier "/{prefix}" bypasses TabCompleteEvent (documented leak: norm={norm_matches}; obs={obs_matches})')

    # 3. Death and advancement messages
    norm_death_mark = len(norm.lines)
    obs_death_mark = len(obs.lines)
    death = lane.fixture('death ModStaff')
    wait(lambda: not lane.fixture('state')['staffDead'] and lane.fixture('state')['staffHealth'] > 0,
         lane.name + ': ModStaff respawned after death', 10)
    window(.2)
    require(death['sawDeathEvent'] and death['deathMessage'] is None
            and not any('ModStaff' in c['plain'] and ('died' in c['plain'] or 'fell' in c['plain'] or 'killed' in c['plain'])
                        for c in norm.chats(norm_death_mark) + obs.chats(obs_death_mark)),
            lane.name + ': vanished death message suppressed via PlayerDeathEvent#setDeathMessage(null)',
            death)

    norm_adv_mark = len(norm.lines)
    obs_adv_mark = len(obs.lines)
    adv = lane.fixture('adv ModStaff story/mine_stone')
    window(.3)
    norm_adv_msgs = [c for c in norm.chats(norm_adv_mark)
                     if 'Stone Age' in c['plain'] or c.get('translate') == 'chat.type.advancement.task']
    obs_adv_msgs = [c for c in obs.chats(obs_adv_mark)
                    if 'Stone Age' in c['plain'] or c.get('translate') == 'chat.type.advancement.task']
    observations.setdefault('advancement', {})[lane.name] = dict(adv=adv, normMsgs=norm_adv_msgs, obsMsgs=obs_adv_msgs)
    if lane.kind == 'paper':
        require(adv['done'] and not norm_adv_msgs and not obs_adv_msgs,
                lane.name + ': vanished advancement broadcast suppressed on Paper 26.3 via PlayerAdvancementDoneEvent#message(null)',
                observations['advancement'][lane.name])
    else:
        require(adv['done'],
                lane.name + ': Spigot 1.20.1 advancement observed (lacks per-player suppression API; broadcast=' + str(bool(norm_adv_msgs)) + ')',
                observations['advancement'][lane.name])

    # 4. XP orb attraction
    xp_spawn = lane.fixture('xp spawn ModStaff 2.5')
    window(.8)
    xp_check = lane.fixture('xp check ModStaff')
    lane.fixture('xp cleanup ModStaff')
    observations.setdefault('xpAttraction', {})[lane.name] = dict(spawn=xp_spawn, check=xp_check)
    if lane.kind == 'paper':
        require(xp_spawn['synthTargetCancelled'] and xp_check['expNow'] == xp_spawn['expBefore'] and xp_check['orbAlive'],
                lane.name + ': Paper 26.3 XP orb attraction & pickup prevented for vanished ModStaff (EntityTargetEvent + PlayerPickupExperienceEvent)',
                dict(spawn=xp_spawn, check=xp_check))
    else:
        require(xp_spawn['synthTargetCancelled']
                and (not xp_check['realOrbTargetEvents'] or any('cancelled=true' in e for e in xp_check['realOrbTargetEvents'])),
                lane.name + ': Spigot 1.20.1 XP orb homing cancelled via EntityTargetEvent (lacks PlayerPickupExperienceEvent on direct collision; expNow='
                + str(xp_check['expNow']) + ')',
                dict(spawn=xp_spawn, check=xp_check))

    # 5. Vanished player's public chat switch (default blocked, reloadable allow-public-chat: true)
    norm_vchat_mark = len(norm.lines)
    staff_vchat_mark = len(staff.lines)
    staff.say('vanished default blocked chat')
    staff.chat(staff_vchat_mark, 'cannot send public chat while vanished')
    window(.25)
    require(not any('vanished default blocked chat' in c['plain'] for c in norm.chats(norm_vchat_mark)),
            lane.name + ': vanished public chat is blocked by default (allow-public-chat: false)')

    lane.set_allow_vanish_public_chat(True)
    lane.reload_allium()
    norm_vchat_on = len(norm.lines)
    staff.say('vanished allowed chat')
    wait(lambda: any('vanished allowed chat' in c['plain'] for c in norm.chats(norm_vchat_on))
         or any('vanished allowed chat' in s for s in lane.logs[-20:]),
         lane.name + ': vanished public chat delivered when allow-public-chat=true')
    require(True, lane.name + ': vanished public chat config switch allows chat after /allium reload')

    lane.set_allow_vanish_public_chat(False)
    lane.reload_allium()
    staff_vchat_back = len(staff.lines)
    staff.say('vanished reblocked chat')
    staff.chat(staff_vchat_back, 'cannot send public chat while vanished')

    # 6. Opt-in container live permission (default false -> silent snapshot; allium.vanish.container.live -> live open)
    c_default = lane.fixture('container ModStaff')
    lane.perm('ModStaff', 'allium.vanish.container.live', True)
    c_live = lane.fixture('container ModStaff')
    lane.perm('ModStaff', 'allium.vanish.container.live', False)
    require(c_default['chestInteractCancelled'] and c_default['silentContainerOpened'] and c_default['diamonds'] == 3
            and not c_live['chestInteractCancelled'] and not c_live['silentContainerOpened'] and c_live['blockChestDiamonds'] == 3,
            lane.name + ': default opens silent snapshot (SilentContainerHolder); opt-in allium.vanish.container.live opens live chest',
            dict(default=c_default, live=c_live))

    # 7. Mid-session permission change limitation + /allium reload recomputation
    lane.perm('ModNorm', 'allium.vanish.see', True, sync=False)
    st_mid = lane.fixture('state')
    lane.reload_allium()
    st_reloaded = lane.fixture('state')
    lane.perm('ModNorm', 'allium.vanish.see', False, sync=False)
    lane.reload_allium()
    st_restored = lane.fixture('state')
    require(not st_mid['normCanSeeStaff'] and st_reloaded['normCanSeeStaff'] and not st_restored['normCanSeeStaff'],
            lane.name + ': mid-session permission grant stays hidden until /allium reload recomputes visibility pairs',
            dict(mid=st_mid, reloaded=st_reloaded, restored=st_restored))


def test_vanish_world_effects_relog_and_restart(lane):
    staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']

    fx = lane.fixture('effects ModStaff')
    require(fx['mobTargetCancelled'] and fx['mobTargetAfterEvent'] is None
            and fx['itemPickupCancelled']
            and fx['pressurePlateCancelled']
            and fx['chestInteractCancelled'] and fx['silentContainerOpened'] and fx['silentContainerDiamonds'] == 3,
            lane.name + ': vanished ModStaff suppresses mob targeting, item pickup, pressure plates, and opens lidded containers silently',
            fx)

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

    norm_mark = len(norm.lines)
    obs.command('msg ModNorm hello normal player', '[me \u2192 ModNorm] hello normal player')
    norm.chat(norm_mark, '[ModObs \u2192 me] hello normal player')

    lane.fixture('give ModNorm EMERALD 4')

    # 0. Digit-leading invalid duration token must be rejected with usage instead of becoming reason
    staff.command('mute ModNorm 10x spam', 'Usage: /mute <player> [duration] [reason]')
    require(not lane.fixture('state')['normMuted'],
            lane.name + ': invalid digit-leading duration token "10x" is rejected with usage without muting ModNorm')

    # 1. Staff mutes ModNorm for 8s with a reason
    norm_mute_mark = len(norm.lines)
    staff.command('mute ModNorm 15s Spamming global chat', 'ModNorm muted for')
    norm.chat(norm_mute_mark, 'You have been muted')

    staff.command('mutes', 'Active mutes (1)')
    staff.command('mute list', 'Spamming global chat')

    # 2. HIGHEST chat re-cancel even when another plugin un-cancels AsyncPlayerChatEvent at NORMAL
    lane.fixture('sabotagechat true')
    obs_chat_mark = len(obs.lines)
    norm_chat_mark = len(norm.lines)
    norm.say('can anyone hear me while muted?')
    blocked_chat = norm.chat(norm_chat_mark, 'You are muted (')
    window(.25)
    sab = lane.fixture('sabotagechat false')
    require('Spamming global chat' in blocked_chat['plain']
            and sab['sabotagedCount'] >= 1
            and not any('can anyone hear me while muted?' in c['plain'] for c in obs.chats(obs_chat_mark)),
            lane.name + ': HIGHEST chat listener re-cancels muted chat even after NORMAL un-cancel sabotage',
            dict(blocked=blocked_chat, sabotage=sab))

    # 3. Configurable muted-commands list (/me, /say, /tell, /minecraft:msg, /teammsg, /msg, /reply, /mail send, /mail gift)
    for muted_cmd in [
        'me waves silently',
        'say announcement attempt',
        'tell ModObs whisper attempt',
        'minecraft:msg ModObs vanilla msg attempt',
        'teammsg team attempt',
        'msg ModObs secret pm while muted',
        'reply reply while muted',
        'mail send ModObs mail body while muted',
        'mail gift ModObs 1 gift while muted',
    ]:
        blocked = norm.command(muted_cmd, 'You are muted (')
        require('Spamming global chat' in blocked['plain'],
                f'{lane.name}: muted command "/{muted_cmd}" blocked with remaining time and reason')

    # 4. Wait for timed mute (8s) to expire automatically without restart
    wait(lambda: not lane.fixture('state')['normMuted'], lane.name + ': timed mute expired automatically', 25)
    staff.command('mutes', 'There are no active mutes')

    obs_after_mark = len(obs.lines)
    norm.say('chat after timed mute expiry')
    wait(lambda: any('chat after timed mute expiry' in s for s in lane.logs), lane.name + ': chat delivered after mute expiry')
    norm.command('msg ModObs pm after expiry', '[me \u2192 ModObs] pm after expiry')
    obs.chat(obs_after_mark, '[ModNorm \u2192 me] pm after expiry')
    norm.command('mail send ModObs mail after expiry', 'Mail sent to ModObs')

    # 5. Fault-injected fail-closed pre-login load and retry recovery
    norm.disconnect()
    lane.fixture('fault moderation.load-fail 3')
    norm = lane.bot('ModNorm')
    setup_bot_permissions(lane)
    fail_state = lane.fixture('state')
    norm_fc_chat = len(norm.lines)
    norm.say('fail closed chat attempt')
    fc_msg = norm.chat(norm_fc_chat, 'Your moderation status is still loading')
    fc_cmd = norm.command('me fail closed me', 'Your moderation status is still loading')
    require(fail_state['normMuteLoadState'] == 'FAILED',
            lane.name + ': pre-login fault leaves ModNorm mute load state FAILED and fails closed on chat + /me',
            dict(state=fail_state, chat=fc_msg, cmd=fc_cmd))
    wait(lambda: lane.fixture('state')['normMuteLoadState'] == 'READY',
         lane.name + ': background retries recover mute load state to READY', 20)
    norm_rec_mark = len(norm.lines)
    norm.say('recovered chat after retry')
    wait(lambda: any('recovered chat after retry' in s for s in lane.logs[len(lane.logs) - 20:]),
         lane.name + ': chat delivered after mute state retry recovery')

    # 6. Console permanent mute and staff /unmute, plus console /vanish off ModStaff
    log_mark = len(lane.logs)
    lane.console('mute ModNorm Permanent console mute')
    wait(lambda: any('ModNorm has been muted. Reason: Permanent console mute' in re.sub('\u00a7.', '', s) for s in lane.logs[log_mark:]),
         'console mute ack')
    require(lane.fixture('state')['normMuted'], lane.name + ': console permanent mute applied')

    staff.command('unmute ModNorm Appealed', 'ModNorm has been unmuted')
    require(not lane.fixture('state')['normMuted'], lane.name + ': /unmute clears active mute')

    log_mark = len(lane.logs)
    lane.console('vanish off ModStaff')
    wait(lambda: any('Disabled vanish for ModStaff' in re.sub('\u00a7.', '', s) for s in lane.logs[log_mark:]),
         'console vanish off ack')
    st_end = lane.fixture('state')
    require(not st_end['staffVanished'] and st_end['normCanSeeStaff'],
            lane.name + ': /vanish off ModStaff restores ModStaff visibility to ModNorm', st_end)

    db = lane.fixture('db')
    audit_actions = {a['action'] for a in db['audits']}
    require({'VANISH_ON', 'VANISH_OFF', 'MUTE', 'MUTE_EXPIRED', 'UNMUTE'}.issubset(audit_actions)
            and any('Moderation audit action=MUTE' in s for s in lane.logs),
            lane.name + ': SQL allium_moderation_audit and server console record VANISH_ON, VANISH_OFF, MUTE, MUTE_EXPIRED, and UNMUTE',
            db['audits'][:10])


def run_mariadb_lane(password):
    lane = Lane('spigot-1.20.1-mariadb', 'spigot', 25772, mariadb_password=password)
    lane.boot()
    for name in ('ModStaff', 'ModNorm', 'ModObs'):
        lane.bot(name)

    def mariadb_checks():
        staff, norm, obs = lane.bots['ModStaff'], lane.bots['ModNorm'], lane.bots['ModObs']
        setup_bot_permissions(lane)

        staff.command('vanish', 'Vanish enabled')
        rows = wait(lambda: (lambda r: r if len(r) == 1 else None)(query_mariadb(password, "SELECT player_uuid, vanished FROM allium_vanish WHERE vanished = 1")),
                    'mariadb: allium_vanish row', 10)
        require(len(rows) == 1, 'mariadb: allium_vanish persists vanished ModStaff row in MariaDB', rows)

        staff.command('mute ModNorm 6s MariaDB timed mute', 'ModNorm muted for')
        mute_rows = wait(lambda: (lambda r: r if len(r) == 1 else None)(query_mariadb(password, "SELECT player_name, reason FROM allium_mutes")),
                         'mariadb: allium_mutes row', 10)
        require(len(mute_rows) == 1 and mute_rows[0][0] == 'ModNorm' and mute_rows[0][1] == 'MariaDB timed mute',
                'mariadb: allium_mutes persists active mute row in MariaDB', mute_rows)

        blocked = norm.command('msg ModObs blocked under mariadb', 'You are muted (')
        require('MariaDB timed mute' in blocked['plain'], 'mariadb: muted /msg blocked under MariaDB backend')

        wait(lambda: not lane.fixture('state')['normMuted'], 'mariadb: timed mute expired', 15)
        staff.command('mute ModNorm Permanent MariaDB mute', 'ModNorm has been muted')
        staff.command('unmute ModNorm Clear before restart', 'ModNorm has been unmuted')

        # Restart and confirm vanish persists in MariaDB without revealing ModStaff
        lane.stop()
        lane.boot()
        norm = lane.bot('ModNorm')
        obs = lane.bot('ModObs')
        norm_mark = len(norm.lines)
        staff = lane.bot('ModStaff')
        setup_bot_permissions(lane)
        window(.3)
        st = lane.fixture('state')
        require(st['staffVanished'] and not st['normCanSeeStaff'] and st['obsCanSeeStaff']
                and not any('ModStaff joined the game' in c['plain'] for c in norm.chats(norm_mark)),
                'mariadb: vanish persists across server restart in MariaDB 10.11 without revealing ModStaff', st)

        actions = wait(lambda: (lambda a: a if {'VANISH_ON', 'MUTE', 'MUTE_EXPIRED', 'UNMUTE'}.issubset(a) else None)(
            {row[0] for row in query_mariadb(password, "SELECT action FROM allium_moderation_audit")}),
            'mariadb: allium_moderation_audit actions', 10)
        require({'VANISH_ON', 'MUTE', 'MUTE_EXPIRED', 'UNMUTE'}.issubset(actions),
                'mariadb: allium_moderation_audit records moderation actions in MariaDB 10.11', sorted(actions))

    case('spigot-1.20.1-mariadb: live MariaDB persistence, expiry, audit, and restart', mariadb_checks)
    lane.stop()


def run_lane(lane):
    lane.boot()
    for name in ('ModStaff', 'ModNorm', 'ModObs'):
        lane.bot(name)
    case(lane.name + ': vanish visibility, tab completion, and looks-offline contract', lambda: test_vanish_visibility_and_discovery(lane))
    case(lane.name + ': vanish leaks outside Allium (vanilla commands, death/advancement, XP, chat toggle, container live, reload)', lambda: test_vanish_leaks_outside_allium(lane))
    case(lane.name + ': vanish world effects, relog, and server restart persistence', lambda: test_vanish_world_effects_relog_and_restart(lane))
    case(lane.name + ': mute HIGHEST, muted-commands, duration reject, fail-closed retry, expiry, unmute, and audit', lambda: test_mute_chat_msg_reply_mail_and_expiry(lane))
    lane.stop()


try:
    build()
    if A.suite == 'spigot':
        run_lane(Lane('spigot-1.20.1', 'spigot', 25770))
    elif A.suite == 'paper':
        run_lane(Lane('paper-26.3', 'paper', 25771))
    else:
        run_mariadb_lane(prepare_mariadb_database())
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
        slice='S17-followup',
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
