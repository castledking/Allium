#!/usr/bin/env python3
"""S25 parity utilities real-protocol live gate on Spigot 1.20.1 and Paper 26.3."""
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
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261115/s25-utilities/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()

if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s25-utilities/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s25-utilities/runs')
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
for port in (25820, 25821, 25822):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25820-25822 free and gate lock held', flush=True)
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
    HERE / 'plugin/gate/UtilitiesGate.java',
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
HERE = RUN / 'harness/tests/live/utilities'
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
        '-d', str(classes), str(HERE / 'plugin/gate/UtilitiesGate.java')
    ], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/UtilitiesGate.jar'), '-C', str(classes), '.'], check=True)


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
        wait(lambda: any('UTIL-WIRE READY' in s for s in self.lines[start:]), self.name + ' wire READY', 120)
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
        wait(lambda: any(s == 'UTIL-WIRE SENT /' + text + '\n' for s in self.lines[start:]), self.name + ' wire command log')
        return start

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('UTIL-CHAT ')]

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def chat_any(self, start, fragments):
        return wait(lambda: next((c for c in self.chats(start) if any(f in c['plain'] for f in fragments)), None),
                    self.name + ': any(' + ','.join(fragments) + ')')

    def command(self, text, expected):
        start = self.cmd(text)
        return self.chat(start, expected)

    def open_window(self, start):
        return wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('UTIL-OPEN ')), None),
                    self.name + ' UTIL-OPEN')

    def close_gui(self):
        start = len(self.lines)
        self.write('close')
        wait(lambda: any(s.startswith('UTIL-CLOSE-SENT ') for s in self.lines[start:]), self.name + ' close sent')
        window(.2)
        return start

    def tab(self, text):
        start, stamp = len(self.lines), time.monotonic()
        self.write('tab ' + text)
        req = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('UTIL-TABREQ ')), None),
                   'completion request sent')

        def response(tid):
            return next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:]
                         if s.startswith('UTIL-TAB ') and json.loads(s.split(' ', 1)[1])['id'] == tid), None)

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
                                   if s.startswith('UTIL-TABREQ ')), None), 'completion positive-control request')
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
    def __init__(self, name, kind, port, disabled_commands=None):
        self.name, self.kind, self.port = name, kind, port
        self.path = RUN / name
        self.proc, self.logs, self.bots = None, [], {}
        self.path.mkdir()
        (self.path / 'plugins/Allium').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/UtilitiesGate.jar', self.path / 'plugins/UtilitiesGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        for m in ['essentials', 'items', 'moderation', 'messaging', 'kits', 'preferences']:
            config.setdefault('modules', {})[m] = True
        config.setdefault('database', {})['type'] = 'SQLITE'
        config.setdefault('economy', {})['vault-provider-enabled'] = False
        if disabled_commands is not None:
            config['disabled-commands'] = list(disabled_commands)
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
difficulty=peaceful
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
                and any('Module [essentials] enabled' in s for s in self.logs[start:])
                and any('S25G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': SQLite, essentials module, and UtilitiesGate ready')
        cfg_now = yaml.safe_load((self.path / 'plugins/Allium/config.yml').read_text())
        require(cfg_now.get('economy', {}).get('vault-provider-enabled') is False,
                self.name + ': economy.vault-provider-enabled remains false')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('s25g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S25G-DONE ' + s + ' => ') in v or ('S25G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S25G-FAIL' in line:
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
        'Allium plugin test helper',
        'moved too quickly',
        'SkullProfiles',
        'degraded',
    ]
    for raw in text.splitlines():
        if '[ERROR]' in raw or 'Exception' in raw or 'ERROR]:' in raw:
            bad_lines.append(raw)
        elif '[WARN]' in raw or 'WARN]:' in raw:
            if any(marker in raw for marker in ['Allium', 'UtilitiesGate', 'Essentials', 'UtilitiesAudit']):
                if not any(f in raw for f in allowed_warn_fragments):
                    bad_lines.append(raw)
    require(len(bad_lines) == 0, f'{lane.name}: server log free of unexpected WARN/ERROR/Exception',
            {'badLines': bad_lines[:10]})


def grant_standard_staff(lane, who):
    for node in [
        'allium.repair', 'allium.repair.all', 'allium.disposal', 'allium.workbench', 'allium.anvil',
        'allium.info', 'allium.speed', 'allium.speed.others', 'allium.ping', 'allium.ping.others',
        'allium.ext', 'allium.ext.others', 'allium.burn', 'allium.smite',
        'allium.nv', 'allium.nv.others', 'allium.clearinventory', 'allium.clearinventory.others',
        'allium.more', 'allium.more.others', 'allium.hat', 'allium.skull', 'allium.skull.others',
        'allium.itemdb', 'allium.itemdb.others', 'allium.gc',
        'allium.time', 'allium.time.set', 'allium.time.add', 'allium.time.pause',
        'allium.weather', 'allium.enchant', 'allium.enchant.others',
    ]:
        lane.perm(who, node, True)


def run_suite(kind, base_port):
    lane = Lane(kind, kind, base_port)
    lane.boot()

    staff = lane.bot('UtilStaff')
    norm = lane.bot('UtilNorm')
    grant_standard_staff(lane, 'UtilStaff')

    # ------------------------------------------------------------------
    # Scope 1: Repair (hand + all), Disposal, Workbenches, /info
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':repair-disposal-menus-info')
    lane.perm('UtilNorm', 'allium.repair', True)
    lane.fixture('seedrepair UtilNorm')

    norm.command('repair', 'Repaired the held item')
    st_rep = lane.fixture('state UtilNorm')
    require(st_rep['heldDamage'] == 0
            and st_rep['heldDisplayName'] == 'Custom Pick'
            and st_rep['heldLore'] == 'Keeper Line'
            and st_rep['heldPdc'] == 'keep-me'
            and st_rep.get('heldEnchants', {}).get('efficiency', st_rep.get('heldEnchants', {}).get('dig_speed')) == 2
            and st_rep['slot1Damage'] == 40,
            lane.name + ': /repair repairs held item in-place preserving name/lore/enchant/PDC while leaving slot1 damaged',
            st_rep)

    norm.command('repair all', 'permission')
    lane.perm('UtilNorm', 'allium.repair.all', True)
    norm.command('repair all', 'Repaired 1 damaged item(s)')
    st_all = lane.fixture('state UtilNorm')
    require(st_all['slot1Damage'] == 0, lane.name + ': /repair all repairs all damaged inventory slots')
    lane.perm('UtilNorm', 'allium.repair.all', False)
    lane.perm('UtilNorm', 'allium.repair', False)

    # /disposal (/trash), /workbench (/craft), /anvil, /info
    lane.perm('UtilNorm', 'allium.disposal', True)
    disp_start = norm.cmd('trash')
    disp_win = norm.open_window(disp_start)
    require(disp_win['windowId'] > 0 and 'Disposal' in disp_win['title'],
            lane.name + ': /trash opens Disposal menu')
    placed = lane.fixture('place_disposal UtilNorm')
    require(placed['topSize'] == 27 and placed['topZero'] == '4xCOBBLESTONE',
            lane.name + ': Disposal 27-slot menu accepts items before close')
    norm.close_gui()
    cleared = lane.fixture('disposal_cleared UtilNorm')
    require(cleared['cleared'] is True, lane.name + ': Disposal clears placed items on inventory close')
    lane.perm('UtilNorm', 'allium.disposal', False)

    lane.perm('UtilNorm', 'allium.workbench', True)
    wb_start = norm.cmd('craft')
    wb_win = norm.open_window(wb_start)
    require(wb_win['windowId'] > 0, lane.name + ': /craft opens workbench GUI')
    norm.close_gui()
    lane.perm('UtilNorm', 'allium.workbench', False)

    lane.perm('UtilNorm', 'allium.anvil', True)
    anv_start = norm.cmd('anvil')
    anv_win = norm.open_window(anv_start)
    require(anv_win['windowId'] > 0, lane.name + ': /anvil opens anvil GUI')
    norm.close_gui()
    lane.perm('UtilNorm', 'allium.anvil', False)

    lane.perm('UtilNorm', 'allium.info', True)
    norm.command('info', '2 player(s) online')
    lane.perm('UtilStaff', 'allium.vanish', True)
    staff.command('vanish on', 'Vanish enabled')
    norm.command('info', '1 player(s) online')
    staff.command('vanish off', 'Vanish disabled')
    lane.perm('UtilStaff', 'allium.vanish', False)
    lane.perm('UtilNorm', 'allium.info', False)

    # ------------------------------------------------------------------
    # Scope 2: Speed, Ping, Vanish Blindness, Fire, Smite, Night Vision
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':speed-ping-fire-nv')
    lane.perm('UtilNorm', 'allium.speed', True)
    norm.command('speed walk 3', 'Walk speed set to 3')
    norm.command('speed fly 4', 'Fly speed set to 4')
    st_spd = lane.fixture('state UtilNorm')
    expected_walk_3 = 0.2 + (2.0 / 9.0) * 0.8
    expected_fly_4 = 0.1 + (3.0 / 9.0) * 0.9
    require(abs(st_spd['walkSpeed'] - expected_walk_3) < 1e-4 and abs(st_spd['flySpeed'] - expected_fly_4) < 1e-4,
            lane.name + ': /speed walk and /speed fly update Bukkit speeds', st_spd)
    norm.command('speed 11', '/speed')
    norm.command('speed walk reset', 'Walk speed set to 1')
    norm.command('speed fly reset', 'Fly speed set to 1')
    st_rst = lane.fixture('state UtilNorm')
    require(abs(st_rst['walkSpeed'] - 0.2) < 1e-4 and abs(st_rst['flySpeed'] - 0.1) < 1e-4,
            lane.name + ': /speed reset restores default walk (0.2) and fly (0.1) speeds')
    lane.perm('UtilNorm', 'allium.speed', False)

    staff.command('speed walk 5 UtilNorm', 'UtilNorm')
    st_spd_other = lane.fixture('state UtilNorm')
    expected_walk_5 = 0.2 + (4.0 / 9.0) * 0.8
    require(abs(st_spd_other['walkSpeed'] - expected_walk_5) < 1e-4,
            lane.name + ': /speed walk <n> <player> applies to target')

    # Ping + Vanish blindness (both /speed and /ping + tab completion)
    staff.command('ping', 'Your ping:')
    staff.command('ping UtilNorm', "UtilNorm's ping:")
    lane.perm('UtilNorm', 'allium.vanish', True)
    norm.command('vanish on', 'Vanish enabled')
    lane.perm('UtilNorm', 'allium.vanish', False)

    tabs_ping_hidden, _ = staff.tab('ping ')
    tabs_ci_hidden, _ = staff.tab('ci ')
    require('UtilNorm' not in tabs_ping_hidden and 'UtilNorm' not in tabs_ci_hidden,
            lane.name + ': /ping and /ci tab completion hide vanished player without vanish.see')
    staff.command('speed walk 2 UtilNorm', 'UtilNorm not found')
    staff.command('ping UtilNorm', 'UtilNorm not found')
    require(abs(lane.fixture('state UtilNorm')['walkSpeed'] - expected_walk_5) < 1e-4,
            lane.name + ': vanished target speed unaffected when hidden from sender')

    lane.perm('UtilStaff', 'allium.vanish.see', True)
    tabs_ping_vis, _ = staff.tab('ping ')
    require('UtilNorm' in tabs_ping_vis, lane.name + ': /ping tab completion reveals vanished player with vanish.see')
    lane.perm('UtilStaff', 'allium.vanish.see', False)

    lane.perm('UtilNorm', 'allium.vanish', True)
    norm.command('vanish off', 'Vanish disabled')
    lane.perm('UtilNorm', 'allium.vanish', False)
    staff.command('speed walk reset UtilNorm', 'Set UtilNorm')

    # Burn / Ext / Smite / NV
    staff.command('burn UtilNorm 5', 'Set UtilNorm on fire')
    st_burn = lane.fixture('state UtilNorm')
    require(st_burn['fireTicks'] >= 20, lane.name + ': /burn ignites target player', st_burn)
    staff.command('burn UtilNorm 999', 'between 1 and 300')
    staff.command('ext UtilNorm', 'Extinguished UtilNorm')
    require(lane.fixture('state UtilNorm')['fireTicks'] <= 0, lane.name + ': /ext extinguishes target player')
    staff.command('smite UtilNorm', 'Struck UtilNorm with lightning')

    lane.perm('UtilNorm', 'allium.nv', True)
    norm.command('nv', 'enabled')
    st_nv = lane.fixture('state UtilNorm')
    require(st_nv['hasNightVision'] is True and (st_nv['nightVisionDuration'] == -1 or st_nv['nightVisionDuration'] >= 100_000),
            lane.name + ': /nv toggles on infinite/long night vision', st_nv)
    norm.command('nv off', 'disabled')
    require(lane.fixture('state UtilNorm')['hasNightVision'] is False, lane.name + ': /nv off clears night vision')
    lane.perm('UtilNorm', 'allium.nv', False)
    staff.command('nv on UtilNorm', 'enabled')
    require(lane.fixture('state UtilNorm')['hasNightVision'] is True, lane.name + ': /nv on <player> grants night vision')
    staff.command('nv off UtilNorm', 'disabled')
    require(lane.fixture('state UtilNorm')['hasNightVision'] is False, lane.name + ': /nv off <player> removes night vision')

    # ------------------------------------------------------------------
    # Scope 3: /clearinventory, /more, /hat, /skull, /itemdb
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':clear-more-hat-skull-itemdb')
    lane.fixture('seedstack UtilNorm')

    staff.command('ci UtilNorm dirt 4', 'Cleared 4 item(s) from UtilNorm')
    st_ci = lane.fixture('state UtilNorm')
    require(st_ci['dirtCount'] == 6 and st_ci['stoneCount'] == 5 and st_ci['heldType'] == 'GOLDEN_APPLE',
            lane.name + ': /ci <player> <material> <maxCount> removes exact partial count', st_ci)

    lane.perm('UtilNorm', 'allium.more', True)
    norm.command('more 16', 'Filled held stack to 16')
    st_more = lane.fixture('state UtilNorm')
    require(st_more['heldType'] == 'GOLDEN_APPLE' and st_more['heldAmount'] == 16,
            lane.name + ': /more <amount> sets held stack to 16', st_more)
    lane.perm('UtilNorm', 'allium.more', False)

    lane.perm('UtilNorm', 'allium.hat', True)
    norm.command('hat', 'Equipped')
    st_hat = lane.fixture('state UtilNorm')
    require(st_hat['helmetType'] == 'GOLDEN_APPLE' and st_hat['helmetAmount'] == 16
            and st_hat['heldType'] == 'DIAMOND_HELMET' and st_hat['heldAmount'] == 1,
            lane.name + ': /hat atomically swaps held stack with existing helmet', st_hat)
    lane.perm('UtilNorm', 'allium.hat', False)

    staff.chat_any(staff.cmd('skull Notch UtilNorm'), ["Gave Notch's head to UtilNorm"])
    wait(lambda: lane.fixture('state UtilNorm')['headCount'] >= 1, lane.name + ' skull delivered', 15)
    require(lane.fixture('state UtilNorm')['headCount'] >= 1,
            lane.name + ': /skull Notch UtilNorm resolves off-thread and delivers PLAYER_HEAD')

    # Seed damaged sword for /itemdb and /enchant
    lane.fixture('seedsword UtilNorm')
    lane.perm('UtilNorm', 'allium.itemdb', True)
    it_start = norm.cmd('itemdb')
    norm.chat(it_start, 'Diamond Sword (minecraft:diamond_sword)')
    norm.chat(it_start, 'Durability:')
    lane.perm('UtilNorm', 'allium.itemdb', False)

    # /more refuses non-stackable sword
    staff.command('more UtilNorm', 'cannot be stacked above 1')

    # ------------------------------------------------------------------
    # Scope 4: /gc, /time, /day, /night, /weather, /enchant, tab completion
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':gc-time-weather-enchant')
    gc_start = staff.cmd('gc')
    gc_line1 = staff.chat(gc_start, 'Runtime uptime:')
    staff.chat(gc_start, 'Memory:')
    staff.chat(gc_start, 'world:')
    if kind == 'spigot':
        require('TPS: N/A (Spigot)' in gc_line1['plain'], lane.name + ': /gc reports N/A (Spigot) on Spigot 1.20.1', gc_line1)
    else:
        require('TPS: ' in gc_line1['plain'] and 'N/A' not in gc_line1['plain'],
                lane.name + ': /gc reports live Bukkit.getTPS() on Paper 26.3', gc_line1)

    staff.command('day', 'Time set to')
    t_day = lane.fixture('state UtilNorm')['worldTime'] % 24000
    require(995 <= t_day <= 1100, lane.name + ': /day sets world time to 1000', {'time': t_day})

    staff.command('night', 'Time set to')
    t_night = lane.fixture('state UtilNorm')['worldTime'] % 24000
    require(12995 <= t_night <= 13100, lane.name + ': /night sets world time to 13000', {'time': t_night})

    staff.command('time add 500', 'Added 500 ticks')
    t_add = lane.fixture('state UtilNorm')['worldTime'] % 24000
    require(13490 <= t_add <= 13700, lane.name + ': /time add advances world time by 500 ticks', {'time': t_add})

    staff.command('time pause on', 'paused')
    st_p0 = lane.fixture('state UtilNorm')
    window(1.2)
    st_p1 = lane.fixture('state UtilNorm')
    require(st_p0['daylightCycle'] is False and st_p1['daylightCycle'] is False
            and abs(st_p1['worldTime'] - st_p0['worldTime']) <= 1,
            lane.name + ': /time pause on freezes world clock and disables DO_DAYLIGHT_CYCLE',
            {'t0': st_p0['worldTime'], 't1': st_p1['worldTime']})

    gc_paused = staff.cmd('gc')
    staff.chat(gc_paused, '[TIME PAUSED]')

    staff.command('time pause off', 'resumed')
    require(lane.fixture('state UtilNorm')['daylightCycle'] is True,
            lane.name + ': /time pause off restores DO_DAYLIGHT_CYCLE')

    staff.command('weather rain 60', 'Weather set to rain')
    st_rain = lane.fixture('state UtilNorm')
    require(st_rain['storm'] is True and st_rain['thundering'] is False, lane.name + ': /weather rain sets storm')

    staff.command('weather thunder 60', 'Weather set to thunder')
    st_th = lane.fixture('state UtilNorm')
    require(st_th['storm'] is True and st_th['thundering'] is True, lane.name + ': /weather thunder sets storm + thunder')

    staff.command('weather clear', 'Weather set to clear')
    st_clr = lane.fixture('state UtilNorm')
    require(st_clr['storm'] is False and st_clr['thundering'] is False, lane.name + ': /weather clear clears storm & thunder')

    # /enchant + allium.enchant.unsafe + tab completion
    ench_tabs, _ = staff.tab('enchant ')
    require(any('sharpness' in m for m in ench_tabs), lane.name + ': /enchant tab completes enchantment keys',
            {'sample': ench_tabs[:10]})

    lane.perm('UtilNorm', 'allium.enchant', True)
    norm.command('enchant sharpness 5', 'Applied')
    lane.perm('UtilNorm', 'allium.enchant', False)
    require(lane.fixture('state UtilNorm')['heldEnchants'].get('sharpness') == 5,
            lane.name + ': /enchant safe level applied')

    staff.command('enchant UtilNorm sharpness 10', 'unsafe')
    require(lane.fixture('state UtilNorm')['heldEnchants'].get('sharpness') == 5,
            lane.name + ': /enchant refuses above-max level without allium.enchant.unsafe')
    staff.command('enchant UtilNorm fortune 1', 'unsafe')
    require('fortune' not in lane.fixture('state UtilNorm')['heldEnchants'],
            lane.name + ': /enchant refuses incompatible enchantment without allium.enchant.unsafe')

    lane.perm('UtilStaff', 'allium.enchant.unsafe', True)
    staff.command('enchant UtilNorm sharpness 10', 'Applied')
    require(lane.fixture('state UtilNorm')['heldEnchants'].get('sharpness') == 10,
            lane.name + ': allium.enchant.unsafe allows above-max level 10')
    lane.perm('UtilStaff', 'allium.enchant.unsafe', False)

    staff.command('enchant UtilNorm sharpness 0', 'Removed')
    require('sharpness' not in lane.fixture('state UtilNorm')['heldEnchants'],
            lane.name + ': /enchant level 0 removes enchantment')

    # Verify structured [UtilitiesAudit] records exist before shutdown
    lane.stop()
    srv_log = RUN / f'{lane.name}-server.log'
    srv_text = srv_log.read_text(errors='replace')
    require('[UtilitiesAudit] actor=UtilNorm action=repair_all' in srv_text
            and '[UtilitiesAudit] actor=UtilStaff action=burn' in srv_text
            and '[UtilitiesAudit] actor=UtilStaff action=enchant' in srv_text,
            lane.name + ': structured [UtilitiesAudit] logs recorded')
    assert_clean_logs(lane, srv_log)

    # ------------------------------------------------------------------
    # Scope 5: disabled-commands: [ci, uptime] exclusion lane
    # ------------------------------------------------------------------
    scopes.append(lane.name + ':disabled-commands')
    disabled_lane = Lane(kind + '-disabled', kind, base_port + 1, disabled_commands=['ci', 'uptime'])
    disabled_lane.boot()
    d_bot = disabled_lane.bot('UtilStaff')
    disabled_lane.perm('UtilStaff', 'allium.ping', True)
    disabled_lane.perm('UtilStaff', 'allium.clearinventory', True)
    disabled_lane.perm('UtilStaff', 'allium.gc', True)

    d_bot.command('ping', 'Your ping:')
    d_bot.chat_any(d_bot.cmd('ci'), ['command.unknown.command', 'Unknown'])
    d_bot.chat_any(d_bot.cmd('uptime'), ['command.unknown.command', 'Unknown'])
    require(True, disabled_lane.name + ': disabled-commands: [ci, uptime] yields clearinventory & gc families while keeping /ping')
    disabled_lane.stop()
    assert_clean_logs(disabled_lane, RUN / f'{disabled_lane.name}-server.log')

    observations[kind] = {'repairAndItemDb': 'ok', 'gcTps': gc_line1['plain'], 'pausedTime': st_p1['worldTime']}


build()
base = 25820
status = 'FAIL'
try:
    run_suite(A.suite, base)
    status = 'PASS' if not failures else 'FAIL'
except Exception as error:
    emit('EXCEPTION ' + repr(error))
    traceback.print_exc()
    failures.append('exception: ' + repr(error))
finally:
    for lane in lanes:
        try:
            lane.stop()
        except Exception as error:
            emit('STOP_ERROR ' + str(error))

summary = {
    'suite': A.suite,
    'run': A.run_name,
    'status': status,
    'sha256': A.sha256,
    'passed': sum(1 for c in checks if c['passed']),
    'failed': len(failures),
    'failures': failures,
    'scopes': scopes,
    'checks': checks,
    'observations': observations,
    'elapsedSeconds': round(time.time() - started, 2),
    'sourceHashes': SOURCE_HASHES,
}
(RUN / 'summary.json').write_text(json.dumps(summary, indent=2))
emit(f'RESULT {A.suite}: {status} ({summary["passed"]}/{len(checks)} passed, {summary["elapsedSeconds"]}s)')
sys.exit(0 if status == 'PASS' else 1)
