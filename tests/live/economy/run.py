#!/usr/bin/env python3
"""Economy correctness and labelled realistic/stress measurements in disposable loopback copies."""
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
P.add_argument('--suite', choices=['spigot', 'paper'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='parent-confirmed frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--diagnostics',action='store_true',help='opt-in engine phase samples, GC log and PASSIVE checkpoint probe')
P.add_argument('--paired-reads',action='store_true',help='run pooled then dedicated read-only ten-minute phases')
P.add_argument('--followup-gates',action='store_true',help='operator resolution and realistic latency with background paid claims')
P.add_argument('--paid-workflows',action='store_true',help='E5 reservations, native receipts, real client and crash gates')
P.add_argument('--latency-only', action='store_true')
P.add_argument('--stress-only',action='store_true',help='diagnostic stress workload, never a release gate')
P.add_argument('--duration', type=int, default=600)
P.add_argument('--check', action='store_true')
P.add_argument('--harness-manifest', type=Path, default=HERE / 'evidence/harness-freeze.json')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261114/s20-economy/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
P.add_argument('--infra',type=Path,default=INFRA)
P.add_argument('--java17',type=Path,default=Path('/usr/lib/jvm/java-17-openjdk'))
P.add_argument('--java25',type=Path,default=Path('/usr/lib/jvm/java-25-openjdk'))
P.add_argument('--vault-api',type=Path)
P.add_argument('--host-copy',action='store_true',help='allow a different empty disposable run root; never points at a running server')
A = P.parse_args()
INFRA=A.infra.resolve()
VAULT_API=Path('/home/towkio/.gradle/caches/modules-2/files-2.1/com.github.MilkBowl/VaultAPI/1.7.1/24d63ed39e2d36a5a03fb0addbe8cfe1eff0036/VaultAPI-1.7.1.jar')
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not A.host_copy and not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s20-economy/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s20-economy/runs')
if A.host_copy and (not (A.latency_only or A.stress_only) or A.run_root.resolve()==Path('/') or 'runs'!=A.run_root.name):
    P.error('--host-copy requires --latency-only or --stress-only and a disposable directory named runs')
if A.vault_api: VAULT_API=A.vault_api.resolve()
if A.latency_only and A.stress_only:P.error('choose realistic or stress, not both')
if A.duration<600:P.error('duration must be at least 600 seconds')
A.allium_jar = A.allium_jar.resolve()
if not re.fullmatch('[0-9a-f]{64}', A.sha256) or hashlib.sha256(A.allium_jar.read_bytes()).hexdigest() != A.sha256:
    P.error('frozen jar SHA256 mismatch')
API = INFRA / 'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP = INFRA / 'client/node_modules/minecraft-protocol'
for p in [API, MCP / 'package.json', INFRA / 'servers/spigot-1.20.1.jar', A.paper_template / 'versions/26.3/paper-26.3.jar']:
    if not p.is_file():
        P.error('missing input ' + str(p))

# Verify the reviewable source freeze before creating any run or starting a process.
SOURCE_FILES = [HERE / f for f in ['run.py','plugin/gate/EconomyGate.java','plugin/plugin.yml',
                                  'client-mcp.cjs','client-raw26.cjs','text-components.cjs','process_cleanup.py','plugin/gate/VaultHost.java']]
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
for port in (25800, 25801, 25802):
    guard_port(port)
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
HERE = RUN / 'harness/tests/live/economy'
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
                         and (proc._gate_kick_proof is not None or record.get('expectedCrash')) and transports
                         and all(t.get('code') in ['ECONNRESET','EPIPE'] for t in transports)
                         and not any('DISC-GCLIENTERROR' in line for line in lines))
    record.update(finishedAt=time.time(),exitCode=proc.returncode,intentional=intentional,
                  shutdown=getattr(proc,'_gate_shutdown',None),kickProof=proc._gate_kick_proof,
                  transport=transports,acceptedTransportException=bool(transport_exception))
    require(proc.returncode==0 or transport_exception or (record.get('expectedExit') is not None and proc.returncode==record['expectedExit']),'owned '+record['role']+' exit status accepted',dict(record))
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
        verify(lane.path/'plugins/EconomyGate.jar',HELPER_HASHES['helper/EconomyGate.jar'])
        with zipfile.ZipFile(lane.path/'plugins/EconomyGate.jar') as jar:
            for relative,expected in HELPER_HASHES.items():
                if relative.startswith('helper/classes/') and relative.endswith('.class'):
                    entry=relative.removeprefix('helper/classes/')
                    actual=hashlib.sha256(jar.read(entry)).hexdigest()
                    checked.append(dict(path=str(lane.path/'plugins/EconomyGate.jar')+'!/'+entry,expected=expected,actual=actual,passed=actual==expected))
    record=dict(stage=stage,lane=lane.name if lane else None,checks=checked,passed=all(c['passed'] for c in checked))
    integrity.append(record)
    require(record['passed'],'frozen jar, read-only harness and helper integrity: '+stage,record)


