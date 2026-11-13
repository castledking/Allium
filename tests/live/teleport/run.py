#!/usr/bin/env python3
"""S13 teleport real-protocol gate. No Gradle, production changes, downloads, or provider plugins."""
import argparse
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
P.add_argument('--focus',choices=['full','death-back'],default='full')
P.add_argument('--run-root', type=Path, default=Path('/mnt/storage/.allium-validation/20261113/s13-teleport/runs'))
P.add_argument('--paper-template', type=Path, default=Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-26.3'))
A = P.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]*', A.run_name): P.error('invalid run-name')
if not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/s13-teleport/runs', str(A.run_root.resolve())): P.error('invalid owned run-root')
if not A.prepare_only and not (A.freeze_confirmed and A.allium_jar and A.sha256): P.error('live execution requires parent freeze confirmation, jar and hash')
if A.prepare_only and not A.allium_jar: A.allium_jar=REPO/'build/libs/Allium.jar'
A.allium_jar=A.allium_jar.resolve()
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
lock=open('/mnt/storage/.allium-validation/s13-teleport-ports.lock','a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
for port in [25740,25741]:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',port))
RUN=A.run_root/A.run_name
RUN.mkdir(parents=True,exist_ok=False)
shutil.copy2(A.allium_jar,RUN/'Allium-under-test.jar')
JAR=RUN/'Allium-under-test.jar'
jar_hash=sha(JAR)
# Snapshot all local executed helper/client code and production source contracts. Execute copies.
def source_files():
    return sorted(set([p for p in SOURCE.rglob('*') if p.is_file() and p.suffix in ['.py','.java','.cjs','.yml']]
                       +[REPO/'tests/live/baseline/text-components.cjs',REPO/'tests/live/providers/trigger/process_cleanup.py']
                       +[p for root in ['src/main','allium-core/src/main','allium-api/src/main'] for p in (REPO/root).rglob('*') if p.is_file()]))
SOURCE_FILES=source_files()
SOURCE_HASHES={str(p):sha(p) for p in SOURCE_FILES}
for p in SOURCE_FILES:
    dest=RUN/'harness'/p.relative_to(REPO);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest);dest.chmod(0o444)
HERE=RUN/'harness/tests/live/teleport'
sys.path.insert(0,str(RUN/'harness/tests/live/providers/trigger'))
from process_cleanup import stop_process
checks=[];failures=[];cases=[];observations={};processes=[];lane=None
PLANNED_CASES=['request-accept-tpahere-back','deny-expiry-cancel','real-movement-cancels-warmup','real-damage-cancels-warmup',
               'toggle-off-cancels-pending','offline-mid-warmup','unsafe-lava-void-headspace-fall','tab-vanish',
               'unloaded-preferences-generic-refusal','staff-distinct-permission-toggle-bypass-audit-console','crossworld-back-spawn',
               'death-back-config-switch','history-write-fault-retry','restart-persistence-back-spawn-toggle-cooldown','final-callback-client-service-health']
