#!/usr/bin/env python3
"""S12 mail: real command/completion/chat/sound clients plus console-only fixture observations.
No root Gradle, production ports, server downloads, or production DB access.
Live mode requires the parent's frozen jar SHA256. Preparation never starts a server.
"""
import argparse
import concurrent.futures
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
import statistics
import subprocess
import sys
import threading
import time
import traceback
import zipfile
import uuid

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['sqlite-spigot', 'sqlite-paper', 'mysql-spigot', 'mysql-paper', 'shared-mysql'], required=True)
P.add_argument('--allium-jar', type=Path, required=True)
P.add_argument('--sha256', required=True, help='parent-confirmed frozen jar hash')
P.add_argument('--run-name', required=True)
P.add_argument('--prepare-only', action='store_true')
P.add_argument('--check', action='store_true')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261113/s12-mail/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name):
    P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s12-mail/runs', str(A.run_root.resolve())):
    P.error('require disposable /mnt/storage/.allium-validation/YYYYMMDD/s12-mail/runs')
A.allium_jar = A.allium_jar.resolve()
if not re.fullmatch('[0-9a-f]{64}', A.sha256) or hashlib.sha256(A.allium_jar.read_bytes()).hexdigest() != A.sha256:
    P.error('frozen jar SHA256 mismatch')
API = INFRA / 'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP = INFRA / 'client/node_modules/minecraft-protocol'
for p in [API, MCP / 'package.json', INFRA / 'servers/spigot-1.20.1.jar', A.paper_template / 'versions/26.3/paper-26.3.jar']:
    if not p.is_file():
        P.error('missing input ' + str(p))
# Reserve the entire owned range against another instance of this gate. Never probe 25565.
lock = open('/mnt/storage/.allium-validation/s12-mail-ports.lock', 'a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
for port in range(25720, 25726):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.bind(('127.0.0.1', port))
print('PREFLIGHT: SHA256 matched; 127.0.0.1:25720-25725 free and gate lock held', flush=True)
if A.check:
    sys.exit(0)
RUN = A.run_root / A.run_name
RUN.mkdir(parents=True, exist_ok=False)
shutil.copy2(A.allium_jar, RUN / 'Allium-under-test.jar')
JAR = RUN / 'Allium-under-test.jar'
assert hashlib.sha256(JAR.read_bytes()).hexdigest() == A.sha256
checks, failures, observations, scopes = [], [], {}, []
lanes = []
SOURCE_FILES=[HERE/'run.py',HERE/'plugin/gate/MailGate.java',HERE/'plugin/plugin.yml',HERE/'client-mcp.cjs',HERE/'client-raw26.cjs',
              HERE/'playerdata.cjs',HERE.parent/'baseline/text-components.cjs',REPO/'tests/live/providers/trigger/process_cleanup.py']
SOURCE_HASHES={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE_FILES}
for source in SOURCE_FILES:
    dest=RUN/'harness'/source.relative_to(REPO)
    dest.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(source,dest)
    os.chmod(dest,0o444)
# Clients and compiled fixture now execute from this frozen per-run tree. The Python runner
# and imported cleanup helper are hashed at entry and checked unchanged at exit as well.
HERE=RUN/'harness/tests/live/mail'

started = time.time()

def interrupted(signum, frame):
    raise InterruptedError('gate received signal '+str(signum))

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


def window(seconds=.8):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        alive()
        time.sleep(.025)


def build():
    classes = RUN / 'helper/classes'
    classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-cp', f'{API}:{JAR}',
                    '-d', str(classes), str(HERE / 'plugin/gate/MailGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar', 'cf', str(RUN / 'helper/MailGate.jar'), '-C', str(classes), '.'], check=True)


class Bot:
    def __init__(self, lane, name):
        self.lane, self.name, self.proc, self.lines = lane, name, None, []
        self.last = 0

    def connect(self, ready=True):
        start = len(self.lines)
        self.connection_start=start
        cmd = ['node', str(HERE / ('client-mcp.cjs' if self.lane.kind == 'spigot' else 'client-raw26.cjs')),
               str(self.lane.path), str(self.lane.port)]
        if self.lane.kind == 'spigot':
            cmd.append('1.20.1')
        env = dict(os.environ, MC_PROTOCOL_MODULE=str(MCP), NODE_COMPILE_CACHE=str(RUN / '.node-cache'))
        self.proc = subprocess.Popen(cmd + [self.name], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / f'{self.lane.name}-{self.name}.log', self.lines), daemon=True).start()
        wait(lambda: any('BASEG-WIRE READY' in s for s in self.lines[start:]), self.name + ' wire READY', 120)
        if ready:
            wait(lambda: self.lane.fixture('state ' + self.name)['loaded'], self.name + ' prefs ready', 25)
        if ready:
            wait(lambda: self.lane.state(self.name)['mailReady'],self.name+' join recovery/lock settled',30)
        self.lane.perm(self.name,'allium.mail.bypass.limits',True)
        window(.2)
        return start

    def write(self, s):
        alive()
        self.proc.stdin.write(s + '\n')
        self.proc.stdin.flush()

    def cmd(self, text):
        wait(lambda: self.lane.state(self.name)['mailReady'],self.name+' mail recovery/lock settled',30)
        pause = 1.05 - (time.monotonic() - self.last)
        if pause > 0:
            window(pause)
        self.last = time.monotonic()
        start = len(self.lines)
        self.write('cmd ' + text)
        return start

    def chats(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('BASEG-CHAT ')]

    def sounds(self, start):
        return [json.loads(s.split(' ', 1)[1]) for s in self.lines[start:] if s.startswith('BASEG-SOUND ')
                and json.loads(s.split(' ', 1)[1]).get('name') == 'minecraft:entity.experience_orb.pickup']

    def chat(self, start, fragment):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), self.name + ': ' + fragment)

    def command(self, text, expected):
        start = self.cmd(text)
        return self.chat(start, expected)

    def tab(self, text):
        start, stamp = len(self.lines), time.monotonic()
        self.write('tab ' + text)
        req=wait(lambda: next((json.loads(s.split(' ',1)[1]) for s in self.lines[start:] if s.startswith('MSGG-TABREQ ')),None),'completion request sent')
        def response(tid):
            return next((json.loads(s.split(' ',1)[1]) for s in self.lines[start:]
                         if s.startswith('MSGG-TAB ') and json.loads(s.split(' ',1)[1])['id']==tid),None)
        deadline=time.monotonic()+.5
        while time.monotonic()<deadline:
            alive()
            result=response(req['id'])
            if result is not None:
                return result['matches'], (time.monotonic()-stamp)*1000
            time.sleep(.01)
        # Spigot omits the response for empty suggestions. Fence with a different positive
        # request and correlate transaction IDs, so a dead/invalid client is never an empty pass.
        fence_start=len(self.lines)
        self.write('tab msgtoggle ')
        fence=wait(lambda: next((json.loads(s.split(' ',1)[1]) for s in self.lines[fence_start:]
                                if s.startswith('MSGG-TABREQ ')),None),'completion positive-control request')
        positive=wait(lambda: response(fence['id']),'completion positive-control response',5)
        if not {'on','off'}.issubset(set(positive['matches'])):
            raise RuntimeError('completion fence did not return expected on/off suggestions')
        window(.15)
        result=response(req['id'])
        if result is None:
            observations.setdefault('emptyCompletionFences',[]).append(dict(lane=self.lane.name,player=self.name,text=text,requestId=req['id'],fenceId=fence['id']))
            return [], (time.monotonic()-stamp)*1000
        return result['matches'], (time.monotonic()-stamp)*1000

    def disconnect(self):
        if self.proc is None:
            return
        proc = self.proc
        # Remove from liveness monitoring only once owned intentional cleanup begins.
        self.proc = None
        stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=15)
        if self.lane.proc is not None and self.lane.proc.poll() is None:
            wait(lambda: self.name not in self.lane.fixture('online')['players'],self.name+' server observed offline',10)