def final_log_scan():
    # Run after every owned process exits and its output-pump thread joins. No exit-0 exemption.
    pattern=re.compile(r'allium.{0,100}task.{0,50}fail|scheduler|callback|could not pass event|\bSEVERE\b|decoder|client.?error|(?<![\"A-Za-z0-9_])ERROR(?![\"A-Za-z0-9_])|S20G-FAIL|error processing packet',re.I)
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


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    code,output=run_tracked([
        str(A.java17/'bin/javac'),'--release','17','-cp',f'{API}:{JAR}:{VAULT_API}',
        '-d',str(classes),str(HERE/'plugin/gate/EconomyGate.java')],'fixture-javac')
    (RUN/'helper/javac.log').write_text(output)
    if code: raise RuntimeError('fixture compilation failed; see helper/javac.log')
    shutil.copy2(HERE/'plugin/plugin.yml',classes/'plugin.yml')
    code,output=run_tracked([str(A.java17/'bin/jar'),'cf',str(RUN/'helper/EconomyGate.jar'),'-C',str(classes),'.'],'fixture-jar')
    (RUN/'helper/jar.log').write_text(output)
    if code: raise RuntimeError('fixture packaging failed; see helper/jar.log')
    for path in [RUN/'helper/EconomyGate.jar',*sorted(classes.rglob('*.class'))]:
        HELPER_HASHES[str(path.relative_to(RUN))]=hashlib.sha256(path.read_bytes()).hexdigest()
    vault_classes=RUN/'vault-host/classes';vault_classes.mkdir(parents=True)
    with zipfile.ZipFile(VAULT_API) as vault:
        for name in vault.namelist():
            if name.startswith('net/milkbowl/vault/economy/') and name.endswith('.class'):
                dest=vault_classes/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(vault.read(name))
    code,output=run_tracked([str(A.java17/'bin/javac'),'--release','17','-cp',str(API),'-d',str(vault_classes),str(HERE/'plugin/gate/VaultHost.java')],'vault-host-javac')
    if code:raise RuntimeError(output)
    (vault_classes/'plugin.yml').write_text('name: Vault\nversion: "1.7.1-testhost"\nmain: gate.VaultHost\napi-version: "1.20"\n')
    code,output=run_tracked([str(A.java17/'bin/jar'),'cf',str(RUN/'helper/Vault.jar'),'-C',str(vault_classes),'.'],'vault-host-jar')
    if code:raise RuntimeError(output)
    HELPER_HASHES['helper/Vault.jar']=hashlib.sha256((RUN/'helper/Vault.jar').read_bytes()).hexdigest()
    observations['vaultHost']=dict(api=str(VAULT_API),apiSha256=hashlib.sha256(VAULT_API.read_bytes()).hexdigest(),scope='unmodified VaultAPI1.7.1 economy classes hosted by test plugin named Vault; actual registered provider is Allium')
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
        shutil.copy2(RUN / 'helper/EconomyGate.jar', self.path / 'plugins/EconomyGate.jar')
        shutil.copy2(RUN/'helper/Vault.jar',self.path/'plugins/Vault.jar')
        with zipfile.ZipFile(JAR) as z:
            config = yaml.safe_load(z.read('config.yml'))
        config.setdefault('economy',{})['vault-provider-enabled']=True # disposable measurement copy only
        for m in ['moderation', 'teleport']:
            config.setdefault('modules', {})[m] = True
        if A.paid_workflows:
            config.setdefault('mail',{}).update({'costs':{'text':'0.25','gift':'1.50'},'mailbox-size':1000,'rate-limit':{'sends':1000,'per-seconds':60}})
            kitdir=self.path/'plugins/Allium/kits';kitdir.mkdir(exist_ok=True)
            (kitdir/'paid.yml').write_text(yaml.safe_dump(dict(name='paid',enabled=True,**{'cooldown-seconds':0,'one-time':False},cost='2.00',items=['minecraft:diamond 1'])))
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
        java = str((A.java17 if self.kind=='spigot' else A.java25)/'bin/java')
        cmd = [
            java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dallium.fault-injection=true',
            '-Xlog:gc*:file='+str(self.path/'gc.log')+':time,uptime,level,tags',
            '-Dallium.economy.timing='+str(A.diagnostics).lower(),
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
        expected_db = 'Database connected (SQLITE)'
        require(any(expected_db in s for s in self.logs[start:])
                and any('Journalled SQLite economy enabled' in s for s in self.logs[start:])
                and any('S20G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': ' + expected_db + ', economy module, Vault API host and helper enabled')
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
        self.console('s20g ' + s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S20G-DONE ' + s + ' => ') in v or ('S20G-FAIL ' + s + ' => ') in v), None),
                    'fixture ' + s)
        if 'S20G-FAIL' in line:
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
    with sqlite3.connect('file:' + str(lane.path / 'plugins/Allium/allium.db') + '?mode=ro', uri=True) as db:
        return [[str(v) if v is not None else 'NULL' for v in row] for row in db.execute(query)]



def window(seconds=.4):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        alive();time.sleep(.025)


def balance(lane,name):
    value=lane.fixture('state '+name)
    require(value['kind']=='Found',lane.name+': '+name+' has an explicitly opened account',value)
    return value['available']


def amount(lane,name):
    ready=wait(lambda:(lambda r:r if r.get('kind')=='Found' else None)(lane.fixture('state '+name)),name+' account ready',30)
    return ready['available']


def console(lane,text,expected):
    mark=len(lane.logs);lane.console(text)
    return wait(lambda: next((line for line in lane.logs[mark:] if expected in line),None),'console '+text)


def set_balance(lane,name,text):
    console(lane,'eco set '+name+' '+text,'Economy operation committed:')
    wanted=int(__import__('decimal').Decimal(text)*100)
    wait(lambda:lane.fixture('state '+name).get('available')==wanted,name+' admin balance')


def setup(lane):
    lane.boot()
    for name in ['MoneyA','MoneyB','MoneyObs']:
        lane.bot(name);amount(lane,name)
        for node in ['allium.balance','allium.pay','allium.paytoggle']:
            lane.perm(name,node,True)
    lane.perm('MoneyA','allium.pay.offline',True)
    for node in ['allium.eco','allium.vanish','allium.vanish.others','allium.vanish.see','allium.ignore','allium.unignore','allium.mute','allium.unmute','allium.msg','allium.socialspy']:
        lane.perm('MoneyObs',node,True)
    for name in ['MoneyA','MoneyB']:
        lane.perm(name,'allium.ignore',True);lane.perm(name,'allium.unignore',True)
    with sqlite3.connect(lane.path/'plugins/Allium/allium.db') as db:
        require(db.execute('PRAGMA journal_mode').fetchone()[0].lower()=='wal',lane.name+': SQLite WAL is active')
    require('scaffold-vault-provider-enabled' not in yaml.safe_load(zipfile.ZipFile(JAR).read('config.yml')).get('economy',{}),lane.name+': retired scaffold flag is absent')
    require('codes/castled/allium/database/EconomyTable.class' not in zipfile.ZipFile(JAR).namelist(),lane.name+': scaffold writer class is absent')
    require(not any(n.startswith('net/milkbowl/vault/') for n in zipfile.ZipFile(JAR).namelist()),lane.name+': production jar does not bundle optional Vault API')


def commands(lane):
    a,b,obs=(lane.bots[n] for n in ['MoneyA','MoneyB','MoneyObs'])
    set_balance(lane,'MoneyA','100.00');set_balance(lane,'MoneyB','100.00')
    a.command('balance','Balance: 100.00')
    mark=len(b.lines);a.command('pay MoneyB 1.23','Paid 1.23 to MoneyB');b.chat(mark,'Received 1.23 from MoneyA')
    require(balance(lane,'MoneyA')==9877 and balance(lane,'MoneyB')==10123,lane.name+': /pay debits and credits exact cents')
    b.command('paytoggle off','Payments disabled.')
    before=(balance(lane,'MoneyA'),balance(lane,'MoneyB'))
    toggle=a.command('pay MoneyB 1.00',"can't receive payments right now.")
    require((balance(lane,'MoneyA'),balance(lane,'MoneyB'))==before,lane.name+': payment toggle refusal has no money effect')
    b.command('paytoggle on','Payments enabled.');b.command('ignore MoneyA','You are now ignoring MoneyA')
    ignored=a.command('pay MoneyB 1.00',"can't receive payments right now.")
    require(toggle['plain']==ignored['plain'],lane.name+': ignore and toggle have identical generic refusal')
    b.command('unignore MoneyA','You are no longer ignoring MoneyA')
    a.command('pay MoneyB 0.001','Invalid arguments or amount')
    a.command('pay MoneyB 1e2','Invalid arguments or amount')
    require((balance(lane,'MoneyA'),balance(lane,'MoneyB'))==before,lane.name+': non-cent and exponent input never writes money')
    config_path=lane.path/'plugins/Allium/config.yml'
    config=yaml.safe_load(config_path.read_text());config['economy']['minimum-pay-amount']='1.00';config_path.write_text(yaml.safe_dump(config,sort_keys=False))
    console(lane,'allium reload','Configuration reloaded.')
    a.command('pay MoneyB 0.01','Amount is below the minimum payment.')
    require((balance(lane,'MoneyA'),balance(lane,'MoneyB'))==before,lane.name+': configured minimum pay refuses without effect')
    config['economy']['minimum-pay-amount']='0.01';config_path.write_text(yaml.safe_dump(config,sort_keys=False))
    console(lane,'allium reload','Configuration reloaded.')
    # Offline execution uses authoritative UUID name storage and an explicit permission.
    b.disconnect();lane.perm('MoneyA','allium.pay.offline',False)
    a.command('pay MoneyB 0.01',"can't receive payments right now.")
    lane.perm('MoneyA','allium.pay.offline',True);a.command('pay MoneyB 0.01','Paid 0.01 to MoneyB')
    require(balance(lane,'MoneyB')==before[1]+1,lane.name+': offline pay with permission credits the frozen UUID')
    b=lane.bot('MoneyB');amount(lane,'MoneyB')
    b.command('balance','Balance: 101.24')
    # Hide target through the actual moderation service; offline permission cannot bypass vanish.
    obs.command('vanish on MoneyB','Enabled vanish for MoneyB')
    hidden=a.command('pay MoneyB 0.01',"can't receive payments right now.")
    require(hidden['plain']==toggle['plain'],lane.name+': vanished recipient uses the generic refusal')
    obs.command('vanish off MoneyB','Disabled vanish for MoneyB')
    obs.command('mute MoneyA payment mute','MoneyA has been muted')
    a.command('pay MoneyB 0.01',"can't receive payments right now.")
    obs.command('unmute MoneyA','MoneyA has been unmuted')
    set_balance(lane,'MoneyA','0.01');a.command('pay MoneyB 0.02','INSUFFICIENT_FUNDS')
    set_balance(lane,'MoneyB','10000000000000.00');a.command('pay MoneyB 0.01','CAP')
    require(balance(lane,'MoneyA')==1,lane.name+': recipient cap rolls back debit')
    set_balance(lane,'MoneyA','1.00');set_balance(lane,'MoneyB','0.00')
    # Real protocol burst sends independent command packets, then observes exact conservation.
    mark=len(a.lines)
    for i in range(8):a.write('cmd pay MoneyB 0.20')
    wait(lambda:len([c for c in a.chats(mark) if 'Paid 0.20' in c['plain'] or 'INSUFFICIENT_FUNDS' in c['plain']])>=8,'all spam payments settled',30)
    require(balance(lane,'MoneyA')==0 and balance(lane,'MoneyB')==100,lane.name+': concurrent /pay spam cannot overspend and conserves finite balance')


def restart_persistence(lane):
    set_balance(lane,'MoneyA','2.00');set_balance(lane,'MoneyB','1.00')
    b=lane.bots['MoneyB'];b.command('paytoggle off','Payments disabled.');b.command('ignore MoneyA','You are now ignoring MoneyA')
    before=(balance(lane,'MoneyA'),balance(lane,'MoneyB'))
    lane.stop();lane.boot()
    for name in ['MoneyA','MoneyB','MoneyObs']:lane.bot(name);amount(lane,name)
    require((balance(lane,'MoneyA'),balance(lane,'MoneyB'))==before,lane.name+': exact money survives clean restart')
    a,b=lane.bots['MoneyA'],lane.bots['MoneyB']
    b.command('paytoggle','Payments enabled.')
    a.command('pay MoneyB 0.01',"can't receive payments right now.")
    b.command('unignore MoneyA','You are no longer ignoring MoneyA')
    a.command('pay MoneyB 0.01','Paid 0.01 to MoneyB')
    require(balance(lane,'MoneyA')==199 and balance(lane,'MoneyB')==101,lane.name+': payment toggle/ignore persistence and recovery share command policy')


def vault_calls(lane):
    set_balance(lane,'MoneyA','100.00')
    initial=lane.fixture('vault MoneyA string')['balance']
    require(initial==100.0,lane.name+': Vault String overload resolves local authoritative name')
    snapped=lane.fixture('vault MoneyA deposit 0.30000000000000004')
    require(snapped['success'] and snapped['amount']==.3 and snapped['balance']==100.3,lane.name+': Vault snaps binary residue and returns exact receipt balance',snapped)
    for value in ['0','-1','0.001','1.001','NaN','Infinity']:
        require(not lane.fixture('vault MoneyA withdraw '+value)['success'],lane.name+': Vault refuses invalid/zero mutation '+value)
    before=balance(lane,'MoneyA');lane.fixture('fault economy.commit-unknown 1')
    unknown=lane.fixture('vault MoneyA deposit 0.01')
    require(not unknown['success'] and unknown['error'].startswith('OUTCOME_UNKNOWN:'),lane.name+': Vault reports explicit unknown outcome with operation ID',unknown)
    op=unknown['error'].split(':',1)[1]
    recovered=lane.fixture('recovervault '+op+' DEPOSIT MoneyA 1')
    require(recovered['kind']=='Committed' and recovered['replay'] and balance(lane,'MoneyA')==before+1,lane.name+': unknown Vault call reconciles original key without duplication')
    set_balance(lane,'MoneyA','0.50');lane.fixture('parallel MoneyA 80')
    result=wait(lambda:(lambda r:r if r.get('done') else None)(lane.fixture('parallelstate')),'concurrent Vault calls complete',30)
    require(result['successful']==50 and not result['error'] and balance(lane,'MoneyA')==0,lane.name+': concurrent Vault withdrawals cannot overspend',result)


def crash_boundary(lane,phase,exit_code):
    a,b=lane.bots['MoneyA'],lane.bots['MoneyB'];set_balance(lane,'MoneyA','10.00');set_balance(lane,'MoneyB','0.00')
    operation=str(__import__('uuid').uuid4())
    lane.fixture('fault economy.halt-'+phase+'-commit 1')
    proc=lane.proc;proc._gate_record['expectedExit']=exit_code
    for bot in lane.bots.values():bot.expected_exit=True;bot.proc._gate_record['expectedCrash']=True
    lane.console('s20g operation '+operation+' PAY MoneyA MoneyB 123')
    wait(lambda:proc.poll() is not None,'owned deliberate journal halt',30,monitor=False)
    require(proc.returncode==exit_code,lane.name+': real journal '+phase+' commit halt has expected exit',proc.returncode)
    for bot in lane.bots.values():
        if bot.proc:
            bot.proc.wait(timeout=15);finish_process(bot.proc,intentional=True);bot.proc=None;bot.expected_exit=False
    finish_process(proc,intentional=True);lane.proc=None
    with sqlite3.connect(lane.path/'plugins/Allium/allium.db') as db:
        count=db.execute('SELECT COUNT(*) FROM allium_economy_operations WHERE operation_id=?',(operation,)).fetchone()[0]
        require(count==(0 if phase=='before' else 1),lane.name+': stopped journal boundary has all-or-none operation row')
    lane.boot()
    for name in ['MoneyA','MoneyB','MoneyObs']:lane.bot(name);amount(lane,name)
    result=lane.fixture('operation '+operation+' PAY MoneyA MoneyB 123')
    require(result['kind']=='Committed' and result['replay']==(phase=='after'),lane.name+': same operation ID recovers exact journal outcome',result)
    require(balance(lane,'MoneyA')==877 and balance(lane,'MoneyB')==123,lane.name+': '+phase+' commit recovery applies one exact transfer')
    again=lane.fixture('operation '+operation+' PAY MoneyA MoneyB 123')
    require(again['replay'] and balance(lane,'MoneyA')==877,lane.name+': second recovery never duplicates effect')


def percentiles(values):
    values=sorted(values)
    return dict(n=len(values),p50=values[int((len(values)-1)*.50)],p95=values[int((len(values)-1)*.95)],p99=values[int((len(values)-1)*.99)],p999=values[int((len(values)-1)*.999)],max=values[-1])


def benchmark(lane):
    # Existing write load is real mail, moderation and preference repository traffic.
    for separate in [False]: # Stress only; never creates a separate economy database.
        lane.fixture('benchmark MoneyA '+str(separate).lower())
        result=wait(lambda:(lambda r:r if r.get('done') else None)(lane.fixture('benchmarkstate')),'FULL contention benchmark',120)
        record=dict(workload='stress',releaseGate=False,layout='separate-economy.db prototype' if separate else 'shared-allium.db',
                    calls=percentiles(result['callsMs']),economyWallPerTick=percentiles(result['tickEconomyWallMs']),
                    serverThreadCpuPerTick=percentiles(result['serverThreadCpuPerTickMs']),otherRepositoryWriteRounds=result['writerRounds'],errors=result['errors'])
        observations[lane.name+('-separate' if separate else '-shared')]=record
        require(not result['errors'] and result['writerRounds']>0,lane.name+': benchmark executes healthy Vault calls concurrently with all three repositories',record)
        record['stress2msReferenceMet']=record['calls']['p99']<=2 and record['calls']['max']<=25
        emit('LATENCY '+json.dumps(record))


def machine_info():
    commands={'kernel':['uname','-srvmo'],'cpu':['lscpu'],'filesystem':['findmnt','-T',str(RUN),'-o','TARGET,SOURCE,FSTYPE,OPTIONS'],'disks':['lsblk','-o','NAME,TYPE,SIZE,ROTA,MODEL,FSTYPE,MOUNTPOINTS'],'memory':['free','-b']}
    return {key:subprocess.run(cmd,capture_output=True,text=True).stdout.strip() for key,cmd in commands.items()}


def realistic(lane,mode='pooled'):
    lane.fixture('realistic MoneyA '+str(A.duration)+' '+mode)
    def completed():
        try:return json.loads((lane.path/('plugins/EconomyGate/realistic-'+mode+'.json')).read_text())
        except (FileNotFoundError,json.JSONDecodeError):return None
    result=wait(completed,'realistic FULL workload',A.duration+180)
    (RUN/('realistic-'+mode+'-raw.json')).write_text(json.dumps(result)+'\n')
    record=dict(workload='realistic',readMode=mode,activeSeconds=result['activeSeconds'],timingSamples=len(result['timings']),checkpointProbes=len(result['checkpoints']),writes=percentiles(result['writeCallsMs']),reads=percentiles(result['readCallsMs']),
        allCalls=percentiles(result['writeCallsMs']+result['readCallsMs']),economyWallPerTick=percentiles(result['tickEconomyWallMs']),
        economyCpuPerTick=percentiles(result['tickEconomyCpuMs']),ticksOver5ms=sum(v>5 for v in result['tickEconomyWallMs']),
        elapsedSeconds=result['elapsedSeconds'],backgroundWrites=result['backgroundWrites'],backgroundIntervalsMs=result['backgroundIntervalsMs'],errors=result['errors'])
    observations[lane.name+'-realistic-'+mode]=record
    require(not result['errors'] and len(result['writeCallsMs'])>=3000 and len(result['readCallsMs'])>=30000 and min(result['backgroundWrites'].values())>0,lane.name+': ten-minute realistic workload completes',record)
    emit('REALISTIC '+json.dumps(record))


def paid_state(lane,name='MoneyA'):
    return lane.fixture('paidstate '+name)


def paid_ready(lane,name='MoneyA'):
    return wait(lambda:(lambda s:s if s['kitReady'] and not s['mailBusy'] else None)(paid_state(lane,name)),name+' item receipt recovery ready',60)



def paid_permissions(lane):
    for name in ['MoneyA','MoneyB','MoneyObs']:
        for node in ['allium.kit','allium.kits.paid','allium.mail','allium.mail.send','allium.mail.gift','allium.mail.claim','allium.mail.read']:
            lane.perm(name,node,True)
        paid_ready(lane,name)

def paid_setup(lane):
    setup(lane)
    paid_permissions(lane)
    require(lane.fixture('state MoneyA')['ready'],lane.name+': E5 authoritative economy and receipt services ready')


def paid_seed(lane,name='MoneyA',material='AIR',amount=0):
    paid_ready(lane,name)
    lane.fixture('seeditems '+name+' '+material+' '+str(amount))


def paid_invariant(lane):
    with sqlite3.connect('file:'+str(lane.path/'plugins/Allium/allium.db')+'?mode=ro',uri=True) as db:
        mismatches=db.execute("SELECT account_uuid FROM allium_economy_accounts a WHERE reserved_minor<>(SELECT COALESCE(SUM(amount_minor),0) FROM allium_economy_reservations r WHERE r.account_uuid=a.account_uuid AND state='HELD')").fetchall()
    require(not mismatches,lane.name+': R equals sum of HELD across both workflow types',mismatches)


def paid_retry(lane,row,step='CAPTURE'):
    before=paid_state(lane)
    result=lane.fixture('retrycapture '+row['kind']+' '+row['id']+' '+step)
    after=paid_state(lane)
    require(result['kind']=='Committed' and result['replay'] and after['book']==before['book'] and after['reserved']==before['reserved'] and after['items']==before['items'] and after['kitUsage']==before['kitUsage'],lane.name+': stable '+row['kind']+' capture retry has no second charge, item grant or cooldown',result)


def paid_success(lane):
    a,b=lane.bots['MoneyA'],lane.bots['MoneyB']
    set_balance(lane,'MoneyA','10.00');paid_seed(lane);paid_seed(lane,'MoneyB')
    a.command('kit paid','Received kit paid')
    state=paid_ready(lane)
    require(state['book']==800 and state['reserved']==0 and state['items'].get('DIAMOND',0)==1 and state['kitUsage']==1,lane.name+': paid kit item, charge, acknowledgement and usage commit',state)
    paid_retry(lane,next(r for r in reversed(state['reservations']) if r['kind']=='KIT'))
    set_balance(lane,'MoneyA','1.00');before=paid_state(lane)
    a.command('kit paid','You cannot pay for that kit')
    state=paid_ready(lane)
    require(state['book']==100 and state['reserved']==0 and state['items']==before['items'] and state['kitUsage']==before['kitUsage'],lane.name+': insufficient paid kit grants nothing and burns no usage')
    set_balance(lane,'MoneyA','10.00');lane.fixture('fillinventory MoneyA');before=paid_state(lane)
    a.command('kit paid','Your inventory cannot hold all items')
    state=wait(lambda:(lambda s:s if s['reserved']==0 and s['kitPending']==0 and s['kitReady'] else None)(paid_state(lane)),'full paid kit releases hold')
    require(state['book']==1000 and state['items']==before['items'] and state['kitUsage']==before['kitUsage'],lane.name+': REFUSE partial kit policy releases full-inventory hold without charge')
    require(any(r['kind']=='KIT' and r['state']=='RELEASED' for r in state['reservations']),lane.name+': full inventory stores terminal RELEASED intent')
    paid_seed(lane)
    a.command('mail send MoneyB paid-text','Mail sent to MoneyB')
    state=paid_state(lane)
    require(state['book']==975 and state['reserved']==0,lane.name+': text mail saves READY with single-commit charge')
    paid_retry(lane,next(r for r in reversed(state['reservations']) if r['kind']=='MAIL'),'SPEND')
    box_before=paid_state(lane,'MoneyB')['mailbox'];lane.fixture('fault economy.commit-unknown 1')
    a.command('mail send MoneyB unknown-text','Payment outcome')
    unknown_state=paid_state(lane);unknown_box=paid_state(lane,'MoneyB')['mailbox']
    require(unknown_state['book']==950 and len(unknown_box)==len(box_before)+1,lane.name+': unknown text acknowledgement preserves committed charge and one mail')
    paid_retry(lane,next(r for r in reversed(unknown_state['reservations']) if r['kind']=='MAIL'),'SPEND')
    before_box=paid_state(lane,'MoneyB')['mailbox'];set_balance(lane,'MoneyA','0.10')
    a.command('mail send MoneyB denied-text','You cannot pay for that mail')
    require(paid_state(lane)['book']==10 and paid_state(lane,'MoneyB')['mailbox']==before_box,lane.name+': insufficient text mail stores no message and charges nothing')
    set_balance(lane,'MoneyA','10.00');paid_seed(lane,material='STONE',amount=4)
    a.command('mail gift MoneyB 2 paid-gift','Gift of 2 item(s) sent to MoneyB')
    state=paid_ready(lane);row=next(r for r in reversed(state['reservations']) if r['kind']=='MAIL' and r['amount']==150)
    require(state['book']==850 and state['reserved']==0 and state['items'].get('STONE')==2 and row['state']=='CAPTURED',lane.name+': gift charges only with saved sender receipt and READY acknowledgement',state)
    target_before=paid_state(lane,'MoneyB');b.command('mail claim '+row['id'][:8],'Claimed gift')
    target=paid_ready(lane,'MoneyB')
    require(target['items'].get('STONE',0)==target_before['items'].get('STONE',0)+2 and target['book']==target_before['book'] and paid_state(lane)['book']==850,lane.name+': claiming paid gift never charges sender or recipient again')
    b.command('mail claim '+row['id'][:8],'You have no mail')
    require(paid_state(lane,'MoneyB')['items']==target['items'] and paid_state(lane)['book']==850,lane.name+': repeated gift claim neither duplicates items nor charges')
    paid_retry(lane,row)
    set_balance(lane,'MoneyA','0.50');before=paid_state(lane);box=paid_state(lane,'MoneyB')['mailbox']
    a.command('mail gift MoneyB 1 denied-gift','You cannot pay for that mail')
    after=paid_ready(lane)
    require(after['book']==50 and after['reserved']==0 and after['items']==before['items'] and paid_state(lane,'MoneyB')['mailbox']==box,lane.name+': insufficient gift preserves held item, money and mailbox')
    paid_invariant(lane)


def paid_concurrent(lane):
    config=lane.path/'plugins/Allium/config.yml';settings=yaml.safe_load(config.read_text());settings['mail']['costs']['text']='2.00';config.write_text(yaml.safe_dump(settings,sort_keys=False));lane.reload_allium()
    set_balance(lane,'MoneyA','3.00');paid_seed(lane);before=paid_state(lane);box=paid_state(lane,'MoneyB')['mailbox'];a=lane.bots['MoneyA'];mark=len(a.lines)
    a.write('cmd mail send MoneyB competing-intent');a.write('cmd kit paid')
    wait(lambda:len(a.chats(mark))>=2,'two real client workflow responses',30)
    state=wait(lambda:(lambda s:s if s['book']==100 and s['reserved']==0 and s['kitReady'] and not s['mailBusy'] else None)(paid_state(lane)),'concurrent workflows settle',30)
    count=int(state['kitUsage']>before['kitUsage'])+int(len(paid_state(lane,'MoneyB')['mailbox'])>len(box))
    require(count==1 and state['book']==100 and state['reserved']==0,lane.name+': racing text/kit intents spend one finite balance once',state)
    paid_invariant(lane);settings['mail']['costs']['text']='0.25';config.write_text(yaml.safe_dump(settings,sort_keys=False));lane.reload_allium()


def paid_restart(lane):
    snapshots={name:paid_ready(lane,name) for name in ['MoneyA','MoneyB']}
    lane.stop();lane.boot()
    for name in ['MoneyA','MoneyB','MoneyObs']:lane.bot(name);amount(lane,name);paid_ready(lane,name)
    paid_permissions(lane)
    for name,old in snapshots.items():
        new=paid_state(lane,name)
        require(new['book']==old['book'] and new['reserved']==old['reserved'] and new['items']==old['items'] and new['kitUsage']==old['kitUsage'],lane.name+': '+name+' ordinary restart preserves charge, item inventory and usage')
    paid_invariant(lane)


def paid_crash(lane,kind,phase,exit_code):
    a=lane.bots['MoneyA'];paid_ready(lane);set_balance(lane,'MoneyA','10.00');paid_seed(lane,material='STONE' if kind=='MAIL' else 'AIR',amount=4 if kind=='MAIL' else 0)
    before=paid_state(lane);previous={r['id'] for r in before['reservations']}
    fault=('kit.claim.halt-before-deliver' if kind=='KIT' else 'mail.send.halt-before-take') if phase=='absent' else 'economy.capture.halt-'+phase
    lane.fixture('fault '+fault+' 1');proc=lane.proc;proc._gate_record['expectedExit']=exit_code
    for bot in lane.bots.values():bot.expected_exit=True;bot.proc._gate_record['expectedCrash']=True
    a.write('cmd '+('kit paid' if kind=='KIT' else 'mail gift MoneyB 2 crash-gift'))
    wait(lambda:proc.poll() is not None,'real '+kind+' '+phase+' capture halt',30,monitor=False)
    require(proc.returncode==exit_code,lane.name+': '+kind+' '+phase+' actual JVM halt has expected exit',proc.returncode)
    for bot in lane.bots.values():
        if bot.proc:
            bot.proc.wait(timeout=15);finish_process(bot.proc,intentional=True);bot.proc=None;bot.expected_exit=False
    finish_process(proc,intentional=True);lane.proc=None
    with sqlite3.connect('file:'+str(lane.path/'plugins/Allium/allium.db')+'?mode=ro',uri=True) as db:
        rows=db.execute("SELECT workflow_id,state,amount_minor FROM allium_economy_reservations WHERE workflow_kind=? AND account_uuid=?",(kind,before['uuid'])).fetchall()
        fresh=[r for r in rows if r[0] not in previous]
        require(len(fresh)==1,lane.name+': crash retains exactly one stable workflow',fresh)
        workflow,status,cost=fresh[0]
        money=db.execute('SELECT balance_minor,reserved_minor FROM allium_economy_accounts WHERE account_uuid=?',(before['uuid'],)).fetchone()
        require(status==('CAPTURED' if phase=='after' else 'HELD') and tuple(money)==((1000-cost,0) if phase=='after' else (1000,cost)),lane.name+': stopped '+kind+' boundary is atomic',dict(state=status,money=money))
    lane.boot()
    with sqlite3.connect('file:'+str(lane.path/'plugins/Allium/allium.db')+'?mode=ro',uri=True) as db:
        offline=db.execute('SELECT state FROM allium_economy_reservations WHERE workflow_id=?',(workflow,)).fetchone()[0]
        require(offline==('RELEASED' if phase=='absent' else 'CAPTURED'),lane.name+': '+kind+' '+phase+' recovered at startup before any player rejoins',offline)
    for name in ['MoneyA','MoneyB','MoneyObs']:lane.bot(name);amount(lane,name);paid_ready(lane,name)
    paid_permissions(lane)
    state=paid_ready(lane);current=next(r for r in state['reservations'] if r['id']==workflow)
    require(current['state']==('RELEASED' if phase=='absent' else 'CAPTURED') and state['reserved']==0 and state['book']==(1000 if phase=='absent' else 1000-cost),lane.name+': '+kind+' '+phase+' recovers original intent without duplicate charge',state)
    if kind=='KIT':
        require(state['items'].get('DIAMOND',0)==(0 if phase=='absent' else 1) and state['kitUsage']==before['kitUsage']+(0 if phase=='absent' else 1),lane.name+': kit native receipt recovery neither duplicates nor burns usage on absence')
    else:
        require(state['items'].get('STONE',0)==(4 if phase=='absent' else 2),lane.name+': gift native sender receipt and inventory recover together')
        box=paid_state(lane,'MoneyB')['mailbox'];require(any(r['id']==workflow and r['state']=='READY' for r in box)==(phase!='absent'),lane.name+': paid gift READY agrees with capture')
        if phase!='absent':
            b_before=paid_state(lane,'MoneyB');lane.bots['MoneyB'].command('mail claim '+workflow[:8],'Claimed gift');b_after=paid_ready(lane,'MoneyB')
            require(b_after['items'].get('STONE',0)==b_before['items'].get('STONE',0)+2 and paid_state(lane)['book']==1000-cost,lane.name+': recovered gift claims once without extra payment')
    if phase!='absent':paid_retry(lane,current)
    paid_invariant(lane)


def operator_resolution(lane):
    # MoneyObs remains offline for both explicit audited decisions; preserve its native file.
    bot=lane.bots['MoneyObs'];bot.disconnect();del lane.bots['MoneyObs']
    window(1)
    for action in ['capture','release']:
        row=lane.fixture('adminhold MoneyObs')
        workflow=row['id']
        lane.console('eco reservations MoneyObs')
        lane.console('eco resolve MAIL '+workflow+' '+action+' gate operator evidence ticket-42')
        def observed():
            with sqlite3.connect('file:'+str(lane.path/'plugins/Allium/allium.db')+'?mode=ro',uri=True) as db:
                state=db.execute('SELECT state FROM allium_economy_reservations WHERE workflow_id=?',(workflow,)).fetchone()[0]
                audit=db.execute('SELECT actor,action,reason FROM allium_economy_reservation_audit WHERE workflow_id=?',(workflow,)).fetchone()
                intent=db.execute('SELECT state FROM allium_mail WHERE id=?',(workflow,)).fetchone()
                return (state,audit,intent) if audit else None
        result=wait(observed,'audited operator resolution',15)
        require(result[0]==('CAPTURED' if action=='capture' else 'RELEASED') and result[1]==('CONSOLE',action.upper(),'gate operator evidence ticket-42') and result[2]==('PENDING',),lane.name+': operator '+action+' preserves intent and records required reason',result)


def paid_latency(lane):
    set_balance(lane,'MoneyB','10000.00')
    lane.fixture('seeditems MoneyB AIR 0')
    stop=threading.Event();failures=[];rounds=[]
    def claims():
        try:
            while not stop.is_set():
                # A full paid service claim and durable recipient gift acknowledgement each round.
                paid_ready(lane,'MoneyB')
                lane.bots['MoneyB'].command('kit paid','Received kit')
                paid_ready(lane,'MoneyB')
                lane.bots['MoneyB'].command('mail gift MoneyA 1 benchmark-paid','Gift of 1 item(s) sent to MoneyA')
                paid_ready(lane,'MoneyB')
                rounds.append(time.time())
                stop.wait(5)
        except Exception as error:failures.append(str(error))
    thread=threading.Thread(target=claims);thread.start()
    try:realistic(lane)
    finally:stop.set();thread.join(timeout=60)
    require(not thread.is_alive() and not failures and len(rounds)>=90,lane.name+': realistic lane overlaps durable paid kit and gift claims',dict(rounds=len(rounds),errors=failures))


try:
    build();lane=Lane(A.suite+'-economy',A.suite,25800 if A.suite=='spigot' else 25801)
    if A.paid_workflows:
        case(lane.name+': E5 setup',lambda:paid_setup(lane))
        case(lane.name+': sufficient/insufficient paid kits and mail',lambda:paid_success(lane))
        case(lane.name+': concurrent workflows cannot overspend',lambda:paid_concurrent(lane))
        case(lane.name+': ordinary restart persistence',lambda:paid_restart(lane))
        for kind in ['KIT','MAIL']:
            for phase,code in [('before',77),('after',78),('absent',77)]:
                case(lane.name+': '+kind+' capture '+phase,lambda k=kind,p=phase,c=code:paid_crash(lane,k,p,c))
        if A.followup_gates:case(lane.name+': operator tooling',lambda:operator_resolution(lane))
        if A.followup_gates:case(lane.name+': realistic Vault with paid background claims',lambda:paid_latency(lane))
    elif A.stress_only:
        case(lane.name+': stress setup',lambda:setup(lane))
        case(lane.name+': diagnostic stress, not release gate',lambda:benchmark(lane))
    elif A.latency_only:
        case(lane.name+': realistic setup',lambda:setup(lane))
        for mode in (['pooled','dedicated'] if A.paired_reads else ['pooled']):
            case(lane.name+': ten minute realistic latency '+mode,lambda m=mode:realistic(lane,m))
    else:
        case(lane.name+': startup and exact command payments',lambda:(setup(lane),commands(lane)))
        case(lane.name+': ordinary restart and payment-policy persistence',lambda:restart_persistence(lane))
        case(lane.name+': Vault API correctness and concurrency',lambda:vault_calls(lane))
        for phase,code in [('before',77),('after',78)]:case(lane.name+': journal halt '+phase,lambda p=phase,c=code:crash_boundary(lane,p,c))
        case(lane.name+': diagnostic stress, not release gate',lambda:benchmark(lane))
except BaseException:
    (RUN/'exceptions.log').open('a').write(traceback.format_exc()+'\n');require(False,'unhandled economy gate exception')
finally:
    for lane in lanes:
        try:lane.stop()
        except Exception as error:require(False,lane.name+': cleanup',str(error))
    for lane in lanes:
        try:verify_integrity('final-before-result',lane)
        except Exception as error:require(False,'final integrity',str(error))
    try:final_log_scan()
    except Exception as error:require(False,'final log scan',str(error))
    performance=[v for k,v in observations.items() if k.endswith('-shared')]
    manifest=dict(releaseDecision='UNAPPROVED',workload='paid-workflows' if A.paid_workflows else 'realistic' if A.latency_only else 'stress' if A.stress_only else 'stress-and-correctness',machine=machine_info(),suite=A.suite,run=str(RUN),alliumJar=str(JAR),alliumSha256=A.sha256,sourceHashes=SOURCE_HASHES,helperHashes=HELPER_HASHES,
                  passed=not failures,passedCount=sum(c['passed'] for c in checks),failedCount=len(failures),failures=failures,checks=checks,scopes=scopes,
                  processes=processes,integrityChecks=integrity,observations=observations,stress2msReferenceMet=bool(performance) and all(r['stress2msReferenceMet'] for r in performance),
                  limitations=['Phase1 SQLite FULL only; no MySQL economy lane.','VaultAPI1.7.1 hosted by a test plugin; actual Allium provider and service calls exercised.','No separate economy database in current lanes; historical prototype is not adopted. SF host run and user budget approval remain required.'])
    (RUN/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    emit('SUMMARY correctness='+str(manifest['passed'])+' checks='+str(manifest['passedCount'])+'/'+str(len(checks))+' releaseDecision='+manifest['releaseDecision'])
    sys.exit(0 if manifest['passed'] else 1)
