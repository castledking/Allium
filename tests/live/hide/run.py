#!/usr/bin/env python3
"""Hide native SQLite gate; shares the playerinfo lane lifecycle."""
from pathlib import Path
BASE=Path(__file__).resolve().parents[1]/'playerinfo/run.py'
source=BASE.read_text()
OWN_FILE=__file__
__file__=str(BASE)
exec(compile(source[:source.rindex('\ntry:\n    build()')],str(BASE),'exec'))
__file__=OWN_FILE
ORIGINAL=HERE
HERE=Path(__file__).resolve().parent
for p in HERE.rglob('*'):
    if p.is_file():
        SOURCE_HASHES[str(p.relative_to(REPO))]=hashlib.sha256(p.read_bytes()).hexdigest()
        dest=RUN/'harness'/p.relative_to(REPO);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)
HERE=RUN/'harness/tests/live/hide'
original_build=build

def build():
    global HERE
    own=HERE;HERE=ORIGINAL;original_build();HERE=own
    classes=RUN/'hide-helper/classes';classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac','--release','17','-cp',f'{API}:{JAR}','-d',str(classes),str(HERE/'plugin/gate/HideGate.java')],check=True)
    shutil.copy2(HERE/'plugin/plugin.yml',classes/'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar','cf',str(RUN/'hide-helper/HideGate.jar'),'-C',str(classes),'.'],check=True)

def tab(bot,text):
    start=len(bot.lines);bot.write('tab '+text)
    req=wait(lambda:next((json.loads(s.split(' ',1)[1]) for s in bot.lines[start:] if s.startswith('MSGG-TABREQ ')),None),'request')
    window(.6)
    response=next((json.loads(s.split(' ',1)[1]) for s in bot.lines[start:] if s.startswith('MSGG-TAB ') and json.loads(s.split(' ',1)[1])['id']==req['id']),None)
    return response['matches'] if response else []

