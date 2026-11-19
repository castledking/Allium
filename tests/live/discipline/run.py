#!/usr/bin/env python3
"""S19 discipline gate: real protocol commands, three clients and console; disposable loopback lanes."""
import argparse
import fcntl
import hashlib
import gzip
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
import zipfile
import yaml

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
sys.dont_write_bytecode = True
sys.path.insert(0, str(HERE))
from process_cleanup import stop_process

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['spigot', 'paper', 'mariadb'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='parent-confirmed frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--check', action='store_true')
P.add_argument('--harness-manifest', type=Path, default=HERE / 'evidence/harness-freeze.json')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261114/s19-discipline/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s19-discipline/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s19-discipline/runs')
A.allium_jar = A.allium_jar.resolve()
if not re.fullmatch('[0-9a-f]{64}', A.sha256) or hashlib.sha256(A.allium_jar.read_bytes()).hexdigest() != A.sha256:
    P.error('frozen jar SHA256 mismatch')
API = INFRA / 'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP = INFRA / 'client/node_modules/minecraft-protocol'
for p in [API, MCP / 'package.json', INFRA / 'servers/spigot-1.20.1.jar', A.paper_template / 'versions/26.3/paper-26.3.jar']:
    if not p.is_file():
        P.error('missing input ' + str(p))

# Verify the reviewable source freeze before creating any run or starting a process.
SOURCE_FILES = [HERE / f for f in ['run.py','plugin/gate/DisciplineGate.java','plugin/plugin.yml',
                                  'client-mcp.cjs','client-raw26.cjs','text-components.cjs','process_cleanup.py']]
SOURCE_HASHES = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE_FILES}
FREEZE_SHA256=hashlib.sha256(A.harness_manifest.read_bytes()).hexdigest()
frozen = json.loads(A.harness_manifest.read_text())
if frozen.get('sourceHashes') != SOURCE_HASHES:
    P.error('harness differs from source freeze; re-review and freeze before RUN')

def guard_port(port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1',port))
        sock.listen(1)

A.run_root.parent.mkdir(parents=True, exist_ok=True)
lock = open(str(A.run_root.parent / 'ports.lock'), 'a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
for port in (25790, 25791, 25792):
    guard_port(port)
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25790-25792 free and gate lock held', flush=True)
if A.check:
    sys.exit(0)

RUN = A.run_root / A.run_name
RUN.mkdir(parents=True, exist_ok=False)
shutil.copy2(A.allium_jar, RUN / 'Allium-under-test.jar')
JAR = RUN / 'Allium-under-test.jar'
assert hashlib.sha256(JAR.read_bytes()).hexdigest() == A.sha256
checks, failures, observations, scopes = [], [], {}, []
lanes = []
processes = []
integrity = []
HELPER_HASHES = {}
mariadb_owned = False
mariadb_password = None
for source in SOURCE_FILES:
    dest = RUN / 'harness' / source.relative_to(REPO)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, dest)
    os.chmod(dest, 0o444)
shutil.copy2(A.harness_manifest, RUN / 'harness-freeze.json')
os.chmod(RUN/'harness-freeze.json',0o444)
HERE = RUN / 'harness/tests/live/discipline'
started = time.time()

MARIADB_CONTAINER = 'allium-gate-mariadb'
MARIADB_PORT = 25734
MARIADB_DB = 'allium_live_s19_' + hashlib.sha256(A.run_name.encode()).hexdigest()[:12]


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


def track_process(proc, role, lane=None, name=None, log=None, lines=None):
    record=dict(role=role,lane=lane,name=name,pid=proc.pid,startedAt=time.time(),exitCode=None,
                intentional=False,shutdown=None,log=str(log) if log else None)
    processes.append(record)
    proc._gate_record=record
    proc._gate_lines=lines if lines is not None else []
    proc._gate_line_start=len(proc._gate_lines)
    proc._gate_kick_proof=None
    proc._gate_pump=None
    return proc


def finish_process(proc, intentional=False):
    if proc is None: return
    record=proc._gate_record
    if record.get('finishedAt') is not None: return
    if proc.poll() is None:
        require(False,'owned process still running at exit capture',record)
        return
    if proc._gate_pump:
        proc._gate_pump.join(timeout=10)
        require(not proc._gate_pump.is_alive(),'process log drained before exit capture',dict(pid=proc.pid,role=record['role']))
    lines=proc._gate_lines[proc._gate_line_start:]
    transports=[]
    for line in lines:
        if line.startswith('DISC-GTRANSPORT '):
            try: transports.append(json.loads(line.split(' ',1)[1]))
            except ValueError: pass
    # Only a decoded server kick followed by Node ECONNRESET/EPIPE may explain exit 1.
    transport_exception=(record['role']=='client' and intentional and proc.returncode==1
                         and proc._gate_kick_proof is not None and transports
                         and all(t.get('code') in ['ECONNRESET','EPIPE'] for t in transports)
                         and not any('DISC-GCLIENTERROR' in line for line in lines))
    record.update(finishedAt=time.time(),exitCode=proc.returncode,intentional=intentional,
                  shutdown=getattr(proc,'_gate_shutdown',None),kickProof=proc._gate_kick_proof,
                  transport=transports,acceptedTransportException=bool(transport_exception))
    require(proc.returncode==0 or transport_exception,'owned '+record['role']+' exit status accepted',dict(record))
    require(not record['shutdown'] or not(record['shutdown']['terminated'] or record['shutdown']['killed']),
            'owned '+record['role']+' required no forced shutdown',dict(pid=proc.pid,shutdown=record['shutdown']))


def run_tracked(argv, role):
    # Store only a purpose label; database argv can contain credentials.
    proc=track_process(subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True),role)
    output,_=proc.communicate()
    finish_process(proc,intentional=True)
    return proc.returncode,output


