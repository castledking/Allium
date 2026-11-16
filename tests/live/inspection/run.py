#!/usr/bin/env python3
"""S24 (/invsee and /enderchest) real-protocol live gate on Spigot 1.20.1 and Paper 26.3 (SQLite only)."""
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
import sqlite3
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
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261115/s24-inspection/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()

if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s24-inspection/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s24-inspection/runs')
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
for port in (25800, 25801, 25802):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25800-25802 free and gate lock held', flush=True)
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
    HERE / 'plugin/gate/InspectGate.java',
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
HERE = RUN / 'harness/tests/live/inspection'
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


def wait(fn, label, timeout=20, monitor=True):
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
        for bot in list(lane.bots.values()):
            if bot.proc is not None and bot.proc.poll() is not None:
                raise RuntimeError(f'{lane.name}/{bot.name} client unexpected exit {bot.proc.returncode}')


def window(seconds=.35):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    subprocess.run([
        '/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-cp', f'{API}:{JAR}',
        '-d', str(classes), str(HERE / 'plugin/gate/InspectGate.java')
    ], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/InspectGate.jar'), '-C', str(classes), '.'], check=True)


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
        wait(lambda: any('INSP-WIRE READY' in s for s in self.lines[start:]), self.name + ' wire READY', 120)
        window(.25)
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
        wait(lambda: any(s == 'INSP-WIRE SENT /' + text + '\n' for s in self.lines[start:]), self.name + ' wire command log')
        return start

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('INSP-CHAT ')]

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def chat_any(self, start, fragments):
        return wait(lambda: next((c for c in self.chats(start) if any(f in c['plain'] for f in fragments)), None),
                    self.name + ': any(' + ','.join(fragments) + ')')

    def command(self, text, expected):
        start = self.cmd(text)
        return self.chat(start, expected)

    def open_window(self, start):
        return wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('INSP-OPEN ')), None),
                    self.name + ' INSP-OPEN')

    def wait_content(self, start, wid=None):
        return wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:]
                                  if s.startswith('INSP-CONTENT ') and (wid is None or json.loads(s.split(' ', 1)[1])['windowId'] == wid)), None),
                    self.name + ' INSP-CONTENT')

    def wait_close(self, start):
        return wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('INSP-CLOSE ')), None),
                    self.name + ' INSP-CLOSE')

    def click(self, slot, button=0, mode=0):
        start = len(self.lines)
        self.write(f'click {slot} {button} {mode}')
        wait(lambda: any(s.startswith('INSP-CLICK ') for s in self.lines[start:]), self.name + f' click {slot}')
        return start

    def drag(self, *slots):
        start = len(self.lines)
        self.write('drag ' + ' '.join(str(s) for s in slots))
        wait(lambda: any(s.startswith('INSP-CLICK ') for s in self.lines[start:]), self.name + f' drag {slots}')
        return start

    def close_gui(self):
        start = len(self.lines)
        self.write('close')
        wait(lambda: any(s.startswith('INSP-CLOSE-SENT ') for s in self.lines[start:]), self.name + ' close sent')
        window(.15)
        return start

    def tab(self, text):
        start, stamp = len(self.lines), time.monotonic()
        self.write('tab ' + text)
        req = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('INSP-TABREQ ')), None),
                   'completion request sent')

        def response(tid):
            return next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:]
                         if s.startswith('INSP-TAB ') and json.loads(s.split(' ', 1)[1])['id'] == tid), None)

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
                                   if s.startswith('INSP-TABREQ ')), None), 'completion positive-control request')
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
        shutil.copy2(RUN / 'helper/InspectGate.jar', self.path / 'plugins/InspectGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        for m in ['moderation', 'messaging', 'mail', 'teleport', 'travel', 'kits', 'preferences']:
            config.setdefault('modules', {})[m] = True
        config.setdefault('database', {})['type'] = 'SQLITE'
        config.setdefault('economy', {})['vault-provider-enabled'] = False
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
level-seed=7012024
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
        require(any('Database connected (SQLITE)' in s for s in self.logs[start:])
                and any('Moderation enabled' in s for s in self.logs[start:])
                and any('/invsee, /enderchest' in s for s in self.logs[start:])
                and any('S24G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': SQLite, moderation/inspection, and InspectGate ready')
        cfg_now = yaml.safe_load((self.path / 'plugins/Allium/config.yml').read_text())
        require(cfg_now.get('economy', {}).get('vault-provider-enabled') is False,
                self.name + ': economy.vault-provider-enabled remains false')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('s24g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S24G-DONE ' + s + ' => ') in v or ('S24G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S24G-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def bot(self, name):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect()
        return b

    def perm(self, name, node, value):
        r = self.fixture(f'perm {name} {node} {str(value).lower()}')
        require(r['permission'] == value, self.name + ': perm ' + name + ' ' + node + '=' + str(value))

    def stop(self):
        errors = []
        for bot in list(self.bots.values()):
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


def assert_clean_logs(lane, log_file):
    text = log_file.read_text(errors='replace')
    bad_lines = []
    allowed_warn_fragments = [
        'Vanish is enabled on CraftBukkit',
        'Vanish is enabled on Spigot',
        'Could not save config.yml',
        'Inspection',
        'Corrupt offline player data',
        'Refused offline',
        'CAS conflict',
        'Allium plugin test helper',
        'OfflinePlayer',
        'moved too quickly',
        'DataVersion',
    ]
    for raw in text.splitlines():
        if '[ERROR]' in raw or 'Exception' in raw or 'ERROR]:' in raw:
            if not any(f in raw for f in ['Corrupt', 'DataVersionTooNew', 'CasConflict']):
                bad_lines.append(raw)
        elif '[WARN]' in raw or 'WARN]:' in raw:
            if any(marker in raw for marker in ['Allium', 'Inspect', 'Moderation']):
                if not any(f in raw for f in allowed_warn_fragments):
                    bad_lines.append(raw)
    require(len(bad_lines) == 0, f'{lane.name}: server log free of unexpected WARN/ERROR/Exception',
            {'badLines': bad_lines[:10]})


def run_suite(kind, port):
    lane = Lane(kind, kind, port)
    lane.boot()

    staff = lane.bot('InspStaff')
    obs = lane.bot('InspObs')
    norm = lane.bot('InspNorm')

    lane.perm('InspStaff', 'allium.invsee', True)
    lane.perm('InspStaff', 'allium.enderchest', True)
    lane.perm('InspStaff', 'allium.enderchest.others', True)
    lane.perm('InspObs', 'allium.invsee', True)
    lane.perm('InspObs', 'allium.enderchest', True)
    lane.perm('InspObs', 'allium.enderchest.others', True)

    # ------------------------------------------------------------------
    # Scope 1: Console, Self, Permissions, Exempt/Hierarchy, Vanish Parity
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':permissions-hierarchy-vanish')
    c_start = len(lane.logs)
    lane.console('invsee InspNorm')
    wait(lambda: any('player' in s.lower() and 'only' in s.lower() for s in lane.logs[c_start:]),
         lane.name + ': console invsee player-only')
    require(True, lane.name + ': console /invsee rejected with player-only notice')

    staff.command('invsee InspStaff', 'cannot use /invsee on yourself')
    norm.chat_any(norm.cmd('invsee InspStaff'), ['permission', 'command.unknown.command', 'Unknown'])
    norm.chat_any(norm.cmd('enderchest'), ['permission', 'command.unknown.command', 'Unknown'])

    # Self enderchest (/ec) + no .others refusal
    lane.perm('InspNorm', 'allium.enderchest', True)
    ec_start = norm.cmd('ec')
    ec_open = norm.open_window(ec_start)
    require(ec_open['windowId'] > 0, lane.name + ': /ec opens own enderchest without .others')
    norm.close_gui()
    norm.command('echest InspStaff', 'permission')
    lane.perm('InspNorm', 'allium.enderchest', False)

    # Seed InspNorm inventory + enderchest + BukkitValues PDC
    seeded = lane.fixture('seedinv InspNorm')
    require(seeded['slots']['hotbar0'] == '3xDIAMOND' and seeded['slots']['helmet'] == '1xDIAMOND_HELMET'
            and seeded['slots']['offhand'] == '1xSHIELD' and seeded['slots']['ec0'] == '2xNETHERITE_INGOT',
            lane.name + ': seeded InspNorm inventory, armor, offhand, and enderchest')

    # Exempt protection vs allium.moderation.bypass
    lane.perm('InspNorm', 'allium.moderation.exempt', True)
    staff.command('invsee InspNorm', 'moderation hierarchy')
    staff.command('enderchest InspNorm', 'moderation hierarchy')
    lane.perm('InspStaff', 'allium.moderation.bypass', True)
    op_start = staff.cmd('invsee InspNorm')
    op_w = staff.open_window(op_start)
    require(op_w['windowId'] > 0 and 'Read-Only' in op_w['title'], lane.name + ': bypass overrides exempt for /invsee')
    staff.close_gui()
    lane.perm('InspStaff', 'allium.moderation.bypass', False)
    lane.perm('InspNorm', 'allium.moderation.exempt', False)

    # Hierarchy rank protection (InspNorm temporarily holds higher staff rank allium.ban while InspStaff holds allium.mute)
    lane.perm('InspStaff', 'allium.mute', True)
    lane.perm('InspNorm', 'allium.ban', True)
    staff.command('invsee InspNorm', 'moderation hierarchy')
    lane.perm('InspNorm', 'allium.ban', False)
    lane.perm('InspStaff', 'allium.mute', False)

    # Vanish blindness parity
    lane.perm('InspNorm', 'allium.vanish', True)
    norm.command('vanish on', 'Vanish enabled')
    lane.perm('InspNorm', 'allium.vanish', False)

    # Tab completion hides vanished InspNorm
    tabs_hidden, _ = staff.tab('invsee ')
    require('InspNorm' not in tabs_hidden, lane.name + ': /invsee tab completion hides vanished target')
    tabs_ec_hidden, _ = staff.tab('echest ')
    require('InspNorm' not in tabs_ec_hidden, lane.name + ': /echest tab completion hides vanished target')

    # Response parity without .offline
    r_van = staff.command('invsee InspNorm', 'allium.invsee.offline')
    r_unk = staff.command('invsee UnknownBot99', 'allium.invsee.offline')
    require(r_van['plain'].replace('InspNorm', '<X>') == r_unk['plain'].replace('UnknownBot99', '<X>'),
            lane.name + ': vanish parity without .offline')

    # Response parity with .offline only
    lane.perm('InspStaff', 'allium.invsee.offline', True)
    r_van2 = staff.command('invsee InspNorm', 'moderation hierarchy')
    r_unk2 = staff.command('invsee UnknownBot99', 'moderation hierarchy')
    require(r_van2['plain'] == r_unk2['plain'],
            lane.name + ': vanish parity with .offline but without bypass')

    # Response parity with .offline + bypass
    lane.perm('InspStaff', 'allium.moderation.bypass', True)
    r_van3 = staff.command('invsee InspNorm', 'unavailable right now')
    r_unk3 = staff.command('invsee UnknownBot99', 'unavailable right now')
    require(r_van3['plain'].replace('InspNorm', '<X>') == r_unk3['plain'].replace('UnknownBot99', '<X>'),
            lane.name + ': vanish parity with .offline + bypass (never opens live or .dat)')

    # With allium.vanish.see -> visible in tab + opens live
    lane.perm('InspStaff', 'allium.vanish.see', True)
    tabs_visible, _ = staff.tab('invsee ')
    require('InspNorm' in tabs_visible, lane.name + ': /invsee tab completion shows vanished target with vanish.see')
    v_start = staff.cmd('invsee InspNorm')
    v_win = staff.open_window(v_start)
    require(v_win['windowId'] > 0, lane.name + ': vanish.see allows opening live /invsee on vanished target')
    staff.close_gui()

    # Cleanup vanish & temp perms
    lane.perm('InspNorm', 'allium.vanish', True)
    norm.command('vanish off', 'Vanish disabled')
    lane.perm('InspNorm', 'allium.vanish', False)
    for node in ['allium.vanish.see', 'allium.invsee.offline', 'allium.moderation.bypass']:
        lane.perm('InspStaff', node, False)

    # ------------------------------------------------------------------
    # Scope 2: Online /invsee & /enderchest Read-Only, .edit, Layout, Confinement, Guards, Quit, Death
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':online-layout-confinement-edits-guards')
    lane.fixture('seedinv InspNorm')
    lane.fixture('setslot InspStaff 0 IRON_INGOT 6')

    # Online Read-Only layout & confinement
    ro_start = staff.cmd('invsee InspNorm')
    ro_win = staff.open_window(ro_start)
    staff.wait_content(ro_start, ro_win['windowId'])
    require(ro_win['windowId'] > 0 and 'Read-Only' in ro_win['title'], lane.name + ': online /invsee Read-Only opened')

    gui_state = lane.fixture('inv InspStaff')
    top = gui_state.get('openTopNonEmpty', {})
    require(gui_state.get('openTopSize') == 45
            and top.get('0') == '5xEMERALD'
            and top.get('26') == '7xGOLD_INGOT'
            and top.get('27') == '3xDIAMOND'
            and top.get('36') == '1xDIAMOND_HELMET'
            and top.get('37') == '1xDIAMOND_CHESTPLATE'
            and top.get('38') == '1xDIAMOND_LEGGINGS'
            and top.get('39') == '1xDIAMOND_BOOTS'
            and top.get('40') == '1xSHIELD'
            and all(top.get(str(i)) == '1xGRAY_STAINED_GLASS_PANE' for i in range(41, 45)),
            lane.name + ': /invsee 45-slot layout (storage 0..26, hotbar 27..35, armor 36..39, offhand 40, panes 41..44)',
            {'top': top})

    # Concurrent read-only viewer + live refresh sync
    obs_ro = obs.cmd('invsee InspNorm')
    obs_win = obs.open_window(obs_ro)
    obs.wait_content(obs_ro, obs_win['windowId'])
    require(obs_win['windowId'] > 0, lane.name + ': second concurrent read-only /invsee allowed')

    # Wire attacks against Read-Only GUI (top click, shift from top, shift from bottom, locked pane 41)
    staff.click(27, 0, 0)
    staff.click(0, 0, 1)
    staff.click(72, 0, 1)
    staff.click(41, 0, 0)
    window(.25)
    after_ro = lane.fixture('inv InspNorm')['slots']
    staff_after_ro = lane.fixture('inv InspStaff')['slots']
    require(after_ro['hotbar0'] == '3xDIAMOND' and after_ro['storage9'] == '5xEMERALD'
            and staff_after_ro['hotbar0'] == '6xIRON_INGOT',
            lane.name + ': Read-Only GUI rejects pickup, shift-out, shift-in, and locked pane clicks')

    # Live refresh update to read-only viewers
    lane.fixture('setslot InspNorm 0 DIAMOND 11')
    window(.35)
    refreshed_staff = lane.fixture('inv InspStaff').get('openTopNonEmpty', {})
    refreshed_obs = lane.fixture('inv InspObs').get('openTopNonEmpty', {})
    require(refreshed_staff.get('27') == '11xDIAMOND' and refreshed_obs.get('27') == '11xDIAMOND',
            lane.name + ': live online inventory mutation refreshes to concurrent read-only viewers')
    staff.close_gui()
    obs.close_gui()
    lane.fixture('seedinv InspNorm')

    # Online Mutable (.edit) + Busy Locks + Locked Panes + Armor/Offhand/Hotbar Edits
    lane.perm('InspStaff', 'allium.invsee.edit', True)
    lane.perm('InspObs', 'allium.invsee.edit', True)
    ed_start = staff.cmd('invsee InspNorm')
    ed_win = staff.open_window(ed_start)
    staff.wait_content(ed_start, ed_win['windowId'])
    require('Edit' in ed_win['title'], lane.name + ': online /invsee with .edit shows Edit title')

    # Mutable lock blocks second mutable viewer, allows read-only viewer
    obs.command('invsee InspNorm', 'busy right now')
    lane.perm('InspObs', 'allium.invsee.edit', False)
    obs_ro2 = obs.cmd('invsee InspNorm')
    obs_w2 = obs.open_window(obs_ro2)
    require(obs_w2['windowId'] > 0, lane.name + ': read-only viewer allowed alongside active mutable viewer')
    obs.close_gui()

    # Locked panes 41..44 refused even in .edit (pickup and drag)
    staff.click(41, 0, 0)
    staff.click(44, 0, 0)
    staff.drag(41, 42)
    window(.2)
    gui_locked = lane.fixture('inv InspStaff').get('openTopNonEmpty', {})
    require(all(gui_locked.get(str(i)) == '1xGRAY_STAINED_GLASS_PANE' for i in range(41, 45)),
            lane.name + ': locked panes 41..44 preserved under .edit clicks and drags')

    # Place 6xIRON_INGOT from InspStaff hotbar[0] (rawSlot 72) into InspNorm hotbar[1] (GUI slot 28)
    staff.click(72, 0, 0)
    staff.click(28, 0, 0)
    window(.2)
    require(lane.fixture('inv InspNorm')['slots']['hotbar1'] == '6xIRON_INGOT',
            lane.name + ': /invsee .edit places item into target hotbar[1]')

    # Armor swap (helmet GUI slot 36) & offhand shift-take (GUI slot 40)
    lane.fixture('setslot InspStaff 0 GOLDEN_HELMET 1')
    staff.click(72, 0, 0)
    staff.click(36, 0, 0)
    staff.click(72, 0, 0)
    staff.click(40, 0, 1)
    window(.2)
    after_armor = lane.fixture('inv InspNorm')['slots']
    require(after_armor['helmet'] == '1xGOLDEN_HELMET' and after_armor['offhand'] == 'AIR',
            lane.name + ': /invsee .edit updates armor helmet and offhand slots', {'slots': after_armor})

    # Same-tick 1-tick guard on target drop & swap
    lane.fixture('setslot InspStaff 0 STICK 2')
    lane.fixture('armsametick InspNorm')
    staff.click(72, 0, 0)
    staff.click(29, 0, 0)
    window(.2)
    guard_res = lane.fixture('getsametick')['result']
    require(guard_res is not None and guard_res.get('dropCancelled') is True and guard_res.get('swapCancelled') is True,
            lane.name + ': 1-tick target guard cancels DropItem and SwapHandItems on edit tick', {'guard': guard_res})

    # Mid-session edit permission revocation aborts open mutable session
    rev_start = len(staff.lines)
    lane.perm('InspStaff', 'allium.invsee.edit', False)
    staff.chat(rev_start, 'permission')
    staff.wait_close(rev_start)
    require(True, lane.name + ': revoking .edit mid-session aborts mutable /invsee cleanly')
    lane.perm('InspStaff', 'allium.invsee.edit', True)

    # Mail busy lock exclusion (mutable refused, read-only still allowed)
    lane.fixture('mailbusylock InspNorm true')
    staff.command('invsee InspNorm', 'busy right now')
    lane.perm('InspStaff', 'allium.invsee.edit', False)
    ro_busy = staff.cmd('invsee InspNorm')
    ro_busy_w = staff.open_window(ro_busy)
    require(ro_busy_w['windowId'] > 0, lane.name + ': read-only /invsee allowed while MailService busy lock held')
    staff.close_gui()
    lane.fixture('mailbusylock InspNorm false')
    lane.perm('InspStaff', 'allium.invsee.edit', True)

    # Online /enderchest (/ec) Read-Only & Mutable (.edit)
    ec_ro = obs.cmd('enderchest InspNorm')
    ec_ro_w = obs.open_window(ec_ro)
    obs.wait_content(ec_ro, ec_ro_w['windowId'])
    obs.click(0, 0, 1)
    window(.2)
    require(lane.fixture('inv InspNorm')['slots']['ec0'] == '2xNETHERITE_INGOT',
            lane.name + ': Read-Only /enderchest rejects edits')
    obs.close_gui()

    lane.perm('InspStaff', 'allium.enderchest.edit', True)
    ec_ed = staff.cmd('ec InspNorm')
    ec_ed_w = staff.open_window(ec_ed)
    staff.wait_content(ec_ed, ec_ed_w['windowId'])
    require(lane.fixture('inv InspStaff').get('openTopSize') == 27, lane.name + ': /ec opens 27-slot enderchest GUI')
    staff.click(26, 0, 1)  # shift-take 4xENDER_PEARL from ec[26]
    window(.2)
    require(lane.fixture('inv InspNorm')['slots']['ec26'] == 'AIR',
            lane.name + ': /enderchest .edit removes ec[26] item')
    staff.close_gui()

    # Target death during open mutable /invsee + clean drops (no duplication)
    lane.fixture('seedinv InspNorm')
    d_open = staff.cmd('invsee InspNorm')
    d_w = staff.open_window(d_open)
    staff.wait_content(d_open, d_w['windowId'])
    lane.fixture('setslot InspStaff 0 IRON_INGOT 4')
    staff.click(72, 0, 0)
    staff.click(28, 0, 0)
    window(.2)
    lane.fixture('armdeathdrops')
    d_close_start = len(staff.lines)
    lane.fixture('death InspNorm')
    staff.chat(d_close_start, 'died; live inventory inspection closed')
    staff.wait_close(d_close_start)
    drops = lane.fixture('getdeathdrops')['drops']
    require('IRON_INGOT:4' in drops and 'DIAMOND:3' in drops,
            lane.name + ': target death aborts mutable /invsee and drops post-edit inventory cleanly', {'drops': drops})
    wait(lambda: lane.fixture('alive InspNorm')['alive'], lane.name + ': InspNorm respawned after death')

    # Target quit during open mutable /invsee -> flushes before .dat save and aborts viewer
    lane.fixture('seedinv InspNorm')
    q_open = staff.cmd('invsee InspNorm')
    q_w = staff.open_window(q_open)
    staff.wait_content(q_open, q_w['windowId'])
    lane.fixture('setslot InspStaff 0 COPPER_INGOT 5')
    staff.click(72, 0, 0)
    staff.click(28, 0, 0)
    window(.2)
    q_close_start = len(staff.lines)
    norm.disconnect()
    staff.chat(q_close_start, 'went offline; live inspection closed')
    staff.wait_close(q_close_start)

    dat_after_quit = lane.fixture('datinfo InspNorm')
    require(dat_after_quit['exists'] is True
            and dat_after_quit['inv'].get('1') == '5xCOPPER_INGOT'
            and dat_after_quit['bukkitValues'].get('allium:s24_proof') == 'allium-keep-s24'
            and dat_after_quit['bukkitValues'].get('inspgate:marker') == 'keep-me-s24',
            lane.name + ': target quit flushes /invsee edit to <uuid>.dat and preserves BukkitValues PDC keys',
            {'datinfo': dat_after_quit})

    # ------------------------------------------------------------------
    # Scope 3: Offline /invsee & /enderchest, NBT/BukkitValues Preservation, CAS/Corrupt/Newer/Login Fence
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':offline-nbt-cas-loginfence')
    # Offline gating: requires BOTH .offline AND bypass
    staff.command('invsee InspNorm', 'allium.invsee.offline')
    lane.perm('InspStaff', 'allium.invsee.offline', True)
    staff.command('invsee InspNorm', 'moderation hierarchy')
    lane.perm('InspStaff', 'allium.moderation.bypass', True)

    # Verify offline edit preserves exact allium:mail_receipts & allium:kit_receipts on InspObs (without reconnecting InspObs)
    lane.perm('InspStaff', 'allium.invsee.edit', True)
    lane.fixture('seedreceipts InspObs')
    obs.disconnect()
    wait(lambda: lane.fixture('datinfo InspObs')['exists'], lane.name + ': InspObs .dat written on quit')
    lane.fixture('setslot InspStaff 0 STICK 4')
    obs_ed = staff.cmd('invsee InspObs')
    obs_w = staff.open_window(obs_ed)
    staff.wait_content(obs_ed, obs_w['windowId'])
    staff.click(72, 0, 0)
    staff.click(27, 0, 0)
    window(.4)
    staff.close_gui()
    wait(lambda: lane.fixture('datinfo InspObs')['inv'].get('0') == '4xSTICK',
         lane.name + ': offline /invsee edit on InspObs flushed to .dat')
    dat_obs = lane.fixture('datinfo InspObs')
    require(dat_obs['bukkitValues'].get('allium:mail_receipts') == 'mail-proof-s24'
            and dat_obs['bukkitValues'].get('allium:kit_receipts') == 'kit-proof-s24',
            lane.name + ': offline /invsee edit preserves allium:mail_receipts and allium:kit_receipts verbatim',
            {'bukkitValues': dat_obs['bukkitValues']})

    # Offline Read-Only confinement
    lane.perm('InspStaff', 'allium.invsee.edit', False)
    off_ro = staff.cmd('invsee InspNorm')
    off_ro_w = staff.open_window(off_ro)
    staff.wait_content(off_ro, off_ro_w['windowId'])
    require(('Offline' in off_ro_w['title'] or '[Off]' in off_ro_w['title'])
            and ('Read-Only' in off_ro_w['title'] or '(RO)' in off_ro_w['title']),
            lane.name + ': offline /invsee Read-Only opened',
            {'title': off_ro_w['title']})
    hash_before_ro = lane.fixture('datinfo InspNorm')['sha256']
    staff.click(27, 0, 0)
    window(.2)
    staff.close_gui()
    window(.2)
    require(lane.fixture('datinfo InspNorm')['sha256'] == hash_before_ro,
            lane.name + ': offline Read-Only /invsee does not modify <uuid>.dat')

    # Offline Mutable /invsee (.edit): add 8xAMETHYST_SHARD to hotbar[2] (slot 29), then remove helmet (slot 36)
    lane.perm('InspStaff', 'allium.invsee.edit', True)
    lane.fixture('setslot InspStaff 0 AMETHYST_SHARD 8')
    off_ed = staff.cmd('invsee InspNorm')
    off_ed_w = staff.open_window(off_ed)
    staff.wait_content(off_ed, off_ed_w['windowId'])
    require(('Offline' in off_ed_w['title'] or '[Off]' in off_ed_w['title'])
            and 'Edit' in off_ed_w['title'],
            lane.name + ': offline /invsee Edit opened',
            {'title': off_ed_w['title']})
    staff.click(72, 0, 0)
    staff.click(29, 0, 0)
    window(.3)  # allow async write 1 to finish before issuing click 2 (writeInFlight guard)
    staff.click(36, 0, 1)  # shift-take helmet
    window(.4)
    staff.close_gui()
    wait(lambda: lane.fixture('datinfo InspNorm')['inv'].get('2') == '8xAMETHYST_SHARD'
         and '39' not in lane.fixture('datinfo InspNorm')['inv'],
         lane.name + ': offline /invsee edits flushed to .dat')
    dat_off_inv = lane.fixture('datinfo InspNorm')
    require(dat_off_inv['inv'].get('2') == '8xAMETHYST_SHARD'
            and '39' not in dat_off_inv['inv']
            and dat_off_inv['inv'].get('0') == '3xDIAMOND'
            and dat_off_inv['inv'].get('40') == '1xSHIELD'
            and dat_off_inv['bukkitValues'].get('allium:s24_proof') == 'allium-keep-s24'
            and dat_off_inv['bukkitValues'].get('inspgate:marker') == 'keep-me-s24',
            lane.name + ': offline /invsee preserves unrelated slots and BukkitValues PDC',
            {'inv': dat_off_inv['inv'], 'bukkitValues': dat_off_inv['bukkitValues']})

    # Offline Mutable /enderchest (.offline + bypass + .edit)
    lane.perm('InspStaff', 'allium.enderchest.offline', True)
    lane.fixture('setslot InspStaff 0 REDSTONE 12')
    off_ec = staff.cmd('enderchest InspNorm')
    off_ec_w = staff.open_window(off_ec)
    staff.wait_content(off_ec, off_ec_w['windowId'])
    # For 27-slot chest GUI, viewer hotbar[0] is rawSlot 54
    staff.click(54, 0, 0)
    staff.click(1, 0, 0)
    window(.3)
    staff.click(26, 0, 1)
    window(.4)
    staff.close_gui()
    wait(lambda: lane.fixture('datinfo InspNorm')['ec'].get('1') == '12xREDSTONE'
         and '26' not in lane.fixture('datinfo InspNorm')['ec'],
         lane.name + ': offline /enderchest edits flushed to .dat')
    dat_off_ec = lane.fixture('datinfo InspNorm')
    require(dat_off_ec['ec'].get('1') == '12xREDSTONE'
            and '26' not in dat_off_ec['ec']
            and dat_off_ec['bukkitValues'].get('allium:s24_proof') == 'allium-keep-s24'
            and dat_off_ec['bukkitValues'].get('inspgate:marker') == 'keep-me-s24',
            lane.name + ': offline /enderchest updates EnderItems and preserves BukkitValues PDC')

    # Corrupt .dat & newer DataVersion refusal
    lane.fixture('corruptdat InspNorm')
    staff.command('invsee InspNorm', 'could not be read safely')
    lane.fixture('restoredat InspNorm')

    lane.fixture('newerdat InspNorm')
    staff.command('invsee InspNorm', 'newer Minecraft version')
    lane.fixture('restoredat InspNorm')

    # CAS conflict detection when external writer touches <uuid>.dat while offline session open
    cas_open = staff.cmd('invsee InspNorm')
    cas_w = staff.open_window(cas_open)
    staff.wait_content(cas_open, cas_w['windowId'])
    lane.fixture('touchdat InspNorm')  # sets ec[25]=9xAPPLE externally on disk
    cas_click_start = len(staff.lines)
    staff.click(27, 0, 1)  # attempt click on slot 27 (hotbar 0 = 3xDIAMOND) -> triggers save -> CAS conflict
    staff.chat(cas_click_start, 'changed externally; inspection closed without saving')
    staff.wait_close(cas_click_start)
    dat_cas = lane.fixture('datinfo InspNorm')
    require(dat_cas['inv'].get('0') == '3xDIAMOND' and dat_cas['ec'].get('25') == '9xAPPLE',
            lane.name + ': offline CAS conflict aborts save cleanly without overwriting external .dat changes',
            {'dat': dat_cas})

    # Login fence: target logs in while offline session is open (plus live verification of offline edits on login)
    lf_open = staff.cmd('invsee InspNorm')
    lf_w = staff.open_window(lf_open)
    staff.wait_content(lf_open, lf_w['windowId'])
    lf_start = len(staff.lines)
    norm.connect()
    staff.chat(lf_start, 'joined the server; offline inspection closed')
    staff.wait_close(lf_start)

    live_after_login = lane.fixture('inv InspNorm')
    require(live_after_login['slots']['allInv'].get('2') == '8xAMETHYST_SHARD'
            and live_after_login['slots']['helmet'] == 'AIR'
            and live_after_login['slots']['ec0'] == '2xNETHERITE_INGOT'
            and live_after_login['slots']['ec25'] == '9xAPPLE'
            and live_after_login['slots']['allEc'].get('1') == '12xREDSTONE'
            and live_after_login['pdc'].get('allium:s24_proof') == 'allium-keep-s24'
            and live_after_login['pdc'].get('inspgate:marker') == 'keep-me-s24',
            lane.name + ': target login after offline edits observes all updated inv/enderchest slots and preserved PDC',
            {'live': live_after_login})

    # Prepare one more offline edit (put 2xGOLD_BLOCK in InspNorm offhand) and verify across FULL SERVER RESTART
    norm.disconnect()
    lane.fixture('setslot InspStaff 0 GOLD_BLOCK 2')
    rst_open = staff.cmd('invsee InspNorm')
    rst_w = staff.open_window(rst_open)
    staff.wait_content(rst_open, rst_w['windowId'])
    staff.click(72, 0, 0)
    staff.click(40, 0, 0)  # offhand is GUI slot 40 (currently 1xSHIELD; swap/replace or place)
    window(.4)
    staff.close_gui()
    wait(lambda: lane.fixture('datinfo InspNorm')['inv'].get('40') == '2xGOLD_BLOCK',
         lane.name + ': offline offhand edit flushed before server restart')

    # Verify SQL audit table directly before restart
    audits_db = lane.fixture('db')['audits']
    actions_seen = {a['action'] for a in audits_db}
    require({'INVSEE_OPEN', 'INVSEE_EDIT', 'INVSEE_ABORT', 'ENDERCHEST_OPEN', 'ENDERCHEST_EDIT'}.issubset(actions_seen),
            lane.name + ': SQL audit records INVSEE_OPEN/EDIT/ABORT and ENDERCHEST_OPEN/EDIT',
            {'actions': sorted(actions_seen)})
    require(all(len(a['detail']) <= 512 and '\n' not in a['detail'] and '\r' not in a['detail'] for a in audits_db),
            lane.name + ': SQL audit details bounded <=512 chars and sanitized')

    # ------------------------------------------------------------------
    # Scope 4: Full server restart persistence + SQLite direct check
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':restart-persistence')
    lane.stop()

    db_path = lane.path / 'plugins/Allium/allium.db'
    con = sqlite3.connect(str(db_path))
    rows = con.execute("SELECT action, actor_name, target_name, detail FROM allium_moderation_audit WHERE action LIKE 'INVSEE%' OR action LIKE 'ENDERCHEST%'").fetchall()
    con.close()
    require(len(rows) >= 8, lane.name + ': SQLite allium_moderation_audit persisted inspection rows across shutdown', {'count': len(rows)})

    lane.boot()
    norm2 = lane.bot('InspNorm')
    live_restarted = lane.fixture('inv InspNorm')
    require(live_restarted['slots']['offhand'] == '2xGOLD_BLOCK'
            and live_restarted['slots']['allEc'].get('1') == '12xREDSTONE'
            and live_restarted['pdc'].get('allium:s24_proof') == 'allium-keep-s24'
            and live_restarted['pdc'].get('inspgate:marker') == 'keep-me-s24',
            lane.name + ': offline edit and PDC survive full server restart',
            {'live': live_restarted})

    observations[lane.name] = {
        'auditsCount': len(rows),
        'actionsSeen': sorted(actions_seen),
        'finalLiveSlots': live_restarted['slots'],
        'finalPdc': live_restarted['pdc'],
    }
    lane.stop()
    assert_clean_logs(lane, RUN / (lane.name + '-server.log'))


def main():
    build()
    if A.suite == 'spigot':
        run_suite('spigot', 25800)
    else:
        run_suite('paper', 25801)


try:
    main()
except BaseException as exc:
    failures.append('UNHANDLED: ' + repr(exc))
    emit('ERROR ' + repr(exc))
    emit(traceback.format_exc())
    for lane in lanes:
        try:
            lane.stop()
        except Exception as stop_err:
            emit('CLEANUP ERROR ' + lane.name + ': ' + repr(stop_err))
finally:
    manifest = {
        'suite': A.suite,
        'alliumJar': str(A.allium_jar),
        'alliumSha256': A.sha256,
        'runDir': str(RUN),
        'durationSeconds': round(time.time() - started, 2),
        'sourceHashes': SOURCE_HASHES,
        'scopes': scopes,
        'checksPassed': sum(1 for c in checks if c['passed']),
        'checksTotal': len(checks),
        'failures': failures,
        'observations': observations,
        'checks': checks,
    }
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    emit(f'SUMMARY {A.suite}: {manifest["checksPassed"]}/{manifest["checksTotal"]} passed, {len(failures)} failures')
    if failures:
        sys.exit(1)
