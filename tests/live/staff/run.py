#!/usr/bin/env python3
"""Staff tooling live gate: real protocol clients on Spigot 1.20.1 and Paper 26.3 (SQLite).

Covers economy completions, the staff-chat ">" redirect, per-gamemode permissions, the creative
manager (separate inventories across restarts, force-gamemode restore, container restriction and
audits), /invsee dead-target and offline-edit messages and slot icons, numbered mail, /whois extras,
alt detection with /seen and /altexempt, /restore (panels, edits, owner and queued offline
restores) and paid repair signs (success, refusal, and halts after the hold and after the save).
Owned loopback ports 25860-25862 and disposable run directories only.
"""
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import zipfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['spigot', 'paper'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--only', default='', help='comma-separated case names (debugging)')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261117/staff/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()

if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/staff/runs', str(A.run_root.resolve())):
    P.error('require a disposable /mnt/storage/.allium-validation/YYYYMMDD/staff/runs root')
A.allium_jar = A.allium_jar.resolve()
if hashlib.sha256(A.allium_jar.read_bytes()).hexdigest() != A.sha256:
    P.error('frozen jar SHA256 mismatch')

API = INFRA / 'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP = INFRA / 'client/node_modules/minecraft-protocol'
PORT = 25860 if A.suite == 'spigot' else 25861
A.run_root.mkdir(parents=True, exist_ok=True)
lock = open(str(A.run_root / ('ports-' + A.suite + '.lock')), 'a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
with socket.socket() as s:
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', PORT))

RUN = A.run_root / A.run_name
RUN.mkdir(parents=True, exist_ok=False)
JAR = RUN / 'Allium-under-test.jar'
shutil.copy2(A.allium_jar, JAR)
SOURCES = [HERE / 'run.py', HERE / 'plugin/gate/StaffGate.java', HERE / 'plugin/plugin.yml', HERE / 'client-mcp.cjs',
           HERE / 'client-raw26.cjs', HERE / 'text-components.cjs']
SOURCE_HASHES = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCES}
for src in SOURCES:
    dest = RUN / 'harness' / src.relative_to(REPO)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
HARNESS = RUN / 'harness/tests/live/staff'
ONLY = {c for c in A.only.split(',') if c}

checks, failures, cases = [], [], []
lanes = []
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
    emit(('PASS ' if ok else 'FAIL ') + label + ('' if ok or detail is None else '  ' + json.dumps(detail, default=str)[:900]))
    return bool(ok)


def pump(proc, path, sink):
    with path.open('a') as f:
        for line in proc.stdout:
            f.write(line)
            f.flush()
            sink.append(line)


def alive():
    for lane in lanes:
        if lane.proc is not None and not lane.expect_exit and lane.proc.poll() is not None:
            raise RuntimeError(f'{lane.name} server unexpected exit {lane.proc.returncode}')
        for bot in list(lane.bots.values()):
            if bot.proc is not None and not bot.expect_exit and bot.proc.poll() is not None:
                raise RuntimeError(f'{lane.name}/{bot.name} client unexpected exit {bot.proc.returncode}')


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


def window(seconds=.35):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build_helper():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-cp', f'{API}:{JAR}', '-d', str(classes),
                    str(HARNESS / 'plugin/gate/StaffGate.java')], check=True)
    shutil.copy2(HARNESS / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/StaffGate.jar'), '-C', str(classes), '.'], check=True)


class Bot:
    def __init__(self, lane, name):
        self.lane, self.name, self.proc, self.lines, self.last, self.expect_exit = lane, name, None, [], 0, False

    def connect(self):
        start = len(self.lines)
        cmd = ['node', str(HARNESS / ('client-mcp.cjs' if self.lane.kind == 'spigot' else 'client-raw26.cjs')),
               str(self.lane.path), str(self.lane.port)]
        if self.lane.kind == 'spigot':
            cmd.append('1.20.1')
        env = dict(os.environ, MC_PROTOCOL_MODULE=str(MCP), NODE_COMPILE_CACHE=str(RUN / '.node-cache'))
        self.expect_exit = False
        self.proc = subprocess.Popen(cmd + [self.name], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / f'{self.lane.name}-{self.name}.log', self.lines), daemon=True).start()
        wait(lambda: any('BASEG-WIRE READY' in s for s in self.lines[start:]), self.name + ' ready', 120)
        window(.6)
        return start

    def write(self, s):
        alive()
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def mark(self):
        return len(self.lines)

    def cmd(self, text):
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = self.mark()
        self.write('cmd ' + text)
        wait(lambda: any(s == 'BASEG-WIRE SENT /' + text + '\n' for s in self.lines[start:]), self.name + ' sent /' + text)
        return start

    def say(self, text):
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = self.mark()
        self.write('say ' + text)
        wait(lambda: any(s == 'BASEG-WIRE SAY ' + text + '\n' for s in self.lines[start:]), self.name + ' said ' + text)
        return start

    def chats(self, start):
        out = []
        for s in self.lines[start:]:
            if s.startswith('BASEG-CHAT '):
                try:
                    out.append(json.loads(s.split(' ', 1)[1]))
                except ValueError:
                    pass
        return out

    def chat(self, start, fragment, timeout=12):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c.get('plain', '')), None),
                    self.name + ' chat: ' + fragment, timeout)

    def saw(self, start, fragment):
        return any(fragment in c.get('plain', '') for c in self.chats(start))

    def command(self, text, expected, timeout=12):
        return self.chat(self.cmd(text), expected, timeout)

    def opened(self, start):
        return wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('INSP-OPEN ')), None),
                    self.name + ' window open')

    def click(self, slot, button=0, mode=0):
        start = self.mark()
        self.write(f'click {slot} {button} {mode}')
        wait(lambda: any(s.startswith('INSP-CLICK ') for s in self.lines[start:]), self.name + f' click {slot}')
        window(.35)

    def close(self):
        start = self.mark()
        self.write('close')
        window(.4)
        return start

    def tab(self, text):
        start = self.mark()
        self.write('tab ' + text)
        req = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('MSGG-TABREQ ')), None),
                   'tab request')
        def response(tid):
            return next((json.loads(s.split(' ', 1)[1]) for s in self.lines[start:]
                         if s.startswith('MSGG-TAB ') and json.loads(s.split(' ', 1)[1])['id'] == tid), None)
        deadline = time.monotonic() + .8
        while time.monotonic() < deadline:
            alive()
            res = response(req['id'])
            if res is not None:
                return res['matches']
            time.sleep(.02)
        # A server sends no suggestions at all for a command the player cannot use: prove the
        # connection still answers with a positive control, then treat the request as empty.
        fence_start = self.mark()
        self.write('tab msgtoggle ')
        fence = wait(lambda: next((json.loads(s.split(' ', 1)[1]) for s in self.lines[fence_start:] if s.startswith('MSGG-TABREQ ')), None),
                     'control request')
        positive = wait(lambda: response(fence['id']), 'control response', 6)
        if not {'on', 'off'} <= set(positive['matches']):
            raise RuntimeError('completion control did not answer on/off')
        res = response(req['id'])
        return [] if res is None else res['matches']

    def signuse(self, x, y, z):
        start = self.mark()
        self.write(f'signuse {x} {y} {z} 0')
        wait(lambda: any('SIGN-SENT use' in s for s in self.lines[start:]), 'sign use sent')
        return start

    def disconnect(self):
        if self.proc is None:
            return
        proc, self.proc = self.proc, None
        self.expect_exit = True
        stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=15)
        if self.lane.proc is not None and self.lane.proc.poll() is None:
            wait(lambda: self.name not in self.lane.fixture('online')['players'], self.name + ' offline', 10)