def run_suite(lane):
    scopes.append(lane.name)
    shutil.copy2(RUN/'hide-helper/HideGate.jar',lane.path/'plugins/HideGate.jar')
    pe=INFRA/'tools/packetevents-spigot-2.14.0-official.jar'
    assert hashlib.sha256(pe.read_bytes()).hexdigest()=='060087c58ec268eae7dd0eb1f17ac3bea1eaadda1693414141ff64e506091bd7'
    shutil.copy2(pe,lane.path/'plugins/PacketEvents.jar')
    cfg={'settings':{'enabled':True,'unknown-command-completion-help-message':'&c&lHIDE 100% &7Try /help'},'groups':{
        'default':{'whitelist':True,'commands':['hgparty invite','party invite','hgmapped','hgguarded','near','help','i'],'tabcompletes':['hgparty invite','party invite','hgmapped','hgguarded','near','help','i']},
        'alt':{'whitelist':False,'commands':['party invite','hgparty invite'],'tabcompletes':['party invite','hgparty invite']},
        'vip':{'whitelist':True,'commands':['hideghost'],'tabcompletes':['^']}}}
    (lane.path/'plugins/Allium/hide.yml').write_text(yaml.safe_dump(cfg,sort_keys=False,default_flow_style=True))
    config_path=lane.path/'plugins/Allium/config.yml'
    config=yaml.safe_load(config_path.read_text())
    config['resource-pack']['owner']='standalone'
    config_path.write_text(yaml.safe_dump(config,sort_keys=False))
    helper=lane.path/'plugins/Allium/generated/Allium_unknown_command_resource_pack.zip'
    helper.parent.mkdir()
    with zipfile.ZipFile(helper,'w') as old:
        old.writestr('pack.mcmeta','{"allium-command-hints":1}')
    lane.boot();lane.console_cmd('allium hide status','packet-filter=true')
    bot=Bot(lane,'InfoNorm');lane.bots[bot.name]=bot;bot.connect();window(1)
    trees=[json.loads(s.split(' ',1)[1]) for s in bot.lines if s.startswith('HIDE-TREE ')]
    require(bool(trees) and 'hideghost' not in json.dumps(trees[-1]) and 'hiddenbranch' not in json.dumps(trees[-1]),'wire tree excludes hidden root and scoped cached children')
    installed=lane.path/'plugins/Allium/lang/en_US.yml'
    lang=yaml.safe_load(installed.read_text())
    require(all(k in lang for k in ['unknown-command','unknown-command-suggestion','no-permission']),'legacy root language keys generated')
    require('\n- ' in (lane.path/'plugins/Allium/hide.yml').read_text() or '\n    - ' in (lane.path/'plugins/Allium/hide.yml').read_text(),'startup expands flow lists to block sequences')
    help_start=bot.cmd('help');bot.chat(help_start,'Help Menu');window(.3)
    help_lines=[c['plain'] for c in bot.chats(help_start)]
    require(any('/party invite' in s for s in help_lines) and not any('/hgguarded' in s or '/hideghost' in s or '/party kick' in s for s in help_lines),'help shows allowed paths and hides denied permissions/siblings')
    bot.chat(bot.cmd('hel'),'Did you mean');require(True,'unknown typo suggests visible executable command')
    bot.chat(bot.cmd('party invite'),'HIDE-ACTION hgparty invite');require(True,'allowed alias executes')
    bot.chat(bot.cmd('party kick'),'Unknown command');require(True,'sibling execution refused')
    require(tab(bot,'party ')==['invite'],'full path suggestions filtered')
    # Spigot and Paper never answer a request whose completion is empty. 40 of them used to fill
    # the 32-entry ledger, after which every new request was dropped until they expired.
    burst=len(bot.lines)
    for i in range(40):bot.write(f'tab zzunanswered{i} x')
    wait(lambda:sum(1 for s in bot.lines[burst:] if s.startswith('MSGG-TABREQ '))>=40,'unanswered burst sent')
    window(1)
    require(not any(s.startswith('MSGG-TAB ') for s in bot.lines[burst:]),'requests with nothing to complete get no answer from the server')
    require(tab(bot,'party ')==['invite'],'suggestions still answer right after 40 unanswered requests')
    require(tab(bot,'i diamond_s')==[],'/i completes nothing without allium.give')
    lane.fixture('perm InfoNorm allium.give true')
    items=tab(bot,'i diamond_s')
    require('diamond_sword' in items and all(v.startswith('diamond_s') for v in items),'/i item names pass the resolver with allium.give',items)
    require('stick,gshovel' in tab(bot,'i stick,gsh'),'/i completes the entry after a comma')
    lane.fixture('perm InfoNorm allium.give false')
    require(tab(bot,'')==[],'bare slash response withheld')
    require(not any(':' in s for s in tab(bot,'h')),'namespaced root suggestions absent')
    bot.chat(bot.cmd('hgguarded'),'permission');require(True,'native permission denies whitelisted command')
    lang['no-permission']=['{prefix} &cRESOLVER-DENIED /{cmd}'];installed.write_text(yaml.safe_dump(lang,sort_keys=False))
    lane.console_cmd('allium reload','Configuration reloaded.')
    bot.chat(bot.cmd('hgguarded'),'RESOLVER-DENIED /hgguarded');require(True,'allowed path resolver denial uses root no-permission')
    policy_path=lane.path/'plugins/Allium/hide.yml';policy=yaml.safe_load(policy_path.read_text());policy['settings']['use-allium-permission-resolver']=False;policy_path.write_text(yaml.safe_dump(policy,sort_keys=False))
    lane.console_cmd('core hide reload','Hide configuration reloaded')
    start=bot.cmd('hgguarded');bot.chat(start,'command.unknown.command');window(.3)
    require(not any('RESOLVER-DENIED' in c['plain'] for c in bot.chats(start)),'resolver off delegates refusal to native command')
    policy['settings']['use-allium-permission-resolver']=True;policy_path.write_text(yaml.safe_dump(policy,sort_keys=False));lane.console_cmd('core hide reload','Hide configuration reloaded')
    lane.fixture('perm InfoNorm hidegate.guarded true');bot.chat(bot.cmd('hgguarded'),'HIDE-ACTION hgguarded');require(True,'live permission grant takes effect')
    lane.console_cmd('core hide fix hgmapped hidegate.manual','Hide configuration updated')
    bot.chat(bot.cmd('hgmapped'),'RESOLVER-DENIED');lane.fixture('perm InfoNorm hidegate.manual true');bot.chat(bot.cmd('hgmapped'),'HIDE-ACTION hgmapped')
    lane.console_cmd('core hide resolve InfoNorm hgmapped','via OVERRIDE');require(True,'unknown-only override enforces dotted permission')
    lane.fixture('perm InfoNorm group.vip true');window(2.3);bot.chat(bot.cmd('hideghost'),'HIDE-ACTION hideghost')
    require('hideghost' in tab(bot,'hideg'),'membership refresh updates completion')
    lane.fixture('perm InfoNorm group.vip false');lane.fixture('perm InfoNorm allium.hide.bypass true');lane.fixture('perm InfoNorm group.alt true')
    bot.chat(bot.cmd('party invite'),'Unknown command');require(True,'blacklist wins over execution bypass')
    lane.fixture('perm InfoNorm group.alt false');lane.fixture('perm InfoNorm allium.hide.bypass false')
    bad=lane.path/'plugins/Allium/hide.yml';before=bad.read_bytes();bad.write_text('groups: [invalid\n')
    lane.console_cmd('core hide reload','Hide configuration was refused');bot.chat(bot.cmd('party kick'),'Unknown command');bad.write_bytes(before);require(True,'invalid reload preserves active rules')
    pack=lane.path/'plugins/Allium/pack/allium-core-pack.zip'
    with zipfile.ZipFile(pack) as z:
        locales=[name for name in z.namelist() if name.startswith('assets/minecraft/lang/') and name.endswith('.json')]
        require(len(locales)>=142 and all('100%%' in json.loads(z.read(name))['command.unknown.command'] for name in locales),
                'configured hint is merged into 142 locales in the standalone pack')
    require(not helper.parent.exists(),'old owned helper and empty generated directory removed')
    bot.disconnect();lane.bots.clear();lane.stop();(lane.path/'plugins/PacketEvents.jar').unlink()
    lane.boot();lane.console_cmd('allium hide status','packet-filter=false')
    bot=Bot(lane,'InfoNorm');lane.bots[bot.name]=bot;bot.connect();window(.5)
    trees=[json.loads(s.split(' ',1)[1]) for s in bot.lines if s.startswith('HIDE-TREE ')]
    require(bool(trees) and 'hgparty' not in json.dumps(trees[-1]),'without PacketEvents scoped roots withheld')
    bot.chat(bot.cmd('party invite'),'HIDE-ACTION hgparty invite');bot.chat(bot.cmd('party kick'),'Unknown command');require(True,'without PacketEvents typed path authorization works')
    require('hidegate.manual' in (lane.path/'plugins/Allium/hide.yml').read_text(),'override survived restart')