class Lane:
    def __init__(self, name, kind, port, database=None):
        self.name, self.kind, self.port = name, kind, port
        self.path = RUN / name
        self.proc, self.logs, self.bots = None, [], {}
        self.database = database
        self.path.mkdir()
        (self.path / 'plugins/Allium').mkdir(parents=True)
        (self.path / 'tmp').mkdir()
        shutil.copy2(JAR, self.path / 'plugins/Allium.jar')
        shutil.copy2(RUN / 'helper/MailGate.jar', self.path / 'plugins/MailGate.jar')
        with zipfile.ZipFile(JAR) as z:
            config = z.read('config.yml').decode()
        if database:
            # Parse without sourcing or ever printing env contents.
            env = dict(line.split('=', 1) for line in (INFRA / 'mysql.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
            password = shlex.split(env['MYSQL_ROOT_PASSWORD'])[0]
            config = config.replace('type: SQLITE', 'type: MYSQL', 1).replace('host: localhost', 'host: 127.0.0.1', 1)
            config = config.replace('port: 3306', 'port: 25733', 1).replace('database: allium', 'database: ' + database, 1)
            config = config.replace('password: ""', 'password: ' + json.dumps(password), 1)
        (self.path / 'plugins/Allium/config.yml').write_text(config)
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

    def argv(self):
        java = '/usr/lib/jvm/java-' + ('17' if self.kind == 'spigot' else '25') + '-openjdk/bin/java'
        cmd = [java, '-Xms256M', '-Xmx1200M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dallium.fault-injection=true',
               '-Djava.io.tmpdir=' + str(self.path / 'tmp')]
        if self.kind == 'spigot':
            return cmd + ['-jar', 'server.jar', '--nogui']
        # The template cache also contains older artifact groups with overlapping classes.
        # Only the exact bundler library catalog belongs to this Paper build.
        with zipfile.ZipFile(INFRA/'servers/paper-26.3-141.jar') as z:
            catalog=[line.split('\t') for line in z.read('META-INF/libraries.list').decode().splitlines()]
        jars=[]
        for expected, coordinate, relative in catalog:
            lib=self.path/'libraries'/relative
            if hashlib.sha256(lib.read_bytes()).hexdigest()!=expected:
                raise RuntimeError('Paper library checksum mismatch: '+relative)
            jars.append(lib)
        cp=':'.join(str(p.relative_to(self.path)) for p in [self.path/'versions/26.3/paper-26.3.jar',*jars])
        return cmd + ['-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']

    def boot(self):
        start = len(self.logs)
        emit('BOOT ' + self.name)
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(('127.0.0.1', self.port))
        self.proc = subprocess.Popen(self.argv(), cwd=self.path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, RUN / (self.name + '-server.log'), self.logs), daemon=True).start()
        wait(lambda: any('Done (' in s for s in self.logs[start:]), self.name + ' startup', 600)
        require(any('Mail enabled on' in s for s in self.logs[start:]) and any('S12G-GATE READY' in s for s in self.logs[start:]),
                self.name + ': mail module and helper enabled')

    def console(self, s):
        self.proc.stdin.write(s+'\n')
        self.proc.stdin.flush()

    def fixture(self, s):
        start = len(self.logs)
        self.console('s12g '+s)
        line = wait(lambda: next((v for v in self.logs[start:] if ('S12G-DONE '+s+' => ') in v or ('S12G-FAIL '+s+' => ') in v), None), 'fixture '+s)
        if 'S12G-FAIL' in line:
            raise RuntimeError(line.strip())
        return json.loads(line.split(' => ', 1)[1])

    def bot(self, name, ready=True):
        b = self.bots.setdefault(name, Bot(self, name))
        b.connect(ready)
        return b

    def perm(self, name, node, value):
        r = self.fixture(f'perm {name} {node} {str(value).lower()}')
        require(r['permission'] == value, self.name+': effective '+name+' '+node+'='+str(value))

    def box(self, name='MailB'):
        return self.fixture('box '+name)['rows']

    def state(self, name):
        state=self.fixture('state '+name)
        self.playerDataDirectory=Path(state['playerDataDirectory'])
        return state

    def amount(self, name, material='DIAMOND'):
        return sum(s['amount'] for s in self.state(name)['stacks'] if s['material']==material)

    def stop(self):
        errors=[]
        for bot in self.bots.values():
            try:
                bot.disconnect()
            except Exception as error:
                errors.append(bot.name+': '+str(error))
        if self.proc is not None:
            proc, self.proc = self.proc, None
            try:
                stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=90)
            except Exception as error:
                errors.append('server: '+str(error))
        if errors:
            raise RuntimeError('; '.join(errors))

    def crash(self, point=None, target=None, body='S12_pre_escrow'):
        # Only this function may accept nonzero termination; every call records its deliberate fault.
        proc = self.proc
        playerDataDirectory=self.playerDataDirectory
        if point is None:
            emit('DELIBERATE CRASH: before escrow during tripped slow-persist; owned server SIGKILL')
            proc.kill()
            code = proc.wait(20)
            require(code == -9, self.name+': deliberate pre-escrow SIGKILL exit=-9')
        else:
            code = proc.wait(30)
            require(code == 77 and any('S12G-DONE arm '+point+' 1 =>' in s for s in self.logs),
                    self.name+': deliberate '+point+' crash exit=77')
        self.proc = None
        if self.database is None:
            with sqlite3.connect('file:'+str(self.path/'plugins/Allium/allium.db')+'?mode=ro',uri=True) as db:
                db.row_factory=sqlite3.Row
                rows=[dict(r) for r in db.execute('SELECT id,kind,body,state,claim_token,claim_owner,origin FROM allium_mail WHERE body=?',(body,))]
        else:
            literal=body.replace("'","''")
            sql="SELECT JSON_OBJECT('id',id,'kind',kind,'body',body,'state',state,'claim_token',claim_token,'claim_owner',claim_owner,'origin',origin) FROM allium_mail WHERE body='"+literal+"';"
            result=subprocess.run(['docker','exec','-i','allium-gate-mysql','sh','-c',
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names '+self.database],
                    input=sql,text=True,capture_output=True,check=True)
            rows=[json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        if True:  # The same independent stopped-playerdata proof applies to both storage backends.
            players={}
            for name in ['MailA','MailB']:
                ident=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
                file=playerDataDirectory/(str(ident)+'.dat')
                env=dict(os.environ,MC_PROTOCOL_MODULE=str(MCP))
                result=subprocess.run(['node',str(HERE/'playerdata.cjs'),str(file)],env=env,capture_output=True,text=True,check=True)
                players[name]=json.loads(result.stdout)
                players[name]['fileSha256']=hashlib.sha256(file.read_bytes()).hexdigest()
            snap=dict(lane=self.name,point=point,body=body,target=target,rows=rows,savedPlayers=players)
            observations.setdefault('crashSnapshots',[]).append(snap)
            def diamonds(name):
                return sum(int(i['count']) for i in players[name]['inventory'] if i['id']=='minecraft:diamond')
            receiptA=players['MailA']['receipts'] or ''
            receiptB=players['MailB']['receipts'] or ''
            if point is None:
                valid=not rows and diamonds('MailA')==8 and diamonds('MailB')==0 and not receiptA and not receiptB
            elif point=='mail.send.halt-after-take':
                valid=len(rows)==1 and rows[0]['state']=='PENDING' and 'S/'+rows[0]['id'] in receiptA and diamonds('MailA')==0 and diamonds('MailB')==0
            else:
                valid=(len(rows)==1 and target is not None and rows[0]['id']==target['id'] and rows[0]['state']=='CLAIMING'
                       and rows[0]['claim_token']==target['claim_token'] and bool(target['claim_token'])
                       and rows[0]['claim_owner']==target['claim_owner']==rows[0]['origin'])
                key='C/'+target['id']+'/'+target['claim_token']+'/'
                if point=='mail.claim.halt-before-deliver':
                    valid=valid and key not in receiptB and diamonds('MailB')==0
                else:
                    valid=valid and key+'8' in receiptB.split(';') and diamonds('MailB')==8
            require(valid,self.name+': exact target DB row/token and stopped native playerdata receipt/inventory match '+str(point),snap)
        for b in self.bots.values():
            if b.proc is not None:
                bp, b.proc = b.proc, None
                try:
                    bp.wait(15)
                except subprocess.TimeoutExpired:
                    # A client needing forced termination remains a failure even after server crash.
                    bp.terminate()
                    bp.wait(10)
                    require(False, self.name+'/'+b.name+': client failed to exit after deliberate server crash')
                segment=b.lines[getattr(b,'connection_start',0):]
                transport=any(line.startswith('MAILG-TRANSPORT ') for line in segment)
                decoder=any(line.startswith('MAILG-CLIENTERROR ') for line in segment)
                require((bp.returncode==0 or (bp.returncode==1 and transport)) and not decoder,
                        self.name+'/'+b.name+': deliberate crash client exit is clean or identified transport failure, no decoder error',
                        dict(exit=bp.returncode,transport=transport,decoder=decoder))
                emit(f'EXPECTED CRASH DISCONNECT {self.name}/{b.name} exit={bp.returncode}')
        window(.3)


def case(label, fn):
    emit('CASE '+label)
    start=len(failures)
    scope=dict(label=label,status='RUNNING')
    scopes.append(scope)
    try:
        fn()
        scope['status']='FAIL' if len(failures)>start else 'PASS'
    except Exception as e:
        scope['status']='FAIL'
        require(False, label+': '+str(e))
        (RUN / 'exceptions.log').open('a').write(traceback.format_exc()+'\n')


def row_for(lane, token, recipient='MailB'):
    return wait(lambda: next((r for r in lane.box(recipient) if r['body']==token), None), 'stored '+token)


def gift(lane, sender, recipient, token, amount=8, material='DIAMOND'):
    online=lane.bots.get(recipient)
    mark=len(online.lines) if online is not None and online.proc is not None else None
    lane.fixture(f'give {sender.name} {material} {amount}')
    sender.command(f'mail gift {recipient} {amount} {token}', 'item(s) sent to')
    row = row_for(lane, token, recipient)
    if mark is not None:
        online.chat(mark,'New gift from')
        window(.3)
        require(len([c for c in online.chats(mark) if 'New gift from' in c['plain']])==1 and len(online.sounds(mark))==1,
                token+': new gift exactly one line and one NotificationService sound')
    require(row['state']=='READY' and lane.amount(sender.name, material)==0, token+': durable escrow READY and sender items removed')
    return row


def claim(lane, bot, row, expected=None):
    bot.command('mail claim '+row['id'][:8], expected or 'Claimed')
    if expected is None:
        wait(lambda: all(r['id']!=row['id'] for r in lane.box(bot.name)), 'claim finalized')


def basics(lane):
    a,b = lane.bots['MailA'],lane.bots['MailB']
    start = len(b.lines)
    a.command('mail send MailB S12_online', 'Mail sent to MailB')
    b.chat(start,'New mail from MailA')
    window()
    require(len([c for c in b.chats(start) if 'New mail from MailA' in c['plain']])==1 and len(b.sounds(start))==1,
            lane.name+': online new-mail exactly one line and one NotificationService sound', b.sounds(start))
    r=row_for(lane,'S12_online')
    require(r['unread'], lane.name+': text initially unread')
    b.command('mail read','S12_online')
    wait(lambda: not row_for(lane,'S12_online')['unread'], 'mark read')
    b.command('mail delete '+r['id'][:8], 'deleted')
    require(not lane.box(),lane.name+': read and delete remove text')
    b.disconnect()
    a.command('mail send MailB S12_offline','Mail sent to MailB')
    row_for(lane,'S12_offline')
    mark=b.connect()
    b.chat(mark,'unread mail')
    window()
    require(len([c for c in b.chats(mark) if 'unread mail' in c['plain']])==1 and len(b.sounds(mark))==1,
            lane.name+': offline text joins with exactly one unread summary and sound',b.sounds(mark))
    b.command('mail clear','Deleted 0 read mail')
    require(len(lane.box())==1,lane.name+': clear preserves unread text')
    b.command('mail','S12_offline')
    wait(lambda: not row_for(lane,'S12_offline')['unread'],'bare mail marks read')
    b.command('mail clear','Deleted 1 read mail')
    require(not lane.box(),lane.name+': clear removes only read text')


def policies(lane):
    a,b=lane.bots['MailA'],lane.bots['MailB']
    lane.perm('MailA','allium.msg.bypass.ignore',False)
    refusals=[]
    def blocked(label,fragment="can't receive mail right now"):
        before=lane.box()
        mark=len(b.lines)
        start=a.cmd('mail send MailB S12_block_'+label)
        response=a.chat(start,fragment)['plain']
        window()
        require(lane.box()==before and not [c for c in b.chats(mark) if 'mail' in c['plain'].lower()] and not b.sounds(mark),
                lane.name+': '+label+' refuses generically without row or recipient notification',response)
        lane.fixture('give MailA DIAMOND 8')
        mark=len(b.lines)
        gift_response=a.command('mail gift MailB 8 S12_block_gift_'+label,fragment)['plain']
        window(.4)
        require(gift_response==response and lane.box()==before and lane.amount('MailA')==8
                and not [c for c in b.chats(mark) if 'gift' in c['plain'].lower() or 'mail' in c['plain'].lower()] and not b.sounds(mark),
                lane.name+': '+label+' gift has same refusal, no escrow/item removal/recipient notification',gift_response)
        return response
    b.command('ignore MailA','now ignoring')
    refusals.append(blocked('ignore-online'))
    b.disconnect()
    # Authoritative offline ignore list: reconnect is not necessary for enforcement.
    start=a.cmd('mail send MailB S12_ignore_offline')
    a.chat(start,"can't receive mail right now")
    require(not lane.box(),lane.name+': offline persisted ignore refuses without stored mail')
    b.connect()
    b.command('unignore MailA','no longer ignoring')
    b.command('msgtoggle off','no longer accept')
    refusals.append(blocked('msgtoggle-online'))
    b.disconnect()
    start=a.cmd('mail send MailB S12_toggle_offline')
    a.chat(start,"can't receive mail right now")
    require(not lane.box(),lane.name+': offline persisted msgtoggle is authoritative')
    b.connect()
    b.command('msgtoggle on','now accept')
    lane.fixture('mute MailA on')
    blocked('muted','muted')
    lane.fixture('mute MailA off')
    b.disconnect()
    lane.fixture('arm preferences.load-fail 16')
    b.connect(ready=False)
    require(not lane.state('MailB')['loaded'],lane.name+': fault confirms online preferences unloaded')
    blocked('unloaded-preferences')
    lane.fixture('reset')
    b.disconnect()
    b.connect()
    require(len(set(refusals))==1,lane.name+': ignore and msgtoggle use identical generic refusal',refusals)


def completion(lane):
    a,b=lane.bots['MailA'],lane.bots['MailB']
    window(2.2)
    b.disconnect()
    samples=[a.tab('mail send Mail') for _ in range(8)]
    require(all('MailB' in x[0] for x in samples),lane.name+': cached offline name offered by command completion')
    latency=[x[1] for x in samples]
    require(max(latency)<1000 and statistics.median(latency)<250,lane.name+': offline cached completion fast (median<250ms/max<1s)',latency)
    b.connect()
    lane.fixture('vanish MailB on')
    require('MailB' not in a.tab('mail send Mail')[0],lane.name+': cached known name obeys online vanish visibility')
    lane.fixture('vanishjoin MailHidden on')
    hidden=lane.bot('MailHidden')
    window(2.2)
    require('MailHidden' not in a.tab('mail send MailH')[0],lane.name+': first-join vanished player absent from completion')
    hidden.disconnect()
    require('MailHidden' in a.tab('mail send MailH')[0],lane.name+': vanished player becomes historical name after quit')
    lane.fixture('vanish MailB off')
    lane.perm('MailA','allium.mail.send',False)
    require('MailB' not in a.tab('mail send Mail')[0],lane.name+': completion obeys send permission')
    lane.perm('MailA','allium.mail.send',True)
    require('MailB' not in a.tab('mail gift MailB 2 ')[0],lane.name+': gift subsequent note args do not suggest names')


def gifts(lane):
    a,b=lane.bots['MailA'],lane.bots['MailB']
    lane.fixture('empty MailB')
    b.disconnect()
    row=gift(lane,a,'MailB','S12_offline_gift')
    b.connect()
    claim(lane,b,row)
    require(lane.amount('MailB')==8,lane.name+': offline gift delivered on later join and claim')
    lane.fixture('empty MailB')
    row=gift(lane,a,'MailB','S12_cancel_event')
    before=lane.state('MailB')['giveEvents']
    lane.fixture('cancel MailB on')
    b.cmd('mail claim '+row['id'][:8])
    wait(lambda: lane.state('MailB')['giveEvents']==before+1,'ItemGiveEvent fired')
    wait(lambda: row_for(lane,'S12_cancel_event')['state']=='READY','cancelled claim released')
    require(lane.amount('MailB')==0,lane.name+': cancelled shared delivery event keeps gift READY and delivers nothing')
    lane.fixture('cancel MailB off')
    claim(lane,b,row)
    require(lane.amount('MailB')==8,lane.name+': uncancelled gift can subsequently be claimed')
    lane.fixture('empty MailB')
    lane.fixture('give MailA DIAMOND 8')
    a.command('mail gift MailB 3 S12_amount_note','item(s) sent to')
    row=row_for(lane,'S12_amount_note')
    require(lane.amount('MailA')==5,lane.name+': gift numeric amount keeps sender remainder and subsequent note intact')
    b.command('mail read','S12_amount_note')
    wait(lambda: row['id'][:8] in b.tab('mail claim ')[0],'cached claim completion')
    require(row['id'][:8] not in b.tab('mail delete ')[0],lane.name+': delete completion excludes gifts; claim completion includes gift')
    claim(lane,b,row)
    require(lane.amount('MailB')==3,lane.name+': numeric amount gift claims exactly three')
    a.command('mail gift MailB S12_note_no_amount','item(s) sent to')
    row=row_for(lane,'S12_note_no_amount')
    claim(lane,b,row)
    require(lane.amount('MailB')==8 and lane.amount('MailA')==0,lane.name+': nonnumeric gift note sends the remaining held stack')
    lane.fixture('empty MailB')
    gift(lane,a,'MailB','S12_claim_all_one',3)
    gift(lane,a,'MailB','S12_claim_all_two',5)
    start=b.cmd('mail claim all')
    wait(lambda: not lane.box(),'claim all finalizes both gifts')
    window(.3)
    require(lane.amount('MailB')==8 and len([c for c in b.chats(start) if 'Claimed gift' in c['plain']])==2,
            lane.name+': claim all serially delivers both gifts exactly once')
    b.command('mail claim all','no gifts to claim')
    lane.fixture('empty MailB')
    row=gift(lane,a,'MailB','S12_full_partial')
    b.command('mail delete '+row['id'][:8],'still holds items')
    lane.fixture('room MailB 0')
    claim(lane,b,row,'inventory is full')
    wait(lambda: row_for(lane,'S12_full_partial')['state']=='READY','full claim released')
    require(lane.amount('MailB')==0,lane.name+': full inventory delivers no items and keeps gift')
    lane.fixture('room MailB 3')
    claim(lane,b,row,'did not fit')
    wait(lambda: row_for(lane,'S12_full_partial')['state']=='READY','partial finalized')
    require(lane.amount('MailB')==64,lane.name+': partial claim inserts exactly three into available room')
    lane.fixture('empty MailB')
    claim(lane,b,row)
    require(lane.amount('MailB')==5,lane.name+': remnant claim delivers exactly five, conserves original eight')
    b.command('mail claim '+row['id'][:8],'You have no mail')
    require(lane.amount('MailB')==5,lane.name+': repeated claim never duplicates items')
    lane.fixture('empty MailB')
    row=gift(lane,a,'MailB','S12_reload_claim')
    lane.fixture('arm mail.claim.slow 1')
    mark=b.cmd('mail claim '+row['id'][:8])
    wait(lambda: lane.fixture('fault mail.claim.slow')['tripped']==1,'slow claim started')
    logmark=len(lane.logs)
    lane.console('allium reload')
    acknowledgment=wait(lambda: next((line for line in lane.logs[logmark:] if 'Configuration reloaded.' in re.sub('§.','',line)),None),'successful allium reload acknowledgment')
    require(row_for(lane,'S12_reload_claim')['state']=='CLAIMING' and lane.fixture('fault mail.claim.slow')['tripped']==1,
            lane.name+': actual successful reload acknowledged while target claim still held',re.sub('§.','',acknowledgment).strip())
    b.chat(mark,'Claimed')
    wait(lambda: not lane.box(),'reload claim finalized')
    require(lane.state('MailB')['sameService'] and lane.amount('MailB')==8,lane.name+': /allium reload keeps same mail service and in-flight claim exactly once')
    lane.fixture('empty MailB')
    lane.fixture('empty MailA')
    tool=lane.fixture('tool MailA')['item']
    require(bool(tool['signature']) and tool['behavior']=='allium:mob_disarmer',lane.name+': sender signed tool verifies before mail')
    a.command('mail gift MailB 1 S12_signed','item(s) sent to')
    row=row_for(lane,'S12_signed')
    require(not any(s.get('instance_uuid')==tool['instance_uuid'] for s in lane.state('MailA')['stacks']),
            lane.name+': signed source instance absent from sender after escrow before receiver claim')
    claim(lane,b,row)
    received=lane.state('MailB')['stacks']
    require(len(received)==1 and all(received[0].get(k)==tool.get(k) for k in ('material','amount','signature','instance_uuid','state','behavior')),
            lane.name+': signed tool identity/MAC/state survive actual gift escrow and claim',received)


def write_errors(lane):
    a,b=lane.bots['MailA'],lane.bots['MailB']
    lane.fixture('cost deny')
    for cmd in ['mail send MailB S12_policy_denied','mail gift MailB 2 S12_policy_denied_gift']:
        lane.fixture('give MailA DIAMOND 8')
        before=lane.fixture('cost')
        a.command(cmd,'cannot pay for that mail')
        window(.3)
        require(not lane.box() and lane.amount('MailA')==8,lane.name+': CostPolicy denial creates no escrow and takes no inventory')
    lane.fixture('cost allow')
    for kind in ['TEXT','GIFT']:
        lane.fixture('empty MailB')
        lane.fixture('give MailA DIAMOND 8')
        before=lane.fixture('cost')
        mark=len(b.lines)
        lane.fixture('arm mail.write-fail 1')
        cmd='mail send MailB S12_db_error' if kind=='TEXT' else 'mail gift MailB 8 S12_db_error_gift'
        a.command(cmd,'Mail is unavailable')
        window()
        after=lane.fixture('cost')
        require(lane.fixture('fault mail.write-fail')==dict(tripped=1,armed=0),lane.name+': '+kind+' write-failure hook tripped exactly once')
        require(after.get('allows:'+kind,0)==before.get('allows:'+kind,0)+1,
                lane.name+': injected DB error '+kind+' performs exactly one non-debiting CostPolicy preflight',{'before':before,'after':after})
        require(not lane.box() and lane.amount('MailA')==8 and not b.sounds(mark),lane.name+': '+kind+' DB error takes no items, stores no row, gives no recipient sound')
        lane.fixture('reset')


def reconnects(lane):
    a,b=lane.bots['MailA'],lane.bots['MailB']
    lane.fixture('empty MailB')
    lane.fixture('give MailA DIAMOND 8')
    lane.fixture('arm mail.send.slow-persist 1')
    a.cmd('mail gift MailB 8 S12_send_reconnect')
    wait(lambda: lane.fixture('fault mail.send.slow-persist')['tripped']==1,'slow send tripped')
    a.disconnect()
    a.connect()
    # Reconnect immediately, before the 4s slow operation returns.
    window(5)
    require(lane.amount('MailA')==8 and not lane.box(),lane.name+': send disconnect/immediate reconnect keeps inventory and cancels old-generation PENDING gift')
    row=gift(lane,a,'MailB','S12_claim_reconnect')
    lane.fixture('arm mail.claim.slow 1')
    b.cmd('mail claim '+row['id'][:8])
    wait(lambda: lane.fixture('fault mail.claim.slow')['tripped']==1,'slow claim tripped')
    b.disconnect()
    b.connect()
    window(5)
    require(lane.amount('MailB')==0 and row_for(lane,'S12_claim_reconnect')['state']=='READY',lane.name+': claim disconnect/immediate reconnect releases old-generation claim without delivery')
    claim(lane,b,row)
    require(lane.amount('MailB')==8,lane.name+': new session claims released gift exactly once')


def crash_recovery(lane):
    def reconnect():
        lane.boot()
        lane.bots['MailA'].connect()
        lane.bots['MailB'].connect()
    a,b=lane.bots['MailA'],lane.bots['MailB']
    lane.fixture('empty MailB')
    lane.fixture('give MailA DIAMOND 8')
    lane.fixture('arm mail.send.slow-persist 1')
    a.cmd('mail gift MailB 8 S12_pre_escrow')
    wait(lambda: lane.fixture('fault mail.send.slow-persist')['tripped']==1,'pre-escrow fault tripped')
    lane.crash()
    reconnect()
    require(lane.amount('MailA')==8 and not lane.box(),lane.name+': crash before persistence/escrow preserves sender items and creates no gift')
    lane.fixture('arm mail.send.halt-after-take 1')
    a.cmd('mail gift MailB 8 S12_after_escrow')
    lane.crash('mail.send.halt-after-take',body='S12_after_escrow')
    reconnect()
    row=wait(lambda: next((r for r in lane.box() if r['body']=='S12_after_escrow' and r['state']=='READY'),None),'escrow receipt activated')
    require(lane.amount('MailA')==0,lane.name+': crash after escrow recovers READY gift without inventory duplication')
    claim(lane,b,row)
    require(lane.amount('MailB')==8,lane.name+': recovered escrow delivered exactly once')
    lane.fixture('empty MailB')
    row=gift(lane,a,'MailB','S12_before_delivery')
    lane.fixture('arm mail.claim.slow 1')
    lane.fixture('arm mail.claim.halt-before-deliver 1')
    b.cmd('mail claim '+row['id'][:8])
    held=wait(lambda: next((r for r in lane.box() if r['id']==row['id'] and r['state']=='CLAIMING' and r['claim_token']),None),'exact target claim held before crash')
    require(lane.fixture('fault mail.claim.slow')['tripped']==1,lane.name+': exact before-delivery claim observed held under slow hook',held)
    lane.crash('mail.claim.halt-before-deliver',target=held,body='S12_before_delivery')
    reconnect()
    wait(lambda: row_for(lane,'S12_before_delivery')['state']=='READY','unreceipted claim released')
    require(lane.amount('MailB')==0,lane.name+': crash before delivery leaves inventory empty and gift claimable')
    claim(lane,b,row)
    require(lane.amount('MailB')==8,lane.name+': pre-delivery recovery delivers exactly once')
    lane.fixture('empty MailB')
    row=gift(lane,a,'MailB','S12_after_delivery')
    lane.fixture('arm mail.claim.slow 1')
    lane.fixture('arm mail.claim.halt-after-deliver 1')
    b.cmd('mail claim '+row['id'][:8])
    held=wait(lambda: next((r for r in lane.box() if r['id']==row['id'] and r['state']=='CLAIMING' and r['claim_token']),None),'exact target claim held before crash')
    require(lane.fixture('fault mail.claim.slow')['tripped']==1,lane.name+': exact after-delivery claim observed held under slow hook',held)
    lane.crash('mail.claim.halt-after-deliver',target=held,body='S12_after_delivery')
    reconnect()
    wait(lambda: not lane.box(),'delivered receipt finalized')
    require(lane.amount('MailB')==8,lane.name+': crash after delivery finishes DB from receipt without redelivery')
    b.command('mail claim '+row['id'][:8],'You have no mail')
    require(lane.amount('MailB')==8,lane.name+': post-crash repeated claim cannot duplicate')
    b.disconnect()
    b.connect()
    require(lane.amount('MailB')==8 and not lane.state('MailB')['receipts'],lane.name+': second reconnect preserves exactly-once inventory with spent receipt removed')


def mysql_create():
    db='allium_s12_'+re.sub('[^a-z0-9_]','_', A.run_name.lower())[:35]
    cmd=['docker','exec','-i','allium-gate-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
    # Only the explicitly owned disposable container is used. No secret appears in argv/output.
    subprocess.run(cmd,input='CREATE DATABASE `'+db+'`;\n',text=True,stdout=subprocess.DEVNULL,check=True)
    return db


def shared(left,right):
    a=left.bots['MailA']
    b1,b2=left.bots['MailB'],right.bots['MailB']
    for lane in [left,right]:
        lane.fixture('empty MailB')
    row=gift(left,a,'MailB','S12_shared_concurrent',16)
    left.fixture('arm mail.claim.slow 1')
    right.fixture('arm mail.claim.slow 1')
    barrier=threading.Barrier(2)
    def send(bot):
        barrier.wait(10)
        return bot.cmd('mail claim '+row['id'][:8])
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        f1,f2=pool.submit(send,b1),pool.submit(send,b2)
        m1,m2=f1.result(20),f2.result(20)
    held=wait(lambda: next((r for r in left.box() if r['id']==row['id'] and r['state']=='CLAIMING' and r['claim_token']),None),'shared target CLAIMING under token')
    holder=left if held['claim_owner']==(left.path/'plugins/Allium/.server-id').read_text().strip() else right
    winner=b1 if holder is left else b2
    loser=b2 if holder is left else b1
    lostmark=m2 if holder is left else m1
    winmark=m1 if holder is left else m2
    require(holder.fixture('fault mail.claim.slow')['tripped']==1,'shared MySQL: target row owner is paused winning claimant under tripped slow hook',held)
    lost=loser.chat(lostmark,'already being claimed')
    still=next((r for r in left.box() if r['id']==row['id']),None)
    require(still is not None and still['state']=='CLAIMING' and still['claim_token']==held['claim_token']
            and still['claim_owner']==held['claim_owner'] and not any('Claimed gift' in c['plain'] for c in winner.chats(winmark))
            and left.amount('MailB')+right.amount('MailB')==0,
            'shared MySQL: loser refused before paused winner delivered/finalized, exact row token/owner unchanged',dict(held=held,current=still,loser=lost))
    wait(lambda: not left.box(),'shared claim finalized',20)
    window()
    delivered=[left.amount('MailB'),right.amount('MailB')]
    responses=[b1.chats(m1),b2.chats(m2)]
    require(sum(delivered)==16 and sorted(delivered)==[0,16], 'shared MySQL: concurrent same-UUID claims on two live servers deliver exactly once',{'amounts':delivered,'responses':responses})
    require(sum('Claimed gift' in c['plain'] for cs in responses for c in cs)==1 and sum('already being claimed' in c['plain'] or 'You have no mail' in c['plain'] for cs in responses for c in cs)==1,
            'shared MySQL: one client receives success and one refuses',responses)
    left.fixture('reset');right.fixture('reset')
    def blocked_remote(label):
        marks=[len(b1.lines),len(b2.lines)]
        a.command('mail send MailB S12_shared_'+label,"can't receive mail right now")
        left.fixture('give MailA DIAMOND 8')
        a.command('mail gift MailB 8 S12_shared_gift_'+label,"can't receive mail right now")
        window(.4)
        require(not left.box() and left.amount('MailA')==8 and not b1.sounds(marks[0]) and not b2.sounds(marks[1])
                and not [c for bot,mark in zip([b1,b2],marks) for c in bot.chats(mark) if 'New mail' in c['plain'] or 'New gift' in c['plain']],
                'shared MySQL: '+label+' overrides stale left online cache for text/gift with no escrow or recipient notification')
    b2.command('msgtoggle off','no longer accept')
    wait(lambda: right.fixture('stored MailB')['acceptMessages'] in (False,0),'remote msgtoggle persisted')
    require(left.state('MailB')['acceptMessages'] is True,'shared MySQL: left recipient cache demonstrably still accepts while right persisted off')
    blocked_remote('remote_online_toggle')
    b2.command('msgtoggle on','now accept')
    wait(lambda: right.fixture('stored MailB')['acceptMessages'] in (True,1),'remote toggle restore persisted')
    b2.command('ignore MailA','now ignoring')
    wait(lambda: right.fixture('stored MailB')['ignoresMailA'],'remote ignore persisted')
    require(left.state('MailB')['ignoresMailA'] is False,'shared MySQL: left recipient ignore cache demonstrably stale while right persisted ignore')
    blocked_remote('remote_online_ignore')
    b2.command('unignore MailA','no longer ignoring')
    wait(lambda: not right.fixture('stored MailB')['ignoresMailA'],'remote unignore persisted')
    b1.disconnect()
    b2.command('msgtoggle off','no longer accept')
    mark=len(b2.lines)
    a.command('mail send MailB S12_shared_offline_policy',"can't receive mail right now")
    window(.4)
    require(not left.box() and not b2.sounds(mark),'shared MySQL: offline-on-sender recipient uses authoritative persisted remote msgtoggle and gives no notification')
    b2.command('msgtoggle on','now accept')
    right.fixture('empty MailB')
    row=gift(left,a,'MailB','S12_old_to_new',8)
    claim(right,b2,row)
    require(right.amount('MailB')==8 and not left.box(), 'shared MySQL codec: Spigot 1.20.1 gift upgraded and claimed on Paper 26.3')
    # Both disposable servers were started with the same signing keyring.
    right.fixture('empty MailB')
    tool=left.fixture('tool MailA')['item']
    require(bool(tool['signature']) and tool['behavior']=='allium:mob_disarmer','shared signed codec: 1.20.1 source tool verifies')
    a.command('mail gift MailB 1 S12_signed_old_to_new','item(s) sent to')
    row=row_for(left,'S12_signed_old_to_new')
    require(not any(s.get('instance_uuid')==tool['instance_uuid'] for s in left.state('MailA')['stacks']),
            'shared signed codec: source instance absent from sender before cross-version claim')
    claim(right,b2,row)
    received=right.state('MailB')['stacks']
    require(len(received)==1 and all(received[0].get(k)==tool.get(k) for k in ('material','amount','signature','instance_uuid','state','behavior')),
            'shared signed codec: 1.20.1 to 26.3 preserves verified tool identity/MAC/state with shared disposable keyring',received)
    # Backwards data versions must refuse and preserve the original gift.
    right.fixture('give MailB DIAMOND 4')
    b2.command('mail gift MailA 4 S12_new_to_old','item(s) sent to')
    row=row_for(right,'S12_new_to_old','MailA')
    a.command('mail claim '+row['id'][:8],'cannot be claimed on this server')
    wait(lambda: row_for(left,'S12_new_to_old','MailA')['state']=='READY','newer gift preserved')
    require(left.amount('MailA')==0,'shared MySQL codec: newer gift refused on 1.20.1 with no delivery and row preserved')
    anew=right.bots['MailA']
    right.fixture('empty MailA')
    claim(right,anew,row)
    require(right.amount('MailA')==4,'shared MySQL codec: refused newer gift remains claimable on compatible Paper')


def finalize():
    for lane in lanes:
        try:
            lane.stop()
            require(True,lane.name+': all remaining owned clients/server exited cleanly (zero, no forced termination)')
        except Exception as e:
            require(False,lane.name+': cleanup '+str(e))
    for lane in lanes:
        errors=[line.strip() for line in lane.logs if re.search(
            r'Could not pass event|Error occurred while (?:enabling|disabling)|Exception in thread|generated an exception|Could not load .plugins/Allium|Encountered an unexpected exception',line)]
        require(not errors,lane.name+': no unexpected listener/plugin/thread exceptions',errors)
    require(all(Path(p).is_file() and hashlib.sha256(Path(p).read_bytes()).hexdigest()==sha for p,sha in SOURCE_HASHES.items()),'executed harness sources and imported helpers unchanged since frozen snapshot')
    manifest=dict(schema=1, suite=A.suite, status='FAIL' if failures else 'PASS', alliumJar=str(JAR), sha256=A.sha256,
                  startedEpoch=started, finishedEpoch=time.time(), run=str(RUN), checks=checks, failures=failures,
                  scopes=scopes,
                  inputs=dict(SOURCE_HASHES,**{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                          [JAR, API, RUN/'helper/MailGate.jar', INFRA/'servers/spigot-1.20.1.jar', A.paper_template/'versions/26.3/paper-26.3.jar',
                           ] if p.is_file()}),
                  clientLibrary=json.loads((MCP/'package.json').read_text()).get('version'),
                  lanes=[dict(name=l.name,kind=l.kind,port=l.port,database=l.database) for l in lanes],
                  boundaries=['Real protocol command/completion/chat/sound clients; console helper prepares inventory and observes DB/inventory',
                              'Pre-escrow crash is during slow-persist before row insert; not the PENDING-insert/before-take subwindow',
                              'Write error is injected SQLException, not a physical MySQL outage',
                              'Shared MySQL concurrency uses same offline UUID on two separate playerdata directories',
                              'Mail body spying is outside scope'], observations=observations)
    (RUN / 'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    emit('RESULT '+manifest['status']+f' checks={len(checks)} failures={len(failures)} manifest={RUN / "manifest.json"}')

try:
    build()
    if A.prepare_only:
        emit('PREPARED helper only; no live checks executed')
        (RUN/'prepare.json').write_text(json.dumps(dict(status='PREPARED_NOT_RUN',sha256=A.sha256,helper=str(RUN/'helper/MailGate.jar')),indent=2)+'\n')
        sys.exit(0)
    if A.suite=='shared-mysql':
        db=mysql_create()
        left=Lane('mysql-spigot','spigot',25722,db)
        right=Lane('mysql-paper','paper',25723,db)
        left.boot()
        signing='.item-hmac-secret'
        shutil.copy2(left.path/'plugins/Allium'/signing,right.path/'plugins/Allium'/signing)
        os.chmod(right.path/'plugins/Allium'/signing,0o600)
        observations['sharedSigningKeyringCopied']=True
        right.boot()
        left.bot('MailA');left.bot('MailB');right.bot('MailA');right.bot('MailB')
        case('shared-mysql protocol/codec/concurrency',lambda:shared(left,right))
    else:
        kind='spigot' if A.suite.endswith('spigot') else 'paper'
        database=mysql_create() if A.suite.startswith('mysql-') else None
        port={'sqlite-spigot':25720,'sqlite-paper':25721,'mysql-spigot':25724,'mysql-paper':25725}[A.suite]
        lane=Lane(A.suite,kind,port,database)
        lane.boot()
        lane.bot('MailA');lane.bot('MailB')
        for label,fn in [('text lifecycle/notifications',basics),('policy',policies),('completion',completion),
                         ('gift/partial/reload/signed',gifts),('write-error inventory safety and cost policy',write_errors),('session reconnect',reconnects),('crash recovery',crash_recovery)]:
            case(lane.name+' '+label,lambda fn=fn:fn(lane))
except (Exception, KeyboardInterrupt) as e:
    require(False,'gate exception: '+str(e))
    (RUN / 'exceptions.log').open('a').write(traceback.format_exc()+'\n')
finally:
    if not A.prepare_only:
        finalize()
if failures:
    sys.exit(1)