class Lane:
    def __init__(self, kind, port):
        self.name, self.kind, self.port = kind, kind, port
        self.path = RUN / kind
        self.proc, self.logs, self.bots, self.expect_exit = None, [], {}, False
        (self.path / 'plugins/Allium').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/StaffGate.jar', self.path / 'plugins/StaffGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        config.setdefault('modules', {}).update({m: True for m in config['modules']})
        config.setdefault('database', {})['type'] = 'SQLITE'
        config['enabledSigns'] = ['color', 'warp', 'kit', 'balance', 'heal', 'repair', 'disposal', 'workbench', 'anvil', 'mail', 'info']
        config.setdefault('restore', {})['autosave-minutes'] = 600
        # Every gate client connects from 127.0.0.1; let alt detection see them.
        config.setdefault('alts', {})['ignore-local-addresses'] = False
        (self.path / 'plugins/Allium/config.yml').write_text(yaml.safe_dump(config, sort_keys=False))
        if kind == 'paper':
            for d in ['libraries', 'versions']:
                shutil.copytree(A.paper_template / d, self.path / d)
        else:
            shutil.copy2(INFRA / 'servers/spigot-1.20.1.jar', self.path / 'server.jar')
        (self.path / 'eula.txt').write_text('eula=true\n')
        (self.path / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
        self.properties(force_gamemode=False)
        lanes.append(self)

    def properties(self, force_gamemode):
        (self.path / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={self.port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
difficulty=peaceful
spawn-monsters=false
force-gamemode={'true' if force_gamemode else 'false'}
gamemode=survival
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

    def argv(self):
        java = '/usr/lib/jvm/java-' + ('17' if self.kind == 'spigot' else '25') + '-openjdk/bin/java'
        cmd = [java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dallium.fault-injection=true',
               '-Djava.io.tmpdir=' + str(self.path / 'tmp')]
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
        self.boot_mark = start
        emit('BOOT ' + self.name)
        self.expect_exit = False
        self.proc = subprocess.Popen(self.argv(), cwd=self.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / (self.name + '-server.log'), self.logs), daemon=True).start()
        wait(lambda: any('Done (' in s for s in self.logs[start:]), self.name + ' startup', 600)
        return start

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('stg ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('STG-DONE ' + s + ' => ') in v or ('STG-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'STG-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def log_since(self, start, fragment, timeout=10):
        return wait(lambda: next((v for v in self.logs[start:] if fragment in v), None), self.name + ' log: ' + fragment, timeout)

    def bot(self, name):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect()
        return b

    def perm(self, name, node, value=True):
        r = self.fixture(f'perm {name} {node} {str(value).lower()}')
        if r['permission'] != value:
            raise RuntimeError('permission ' + node + ' did not apply')

    def stop(self):
        for bot in list(self.bots.values()):
            if bot.proc is not None:
                bot.disconnect()
        if self.proc is not None:
            proc, self.proc = self.proc, None
            stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=120)

    def halted(self, code):
        """Waits for a deliberate JVM halt and reaps the clients it disconnected."""
        proc = self.proc
        self.expect_exit = True
        for bot in self.bots.values():
            bot.expect_exit = True
        wait(lambda: proc.poll() is not None, self.name + ' deliberate halt', 60, monitor=False)
        for bot in self.bots.values():
            if bot.proc is not None:
                try:
                    bot.proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    bot.proc.kill()
                bot.proc = None
        self.proc = None
        return proc.returncode


def slot(inv, n):
    return inv['slots'].get(str(n))


def case(name, fn):
    if ONLY and name not in ONLY:
        return
    emit('CASE ' + name)
    before = len(failures)
    try:
        fn()
        cases.append(dict(name=name, passed=len(failures) == before))
    except Exception as error:
        traceback.print_exc()
        require(False, name + ': ' + type(error).__name__ + ': ' + str(error)[:400])
        cases.append(dict(name=name, passed=False, error=str(error)[:400]))


# ---------------------------------------------------------------------------------------------------------------------
# Cases
# ---------------------------------------------------------------------------------------------------------------------

def setup(lane):
    staff = lane.bot('StaffBot')
    player = lane.bot('PlayerBot')
    for node in ['allium.eco', 'allium.staffchat', 'allium.alerts', 'allium.whois', 'allium.whois.extra', 'allium.whois.ip', 'allium.seen.alts',
                 'allium.seen.ip', 'allium.altexempt', 'allium.invsee', 'allium.invsee.edit', 'allium.invsee.offline',
                 'allium.moderation.bypass', 'allium.restore', 'allium.restore.others', 'allium.restore.edit',
                 'allium.gamemode', 'allium.gamemode.others', 'allium.gamemode.survival', 'allium.gamemode.creative',
                 'allium.gamemode.adventure', 'allium.gamemode.spectator', 'allium.gamemode.creative.inventory',
                 'allium.channel.join.staff-chat.write', 'allium.channel', 'allium.mail.bypass.limits']:
        lane.perm('StaffBot', node)
    return staff, player


def completions(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    eco = staff.tab('eco ')
    require({'give', 'take', 'set', 'open'} <= set(eco) and 'resolve' not in eco, lane.name + ': /eco completes its actions', eco)
    give = staff.tab('eco give ')
    require('PlayerBot' in give, lane.name + ': /eco give completes known players', give)
    amounts = staff.tab('eco give PlayerBot ')
    require('100' in amounts, lane.name + ': /eco give completes amounts', amounts)
    pay = player.tab('pay ')
    require('StaffBot' in pay, lane.name + ': /pay completes visible players', pay)
    require(player.tab('eco ') == [], lane.name + ': /eco completes nothing without allium.eco')
    bal = staff.tab('balance ')
    require('PlayerBot' in bal or bal == [], lane.name + ': /balance completion follows allium.balance.others', bal)


def help_menu(lane):
    staff = lane.bots['StaffBot']
    # A registered "<owner>.command.<x>" node (no module) crashed /help on a live server whose hide
    # settings use Allium's permission resolver: it then inspects commands without a plain node.
    require(lane.fixture('addperm allium.command.reload')['registered'], lane.name + ': an allium.command.reload node is registered')
    lane.perm('StaffBot', 'allium.help')
    hide = lane.path / 'plugins/Allium/hide.yml'
    original = hide.read_text()
    settings = yaml.safe_load(original)
    settings.setdefault('settings', {}).update({'enabled': True, 'use-allium-permission-resolver': True})
    # Commands with no plain permission node are the ones the resolver inspects.
    settings['groups']['default']['commands'] = list(settings['groups']['default'].get('commands', [])) + ['homes', 'delmsg']
    hide.write_text(yaml.safe_dump(settings, sort_keys=False))
    start = len(lane.logs)
    lane.console('allium reload')
    lane.log_since(start, 'reload', 20)
    window(1)
    try:
        start = len(lane.logs)
        m = staff.cmd('help')
        staff.chat(m, 'Help Menu')
        window(1)
        require(not any('Exception' in v for v in lane.logs[start:]),
                lane.name + ': with the resolver on, /help lists commands without an exception',
                [v for v in lane.logs[start:] if 'Exception' in v][:3])
        lines = [c['plain'] for c in staff.chats(m)]
        require(not any('Allium safe teleport command' in v for v in lines), lane.name + ': /help descriptions are the rewritten ones', lines[:12])
    finally:
        hide.write_text(original)
        start = len(lane.logs)
        lane.console('allium reload')
        lane.log_since(start, 'reload', 20)
        window(1)


def staff_chat(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    staff.command('channel join staff-chat write', 'staff-chat')
    m_player, m_staff = player.mark(), staff.mark()
    staff.say('>global-from-staff-1')
    player.chat(m_player, 'global-from-staff-1')
    require(not player.saw(m_player, '>global-from-staff-1'), lane.name + ': ">" is stripped from the redirected message')
    m_player = player.mark()
    staff.say('still-staff-2')
    staff.chat(m_staff, 'still-staff-2')
    window(1.5)
    require(not player.saw(m_player, 'still-staff-2'), lane.name + ': after ">", the staff selection stays (next line is staff only)')
    staff.command('channel join default write', 'default')
    m_player, m_staff = player.mark(), staff.mark()
    staff.say('#to-staff-3')
    staff.chat(m_staff, 'to-staff-3')
    window(1.5)
    require(not player.saw(m_player, 'to-staff-3'), lane.name + ': "#" from global still goes to staff only')


def gamemode_perms(lane):
    player = lane.bots['PlayerBot']
    lane.perm('PlayerBot', 'allium.gamemode')
    player.command('gamemode creative', "permission to use creative mode")
    require(lane.fixture('inv PlayerBot')['gamemode'] == 'SURVIVAL', lane.name + ': /gamemode creative refused without allium.gamemode.creative')
    require(player.tab('gamemode ') == [], lane.name + ': /gamemode completes no mode without a mode node', player.tab('gamemode '))
    lane.perm('PlayerBot', 'allium.gamemode.creative')
    require(player.tab('gamemode ') == ['creative'], lane.name + ': /gamemode completes only the permitted mode')
    lane.perm('PlayerBot', 'allium.gamemode.survival')
    lane.perm('PlayerBot', 'allium.gamemode.adventure')


def creative_inventories(lane):
    player = lane.bots['PlayerBot']
    lane.fixture('clear PlayerBot')
    lane.fixture('give PlayerBot 0 DIAMOND 5')
    lane.fixture('give PlayerBot 39 IRON_HELMET 1')
    player.command('gamemode creative', 'Gamemode set to')
    inv = wait(lambda: (lambda i: i if i['gamemode'] == 'CREATIVE' else None)(lane.fixture('inv PlayerBot')), 'creative')
    require(slot(inv, 0) is None and slot(inv, 39) is None, lane.name + ': entering creative loads an empty creative inventory', inv['slots'])
    require(inv['pdc'].get('allium:creative_group') == 'creative' and 'allium:creative_stash_survival' in inv['pdc'],
            lane.name + ': the survival inventory is stored in the player data', inv['pdc'])
    lane.fixture('give PlayerBot 0 BEDROCK 7')
    player.command('gamemode survival', 'Gamemode set to')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'DIAMOND' and slot(inv, 0)['amount'] == 5
            and slot(inv, 39) and slot(inv, 39)['type'] == 'IRON_HELMET', lane.name + ': survival gets its own items back', inv['slots'])
    require(not any(v['type'] == 'BEDROCK' for v in inv['slots'].values()), lane.name + ': no creative item reaches survival')
    player.command('gamemode adventure', 'Gamemode set to')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'DIAMOND', lane.name + ': adventure shares the survival inventory')
    player.command('gamemode creative', 'Gamemode set to')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'BEDROCK' and slot(inv, 0)['amount'] == 7,
            lane.name + ': creative gets its own items back', inv['slots'])


def creative_restriction(lane):
    player = lane.bots['PlayerBot']
    lane.perm('PlayerBot', 'allium.workbench')
    m = player.cmd('workbench')
    player.chat(m, 'use this container in creative mode')
    inv = lane.fixture('inv PlayerBot')
    require(inv['topType'] == 'CRAFTING', lane.name + ': a crafting table does not open in creative without .container', inv['topType'])
    lane.perm('PlayerBot', 'allium.gamemode.creative.container')
    m = player.cmd('workbench')
    wait(lambda: lane.fixture('inv PlayerBot')['topType'] == 'WORKBENCH', 'workbench opens with .container')
    require(True, lane.name + ': it opens with allium.gamemode.creative.container')
    player.close()
    lane.perm('PlayerBot', 'allium.gamemode.creative.container', False)


def creative_restart(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    require(lane.fixture('inv PlayerBot')['gamemode'] == 'CREATIVE', lane.name + ': PlayerBot leaves in creative')
    lane.stop()
    lane.properties(force_gamemode=True)
    start = lane.boot()
    staff = lane.bot('StaffBot')
    setup_perms_after_restart(lane)
    # The node must hold at join, as a permissions plugin provides it.
    for node in ['allium.gamemode', 'allium.gamemode.survival', 'allium.gamemode.creative', 'allium.gamemode.adventure']:
        lane.fixture('sticky PlayerBot ' + node + ' true')
    lane.bot('PlayerBot')
    inv = wait(lambda: (lambda i: i if i['gamemode'] == 'CREATIVE' else None)(lane.fixture('inv PlayerBot')), 'creative restored', 15)
    require(any('Restored PlayerBot to creative' in s for s in lane.logs[start:]),
            lane.name + ': force-gamemode reset is undone: PlayerBot is restored to creative on join')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'BEDROCK', lane.name + ': after a restart and a forced reset the creative inventory is intact', inv['slots'])
    player = lane.bots['PlayerBot']
    player.command('gamemode survival', 'Gamemode set to')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'DIAMOND' and slot(inv, 0)['amount'] == 5 and slot(inv, 39)
            and slot(inv, 39)['type'] == 'IRON_HELMET', lane.name + ': the survival inventory survived the restart unchanged', inv['slots'])
    lane.stop()
    lane.properties(force_gamemode=False)
    lane.boot()
    lane.bot('StaffBot')
    setup_perms_after_restart(lane)
    lane.bot('PlayerBot')