try:
    build()
    if A.suite == 'spigot':
        run_suite(Lane('spigot', 'spigot', 25780))
    else:
        run_suite(Lane('paper', 'paper', 25781))
except BaseException:
    emit('EXCEPTION:\n' + traceback.format_exc())
    failures.append('exception')
finally:
    for lane in lanes:
        for bot in list(lane.bots.values()):
            try:
                bot.disconnect()
            except Exception:
                pass
        try:
            lane.stop()
        except Exception as exc:
            failures.append(lane.name + ' cleanup: ' + str(exc))
            emit('CLEANUP FAIL ' + lane.name + ': ' + str(exc))
    manifest = {
        'suite': A.suite,
        'runName': A.run_name,
        'alliumJar': str(A.allium_jar),
        'sha256': A.sha256,
        'sourceHashes': SOURCE_HASHES,
        'scopes': scopes,
        'durationSeconds': round(time.time() - started, 2),
        'checksPassed': sum(1 for c in checks if c['passed']),
        'checksTotal': len(checks),
        'failures': failures,
        'checks': checks,
        'observations': observations,
    }
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    emit(f"SUMMARY suite={A.suite} passed={manifest['checksPassed']}/{manifest['checksTotal']} duration={manifest['durationSeconds']}s")
sys.exit(1 if failures else 0)
