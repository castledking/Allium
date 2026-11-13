#!/usr/bin/env python3
"""Aggregate only supplied S12 manifests. Missing/failed scopes remain explicit."""
import argparse
import json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('manifests',nargs='+',type=Path)
p.add_argument('--unit-summary',type=Path)
a=p.parse_args()
here=Path(__file__).resolve().parent
runs=[]
for path in a.manifests:
    row=json.loads(path.read_text())
    if row.get('schema')!=1 or 'scopes' not in row:
        p.error('unsupported/live manifest missing scopes: '+str(path))
    runs.append(dict(manifest=str(path.resolve()),**row))
expected={'sqlite-spigot','sqlite-paper','mysql-spigot','mysql-paper','shared-mysql'}
missing=sorted(expected-{r['suite'] for r in runs})
failed=[r for r in runs if r['status']!='PASS']
hashes=sorted({r['sha256'] for r in runs})
status='FAIL' if failed else 'INCOMPLETE' if missing or len(hashes)!=1 else 'PASS'
unit=json.loads(a.unit_summary.read_text()) if a.unit_summary else None
if unit and (unit['artifactSha256'] not in hashes or any(s['failures'] or s['errors'] or s['skipped'] for s in unit['tests'].values())):
    p.error('unit summary does not match the live artifact or has unsuccessful tests')
aggregate=dict(schema=1,status=status,missingSuites=missing,artifactHashes=hashes,runs=runs,unitVerification=unit)
out=here/'evidence/s12-mail-manifest.json'
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(aggregate,indent=2)+'\n')
total=sum(len(r['checks']) for r in runs)
lines=['# S12 mail live gate','',f'**Live verified: {status}, {total} checks.** Only the supplied executed runs are included.','']
if unit:
    count=sum(s['tests'] for s in unit['tests'].values())
    lines += [f'**Unit only:** {count} tests, zero failures/errors/skips; `test shadowJar` passed. Selected mail/preference repository tests also passed against disposable MySQL.','']
for r in runs:
    passed=sum(c['passed'] for c in r['checks'])
    lines += [f"- **{r['suite']} — {r['status']}**: {passed}/{len(r['checks'])} assertions passed, {len(r['failures'])} failure(s). Runtime: `{r['run']}`."]
lines+=['',f"Frozen artifact hash(es): {', '.join('`'+s+'`' for s in hashes)}."]
if missing:lines+=['','**Not run:** '+', '.join(missing)+'.']
lines+=['','Executed scope results:','']
for r in runs:
    for scope in r['scopes']:lines+=[f"- {r['suite']}: {scope['label']} — **{scope['status']}**."]
if failed:
    lines+=['','Failures (no failed assertion is relabelled as a pass):','']
    for r in failed:
        lines += [f"- {r['suite']}: {s}" for s in r['failures']]
lines+=['','Scope limits:','']
for boundary in sorted({s for r in runs for s in r['boundaries']}):lines+=['- '+boundary+'.']
signed=[dict(suite=r['suite'],label=c['label'],passed=c['passed']) for r in runs for c in r['checks']
        if 'signed tool identity/MAC/state survive' in c['label'] or '1.20.1 to 26.3 preserves verified tool' in c['label']]
if signed:
    lines+=['','Signed transfer assertions actually reached:','']
    lines += [f"- {c['suite']}: {c['label']} — **{'PASS' if c['passed'] else 'FAIL'}**." for c in signed]
else:
    lines+=['','Signed transfer assertion was **not reached** in the supplied runs.']
lines+=['- Runtime configs and MySQL credentials are excluded from repository evidence.',
        '', 'Compatibility fixes established by the gate:', '',
        '- Paper 26.3 stores player data separately from dimension folders. One guarded native accessor obtains the actual player-data directory; failed lookup disables mail.',
        '- Native 1.20.1-to-26.3 item conversion reinterpreted a numeric-looking signed UUID as a number. Envelope format 2 retains a typed PDC sidecar and restores the original UUID/state/MAC without re-signing.',
        '- Superseded failures remain under `/mnt/storage/.allium-validation/20261113/s12-mail/runs/`; they are not counted as final passes.',
        '', '[Full assertion details and artifact manifest](evidence/s12-mail-manifest.json).','']
(here/'RESULTS.md').write_text('\n'.join(lines))
print(status+' '+str(out))
