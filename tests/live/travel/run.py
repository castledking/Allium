#!/usr/bin/env python3
"""S15 homes/warps real-protocol gate. No Gradle, production changes, downloads, or provider plugins."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
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
import yaml

sys.dont_write_bytecode = True
SOURCE = Path(__file__).resolve().parent
REPO = SOURCE.parents[2]
INFRA = Path('/mnt/storage/.allium-validation/infra')
P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--suite', choices=['spigot', 'paper'], required=True)
P.add_argument('--run-name', required=True)
P.add_argument('--allium-jar', type=Path)
P.add_argument('--sha256')
P.add_argument('--freeze-confirmed', action='store_true', help='parent explicitly confirmed this exact frozen jar/hash')
P.add_argument('--prepare-only', action='store_true')
P.add_argument('--contract',type=Path,default=SOURCE/'contract.json')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261113/s15-travel/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
A.contract=A.contract.resolve()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name): P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/20261113/s15-travel/runs', str(A.run_root.resolve())): P.error('invalid owned run-root')
if not A.prepare_only and not (A.freeze_confirmed and A.allium_jar and A.sha256): P.error('live execution requires parent freeze confirmation, jar and hash')
if A.prepare_only and not A.allium_jar: A.allium_jar=REPO/'build/libs/Allium.jar'
A.allium_jar=A.allium_jar.resolve()
CONTRACT=json.loads(A.contract.read_text())
if not A.prepare_only and not CONTRACT['pinned']:P.error('travel message contract is not pinned')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if A.sha256 and (not re.fullmatch('[0-9a-f]{64}', A.sha256) or sha(A.allium_jar)!=A.sha256): P.error('frozen jar hash mismatch')
API=INFRA/'api/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
MCP=INFRA/'client/node_modules/minecraft-protocol'
def dependency_fingerprint():
    digest=hashlib.sha256();root=INFRA/'client'
    for p in sorted(p for p in root.rglob('*') if p.is_file()):
        digest.update(str(p.relative_to(root)).encode());digest.update(b'\0');digest.update(bytes.fromhex(sha(p)))
    return digest.hexdigest()
DEPENDENCY_HASH=dependency_fingerprint()
A.run_root.parent.mkdir(parents=True,exist_ok=True)
lock=open(str(A.run_root.parent/'ports.lock'),'a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
for port in [25750,25751]:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',port))
RUN=A.run_root/A.run_name
RUN.mkdir(parents=True,exist_ok=False)
shutil.copy2(A.allium_jar,RUN/'Allium-under-test.jar')
JAR=RUN/'Allium-under-test.jar'
jar_hash=sha(JAR)
# Snapshot all local executed helper/client code and production source contracts. Execute copies.
def source_files():
    return sorted(set([p for p in SOURCE.rglob('*') if p.is_file() and p.suffix in ['.py','.java','.cjs','.yml','.json'] and 'evidence' not in p.relative_to(SOURCE).parts]
                       +[REPO/'tests/live/baseline/text-components.cjs',REPO/'tests/live/providers/trigger/process_cleanup.py',A.contract]
                       +[p for root in ['src/main','allium-core/src/main','allium-api/src/main'] for p in (REPO/root).rglob('*') if p.is_file()]))
SOURCE_FILES=source_files()
SOURCE_HASHES={str(p):sha(p) for p in SOURCE_FILES}
for p in SOURCE_FILES:
    dest=RUN/'harness'/p.relative_to(REPO);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest);dest.chmod(0o444)
HERE=RUN/'harness/tests/live/travel'
CONTRACT=json.loads((RUN/'harness'/A.contract.relative_to(REPO)).read_text())
sys.path.insert(0,str(RUN/'harness/tests/live/providers/trigger'))
from process_cleanup import stop_process
checks=[];failures=[];cases=[];observations={};processes=[];lane=None
PLANNED_CASES=['default-and-multiple-home-crud','highest-permission-limit-and-staff-max',
               'snapshot-bound-home-confirmation','named-warps-permission-list-delete',
               'snapshot-and-revision-bound-warp-confirmation','warp-list-tab-privacy-and-cap',
               'home-warp-shared-warmup-movement-cooldown','warmup-warp-permission-and-revision-recheck',
               'saved-destination-unsafe-and-unloaded-world','permissions-vanish-generic-refusal',
               'restart-home-warp-cooldown-persistence','final-client-service-health']
started=time.time()
def emit(s):
    print(s,flush=True)
    with (RUN/'driver.log').open('a') as f:f.write(s+'\n')
def require(ok,label,detail=None):
    checks.append(dict(label=label,passed=bool(ok),detail=detail))
    if not ok:failures.append(label)
    emit(('PASS ' if ok else 'FAIL ')+label)
    return bool(ok)
def pump(proc,path,lines):
    with path.open('a') as f:
        for line in proc.stdout:f.write(line);f.flush();lines.append(line)
def alive():
    if lane:
        if lane.proc is not None and lane.proc.poll() is not None:raise RuntimeError('server unexpectedly exited '+str(lane.proc.returncode))
        for b in lane.bots.values():
            if b.proc is not None and b.proc.poll() is not None:raise RuntimeError(b.name+' unexpectedly exited '+str(b.proc.returncode))
def wait(fn,label,timeout=15,monitor=True):
    end=time.monotonic()+timeout
    while True:
        if monitor:alive()
        value=fn()
        if value:return value
        if time.monotonic()>end:raise RuntimeError('timeout: '+label)
        time.sleep(.025)
def window(seconds=.35):
    end=time.monotonic()+seconds
    while time.monotonic()<end:alive();time.sleep(.025)
def build():
    classes=RUN/'helper/classes';classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac','--release','17','-cp',f'{API}:{JAR}','-d',str(classes),*[str(p) for p in (HERE/'plugin/gate').glob('*.java')]],check=True)
    shutil.copy2(HERE/'plugin/plugin.yml',classes/'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar','cf',str(RUN/'helper/TravelGate.jar'),'-C',str(classes),'.'],check=True)
def verify_infrastructure():
    runtimes={}
    for version in ['17','25']:
        binary=Path('/usr/lib/jvm/java-'+version+'-openjdk/bin/java')
        check=subprocess.run([str(binary),'-version'],capture_output=True,text=True,check=True)
        runtimes[version]=dict(binary=str(binary),version=(check.stdout+check.stderr).strip())
    require(json.loads((MCP/'package.json').read_text())['version']=='1.68.0','reuse exact cached minecraft-protocol 1.68.0')
    with zipfile.ZipFile(INFRA/'servers/paper-26.3-141.jar') as z:
        catalog=[line.split('\t') for line in z.read('META-INF/libraries.list').decode().splitlines()]
    libraries=[]
    for expected,coordinate,relative in catalog:
        path=A.paper_template/'libraries'/relative
        if sha(path)!=expected:raise RuntimeError('supplied Paper library checksum mismatch '+relative)
        libraries.append(dict(coordinate=coordinate,path=relative,sha256=expected))
    observations['infrastructure']=dict(java=runtimes,paperTemplate=str(A.paper_template),paperLibraries=libraries,
        paperRuntimeSha256=sha(A.paper_template/'versions/26.3/paper-26.3.jar'),
        servers={n:sha(INFRA/'servers'/n) for n in ['spigot-1.20.1.jar','paper-26.3-141.jar']})
    require(bool(libraries),'supplied Paper template matches exact bundler library catalog',dict(libraries=len(libraries)))

def case(name,fn):
    emit('CASE '+name);start=len(failures);record=dict(name=name,status='RUNNING',started=time.time());cases.append(record)
    try:fn();record['status']='PASS' if len(failures)==start else 'FAIL'
    except Exception as e:
        record['status']='FAIL';require(False,name+': '+str(e))
        with (RUN/'exceptions.log').open('a') as f:f.write(traceback.format_exc()+'\n')
    record['seconds']=round(time.time()-record['started'],3)
def clean_process(proc,send,timeout,kind):
    detail=dict(kind=kind,pid=proc.pid,exit=None,forced=False)
    processes.append(detail)
    try:stop_process(proc,send,timeout=timeout)
    except Exception as e:detail['error']=str(e);detail['forced']='forced' in str(e);raise
    finally:detail['exit']=proc.returncode

class Bot:
    def __init__(self,owner,name):self.lane=owner;self.name=name;self.proc=None;self.lines=[];self.last=0;self.arrival_mark=0
    def connect(self,ready=True):
        self.connection_start=len(self.lines);self.arrival_mark=0
        cmd=['node',str(HERE/('client-mcp.cjs' if self.lane.kind=='spigot' else 'client-raw26.cjs')),str(self.lane.path),str(self.lane.port)]
        if self.lane.kind=='spigot':cmd.append('1.20.1')
        self.proc=subprocess.Popen(cmd+[self.name],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,
                                   env=dict(os.environ,MC_PROTOCOL_MODULE=str(MCP),NODE_COMPILE_CACHE=str(RUN/'.node-cache')))
        threading.Thread(target=pump,args=(self.proc,RUN/(self.name+'-client.log'),self.lines),daemon=True).start()
        wait(lambda:any('BASEG-WIRE READY' in s for s in self.lines[self.connection_start:]),self.name+' wire ready',120)
        if ready:wait(lambda:self.state()['loaded'] and self.state()['ready'] and self.state()['warpsReady'],self.name+' preference+travel data ready',30)
    def write(self,text):alive();self.proc.stdin.write(text+'\n');self.proc.stdin.flush()
    def state(self):return self.lane.fixture('state '+self.name)
    def chats(self,start,end=None):return [json.loads(s.split(' ',1)[1]) for s in self.lines[start:end] if s.startswith('BASEG-CHAT ')]
    def cmd(self,text,chat=True):
        delay=1.05-(time.monotonic()-self.last)
        if delay>0:window(delay)
        self.last=time.monotonic();mark=len(self.lines)
        state=self.state();self.arrival_mark=state['eventSequence'];self.command_mark=mark
        self.write('cmd '+text)
        wait(lambda:any(e['type']=='command' and e['command']=='/'+text and e['sequence']>self.arrival_mark for e in self.state()['events']),self.name+' real packet command /'+text)
        wait(lambda:any(s=='BASEG-WIRE SENT /'+text+'\n' for s in self.lines[mark:]),self.name+' wire command log')
        observations.setdefault('commands',[]).append(dict(player=self.name,command=text,clientStartLine=mark+1,eventSequenceAfter=self.arrival_mark))
        if chat:wait(lambda:self.chats(mark),self.name+' command response /'+text)
        window(.15)
        return mark
    def tab(self,text):
        mark=len(self.lines);self.write('tab '+text)
        def data(prefix):return [json.loads(s.split(' ',1)[1]) for s in self.lines[mark:] if s.startswith(prefix+' ')]
        req=wait(lambda:data('MSGG-TABREQ'),'completion request')[0]
        def response(tid):return next((d for d in data('MSGG-TAB') if d['id']==tid),None)
        end=time.monotonic()+.6
        while time.monotonic()<end:
            got=response(req['id'])
            if got is not None:return got['matches']
            alive();time.sleep(.02)
        self.write('tab home h')
        fence=wait(lambda:next((r for r in data('MSGG-TABREQ') if r['id']!=req['id']),None),'completion fence request')
        positive=wait(lambda:response(fence['id']),'completion fence response')
        if 'home' not in positive['matches']:raise RuntimeError('empty completion positive control failed')
        observations.setdefault('emptyCompletionFences',[]).append(dict(text=text,requestId=req['id'],fenceId=fence['id']))
        return (response(req['id']) or {'matches':[]})['matches']
    def move(self,x,y,z):
        if self.lane.kind=='paper':self.write('native '+json.dumps(self.lane.fixture(f'movebytes {self.name} {x} {y} {z}')))
        else:self.write(f'move {x} {y} {z}')
    def respawn(self):
        if self.lane.kind=='paper':self.write('native '+json.dumps(self.lane.fixture('respawnbytes '+self.name)))
        else:self.write('respawn')
    def disconnect(self):
        if self.proc is None:return
        proc,self.proc=self.proc,None
        clean_process(proc,lambda:(proc.stdin.write('stop\n'),proc.stdin.flush()),15,'client:'+self.name)
        if self.lane.proc is not None:
            wait(lambda:self.name not in self.lane.fixture('online')['players'],self.name+' quit observed')

class Lane:
    def __init__(self):
        self.kind=A.suite;self.port=25750 if self.kind=='spigot' else 25751;self.path=RUN/self.kind
        self.proc=None;self.logs=[];self.bots={};self.path.mkdir();(self.path/'plugins/Allium').mkdir(parents=True);(self.path/'tmp').mkdir()
        shutil.copy2(JAR,self.path/'plugins/Allium.jar');shutil.copy2(RUN/'helper/TravelGate.jar',self.path/'plugins/TravelGate.jar')
        with zipfile.ZipFile(JAR) as z:config=yaml.safe_load(z.read('config.yml'))
        config.setdefault('teleport',{}).update({'warmup-seconds':1,'movement-tolerance':.1,'request-expiry-seconds':2,
             'cooldowns':{},'back':{'include-death':False},'safety':{'enabled':True,'adjust':True,'search-radius':3,'vertical-search':4,
             'avoid-lava':True,'avoid-void':True,'avoid-suffocation':True,'avoid-falls':True,'load-destination-chunk':True}})
        config.setdefault('preferences',{})['preload-wait-millis']=0
        config.setdefault('modules',{})['travel']=True
        config.setdefault('homes',{}).update({'default-limit':1,'staff-overrides':{},'overwrite-confirm-seconds':30})
        config.setdefault('warps',{})['overwrite-confirm-seconds']=30
        (self.path/'plugins/Allium/config.yml').write_text(yaml.safe_dump(config,sort_keys=False))
        if self.kind=='paper':
            for d in ['libraries','versions']:shutil.copytree(A.paper_template/d,self.path/d)
            shutil.copytree(A.paper_template/'world',self.path/'world',ignore=shutil.ignore_patterns('session.lock','*.dat_old'))
            # Cached terrain only: never inherit identities, inventories, receipts or advancements.
            for d in ['players','playerdata','stats','advancements']:
                shutil.rmtree(self.path/'world'/d,ignore_errors=True)
        else:shutil.copy2(INFRA/'servers/spigot-1.20.1.jar',self.path/'server.jar')
        (self.path/'eula.txt').write_text('eula=true\n')
        (self.path/'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
        (self.path/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={self.port}
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
    def argv(self):
        java='/usr/lib/jvm/java-'+('17' if self.kind=='spigot' else '25')+'-openjdk/bin/java'
        cmd=[java,'-Xms256M','-Xmx1200M','-Dterminal.jline=false','-Dterminal.ansi=false','-Dallium.fault-injection=true','-Djava.io.tmpdir='+str(self.path/'tmp')]
        if self.kind=='spigot':return cmd+['-jar','server.jar','--nogui']
        with zipfile.ZipFile(INFRA/'servers/paper-26.3-141.jar') as z:catalog=[line.split('\t') for line in z.read('META-INF/libraries.list').decode().splitlines()]
        jars=[]
        for expected,coordinate,relative in catalog:
            p=self.path/'libraries'/relative
            if sha(p)!=expected:raise RuntimeError('Paper exact catalog checksum mismatch '+relative)
            jars.append(p)
        observations['paperLibraries']=[dict(coordinate=c,path=p,sha256=s) for s,c,p in catalog]
        cp=':'.join(str(p.relative_to(self.path)) for p in [self.path/'versions/26.3/paper-26.3.jar',*jars])
        return cmd+['-cp',cp,'org.bukkit.craftbukkit.Main','--nogui']
    def boot(self):
        mark=len(self.logs);emit('BOOT '+self.kind)
        with socket.socket() as s:s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',self.port))
        self.proc=subprocess.Popen(self.argv(),cwd=self.path,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        threading.Thread(target=pump,args=(self.proc,RUN/'server.log',self.logs),daemon=True).start()
        wait(lambda:any('Done (' in s for s in self.logs[mark:]),'server startup',600)
        wait(lambda:any('S15G-GATE READY' in s for s in self.logs[mark:]),'helper ready',30)
        require(any('travel' in s.lower() and 'enabled' in s.lower() for s in self.logs[mark:]),'travel module enabled')
    def console(self,text):self.proc.stdin.write(text+'\n');self.proc.stdin.flush()
    def fixture(self,text):
        mark=len(self.logs);self.console('s15g '+text)
        line=wait(lambda:next((s for s in self.logs[mark:] if 'S15G-DONE '+text+' => ' in s or 'S15G-FAIL '+text+' => ' in s),None),'fixture '+text,120 if text.startswith('world ') else 15)
        if 'S15G-FAIL' in line:raise RuntimeError(line.strip())
        return json.loads(line.split(' => ',1)[1])
    def bot(self,name,ready=True):
        b=self.bots.setdefault(name,Bot(self,name));b.connect(ready);return b
    def perm(self,b,node,value=True):
        got=self.fixture(f'perm {b.name} {node} {str(value).lower()}')
        require(got['permission']==value,'effective '+b.name+' '+node+'='+str(value))
    def place(self,b,x=0,z=0,world='world',terrain='safe'):
        mark=len(b.lines);value=self.fixture(f'place {b.name} {world} {x} {z} {terrain}')
        wait(lambda:any(s.startswith('TPG-POS ') for s in b.lines[mark:]),b.name+' receives fixture position')
        window(.25);return value
    def reload(self,values):
        p=self.path/'plugins/Allium/config.yml';config=yaml.safe_load(p.read_text())
        for key,value in values.items():
            at=config;parts=key.split('.')
            for part in parts[:-1]:at=at.setdefault(part,{})
            at[parts[-1]]=value
        p.write_text(yaml.safe_dump(config,sort_keys=False));mark=len(self.logs);self.console('allium reload')
        wait(lambda:any('Configuration reloaded.' in s for s in self.logs[mark:]),'actual reload acknowledgement')
        observed=self.fixture('config')
        require(all(observed.get(key)==value for key,value in values.items()) and observed['sameService'],'reload acknowledged and same service reads '+','.join(values),observed)
        observations.setdefault('reloads',[]).append(dict(requested=values,observed=observed))
    def stop(self):
        errors=[]
        for b in self.bots.values():
            try:b.disconnect()
            except Exception as e:errors.append(b.name+': '+str(e))
        if self.proc is not None:
            proc,self.proc=self.proc,None
            try:clean_process(proc,lambda:(proc.stdin.write('stop\n'),proc.stdin.flush()),90,'server:'+self.kind)
            except Exception as e:errors.append(str(e))
        if errors:raise RuntimeError('; '.join(errors))

def normalized_components(responses,query):
    def recursive(value):
        if isinstance(value,str):return value.replace(query,'<query>')
        if isinstance(value,list):return [recursive(v) for v in value]
        if isinstance(value,dict):return {k:recursive(v) for k,v in value.items()}
        return value
    return [dict(component=recursive(json.loads(d['rawComponent']) if isinstance(d['rawComponent'],str) else d['rawComponent']),
                 interactive=d['interactive'],overlay=d['overlay']) for d in responses]

def hidden_command_response(responses,command):
    error='{command.unknown.command}';context=command+'{command.context.here}'
    texts=[d['plain'] for d in responses]
    if texts==[error,context]:return True  # Spigot sends the native error and context separately.
    if texts!=[error+'\n'+context]:return False
    expected={'color':'red','extra':[{'translate':'command.unknown.command'},{'':'\n'},
              {'click_event':{'command':'/'+command,'action':'suggest_command'},'color':'gray','extra':[
                  {'underlined':1,'color':'red','text':command},{'italic':1,'color':'red','translate':'command.context.here'}],'text':''}],'text':''}
    d=responses[0];component=json.loads(d['rawComponent']) if isinstance(d['rawComponent'],str) else d['rawComponent']
    return component==expected and d['interactive'] is True and d['overlay'] is False

def near(a,b,tolerance=.2):return a['worldUUID']==b['worldUUID'] and sum((a[k]-b[k])**2 for k in ['x','y','z'])<=tolerance**2
def plugin_events(b):return [e for e in b.state()['events'] if e['type']=='teleport' and e['cause']=='PLUGIN' and not e['preparing']]
def physical(b):return [e for e in b.state()['events'] if e['type']=='teleport' and e['cause']=='PLUGIN' and not e['preparing'] and not e['cancelled']]
def clear(*bots):
    for b in bots:b.arrival_mark=lane.fixture('clear '+b.name)['eventSequence']
def arrived(b,dest,label,timeout=8):
    mark=b.arrival_mark
    def observation():
        state=b.state()
        matching=[e for e in state['events'] if e['type']=='teleport' and e['cause']=='PLUGIN' and not e['preparing']
                  and not e['cancelled'] and e['sequence']>mark and near(e['to'],dest)]
        return (state,matching) if near(state,dest) and matching else None
    state,matching=wait(observation,label+' with fresh matching PLUGIN event',timeout)
    require(near(state,dest) and bool(matching),label,dict(actual=state,expected=dest,eventSequenceAfter=mark,matchingEvents=matching))
    b.arrival_mark=max(e['sequence'] for e in matching)
def stationary(b,before,label,seconds=1.5):
    window(seconds);after=b.state();require(near(before,after) and not plugin_events(b),label,dict(before=before,after=after))
def ready(b):
    wait(lambda:all(b.state()[k] for k in ['loaded','ready','warpsReady','teleportReady']) and not b.state()['pending'] and not b.state()['teleportHistoryPending'],b.name+' settled travel and teleport data',30)

def expect(b,mark,key,label=None,**params):
    """Compare bounded real-wire messages against the frozen plugin's resolved message enum."""
    window(.4)
    substitutions={'name':params.get('name',''), 'home':params.get('name',''), 'warp':params.get('name',''), **params}
    args=' '.join('{'+k+'} b64:'+base64.b64encode(str(v).encode()).decode() for k,v in substitutions.items() if str(v))
    expected=lane.fixture('message '+CONTRACT['messages'][key]+(' '+args if args else ''))['plain']
    responses=b.chats(mark)
    require([d['plain'] for d in responses]==[expected] and all(not d['overlay'] for d in responses),
            label or (b.name+' exact '+key+' response'),dict(expected=expected,responses=responses,clientLineRange=[mark+1,len(b.lines)]))
    return responses