if A.focus=='death-back':PLANNED_CASES=['death-back-config-switch']
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
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar','cf',str(RUN/'helper/TeleportGate.jar'),'-C',str(classes),'.'],check=True)
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
        if ready:wait(lambda:self.state()['loaded'] and self.state()['ready'],self.name+' preference+teleport data ready',30)
    def write(self,text):alive();self.proc.stdin.write(text+'\n');self.proc.stdin.flush()
    def state(self):return self.lane.fixture('state '+self.name)
    def chats(self,start,end=None):return [json.loads(s.split(' ',1)[1]) for s in self.lines[start:end] if s.startswith('BASEG-CHAT ')]
    def cmd(self,text,chat=True):
        delay=1.05-(time.monotonic()-self.last)
        if delay>0:window(delay)
        self.last=time.monotonic();mark=len(self.lines)
        state=self.state();before=len(state['events']);self.arrival_mark=state['eventSequence']
        self.write('cmd '+text)
        wait(lambda:any(e['type']=='command' and e['command']=='/'+text for e in self.state()['events'][before:]),self.name+' real packet command /'+text)
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
        self.write('tab tptoggle ')
        fence=wait(lambda:next((r for r in data('MSGG-TABREQ') if r['id']!=req['id']),None),'completion fence request')
        positive=wait(lambda:response(fence['id']),'completion fence response')
        if not {'on','off'}.issubset(set(positive['matches'])):raise RuntimeError('empty completion positive control failed')
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
        self.kind=A.suite;self.port=25740 if self.kind=='spigot' else 25741;self.path=RUN/self.kind
        self.proc=None;self.logs=[];self.bots={};self.path.mkdir();(self.path/'plugins/Allium').mkdir(parents=True);(self.path/'tmp').mkdir()
        shutil.copy2(JAR,self.path/'plugins/Allium.jar');shutil.copy2(RUN/'helper/TeleportGate.jar',self.path/'plugins/TeleportGate.jar')
        with zipfile.ZipFile(JAR) as z:config=yaml.safe_load(z.read('config.yml'))
        config.setdefault('teleport',{}).update({'warmup-seconds':1,'movement-tolerance':.1,'request-expiry-seconds':2,
             'cooldowns':{},'back':{'include-death':False},'safety':{'enabled':True,'adjust':True,'search-radius':3,'vertical-search':4,
             'avoid-lava':True,'avoid-void':True,'avoid-suffocation':True,'avoid-falls':True,'load-destination-chunk':True}})
        config.setdefault('preferences',{})['preload-wait-millis']=0
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
        wait(lambda:any('S13G-GATE READY' in s for s in self.logs[mark:]),'helper ready',30)
        require(any('teleport' in s.lower() and 'enabled' in s.lower() for s in self.logs[mark:]),'teleport module enabled')
    def console(self,text):self.proc.stdin.write(text+'\n');self.proc.stdin.flush()
    def fixture(self,text):
        mark=len(self.logs);self.console('s13g '+text)
        line=wait(lambda:next((s for s in self.logs[mark:] if 'S13G-DONE '+text+' => ' in s or 'S13G-FAIL '+text+' => ' in s),None),'fixture '+text,120 if text.startswith('world ') else 15)
        if 'S13G-FAIL' in line:raise RuntimeError(line.strip())
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
    window(seconds);after=b.state();require(near(before,after) and not physical(b),label,dict(before=before,after=after))
def ready(b):wait(lambda:b.state()['loaded'] and b.state()['ready'] and not b.state()['historyPending'],b.name+' settled data',30)
def request(a,b,command='tpa'):
    mark=len(b.lines);a.cmd(command+' '+b.name)
    wait(lambda:b.chats(mark),b.name+' receives real request notification');return mark
def warm(b):wait(lambda:b.state()['warming'],b.name+' warmup starts',4)

def basics():
    a,b,c=lane.bots.values()
    origin=lane.place(a,0);dest=lane.place(b,30);lane.place(c,60);clear(a,b,c)
    request(a,b);b.cmd('tpaccept '+a.name);arrived(a,dest,'request accept moves actual coordinates')
    require(len(physical(a))==1,'request accept exactly one physical teleport',physical(a));ready(a)
    a.cmd('back');arrived(a,origin,'back restores successful teleport origin');ready(a)
    lane.place(a,0);lane.place(b,30);clear(a,b)
    request(a,b,'tpahere');b.cmd('tpaccept '+a.name);arrived(b,origin,'tpahere accept moves recipient to requester');ready(b)

def denials():
    a,b,_=lane.bots.values()
    for action in ['deny','expiry','cancel']:
        origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b)
        if action=='deny':b.cmd('tpdeny '+a.name)
        elif action=='cancel':a.cmd('tpacancel')
        else:window(2.4)
        b.cmd('tpaccept '+a.name)
        stationary(a,origin,action+' prevents actual teleport')
        require(a.state()['pendingRequests']==0,action+' consumes/expires request')
    lane.reload({'teleport.warmup-seconds':3,'teleport.request-expiry-seconds':8})
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tpaccept '+a.name);warm(a);a.cmd('tpacancel')
    stationary(a,origin,'tpacancel cancels already accepted warmup',3.2)
    require(not a.state()['warming'],'explicit cancel clears active warmup')
    lane.reload({'teleport.warmup-seconds':1,'teleport.request-expiry-seconds':2})

