#!/usr/bin/env python3
"""Disposable native CE trigger diagnostic. Nonzero on a contract failure; retains evidence.
Never reads production data. Only /tmp/allium-s05-20261106/ce-triggers and loopback25610-12.
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
p.add_argument('--exact1218',action='store_true',help='exact official CE26.9.1 / Paper1.21.8-60 / Java21')
a=p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_-]+',a.run_name):p.error('invalid run-name')
run=ROOT/a.run_name
if run.exists():p.error('run directory already exists; choose a fresh --run-name')
run.mkdir(parents=True)
lane=run/'lane';lane.mkdir()
server=client=None
logs=[];client_logs=[];cases=[];failures=[]
# Controls leave version1 unchanged, despite a material edit; CE must not reset them.
for mode in ['click','drop','pickup']:
 for count,kind in [(7,'control'),(1,'reset'),(7,'reset'),(7,'repeat')]:
  cases.append((f'{mode}-{count}-{kind}',mode,count,kind))
versions={name:1 for name,_,_,_ in cases}
materials={name:'iron_ingot' for name,_,_,_ in cases}
itemfile=lane/'plugins/CraftEngine/resources/ce_trigger/configuration/items.yml'
def definitions():
 text='items:\n'
 for name,_,_,_ in cases:
  text+=f'''  trigger:{name}:
    material: {materials[name]}
    data:
      item_name: CE trigger {name}
    updater:
      '{versions[name]}':
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
def reload_ce():
 start=len(logs);cmd('ce reload')
 wait(lambda:any('Reload successfully' in x or 'Reloaded successfully' in x or 'Successfully reloaded' in x or 'Configs reloaded' in x for x in logs[start:]),'CE reload',45)
 time.sleep(.25)
def require(value,label):
 if not value:failures.append(label)
 print(('PASS ' if value else 'FAIL ')+label,flush=True)
try:
 with socket.socket() as s:
  s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',a.port))
 # Copy binaries/runtime libraries only. Fresh world, player data, Allium DB and test keyring.
 if not a.exact1218:
  for name in ['libraries','versions']:shutil.copytree(a.template/name,lane/name)
 ce=lane/'plugins/CraftEngine';ce.mkdir(parents=True)
 if a.exact1218:
  cejar=Path('/tmp/allium-s05-20261106/pins/ce/craft-engine-paper-plugin-26.9.1.jar')
  with zipfile.ZipFile(cejar) as archive:
   (ce/'commands.yml').write_bytes(archive.read('commands.yml'));config=archive.read('config.yml').decode()
 else:
  cejar=a.artifacts/'craft-engine-sf-26.9.2.jar'
  shutil.copy2(a.template/'plugins/CraftEngine/commands.yml',ce/'commands.yml')
  config=(a.template/'plugins/CraftEngine/config.yml').read_text()
 start=config.index('  workflows:');end=config.index('  # Players apply',start)
 config=config[:start]+'  workflows: {}\n  packs: {}\n\n'+config[end:]
 for old,new in [('send-on-join: true','send-on-join: false'),('metrics: true','metrics: false'),('update-checker: true','update-checker: false'),('click-in-inventory: false','click-in-inventory: true'),('    drop: false','    drop: true'),('    pick-up: false','    pick-up: true')]:config=config.replace(old,new)
 (ce/'config.yml').write_text(config)
 (itemfile.parent).mkdir(parents=True)
 (itemfile.parent.parent/'pack.yml').write_text('namespace: trigger\nauthor: S05TestOnly\nversion: 1.0\n')
 definitions()
 shutil.copy2(a.template/'plugins/Allium.jar',lane/'plugins/Allium.jar')
 shutil.copy2(cejar,lane/'plugins'/cejar.name)
 env=dict(os.environ,LANE_TEMPLATE=str(a.template),ALLIUM_JAR=str(a.template/'plugins/Allium.jar'),OUTPUT=str(run/'fixture'))
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
  print('CASE '+name,flush=True)
  cmd(f'cetrigger seed {name} {mode} {count}')
  baseline=read_result(name+'-baseline.json')
  require(baseline['amount']==count and baseline['charges']=='2' and baseline['behavior']=='allium:trigger_gate',name+' signed/spent baseline')
  if kind!='control':versions[name]=2
  materials[name]='emerald';definitions();reload_ce()
  cmd('cetrigger before');before=read_result(name+'-before.json')
  require(before['unchangedSinceSeed'] and sum(before['events'].values())==0,name+' reload alone unchanged; no trigger yet')
  if mode=='click':packet('click')
  elif mode=='drop':packet('drop')
  else:
   for x in [1,1.5,2,2.5,3,3.5]:packet(f'move {x} 100 0.5');time.sleep(.15)
  time.sleep(1)
  cmd('cetrigger observe');result=read_result(name+'-result.json')
  (run/(name+'.json')).write_text(json.dumps(result,indent=2))
  require(result['counts'][mode]>=1,name+' genuine native '+mode+' event observed')
  ev=result['events'].get(mode+'-monitor',{})
  require(ev.get('nativeServerCallStack') is True and ev.get('cancelled') is False,name+' uncancelled native call stack')
  stacks=[row['stack'] for row in result['inventory']]+[row['stack'] for row in result['entities']]
  if 'empty' not in result['cursor']:stacks.append(result['cursor'])
  require(len(stacks)==1,name+' exactly one actual inventory/cursor/entity stack')
  if len(stacks)!=1:continue
  actual=stacks[0]
  for key in ['signature','uuid','pdc','charges','behavior','identity','rawSentinel']:
   require(actual.get(key)==baseline.get(key),name+' retained '+key)
  require(actual['material']==('IRON_INGOT' if kind=='control' else 'EMERALD'),name+' actual material')
  require(actual['nativeVersion']==('1' if kind=='control' else '2'),name+' actual CE version')
  require(actual['amount']==count,name+f' actual quantity expected={count} observed={actual["amount"]}')
  print('RESULT '+name+' '+json.dumps({'counts':result['counts'],'before':baseline['amount'],'after':actual['amount'],'material':actual['material'],'version':actual['nativeVersion']}),flush=True)
except BaseException as e:
 failures.append('HARNESS '+repr(e));print('ERROR '+repr(e),flush=True)
finally:
 # Client cleanup failure must never skip server shutdown.
 try:stop_process(client,lambda:packet('stop'),timeout=8)
 except Exception as e:failures.append('client cleanup '+repr(e))
 try:stop_process(server,lambda:cmd('stop'))
 except Exception as e:failures.append('server cleanup '+repr(e))
 if server:server_thread.join(timeout=5)
 if client:client_thread.join(timeout=5)
 metadata={'apiPin':'26.9.1','nativeCE':'26.9.1' if a.exact1218 else '26.9.2','paper':'1.21.8-60' if a.exact1218 else '26.3-141-54024d3','port':a.port,'failures':failures,'serverExit':server.returncode if server else None,'clientExit':client.returncode if client else None,'hashes':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in (lane/'plugins').glob('*.jar')}}
 (run/'summary.json').write_text(json.dumps(metadata,indent=2))
 print('SUMMARY '+json.dumps(metadata),flush=True)
sys.exit(1 if failures else 0)