def expect_sequence(b,mark,expected,label):
    resolved=[]
    for key,params in expected:
        args=' '.join('{'+k+'} b64:'+base64.b64encode(str(v).encode()).decode() for k,v in params.items())
        resolved.append(lane.fixture('message '+CONTRACT['messages'][key]+(' '+args if args else ''))['plain'])
    responses=b.chats(mark)
    require([r['plain'] for r in responses]==resolved and all(not r['overlay'] for r in responses),
            label,dict(expected=resolved,responses=responses,clientLineRange=[mark+1,len(b.lines)]))

def say(b,command,key,**params):
    if key in ['homeConfirm','warpConfirm']:params.setdefault('command','/'+command+' confirm')
    mark=b.cmd(command);ready(b);expect(b,mark,key,command+' reports '+key,**params);return mark

def refused(b,command,key,seconds=1.3,**params):
    clear(b);before=b.state();mark=b.cmd(command);ready(b);window(seconds)
    if key=='permission':
        responses=b.chats(mark);expected=lane.fixture('message NO_PERMISSION')['plain']
        require([r['plain'] for r in responses]==[expected] or hidden_command_response(responses,command),
                command+' exact permission or native hidden-command refusal',responses)
    else:expect(b,mark,key,command+' exact refusal',**params)
    require(near(before,b.state()) and not plugin_events(b),command+' refuses with no physical event',dict(before=before,after=b.state()))
    return mark

