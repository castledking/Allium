#!/usr/bin/env python3
"""Render retained native JSON results + wire/driver logs as reviewable evidence."""
import json, sys
from pathlib import Path
root=Path('/tmp/allium-s05-20261106/ce-triggers')
run=(root/sys.argv[1]).resolve()
if run.parent!=root:raise SystemExit('require an owned run name')
summary=json.loads((run/'summary.json').read_text())
print('S05 CE native trigger diagnostic / 2026-11-06')
print('RESULT: FAIL quantity invariant; metadata retention passes. No provider/adapter fix applied.')
print('Actual packets and native server events. No manually fired Bukkit events.')
print('Client emits actual left-click/full-stack-drop/movement; vanilla runs event continuation.')
print('Seeder spends charges9->2 before baseline only; no post-baseline refresh/re-sign/native-update calls.')
print('Fresh world/player data/Allium DB/keyring. Only runtime jars/libraries and CE template config copied.')
print('Hosting/workflows/packs disabled before boot; send-on-join false. item update triggers click/drop/pick-up true; attack false.')
print('API pin26.9.1. Native26.9.2 on26.3 is adjacent-patch evidence; native26.9.1 on1.21.8 is exact-pin evidence.')
print('RUN '+str(run))
print('SUMMARY '+json.dumps(summary,sort_keys=True))
server=(run/'server.log').read_text()
print('SERVER_RUNTIME_AND_STOP')
for line in server.splitlines():
 if any(x in line for x in ['Loading Paper ','CE-TRIGGER runtime=','CraftEngine provider (','Stopping server','All dimensions are saved','All RegionFile I/O tasks to complete']):print(line)
errors=[x for x in server.splitlines() if any(k in x.lower() for k in ['disk quota exceeded','no space left on device','ce-trigger error','exception'])]
print('SERVER_LOG_ERRORS '+json.dumps(errors))
print('ASSERTIONS_FROM_DRIVER')
print((root/(run.name+'.log')).read_text().strip())
print('WIRE_PACKETS_FROM_ACTUAL_CLIENT')
print((run/'client.log').read_text().strip())
print('SERVER_OWNED_SNAPSHOTS_JSONL')
for mode in ['click','drop','pickup']:
 for count,kind in [(7,'control'),(1,'reset'),(7,'reset'),(7,'repeat')]:
  name=f'{mode}-{count}-{kind}'
  result=json.loads((run/(name+'.json')).read_text())
  before=json.loads((run/'lane/plugins/CETriggerGate'/(name+'-before.json')).read_text())
  print('CASE '+name)
  print('BASELINE '+json.dumps(result['baseline'],sort_keys=True))
  print('POST_RELOAD_BEFORE_TRIGGER '+json.dumps(before,sort_keys=True))
  print('EVENTS '+json.dumps(result['events'],sort_keys=True))
  print('REREAD_FINAL '+json.dumps({k:result[k] for k in ['inventory','cursor','entities','counts']},sort_keys=True))
