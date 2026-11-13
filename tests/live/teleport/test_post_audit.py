#!/usr/bin/env python3
"""Negative controls mutate disposable evidence copies; original live evidence stays untouched."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True
from post_audit import run_audit, PRIVACY, DENIAL

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--spigot',required=True,type=Path);p.add_argument('--paper',required=True,type=Path);p.add_argument('--supplement',required=True,type=Path);p.add_argument('--output-root',required=True,type=Path)
a=p.parse_args();a.output_root.mkdir(parents=True,exist_ok=True)
spigot=json.loads(a.spigot.read_text());paper=json.loads(a.paper.read_text())
originals={str(v.resolve()):hashlib.sha256(v.read_bytes()).hexdigest() for v in [a.spigot,a.paper,a.supplement]}
controls=[]
def execute(name,overrides):
    out=a.output_root/(name+'-audit.json')
    result=run_audit(a.spigot,a.paper,a.supplement,out,overrides)
    controls.append(dict(name=name,passed=not result['passed'],auditPassed=result['passed'],error=result.get('error'),output=str(out),sha256=hashlib.sha256(out.read_bytes()).hexdigest()))
def save(name,original,content):
    dest=a.output_root/name;dest.write_text(content)
    return str(original.resolve()),str(dest.resolve())

# Missing a fresh requester refusal must fail its own causal hazard window.
client=Path(spigot['run'])/'TeleA-client.log';lines=client.read_text().splitlines()
idx=next(i for i,s in enumerate(lines) if s.startswith('BASEG-CHAT ') and json.loads(s.split(' ',1)[1])['plain']=='[Allium] No safe destination was found. Nothing was teleported.')
mutated=lines[:idx]+lines[idx+1:]
execute('removed-requester-denial',dict([save('removed-denial-client.log',client,'\n'.join(mutated)+'\n')]))

# Coherently mutate both recorded array and wire input. Equality of flattened text must not
# conceal a one-sided hover event, and input/recording consistency alone must not pass it.
changed=copy.deepcopy(spigot);record=next(c for c in changed['checks'] if c['label']==PRIVACY)['detail']['vanished'][0]
component=json.loads(record['rawComponent'])
component['hoverEvent']={'action':'show_text','contents':{'text':'private-presence-leak'}}
record['rawComponent']=json.dumps(component,separators=(',',':'));record['interactive']=True
wire_commands=[i for i,s in enumerate(lines) if s.startswith('BASEG-WIRE SENT /')]
unknown=next(i for i,s in enumerate(lines) if s=='BASEG-WIRE SENT /tpa NoSuchTeleGate')
previous=max(i for i in wire_commands if i<unknown)
reply=next(i for i in range(previous+1,unknown) if lines[i].startswith('BASEG-CHAT '))
mutated=list(lines);mutated[reply]='BASEG-CHAT '+json.dumps(record,separators=(',',':'))
execute('injected-hover-metadata',dict([save('hover-manifest.json',a.spigot,json.dumps(changed)),save('hover-client.log',client,'\n'.join(mutated)+'\n')]))

# Preserve the full denied-command wire/recording agreement while corrupting its translation.
changed=copy.deepcopy(paper);record=next(c for c in changed['checks'] if c['label']==DENIAL)['detail'][0]
record['rawComponent']['extra'][0]['translate']='command.context.parse_error'
client=Path(paper['run'])/'TeleStaff-client.log';lines=client.read_text().splitlines()
command=next(i for i,s in enumerate(lines) if s=='BASEG-WIRE SENT /tp TeleA TeleB')
reply=next(i for i in range(command+1,len(lines)) if lines[i].startswith('BASEG-CHAT '))
mutated=list(lines);mutated[reply]='BASEG-CHAT '+json.dumps(record,separators=(',',':'))
execute('altered-paper-denial-translation',dict([save('translation-manifest.json',a.paper,json.dumps(changed)),save('translation-client.log',client,'\n'.join(mutated)+'\n')]))

unchanged=all(hashlib.sha256(Path(path).read_bytes()).hexdigest()==h for path,h in originals.items())
result=dict(passed=all(c['passed'] for c in controls) and unchanged,controls=controls,originalManifestsUnchanged=unchanged,originalManifestHashes=originals,
            auditScriptSha256=hashlib.sha256(Path(__file__).with_name('post_audit.py').read_bytes()).hexdigest(),negativeControlScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
(a.output_root/'negative-controls.json').write_text(json.dumps(result,indent=2)+'\n')
for c in controls:print(('PASS ' if c['passed'] else 'FAIL ')+c['name']+': '+str(c.get('error')))
sys.exit(0 if result['passed'] else 1)