def setup_perms_after_restart(lane):
    for node in ['allium.eco', 'allium.staffchat', 'allium.alerts', 'allium.whois', 'allium.whois.extra', 'allium.whois.ip', 'allium.seen.alts',
                 'allium.seen.ip', 'allium.altexempt', 'allium.invsee', 'allium.invsee.edit', 'allium.invsee.offline',
                 'allium.moderation.bypass', 'allium.restore', 'allium.restore.others', 'allium.restore.edit',
                 'allium.gamemode', 'allium.gamemode.others', 'allium.gamemode.survival', 'allium.gamemode.creative',
                 'allium.gamemode.creative.inventory', 'allium.mail.bypass.limits']:
        lane.perm('StaffBot', node)


def creative_audit(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    lane.perm('PlayerBot', 'allium.gamemode.creative', False)
    m = staff.mark()
    lane.console('gamemode creative PlayerBot')
    staff.chat(m, 'switched to creative')
    require(True, lane.name + ': staff are alerted when a player enters creative without allium.gamemode.creative')
    lane.console('gamemode survival PlayerBot')
    wait(lambda: lane.fixture('inv PlayerBot')['gamemode'] == 'SURVIVAL', 'back to survival')
    lane.perm('PlayerBot', 'allium.gamemode.creative')


def invsee(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    lane.fixture('clear PlayerBot')
    lane.fixture('give PlayerBot 9 COBBLESTONE 3')
    lane.fixture('give PlayerBot 2 GOLDEN_APPLE 3')
    lane.fixture('held PlayerBot 2')
    # Icons: armor and offhand are empty; the held item (hotbar slot 3) is in the main-hand cell.
    m = staff.cmd('invsee PlayerBot')
    staff.opened(m)
    top = wait(lambda: (lambda i: i['top'] if i['topSize'] == 45 else None)(lane.fixture('inv StaffBot')), 'invsee open')
    icons = {k: v for k, v in top.items() if v['type'] == 'LIGHT_GRAY_STAINED_GLASS_PANE'}
    require(all(str(s) in icons for s in [36, 37, 38, 39, 40]), lane.name + ': empty armor and offhand slots show slot icons', top)
    require(icons.get('36', {}).get('cmd') == 2577101 and icons.get('40', {}).get('cmd') == 2577105
            and icons.get('42', {}).get('cmd') == 2577100, lane.name + ': icons carry the gui_slot model data (helmet, shield, pane)', icons)
    require(top.get('41', {}).get('type') == 'GOLDEN_APPLE' and top['41']['amount'] == 3,
            lane.name + ': the main-hand cell shows the held hotbar item', top.get('41'))
    require(icons.get('29', {}).get('cmd') == 2577100 and 'Selected slot 3' in icons.get('29', {}).get('name', ''),
            lane.name + ': the held hotbar cell is a marker, so the item shows once', icons.get('29'))
    # Put a helmet on PlayerBot through the helmet icon.
    lane.fixture('give StaffBot 0 IRON_HELMET 1')
    staff.click(72)
    staff.click(36)
    window(.6)
    target = lane.fixture('inv PlayerBot')
    mine = lane.fixture('inv StaffBot')
    require(slot(target, 39) and slot(target, 39)['type'] == 'IRON_HELMET', lane.name + ': placing an item on the helmet icon equips it', target['slots'])
    require(not any(v['type'] == 'LIGHT_GRAY_STAINED_GLASS_PANE' for v in mine['slots'].values()) and mine['cursor'] is None,
            lane.name + ': no icon ever reaches the viewer', mine['slots'])
    # Take the main-hand item, then put it back through the empty main-hand (sword) cell.
    staff.click(41)
    staff.click(72)
    window(.6)
    target, top = lane.fixture('inv PlayerBot'), lane.fixture('inv StaffBot')
    require(slot(target, 2) is None and slot(top, 0) and slot(top, 0)['type'] == 'GOLDEN_APPLE'
            and top['top'].get('41', {}).get('cmd') == 2577106 and 'hotbar slot' in json.dumps(top['top'].get('41', {}).get('lore')),
            lane.name + ': taking the main-hand item empties the held hotbar slot; the sword shows the empty hand',
            dict(target=target['slots'], cell=top['top'].get('41')))
    staff.click(72)
    staff.click(41)
    window(.6)
    target, mine = lane.fixture('inv PlayerBot'), lane.fixture('inv StaffBot')
    require(slot(target, 2) and slot(target, 2)['type'] == 'GOLDEN_APPLE' and slot(target, 2)['amount'] == 3 and not mine['slots']
            and mine['cursor'] is None, lane.name + ': placing on the main-hand cell puts the item in the held hotbar slot',
            dict(target=target['slots'], mine=mine['slots']))
    staff.close()
    # Dead target.
    lane.fixture('kill PlayerBot')
    wait(lambda: lane.fixture('inv PlayerBot')['dead'], 'PlayerBot dead')
    staff.command('invsee PlayerBot', 'is dead (on the respawn screen)')
    require(True, lane.name + ': /invsee on a dead player says so instead of "went offline"')
    lane.fixture('respawn PlayerBot')
    wait(lambda: not lane.fixture('inv PlayerBot')['dead'], 'PlayerBot respawned')
    # Offline edit messages.
    lane.fixture('clear PlayerBot')
    lane.fixture('give PlayerBot 9 COBBLESTONE 3')
    lane.fixture('give PlayerBot 2 BREAD 4')
    lane.fixture('held PlayerBot 2')
    lane.fixture('save PlayerBot')
    player.disconnect()
    lane.fixture('clear StaffBot')
    m = staff.cmd('invsee PlayerBot')
    staff.chat(m, "Opened PlayerBot's saved inventory")
    require(any('editable' in c['plain'] for c in staff.chats(m)), lane.name + ': offline /invsee announces it is editable')
    staff.opened(m)
    window(.5)
    top = lane.fixture('inv StaffBot')['top']
    require(top.get('41', {}).get('type') == 'BREAD' and top.get('29', {}).get('cmd') == 2577100,
            lane.name + ': offline, the main hand comes from the player file\'s selected slot', dict(hand=top.get('41'), marker=top.get('29')))
    staff.click(41)
    staff.click(72)
    window(.5)
    m = staff.close()
    staff.chat(m, "Saved 1 slot change(s) to PlayerBot's offline inventory")
    require(True, lane.name + ': closing an edited offline inventory reports the saved changes')
    player = lane.bot('PlayerBot')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 2) is None and slot(inv, 9) and slot(inv, 9)['type'] == 'COBBLESTONE',
            lane.name + ': the offline main-hand edit reached the player data, nothing else changed', inv['slots'])
    mine = lane.fixture('inv StaffBot')
    require(slot(mine, 0) and slot(mine, 0)['type'] == 'BREAD' and slot(mine, 0)['amount'] == 4,
            lane.name + ': the viewer holds the taken main-hand stack', mine['slots'])