def movement():
    a,b,_=lane.bots.values()
    lane.reload({'teleport.warmup-seconds':3,'teleport.request-expiry-seconds':8})
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tpaccept '+a.name);warm(a)
    a.move(origin['x']+1,origin['y'],origin['z'])
    wait(lambda:any(e['type']=='move' and not e['preparing'] for e in a.state()['events']),'real movement event')
    wait(lambda:not a.state()['warming'],'movement cancels warmup')
    window(3.2);after=a.state()
    require(abs(after['x']-(origin['x']+1))<.2 and near(after,dict(origin,x=origin['x']+1)) and not physical(a),'real movement cancels actual teleport',after)

def damage():
    a,b,_=lane.bots.values()
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tpaccept '+a.name);warm(a)
    outcome=lane.fixture('damage '+a.name+' 2')
    events=[e for e in a.state()['events'] if e['type']=='damage']
    require(events and events[-1]['amount']>0 and not events[-1]['cancelled'] and outcome['health']<20,'API damage produced actual uncancelled EntityDamageEvent',events)
    wait(lambda:not a.state()['warming'],'damage cancels warmup')
    stationary(a,origin,'real damage cancels physical teleport',3.2)
    lane.reload({'teleport.warmup-seconds':1,'teleport.request-expiry-seconds':2})

def toggles():
    a,b,_=lane.bots.values()
    lane.reload({'teleport.request-expiry-seconds':30})
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tptoggle off')
    require(b.state()['pendingRequests']==0,'toggle off immediately removes pending request before any accept',b.state())
    b.cmd('tpaccept '+a.name)
    stationary(a,origin,'toggle off cancels pending request');require(b.state()['acceptTeleportRequests'] is False,'tptoggle effective preference off')
    mark=len(b.lines);a.cmd('tpa '+b.name);window(.4)
    require(not b.chats(mark),'toggle off refuses new request without target notification');require(a.state()['pendingRequests']==0,'toggle off no request created')
    b.cmd('tptoggle on');require(b.state()['acceptTeleportRequests'] is True,'tptoggle effective preference on')
    lane.reload({'teleport.warmup-seconds':3,'teleport.request-expiry-seconds':30})
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tpaccept '+a.name);warm(a);b.cmd('tptoggle off')
    stationary(a,origin,'toggle off cancels accepted active warmup',3.2)
    require(not a.state()['warming'],'toggle off clears active warmup');b.cmd('tptoggle on')
    lane.reload({'teleport.warmup-seconds':1,'teleport.request-expiry-seconds':2})

def offline():
    a,b,_=lane.bots.values()
    lane.reload({'teleport.warmup-seconds':3,'teleport.request-expiry-seconds':8})
    origin=lane.place(a,0);lane.place(b,30);clear(a,b);request(a,b);b.cmd('tpaccept '+a.name);warm(a);b.disconnect()
    stationary(a,origin,'target disconnect during warmup cancels physical teleport',3.3)
    b.connect();lane.reload({'teleport.warmup-seconds':1,'teleport.request-expiry-seconds':2})