def travel(b,command,dest,label=None):
    clear(b);mark=b.cmd(command);arrived(b,dest,label or (command+' reaches saved world and XYZ'));ready(b)
    responses=b.chats(mark)
    expected=lane.fixture('message TELEPORT_SUCCESS {target} '+command.split()[0])['plain']
    require(any(d['plain']==expected and not d['overlay'] for d in responses),command+' returns shared pipeline success',responses)
    require(len(physical(b))==1 and len(plugin_events(b))==1,command+' exactly one physical event',plugin_events(b))

def cooldown_refusal(b,command,maximum,label=None):
    clear(b);before=b.state();mark=b.cmd(command);window(.4);responses=b.chats(mark)
    template=lane.fixture('message COOLDOWN_ACTIVE {time} S15TIME')['plain']
    pattern=re.escape(template).replace('S15TIME',r'(\d+)s')
    matched=re.fullmatch(pattern,responses[0]['plain']) if len(responses)==1 else None
    require(bool(matched) and 1<=int(matched.group(1))<=maximum and not responses[0]['overlay']
            and near(before,b.state()) and not plugin_events(b),label or command+' exact cooldown refusal without event',
            dict(template=template,responses=responses,before=before,after=b.state()))

def row_snapshot():return lane.fixture('rows')['tables']
def rows_for(tables,kind,name):
    return [r for t in tables if t['table'].lower().endswith('_'+kind+'s') for r in t['rows'] if name in [str(v) for v in r.values()]]
