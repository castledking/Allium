#!/usr/bin/env python3
"""Disposable native CE trigger diagnostic. Nonzero on a contract failure; retains evidence.
Never reads production data. Owned disposable roots and loopback25610-12 only.
"""
import argparse, hashlib, json, os, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path
from process_cleanup import stop_process
HERE=Path(__file__).resolve().parent
ROOT=Path('/tmp/allium-s05-20261106/ce-triggers')
p=argparse.ArgumentParser()
p.add_argument('--template',type=Path,default=Path('/tmp/allium-s05-20261105/provider-gate/lane'))
p.add_argument('--artifacts',type=Path,default=Path('/tmp/allium-s05-20261105/provider-gate/artifacts'))
p.add_argument('--port',type=int,choices=[25610,25611,25612],default=25610)
p.add_argument('--run-name',default='native-263')
p.add_argument('--run-root',type=Path,default=ROOT)
p.add_argument('--ce-jar',type=Path,help='explicit full native plugin, including a source-built patched artifact')
p.add_argument('--allium-jar',type=Path,default=HERE.parents[3]/'build/libs/Allium.jar')
p.add_argument('--provenance',type=Path,help='builder provenance JSON, retained unchanged alongside the runtime hash')
p.add_argument('--extended',action='store_true',help='also test full stacks, dynamic chains/repeats, split/merge and partial drops')
p.add_argument('--exact1218',action='store_true',help='Paper1.21.8-60 / Java21 lane; default native artifact is official CE26.9.1')
a=p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_-]+',a.run_name):p.error('invalid run-name')
ROOT=a.run_root.resolve()
if ROOT!=Path('/tmp/allium-s05-20261106/ce-triggers') and not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/ce-count-fix/runs',str(ROOT)):
 p.error('require an owned disposable run root')
run=ROOT/a.run_name
if run.exists():p.error('run directory already exists; choose a fresh --run-name')
run.mkdir(parents=True)
lane=run/'lane';lane.mkdir()
server=client=None
logs=[];client_logs=[];cases=[];failures=[];observations=[];driver_lines=[]
artifact_info=None
def emit(message):
 driver_lines.append(message);print(message,flush=True)
# Controls leave version1 unchanged, despite a material edit; CE must not reset them.
for mode in ['click','drop','pickup']:
 for count,kind in [(7,'control'),(1,'reset'),(7,'reset'),(7,'repeat')]:
  cases.append((f'{mode}-{count}-{kind}',mode,count,kind))
 if a.extended:
  cases.extend([(f'{mode}-64-reset',mode,64,'reset'),(f'{mode}-7-dynamic',mode,7,'dynamic')])
if a.extended:cases.extend([('click-7-split','click',7,'split'),('drop-7-single','drop',7,'single')])
versions={name:1 for name,_,_,_ in cases}
materials={name:'iron_ingot' for name,_,_,_ in cases}
itemfile=lane/'plugins/CraftEngine/resources/ce_trigger/configuration/items.yml'
def definitions():
 text='items:\n'
 for name,_,_,kind in cases:
  title='count=<arg:item.count>' if kind=='dynamic' and versions[name]>1 else 'CE trigger '+name
  text+=f'''  trigger:{name}:
    material: {materials[name]}
    data:
      item_name: {json.dumps(title)}
    updater:
'''
  applicable=range(2,versions[name]+1) if kind=='dynamic' and versions[name]>1 else [versions[name]]
  for version_id in applicable:
   text+=f'''      '{version_id}':
        - type: reset
          keep_components: [minecraft:custom_data]
          keep_tags: [PublicBukkitValues]
'''
 itemfile.write_text(text)
def pump(proc,path,lines):
 with path.open('w') as f:
  for line in proc.stdout:
   f.write(line);f.flush();lines.append(line)
def wait(fn,label,timeout=70):
 end=time.monotonic()+timeout
 while not fn():
  if server and server.poll() is not None:raise RuntimeError('server exited '+str(server.returncode)+' waiting for '+label)
  if time.monotonic()>end:raise RuntimeError('timeout '+label)
  time.sleep(.05)
def cmd(text):
 server.stdin.write(text+'\n');server.stdin.flush()
def packet(text):
 client.stdin.write(text+'\n');client.stdin.flush()
def read_result(suffix):
 file=lane/'plugins/CETriggerGate'/suffix
 wait(file.exists,suffix);return json.loads(file.read_text())