def mail_numbers(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    for i in range(1, 4):
        lane.console(f'mail send PlayerBot text-{i}')
    wait(lambda: len(lane.fixture('mailbox PlayerBot')['rows']) >= 3, 'three texts')
    m = player.cmd('mail read')
    player.chat(m, 'text-3')
    lines = [c['plain'] for c in player.chats(m)]
    require(any('[1]' in s and 'text-1' in s for s in lines) and any('[3]' in s and 'text-3' in s for s in lines),
            lane.name + ': text mail reads as [1] [2] [3]', lines)
    player.command('mail delete 2', 'Mail 2 deleted')
    lane.console('mail send PlayerBot text-4')
    rows = wait(lambda: (lambda r: r if len([x for x in r if x['kind'] == 'TEXT']) == 3 else None)(lane.fixture('mailbox PlayerBot')['rows']), 'text 4')
    text4 = next(r for r in rows if r['body'] == 'text-4')
    require(text4['number'] == 2, lane.name + ': a new mail takes the freed number 2', rows)
    lane.fixture('give StaffBot 0 STONE 3')
    lane.fixture('held StaffBot 0')
    staff.command('mail gift PlayerBot 1', 'Gift of')
    staff.command('mail gift PlayerBot 1', 'Gift of')
    rows = wait(lambda: (lambda r: r if len([x for x in r if x['kind'] == 'GIFT' and x['state'] == 'READY']) == 2 else None)(
        lane.fixture('mailbox PlayerBot')['rows']), 'two gifts')
    require(sorted(r['number'] for r in rows if r['kind'] == 'GIFT') == [1, 2], lane.name + ': gifts are numbered 1 and 2 apart from text', rows)
    m = player.cmd('mail read')
    player.chat(m, '/mail claim 2')
    player.command('mail claim 1', 'Claimed gift 1')
    player.command('mail claim 1', 'You have no mail 1')
    staff.command('mail gift PlayerBot 1', 'Gift of')
    rows = wait(lambda: (lambda r: r if len([x for x in r if x['kind'] == 'GIFT' and x['state'] == 'READY']) == 2 else None)(
        lane.fixture('mailbox PlayerBot')['rows']), 'gift reuses 1')
    require(sorted(r['number'] for r in rows if r['kind'] == 'GIFT') == [1, 2], lane.name + ': the next gift reuses number 1', rows)
    player.command('mail claim all', 'Claimed gift')
    player.command('mail clear', 'read mail')


def whois_and_alts(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    alts = lane.fixture('alts PlayerBot')
    require(any(r['ip'] == '127.0.0.1' for r in alts['ips']), lane.name + ': joins record the IP history', alts)
    # Rejoin PlayerBot: staff hear about the alt (no permissions plugin on the lane, so on every join).
    player.disconnect()
    m = staff.mark()
    start = len(lane.logs)
    player = lane.bot('PlayerBot')
    staff.chat(m, 'PlayerBot is an alt account')
    require(any('shares an IP with' in c['plain'] and 'StaffBot' in c['plain'] for c in staff.chats(m)),
            lane.name + ': the alert names the older account')
    run_logs = lane.logs[lane.boot_mark:]
    require(any('cannot add group alt' in s for s in run_logs), lane.name + ': without LuckPerms or Vault the missing group backend is logged')
    require(sum('cannot add group alt' in s for s in run_logs) == 1, lane.name + ': and only once per server run',
            [s for s in run_logs if 'cannot add group alt' in s])
    m = staff.cmd('seen PlayerBot')
    staff.chat(m, 'Alt accounts: StaffBot')
    staff.chat(m, 'IP: 127.0.0.1')
    require(True, lane.name + ': /seen lists alt accounts and the IP for staff')
    m = player.cmd('seen StaffBot')
    window(1.5)
    require(not any('Alt accounts' in c['plain'] or 'IP:' in c['plain'] for c in player.chats(m)), lane.name + ': players without the nodes see neither')
    staff.command('altexempt add PlayerBot verified sibling', 'Exempted PlayerBot from alt detection')
    staff.command('altexempt check PlayerBot', 'is exempt from alt detection')
    require(lane.fixture('alts PlayerBot')['exempt'][0]['reason'] == 'verified sibling', lane.name + ': the exemption is stored with its reason')
    player.disconnect()
    m = staff.mark()
    player = lane.bot('PlayerBot')
    window(2)
    require(not staff.saw(m, 'is an alt account'), lane.name + ': an exempt account rejoins without an alert')
    staff.command('altexempt remove PlayerBot', "Removed PlayerBot's alt exemption")
    # /whois extras.
    m = staff.cmd('whois PlayerBot')
    for fragment in ['Gamemode:', 'Health:', 'Op:', 'IP: 127.0.0.1', 'Alt accounts: StaffBot', 'Actions', '[CoreProtect]', '[LiteBans]', '[ChatSentry]']:
        staff.chat(m, fragment)
    actions = [c for c in staff.chats(m) if '[LiteBans]' in c['plain']]
    require(actions and actions[0].get('interactive') and '/history PlayerBot' in json.dumps(actions[0].get('rawComponent')),
            lane.name + ': the LiteBans action carries a click event running /history PlayerBot', actions[:1])
    require(True, lane.name + ': /whois shows state, vitals, flags, IP, alts and the action links to allium.whois.extra')
    lane.perm('PlayerBot', 'allium.whois')
    m = player.cmd('whois StaffBot')
    player.chat(m, 'Known names')
    window(1)
    require(not any('Gamemode:' in c['plain'] or '[LiteBans]' in c['plain'] for c in player.chats(m)),
            lane.name + ': without allium.whois.extra /whois stays basic')


def restore(lane):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    lane.fixture('clear PlayerBot')
    lane.fixture('give PlayerBot 0 DIAMOND 5')
    lane.fixture('give PlayerBot 38 IRON_CHESTPLATE 1')
    lane.fixture('give PlayerBot 3 GOLDEN_APPLE 2')
    lane.fixture('held PlayerBot 3')
    lane.fixture('snapshot PlayerBot MANUAL')
    wait(lambda: any(r['reason'] == 'MANUAL' for r in lane.fixture('snapcount PlayerBot')['newest']), 'manual snapshot stored')
    count = lane.fixture('snapcount PlayerBot')['snapshots'][0]['n']
    require(count >= 2, lane.name + ': snapshots were taken on login/logout/gamemode change and manually', count)
    lane.fixture('clear PlayerBot')
    lane.fixture('held PlayerBot 0')
    m = staff.cmd('restore PlayerBot')
    win = staff.opened(m)
    require('Restore: PlayerBot' in win['title'], lane.name + ': /restore opens the history', win)
    window(.5)
    m = staff.mark()
    staff.click(0)
    win = staff.opened(m)
    require('manual' in win['title'].lower(), lane.name + ': the newest (MANUAL) snapshot opens its restore panel', win)
    top = lane.fixture('inv StaffBot')['top']
    require(top.get('27', {}).get('type') == 'DIAMOND' and top.get('37', {}).get('type') == 'IRON_CHESTPLATE',
            lane.name + ': the panel shows the snapshot (hotbar and armor)', top)
    require(top.get('36', {}).get('cmd') == 2577101 and top.get('40', {}).get('cmd') == 2577105, lane.name + ': empty armor and offhand show icons', top)
    require(top.get('41', {}).get('type') == 'GOLDEN_APPLE' and top.get('30', {}).get('cmd') == 2577100
            and 'Selected slot 4' in top.get('30', {}).get('name', ''),
            lane.name + ': the main-hand cell holds the snapshot\'s held item (hotbar slot 4 is the marker)', dict(hand=top.get('41'), marker=top.get('30')))
    require(top.get('52', {}).get('type') == 'PAPER' and 'MANUAL' in top.get('52', {}).get('name', ''),
            lane.name + ': the snapshot info sits in the bottom row', top.get('52'))
    mp = player.mark()
    staff.click(45)
    player.chat(mp, 'restored from the MANUAL snapshot')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 0) and slot(inv, 0)['type'] == 'DIAMOND' and slot(inv, 38) and slot(inv, 38)['type'] == 'IRON_CHESTPLATE',
            lane.name + ': "Restore everything" replaces the online owner\'s inventory', inv['slots'])
    require(inv['held'] == 3, lane.name + ': and selects the main hand the snapshot had', inv['held'])
    # The main hand is editable: shift it out, then put it back on the empty main-hand (sword) cell.
    lane.fixture('clear StaffBot')
    staff.click(41, 0, 1)
    window(.4)
    mine = lane.fixture('inv StaffBot')
    require(slot(mine, 0) and slot(mine, 0)['type'] == 'GOLDEN_APPLE' and mine['top'].get('41', {}).get('cmd') == 2577106,
            lane.name + ': shift-click takes the main-hand stack out; the sword shows the empty hand', dict(mine=mine['slots'], hand=mine['top'].get('41')))
    staff.click(81)
    staff.click(41)
    window(.4)
    mine = lane.fixture('inv StaffBot')
    require(not mine['slots'] and mine['cursor'] is None and mine['top'].get('41', {}).get('type') == 'GOLDEN_APPLE',
            lane.name + ': placing on the empty main-hand cell puts it back', dict(mine=mine['slots'], hand=mine['top'].get('41')))
    # Edit with shift-click: a whole stack out of the panel, then back into a storage cell.
    lane.fixture('clear StaffBot')
    staff.click(27, 0, 1)
    window(.4)
    mine, top = lane.fixture('inv StaffBot'), lane.fixture('inv StaffBot')['top']
    require(slot(mine, 0) and slot(mine, 0)['type'] == 'DIAMOND' and slot(mine, 0)['amount'] == 5 and '27' not in top,
            lane.name + ': shift-click takes a whole stack out of the panel', dict(mine=mine['slots'], top27=top.get('27')))
    staff.click(81, 0, 1)
    window(.4)
    mine = lane.fixture('inv StaffBot')
    require(not mine['slots'] and mine['top'].get('0', {}).get('type') == 'DIAMOND' and mine['top']['0']['amount'] == 5
            and mine['top'].get('45', {}).get('type') == 'PLAYER_HEAD' and mine['top'].get('36', {}).get('cmd') == 2577101,
            lane.name + ': shift-click puts a whole stack into the first free storage cell, never armor or controls',
            dict(mine=mine['slots'], top=mine['top']))
    staff.click(47)
    inv = wait(lambda: (lambda i: i if slot(i, 0) is None else None)(lane.fixture('inv PlayerBot')), 'inventory-only restore')
    require(slot(inv, 0) is None and slot(inv, 9) and slot(inv, 9)['type'] == 'DIAMOND' and slot(inv, 38)
            and slot(inv, 38)['type'] == 'IRON_CHESTPLATE' and slot(inv, 3) and slot(inv, 3)['type'] == 'GOLDEN_APPLE',
            lane.name + ': "inventory only" uses the edited panel (main hand included) and leaves armor alone', inv['slots'])
    before = lane.fixture('snapcount PlayerBot')['snapshots'][0]['n']
    m = staff.mark()
    staff.click(46)
    staff.chat(m, 'Saved this panel as a new MANUAL snapshot')
    wait(lambda: lane.fixture('snapcount PlayerBot')['snapshots'][0]['n'] == before + 1, 'manual save stored')
    require(True, lane.name + ': only "Save as manual snapshot" writes to storage')
    staff.close()
    # Offline owner: queued, then applied on join.
    lane.fixture('clear StaffBot')
    lane.fixture('clear PlayerBot')
    lane.fixture('save PlayerBot')
    player.disconnect()
    m = staff.cmd('restore PlayerBot')
    staff.opened(m)
    window(.5)
    # Newest snapshots now include LOGOUT; open the second MANUAL (index of first MANUAL with diamonds).
    rows = lane.fixture('snapcount PlayerBot')['newest']
    index = next(i for i, r in enumerate(rows) if r['reason'] == 'MANUAL')
    m = staff.mark()
    staff.click(index)
    staff.opened(m)
    window(.4)
    m = staff.mark()
    staff.click(45)
    staff.chat(m, 'PlayerBot is offline')
    wait(lambda: lane.fixture('snapcount PlayerBot')['pending'][0]['n'] == 1, 'queued restore stored')
    staff.close()
    mp = lane.bots['PlayerBot'].mark()
    player = lane.bot('PlayerBot')
    player.chat(mp, 'Staff restored your')
    inv = lane.fixture('inv PlayerBot')
    require(slot(inv, 38) and slot(inv, 38)['type'] == 'IRON_CHESTPLATE', lane.name + ': the queued restore is applied on join', inv['slots'])
    wait(lambda: lane.fixture('snapcount PlayerBot')['pending'][0]['n'] == 0, 'queue cleared')
    wait(lambda: 'allium:restore_receipts' not in lane.fixture('inv PlayerBot')['pdc'], 'receipt cleared')
    require(True, lane.name + ': the queue row and the applied receipt are cleared after the save')
    # Without .edit the panel is read-only.
    lane.perm('StaffBot', 'allium.restore.edit', False)
    lane.fixture('clear StaffBot')
    rows = lane.fixture('snapcount PlayerBot')['newest']
    index = next(i for i, r in enumerate(rows) if r['reason'] == 'MANUAL')
    m = staff.cmd('restore PlayerBot')
    staff.opened(m)
    window(.5)
    m = staff.mark()
    staff.click(index)
    win = staff.opened(m)
    require(win['title'].lower().startswith('view'), lane.name + ': without allium.restore.edit the panel opens read-only', win)
    staff.click(27)
    staff.click(81)
    staff.click(0, 0, 1)
    window(.3)
    mine = lane.fixture('inv StaffBot')
    require(not mine['slots'] and mine['cursor'] is None and mine['top'].get('0', {}).get('type') == 'DIAMOND',
            lane.name + ': a read-only panel hands out nothing, by click or shift-click', mine)
    staff.close()
    lane.perm('StaffBot', 'allium.restore.edit', True)


