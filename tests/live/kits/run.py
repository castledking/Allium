#!/usr/bin/env python3
"""S16 kits real-protocol gate: free kit claiming, cooldowns, one-time kits, full-inventory refusal,
provider test adapter and signed privileged items, /createkit (name validation, privileged gating,
lossless grammar check), /delkit, allium.kits.* wildcard permission, tab completion, disconnect
mid-delivery, crash mid-delivery with restart recovery on Spigot 1.20.1 and Paper 26.3, and
two-server MySQL recovery ownership isolation.
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
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
import uuid
import zipfile
import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['spigot', 'paper', 'shared-mysql'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='parent-confirmed frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--check', action='store_true')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261114/s16-kits/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s16-kits/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s16-kits/runs')
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
for port in (25760, 25761, 25762):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25760-25762 free and gate lock held', flush=True)
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
    HERE / 'plugin/gate/KitGate.java',
    HERE / 'plugin/plugin.yml',
    HERE / 'client-mcp.cjs',
    HERE / 'client-raw26.cjs',
    HERE / 'playerdata.cjs',
    HERE.parent / 'baseline/text-components.cjs',
    REPO / 'tests/live/providers/trigger/process_cleanup.py',
]
SOURCE_HASHES = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE_FILES}
for source in SOURCE_FILES:
    dest = RUN / 'harness' / source.relative_to(REPO)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
    os.chmod(dest, 0o444)
HERE = RUN / 'harness/tests/live/kits'
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


def window(seconds=.5):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    subprocess.run([
        '/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-cp', f'{API}:{JAR}',
        '-d', str(classes), str(HERE / 'plugin/gate/KitGate.java')
    ], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/KitGate.jar'), '-C', str(classes), '.'], check=True)


def prepare_mysql_database(db_name):
    subprocess.run(['docker', 'start', 'allium-gate-mysql'], check=True, stdout=subprocess.DEVNULL)
    env = dict(line.split('=', 1) for line in (INFRA / 'mysql.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    password = shlex.split(env['MYSQL_ROOT_PASSWORD'])[0]
    for _ in range(30):
        r = subprocess.run(
            ['docker', 'exec', '-e', f'MYSQL_PWD={password}', 'allium-gate-mysql',
             'mysql', '-uroot', '-e', f'DROP DATABASE IF EXISTS {db_name}; CREATE DATABASE {db_name};'],
            capture_output=True, text=True
        )
        if r.returncode == 0:
            return password
        time.sleep(1)
    raise RuntimeError('MySQL container did not become ready')


class Bot:
    def __init__(self, lane, name):
        self.lane, self.name, self.proc, self.lines = lane, name, None, []
        self.last = 0

    def connect(self, ready=True):
        start = len(self.lines)
        self.connection_start = start
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
        if ready:
            wait(lambda: self.lane.state(self.name)['kitReady'], self.name + ' kit join recovery/lock settled', 30)
        window(.2)
        return start

    def write(self, s):
        alive()
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def cmd(self, text):
        wait(lambda: self.lane.state(self.name)['kitReady'], self.name + ' kit recovery/lock settled', 30)
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = len(self.lines)
        self.write('cmd ' + text)
        wait(lambda: any(s == 'BASEG-WIRE SENT /' + text + '\n' for s in self.lines[start:]), self.name + ' wire command log')
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
        # Spigot omits the response for empty suggestions. Fence with a positive request.
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
            observations.setdefault('emptyCompletionFences', []).append(
                dict(lane=self.lane.name, player=self.name, text=text, requestId=req['id'], fenceId=fence['id']))
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
    def __init__(self, name, kind, port, database=None, server_id=None):
        self.name, self.kind, self.port = name, kind, port
        self.database = database
        self.path = RUN / name
        self.proc, self.logs, self.bots = None, [], {}
        self.path.mkdir()
        (self.path / 'plugins/Allium/kits').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        if server_id:
            (self.path / 'plugins/Allium/.server-id').write_text(server_id + '\n')
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/KitGate.jar', self.path / 'plugins/KitGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        config.setdefault('modules', {})['kits'] = True
        if database:
            env = dict(line.split('=', 1) for line in (INFRA / 'mysql.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
            password = shlex.split(env['MYSQL_ROOT_PASSWORD'])[0]
            db_cfg = config.setdefault('database', {})
            db_cfg['type'] = 'MYSQL'
            mysql_cfg = db_cfg.setdefault('mysql', {})
            mysql_cfg['host'] = '127.0.0.1'
            mysql_cfg['port'] = 25733
            mysql_cfg['database'] = database
            mysql_cfg['username'] = 'root'
            mysql_cfg['password'] = password
        (self.path / 'plugins/Allium/config.yml').write_text(yaml.safe_dump(config, sort_keys=False))
        os.chmod(self.path / 'plugins/Allium/config.yml', 0o600)
        # Write extra fixture kit files before first boot:
        # 1. oncekit.yml (one-time kit, decimal text cost)
        (self.path / 'plugins/Allium/kits/oncekit.yml').write_text(yaml.safe_dump({
            'enabled': True, 'cooldown-seconds': 0, 'one-time': True, 'cost': '0.00',
            'items': ['DIAMOND 4', 'EMERALD 2']
        }, sort_keys=False))
        # 2. vipkit.yml (paid kit with decimal text cost '50.00', must be denied)
        (self.path / 'plugins/Allium/kits/vipkit.yml').write_text(yaml.safe_dump({
            'enabled': True, 'cooldown-seconds': 60, 'one-time': False, 'cost': '50.00',
            'items': ['NETHERITE_INGOT 1']
        }, sort_keys=False))
        # 3. slowkit.yml (for disconnect mid-delivery and full inventory tests)
        (self.path / 'plugins/Allium/kits/slowkit.yml').write_text(yaml.safe_dump({
            'enabled': True, 'cooldown-seconds': 3600, 'one-time': True, 'cost': '0.00',
            'items': ['IRON_INGOT 5', 'GOLD_INGOT 3']
        }, sort_keys=False))
        # 4. haltbefore.yml (for crash before inventory delivery)
        (self.path / 'plugins/Allium/kits/haltbefore.yml').write_text(yaml.safe_dump({
            'enabled': True, 'cooldown-seconds': 3600, 'one-time': True, 'cost': '0.00',
            'items': ['DIAMOND 6']
        }, sort_keys=False))
        # 5. haltafter.yml (for crash after inventory delivery + player.saveData)
        (self.path / 'plugins/Allium/kits/haltafter.yml').write_text(yaml.safe_dump({
            'enabled': True, 'cooldown-seconds': 3600, 'one-time': True, 'cost': '0.00',
            'items': ['EMERALD 7']
        }, sort_keys=False))

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

    def argv(self):
        java = '/usr/lib/jvm/java-' + ('17' if self.kind == 'spigot' else '25') + '-openjdk/bin/java'
        cmd = [
            java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dallium.fault-injection=true', '-Djava.io.tmpdir=' + str(self.path / 'tmp')
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
        require(any('Kits enabled on' in s for s in self.logs[start:]) and any('S16G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': kits module and helper enabled')

    def console(self, s):
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('s16g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S16G-DONE ' + s + ' => ') in v or ('S16G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S16G-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def bot(self, name, ready=True):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect(ready)
        return b

    def perm(self, name, node, value):
        r = self.fixture(f'perm {name} {node} {str(value).lower()}')
        require(r['permission'] == value, self.name + ': effective ' + name + ' ' + node + '=' + str(value))

    def db(self, name='KitA'):
        return self.fixture('db ' + name)

    def state(self, name='KitA'):
        state = self.fixture('state ' + name)
        self.playerDataDirectory = Path(state['playerDataDirectory'])
        return state

    def amount(self, name, material):
        return sum(s['amount'] for s in self.state(name)['stacks'] if s['material'] == material)

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

    def crash(self, point, kit_name, player='KitA', expected_material=None, expected_count=0):
        proc = self.proc
        playerDataDirectory = self.playerDataDirectory
        code = proc.wait(30)
        require(code == 77 and any('S16G-DONE arm ' + point + ' 1 =>' in s for s in self.logs),
                self.name + ': deliberate ' + point + ' crash exit=77')
        self.proc = None
        ident = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + player).encode()).digest(), version=3)
        if not self.database:
            with sqlite3.connect('file:' + str(self.path / 'plugins/Allium/allium.db') + '?mode=ro', uri=True) as db:
                db.row_factory = sqlite3.Row
                deliveries = [dict(r) for r in db.execute(
                    'SELECT id, kit_name, claim_token, claim_owner, item_count FROM allium_kit_deliveries WHERE player_uuid=? AND kit_name=?',
                    (str(ident), kit_name))]
                usages = [dict(r) for r in db.execute(
                    'SELECT kit_name, last_claimed_at, one_time_claimed, claim_count FROM allium_kit_usages WHERE player_uuid=? AND kit_name=?',
                    (str(ident), kit_name))]
            file = playerDataDirectory / (str(ident) + '.dat')
            env = dict(os.environ, MC_PROTOCOL_MODULE=str(MCP))
            result = subprocess.run(['node', str(HERE / 'playerdata.cjs'), str(file)], env=env, capture_output=True, text=True, check=True)
            saved = json.loads(result.stdout)
            saved['fileSha256'] = hashlib.sha256(file.read_bytes()).hexdigest()
            snap = dict(lane=self.name, point=point, kitName=kit_name, deliveries=deliveries, usages=usages, savedPlayer=saved)
            observations.setdefault('crashSnapshots', []).append(snap)
            item_count = sum(int(i['count']) for i in saved['inventory'] if i['id'] == expected_material) if expected_material else 0
            receipt = saved['receipts'] or ''
            valid = len(deliveries) == 1 and not usages
            if valid:
                d = deliveries[0]
                expected_receipt = f"K/{d['id']}/{d['claim_token']}/{kit_name}"
                if point == 'kit.claim.halt-before-deliver':
                    valid = valid and expected_receipt not in receipt and item_count == 0
                else:
                    valid = valid and expected_receipt in receipt.split(';') and item_count == expected_count
            require(valid, self.name + ': exact target DB row/token and stopped native playerdata receipt/inventory match ' + point, snap)
        for b in self.bots.values():
            if b.proc is not None:
                bp, b.proc = b.proc, None
                try:
                    bp.wait(15)
                except subprocess.TimeoutExpired:
                    bp.terminate()
                    bp.wait(10)
                    require(False, self.name + '/' + b.name + ': client failed to exit after deliberate server crash')
                segment = b.lines[getattr(b, 'connection_start', 0):]
                transport = any(line.startswith('KITG-TRANSPORT ') for line in segment)
                decoder = any(line.startswith('KITG-CLIENTERROR ') for line in segment)
                require((bp.returncode == 0 or (bp.returncode == 1 and transport)) and not decoder,
                        self.name + '/' + b.name + ': deliberate crash client exit is clean or identified transport failure, no decoder error',
                        dict(exit=bp.returncode, transport=transport, decoder=decoder))
                emit(f'EXPECTED CRASH DISCONNECT {self.name}/{b.name} exit={bp.returncode}')
        window(.3)


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


def test_claim_and_cooldown(lane):
    a, b = lane.bots['KitA'], lane.bots['KitB']
    lane.fixture('empty KitA')
    lane.fixture('empty KitB')
    # Without per-kit permission, /kits shows no kits and /kit starter is denied
    a.command('kits', 'No kits available')
    a.command('kit starter', "don't have permission to claim kit starter")
    require(not lane.db('KitA')['usages'] and not lane.state('KitA')['stacks'],
            lane.name + ': unpermitted kit claim denied with zero items and zero DB usage')
    # Grant parent wildcard allium.kits.* and verify it covers all free kits, then revoke it
    lane.perm('KitA', 'allium.kits.*', True)
    a.command('kits', 'oncekit')
    lane.perm('KitA', 'allium.kits.*', False)
    # Grant explicit allium.kits.starter
    lane.perm('KitA', 'allium.kits.starter', True)
    a.command('kits', 'starter')
    a.command('kit', 'starter')
    a.command('kit starter', 'Received kit starter')
    wait(lambda: any(u['kit_name'] == 'starter' and u['claim_count'] == 1 for u in lane.db('KitA')['usages']),
         'starter usage persisted')
    require(lane.amount('KitA', 'STONE_SWORD') == 1 and lane.amount('KitA', 'STONE_PICKAXE') == 1 and lane.amount('KitA', 'COOKED_BEEF') == 16,
            lane.name + ': starter kit delivered exact items', lane.state('KitA')['stacks'])
    require(not lane.db('KitA')['deliveries'] and not lane.state('KitA')['receipts'],
            lane.name + ': completed claim clears delivery row and player receipt')
    # Immediate second claim is on cooldown
    a.command('kit starter', 'on cooldown')
    require(lane.amount('KitA', 'COOKED_BEEF') == 16 and lane.db('KitA')['usages'][0]['claim_count'] == 1,
            lane.name + ': cooldown refusal delivers nothing and does not increment claim_count')
    # Bypass cooldown permission allows claiming while on cooldown
    lane.perm('KitA', 'allium.kit.bypass.cooldown', True)
    a.command('kit starter', 'Received kit starter')
    wait(lambda: any(u['kit_name'] == 'starter' and u['claim_count'] == 2 for u in lane.db('KitA')['usages']),
         'starter bypass usage persisted')
    require(lane.amount('KitA', 'COOKED_BEEF') == 32, lane.name + ': cooldown bypass delivers second starter kit')
    lane.perm('KitA', 'allium.kit.bypass.cooldown', False)
    # /kit <name> <player> requires allium.kit.others AND kit access on the sender
    a.command('kit starter KitB', "don't have permission")
    lane.perm('KitA', 'allium.kit.others', True)
    lane.perm('KitA', 'allium.kits.starter', False)
    a.command('kit starter KitB', "don't have permission to claim kit starter")
    lane.perm('KitA', 'allium.kits.starter', True)
    mark_b = len(b.lines)
    a.command('kit starter KitB', 'Gave kit starter to KitB')
    b.chat(mark_b, 'Received kit starter')
    require(lane.amount('KitB', 'COOKED_BEEF') == 16 and any(u['kit_name'] == 'starter' and u['claim_count'] == 1 for u in lane.db('KitB')['usages'])
            and lane.db('KitA')['usages'][0]['claim_count'] == 2,
            lane.name + ': /kit starter KitB delivers to target and records usage on target (not sender)')


def test_one_time_and_paid_denial(lane):
    a = lane.bots['KitA']
    lane.fixture('empty KitA')
    lane.perm('KitA', 'allium.kits.oncekit', True)
    lane.perm('KitA', 'allium.kits.vipkit', True)
    # Claim one-time kit
    a.command('kit oncekit', 'Received kit oncekit')
    wait(lambda: any(u['kit_name'] == 'oncekit' and u['one_time_claimed'] for u in lane.db('KitA')['usages']),
         'oncekit one_time_claimed persisted')
    require(lane.amount('KitA', 'DIAMOND') == 4 and lane.amount('KitA', 'EMERALD') == 2,
            lane.name + ': one-time kit delivered exact items')
    # Second claim denied even with cooldown bypass
    lane.perm('KitA', 'allium.kit.bypass.cooldown', True)
    a.command('kit oncekit', 'already claimed one-time kit oncekit')
    require(lane.amount('KitA', 'DIAMOND') == 4, lane.name + ': one-time kit cannot be claimed a second time even with cooldown bypass')
    lane.perm('KitA', 'allium.kit.bypass.cooldown', False)
    # Paid kit denied
    a.command('kit vipkit', 'paid kits are disabled until economy reservations exist')
    require(lane.amount('KitA', 'NETHERITE_INGOT') == 0 and not any(u['kit_name'] == 'vipkit' for u in lane.db('KitA')['usages']),
            lane.name + ': paid kit denied without delivering items or recording usage')


def test_full_inventory_and_cancelled_event(lane):
    a = lane.bots['KitA']
    lane.perm('KitA', 'allium.kits.slowkit', True)
    # Fill inventory with only 1 free slot (slowkit requires 2 slots: IRON_INGOT 5 + GOLD_INGOT 3)
    lane.fixture('room KitA 1')
    a.command('kit slowkit', 'inventory cannot hold all items from kit slowkit; nothing was given')
    window(.3)
    require(lane.amount('KitA', 'IRON_INGOT') == 0 and lane.amount('KitA', 'GOLD_INGOT') == 0
            and not any(u['kit_name'] == 'slowkit' for u in lane.db('KitA')['usages'])
            and not lane.db('KitA')['deliveries'],
            lane.name + ': partial-room inventory refuses entire kit without burning cooldown or one-time status')
    # Also test cancelled ItemGiveEvent
    lane.fixture('empty KitA')
    lane.fixture('cancel KitA on')
    a.command('kit slowkit', 'Kit delivery is unavailable right now; nothing was given or consumed')
    window(.3)
    require(lane.amount('KitA', 'IRON_INGOT') == 0 and not any(u['kit_name'] == 'slowkit' for u in lane.db('KitA')['usages']),
            lane.name + ': cancelled ItemGiveEvent releases claim without burning cooldown or one-time status')
    lane.fixture('cancel KitA off')


def test_provider_and_signed_items_createkit_delkit(lane):
    a, b = lane.bots['KitA'], lane.bots['KitB']
    lane.perm('KitA', 'allium.createkit', True)
    lane.perm('KitA', 'allium.delkit', True)
    # 1. Path traversal / invalid name rejected by /createkit
    a.command('createkit ../x', 'Use 1-32 lowercase letters, digits, underscores or hyphens')
    # 2. Lossy item (written book in slot 3) refused naming slot and unsupported data
    lane.fixture('givelossy KitA')
    a.command('createkit lossykit', 'slot 3 contains unsupported item data (written book data')
    require('lossykit' not in lane.fixture('defs')['definitions']
            and not (lane.path / 'plugins/Allium/kits/lossykit.yml').exists(),
            lane.name + ': /createkit refuses lossy written book without saving degraded kit')
    # 3. Put test-adapter nexo:ruby_sword, signed privileged allium:phantom_obliterator, and GOLDEN_APPLE x3 into KitA's inventory
    lane.fixture('givecustom KitA')
    source_stacks = lane.state('KitA')['stacks']
    source_obliterator = next(s for s in source_stacks if s['behavior'] == 'allium:phantom_obliterator')
    # Without allium.give.privileged, capturing privileged allium:phantom_obliterator is refused
    lane.perm('KitA', 'allium.give.privileged', False)
    a.command('createkit customgear 120', 'capturing privileged items requires allium.give.privileged')
    require('customgear' not in lane.fixture('defs')['definitions'],
            lane.name + ': /createkit refuses privileged behavior without allium.give.privileged')
    # Grant allium.give.privileged and capture customgear
    lane.perm('KitA', 'allium.give.privileged', True)
    a.command('createkit customgear 120', 'Created kit customgear with 3 item stack(s)')
    defs = lane.fixture('defs')['definitions']
    require('customgear' in defs and defs['customgear']['cooldownSeconds'] == 120
            and defs['customgear']['cost'] == '0.00'
            and any('nexo:ruby_sword' in item for item in defs['customgear']['items'])
            and any('allium:phantom_obliterator' in item for item in defs['customgear']['items']),
            lane.name + ': /createkit captures provider test-adapter and signed privileged Allium item specs with decimal cost 0.00',
            defs.get('customgear'))
    # Claim customgear as KitB
    lane.fixture('empty KitB')
    lane.perm('KitB', 'allium.kits.customgear', True)
    b.command('kit customgear', 'Received kit customgear')
    received = lane.state('KitB')['stacks']
    nexo_item = next((s for s in received if s['provider'] == 'nexo' and s['itemId'] == 'ruby_sword'), None)
    signed_item = next((s for s in received if s['behavior'] == 'allium:phantom_obliterator'), None)
    gapple = next((s for s in received if s['material'] == 'GOLDEN_APPLE' and s['amount'] == 3), None)
    require(nexo_item is not None and signed_item is not None and gapple is not None
            and bool(signed_item['signature']) and bool(signed_item['instance_uuid'])
            and signed_item['instance_uuid'] != source_obliterator['instance_uuid'],
            lane.name + ': claiming kit mints provider test-adapter item and fresh signed privileged Allium item instance',
            received)
    # Verify /allium reload preserves definitions and service instance
    logmark = len(lane.logs)
    lane.console('allium reload')
    wait(lambda: any('Configuration reloaded.' in re.sub('\u00a7.', '', s) for s in lane.logs[logmark:]), 'reload ack')
    require(lane.fixture('defs')['sameService'] and 'customgear' in lane.fixture('defs')['definitions'],
            lane.name + ': /allium reload keeps KitService and reloads customgear.yml')
    # Test /delkit
    a.command('delkit customgear', 'Deleted kit customgear')
    require('customgear' not in lane.fixture('defs')['definitions']
            and not (lane.path / 'plugins/Allium/kits/customgear.yml').exists(),
            lane.name + ': /delkit removes kit definition from memory and disk')


def test_permissions_and_tab_completion(lane):
    a, b = lane.bots['KitA'], lane.bots['KitB']
    # KitB has starter permission, not haltbefore, and vipkit is paid
    lane.perm('KitB', 'allium.kits.starter', True)
    lane.perm('KitB', 'allium.kits.vipkit', True)
    lane.perm('KitB', 'allium.kits.haltbefore', False)
    matches, _ = b.tab('kit ')
    require('starter' in matches and 'haltbefore' not in matches and 'vipkit' not in matches,
            lane.name + ': /kit tab completion offers permitted free kits and hides unpermitted or paid kits', matches)
    # Admin /delkit tab completion offers all defined kits (including paid vipkit)
    admin_matches, _ = a.tab('delkit ')
    require({'starter', 'oncekit', 'vipkit', 'slowkit'}.issubset(set(admin_matches)),
            lane.name + ': /delkit tab completion offers all admin kit names', admin_matches)
    # Non-admin /delkit tab completion returns empty
    non_admin_matches, _ = b.tab('delkit ')
    require(not non_admin_matches, lane.name + ': unpermitted player receives no /delkit tab suggestions', non_admin_matches)


def test_disconnect_mid_delivery(lane):
    a = lane.bots['KitA']
    lane.fixture('empty KitA')
    lane.perm('KitA', 'allium.kits.slowkit', True)
    lane.fixture('arm kit.claim.slow 1')
    a.cmd('kit slowkit')
    wait(lambda: lane.fixture('fault kit.claim.slow')['tripped'] == 1, 'slow kit claim tripped')
    # Disconnect while CLAIMING row is in flight
    a.disconnect()
    a.connect()
    window(4.8)
    db_after = lane.db('KitA')
    require(lane.amount('KitA', 'IRON_INGOT') == 0 and not db_after['deliveries']
            and not any(u['kit_name'] == 'slowkit' for u in db_after['usages']),
            lane.name + ': disconnect mid-delivery releases CLAIMING row without delivering items or burning cooldown/one-time',
            db_after)
    # Re-grant permission after reconnect and claim slowkit cleanly in the new session
    lane.perm('KitA', 'allium.kits.slowkit', True)
    a.command('kit slowkit', 'Received kit slowkit')
    wait(lambda: any(u['kit_name'] == 'slowkit' and u['one_time_claimed'] for u in lane.db('KitA')['usages']),
         'slowkit claimed after reconnect')
    require(lane.amount('KitA', 'IRON_INGOT') == 5 and lane.amount('KitA', 'GOLD_INGOT') == 3,
            lane.name + ': new session claims released kit exactly once')


def test_crash_recovery(lane):
    def reconnect():
        lane.boot()
        lane.bots['KitA'].connect()
        lane.bots['KitB'].connect()

    a = lane.bots['KitA']
    # 1. Crash before inventory delivery (after SQL beginClaim)
    lane.fixture('empty KitA')
    lane.perm('KitA', 'allium.kits.haltbefore', True)
    lane.fixture('arm kit.claim.halt-before-deliver 1')
    a.cmd('kit haltbefore')
    lane.crash('kit.claim.halt-before-deliver', 'haltbefore', player='KitA', expected_material='minecraft:diamond', expected_count=0)
    reconnect()
    lane.perm('KitA', 'allium.kits.haltbefore', True)
    wait(lambda: not lane.db('KitA')['deliveries'], 'unreceipted kit claim released on join')
    require(lane.amount('KitA', 'DIAMOND') == 0 and not any(u['kit_name'] == 'haltbefore' for u in lane.db('KitA')['usages']),
            lane.name + ': crash before delivery releases claim and preserves cooldown/one-time status')
    a.command('kit haltbefore', 'Received kit haltbefore')
    wait(lambda: any(u['kit_name'] == 'haltbefore' and u['one_time_claimed'] for u in lane.db('KitA')['usages']),
         'haltbefore claimed after recovery')
    require(lane.amount('KitA', 'DIAMOND') == 6, lane.name + ': pre-delivery crash kit claimed cleanly after restart')

    # 2. Crash after inventory delivery + player.saveData() (before SQL finishClaim)
    lane.fixture('empty KitA')
    lane.perm('KitA', 'allium.kits.haltafter', True)
    lane.fixture('arm kit.claim.halt-after-deliver 1')
    a.cmd('kit haltafter')
    lane.crash('kit.claim.halt-after-deliver', 'haltafter', player='KitA', expected_material='minecraft:emerald', expected_count=7)
    reconnect()
    lane.perm('KitA', 'allium.kits.haltafter', True)
    wait(lambda: not lane.db('KitA')['deliveries'] and any(u['kit_name'] == 'haltafter' and u['one_time_claimed'] for u in lane.db('KitA')['usages']),
         'delivered kit receipt finalized on join')
    require(lane.amount('KitA', 'EMERALD') == 7 and not lane.state('KitA')['receipts']
            and any(r['kit_name'] == 'haltafter' for r in lane.db('KitA')['receipts']),
            lane.name + ': crash after delivery finalizes DB usage from player.dat receipt without redelivering items',
            lane.db('KitA'))
    # Repeated claim after recovery is denied as already claimed
    a.command('kit haltafter', 'already claimed one-time kit haltafter')
    require(lane.amount('KitA', 'EMERALD') == 7, lane.name + ': post-crash repeated claim cannot duplicate items')


def test_shared_mysql_ownership(lane_a, lane_b):
    a_on_1 = lane_a.bot('KitA')
    lane_a.fixture('empty KitA')
    lane_a.perm('KitA', 'allium.kits.haltbefore', True)
    lane_a.fixture('arm kit.claim.halt-before-deliver 1')
    a_on_1.cmd('kit haltbefore')
    lane_a.crash('kit.claim.halt-before-deliver', 'haltbefore', player='KitA', expected_material='minecraft:diamond', expected_count=0)

    # Now KitA connects to Server B (server-b), which shares the same MySQL database
    a_on_2 = lane_b.bot('KitA')
    db_on_b = lane_b.db('KitA')
    require(len(db_on_b['deliveries']) == 1 and db_on_b['deliveries'][0]['claim_owner'] == 'server-a'
            and not db_on_b['usages'],
            'shared-mysql: server-b join recovery leaves server-a CLAIMING row untouched',
            db_on_b)
    row = db_on_b['deliveries'][0]
    foreign = lane_b.fixture(f"foreigncheck {row['id']} {row['claim_token']}")
    require(foreign['serverId'] == 'server-b' and not foreign['releaseAllowed'] and not foreign['finishAllowed']
            and len(lane_b.db('KitA')['deliveries']) == 1,
            'shared-mysql: server-b repository releaseClaim/finishClaim refuses server-a CLAIMING row',
            foreign)
    a_on_2.disconnect()

    # Reboot Server A (server-a) and reconnect KitA: server-a releases its own CLAIMING row and claims cleanly
    lane_a.boot()
    a_on_1 = lane_a.bot('KitA')
    wait(lambda: not lane_a.db('KitA')['deliveries'], 'server-a released its own CLAIMING row on rejoin')
    lane_a.perm('KitA', 'allium.kits.haltbefore', True)
    a_on_1.command('kit haltbefore', 'Received kit haltbefore')
    wait(lambda: any(u['kit_name'] == 'haltbefore' and u['one_time_claimed'] for u in lane_a.db('KitA')['usages']),
         'haltbefore claimed on server-a after recovery')
    require(lane_a.amount('KitA', 'DIAMOND') == 6, 'shared-mysql: owning server-a recovers and delivers kit once')


try:
    build()
    if A.suite == 'shared-mysql':
        db_name = 'allium_s16_shared_' + str(int(time.time()))
        prepare_mysql_database(db_name)
        lane_a = Lane('shared-a', 'spigot', 25760, database=db_name, server_id='server-a')
        lane_b = Lane('shared-b', 'spigot', 25762, database=db_name, server_id='server-b')
        lane_a.boot()
        lane_b.boot()
        case('shared-mysql:cross-server-ownership-isolation', lambda: test_shared_mysql_ownership(lane_a, lane_b))
    else:
        port = 25760 if A.suite == 'spigot' else 25761
        lane = Lane(A.suite, A.suite, port)
        lane.boot()
        lane.bot('KitA')
        lane.bot('KitB')
        case(A.suite + ':claim-cooldown-and-others', lambda: test_claim_and_cooldown(lane))
        case(A.suite + ':one-time-and-paid-denial', lambda: test_one_time_and_paid_denial(lane))
        case(A.suite + ':full-inventory-and-cancelled-event', lambda: test_full_inventory_and_cancelled_event(lane))
        case(A.suite + ':provider-and-signed-items-createkit-delkit', lambda: test_provider_and_signed_items_createkit_delkit(lane))
        case(A.suite + ':permissions-and-tab-completion', lambda: test_permissions_and_tab_completion(lane))
        case(A.suite + ':disconnect-mid-delivery', lambda: test_disconnect_mid_delivery(lane))
        case(A.suite + ':crash-mid-delivery-recovery', lambda: test_crash_recovery(lane))
finally:
    cleanup_errors = []
    for l in lanes:
        try:
            l.stop()
        except Exception as e:
            cleanup_errors.append(l.name + ': ' + str(e))
    for p, digest in SOURCE_HASHES.items():
        if hashlib.sha256(Path(p).read_bytes()).hexdigest() != digest:
            cleanup_errors.append('source modified during run: ' + p)
    if cleanup_errors:
        require(False, 'clean shutdown and immutable harness verification', cleanup_errors)
    else:
        require(True, 'clean shutdown and immutable harness verification')
    summary = dict(
        suite=A.suite,
        runName=A.run_name,
        jarSha256=A.sha256,
        passed=not failures,
        totalChecks=len(checks),
        failedChecks=len(failures),
        failures=failures,
        scopes=scopes,
        durationSeconds=round(time.time() - started, 3),
        observations=observations,
        checks=checks,
    )
    (RUN / 'summary.json').write_text(json.dumps(summary, indent=2))
    emit(f"SUMMARY suite={A.suite} passed={summary['passed']} checks={len(checks)} failures={len(failures)}")
    if failures:
        sys.exit(1)