def unsafe():
    a,b,_=lane.bots.values()
    for hazard in ['lava','void','headspace','fall']:
        origin=lane.place(a,0);dest=lane.place(b,120 if hazard in ['lava','void'] else 160,0,terrain=hazard);clear(a,b)
        reply_mark=len(a.lines);request(a,b);b.cmd('tpaccept '+a.name,chat=False);window(1.6);after=a.state()
        refused=near(after,origin) and not physical(a)
        adjusted=(after['worldUUID']==dest['worldUUID'] and abs(after['x']-dest['x'])<=3.6 and abs(after['z']-dest['z'])<=3.6
                  and abs(after['y']-dest['y'])<=4.1 and after['safeFeet'] and after['safeHead'] and after['floor'] not in ['AIR','CAVE_AIR','LAVA','WATER'])
        require(refused or adjusted,hazard+' refuses or bounds safe adjustment using actual blocks/coordinates',dict(origin=origin,destination=dest,actual=after))
        if refused:
            expected=lane.fixture('message TELEPORT_UNSAFE '+a.name)['plain'];responses=a.chats(reply_mark)
            require(sum(d['plain']==expected and not d['overlay'] for d in responses)==1,hazard+' fresh exact requester unsafe refusal',dict(expected=expected,responses=responses))
        if after['warming']:raise RuntimeError(hazard+' left stale warmup')
    origin=lane.place(a,0);dest=lane.place(b,30,terrain='adjust');clear(a,b)
    request(a,b);b.cmd('tpaccept '+a.name,chat=False);wait(lambda:physical(a),'bounded safety adjustment teleport');ready(a);after=a.state()
    require(not near(after,origin) and after['worldUUID']==dest['worldUUID'] and abs(after['x']-dest['x'])<=3.6 and abs(after['z']-dest['z'])<=3.6 and abs(after['y']-dest['y'])<=4.1 and after['safeFeet'] and after['safeHead'] and after['floor']=='STONE','single head obstruction adjusts to bounded safe coordinates',dict(destination=dest,actual=after))
    lane.place(b,30);origin=lane.place(a,0);clear(a,b)
    # Use a safe target as positive control after unsafe cases.
    request(a,b);b.cmd('tpaccept '+a.name,chat=False);arrived(a,b.state(),'safe destination positive control after hazards');ready(a)

def visibility():
    a,b,c=lane.bots.values()
    lane.place(a,0);lane.place(b,30);clear(a,b)
    visible=a.tab('tpa Tele');require(b.name in visible and c.name in visible,'completion visible online players',visible)
    lane.fixture('vanish '+b.name+' on')
    matches=a.tab('tpa Tele');require(b.name not in matches and c.name in matches,'completion filters vanished target with visible positive control',matches)
    mark=len(b.lines);before=a.state();s1=a.cmd('tpa '+b.name);window(.4)
    s2=a.cmd('tpa NoSuchTeleGate');window(.4);end=len(a.lines)
    first=a.chats(s1,s2);second=a.chats(s2,end)
    one=normalized_components(first,b.name)
    two=normalized_components(second,'NoSuchTeleGate')
    require(one and two and one==two,'vanished and unknown target have same complete bounded generic response arrays',dict(vanished=first,unknown=second,normalizedVanished=one,normalizedUnknown=two))
    require(not b.chats(mark) and a.state()['pendingRequests']==0 and near(a.state(),before),'vanished request no notification, pending state, or movement')
    lane.fixture('vanish '+b.name+' off')
    clear(a,b);origin=lane.place(a,0);dest=lane.place(b,30)
    a.cmd('tpa TeleB');b.cmd('tpaccept TeleA');arrived(a,dest,'exact player names resolve');ready(a)
    lane.place(a,0);clear(a);a.cmd('tpa Tele');stationary(a,origin,'ambiguous player prefix refuses',.4)
    a.cmd('tpa TeleB');b.cmd('tpdeny TeleA')

def unloaded():
    a,b,c=lane.bots.values()
    expected=lane.fixture('message PREFERENCES_UNAVAILABLE '+b.name)
    loading=lane.fixture('message TELEPORT_LOADING '+a.name)
    def exact_responses(start,allowed):
        responses=a.chats(start)
        return ([d['plain'] for d in responses] in [[v] for v in allowed]
                and all(not d['overlay'] for d in responses)),responses
    b.disconnect();lane.fixture('arm preferences.load-slow 1');b.connect(ready=False)
    require(not b.state()['loaded'],'slow target preference load premise')
    mark=len(b.lines);start=a.cmd('tpa '+b.name);window(.4)
    good,responses=exact_responses(start,[expected['plain']])
    require(good,'unloaded target exact resolved preference-unavailable response',dict(expected=expected,responses=responses))
    require(not b.chats(mark) and a.state()['pendingRequests']==0,'unloaded target creates no request and sends no target notification',responses)
    ready(b);lane.fixture('reset')
    a.disconnect();lane.fixture('arm preferences.load-slow 1');a.connect(ready=False)
    require(not a.state()['loaded'],'slow sender preference load premise');mark=len(b.lines);start=a.cmd('tpa '+b.name);window(.4)
    good,responses=exact_responses(start,[expected['plain'],loading['plain']])
    require(good,'unloaded sender exact resolved preference-unavailable or teleport-loading response',dict(expected=[expected,loading],responses=responses))
    require(not b.chats(mark) and a.state()['pendingRequests']==0,'unloaded sender refused without target notification',responses);ready(a);lane.fixture('reset')