SIGN = [8, 0, 8]


def repair_setup(lane, balance):
    lane.console('eco open PlayerBot')
    window(1)
    lane.console(f'eco set PlayerBot {balance}')
    wait(lambda: (lambda a: a and a[0]['balance_minor'] == int(round(float(balance) * 100)))(lane.fixture('econ PlayerBot')['account']), 'balance set')
    lane.fixture('clear PlayerBot')
    lane.fixture('give PlayerBot 0 DIAMOND_SWORD 1 100')
    lane.fixture('held PlayerBot 0')
    SIGN[1] = lane.fixture(f'sign {SIGN[0]} {SIGN[2]} hand 125')['y']
    lane.fixture(f'tp PlayerBot {SIGN[0] + .5} {SIGN[1]} {SIGN[2] + 2.5} 180 20')
    lane.perm('PlayerBot', 'allium.repair')
    lane.perm('PlayerBot', 'allium.signs.use.repair')
    window(1.2)


def paid_repair(lane):
    player = lane.bots['PlayerBot']
    repair_setup(lane, '10.00')
    m = player.signuse(*SIGN)
    player.chat(m, 'Repaired diamond sword for $1.25')
    inv = lane.fixture('inv PlayerBot')
    econ = lane.fixture('econ PlayerBot')
    require(slot(inv, 0)['damage'] == 0, lane.name + ': the paid repair repairs the held item', inv['slots'])
    require(econ['account'][0]['balance_minor'] == 875 and econ['account'][0]['reserved_minor'] == 0
            and [r['state'] for r in econ['repairs']] == ['CAPTURED'], lane.name + ': exactly $1.25 is captured and nothing stays held', econ)
    wait(lambda: 'allium:repair_receipts' not in lane.fixture('inv PlayerBot')['pdc'], 'receipt removed')
    require(True, lane.name + ': the repair receipt is dropped once captured')
    m = player.signuse(*SIGN)
    player.chat(m, 'Hold a damaged item')
    require(lane.fixture('econ PlayerBot')['account'][0]['balance_minor'] == 875, lane.name + ': an undamaged item is never charged')
    repair_setup(lane, '1.00')
    m = player.signuse(*SIGN)
    player.chat(m, 'You need $1.25')
    inv, econ = lane.fixture('inv PlayerBot'), lane.fixture('econ PlayerBot')
    require(slot(inv, 0)['damage'] == 100 and econ['account'][0]['balance_minor'] == 100
            and all(r['state'] != 'HELD' for r in econ['repairs']), lane.name + ': without funds nothing is repaired, held or charged', econ)