def verify_integrity(stage, lane=None):
    checked=[]
    def verify(path,expected,readonly=False):
        try:
            actual=hashlib.sha256(path.read_bytes()).hexdigest()
            mode_ok=not readonly or path.stat().st_mode & 0o222 == 0
            ok=actual==expected and mode_ok
        except OSError:
            actual=None; mode_ok=False; ok=False
        checked.append(dict(path=str(path),expected=expected,actual=actual,readOnly=mode_ok if readonly else None,passed=ok))
    verify(JAR,A.sha256)
    verify(RUN/'harness-freeze.json',FREEZE_SHA256,True)
    verify(A.allium_jar,A.sha256)
    for relative,expected in SOURCE_HASHES.items(): verify(RUN/'harness'/relative,expected,True)
    for relative,expected in HELPER_HASHES.items(): verify(RUN/relative,expected)
    if lane:
        verify(lane.path/'plugins/Allium.jar',A.sha256)
        verify(lane.path/'plugins/DisciplineGate.jar',HELPER_HASHES['helper/DisciplineGate.jar'])
        with zipfile.ZipFile(lane.path/'plugins/DisciplineGate.jar') as jar:
            for relative,expected in HELPER_HASHES.items():
                if relative.startswith('helper/classes/') and relative.endswith('.class'):
                    entry=relative.removeprefix('helper/classes/')
                    actual=hashlib.sha256(jar.read(entry)).hexdigest()
                    checked.append(dict(path=str(lane.path/'plugins/DisciplineGate.jar')+'!/'+entry,expected=expected,actual=actual,passed=actual==expected))
    record=dict(stage=stage,lane=lane.name if lane else None,checks=checked,passed=all(c['passed'] for c in checked))
    integrity.append(record)
    require(record['passed'],'frozen jar, read-only harness and helper integrity: '+stage,record)


def final_log_scan():
    # Run after every owned process exits and its output-pump thread joins. No exit-0 exemption.
    pattern=re.compile(r'allium.{0,100}task.{0,50}fail|scheduler|callback|could not pass event|\bSEVERE\b|decoder|client.?error|\bERROR\b|error processing packet',re.I)
    findings=[]
    exemptions=[]
    scanned=[]
    paths=set(RUN.glob('*-server.log')) | set(RUN.glob('*-client-*.log'))
    for lane in lanes:
        paths.update(lane.path.rglob('*.log'))
        paths.update(lane.path.rglob('*.log.gz'))
    for path in sorted(paths):
        scanned.append(str(path))
        content=gzip.open(path,'rt',errors='replace').read() if path.suffix=='.gz' else path.read_text(errors='replace')
        for number,line in enumerate(content.splitlines(),1):
            if re.search(r'\[.*INFO\].*\[ChunkHolderManager\] Halted I/O scheduler for world ', line):
                exemptions.append(dict(path=str(path), line=number, text=line, rule='normal Paper INFO shutdown acknowledgement'))
                continue
            if pattern.search(line):
                record=next((p for p in processes if p['log']==str(path)),None)
                precise_transport=record and record.get('acceptedTransportException') and re.fullmatch(r'Error: (?:read|write) (?:ECONNRESET|EPIPE)',line)
                entry=dict(path=str(path),line=number,text=line)
                if precise_transport: exemptions.append(dict(entry,rule='decoded kick then Node read/write ECONNRESET/EPIPE, exit 1'))
                else: findings.append(entry)
    observations['postShutdownLogScan']=dict(files=scanned,pattern=pattern.pattern,findings=findings,preciseTransportExemptions=exemptions)
    require(not findings,'post-shutdown ALL server/client logs contain no task/scheduler/callback/event/severe/decoder/client errors',findings)


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
            if bot.proc is not None and not bot.expected_exit and bot.proc.poll() is not None:
                raise RuntimeError(f'{lane.name}/{bot.name} client unexpected exit {bot.proc.returncode}')