def completed_audit(actor,mover,target,command,mark):
    mover_id=mover.state()['uuid'];target_id=target.state()['uuid']
    expected=dict(actor=actor,mover=mover_id,target=target_id,command=command,result='completed',reason='bukkit-teleport-success')
    def matching():
        found=[]
        for line in lane.logs[mark:]:
            if 'Teleport audit completed' not in line:continue
            fields=dict(re.findall(r'(\w+)=([^\s]+)',line))
            if all(fields.get(key,'').lower()==value.lower() for key,value in expected.items()):found.append(line.strip())
        return found
    result=wait(matching,'fresh physical completion audit '+actor+'/'+command,5)
    require(bool(result),'physical completion audit '+actor+'/'+command+' matches actor mover target command and reason',dict(logBaseline=mark,expected=expected,matching=result))


def staff():
    a,b,c=lane.bots.values()
    origin=lane.place(a,0);dest=lane.place(b,30);lane.place(c,60);clear(a,b,c)
    lane.perm(c,'allium.tp',False);s=c.cmd('tp '+a.name+' '+b.name);stationary(a,origin,'staff force requires distinct command permission',.4)
    require([d['plain'] for d in c.chats(s)]==[lane.fixture('message NO_PERMISSION '+c.name)['plain']] or hidden_command_response(c.chats(s),'tp '+a.name+' '+b.name),'force unauthorized command reports explicit permission denial or exact hidden-command key',c.chats(s))
    lane.perm(c,'allium.tp');lane.fixture('op '+c.name+' true');lane.fixture('unsetperm '+c.name+' allium.teleport.bypass.toggle')
    require(c.state()['op'] and not c.state()['toggleBypass'],'operator has no implicit toggle bypass')
    b.cmd('tptoggle off');mark=len(lane.logs);c.cmd('tp '+a.name+' '+b.name);stationary(a,origin,'force command permission alone cannot bypass toggle',.4)
    require(any('Teleport audit' in s and 'denied' in s.lower() for s in lane.logs[mark:]),'denied staff force audit emitted',lane.logs[mark:])
    lane.perm(c,'allium.teleport.bypass.toggle');mark=len(lane.logs);c.cmd('tp '+a.name+' '+b.name,chat=False);arrived(a,dest,'explicit staff force toggle bypass reaches actual target');ready(a)
    audit=[s for s in lane.logs[mark:] if 'Teleport audit' in s]
    require(any(all(v.lower() in s.lower() for v in [c.name,a.state()['uuid'],b.state()['uuid'],'authorized']) and 'reason' in s.lower() for s in audit),'authorized force audit contains actor mover target reason',audit)
    require(len(physical(a))==1,'staff force exactly one physical teleport');completed_audit(c.name,a,b,'tp',mark);b.cmd('tptoggle on')
    lane.perm(c,'allium.tphere');lane.place(a,0);here=lane.place(c,60);clear(a)
    mark=len(lane.logs);c.cmd('tphere '+a.name,chat=False);arrived(a,here,'staff tphere moves target through service');ready(a);completed_audit(c.name,a,c,'tphere',mark)
    lane.place(c,60);clear(c);mark=len(lane.logs);c.cmd('tp '+b.name);arrived(c,b.state(),'staff one-argument tp moves actor through service');ready(c);completed_audit(c.name,c,b,'tp',mark)
    lane.perm(c,'allium.tptoggle.others');c.cmd('tptoggle off '+b.name)
    require(b.state()['acceptTeleportRequests'] is False,'explicit others permission changes target toggle')
    c.cmd('tptoggle on '+b.name)
    require(b.state()['acceptTeleportRequests'] is True,'others toggle restores target acceptance')
    lane.perm(c,'allium.teleport.bypass.toggle',False);lane.fixture('op '+c.name+' false')
    # Console is a fourth actor; commands are server console input, no fixture dispatch.
    origin=lane.place(a,0);dest=lane.place(b,30);clear(a);mark=len(lane.logs);lane.console('tp '+a.name+' '+b.name)
    arrived(a,dest,'console two-argument force actual coordinates');ready(a);completed_audit('CONSOLE',a,b,'tp',mark)
    require(any('Teleport audit' in s and 'console' in s.lower() and a.state()['uuid'] in s and b.state()['uuid'] in s for s in lane.logs[mark:]),'console force audit contains actual participants')