def request_result(action,suffix):
 (lane/'plugins/CETriggerGate'/suffix).unlink(missing_ok=True)
 cmd('cetrigger '+action)
 return read_result(suffix)
def reload_ce():
 start=len(logs);cmd('ce reload')
 wait(lambda:any('Reload successfully' in x or 'Reloaded successfully' in x or 'Successfully reloaded' in x or 'Configs reloaded' in x for x in logs[start:]),'CE reload',45)
 time.sleep(.25)
def require(value,label):
 if not value:failures.append(label)
 emit(('PASS ' if value else 'FAIL ')+label)
def collect_stacks(result):
 stacks=[row['stack'] for row in result['inventory']]+[row['stack'] for row in result['entities']]
 if 'empty' not in result['cursor']:stacks.append(result['cursor'])
 return stacks
def compare_stack(stack,baseline,label,material,version,count=None):
 for key in ['signature','uuid','pdc','charges','behavior','identity','rawSentinel']:
  require(stack.get(key)==baseline.get(key),label+' retained '+key)
 require(stack['material']==material,label+' actual material')
 require(stack['nativeVersion']==str(version),label+' actual CE version')
 if count is not None:require(stack['amount']==count,label+f' actual quantity expected={count} observed={stack["amount"]}')
def observe_case(name,mode,stage):
 result=request_result('observe',name+'-result.json')
 file=name+('-'+stage if stage else '')+'.json';(run/file).write_text(json.dumps(result,indent=2))
 observations.append({'name':name,'mode':mode,'stage':stage,'file':file})
 require(result['counts'][mode]>=1,name+' '+stage+' genuine native '+mode+' event observed')
 require(all(result['counts'][m]==0 for m in ['click','drop','pickup'] if m!=mode),name+' '+stage+' no unrelated trigger')
 ev=result['events'].get(mode+'-monitor',{})
 require(ev.get('nativeServerCallStack') is True and ev.get('cancelled') is False,name+' '+stage+' uncancelled native call stack')
 return result
def trigger(mode,action=None):
 if mode=='pickup':
  for x in [1,1.5,2,2.5,3,3.5]:packet(f'move {x} 100 0.5');time.sleep(.15)
 else:packet(action or mode)
 time.sleep(1)
def text_component(value):
 if isinstance(value,str):return value
 if isinstance(value,list):return ''.join(text_component(v) for v in value)
 if isinstance(value,dict):return str(value.get('text',''))+text_component(value.get('extra',[]))
 return ''
def check_single(result,baseline,name,material,version,count,mode,dynamic=False,destination=None):
 destination=destination or {'click':'cursor','drop':'entities','pickup':'inventory'}[mode]
 require((destination=='inventory')==bool(result['inventory']),name+' expected inventory destination')
 require((destination=='entities')==bool(result['entities']),name+' expected entity destination')
 require((destination=='cursor')==('empty' not in result['cursor']),name+' expected cursor destination')
 stacks=collect_stacks(result);require(len(stacks)==1,name+' exactly one actual inventory/cursor/entity stack')
 if len(stacks)!=1:return None
 actual=stacks[0];compare_stack(actual,baseline,name,material,version,count)
 if dynamic:require(text_component(json.loads(actual['itemNameJson']))=='count='+str(count),name+' dynamic processor observes preserved count')
 emit('RESULT '+name+' '+json.dumps({'counts':result['counts'],'before':baseline['amount'],'after':actual['amount'],'material':actual['material'],'version':actual['nativeVersion']}))
 return actual