def prepare_mariadb_database():
    global mariadb_owned, mariadb_password
    env = dict(line.split('=', 1) for line in (INFRA / 'mysql.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    mariadb_password = shlex.split(env['MYSQL_ROOT_PASSWORD'])[0]
    code,output=run_tracked(['docker','inspect','--format','{{.State.Running}}',MARIADB_CONTAINER],'mariadb-container-status')
    if code or output.strip()!='true':
        raise RuntimeError('Shared MariaDB container must already be running; coordinate with parent (no lifecycle mutation)')
    code,_=run_tracked(['docker','exec','-e',f'MYSQL_PWD={mariadb_password}',MARIADB_CONTAINER,
                        'mariadb','-uroot','-e',f'CREATE DATABASE {MARIADB_DB};'],'mariadb-create-owned-database')
    if code: raise RuntimeError('Could not create unique S19 database (credentials redacted)')
    mariadb_owned=True
    return mariadb_password


def query_mariadb(password, sql):
    code,output=run_tracked(['docker','exec','-e',f'MYSQL_PWD={password}',MARIADB_CONTAINER,
                             'mariadb','-uroot','-N','-B',MARIADB_DB,'-e',sql],'mariadb-read-only-observation')
    if code: raise RuntimeError('MariaDB read-only observation failed (credentials redacted)')
    return [line.split('\t') for line in output.splitlines() if line.strip()]


def capture_and_drop_mariadb():
    if not mariadb_owned: return
    # The name is deterministic for THIS run and was created successfully by THIS process.
    if not re.fullmatch(r'allium_live_s19_[0-9a-f]{12}',MARIADB_DB):
        require(False,'refuse unsafe database cleanup name')
        return
    capture=dict(database=MARIADB_DB,tables={},errors=[])
    for table in ['allium_bans','allium_freezes','allium_jails','allium_jailed','allium_warnings','allium_staff_notes','allium_moderation_audit']:
        try: capture['tables'][table]=query_mariadb(mariadb_password,'SELECT * FROM '+table+' ORDER BY 1')
        except Exception as error: capture['errors'].append(dict(table=table,error=str(error)))
    (RUN/'mariadb-final-capture.json').write_text(json.dumps(capture,indent=2)+'\n')
    code,_=run_tracked(['docker','exec','-e',f'MYSQL_PWD={mariadb_password}',MARIADB_CONTAINER,
                        'mariadb','-uroot','-e',f'DROP DATABASE {MARIADB_DB};'],'mariadb-drop-owned-database')
    dropped=code==0
    require(dropped,'drop only this run owned MariaDB database after capture',dict(database=MARIADB_DB,capture=str(RUN/'mariadb-final-capture.json')))
    code,output=run_tracked(['docker','inspect','--format','{{.State.Running}}',MARIADB_CONTAINER],'mariadb-shared-container-still-running')
    require(code==0 and output.strip()=='true','shared MariaDB container remains running')
    observations['mariadbCleanup']=dict(database=MARIADB_DB,dropped=dropped,capture=str(RUN/'mariadb-final-capture.json'))


def window(seconds=.4):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    code,output=run_tracked([
        '/usr/lib/jvm/java-17-openjdk/bin/javac','--release','17','-cp',f'{API}:{JAR}',
        '-d',str(classes),str(HERE/'plugin/gate/DisciplineGate.java')],'fixture-javac')
    (RUN/'helper/javac.log').write_text(output)
    if code: raise RuntimeError('fixture compilation failed; see helper/javac.log')
    shutil.copy2(HERE/'plugin/plugin.yml',classes/'plugin.yml')
    code,output=run_tracked(['/usr/lib/jvm/java-17-openjdk/bin/jar','cf',str(RUN/'helper/DisciplineGate.jar'),'-C',str(classes),'.'],'fixture-jar')
    (RUN/'helper/jar.log').write_text(output)
    if code: raise RuntimeError('fixture packaging failed; see helper/jar.log')
    for path in [RUN/'helper/DisciplineGate.jar',*sorted(classes.rglob('*.class'))]:
        HELPER_HASHES[str(path.relative_to(RUN))]=hashlib.sha256(path.read_bytes()).hexdigest()
    verify_integrity('after-helper-build')



class Bot:
    def __init__(self, lane, name):
        self.lane, self.name, self.proc, self.lines = lane, name, None, []
        self.last = 0
        self.expected_exit = False

    def connect(self, deny=None):
        if self.proc is not None: raise RuntimeError('client process must be reaped before reconnect')
        self.expected_exit = deny is not None
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
        count=sum(p['role']=='client' and p['name']==self.name for p in processes)+1
        log=RUN/f'{self.lane.name}-{self.name}-client-{count}.log'
        track_process(self.proc,'client',self.lane.name,self.name,log,self.lines)
        self.proc._gate_pump=threading.Thread(target=pump,args=(self.proc,log,self.lines),daemon=True)
        self.proc._gate_pump.start()
        if deny is not None:
            self.kicked(start, deny)
            require(not any('BASEG-WIRE READY' in s for s in self.lines[start:]), self.lane.name+': banned login never reaches play READY')
            self.reap()
            return start
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

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('BASEG-CHAT ')]

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def command(self, text, expected):
        start = self.cmd(text)
        return self.chat(start, expected)

    def kicked(self, start, fragment):
        packet = wait(lambda: next((json.loads(s.split(' ',1)[1]) for s in self.lines[start:] if s.startswith('DISC-GKICK ') and fragment in json.loads(s.split(' ',1)[1])['plain']), None), self.name+' kick packet '+fragment)
        self.proc._gate_kick_proof=packet
        require(bool(packet), self.lane.name+': '+self.name+' actual kick packet contains '+fragment, packet)
        wait(lambda: self.proc.poll() is not None, self.name+' disconnect after kick', 15)
        require(not any('DISC-GCLIENTERROR ' in s for s in self.lines[start:]), self.lane.name+': rejected/kicked client has no protocol parser error')
        return packet

    def reap(self):
        if self.proc:
            self.proc.wait(timeout=15)
            finish_process(self.proc,intentional=True)
            if self.proc.stdin: self.proc.stdin.close()
            self.proc = None
        self.expected_exit = False
        wait(lambda: self.name not in self.lane.fixture('online')['players'], self.name+' observed offline')

    def move(self, dx, dz):
        start = len(self.lines)
        self.write(f'move {dx} {dz}')
        wait(lambda: any(s.startswith('DISC-GMOVED ') for s in self.lines[start:]), self.name+' movement packet')

    def look(self, yaw, pitch):
        start = len(self.lines)
        self.write(f'look {yaw} {pitch}')
        wait(lambda: any(s.startswith('DISC-GLOOK ') for s in self.lines[start:]), self.name+' look packet')

    def disconnect(self):
        if self.proc is None:
            return
        if self.expected_exit and self.proc.poll() is not None:
            self.reap()
            return
        proc = self.proc
        self.proc = None
        try:
            stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=15)
        finally:
            finish_process(proc,intentional=True)
        if self.lane.proc is not None and self.lane.proc.poll() is None:
            wait(lambda: self.name not in self.lane.fixture('online')['players'], self.name + ' server observed offline', 10)