def crossworld():
    a,b,c=lane.bots.values()
    lane.fixture('world gate_other');origin=lane.place(a,0);dest=lane.place(b,30,world='gate_other');clear(a,b)
    request(a,b);b.cmd('tpaccept '+a.name);arrived(a,dest,'request crosses world UUID through service');ready(a)
    a.cmd('back');arrived(a,origin,'back crosses to original world UUID');ready(a)
    lane.perm(c,'allium.setspawn');spawn=lane.place(c,60,world='gate_other');c.cmd('setspawn');ready(c)
    lane.place(a,0);clear(a);a.cmd('spawn');arrived(a,spawn,'spawn uses saved crossworld UUID');ready(a)
    observations['savedSpawn']=spawn

def death_back():
    a,b,c=lane.bots.values()
    def kill_and_respawn(suffix):
        deathloc=lane.place(a,90)
        # Let vanilla/Paper join/respawn protection expire naturally; never reset its timer.
        window(5);clear(a)
        outcome=lane.fixture('damage '+a.name+' 1000')
        state=wait(lambda:(v if (v:=a.state())['dead'] and v['health']==0 else None),'actual player death '+suffix)
        damage_events=[e for e in state['events'] if e['type']=='damage' and not e['cancelled'] and e['amount']>0]
        deaths=[e for e in state['events'] if e['type']=='death' and near(e,deathloc)]
        require(outcome['health']==0 and bool(damage_events) and bool(deaths),'actual uncancelled damage and PlayerDeathEvent health0 '+suffix,dict(outcome=outcome,damage=damage_events,deaths=deaths))
        mark=len(a.lines);a.respawn()
        wait(lambda:not a.state()['dead'],'real packet respawn '+suffix)
        wait(lambda:any(e['type']=='respawn' for e in a.state()['events']) and any(s.startswith('TPG-POS ') for s in a.lines[mark:]),'server respawn event and native position acknowledgement '+suffix)
        require(any(s.startswith('TPG-NATIVE ') or s.startswith('TPG-RESPAWN') for s in a.lines[mark:]),'real client respawn packet emitted '+suffix,a.lines[mark:])
        window(.5)
        return deathloc
    lane.reload({'teleport.back.include-death':False})
    origin=lane.place(a,0);dest=lane.place(b,30);clear(a);request(a,b);b.cmd('tpaccept '+a.name);arrived(a,dest,'death false setup teleport');ready(a)
    kill_and_respawn('false')
    a.cmd('back');arrived(a,origin,'death false preserves prior teleport back location');ready(a)
    lane.reload({'teleport.back.include-death':True})
    deathloc=kill_and_respawn('true');ready(a)
    a.cmd('back');arrived(a,deathloc,'death true saves actual death world/coordinates');ready(a)
    lane.reload({'teleport.back.include-death':False})

