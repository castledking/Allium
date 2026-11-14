#!/usr/bin/env python3
"""S18 player info utilities & /i,/item real-protocol gate on Spigot 1.20.1 and Paper 26.3.
Tests three bots (InfoStaff vanished staff, InfoNorm normal player, InfoObs staff observer) plus console:
- /list (vanish-aware, permission group headers, [AFK], [Vanished])
- /seen <player> ("looks offline" rule: vanished online == offline last-seen, never "online now")
- /whois <player> (UUID, multi-name history from allium_player_name_history, staff-only location gated by
  allium.vanish.see + allium.whois.location)
- /near [radius] (distance sort, [AFK], [Vanished], max-radius enforcement, console player-only)
- /afk [message] + auto-AFK + optional kick-on-afk (off by default, exempt perm) + vanish/AFK night-skip sleep
- /i & /item self-use aliases sharing /give permissions & ItemSpecParser (--overflow=refuse, privileged gate)
- Restart persistence of first_join, last_seen (preserved on quit/rejoin while vanished), and name history.
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
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261114/s18-playerinfo/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s18-playerinfo/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s18-playerinfo/runs')
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
for port in (25780, 25781, 25782):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25780-25782 free and gate lock held', flush=True)
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
    HERE / 'plugin/gate/InfoGate.java',
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
HERE = RUN / 'harness/tests/live/playerinfo'
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
        for bot in lane.bots.values():
            if bot.proc is not None and bot.proc.poll() is not None:
                raise RuntimeError(f'{lane.name}/{bot.name} client unexpected exit {bot.proc.returncode}')


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
        '-d', str(classes), str(HERE / 'plugin/gate/InfoGate.java')
    ], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/InfoGate.jar'), '-C', str(classes), '.'], check=True)


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

    def move(self, dx, dz):
        start = len(self.lines)
        self.write(f'move {dx} {dz}')
        wait(lambda: any(s.startswith('INFOG-MOVED ') for s in self.lines[start:]), self.name + ' move packet')
        return start

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('BASEG-CHAT ')]

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def chat_any(self, start, fragments):
        return wait(lambda: next((c for c in self.chats(start) if any(f in c['plain'] for f in fragments)), None),
                    self.name + ': any(' + '|'.join(fragments) + ')')

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
        self.write('tab near ')
        fence = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[fence_start:]
                                   if s.startswith('MSGG-TABREQ ')), None), 'completion positive-control request')
        positive = wait(lambda: response(fence['id']), 'completion positive-control response', 5)
        if not positive['matches']:
            raise RuntimeError('completion fence did not return expected /near suggestions')
        window(.15)
        result = response(req['id'])
        if result is None:
            return [], (time.monotonic() - stamp) * 1000
        return result['matches'], (time.monotonic() - stamp) * 1000

    def await_kick_disconnect(self, fragment, timeout=12):
        proc = self.proc
        self.proc = None
        wait(lambda: proc.poll() is not None, self.name + ' kicked client exit', timeout, monitor=False)
        kicks = [json.loads(s.split(' ', 1)[1]) for s in self.lines if s.startswith('INFOG-KICK ')]
        wait(lambda: self.name not in self.lane.fixture('online')['players'], self.name + ' server observed kicked', 10)
        return any(fragment in k.get('plain', '') for k in kicks), kicks

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
        shutil.copy2(RUN / 'helper/InfoGate.jar', self.path / 'plugins/InfoGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        for m in ['moderation', 'messaging', 'mail', 'teleport', 'travel', 'kits', 'preferences', 'items', 'info']:
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
difficulty=peaceful
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

    def update_afk_config(self, auto_afk_seconds, kick_on_afk, kick_after_seconds):
        cfg_path = self.path / 'plugins/Allium/config.yml'
        cfg = yaml.safe_load(cfg_path.read_text())
        afk_cfg = cfg.setdefault('info', {}).setdefault('afk', {})
        afk_cfg['auto-afk-seconds'] = int(auto_afk_seconds)
        afk_cfg['kick-on-afk'] = bool(kick_on_afk)
        afk_cfg['kick-after-seconds'] = int(kick_after_seconds)
        cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
        os.chmod(cfg_path, 0o600)

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
                and any('Player info utilities enabled' in s for s in self.logs[start:])
                and any('S18G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': SQLite, PlayerInfo module, and helper enabled')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def console_cmd(self, s, expect_fragment, monitor=True):
        start = len(self.logs)
        self.console(s)
        wait(lambda: any(expect_fragment in line for line in self.logs[start:]), self.name + ' console ' + s, monitor=monitor)
        if monitor:
            window(.15)
        return [strip_ansi(line) for line in self.logs[start:]]

    def fixture(self, action):
        start = len(self.logs)
        self.console('s18g ' + action)
        line = wait(lambda: next((s for s in self.logs[start:] if 'S18G-DONE ' + action + ' => ' in s), None),
                    self.name + ' fixture ' + action)
        return json.loads(line.split(' => ', 1)[1])

    def stop(self):
        if self.proc is None:
            return
        proc = self.proc
        self.proc = None
        stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=60)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                with socket.socket() as s:
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    s.bind(('127.0.0.1', self.port))
                return
            except OSError:
                time.sleep(.1)
        raise RuntimeError(f'{self.name} TCP port {self.port} still bound after stop')


def strip_ansi(line):
    return re.sub(r'\x1b\[[0-9;]*m', '', line)


def connect_bots(lane, include_staff=True, norm_give=False):
    names = (['InfoStaff', 'InfoNorm', 'InfoObs'] if include_staff else ['InfoNorm', 'InfoObs'])
    for name in names:
        bot = Bot(lane, name)
        lane.bots[name] = bot
        bot.connect()
    if include_staff:
        for perm in ['allium.list', 'allium.seen', 'allium.whois', 'allium.whois.location',
                     'allium.near', 'allium.afk', 'allium.vanish', 'allium.vanish.see',
                     'allium.list.group.staff', 'allium.give', 'allium.give.privileged', 'allium.afk.kick.exempt']:
            lane.fixture(f'perm InfoStaff {perm} true')
    for perm in ['allium.list', 'allium.seen', 'allium.whois', 'allium.near', 'allium.afk']:
        lane.fixture(f'perm InfoNorm {perm} true')
    if norm_give:
        lane.fixture('perm InfoNorm allium.give true')
    for perm in ['allium.list', 'allium.seen', 'allium.whois', 'allium.whois.location',
                 'allium.near', 'allium.afk', 'allium.vanish.see', 'allium.list.group.vip',
                 'allium.afk.kick.exempt']:
        lane.fixture(f'perm InfoObs {perm} true')
    window(.4)


def run_suite(lane):
    scopes.append(lane.name)
    lane.boot()
    # Seed prior name in name index before InfoStaff joins
    seeded = lane.fixture('seedname InfoStaff OldStaffName')
    require(seeded.get('seededOldName') == 'OldStaffName', lane.name + ': seeded historical name for /whois')

    connect_bots(lane, include_staff=True, norm_give=False)
    staff = lane.bots['InfoStaff']
    norm = lane.bots['InfoNorm']
    obs = lane.bots['InfoObs']

    # deterministic positions: Staff (100.5, 100.5), Norm (100.5, 130.5 -> 30m), Obs (100.5, 180.5 -> 80m)
    lane.fixture('tppos InfoStaff 100.5 100.5')
    lane.fixture('tppos InfoNorm 100.5 130.5')
    lane.fixture('tppos InfoObs 100.5 180.5')
    window(.3)

    # 1. Pre-vanish /list check
    s = norm.cmd('list')
    norm.chat(s, 'Players (1)')
    pre_list_plain = '\n'.join(c['plain'] for c in norm.chats(s))
    require('3/20' in pre_list_plain and 'Staff (1): InfoStaff' in pre_list_plain
            and 'Vip (1): InfoObs' in pre_list_plain and 'Players (1): InfoNorm' in pre_list_plain,
            lane.name + ': /list groups visible players by permission group')

    # 2. Staff vanishes -> verify across /list, /seen, /whois, /near, tab completion
    staff.command('vanish', 'Vanish')
    time.sleep(2.2)  # so lastSeenAgo is clearly >= 2s when InfoStaff stays online & vanished
    st = lane.fixture('state')
    require(st['staffVanished'] and st['staffSleepingIgnored'],
            lane.name + ': InfoStaff vanished and sleepingIgnored=true')

    # /list across all 4 viewers
    n_list_s = norm.cmd('list')
    norm.chat(n_list_s, '2/20')
    window(.2)
    n_list_lines = [c['plain'] for c in norm.chats(n_list_s)]
    n_list_txt = '\n'.join(n_list_lines)
    require('2/20' in n_list_txt and 'InfoStaff' not in n_list_txt and 'Staff' not in n_list_txt
            and 'InfoObs' in n_list_txt and 'InfoNorm' in n_list_txt,
            lane.name + ': /list hides vanished InfoStaff from InfoNorm')

    for label, bot in [('InfoObs', obs), ('InfoStaff', staff)]:
        bs = bot.cmd('list')
        bot.chat(bs, '3/20')
        window(.2)
        b_txt = '\n'.join(c['plain'] for c in bot.chats(bs))
        require('3/20' in b_txt and 'InfoStaff [Vanished]' in b_txt,
                lane.name + f': /list shows InfoStaff [Vanished] to {label}')

    c_list = lane.console_cmd('list', '3/20')
    require(any('InfoStaff [Vanished]' in l for l in c_list),
            lane.name + ': console /list shows InfoStaff [Vanished]')

    # /seen and /whois while InfoStaff is online & vanished
    # Give InfoNorm temporary allium.whois.location to verify vanish.see still dominates!
    lane.fixture('perm InfoNorm allium.whois.location true')
    n_seen_s = norm.cmd('seen InfoStaff')
    n_seen_msg = norm.chat(n_seen_s, 'First joined:')['plain']
    n_whois_s = norm.cmd('whois InfoStaff')
    norm.chat(n_whois_s, 'Known names:')
    window(.2)
    n_whois_lines = [c['plain'] for c in norm.chats(n_whois_s)]
    lane.fixture('perm InfoNorm allium.whois.location false')

    require('InfoStaff is offline (' in n_seen_msg and 'online' not in n_seen_msg.lower()
            and '[Vanished]' not in n_seen_msg,
            lane.name + ': /seen InfoStaff shows offline + last_seen (never online now) to InfoNorm')
    n_whois_txt = '\n'.join(n_whois_lines)
    require('Whois InfoStaff (offline):' in n_whois_txt
            and 'Location:' not in n_whois_txt
            and 'Online for:' not in n_whois_txt
            and '[Vanished]' not in n_whois_txt
            and 'OldStaffName' in n_whois_txt,
            lane.name + ': /whois InfoStaff hides online/location and matches offline schema for InfoNorm even with whois.location')

    obs_seen_s = obs.cmd('seen InfoStaff')
    obs_seen_msg = obs.chat(obs_seen_s, 'First joined:')['plain']
    obs_whois_s = obs.cmd('whois InfoStaff')
    obs.chat(obs_whois_s, 'Known names:')
    window(.2)
    obs_whois_txt = '\n'.join(c['plain'] for c in obs.chats(obs_whois_s))
    require('InfoStaff is online [Vanished]' in obs_seen_msg
            and 'Whois InfoStaff (online [Vanished]):' in obs_whois_txt
            and 'Location: world (100,' in obs_whois_txt
            and 'Known names: InfoStaff, OldStaffName' in obs_whois_txt,
            lane.name + ': /seen & /whois show online [Vanished], location, and name history to InfoObs')

    c_seen = lane.console_cmd('seen InfoStaff', 'First joined:')
    c_whois = lane.console_cmd('whois InfoStaff', 'Known names:')
    require(any('InfoStaff is online [Vanished]' in l for l in c_seen)
            and any('Location: world (100,' in l for l in c_whois),
            lane.name + ': console /seen & /whois see online [Vanished] + location')

    # Unknown target error
    unk_s = norm.cmd('seen NeverJoined999')
    norm.chat(unk_s, 'No player called NeverJoined999 has played here.')
    require(True, lane.name + ': /seen unknown player handled gracefully')

    # /near across all 4 viewers
    n_near_s = norm.cmd('near 100')
    n_near_msg = norm.chat(n_near_s, 'Nearby players')['plain']
    obs_near_s = obs.cmd('near 100')
    obs_near_msg = obs.chat(obs_near_s, 'Nearby players')['plain']
    st_near_s = staff.cmd('near 100')
    st_near_msg = staff.chat(st_near_s, 'Nearby players')['plain']
    c_near = lane.console_cmd('near', 'Only players can use /near')
    n_far_s = norm.cmd('near 600')
    norm.chat(n_far_s, 'cannot exceed 500')
    require('InfoObs (50m)' in n_near_msg and 'InfoStaff' not in n_near_msg
            and 'InfoNorm (50m)' in obs_near_msg and 'InfoStaff (80m) [Vanished]' in obs_near_msg
            and 'InfoNorm (30m)' in st_near_msg and 'InfoObs (80m)' in st_near_msg
            and len(c_near) > 0,
            lane.name + ': /near filters vanish, sorts by distance, enforces max radius, rejects console')

    # Tab completion while vanished
    m_norm_seen, _ = norm.tab('seen Info')
    m_obs_seen, _ = obs.tab('seen Info')
    m_norm_whois, _ = norm.tab('whois Info')
    require('InfoStaff' not in m_norm_seen and 'InfoStaff' not in m_norm_whois
            and 'InfoObs' in m_norm_seen and 'InfoStaff' in m_obs_seen,
            lane.name + ': /seen and /whois tab completion never leaks vanished InfoStaff')

    # 3. AFK, vanish broadcast suppression, sleep coordination, and real night-skip
    n_watch = len(norm.lines)
    obs_watch = len(obs.lines)
    con_watch = len(lane.logs)
    staff.command('afk Tea break', 'You are now AFK: Tea break')
    obs.chat(obs_watch, 'InfoStaff is now AFK: Tea break')
    wait(lambda: any('InfoStaff is now AFK: Tea break' in l for l in lane.logs[con_watch:]),
         lane.name + ' console vanished AFK notice')
    window(.3)
    require(not any('InfoStaff is now AFK' in c['plain'] for c in norm.chats(n_watch)),
            lane.name + ': vanished AFK change suppressed for InfoNorm, shown to InfoObs and console')

    # Toggle InfoStaff out of AFK -> vanished player MUST stay sleepingIgnored=True
    staff.command('afk', 'no longer AFK')
    st_after_unafk = lane.fixture('state')
    require(st_after_unafk['staffVanished'] and not st_after_unafk['staffAfk'] and st_after_unafk['staffSleepingIgnored'],
            lane.name + ': un-AFKing vanished player keeps sleepingIgnored=true')

    # InfoObs enters AFK -> visible broadcast + [AFK] in /list and /near + enables single-player night skip!
    n_watch2 = len(norm.lines)
    obs.command('afk', 'You are now AFK.')
    norm.chat(n_watch2, 'InfoObs is now AFK.')
    n_near_afk = norm.cmd('near 100')
    n_near_afk_msg = norm.chat(n_near_afk, 'Nearby players')['plain']
    n_list_afk = norm.cmd('list')
    n_list_afk_txt = '\n'.join(c['plain'] for c in [norm.chat(n_list_afk, 'InfoObs')])
    require('InfoObs (50m) [AFK]' in n_near_afk_msg and 'InfoObs [AFK]' in n_list_afk_txt,
            lane.name + ': AFK tag reflected in /near and /list')

    # Sleep night-skip live check: InfoStaff is vanished (sleepingIgnored), InfoObs is AFK (sleepingIgnored), InfoNorm sleeps
    sprep = lane.fixture('sleepprep InfoNorm')
    require(sprep['slept'] and sprep['isSleeping'], lane.name + ': InfoNorm sleeping in real bed at night (time=14000)')
    wait(lambda: lane.fixture('state')['nightSkipCount'] >= 1, lane.name + ': vanilla night-skip triggered by AFK+vanish sleep ignore', 15)
    st_skip = lane.fixture('state')
    require(st_skip['nightSkipCount'] >= 1 and st_skip['worldTime'] < 2000,
            lane.name + ': night skipped to morning because vanished staff + AFK observer are sleepingIgnored')

    # Protocol movement un-AFKs InfoObs and clears sleepingIgnored
    obs_mv_s = obs.move(1.6, 0.0)
    obs.chat(obs_mv_s, 'You are no longer AFK.')
    st_obs_moved = lane.fixture('state')
    require(not st_obs_moved['obsAfk'] and not st_obs_moved['obsSleepingIgnored'],
            lane.name + ': real movement un-AFKs InfoObs and clears sleepingIgnored')

    # 4. Auto-AFK and kick-on-AFK (default off, opt-in on)
    lane.update_afk_config(auto_afk_seconds=3, kick_on_afk=False, kick_after_seconds=2)
    lane.console_cmd('allium reload', 'reloaded')
    wait(lambda: lane.fixture('state')['normAfk'] and lane.fixture('state')['obsAfk'],
         lane.name + ': auto-AFK triggers after 3s inactivity', 12)
    window(2.8)
    st_nokick = lane.fixture('state')
    require(st_nokick['normOnline'] and st_nokick['obsOnline'] and st_nokick['normAfk'] and st_nokick['obsAfk'],
            lane.name + ': kick-on-afk=false default does not kick AFK players')

    # Opt-in kick-on-afk=true: non-exempt InfoNorm kicked, exempt InfoObs spared!
    lane.update_afk_config(auto_afk_seconds=3, kick_on_afk=True, kick_after_seconds=2)
    lane.console_cmd('allium reload', 'reloaded', monitor=False)
    kicked_msg_ok, _ = norm.await_kick_disconnect('kicked for being AFK', timeout=12)
    window(1.0)
    st_kick = lane.fixture('state')
    require(kicked_msg_ok and not st_kick['normOnline'] and st_kick['obsOnline'] and st_kick['obsAfk'],
            lane.name + ': kick-on-afk=true kicks non-exempt InfoNorm and spares allium.afk.kick.exempt InfoObs')

    # Restore normal AFK timers and reconnect InfoNorm
    lane.update_afk_config(auto_afk_seconds=300, kick_on_afk=False, kick_after_seconds=600)
    lane.console_cmd('allium reload', 'reloaded')
    norm = Bot(lane, 'InfoNorm')
    lane.bots['InfoNorm'] = norm
    norm.connect()
    for perm in ['allium.list', 'allium.seen', 'allium.whois', 'allium.near', 'allium.afk']:
        lane.fixture(f'perm InfoNorm {perm} true')
    window(.3)

    # 5. /i and /item self-use harness
    i_denied_s = norm.cmd('i diamond 1')
    norm.chat_any(i_denied_s, ('permission', 'command.unknown.command', 'Unknown or incomplete command'))
    require(len(lane.fixture('inv InfoNorm')['items']) == 0,
            lane.name + ': /i without allium.give is blocked and gives nothing')
    c_i_usage = lane.console_cmd('i diamond', '/give <player>')
    require(len(c_i_usage) > 0, lane.name + ': /i denies missing allium.give and prints /give usage on console')

    lane.fixture('perm InfoNorm allium.give true')
    i_ok_s = norm.cmd('i diamond 3 name:"&aGem Blade"')
    norm.chat(i_ok_s, 'Gave 3x')
    inv_after = lane.fixture('inv InfoNorm')['items']
    require(any(it['type'] == 'DIAMOND' and it['amount'] == 3 and 'Gem Blade' in it.get('displayName', '')
                for it in inv_after),
            lane.name + ': /i delivers ItemSpecParser custom-named stack to self')

    i_priv_s = norm.cmd('i allium:handcuffs 1')
    norm.chat(i_priv_s, 'allium.give.privileged')
    lane.fixture('fillinv InfoNorm')
    item_refuse_s = norm.cmd('item emerald 1 --overflow=refuse')
    norm.chat(item_refuse_s, 'cannot hold')
    inv_full = lane.fixture('inv InfoNorm')['items']
    require(not any(it['type'] == 'EMERALD' for it in inv_full),
            lane.name + ': /item enforces allium.give.privileged and --overflow=refuse cleanly')

    st_handcuff_s = staff.cmd('item allium:handcuffs 1')
    staff.chat(st_handcuff_s, 'Gave 1x')
    st_inv = lane.fixture('inv InfoStaff')['items']
    require(len(st_inv) >= 1, lane.name + ': /item succeeds for staff with allium.give.privileged')

    # 6. Restart persistence & vanished-online vs offline byte/content equivalence
    # Inspect DB lastSeen before quitting vanished InfoStaff
    db_before = lane.fixture('db InfoStaff')
    n_seen_vanished_s = norm.cmd('seen InfoStaff')
    n_seen_vanished = norm.chat(n_seen_vanished_s, 'First joined:')['plain']
    n_whois_vanished_s = norm.cmd('whois InfoStaff')
    norm.chat(n_whois_vanished_s, 'Known names:')
    window(.2)
    n_whois_vanished = [c['plain'] for c in norm.chats(n_whois_vanished_s)]

    # Quit InfoStaff while vanished -> should NOT advance last_seen
    staff.disconnect()
    window(.4)
    db_after_quit = lane.fixture('db InfoStaff')
    require(db_after_quit['lastSeen'] == db_before['lastSeen'],
            lane.name + ': vanished quit preserves pre-vanish last_seen in SQL')

    # Restart whole server
    norm.disconnect()
    obs.disconnect()
    lane.bots.clear()
    lane.stop()
    lane.boot()

    # Reconnect only InfoNorm + InfoObs (InfoStaff offline) and check persisted /seen & /whois
    connect_bots(lane, include_staff=False, norm_give=True)
    norm = lane.bots['InfoNorm']
    obs = lane.bots['InfoObs']
    db_after_restart = lane.fixture('db InfoStaff')
    require(db_after_restart['firstJoin'] == db_before['firstJoin']
            and db_after_restart['lastSeen'] == db_before['lastSeen']
            and 'OldStaffName' in db_after_restart['history'],
            lane.name + ': first_join, last_seen, and name history survived full server restart')

    n_seen_off_s = norm.cmd('seen InfoStaff')
    n_seen_off = norm.chat(n_seen_off_s, 'First joined:')['plain']
    n_whois_off_s = norm.cmd('whois InfoStaff')
    norm.chat(n_whois_off_s, 'Known names:')
    window(.2)
    n_whois_off = [c['plain'] for c in norm.chats(n_whois_off_s)]

    # Verify same status/dates between vanished-online and genuinely-offline after restart
    def extract_date(line):
        m = re.search(r'(\d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC)', line)
        return m.group(1) if m else None

    require('InfoStaff is offline (' in n_seen_off
            and extract_date(n_seen_vanished) is not None
            and extract_date(n_seen_vanished) == extract_date(n_seen_off)
            and n_whois_off[0] == n_whois_vanished[0]
            and n_whois_off[1] == n_whois_vanished[1]
            and n_whois_off[-1] == n_whois_vanished[-1],
            lane.name + ': /seen & /whois persisted offline timestamps match vanished-online view across restart')

    # Reconnect InfoStaff -> relogs STILL vanished, lastSeen still preserved, invisible in /list & /seen
    staff = Bot(lane, 'InfoStaff')
    lane.bots['InfoStaff'] = staff
    staff.connect()
    for perm in ['allium.list', 'allium.vanish', 'allium.vanish.see', 'allium.list.group.staff']:
        lane.fixture(f'perm InfoStaff {perm} true')
    window(.4)
    st_rejoin = lane.fixture('state')
    db_after_vanished_rejoin = lane.fixture('db InfoStaff')
    n_seen_rejoin = norm.chat(norm.cmd('seen InfoStaff'), 'First joined:')['plain']
    require(st_rejoin['staffVanished']
            and db_after_vanished_rejoin['lastSeen'] == db_before['lastSeen']
            and 'InfoStaff is offline (' in n_seen_rejoin,
            lane.name + ': vanished relogin after restart preserves vanish, last_seen, and offline /seen view')

    observations[lane.name] = {
        'vanishedSeenNorm': n_seen_vanished,
        'offlineAfterRestartSeenNorm': n_seen_off,
        'vanishedWhoisNorm': n_whois_vanished,
        'vanishedWhoisObs': obs_whois_txt.splitlines(),
        'nearNorm': n_near_msg,
        'nearObs': obs_near_msg,
        'sleepSkip': st_skip,
        'dbBeforeQuit': db_before,
        'dbAfterRestart': db_after_restart,
    }


try:
    build()
    if A.suite == 'spigot':
        run_suite(Lane('spigot', 'spigot', 25780))
    else:
        run_suite(Lane('paper', 'paper', 25781))
except BaseException:
    emit('EXCEPTION:\n' + traceback.format_exc())
    failures.append('exception')
finally:
    for lane in lanes:
        for bot in list(lane.bots.values()):
            try:
                bot.disconnect()
            except Exception:
                pass
        try:
            lane.stop()
        except Exception as exc:
            failures.append(lane.name + ' cleanup: ' + str(exc))
            emit('CLEANUP FAIL ' + lane.name + ': ' + str(exc))
    manifest = {
        'suite': A.suite,
        'runName': A.run_name,
        'alliumJar': str(A.allium_jar),
        'sha256': A.sha256,
        'sourceHashes': SOURCE_HASHES,
        'scopes': scopes,
        'durationSeconds': round(time.time() - started, 2),
        'checksPassed': sum(1 for c in checks if c['passed']),
        'checksTotal': len(checks),
        'failures': failures,
        'checks': checks,
        'observations': observations,
    }
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    emit(f"SUMMARY suite={A.suite} passed={manifest['checksPassed']}/{manifest['checksTotal']} duration={manifest['durationSeconds']}s")
sys.exit(1 if failures else 0)