def repair_halt(lane, fault, expect):
    repair_setup(lane, '10.00')
    player = lane.bots['PlayerBot']
    lane.fixture(f'fault {fault} 1')
    player.signuse(*SIGN)
    code = lane.halted(77)
    require(code == 77, lane.name + f': {fault} halts the JVM', code)
    start = lane.boot()
    lane.bot('StaffBot')
    setup_perms_after_restart(lane)
    lane.bot('PlayerBot')
    econ = lane.fixture('econ PlayerBot')
    newest = econ['repairs'][-1]
    inv = lane.fixture('inv PlayerBot')
    if expect == 'released':
        require(newest['state'] == 'RELEASED' and econ['account'][0]['balance_minor'] == 1000 and econ['account'][0]['reserved_minor'] == 0,
                lane.name + ': a halt after the hold releases it at restart; no money is lost', econ)
        require(slot(inv, 0)['damage'] == 100, lane.name + ': and the item was not repaired', inv['slots'])
    else:
        require(newest['state'] == 'CAPTURED' and econ['account'][0]['balance_minor'] == 875 and econ['account'][0]['reserved_minor'] == 0,
                lane.name + ': a halt after the save captures the hold at restart; charged once', econ)
        require(slot(inv, 0)['damage'] == 0, lane.name + ': and the saved repair is kept', inv['slots'])
        wait(lambda: 'allium:repair_receipts' not in lane.fixture('inv PlayerBot')['pdc'], 'stale receipt cleared on join')
        require(True, lane.name + ': the settled receipt is cleared on join')
    require(any('Allium' in s and ('enabled' in s.lower()) for s in lane.logs[start:]), lane.name + ': Allium restarted')


