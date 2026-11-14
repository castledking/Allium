#!/usr/bin/env python3
"""Publish one proportional S15 results file and aggregate manifest from two original lanes."""
import argparse
import hashlib
import json
from pathlib import Path

P=argparse.ArgumentParser(description=__doc__)
P.add_argument('manifests',nargs=2,type=Path)
P.add_argument('--failed-attempt',action='append',default=[],type=Path)
A=P.parse_args();HERE=Path(__file__).resolve().parent

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
runs=[dict(path=str(p.resolve()),sha256=sha(p),original=json.loads(p.read_text())) for p in A.manifests]
roles={r['original']['suite']:r for r in runs}
assert len(roles)==2 and set(roles)=={'spigot','paper'},'one original manifest per supplied lane required'
assert len({r['original']['mode'] for r in runs})==1,'cannot mix prepare and live manifests'
mode=runs[0]['original']['mode'];live=mode=='live'
assert roles['spigot']['original']['plannedCases']==roles['paper']['original']['plannedCases'],'lane case plans differ'
if live:
    assert len({r['original']['jarSha256'] for r in runs})==1,'frozen jar differs between lanes'
    assert roles['spigot']['original']['sourceHashes']==roles['paper']['original']['sourceHashes'],'source/helper/client freeze differs between lanes'
    for r in runs:
        m=r['original'];path=Path(m['run'])
        assert sha(path/'Allium-under-test.jar')==m['jarSha256'],'frozen tested jar changed'
        assert sha(path/'helper/TravelGate.jar')==m['helperSha256'],'executed helper changed'
        assert m['contract']['pinned'],'unresolved contract'
        for original,expected in m['sourceHashes'].items():
            copy=path/'harness'/Path(original).relative_to(HERE.parents[2])
            assert sha(copy)==expected,'executed snapshot changed: '+str(copy)
        assert m['cases'] and len(m['cases'])==len(m['plannedCases']) and not m['unrunCases'],'unrun live case'
        assert {c['name'] for c in m['cases']}==set(m['plannedCases']),'unexpected live case set'
passed=live and all(r['original']['passed'] and not r['original']['failures'] and all(c['status']=='PASS' for c in r['original']['cases'])
                      and bool(r['original']['processes']) and all(p['exit']==0 and not p['forced'] and not p.get('error') for p in r['original']['processes']) for r in runs)
failed_attempts=[]
for path in A.failed_attempt:
    original=json.loads(path.read_text())
    assert original['mode']=='live' and original['passed'] is False,'failed attempt must remain original FAIL'
    failed_attempts.append(dict(path=str(path.resolve()),sha256=sha(path),originalPassed=original['passed'],
        failures=original['failures'],cases=original['cases'],processes=original['processes'],jarSha256=original['jarSha256']))
status='PASS' if passed else ('NOT RUN — prepared only' if not live else 'FAIL')
aggregate=dict(schema=1,mode=mode,status=status,passed=passed,runs=runs,failedAttempts=failed_attempts,
    limitations=['SQLite; Spigot 1.20.1 / Java 17 and Paper 26.3-141 / Java 25 only.',
                 'No MySQL, Folia, provider plugins, pet teleport, offline restore, or cross-server claims.',
                 'Preparing/compiling clients and fixtures does not execute any acceptance case.'] if not live else
                ['SQLite; two supplied server versions only; no MySQL, Folia, provider plugins, pet teleport, offline restore, or cross-server claims.'])
(HERE/'evidence').mkdir(exist_ok=True)
(HERE/'evidence/s15-travel-manifest.json').write_text(json.dumps(aggregate,indent=2)+'\n')
lines=['# S15 homes and warps live results','',f'**{status}**.','']
for suite in ['spigot','paper']:
    r=roles[suite];m=r['original'];good=sum(c['status']=='PASS' for c in m['cases'])
    lines.append(f"- {suite}: {good}/{len(m['plannedCases'])} cases executed and passed; {len(m['unrunCases'])} unrun. Original manifest: `{r['path']}` (SHA-256 `{r['sha256']}`).")
if not live:lines+=['','No server or bot process has run. Live execution requires the parent’s explicit frozen S15 jar SHA-256 and API/message contract pin (now confirmed in `contract.json`).']
else:
    lines+=['','Frozen jar SHA-256: `'+runs[0]['original']['jarSha256']+'`.','',
            'Commands and completions travel through real protocol clients. Successful travel requires a fresh non-fixture PLUGIN event matching the current world UUID and XYZ. Refusals require bounded valid wire responses, stationary coordinates, and no physical teleport. Reloads retain the same ready service; restart checks include stopped SQLite rows. Final scans include scheduler, callback, client and shutdown failures.','',
            'Cases:','']
    for name in runs[0]['original']['plannedCases']:lines.append('- '+name)
    for r in runs:
        if r['original']['failures']:lines+=['',r['original']['suite']+' failures:']+['- '+f for f in r['original']['failures']]
if failed_attempts:
    lines+=['','Retained failed attempts:','']
    for f in failed_attempts:
        lines.append('- `'+f['path']+'` — original FAIL preserved (SHA-256 `'+f['sha256']+'`).')
        lines.append('  The first Spigot harness incorrectly required Bukkit isPermissionSet=false for default-true permissions. Raw effective permissions were true, all six frozen plugin.yml defaults were true, commands succeeded, and all processes exited zero. The corrected full runs require registered PermissionDefault.TRUE, effective permission=true, non-operator status and no explicit fixture attachment for that node. No original assertion or case was relabelled.')
lines+=['','Limits: '+ ' '.join(aggregate['limitations']),'','[Original lane manifests and input hashes](evidence/s15-travel-manifest.json).','']
(HERE/'RESULTS.md').write_text('\n'.join(lines))
print(status)
if live and not passed:raise SystemExit(1)
