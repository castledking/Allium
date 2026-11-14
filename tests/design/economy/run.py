#!/usr/bin/env python3
"""Run the THROWAWAY S14 JDBC probe on scratch SQLite and the owned validation MySQL only."""
from pathlib import Path
import argparse, datetime, hashlib, json, math, os, shlex, shutil, subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--root',type=Path,default=Path('/mnt/storage/.allium-validation/20261113/s14-revision'))
args=p.parse_args();root=args.root.resolve()
if root != Path('/mnt/storage/.allium-validation/20261113/s14-revision'):p.error('owned scratch directory required')
here=Path(__file__).resolve().parent
run=root/('run-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
run.mkdir(parents=True);(run/'classes').mkdir();(run/'results').mkdir()
env=dict(os.environ);env.pop('JAVA_TOOL_OPTIONS',None)
for line in Path('/mnt/storage/.allium-validation/infra/mysql.env').read_text().splitlines():
    if line.startswith('MYSQL_ROOT_PASSWORD='):env['BENCH_MYSQL_PASSWORD']=shlex.split(line.split('=',1)[1])[0]
cp=os.pathsep.join(str(x) for x in sorted((root/'deps').glob('*.jar')))
if not cp:raise SystemExit('copy the pinned validation JDBC drivers to root/deps first')
sources=[here/'MoneyBoundary.java',here/'SingleCommitProbe.java']
for source in sources:shutil.copy2(source,run/source.name)
meta=dict(source_sha256={s.name:hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},
          dependencies={s.name:hashlib.sha256(s.read_bytes()).hexdigest() for s in (root/'deps').glob('*.jar')},
          java='/usr/lib/jvm/java-17-openjdk/bin/java',mysql='owned allium-gate-mysql 127.0.0.1:25733',credentials='private environment only')
was_running=subprocess.check_output(['docker','inspect','-f','{{.State.Running}}','allium-gate-mysql'],text=True).strip()=='true'
try:
    if not was_running:subprocess.run(['docker','start','allium-gate-mysql'],check=True,stdout=subprocess.DEVNULL)
    # mysqladmin ping reports server reachability even when authentication is required; no
    # credential is passed here. JDBC authenticates using its private child environment.
    import time
    for i in range(120):
        health=subprocess.run(['docker','exec','allium-gate-mysql','mysqladmin','ping','--silent'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if health.returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('owned MySQL did not become healthy')
    with (run/'compile.log').open('w') as log:
        subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac','--release','17','-cp',cp,'-d',str(run/'classes'),*[str(run/s.name) for s in sources]],check=True,env=env,stdout=log,stderr=subprocess.STDOUT)
    command=[meta['java'],'-Xmx128m','-cp',str(run/'classes')+os.pathsep+cp]
    subprocess.run(command+['MoneyBoundary'],check=True,env=env)
    with (run/'run.log').open('w') as log:
        subprocess.run(command+['SingleCommitProbe',str(run/'results')],check=True,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
    data=json.loads((run/'results/results.json').read_text());summary={}
    for lane in ['sqlite-full','sqlite-normal','mysql-durable']:
        summary[lane]={}
        for phase in ['single','burst3']:
            values=sorted(s['elapsed_ns']/1e6 for s in data['samples'] if s['lane']==lane and s['phase']==phase)
            summary[lane][phase]=dict(n=len(values),**{k:round(values[min(len(values)-1,math.ceil(len(values)*v)-1)],4) for k,v in [('p50',.5),('p95',.95),('p99',.99),('max',1)]})
    meta.update(passed=data['passed'],summary_ms=summary,faults=data['faults'],checks=data['checks'],results_sha256=hashlib.sha256((run/'results/results.json').read_bytes()).hexdigest())
    print(json.dumps(dict(run=str(run),**meta),indent=2))
finally:
    if not was_running:subprocess.run(['docker','stop','allium-gate-mysql'],check=True,stdout=subprocess.DEVNULL)
    meta['restored_mysql_running']=was_running
    (run/'manifest.json').write_text(json.dumps(meta,indent=2)+'\n')