def has_name(responses,name):return any(re.search(r'(?<![A-Za-z0-9_-])'+re.escape(name)+r'(?![A-Za-z0-9_-])',d['plain']) for d in responses)
def listing(b,command,visible,hidden):
    mark=b.cmd(command);window(.4);responses=b.chats(mark)
    require(bool(responses) and all(has_name(responses,n) for n in visible) and all(not has_name(responses,n) for n in hidden),
            command+' lists exactly permitted tested names',dict(visible=visible,hidden=hidden,responses=responses));return responses

def basic_homes():
    a,b,c=lane.bots.values()
    for command in ['sethome','home','delhome','homes']:
        observed=lane.fixture(f'permission {a.name} allium.{command}')
        require(observed['permission'] and observed['registeredDefault']=='TRUE' and not observed['fixtureExplicit'] and not observed['op'],'default true allium.'+command,observed)
    lane.fixture('world gate_other')
    home=lane.place(a,0);say(a,'sethome','homeSet',name='home');observations['defaultHome']=home
    lane.perm(a,'allium.sethome.multiple.3')
    cabin=lane.place(a,30);say(a,'sethome cabin','homeSet',name='cabin')
    mine=lane.place(a,60,world='gate_other');say(a,'sethome mine','homeSet',name='mine')
    listing(a,'homes',['home','cabin','mine'],[])
    for command,dest in [('home',home),('home cabin',cabin),('home mine',mine)]:
        lane.place(a,90);travel(a,command,dest)
    say(a,'delhome cabin','homeDeleted',name='cabin');refused(a,'home cabin','homeNotFound',name='cabin')
    say(a,'delhome mine','homeDeleted',name='mine');say(a,'delhome','homeDeleted',name='home')
    refused(a,'home','homeNotFound',name='home');lane.place(a,0);say(a,'sethome','homeSet',name='home')
    listing(a,'homes',['home'],['cabin','mine'])
    require(not rows_for(row_snapshot(),'home','cabin') and not rows_for(row_snapshot(),'home','mine'),'deleted home rows absent')
    lane.fixture('unsetperm '+a.name+' allium.sethome.multiple.3')