def faults():
    a,b,c=lane.bots.values()
    origin=lane.place(a,0);dest=lane.place(b,30);clear(a,b);lane.fixture('arm teleport.write-fail 1')
    request(a,b);b.cmd('tpaccept '+a.name);arrived(a,dest,'failed history write does not undo actual teleport');ready(a)
    trip=lane.fixture('fault teleport.write-fail')
    require(trip['tripped']==1 and len(physical(a))==1,'history persistence retry trips exactly once and never repeats physical teleport',dict(fault=trip,physical=physical(a)))
    a.cmd('back');arrived(a,origin,'retry persisted successful teleport back location');ready(a)
    lane.fixture('reset')
    # setspawn must persist before publishing; a failed write cannot replace the active spawn.
    lane.place(c,100,world='gate_other');lane.fixture('arm teleport.write-fail 1');mark=c.cmd('setspawn')
    require(lane.fixture('fault teleport.write-fail')['tripped']==1,'setspawn persistence refusal trips intended fault')
    require(any('save' in d['plain'].lower() or 'storage' in d['plain'].lower() for d in c.chats(mark)),'setspawn failed persistence reports refusal',c.chats(mark))
    lane.fixture('reset');lane.place(a,0);clear(a);a.cmd('spawn');arrived(a,observations['savedSpawn'],'failed setspawn does not publish unpersisted location');ready(a)

def persistence():
    a,b,c=lane.bots.values()
    # Establish independently observable back, spawn, toggle and cooldown values, then clean restart.
    origin=lane.place(a,0);dest=lane.place(b,30);clear(a);request(a,b);b.cmd('tpaccept '+a.name);arrived(a,dest,'restart setup teleport');ready(a)
    b.cmd('tptoggle off');lane.reload({'teleport.cooldowns.spawn':60})
    a.cmd('spawn');arrived(a,observations['savedSpawn'],'restart setup spawn cooldown');ready(a)
    # /spawn saved the preceding target as back. Do not issue /back until after restart.
    observations['beforeRestart']=dict(backExpected=dest,spawnExpected=observations['savedSpawn'],rows=lane.fixture('rows'))
    lane.stop();require(all(p['exit']==0 and not p['forced'] for p in processes),'pre-restart every client and server exit zero without force',processes)
    # Independent stopped DB snapshot. SQLite only, disposable DB read-only.
    import sqlite3
    dbpath=lane.path/'plugins/Allium/allium.db'
    with sqlite3.connect('file:'+str(dbpath)+'?mode=ro',uri=True) as db:
        db.row_factory=sqlite3.Row
        names=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'") if re.fullmatch(r'allium_.*(teleport|spawn|back|cooldown|preferences).*',r[0])]
        observations['stoppedRows']={name:[dict(r) for r in db.execute('SELECT * FROM '+name)] for name in names}
    lane.boot();lane.fixture('world gate_other')
    for bot in [a,b,c]:bot.connect()
    require(b.state()['acceptTeleportRequests'] is False,'tptoggle off persists across clean restart')
    lane.place(a,0);clear(a);before=a.state();start=a.cmd('spawn');stationary(a,before,'spawn cooldown persists across clean restart',1.4)
    require(any('cooldown' in d['plain'].lower() or 'wait' in d['plain'].lower() for d in a.chats(start)),'persisted cooldown reports wait',a.chats(start))
    a.cmd('back');arrived(a,dest,'saved back world/coordinates persist across restart');ready(a)
    lane.reload({'teleport.cooldowns.spawn':0})
    lane.perm(a,'allium.teleport.bypass.cooldown')
    lane.place(a,0);clear(a);a.cmd('spawn');arrived(a,observations['savedSpawn'],'saved crossworld spawn persists across restart');ready(a)
    b.cmd('tptoggle on')

def final_health():
    for b in lane.bots.values():require(b.state()['sameService'],'single teleport service survives reloads for '+b.name)
    # Any unexpected callback/task/fixture exception remains a failure, even if later cases work.
    serious=[s.strip() for s in lane.logs if re.search(r'(An Allium .* task failed:|Could not pass event|generated an exception|Unhandled exception|Exception in thread|S13G-FAIL|Error occurred while|Task #\d+.*exception|callback.*(error|exception))',s,re.I)]
    require(not serious,'no event, task, fixture, or callback errors',serious)
    clienterrors=[dict(player=b.name,line=s.strip()) for b in lane.bots.values() for s in b.lines if 'TPG-CLIENTERROR' in s or 'TPG-TRANSPORT' in s]
    require(not clienterrors,'no client decoder or transport errors',clienterrors)