class Lane:
    def __init__(self, name, kind, port, mariadb_password=None):
        self.name, self.kind, self.port = name, kind, port
        self.mariadb_password = mariadb_password
        self.path = RUN / name
        self.proc, self.logs, self.bots = None, [], {}
        self.path.mkdir()
        (self.path / 'banned-players.json').write_text(json.dumps([dict(
            uuid='f0000000-0000-4000-8000-000000000001', name='ForeignNative',
            created='2026-11-14 00:00:00 +0000', source='NativeFixture', expires='forever', reason='native-only')]))
        (self.path / 'plugins/Allium').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/DisciplineGate.jar', self.path / 'plugins/DisciplineGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        for m in ['moderation', 'teleport']:
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
        guard_port(self.port)
        verify_integrity('before-boot-'+str(1+sum(p['role']=='server' for p in processes)),self)
        self.proc = subprocess.Popen(self.argv(), cwd=self.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        log=RUN/(self.name+'-server.log')
        track_process(self.proc,'server',self.name,log=log,lines=self.logs)
        self.proc._gate_pump=threading.Thread(target=pump,args=(self.proc,log,self.logs),daemon=True)
        self.proc._gate_pump.start()
        wait(lambda: any('Done (' in s for s in self.logs[start:]), self.name + ' startup', 600)
        expected_db = 'Database connected (MYSQL)' if self.mariadb_password is not None else 'Database connected (SQLITE)'
        require(any(expected_db in s for s in self.logs[start:])
                and any('Moderation enabled' in s for s in self.logs[start:])
                and any('S19G-GATE READY' in s for s in self.logs[start:]),
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
        self.console('s19g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S19G-DONE ' + s + ' => ') in v or ('S19G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S19G-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def bot(self, name):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect()
        return b

    def perm(self, name, node, value, sync=True):
        cmd = 'perm '
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
            finally:
                finish_process(proc,intentional=True)
        verify_integrity('after-stop',self)
        if errors:
            require(False, self.name+': graceful cleanup', errors)


def case(label, fn):
    emit('CASE ' + label)
    first = len(failures)
    scope = dict(label=label, status='RUNNING')
    scopes.append(scope)
    try:
        fn()
    except Exception:
        (RUN / 'exceptions.log').open('a').write(label + '\n' + traceback.format_exc() + '\n')
        require(False, label + ': exception (see exceptions.log)')
    scope['status'] = 'PASS' if len(failures) == first else 'FAIL'


def sql(lane, query):
    if not re.match(r'^\s*SELECT\b', query, re.I):
        raise ValueError('observations are SELECT only')
    if lane.mariadb_password:
        return query_mariadb(lane.mariadb_password, query)
    with sqlite3.connect('file:' + str(lane.path / 'plugins/Allium/allium.db') + '?mode=ro', uri=True) as db:
        return [[str(v) if v is not None else 'NULL' for v in row] for row in db.execute(query)]


def state(lane, name):
    return lane.fixture('state ' + name)


def settled(lane, name):
    return wait(lambda: (s if s['pendingWrites'] == 0 else None) if (s := state(lane, name)) else None, name + ' pending writes drained')


def ready(lane, name):
    return wait(lambda: (s if s['ready'] else None) if (s := state(lane, name)) else None, name + ' discipline ready', 25)


def command(bot, text, expected):
    reply = bot.command(text, expected)
    require(expected in reply['plain'], bot.lane.name + ': /' + text + ' => ' + expected)
    return reply


def console_command(lane, text, expected):
    start = len(lane.logs)
    lane.console(text)
    found = wait(lambda: next((s.strip() for s in lane.logs[start:] if expected in s), None), 'console /' + text)
    require(bool(found), lane.name + ': console /' + text + ' => ' + expected)
    return found


ACTIONS = ['kick','ban','tempban','unban','banlist','ipban','freeze','setjail','jail','unjail','warn','notes']


def grants(lane, staff=True):
    for name in lane.fixture('online')['players']:
        lane.perm(name, 'allium.moderation.bypass', False)
        lane.perm(name, 'allium.moderation.exempt', False)
    if staff:
        for action in ACTIONS:
            lane.perm('DiscStaff', 'allium.'+action, True)
        lane.perm('DiscStaff','minecraft.command.teleport',True)


def prepare(lane, name, x, z):
    require(lane.fixture(f'prep {name} {x} {z}')['teleported'], lane.name+': prepare terrain/position '+name)
    window(.25)
    return state(lane,name)


def same_place(a,b):
    return a.get('world') == b.get('world') and all(abs(a[k]-b[k]) < .06 for k in ['x','y','z'])


def movement(lane, bot, blocked):
    before = state(lane, bot.name)
    bot.move(.6,0)
    window(.45)
    after = state(lane,bot.name)
    require(after['moves'] > before['moves'], lane.name+': server observed '+bot.name+' real movement packet')
    require(same_place(before,after) if blocked else abs(after['x']-before['x']-.6)<.06,
            lane.name+': '+bot.name+' movement '+('blocked' if blocked else 'positive control accepted'),dict(before=before,after=after))
    return after


def escape(lane, staff, target):
    before=state(lane,target.name)
    # Vanilla teleport is intentionally independent of Allium command handlers.
    start=staff.cmd('minecraft:tp '+target.name+' 150 80 150')
    window(.5)
    after=state(lane,target.name)
    require(same_place(before,after) and after['blockedTeleports']>before['blockedTeleports'],
            lane.name+': '+target.name+' actual vanilla-command teleport escape cancelled',dict(before=before,after=after,replies=staff.chats(start)))


def setup(lane):
    lane.boot()
    for name in ['DiscStaff','DiscNorm','DiscObs']:
        lane.bot(name)
        ready(lane,name)
    grants(lane)
    require(any('NATIVE BAN DIVERGENCE: 1 ' in line for line in lane.logs), lane.name + ': startup warns with unknown native ban count')
    require(sql(lane,"SELECT COUNT(*) FROM allium_bans WHERE player_uuid='f0000000-0000-4000-8000-000000000001'")==[['0']],
            lane.name + ': native ban audit never imports entries')
    owners=lane.fixture('owners')
    observations[lane.name+'CommandOwners']=owners
    for name in ACTIONS+['pardon']:
        require('allium' in owners[name].lower(),lane.name+': bare /'+name+' owned by Allium',owners[name])
    for name in ['minecraft:ban','minecraft:kick','minecraft:pardon']:
        require(owners[name] != 'missing' and 'allium' not in owners[name].lower(),lane.name+': /'+name+' remains native',owners[name])
    with zipfile.ZipFile(JAR) as z:
        cfg=yaml.safe_load(z.read('config.yml'))['moderation']['discipline']
        permissions=yaml.safe_load(z.read('plugin.yml'))['permissions']
    require(all(permissions['allium.'+a]['default']=='op' for a in ACTIONS)
            and permissions['allium.moderation.bypass']['default'] is False
            and permissions['allium.moderation.exempt']['default'] is False,
            lane.name+': shipped command permissions OP; bypass/exempt default false')
    require(cfg.get('ban-load-failure-policy')=='DENY' and cfg.get('ip-ban-enabled') is False
            and cfg.get('exempt-permission')=='allium.moderation.exempt' and cfg.get('rate-limit-millis')==1000,
            lane.name+': shipped discipline defaults DENY, IP-off, exempt node, 1000ms rate limit',cfg)
    prepare(lane,'DiscStaff',100.5,100.5)
    prepare(lane,'DiscNorm',120.5,100.5)
    prepare(lane,'DiscObs',140.5,100.5)
    movement(lane,lane.bots['DiscNorm'],False)


def exact_denial(responses, sent):
    texts=[r['plain'] for r in responses]
    if texts==["[Allium] You don't have permission to do that."]: return True
    error='{command.unknown.command}';context=sent+'{command.context.here}'
    if texts==[error,context]: return True
    if texts!=[error+'\n'+context]: return False
    expected={'color':'red','extra':[{'translate':'command.unknown.command'},{'':'\n'},
              {'click_event':{'command':'/'+sent,'action':'suggest_command'},'color':'gray','extra':[
                  {'underlined':1,'color':'red','text':sent},{'italic':1,'color':'red','translate':'command.context.here'}],'text':''}],'text':''}
    data=responses[0];raw=data.get('rawComponent')
    if isinstance(raw,str): raw=json.loads(raw)
    return raw==expected and data['interactive'] is True and data['overlay'] is False


def authorization(lane):
    staff,norm,obs=(lane.bots[n] for n in ['DiscStaff','DiscNorm','DiscObs'])
    lane.perm(norm.name,'allium.warn',False)
    rows_before=sql(lane,'SELECT COUNT(*) FROM allium_warnings')
    marks={b.name:len(b.lines) for b in [staff,norm,obs]}
    sent='warn DiscObs no-permission'
    start=norm.cmd(sent)
    wait(lambda: norm.chats(start),'exact unauthorized command response')
    window(.4)
    responses=norm.chats(start)
    require(exact_denial(responses,sent),lane.name+': ordinary player receives exact permission denial or native unknown-command context',responses)
    require(sql(lane,'SELECT COUNT(*) FROM allium_warnings')==rows_before
            and not any('Warning:' in c['plain'] for b in [staff,obs] for c in b.chats(marks[b.name])),
            lane.name+': unauthorized /warn creates no SQL warning or public/target notice')
    before=sql(lane,'SELECT COUNT(*) FROM allium_warnings')
    command(staff,'warn DiscStaff self-test','moderation hierarchy')
    for node in ['allium.warn','allium.freeze','allium.jail','allium.ban']:
        lane.perm('DiscObs',node,True)
    command(staff,'warn DiscObs equal-test','moderation hierarchy')
    lane.perm('DiscStaff','allium.ban',False)
    lane.perm('DiscStaff','allium.ipban',False)
    command(staff,'warn DiscObs higher-test','moderation hierarchy')
    lane.perm('DiscStaff','allium.ban',True)
    lane.perm('DiscStaff','allium.ipban',True)
    lane.perm('DiscNorm','allium.moderation.exempt',True)
    command(staff,'warn DiscNorm exempt-test','moderation hierarchy')
    lane.perm('DiscNorm','allium.moderation.exempt',False)
    require(sql(lane,'SELECT COUNT(*) FROM allium_warnings')==before,lane.name+': refused self/equal/higher/exempt actions produce no warnings')
    # The same target becomes lower rank and can be moderated.
    lane.perm('DiscObs','allium.ban',False)
    command(staff,'warn DiscObs lower-rank-control','warn: DiscObs.')
    window(1.1)
    burst_before=sql(lane,"SELECT COUNT(*) FROM allium_warnings WHERE player_name='DiscObs'")
    burst_start=len(staff.lines)
    staff.write('cmd warn DiscObs burst-one')
    staff.write('cmd warn DiscObs burst-two')
    staff.chat(burst_start,'rate limited')
    staff.chat(burst_start,'warn: DiscObs.')
    settled(lane,obs.name)
    require(int(sql(lane,"SELECT COUNT(*) FROM allium_warnings WHERE player_name='DiscObs'")[0][0])==int(burst_before[0][0])+1,
            lane.name+': burst real commands rate-limit one action without duplicate mutation')
    for node in ['allium.warn','allium.freeze','allium.jail']:
        lane.perm('DiscObs',node,False)
    command(staff,'ipban DiscNorm disabled-control','IP bans are disabled')
    require(sql(lane,'SELECT COUNT(*) FROM allium_bans')==[['0']],lane.name+': default /ipban refusal writes no ban')
    command(staff,'tempban DiscNorm 5x malformed','Usage: /tempban')
    command(staff,'jail DiscNorm missing','Jail missing was not found')
    command(staff,'notes DiscNorm add','/notes <player> add <note>')


def history(lane):
    staff,norm=lane.bots['DiscStaff'],lane.bots['DiscNorm']
    reason='S19 &cplain <red>literal</red> #00ff00'
    start=len(norm.lines)
    command(staff,'warn DiscNorm '+reason,'warn: DiscNorm.')
    notice=norm.chat(start,'Warning: '+reason)
    offset=notice['plain'].index(reason)
    colors=notice.get('colors',[])[offset:offset+len(reason)]
    require(not notice.get('interactive') and reason in notice['plain'] and len(set(colors))==1,
            lane.name+': warning reason remains literal untrusted text with one inherited color',notice)
    for form in ['warn DiscNorm history','warn DiscNorm list']:
        listing=command(staff,form,reason)
        require(not listing.get('interactive'),lane.name+': /'+form+' lists literal warning history')
    command(staff,'notes DiscNorm add '+reason,'notes: DiscNorm.')
    for form in ['notes DiscNorm','notes DiscNorm list']:
        listing=command(staff,form,reason)
        require(not listing.get('interactive'),lane.name+': /'+form+' has no interactive markup')
    console_command(lane,'warn DiscNorm console-warning','warn: DiscNorm.')
    console_command(lane,'notes DiscNorm add console-note','notes: DiscNorm.')
    console_command(lane,'warn DiscNorm history','console-warning')
    console_command(lane,'notes DiscNorm','console-note')
    settled(lane,'DiscNorm')
    require(sql(lane,"SELECT reason FROM allium_warnings WHERE player_name='DiscNorm' ORDER BY id")==[[reason],['console-warning']],lane.name+': warning history committed in SQL')
    require(sql(lane,"SELECT reason FROM allium_staff_notes WHERE player_name='DiscNorm' ORDER BY id")==[[reason],['console-note']],lane.name+': staff-note history committed in SQL')
    observations[lane.name+'HistoryReason']=reason


def authoritative_external_ban(lane):
    if lane.mariadb_password:
        return  # This follow-up targets the single-server SQLite deployment.
    norm = lane.bots['DiscNorm']
    player_id = state(lane, norm.name)['uuid']
    norm.disconnect()
    db_path = lane.path / 'plugins/Allium/allium.db'
    with sqlite3.connect(db_path) as external:
        external.execute("INSERT INTO allium_bans (player_uuid,player_name,actor_name,reason,created_at,expires_at,ip_address) VALUES (?,?,?,?,?,NULL,NULL)",
                         (player_id, norm.name, 'ExternalConnection', 'external-connection', int(time.time()*1000)))
    norm.connect(deny='You are banned: external-connection')
    require(True, lane.name + ': authoritative pre-login rejects ban written by second SQLite connection')
    with sqlite3.connect(db_path) as external:
        external.execute('DELETE FROM allium_bans WHERE player_uuid=?', (player_id,))
    norm.connect(); ready(lane, norm.name)
    require(not state(lane,norm.name)['banned'], lane.name + ': authoritative pre-login accepts external unban despite cached ban')


def kicks_and_bans(lane):
    staff,norm=lane.bots['DiscStaff'],lane.bots['DiscNorm']
    start=len(norm.lines); norm.expected_exit=True
    command(staff,'kick DiscNorm S19-kick &cplain','kick: DiscNorm.')
    norm.kicked(start,'You were kicked: S19-kick &cplain'); norm.reap(); norm.connect(); ready(lane,norm.name)
    lane.perm(norm.name,'allium.moderation.bypass',False)
    lane.perm(norm.name,'allium.moderation.exempt',False)
    start=len(norm.lines); norm.expected_exit=True
    console_command(lane,'kick DiscNorm console-kick','kick: DiscNorm.')
    norm.kicked(start,'You were kicked: console-kick'); norm.reap(); norm.connect(); ready(lane,norm.name)
    start=len(norm.lines); norm.expected_exit=True
    # Short but enough time to attempt a rejected login, then prove expiry without /unban.
    command(staff,'tempban DiscNorm 6s timed-expiry','tempban: DiscNorm.')
    norm.kicked(start,'You are banned: timed-expiry'); norm.reap()
    norm.connect(deny='You are banned: timed-expiry')
    window(6.2)
    norm.connect(); ready(lane,norm.name)
    require(not state(lane,norm.name)['banned'],lane.name+': timed ban expires and fresh login succeeds without unban')
    norm.disconnect()
    command(staff,'ban DiscNorm offline-refused','moderation hierarchy')
    require(sql(lane,"SELECT COUNT(*) FROM allium_bans WHERE player_name='DiscNorm' AND reason='offline-refused'")==[['0']],lane.name+': ordinary staff offline mutation fails closed')
    lane.perm(staff.name,'allium.moderation.bypass',True)
    command(staff,'ban DiscNorm offline-bypass','ban: DiscNorm.')
    lane.perm(staff.name,'allium.moderation.bypass',False)
    norm.connect(deny='You are banned: offline-bypass')
    command(staff,'unban DiscNorm','moderation hierarchy')
    console_command(lane,'pardon DiscNorm','unban: DiscNorm.')
    norm.connect(); ready(lane,norm.name)
    lane.perm(norm.name,'allium.moderation.exempt',False)
    start=len(norm.lines); norm.expected_exit=True
    command(staff,'ban DiscNorm S19-online-ban','ban: DiscNorm.')
    norm.kicked(start,'You are banned: S19-online-ban'); norm.reap()
    command(staff,'banlist','S19-online-ban')
    console_command(lane,'banlist','S19-online-ban')
    norm.connect(deny='You are banned: S19-online-ban')
    console_command(lane,'unban DiscNorm','unban: DiscNorm.')
    norm.connect(); ready(lane,norm.name)
    norm.disconnect()
    console_command(lane,'ban DiscNorm S19-console-offline','ban: DiscNorm.')
    norm.connect(deny='You are banned: S19-console-offline')
    console_command(lane,'unban DiscNorm','unban: DiscNorm.')
    norm.connect(); ready(lane,norm.name)


def restraints_and_restart(lane):
    staff,norm,obs=(lane.bots[n] for n in ['DiscStaff','DiscNorm','DiscObs'])
    # Save two jail destinations by actual player commands, never fixture repository writes.
    alpha=prepare(lane,staff.name,100.5,100.5)
    command(staff,'setjail alpha','setjail: alpha.')
    beta=prepare(lane,staff.name,140.5,100.5)
    command(staff,'setjail beta','setjail: beta.')
    origin=prepare(lane,norm.name,120.5,100.5)
    norm.disconnect()
    console_command(lane,'jail DiscNorm alpha no-origin','Moderation data is unavailable')
    require(not state(lane,norm.name)['jailed'],lane.name+': initial offline jail refused without return-location proof')
    norm.connect(); ready(lane,norm.name)
    origin=prepare(lane,norm.name,120.5,100.5)
    command(staff,'freeze DiscNorm on S19-freeze','freeze: DiscNorm.')
    require(state(lane,norm.name)['frozen'],lane.name+': /freeze state published after durable write')
    movement(lane,norm,True); escape(lane,staff,norm)
    norm.look(73,12); window(.4)
    require(abs(state(lane,norm.name)['yaw']-73)<.1,lane.name+': frozen player can look around')
    command(staff,'freeze DiscNorm off','unfreeze: DiscNorm.')
    movement(lane,norm,False)
    origin=state(lane,norm.name)
    command(staff,'jail DiscNorm alpha S19-jail','jail: DiscNorm.')
    jailed=state(lane,norm.name)
    require(jailed['jailed'] and same_place(jailed,alpha),lane.name+': /jail teleports to saved jail',dict(alpha=alpha,actual=jailed))
    movement(lane,norm,True); escape(lane,staff,norm)
    norm.look(91,7); window(.4)
    require(abs(state(lane,norm.name)['yaw']-91)<.1,lane.name+': jailed player can look around')
    command(staff,'jail DiscNorm beta S19-reassigned','jail: DiscNorm.')
    require(same_place(state(lane,norm.name),beta),lane.name+': jail reassignment changes destination')
    command(staff,'unjail DiscNorm','unjail: DiscNorm.')
    require(same_place(state(lane,norm.name),origin),lane.name+': /unjail restores original location after reassignment')
    movement(lane,norm,False)
    # Keep freeze + jail and a different UUID ban across one clean restart.
    origin=state(lane,norm.name)
    command(staff,'jail DiscNorm alpha persistent-jail','jail: DiscNorm.')
    console_command(lane,'freeze DiscNorm on persistent-freeze','freeze: DiscNorm.')
    norm.disconnect()
    console_command(lane,'unjail DiscNorm','Moderation data is unavailable')
    console_command(lane,'jail DiscNorm beta offline-reassigned','jail: DiscNorm.')
    before_rows=sql(lane,"SELECT jail_name,x,y,z FROM allium_jailed WHERE player_name='DiscNorm'")
    require(before_rows and before_rows[0][0]=='beta' and abs(float(before_rows[0][1])-origin['x'])<.06,lane.name+': offline reassignment preserves durable original return',before_rows)
    obs.disconnect()
    console_command(lane,'ban DiscObs persistent-offline-ban','ban: DiscObs.')
    settled(lane,norm.name)
    snapshots={table:sql(lane,'SELECT * FROM '+table+' ORDER BY 1') for table in ['allium_bans','allium_freezes','allium_jails','allium_jailed','allium_warnings','allium_staff_notes']}
    lane.stop(); lane.boot()
    for table,rows in snapshots.items():
        require(sql(lane,'SELECT * FROM '+table+' ORDER BY 1')==rows,lane.name+': restart preserves '+table)
    staff.connect(); ready(lane,staff.name)
    norm.connect(); ready(lane,norm.name)
    obs.connect(deny='You are banned: persistent-offline-ban')
    grants(lane)
    current=state(lane,norm.name)
    require(current['jailed'] and current['frozen'] and same_place(current,beta),lane.name+': restarted player loads freeze+jail and assigned destination',current)
    movement(lane,norm,True); escape(lane,staff,norm)
    console_command(lane,'freeze DiscNorm off','unfreeze: DiscNorm.')
    movement(lane,norm,True)
    console_command(lane,'unjail DiscNorm','unjail: DiscNorm.')
    require(same_place(state(lane,norm.name),origin),lane.name+': restart /unjail restores persisted original location')
    movement(lane,norm,False)
    command(staff,'warn DiscNorm history','console-warning')
    command(staff,'notes DiscNorm','console-note')
    console_command(lane,'unban DiscObs','unban: DiscObs.')
    obs.connect(); ready(lane,obs.name)
    settled(lane,norm.name)
    require(sql(lane,'SELECT COUNT(*) FROM allium_freezes')==[['0']] and sql(lane,'SELECT COUNT(*) FROM allium_jailed')==[['0']],lane.name+': released restraints removed from durable tables')
    audit=sql(lane,'SELECT action,COUNT(*) FROM allium_moderation_audit GROUP BY action ORDER BY action')
    observations[lane.name+'Audit']=audit
    actions={r[0] for r in audit}
    require({'KICK','BAN','UNBAN','FREEZE','UNFREEZE','SETJAIL','JAIL','UNJAIL','WARN','STAFF_NOTE'}.issubset(actions),lane.name+': command mutations have durable audit coverage',audit)


try:
    build()
    if A.suite=='mariadb':
        password=prepare_mariadb_database()
        lane=Lane('mariadb-spigot-1.20.1','spigot',25792,password)
        observations['mariadbDatabase']=MARIADB_DB
        observations['mariadbVersion']=query_mariadb(password,'SELECT VERSION()')
    elif A.suite=='paper': lane=Lane('paper-26.3','paper',25791)
    else: lane=Lane('spigot-1.20.1','spigot',25790)
    case(lane.name+': startup and ownership',lambda:setup(lane))
    case(lane.name+': authorization and safe defaults',lambda:authorization(lane))
    case(lane.name+': warning and notes history',lambda:history(lane))
    case(lane.name+': authoritative external SQLite ban',lambda:authoritative_external_ban(lane))
    case(lane.name+': kick, ban, offline login, timed expiry',lambda:kicks_and_bans(lane))
    case(lane.name+': freeze, jail, escape prevention, restart durability',lambda:restraints_and_restart(lane))
except BaseException:
    (RUN / 'exceptions.log').open('a').write(traceback.format_exc() + '\n')
    require(False,'unhandled gate exception')
finally:
    for lane in lanes:
        try: lane.stop()
        except Exception as error: require(False,lane.name+': cleanup',str(error))
    for lane in lanes:
        try: verify_integrity('final-before-PASS',lane)
        except Exception as error: require(False,'final integrity verification failed',str(error))
    require(all(p['exitCode'] is not None for p in processes),'every owned subprocess has a recorded exit')
    try: final_log_scan()
    except Exception as error: require(False,'post-shutdown log scan failed',str(error))
    try: capture_and_drop_mariadb()
    except Exception as error: require(False,'owned MariaDB capture/drop cleanup failed',str(error))
    require(all(p['exitCode'] is not None for p in processes),'every owned subprocess including database cleanup has a recorded exit')
    manifest=dict(slice='S19',suite=A.suite,run=str(RUN),startedAt=int(started),finishedAt=int(time.time()),
                  alliumJar=str(JAR),alliumSha256=A.sha256,sourceHashes=SOURCE_HASHES,helperHashes=HELPER_HASHES,integrityChecks=integrity,processes=processes,unitTestDelta=0,
                  transportExceptionPolicy='Only client exit 1 after decoded matching kick and ECONNRESET/EPIPE, no decoder/client error; exact Error read/write line exempted and recorded.',
                  passed=not failures,passedCount=sum(c['passed'] for c in checks),failedCount=len(failures),
                  failures=failures,scopes=scopes,checks=checks,observations=observations,
                  limitations=['IP ban enabled mode is optional and not run; default-off refusal is verified.',
                               'DENY is verified as shipped default; storage-outage DENY/ALLOW fault injection belongs to runtime tests.',
                               'Warning and note history are checked through actual commands and read-only SQL.',
                               'Native namespaced ban/kick/pardon retain their independent vanilla database; ownership is observed.'])
    (RUN/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    emit(f"SUMMARY passed={manifest['passed']} checks={manifest['passedCount']}/{len(checks)} manifest={RUN/'manifest.json'}")
    sys.exit(0 if manifest['passed'] else 1)