def limits():
    a=lane.bots['TravelA'];lane.place(a,0)
    refused(a,'sethome limit2','homeLimit',limit=1)
    lane.perm(a,'allium.sethome.multiple.2');lane.perm(a,'allium.sethome.3')
    for n in [2,3]:say(a,'sethome limit'+str(n),'homeSet',name='limit'+str(n))
    refused(a,'sethome limit4','homeLimit',limit=3)
    uid=a.state()['uuid']
    lane.reload({'homes.staff-overrides.'+uid:2})
    refused(a,'sethome limit4','homeLimit',limit=3)
    lane.reload({'homes.staff-overrides.'+uid:4})
    say(a,'sethome limit4','homeSet',name='limit4');refused(a,'sethome limit5','homeLimit',limit=4)
    lane.perm(a,'allium.sethome.unlimited')
    require(a.state()['homeLimit']==1000,'explicit unlimited grants the hard cap of 1000',a.state()['homeLimit'])
    say(a,'sethome limit5','homeSet',name='limit5')
    for n in [2,3,4,5]:say(a,'delhome limit'+str(n),'homeDeleted',name='limit'+str(n))
    lane.perm(a,'allium.sethome.unlimited',False)
    lane.perm(a,'allium.sethome.multiple.1000')
    require(a.state()['homeLimit']==1000,'highest finite grant reaches hard cap without adding staff grant',a.state()['homeLimit'])
    lane.fixture('unsetperm '+a.name+' allium.sethome.multiple.1000')
    lane.fixture('unsetperm '+a.name+' allium.sethome.3')
    lane.reload({'homes.staff-overrides.'+uid:1})
    say(a,'sethome limit2','homeSet',name='limit2');refused(a,'sethome limit3','homeLimit',limit=2)
    say(a,'delhome limit2','homeDeleted',name='limit2')
    lane.perm(a,'allium.sethome.multiple.3')

def home_confirm():
    a=lane.bots['TravelA'];original=lane.place(a,0);say(a,'sethome snap','homeSet',name='snap')
    first=lane.place(a,30);say(a,'sethome snap','homeConfirm',name='snap',time=30)
    lane.place(a,60);say(a,'sethome snap confirm','homeSet',name='snap')
    lane.place(a,90);travel(a,'home snap',first,'home confirm captures first request snapshot')
    lane.place(a,60);say(a,'sethome snap','homeConfirm',name='snap',time=30)
    replacement=lane.place(a,90);say(a,'sethome snap','homeConfirm',name='snap',time=30)
    lane.place(a,120);say(a,'sethome snap confirm','homeSet',name='snap')
    travel(a,'home snap',replacement,'second unconfirmed request replaces pending snapshot')
    lane.reload({'homes.overwrite-confirm-seconds':1})
    lane.place(a,150);say(a,'sethome snap','homeConfirm',name='snap',time=1);window(1.25)
    refused(a,'sethome snap confirm','homeConfirmInvalid',name='snap')
    travel(a,'home snap',replacement,'expired confirmation cannot overwrite home')
    lane.reload({'homes.overwrite-confirm-seconds':30})
    lane.place(a,180);say(a,'sethome snap','homeConfirm',name='snap',time=30)
    a.disconnect();a.connect();lane.perm(a,'allium.sethome.multiple.3')
    refused(a,'sethome snap confirm','homeConfirmInvalid',name='snap')
    lane.place(a,0);travel(a,'home snap',replacement,'confirmation cannot cross connections')
    say(a,'delhome snap','homeDeleted',name='snap')

def warp_crud():
    a,b,c=lane.bots.values();lane.fixture('op '+c.name+' true')
    for command in ['warp','warps']:
        observed=lane.fixture(f'permission {a.name} allium.{command}')
        require(observed['permission'] and observed['registeredDefault']=='TRUE' and not observed['fixtureExplicit'] and not observed['op'],'default true allium.'+command,observed)
    for name,x in [('gate',240),('secret',270),('remove',300)]:
        dest=lane.place(c,x);say(c,'setwarp '+name,'warpSet',name=name);observations['warp-'+name]=dest
    refused(b,'setwarp unauthorized','permission')
    require(not rows_for(row_snapshot(),'warp','unauthorized'),'nonstaff setwarp created no row')
    for bot in [a,c]:
        effective=lane.fixture('permission '+bot.name+' allium.warps.gate')
        require(not effective['permission'],'loaded warp default false even for operator '+bot.name,effective)
        refused(bot,'warp gate','warpNotFound',name='gate')
    lane.perm(a,'allium.warps.gate');lane.place(a,0);travel(a,'warp gate',observations['warp-gate'])
    listing(a,'warps',['gate'],['secret','remove'])
    listing(c,'warps',[],['gate','secret','remove'])
    lane.perm(a,'allium.warps.remove');say(c,'delwarp remove','warpDeleted',name='remove')
    refused(a,'warp remove','warpNotFound',name='remove')
    require(not rows_for(row_snapshot(),'warp','remove'),'delwarp removed row')
    refused(a,'delwarp secret','permission')