try:
 with socket.socket() as s:
  s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',a.port))
 # Copy binaries/runtime libraries only. Fresh world, player data, Allium DB and test keyring.
 if not a.exact1218:
  for name in ['libraries','versions']:shutil.copytree(a.template/name,lane/name)
 ce=lane/'plugins/CraftEngine';ce.mkdir(parents=True)
 cejar=a.ce_jar or (Path('/tmp/allium-s05-20261106/pins/ce/craft-engine-paper-plugin-26.9.1.jar') if a.exact1218 else a.artifacts/'craft-engine-sf-26.9.2.jar')
 with zipfile.ZipFile(cejar) as archive:
  descriptor_name='paper-plugin.yml' if 'paper-plugin.yml' in archive.namelist() else 'plugin.yml'
  descriptor=archive.read(descriptor_name).decode()
  native_version=re.search(r'(?m)^version:\s*[\'"]?([^\s\'"\n]+)',descriptor).group(1)
  if not re.search(r'(?m)^name:\s*[\'"]?CraftEngine[\'"]?\s*$',descriptor):raise ValueError('native artifact must identify as CraftEngine')
  (ce/'commands.yml').write_bytes(archive.read('commands.yml'));config=archive.read('config.yml').decode()
 artifact_info={'path':str(cejar.resolve()),'sha256':hashlib.sha256(cejar.read_bytes()).hexdigest(),'descriptor':descriptor,'nativeVersion':native_version,'origin':'explicit local artifact' if a.ce_jar else 'stock artifact'}
 if a.provenance:
  artifact_info['provenance']=json.loads(a.provenance.read_text())
 start=config.index('  workflows:');end=config.index('  # Players apply',start)
 config=config[:start]+'  workflows: {}\n  packs: {}\n\n'+config[end:]
 for old,new in [('send-on-join: true','send-on-join: false'),('metrics: true','metrics: false'),('update-checker: true','update-checker: false'),('click-in-inventory: false','click-in-inventory: true'),('    drop: false','    drop: true'),('    pick-up: false','    pick-up: true')]:config=config.replace(old,new)
 (ce/'config.yml').write_text(config)
 (itemfile.parent).mkdir(parents=True)
 (itemfile.parent.parent/'pack.yml').write_text('namespace: trigger\nauthor: S05TestOnly\nversion: 1.0\n')
 definitions()
 shutil.copy2(a.allium_jar,lane/'plugins/Allium.jar')
 shutil.copy2(cejar,lane/'plugins'/cejar.name)
 env=dict(os.environ,LANE_TEMPLATE=str(a.template),ALLIUM_JAR=str(a.allium_jar.resolve()),OUTPUT=str(run/'fixture'))
 subprocess.run([str(HERE/'build.sh')],env=env,check=True)
 shutil.copy2(run/'fixture/CETriggerGate.jar',lane/'plugins/CETriggerGate.jar')
 (lane/'eula.txt').write_text('eula=true\n')
 (lane/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={a.port}
online-mode=false
enforce-secure-profile=false
enable-query=false
enable-rcon=false
management-server-enabled=false
spawn-protection=0
view-distance=2
simulation-distance=2
level-name=world
level-seed=56051106
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
pause-when-empty-seconds=-1
max-tick-time=120000
network-compression-threshold=256
''')
 if a.exact1218:
  paperjar=Path('/tmp/allium-s05-20261106/lanes/artifacts/paper-1.21.8-60.jar')
  shutil.copy2(paperjar,lane/'paper.jar')
  argv=['/usr/lib/jvm/java-21-openjdk/bin/java','-Xms512M','-Xmx1536M','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','--nogui']
 else:
  # A stopped lane can contain plugin-downloaded older dependencies. Select the newest
  # per artifact so Gson2.10 / SLF4J1.7 cannot shadow Paper's runtime versions.
  libs={}
  def version(path):return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+',path.parent.name))
  for jar in (lane/'libraries').rglob('*.jar'):
   artifact=jar.parent.parent
   if artifact not in libs or version(jar)>version(libs[artifact]):libs[artifact]=jar
  cp=':'.join(str(x.relative_to(lane)) for x in [lane/'versions/26.3/paper-26.3.jar',*libs.values()])
  argv=['/usr/lib/jvm/java-25-openjdk/bin/java','-Xms512M','-Xmx1536M','-Dterminal.jline=false','-Dterminal.ansi=false','-cp',cp,'org.bukkit.craftbukkit.Main','--nogui']
 (run/'launch.json').write_text(json.dumps(argv,indent=2))
 server=subprocess.Popen(argv,cwd=lane,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 server_thread=threading.Thread(target=pump,args=(server,run/'server.log',logs),daemon=True);server_thread.start()
 wait(lambda:any('Done (' in line for line in logs),'server Done',180)
 cmd('op TriggerBot');time.sleep(.3)
 client=subprocess.Popen(['node',str(HERE/('protocol-trigger-1218.cjs' if a.exact1218 else 'raw-trigger-26.cjs')),str(lane),str(a.port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
 client_thread=threading.Thread(target=pump,args=(client,run/'client.log',client_logs),daemon=True);client_thread.start()
 wait(lambda:any('CE-WIRE READY' in x for x in client_logs),'wire ready')
 time.sleep(1)
 for name,mode,count,kind in cases:
  emit('CASE '+name)
  cmd(f'cetrigger seed {name} {mode} {count}')
  baseline=read_result(name+'-baseline.json')
  require(baseline['amount']==count and baseline['charges']=='2' and baseline['behavior']=='allium:trigger_gate',name+' signed/spent baseline')
  if kind!='control':versions[name]=3 if kind=='dynamic' else 2
  materials[name]='emerald';definitions();reload_ce()
  before=request_result('before',name+'-before.json')
  require(before['unchangedSinceSeed'] and sum(before['events'].values())==0,name+' reload alone unchanged; no trigger yet')
  trigger(mode,'rightclick' if kind=='split' else 'drop-one' if kind=='single' else None)
  result=observe_case(name,mode,'first' if kind in ['dynamic','split','single'] else '')
  if kind=='split':
   require(len(result['inventory'])==1 and result['inventory'][0]['slot']==0,name+' split stays in expected slot')
   require([s['amount'] for s in collect_stacks(result)]==[3,4],name+' split conserves 3+4=7')
   for stack in collect_stacks(result):compare_stack(stack,baseline,name+' split','EMERALD',2)
   trigger(mode);merged=observe_case(name,mode,'merge')
   require(merged['cursor'].get('empty') is True and len(merged['inventory'])==1,name+' merge empties cursor into inventory')
   check_single(merged,baseline,name+' merge','EMERALD',2,7,mode,destination='inventory')
  elif kind=='single':
   require(len(result['inventory'])==1 and len(result['entities'])==1,name+' partial drop has held and dropped stack')
   compare_stack(result['inventory'][0]['stack'],baseline,name+' held','IRON_INGOT',1,6)
   compare_stack(result['entities'][0]['stack'],baseline,name+' single','EMERALD',2,1)
   trigger(mode);whole=observe_case(name,mode,'remaining')
   require(not whole['inventory'] and sum(s['amount'] for s in collect_stacks(whole))==7,name+' final entities conserve total7')
   compare_stack(whole['events']['drop-monitor']['stack'],baseline,name+' remaining event','EMERALD',2,6)
   for stack in collect_stacks(whole):compare_stack(stack,baseline,name+' final entity','EMERALD',2)
  else:
   actual=check_single(result,baseline,name,'IRON_INGOT' if kind=='control' else 'EMERALD',versions[name],count,mode,kind=='dynamic')
   if kind=='dynamic':
    for phase,target in [('advance',4),('noop',4)]:
     rearm=request_result('rearm',name+'-rearm.json');require(rearm['unchanged'] and rearm['after']==actual,name+' '+phase+' relocates same item without changing data/count')
     (run/(name+'-'+phase+'-rearm.json')).write_text(json.dumps(rearm,indent=2))
     if target!=versions[name]:versions[name]=target;definitions();reload_ce()
     before=request_result('before',name+'-before.json');require(before['snapshot']==actual and sum(before['events'].values())==0,name+' '+phase+' unchanged before trigger')
     trigger(mode);following=observe_case(name,mode,phase)
     updated=check_single(following,baseline,name+' '+phase,'EMERALD',target,count,mode,True)
     if phase=='noop':require(updated==actual,name+' current-version trigger is a no-op')
     actual=updated
except BaseException as e:
 failures.append('HARNESS '+repr(e));emit('ERROR '+repr(e))
finally:
 # Client cleanup failure must never skip server shutdown.
 try:stop_process(client,lambda:packet('stop'),timeout=8)
 except Exception as e:failures.append('client cleanup '+repr(e))
 try:stop_process(server,lambda:cmd('stop'))
 except Exception as e:failures.append('server cleanup '+repr(e))
 if server:server_thread.join(timeout=5)
 if client:client_thread.join(timeout=5)
 metadata={'apiPin':'26.9.1','nativeCE':artifact_info['nativeVersion'] if artifact_info else None,'artifact':artifact_info,'paper':'1.21.8-60' if a.exact1218 else '26.3-141-54024d3','port':a.port,'caseCount':len(cases),'cases':[{'name':name,'mode':mode,'count':count,'kind':kind} for name,mode,count,kind in cases],'observations':observations,'failures':failures,'serverExit':server.returncode if server else None,'clientExit':client.returncode if client else None,'hashes':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane/'plugins').glob('*.jar')}}
 (run/'summary.json').write_text(json.dumps(metadata,indent=2))
 emit('SUMMARY '+json.dumps({key:metadata[key] for key in ['apiPin','nativeCE','paper','caseCount','failures','serverExit','clientExit','hashes']}))
 (run/'driver.log').write_text('\n'.join(driver_lines)+'\n')
sys.exit(1 if failures else 0)
