#!/usr/bin/env python3
"""Read-only retained-evidence audit. Never edits or relabels original live manifests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import yaml

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
DENIAL='force unauthorized command reports explicit permission denial or exact hidden-command key'
DEATH='death-back-config-switch: timeout: actual player death true'
DERIVED='all fifteen named cases executed and passed'
PRIVACY='vanished and unknown target have same complete bounded generic response arrays'
STAFF='staff-distinct-permission-toggle-bypass-audit-console'
EXPECTED_CASES=['request-accept-tpahere-back','deny-expiry-cancel','real-movement-cancels-warmup','real-damage-cancels-warmup',
                'toggle-off-cancels-pending','offline-mid-warmup','unsafe-lava-void-headspace-fall','tab-vanish',
                'unloaded-preferences-generic-refusal',STAFF,'crossworld-back-spawn','death-back-config-switch','history-write-fault-retry',
                'restart-persistence-back-spawn-toggle-cooldown','final-callback-client-service-health']

class Audit:
    def __init__(self,overrides=None):self.checks=[];self.inputs={};self.overrides=overrides or {}
    def require(self,ok,label,detail=None):
        self.checks.append(dict(label=label,passed=bool(ok),detail=detail))
        if not ok:raise AssertionError(label)
    def read(self,path):
        path=Path(path).resolve();actual=Path(self.overrides.get(str(path),str(path))).resolve()
        data=actual.read_bytes()
        self.inputs[str(path)]=dict(path=str(actual),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data))
        return data
    def load(self,path):return json.loads(self.read(path))
    def lines(self,path):return self.read(path).decode().splitlines()

def check(m,label):
    found=[c for c in m['checks'] if c['label']==label]
    if len(found)!=1:raise AssertionError('unique original check required: '+label)
    return found[0]

def near(a,b):return a['worldUUID']==b['worldUUID'] and sum((a[k]-b[k])**2 for k in ['x','y','z'])<=.2**2

def chats(lines,start,end):
    return [dict(line=i+1,value=json.loads(s.split(' ',1)[1])) for i,s in enumerate(lines[start:end],start) if s.startswith('BASEG-CHAT ')]

def wire(lines):return [(i,s.removeprefix('BASEG-WIRE SENT /')) for i,s in enumerate(lines) if s.startswith('BASEG-WIRE SENT /')]

def raw(component):return json.loads(component) if isinstance(component,str) else component

def normalize(value,query):
    if isinstance(value,str):return value.replace(query,'<query>')
    if isinstance(value,list):return [normalize(v,query) for v in value]
    if isinstance(value,dict):return {k:normalize(v,query) for k,v in value.items()}
    return value

def production_hashes(m):
    return {p:h for p,h in m['sourceHashes'].items() if any('/'+r+'/' in p for r in ['src/main','allium-core/src/main','allium-api/src/main'])}

def base_integrity(a,m):
    run=Path(m['run'])
    a.require(m['mode']=='live' and all(p['exit']==0 and not p['forced'] and not p.get('error') for p in m['processes']),m['suite']+': original live inputs and clean process exits',m['processes'])
    for label in ['production/helper/client source hashes remain frozen','frozen Allium jar unchanged','cached real-client dependency tree unchanged',
                  'no event, task, fixture, or callback errors','no persisted callback or shutdown errors']:
        a.require(check(m,label)['passed'],m['suite']+': preserved original '+label)
    jar=a.read(run/'Allium-under-test.jar')
    a.require(hashlib.sha256(jar).hexdigest()==m['jarSha256'],m['suite']+': frozen JAR bytes still match original manifest')
    helper=a.read(run/'helper/TeleportGate.jar')
    a.require(hashlib.sha256(helper).hexdigest()==m['helperSha256'],m['suite']+': original helper binary unchanged')
    snapshots={}
    for original,h in m['sourceHashes'].items():
        copy=run/'harness'/Path(original).relative_to(REPO)
        snapshots[str(copy)]=hashlib.sha256(a.read(copy)).hexdigest()==h
    a.require(all(snapshots.values()),m['suite']+': every frozen source/helper/client snapshot matches original source hash',dict(files=len(snapshots)))

def privacy(a,m,client):
    original=check(m,PRIVACY)
    a.require(original['passed'],m['suite']+': original bounded privacy check passed')
    commands=wire(client)
    indexes=[k for k,(_,cmd) in enumerate(commands) if cmd=='tpa NoSuchTeleGate']
    a.require(len(indexes)==1,m['suite']+': unique unknown-query wire boundary')
    k=indexes[0];start,previous=commands[k-1];middle,_=commands[k];end=commands[k+1][0]
    a.require(previous=='tpa TeleB',m['suite']+': vanished query immediately precedes unknown query')
    first=chats(client,start+1,middle);second=chats(client,middle+1,end)
    a.require([v['value'] for v in first]==original['detail']['vanished'] and [v['value'] for v in second]==original['detail']['unknown'],
              m['suite']+': complete retained wire arrays equal captured bounded arrays',dict(vanishedLineRange=[start+1,middle],unknownLineRange=[middle+1,end],vanished=first,unknown=second))
    def canonical(rows,query):return [dict(component=normalize(raw(v['value']['rawComponent']),query),interactive=v['value']['interactive'],overlay=v['value']['overlay']) for v in rows]
    left=canonical(first,'TeleB');right=canonical(second,'NoSuchTeleGate')
    a.require(bool(left) and left==right,m['suite']+': recursive raw component/NBT metadata plus interactive/overlay privacy equality',dict(vanishedNormalized=left,unknownNormalized=right))
    a.require(check(m,'vanished request no notification, pending state, or movement')['passed'],m['suite']+': target notification and pending/movement privacy premises preserved')

def parsed_states(server,player):
    found=[];prefix='S13G-DONE state '+player+' => '
    for i,line in enumerate(server):
        if prefix in line:found.append((i,json.loads(line.split(prefix,1)[1])))
    return found

def hazards(a,m,server,client):
    # Correlate actual server command events to command ordinals on the real client, then use
    # fixture hazard windows. A global count of four refusals cannot satisfy this proof.
    second_ready=[i for i,s in enumerate(server) if 'S13G-GATE READY' in s][1:]
    first_boot_end=second_ready[0] if second_ready else len(server)
    states=parsed_states(server[:first_boot_end],'TeleA')
    events={}
    for index,state in states:
        for e in state['events']:
            if e['type']=='command':events.setdefault(e['sequence'],dict(command=e['command'][1:],line=index,time=e['time'],sequence=e['sequence']))
    ordered=[events[k] for k in sorted(events)]
    commands=wire(client)
    a.require(len(commands)>=len(ordered) and [v['command'] for v in ordered]==[v[1] for v in commands[:len(ordered)]],m['suite']+': server command event sequence aligns exactly to client command ordinals',dict(commands=len(ordered)))
    places=[]
    for i,line in enumerate(server[:first_boot_end]):
        match=re.search(r'S13G-DONE place TeleB world (-?\d+) (-?\d+) (\w+) => ',line)
        if match:places.append((i,match.group(3)))
    selected=[(j,idx,kind) for j,(idx,kind) in enumerate(places) if kind in ['lava','void','headspace','fall']]
    a.require([v[2] for v in selected]==['lava','void','headspace','fall'],m['suite']+': exact four ordered fixture terrain windows',selected)
    langpath=Path(m['run'])/m['suite']/'plugins/Allium/lang/en_US.yml'
    lang=yaml.safe_load(a.read(langpath))
    def plain(text):return re.sub(r'&(?:#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])','',text.replace('{prefix}',lang['prefix']))
    unsafe=plain(lang['teleport']['unsafe']);sent=plain(lang['teleport']['request-sent']).replace('{player}','TeleB')
    expiry=plain(lang['teleport']['cancelled'])
    windows=[]
    for place_index,start,kind in selected:
        end=places[place_index+1][0] if place_index+1<len(places) else first_boot_end
        matching=[(k,e) for k,e in enumerate(ordered) if start<e['line']<end and e['command']=='tpa TeleB']
        a.require(len(matching)==1,m['suite']+': '+kind+' fixture window has one fresh real requester command',matching)
        ordinal,event=matching[0];wire_start,cmd=commands[ordinal];wire_end=commands[ordinal+1][0]
        rows=chats(client,wire_start+1,wire_end);texts=[v['value']['plain'] for v in rows]
        a.require(texts in [[sent,unsafe],[sent,unsafe,expiry]] and all(not v['value']['overlay'] for v in rows),m['suite']+': '+kind+' fresh exact requester unsafe refusal in its own command window',dict(fixtureServerLineRange=[start+1,end],commandEvent=event,clientLineRange=[wire_start+1,wire_end],expected=[sent,unsafe],responses=rows))
        original=check(m,kind+' refuses or bounds safe adjustment using actual blocks/coordinates')
        actual=original['detail']['actual'];origin=original['detail']['origin']
        a.require(original['passed'] and near(actual,origin) and not any(e['type']=='teleport' and e['cause']=='PLUGIN' and not e['preparing'] and not e['cancelled'] for e in actual['events']),m['suite']+': '+kind+' original actual-coordinate and no-PLUGIN-event premise')
        windows.append(ordinal)
    a.require(windows==list(range(windows[0],windows[0]+4)),m['suite']+': four hazard requester windows are consecutive before visibility case',windows)
    boundary=next(i for i,s in enumerate(client) if s.startswith('MSGG-TABREQ ') and json.loads(s.split(' ',1)[1])['text']=='tpa Tele')
    a.require(commands[windows[-1]][0]<boundary,m['suite']+': all four fresh hazard refusals precede unique visibility boundary')

def paper_denial(a,m,server,client):
    original=check(m,DENIAL)
    a.require(not original['passed'],'paper: original exact failed denial assertion retained')
    commands=wire(client);k=next(i for i,v in enumerate(commands) if v[1]=='tp TeleA TeleB')
    start=commands[k][0];end=commands[k+1][0];rows=chats(client,start+1,end)
    expected={'color':'red','extra':[{'translate':'command.unknown.command'},{'':'\n'},
              {'click_event':{'command':'/tp TeleA TeleB','action':'suggest_command'},'color':'gray','extra':[
                  {'underlined':1,'color':'red','text':'tp TeleA TeleB'},
                  {'italic':1,'color':'red','translate':'command.context.here'}],'text':''}],'text':''}
    a.require(len(rows)==1 and [v['value'] for v in rows]==original['detail'],'paper: exact complete bounded denied command wire response equals original failed check detail',dict(lineRange=[start+1,end],responses=rows))
    value=rows[0]['value']
    a.require(raw(value['rawComponent'])==expected and value['plain']=='{command.unknown.command}\ntp TeleA TeleB{command.context.here}' and value['overlay'] is False and value['interactive'] is True,
              'paper: exact bundled hidden-command translation/newline/suggest-command context and query echo',dict(expected=expected,actual=value))
    permissions=[dict(line=i+1,response=json.loads(line.split(' => ',1)[1])) for i,line in enumerate(server) if 'S13G-DONE perm TeleStaff allium.tp false => ' in line]
    a.require(len(permissions)==1 and permissions[0]['response']=={'permission':False} and check(m,'effective TeleStaff allium.tp=False')['passed'],'paper: recorded effective force permission is false',permissions)
    stationary=check(m,'staff force requires distinct command permission')
    before=stationary['detail']['before'];after=stationary['detail']['after']
    a.require(stationary['passed'] and near(before,after) and not any(e['type']=='teleport' and e['cause']=='PLUGIN' and not e['preparing'] and not e['cancelled'] for e in after['events']),
              'paper: actual stationary coordinates and no nonfixture PLUGIN event on denied force',stationary)

def run_audit(spigot_path,paper_path,supplement_path,output,overrides=None):
    a=Audit(overrides);result=dict(schema=1,kind='retained-evidence-post-audit',passed=False,checks=a.checks,inputs=a.inputs,
                                  script=str(Path(__file__).resolve()),scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    originals=[]
    try:
        spigot=a.load(spigot_path);paper=a.load(paper_path);supplement=a.load(supplement_path)
        originals=[dict(path=str(Path(p).resolve()),sha256=hashlib.sha256(a.read(p)).hexdigest(),originalPassed=m['passed'],originalFailures=m['failures']) for p,m in [(spigot_path,spigot),(paper_path,paper),(supplement_path,supplement)]]
        a.require(spigot['suite']=='spigot' and paper['suite']=='paper' and supplement['suite']=='paper','exact baseline lane roles')
        a.require(spigot['jarSha256']==paper['jarSha256']==supplement['jarSha256'],'same frozen production JAR for both baselines and supplement')
        a.require(production_hashes(spigot)==production_hashes(paper)==production_hashes(supplement),'production source hash sets match both baselines and focused supplement')
        for m in [spigot,paper,supplement]:base_integrity(a,m)
        a.require(spigot['passed'] and not spigot['failures'] and len(spigot['cases'])==15 and all(c['status']=='PASS' for c in spigot['cases']),'Spigot original all-fifteen pass retained')
        a.require(supplement.get('focus')=='death-back' and supplement['passed'] and not supplement['failures'] and supplement['plannedCases']==['death-back-config-switch']
                  and len(supplement['cases'])==1 and supplement['cases'][0]['name']=='death-back-config-switch' and supplement['cases'][0]['status']=='PASS' and all(c['passed'] for c in supplement['checks']),
                  'exact one-case live Paper death supplement passes independently',dict(path=str(supplement_path),cases=supplement['cases'],checks=len(supplement['checks'])))
        a.require(set(paper['failures'])=={DENIAL,DEATH,DERIVED} and {c['label'] for c in paper['checks'] if not c['passed']}=={DENIAL,DEATH,DERIVED}
                  and {c['name'] for c in paper['cases'] if c['status']=='FAIL'}=={STAFF,'death-back-config-switch'} and len(paper['cases'])==15,
                  'only exact authorized Paper denial/death and derived summary failures may be resolved; every other failure blocks',dict(failures=paper['failures'],failedCases=[c['name'] for c in paper['cases'] if c['status']!='PASS']))
        for m in [spigot,paper]:
            names=[c['name'] for c in m['cases']]
            expected_failed={STAFF,'death-back-config-switch'} if m['suite']=='paper' else set()
            a.require(m['plannedCases']==EXPECTED_CASES and len(names)==15 and len(set(names))==15 and set(names)==set(EXPECTED_CASES)
                      and all(c['status']==('FAIL' if c['name'] in expected_failed else 'PASS') for c in m['cases']),
                      m['suite']+': exact unique fifteen planned case names and explicit status for every case',dict(cases=m['cases']))
            root=Path(m['run']);client=a.lines(root/'TeleA-client.log');server=a.lines(root/'server.log')
            privacy(a,m,client);hazards(a,m,server,client)
            if m['suite']=='paper':paper_denial(a,m,server,a.lines(root/'TeleStaff-client.log'))
        result['passed']=True
        result['resolutions']=[dict(originalManifest=str(Path(paper_path).resolve()),originalLabel=DENIAL,method='exact retained bundled command component plus recorded false-permission/stationary/no-event premises'),
                               dict(originalManifest=str(Path(paper_path).resolve()),originalLabel=DEATH,method='new one-case live supplement; original missing second death remains recorded',supplementManifest=str(Path(supplement_path).resolve())),
                               dict(originalManifest=str(Path(paper_path).resolve()),originalLabel=DERIVED,method='derived summary only; fifteen case outcomes verified with the two explicit resolutions')]
    except Exception as e:result['error']=str(e)
    result['originals']=originals
    # Ensure no inputs changed during this audit, including original manifests and retained logs.
    changes=[p for p,meta in a.inputs.items() if hashlib.sha256(Path(meta['path']).read_bytes()).hexdigest()!=meta['sha256']]
    if changes:result['passed']=False;result['error']='inputs changed during audit: '+repr(changes)
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,indent=2)+'\n')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--spigot',required=True,type=Path);p.add_argument('--paper',required=True,type=Path);p.add_argument('--supplement',required=True,type=Path)
    p.add_argument('--output',required=True,type=Path);p.add_argument('--overrides',type=Path,help='test-only mapping of original input paths to disposable mutated copies')
    args=p.parse_args();overrides=json.loads(args.overrides.read_text()) if args.overrides else None
    result=run_audit(args.spigot,args.paper,args.supplement,args.output,overrides)
    print(('PASS ' if result['passed'] else 'FAIL ')+str(args.output)+((': '+result['error']) if result.get('error') else ''))
    sys.exit(0 if result['passed'] else 1)