def warp_confirm():
    a,b,c=lane.bots.values();lane.fixture('op '+b.name+' true')
    first=lane.place(c,330);say(c,'setwarp gate','warpConfirm',name='gate',time=30)
    lane.place(c,360);say(c,'setwarp gate confirm','warpSet',name='gate')
    lane.place(a,0);travel(a,'warp gate',first,'warp confirmation uses initial location snapshot')
    lane.place(b,390);say(b,'setwarp gate','warpConfirm',name='gate',time=30)
    updated=lane.place(c,420);say(c,'setwarp gate','warpConfirm',name='gate',time=30)
    lane.place(c,450);say(c,'setwarp gate confirm','warpSet',name='gate')
    refused(b,'setwarp gate confirm','warpChanged',name='gate')
    lane.place(a,0);travel(a,'warp gate',updated,'stale row revision cannot replace another staff write')
    lane.reload({'warps.overwrite-confirm-seconds':1})
    lane.place(c,480);say(c,'setwarp gate','warpConfirm',name='gate',time=1);window(1.25)
    refused(c,'setwarp gate confirm','warpConfirmInvalid',name='gate')
    lane.reload({'warps.overwrite-confirm-seconds':30})
    lane.place(a,0);travel(a,'warp gate',updated,'expired warp confirmation leaves prior row intact')
    observations['warp-gate']=updated;lane.fixture('op '+b.name+' false')

def tab_privacy():
    a,b,c=lane.bots.values()
    for command in ['warp ']:
        before=a.state();matches=a.tab(command);after=a.state()
        require('gate' in matches and 'secret' not in matches and 'remove' not in matches,
                command+' completion filters individual permission',matches)
        require(before['ready'] and after['ready'] and before['warpsReady'] and after['warpsReady'] and not after['pending']
                and before['travelIo']['taskCount']==after['travelIo']['taskCount'],
                command+' completion uses ready cache with no new travel storage task',dict(before=before,after=after))
    lane.perm(a,'allium.warp',False)
    require(not a.tab('warp '),'warp completion needs base command permission')
    lane.perm(a,'allium.warp');lane.perm(a,'allium.warps',False)
    require(not a.tab('warps '),'warps completion needs base command permission')
    lane.perm(a,'allium.warps')
    for i in range(51):
        name='cap'+str(i).zfill(2)
        say(c,'setwarp '+name,'warpSet',name=name);lane.perm(a,'allium.warps.'+name)
    matches=a.tab('warp cap')
    require(len(matches)==50 and len(set(matches))==50 and all(re.fullmatch(r'cap\d\d',n) for n in matches),
            'permission-filtered completion capped at fifty cached names',matches)
    before=a.state();lane.reload({'homes.default-limit':2});after=a.state()
    require(before['sameService'] and after['sameService'] and after['ready'] and after['warpsReady'],
            'reload preserves same service and ready caches',dict(before=before,after=after))
    require('gate' in a.tab('warp g') and 'home' in a.tab('home h'),'ready completion caches remain usable after reload')
    lane.reload({'homes.default-limit':1})

def pipeline():
    a=lane.bots['TravelA'];home=observations['defaultHome'];warp=observations['warp-gate']
    lane.reload({'teleport.warmup-seconds':3,'teleport.cooldowns.home':5,'teleport.cooldowns.warp':5})
    for command,dest in [('home',home),('warp gate',warp)]:
        origin=lane.place(a,510);clear(a);mark=a.cmd(command)
        wait(lambda:a.state()['teleportWarming'],command+' real warmup')
        require(near(origin,a.state()) and not plugin_events(a),command+' does not move during warmup')
        a.move(origin['x']+1,origin['y'],origin['z'])
        wait(lambda:any(e['type']=='move' and not e['preparing'] and e['sequence']>a.arrival_mark for e in a.state()['events']),command+' real movement event')
        wait(lambda:not a.state()['teleportWarming'],command+' warmup cancelled by movement')
        window(3.2);require(not plugin_events(a),command+' movement prevented physical teleport',a.state())
        expect_sequence(a,mark,[('warmup',{'time':'3.0'}),('interrupted',{'reason':'movement'})],command+' movement cancellation returns exact bounded response')
        lane.place(a,510);travel(a,command,dest)
        lane.place(a,540);cooldown_refusal(a,command,5)
        window(5)
    lane.reload({'teleport.warmup-seconds':1,'teleport.cooldowns.home':0,'teleport.cooldowns.warp':0})

def warp_recheck():
    a,b,c=lane.bots.values();lane.reload({'teleport.warmup-seconds':5})
    origin=lane.place(a,0);clear(a);mark=a.cmd('warp gate');wait(lambda:a.state()['teleportWarming'],'warp warmup starts before permission revocation')
    lane.perm(a,'allium.warps.gate',False);window(5.3)
    require(near(origin,a.state()) and not plugin_events(a) and not a.state()['teleportWarming'],
            'per-warp access revoked during warmup prevents physical teleport',a.state())
    expect_sequence(a,mark,[('warmup',{'time':'5.0'}),('interrupted',{'reason':'destination is no longer available'})],'permission recheck returns exact complete bounded response')
    lane.perm(a,'allium.warps.gate');clear(a);mark=a.cmd('warp gate');wait(lambda:a.state()['teleportWarming'],'warp warmup starts before revision change')
    updated=lane.place(c,570);say(c,'setwarp gate','warpConfirm',name='gate',time=30);say(c,'setwarp gate confirm','warpSet',name='gate')
    window(5.3);require(near(origin,a.state()) and not plugin_events(a) and not a.state()['teleportWarming'],
            'warp row revision changed during warmup prevents stale teleport',a.state())
    expect_sequence(a,mark,[('warmup',{'time':'5.0'}),('interrupted',{'reason':'destination is no longer available'})],'revision recheck returns exact complete bounded response')
    observations['warp-gate']=updated;lane.reload({'teleport.warmup-seconds':1})
    travel(a,'warp gate',updated,'fresh warp positive control after revision change')