def clean_logs(lane):
    text = (RUN / (lane.name + '-server.log')).read_text(errors='replace')
    bad = [line for line in text.splitlines() if ('Exception' in line or 'SEVERE' in line or '/ERROR]' in line)
           and 'Fault ' not in line and 'StaffGate' not in line]
    require(not bad, lane.name + ': no exceptions or SEVERE lines in the server log', bad[:10])


def main():
    build_helper()
    lane = Lane(A.suite, PORT)
    try:
        lane.boot()
        require(any('Creative manager enabled' in s for s in lane.logs) and any('Alt detection enabled' in s for s in lane.logs)
                and any('/restore enabled' in s for s in lane.logs), lane.name + ': creative, alts and restore modules enabled')
        setup(lane)
        case('completions', lambda: completions(lane))
        case('help', lambda: help_menu(lane))
        case('staff-chat', lambda: staff_chat(lane))
        case('gamemode-permissions', lambda: gamemode_perms(lane))
        case('creative-inventories', lambda: creative_inventories(lane))
        case('creative-restriction', lambda: creative_restriction(lane))
        case('creative-restart', lambda: creative_restart(lane))
        case('creative-audit', lambda: creative_audit(lane))
        case('invsee', lambda: invsee(lane))
        case('mail-numbers', lambda: mail_numbers(lane))
        case('whois-alts', lambda: whois_and_alts(lane))
        case('restore', lambda: restore(lane))
        case('paid-repair', lambda: paid_repair(lane))
        case('repair-halt-after-reserve', lambda: repair_halt(lane, 'repair.halt-after-reserve', 'released'))
        case('repair-halt-after-save', lambda: repair_halt(lane, 'repair.halt-after-save', 'captured'))
    finally:
        try:
            lane.stop()
        except Exception as error:
            require(False, 'shutdown: ' + str(error))
        clean_logs(lane)
    manifest = dict(suite=A.suite, run=A.run_name, alliumSha256=A.sha256, sources=SOURCE_HASHES, passed=not failures,
                    checks=len(checks), failures=failures, cases=cases, seconds=round(time.time() - started, 1), results=checks)
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2, default=str))
    emit(f'RESULT {"PASS" if not failures else "FAIL"} checks={len(checks)} failures={len(failures)} manifest={RUN / "manifest.json"}')
    sys.exit(0 if not failures else 1)


main()