try:
    build()
    if A.prepare_only:emit('PREPARED: helper compiled; no server started')
    else:
        lane=Lane();lane.boot()
        for name in ['TeleA','TeleB','TeleStaff']:lane.bot(name)
        selected=[('request-accept-tpahere-back',basics),('deny-expiry-cancel',denials),('real-movement-cancels-warmup',movement),
                        ('real-damage-cancels-warmup',damage),('toggle-off-cancels-pending',toggles),('offline-mid-warmup',offline),
                        ('unsafe-lava-void-headspace-fall',unsafe),('tab-vanish',visibility),('unloaded-preferences-generic-refusal',unloaded),
                        ('staff-distinct-permission-toggle-bypass-audit-console',staff),('crossworld-back-spawn',crossworld),
                        ('death-back-config-switch',death_back),('history-write-fault-retry',faults),('restart-persistence-back-spawn-toggle-cooldown',persistence),
                        ('final-callback-client-service-health',final_health)]
        if A.focus=='death-back':
            window(5)
            case('death-back-config-switch',death_back)
            final_health()
        else:
            for name,fn in selected:case(name,fn)
except Exception as e:
    require(False,'harness: '+str(e))
    with (RUN/'exceptions.log').open('a') as f:f.write(traceback.format_exc()+'\n')
finally:
    if lane:
        try:lane.stop()
        except Exception as e:require(False,'cleanup: '+str(e))
    require(all(p['exit']==0 and not p['forced'] and not p.get('error') for p in processes),'all owned processes exit zero without forced termination',processes)
    # Scan after stop too: shutdown callback/storage failures cannot be hidden by earlier PASS.
    if lane:
        bad=[s.strip() for s in lane.logs if re.search(r'(An Allium .* task failed:|Could not pass event|generated an exception|Unhandled exception|Exception in thread|S13G-FAIL|Error occurred while|callback.*(error|exception)|not saved|outcome unknown|did not finish|Teleport shutdown timed out)',s,re.I)]
        require(not bad,'no persisted callback or shutdown errors',bad)
    changed=[p for p,h in SOURCE_HASHES.items() if not Path(p).is_file() or sha(Path(p))!=h]
    require(not changed and set(source_files())==set(SOURCE_FILES),'production/helper/client source hashes remain frozen',changed)
    require(sha(JAR)==jar_hash and sha(A.allium_jar)==jar_hash,'frozen Allium jar unchanged')
    require(dependency_fingerprint()==DEPENDENCY_HASH,'cached real-client dependency tree unchanged')
    if not A.prepare_only:require(len(cases)==len(PLANNED_CASES) and {c['name'] for c in cases}==set(PLANNED_CASES) and all(c['status']=='PASS' for c in cases),'all '+str(len(PLANNED_CASES))+' planned named cases executed and passed')
    manifest=dict(suite=A.suite,focus=A.focus,run=str(RUN),mode='prepare' if A.prepare_only else 'live',passed=not failures and not A.prepare_only,
        started=started,seconds=round(time.time()-started,3),jarSha256=jar_hash,sourceHashes=SOURCE_HASHES,
        helperSha256=sha(RUN/'helper/TeleportGate.jar') if (RUN/'helper/TeleportGate.jar').exists() else None,
        serverSha256=sha(INFRA/('servers/spigot-1.20.1.jar' if A.suite=='spigot' else 'servers/paper-26.3-141.jar')),
        plannedCases=PLANNED_CASES,unrunCases=[n for n in PLANNED_CASES if n not in {c['name'] for c in cases}],clientDependencyTreeSha256=DEPENDENCY_HASH,apiSha256=sha(API),clientDependency=json.loads((MCP/'package.json').read_text())['version'],processes=processes,cases=cases,checks=checks,failures=failures,observations=observations)
    (RUN/'manifest.json').write_text(json.dumps(manifest,indent=2,default=str)+'\n')
    emit('MANIFEST '+str(RUN/'manifest.json'))
sys.exit(1 if failures else 0)