def unsafe_worlds():
    a,b,c=lane.bots.values()
    for name,x,hazard in [('hazardlava',620,'lava'),('hazardhead',660,'headspace'),('hazardfall',700,'fall')]:
        saved=lane.place(a,x);say(a,'sethome '+name,'homeSet',name=name)
        lane.place(c,x);say(c,'setwarp '+name,'warpSet',name=name);lane.perm(a,'allium.warps.'+name)
        origin=lane.place(a,0);lane.fixture(f'terrain world {x} 0 {hazard}')
        for command in ['home '+name,'warp '+name]:refused(a,command,'unsafe')
        say(a,'delhome '+name,'homeDeleted',name=name);say(c,'delwarp '+name,'warpDeleted',name=name)
    lane.fixture('world gate_deleted');saved=lane.place(a,30,world='gate_deleted');say(a,'sethome lost','homeSet',name='lost')
    lane.place(c,30,world='gate_deleted');say(c,'setwarp lost','warpSet',name='lost');lane.perm(a,'allium.warps.lost')
    safe=lane.place(a,0);lane.place(c,60)
    unloaded=lane.fixture('unloadworld gate_deleted');require(unloaded['unloaded'] and unloaded['worldUUID']==saved['worldUUID'],
            'saved destination has genuinely unloaded world UUID',unloaded)
    for command in ['home lost','warp lost']:refused(a,command,'unsafe')
    # Revalidation at completion: start from a loaded saved UUID, unload it during warmup.
    lane.fixture('world gate_warm_delete');saved=lane.place(a,30,world='gate_warm_delete');say(a,'sethome warmgone','homeSet',name='warmgone')
    lane.place(c,30,world='gate_warm_delete');say(c,'setwarp warmgone','warpSet',name='warmgone');lane.perm(a,'allium.warps.warmgone')
    lane.place(a,0);lane.place(c,60);lane.reload({'teleport.warmup-seconds':3})
    clear(a);mark=a.cmd('home warmgone');wait(lambda:a.state()['teleportWarming'],'home warmup before world unload')
    lane.fixture('unloadworld gate_warm_delete');window(3.3)
    require(near(safe,a.state()) and not plugin_events(a) and not a.state()['teleportWarming'],'world unload during home warmup refuses without event',a.state())
    expect_sequence(a,mark,[('warmup',{'time':'3.0'}),('unsafe',{})],'world unload returns exact complete bounded response')
    lane.reload({'teleport.warmup-seconds':1})
    for name in ['lost','warmgone']:say(a,'delhome '+name,'homeDeleted',name=name);say(c,'delwarp '+name,'warpDeleted',name=name)
    lane.place(a,90);travel(a,'home',observations['defaultHome'],'safe home still works after unsafe and deleted worlds')

def privacy_permissions():
    a,b,c=lane.bots.values();lane.place(a,0);clear(a,b)
    mark1=refused(a,'warp secret','warpNotFound',name='secret');first=a.chats(mark1)
    mark2=refused(a,'warp no_such_warp','warpNotFound',name='no_such_warp');second=a.chats(mark2)
    require(bool(first) and normalized_components(first,'secret')==normalized_components(second,'no_such_warp'),
            'denied and unknown warp have identical full bounded raw components',dict(denied=first,unknown=second))
    lane.fixture('vanish '+b.name+' on');targetmark=len(b.lines)
    firstmark=refused(a,'home '+b.name,'homeNotFound',name=b.name.lower());first=a.chats(firstmark)
    secondmark=refused(a,'home NoSuchTravelBot','homeNotFound',name='nosuchtravelbot');second=a.chats(secondmark)
    require(bool(first) and normalized_components(first,b.name.lower())==normalized_components(second,'nosuchtravelbot') and not b.chats(targetmark),
            'vanished and unknown player-like home names have same full generic components and no notification',dict(vanished=first,unknown=second))
    lane.fixture('vanish '+b.name+' off')
    for command,node in [('home','allium.home'),('warp gate','allium.warp'),('homes','allium.homes'),('warps','allium.warps')]:
        lane.perm(a,node,False);clear(a);before=a.state();mark=a.cmd(command);window(1.3);responses=a.chats(mark)
        permission=lane.fixture('message NO_PERMISSION')['plain']
        require(([d['plain'] for d in responses]==[permission] or hidden_command_response(responses,command)) and near(before,a.state()) and not plugin_events(a),
                command+' explicit permission denial or exact native hidden-command response, with no event',dict(responses=responses,before=before,after=a.state()))
        lane.perm(a,node)
    for command,node in [('sethome blocked','allium.sethome'),('delhome','allium.delhome')]:
        before=row_snapshot();lane.perm(a,node,False);mark=a.cmd(command);window(.4);responses=a.chats(mark)
        permission=lane.fixture('message NO_PERMISSION')['plain']
        require(([d['plain'] for d in responses]==[permission] or hidden_command_response(responses,command)) and row_snapshot()==before,
                command+' permission refusal preserves saved rows',responses);lane.perm(a,node)

