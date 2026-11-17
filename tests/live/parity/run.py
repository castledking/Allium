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
HERE=RUN/'harness/tests/live/parity'
original_build=build

def build():
    global HERE
    own=HERE;HERE=ORIGINAL;original_build();HERE=own
    classes=RUN/'parity-helper/classes';classes.mkdir(parents=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac','--release','17','-cp',f'{API}:{JAR}','-d',str(classes),str(HERE/'plugin/gate/ParityGate.java')],check=True)
    shutil.copy2(HERE/'plugin/plugin.yml',classes/'plugin.yml')
    shutil.copy2(HERE/'plugin/legacy-ids.csv',classes/'legacy-ids.csv')
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/jar','cf',str(RUN/'parity-helper/ParityGate.jar'),'-C',str(classes),'.'],check=True)

def fixture(lane,text):
    mark=len(lane.logs);lane.console('parityg '+text)
    line=wait(lambda:next((s for s in lane.logs[mark:] if 'PARITY-DONE '+text+' => ' in s or 'PARITY-FAIL '+text+' => ' in s),None),'parity fixture '+text)
    if 'PARITY-FAIL' in line:raise RuntimeError(line)
    return json.loads(line.split(' => ',1)[1])

def items(lane):return fixture(lane,'items InfoNorm')['items']
def close(a,b):return sum((a[k]-b[k])**2 for k in ['x','y','z'])<.1

def run_suite(lane):
    scopes.append(lane.name)
    shutil.copy2(RUN/'parity-helper/ParityGate.jar',lane.path/'plugins/ParityGate.jar')
    path=lane.path/'plugins/Allium/config.yml';cfg=yaml.safe_load(path.read_text());cfg['teleport']['warmup-seconds']=0;path.write_text(yaml.safe_dump(cfg,sort_keys=False))
    lane.boot()
    for name in ['InfoNorm','InfoObs']:
        b=Bot(lane,name);lane.bots[name]=b;b.connect()
    bot=lane.bots['InfoNorm'];other=lane.bots['InfoObs']
    for perm in ['allium.give','allium.back','allium.enchant','allium.enchant.unsafe','allium.tp','allium.tp.others','allium.tphere','allium.tpthere','allium.tppet','allium.tpmob','allium.selectors','allium.command.selector.all','allium.command.selector.entities']:
        lane.fixture('perm InfoNorm '+perm+' true')
    require((lane.path/'plugins/Allium/itemdb.yml').is_file(),'compressed item database extracted')
    if lane.kind=='spigot':
        legacy=fixture(lane,'legacy')['mapping'];(RUN/'essentials-converted-ids.json').write_text(json.dumps(legacy,indent=2)+'\n')
    def give(text):
        lane.fixture('clearinv InfoNorm');mark=bot.cmd('i '+text);bot.chat(mark,'Gave');return items(lane)
    require(give('dsword')[0]['type']=='DIAMOND_SWORD','simple itemdb alias creates correct item')
    pot=give('acid2pot 2');require(sum(p['amount'] for p in pot)==2 and pot[0]['effects'][0]['duration']==2400,'potion alias duration is not an amount')
    pot=give('potion;speed;60;2');require(pot[0]['effects'][0]['duration']==1200 and pot[0]['effects'][0]['amplifier']==1,'explicit potion duration and level syntax')
    pot=give('tipped_arrow;poison;15;2');require(pot[0]['effects'][0]['duration']==2400,'legacy arrow duration compensation')
    require(give('splashpotion')[0]['type']=='SPLASH_POTION','legacy splash carrier alias')
    pot=give('potion;turtle_master;10');require(len(pot[0]['effects'])==2 and all(e['duration']==200 for e in pot[0]['effects']),'turtle master preserves explicit duration')
    arrow=give('tipped_arrow;poison;15');require(arrow[0]['effects'][0]['duration']==2400,'arrow duration retains legacy delivery compensation')
    require(give('splashpotion')[0]['type']=='SPLASH_POTION','splash sentinel alias changes carrier')
    turtle=give('potion;turtle_master;10');require(len(turtle[0]['effects'])==2 and all(e['duration']==200 for e in turtle[0]['effects']),'turtle master retains custom duration and both effects')
    require(give('zombie_spawner')[0]['spawnedType']=='ZOMBIE','spawner alias keeps entity type')
    require(give('1:1')[0]['type']=='GRANITE','numeric legacy id maps to material')
    require(give('1:2')[0]['type']=='POLISHED_GRANITE','numeric legacy data selects exact material')
    for ref,material in [('1','STONE'),('17:1','SPRUCE_LOG'),('35:14','RED_WOOL'),('383:54','ZOMBIE_SPAWN_EGG'),('31:1','GRASS' if lane.kind=='spigot' else 'SHORT_GRASS')]:
        require(give(ref)[0]['type']==material,'Essentials numeric '+ref+' resolves '+material)
    require(give('52:54')[0]['spawnedType']=='ZOMBIE','legacy spawner data resolves the entity, not a guessed alias')
    require(give('373:8258')[0]['basePotion'] in ['LONG_SPEED','LONG_SWIFTNESS'],'extended numeric potion retains its duration')
    require(give('373:8226')[0]['basePotion'] in ['STRONG_SPEED','STRONG_SWIFTNESS'],'upgraded numeric potion retains its level')
    require(give('373:16386')[0]['type']=='SPLASH_POTION','numeric splash potion keeps its carrier')
    require(give('373:8258')[0]['basePotion'] in ['LONG_SPEED','LONG_SWIFTNESS'],'extended numeric potion retains vanilla base type and duration')
    require(give('373:8225')[0]['basePotion'] in ['STRONG_REGEN','STRONG_REGENERATION'],'upgraded numeric regeneration retains exact native duration')
    require(not any(re.fullmatch(r'[0-9]+(?::[0-9]+)?',v) for v in tab(bot,'i 1')),'numeric IDs absent from i completion')
    require(not any(re.fullmatch(r'[0-9]+(?::[0-9]+)?',v) for v in tab(bot,'give InfoNorm 1')),'numeric IDs absent from give completion')

    require('allium:mob_disarmer' in tab(bot,'i allium:mob_'),'Allium definition completes')
    require('dsword' in tab(bot,'i dsw'),'database alias completes')
    ench=tab(bot,'i DIAMOND_SWORD;');require('DIAMOND_SWORD;sharpness:' in ench and not any('protection:' in v for v in ench),'semicolon completion only offers sword enchants')
    require('diamond_sword' in tab(bot,'give InfoNorm diamond_sw'),'give item completion works on native command route')
    got=give('diamond_sword;sharpness:99');require(got[0]['enchants'].get('minecraft:sharpness')==5,'safe give clamps enchant level')
    fixture(lane,'held InfoNorm 0')
    require('sharpness' not in tab(bot,'enchant ') and 'smite' not in tab(bot,'enchant '),'enchant completion excludes present/conflicting enchants')
    mark=bot.cmd('enchant smite 1');bot.chat(mark,'cannot be combined');require(items(lane)[0]['enchants'].get('minecraft:sharpness')==5,'conflicting command enchant refused')
    mark=bot.cmd('enchant sharpness 99');bot.chat(mark,'Limiting to');require(items(lane)[0]['enchants'].get('minecraft:sharpness')==5,'permission alone cannot enable unsafe levels')
    cfg=yaml.safe_load(path.read_text());cfg['allow-unsafe-enchants']=True;path.write_text(yaml.safe_dump(cfg,sort_keys=False));lane.console_cmd('allium reload','Configuration reloaded.')
    mark=bot.cmd('enchant sharpness 99');bot.chat(mark,'Applied');require(items(lane)[0]['enchants'].get('minecraft:sharpness')==99,'unsafe level requires switch and permission')
    raw='stone{display:{Lore:[\'{"text":"raw,semi;colon"}\']},Unbreakable:1b}' if lane.kind=='spigot' else 'stone[lore=[{text:"raw,semi;colon"}],unbreakable={}]'
    got=give(raw+' 70');require(sum(p['amount'] for p in got)==70 and all(p['amount']<=64 and p['unbreakable'] and p['lore'] for p in got),'native raw NBT/components survive and stacks split safely')
    # Restore safe mode before testing raw embedded enchants.
    cfg=yaml.safe_load(path.read_text());cfg['allow-unsafe-enchants']=False;path.write_text(yaml.safe_dump(cfg,sort_keys=False));lane.console_cmd('allium reload','Configuration reloaded.')
    raw_enchant='diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:99s}]}' if lane.kind=='spigot' else 'diamond_sword[enchantments={"minecraft:sharpness":99}]'
    require(give(raw_enchant)[0]['enchants'].get('minecraft:sharpness')==5,'raw enchantment data cannot bypass safe level policy')
    for alias in ['tphere','here','s']:
        fixture(lane,'place InfoNorm 0 0');fixture(lane,'place InfoObs 30 0');dest=fixture(lane,'state InfoNorm');bot.cmd(alias+' InfoObs');wait(lambda:close(fixture(lane,'state InfoObs'),dest),alias+' movement');require(True,alias+' moves named player')
    fixture(lane,'place InfoObs 30 0');dest=fixture(lane,'state InfoObs');bot.chat(bot.cmd('tp @s InfoObs'),'Teleported');require(close(fixture(lane,'state InfoNorm'),dest),'tp selector moves actor')
    fixture(lane,'place InfoNorm 0 0');dest=fixture(lane,'state InfoNorm');fixture(lane,'place InfoObs 30 0');bot.cmd('here @a');wait(lambda:close(fixture(lane,'state InfoObs'),dest),'all selector');require(True,'here all-player selector')
    fixture(lane,'look InfoNorm');origin=fixture(lane,'state InfoNorm');bot.chat(bot.cmd('there'),'Teleported');state=fixture(lane,'state InfoNorm');require(abs(state['z']-5.5)<1,'there resolves viewed block face safely')
    bot.chat(bot.cmd('back'),'Returned');require(close(fixture(lane,'state InfoNorm'),origin),'back returns to the origin of there')
    fixture(lane,'place InfoNorm 0 0');pet=fixture(lane,'spawn WOLF PetOne 1 0 InfoNorm');bot.chat(bot.cmd('tppet'),'selection enabled');bot.write('interact '+str(pet['entityId']));wait(lambda:fixture(lane,'state PetOne')['glowing'],'pet selected');fixture(lane,'place InfoNorm 15 0');wait(lambda:close(fixture(lane,'state PetOne'),fixture(lane,'state InfoNorm')),'pet follows player teleport');require(not fixture(lane,'state PetOne')['glowing'],'pet glow restored after movement')
    fixture(lane,'place InfoNorm 0 0');mob=fixture(lane,'spawn VILLAGER MobOne 2 0 InfoNorm');bot.chat(bot.cmd('tpent'),'selection enabled');fixture(lane,'protect true');bot.write('interact '+str(mob['entityId']));window(.3);require(not fixture(lane,'state MobOne')['glowing'],'protection cancellation prevents selection');fixture(lane,'protect false');bot.write('interact '+str(mob['entityId']));wait(lambda:fixture(lane,'state MobOne')['glowing'],'entity selected')
    # Move the actor only by walking-fixture? selection follows external teleports, so /s pulls at its current point.
    bot.chat(bot.cmd('s'),'Teleported');wait(lambda:not fixture(lane,'state MobOne')['glowing'],'selection consumed');first=fixture(lane,'state MobOne');bot.cmd('here');window(.3);require(fixture(lane,'state MobOne')['moves']==first['moves'],'selection claimed once across repeated here aliases')

def tab(bot,text):
    start=len(bot.lines);bot.write('tab '+text)
    req=wait(lambda:next((json.loads(s.split(' ',1)[1]) for s in bot.lines[start:] if s.startswith('MSGG-TABREQ ')),None),'request')
    window(.6)
    response=next((json.loads(s.split(' ',1)[1]) for s in bot.lines[start:] if s.startswith('MSGG-TAB ') and json.loads(s.split(' ',1)[1])['id']==req['id']),None)
    return response['matches'] if response else []

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