def persistence():
    a,b,c=lane.bots.values();lane.fixture('world gate_persist')
    home=lane.place(a,30,world='gate_persist');say(a,'sethome persist','homeSet',name='persist')
    warp=lane.place(c,60,world='gate_persist');say(c,'setwarp persist','warpSet',name='persist');lane.perm(a,'allium.warps.persist')
    # Deleted names remain absent after restart as well as retained names remaining present.
    say(a,'sethome removed','homeSet',name='removed');say(a,'delhome removed','homeDeleted',name='removed')
    say(c,'setwarp removed','warpSet',name='removed');say(c,'delwarp removed','warpDeleted',name='removed')
    lane.reload({'teleport.cooldowns.home':180,'teleport.cooldowns.warp':180})
    lane.place(a,0);travel(a,'home persist',home);lane.place(a,0);travel(a,'warp persist',warp)
    observations['beforeRestartRows']=row_snapshot();lane.stop()
    import sqlite3
    databases=list((lane.path/'plugins/Allium').rglob('*.db'))
    if len(databases)!=1:raise RuntimeError('one stopped SQLite database required: '+str(databases))
    with sqlite3.connect(str(databases[0])) as db:
        db.row_factory=sqlite3.Row
        names=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'") if re.fullmatch(r'allium_.*(home|warp|travel|teleport|cooldown).*',r[0])]
        observations['stoppedRows']={name:[dict(r) for r in db.execute('SELECT * FROM '+name)] for name in names}
    require(bool(rows_for(observations['beforeRestartRows'],'home','persist')) and bool(rows_for(observations['beforeRestartRows'],'warp','persist')),'saved home and warp rows independently observed before restart')
    lane.boot();lane.fixture('world gate_other');lane.fixture('world gate_persist')
    for bot in [a,b,c]:bot.connect()
    lane.perm(a,'allium.sethome.multiple.3');lane.perm(a,'allium.warps.persist')
    for command in ['home persist','warp persist']:
        lane.place(a,0);cooldown_refusal(a,command,180,label=command+' cooldown persists across clean restart')
    lane.reload({'teleport.cooldowns.home':0,'teleport.cooldowns.warp':0});lane.perm(a,'allium.teleport.bypass.cooldown')
    lane.place(a,0);travel(a,'home persist',home,'home world UUID and XYZ survive restart')
    lane.place(a,0);travel(a,'warp persist',warp,'warp world UUID and XYZ survive restart')
    listing(a,'homes',['home','persist'],['removed']);listing(a,'warps',['persist'],['removed','secret'])
    refused(a,'home removed','homeNotFound',name='removed');lane.perm(a,'allium.warps.removed');refused(a,'warp removed','warpNotFound',name='removed')
    lane.perm(a,'allium.teleport.bypass.cooldown',False)

def final_health():
    for b in lane.bots.values():
        state=b.state();require(state['sameService'] and state['ready'] and state['warpsReady'] and not state['pending'],
                'single ready travel service with settled operations for '+b.name,state)
    serious=[s.strip() for s in lane.logs if re.search(r'(An Allium .* task failed:|Could not pass event|generated an exception|Unhandled exception|Exception in thread|S15G-FAIL|Error occurred while|Task #\d+.*exception|callback.*(error|exception)|Travel storage failed|Travel data unavailable after bounded retries)',s,re.I)]
    require(not serious,'no event, task, fixture, or callback errors',serious)
    clienterrors=[dict(player=b.name,line=s.strip()) for b in lane.bots.values() for s in b.lines if 'TPG-CLIENTERROR' in s or 'TPG-TRANSPORT' in s]
    require(not clienterrors,'no client decoder or transport errors',clienterrors)

try:
    verify_infrastructure()
    build()
    for client in ['client-mcp.cjs','client-raw26.cjs']:subprocess.run(['node','--check',str(HERE/client)],check=True)
    if A.prepare_only:
        emit('PREPARED: Java 17 helper compiled; both clients parsed; no server started')
    else:
        lane=Lane();lane.boot()
        for name in ['TravelA','TravelB','TravelStaff']:lane.bot(name)
        selected=[('default-and-multiple-home-crud',basic_homes),('highest-permission-limit-and-staff-max',limits),
                  ('snapshot-bound-home-confirmation',home_confirm),('named-warps-permission-list-delete',warp_crud),
                  ('snapshot-and-revision-bound-warp-confirmation',warp_confirm),('warp-list-tab-privacy-and-cap',tab_privacy),
                  ('home-warp-shared-warmup-movement-cooldown',pipeline),('warmup-warp-permission-and-revision-recheck',warp_recheck),
                  ('saved-destination-unsafe-and-unloaded-world',unsafe_worlds),('permissions-vanish-generic-refusal',privacy_permissions),
                  ('restart-home-warp-cooldown-persistence',persistence),('final-client-service-health',final_health)]
        for name,fn in selected:case(name,fn)
except Exception as e:
    require(False,'harness: '+str(e))
    with (RUN/'exceptions.log').open('a') as f:f.write(traceback.format_exc()+'\n')
finally:
    if lane:
        try:lane.stop()
        except Exception as e:require(False,'cleanup: '+str(e))
    require(all(p['exit']==0 and not p['forced'] and not p.get('error') for p in processes),'all owned processes exit zero without forced termination',processes)
    if lane:
        bad=[s.strip() for s in lane.logs if re.search(r'(An Allium .* task failed:|Could not pass event|generated an exception|Unhandled exception|Exception in thread|S15G-FAIL|Error occurred while|callback.*(error|exception)|Travel storage failed|Travel data unavailable after bounded retries|not saved|outcome unknown|did not finish|shutdown timed out)',s,re.I)]
        require(not bad,'no persisted callback or shutdown errors',bad)
    # Parent production work is expressly allowed during PREPARE; only live execution freezes it.
    changed=[p for p,h in SOURCE_HASHES.items() if not Path(p).is_file() or sha(Path(p))!=h]
    if not A.prepare_only:require(not changed and set(source_files())==set(SOURCE_FILES),'production/helper/client source hashes remain frozen',changed)
    require(sha(JAR)==jar_hash and (A.prepare_only or sha(A.allium_jar)==jar_hash),'Allium jar snapshot unchanged')
    require(dependency_fingerprint()==DEPENDENCY_HASH,'cached real-client dependency tree unchanged')
    if not A.prepare_only:require(len(cases)==len(PLANNED_CASES) and {c['name'] for c in cases}==set(PLANNED_CASES) and all(c['status']=='PASS' for c in cases),'all '+str(len(PLANNED_CASES))+' planned named cases executed and passed')
    manifest=dict(schema=1,suite=A.suite,run=str(RUN),mode='prepare' if A.prepare_only else 'live',passed=not failures and not A.prepare_only,
        started=started,seconds=round(time.time()-started,3),jarSha256=jar_hash,sourceHashes=SOURCE_HASHES,
        helperSha256=sha(RUN/'helper/TravelGate.jar') if (RUN/'helper/TravelGate.jar').exists() else None,
        serverSha256=sha(INFRA/('servers/spigot-1.20.1.jar' if A.suite=='spigot' else 'servers/paper-26.3-141.jar')),
        contract=CONTRACT,plannedCases=PLANNED_CASES,unrunCases=[n for n in PLANNED_CASES if n not in {c['name'] for c in cases}],
        clientDependencyTreeSha256=DEPENDENCY_HASH,apiSha256=sha(API),clientDependency=json.loads((MCP/'package.json').read_text())['version'],
        processes=processes,cases=cases,checks=checks,failures=failures,observations=observations)
    (RUN/'manifest.json').write_text(json.dumps(manifest,indent=2,default=str)+'\n');emit('MANIFEST '+str(RUN/'manifest.json'))
sys.exit(1 if failures else 0)
