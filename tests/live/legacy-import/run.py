#!/usr/bin/env python3
"""Legacy Allium import live gate: real servers read a legacy-shaped H2 database.

Builds plugins/Allium/database/sfcore.mv.db with legacy Allium's table definitions (fixture rows only:
no production data) through the H2 that will read it (the shaded legacy Allium jar's relocated H2 on
Spigot, as a real legacy server wrote it; a plain h2 2.1.214 jar on Paper), places that jar in
plugins/Allium/import/, then drives
/allium import legacy from the console: the dry run and its reports, apply refused with players
online and after the plan changed, apply on an empty server, replay refusal, a second dry run that
sees the ledger, and real clients using the imported home and warp.

Reuses the staff gate's lanes, clients and StaffGate fixture (same owned ports 25860/25861).
"""
from pathlib import Path
import hashlib as _hashlib
import sys as _sys

BASE = Path(__file__).resolve().parents[1] / 'staff/run.py'
OWN = Path(__file__).resolve()
_source = BASE.read_text()
__file__ = str(BASE)
exec(compile(_source[:_source.rindex('\nmain()')], str(BASE), 'exec'))
__file__ = str(OWN)
for _own in [OWN, OWN.parent / 'LegacyFixture.java']:
    SOURCE_HASHES[str(_own.relative_to(REPO))] = _hashlib.sha256(_own.read_bytes()).hexdigest()

H2_JAR = INFRA / 'tools/h2-2.1.214.jar'
H2_SHA = 'd623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0'
LEGACY_JAR = INFRA / 'tools/legacy-allium-0.2.20a-shaded.jar'
LEGACY_SHA = 'dcb068063ed0d84fcd44de539f8a73a831fe2e94b0552e290a4f567f7b85115c'
for jar, sha in [(H2_JAR, H2_SHA), (LEGACY_JAR, LEGACY_SHA)]:
    if hashlib.sha256(jar.read_bytes()).hexdigest() != sha:
        raise SystemExit('input jar hash mismatch: ' + str(jar))

GHOST = '33333333-3333-4333-8333-333333333333'


def offline_uuid(name):
    h = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    h[6] = h[6] & 0x0f | 0x30
    h[8] = h[8] & 0x3f | 0x80
    import uuid
    return str(uuid.UUID(bytes=bytes(h)))


PLAYER = offline_uuid('PlayerBot')
STAFF = offline_uuid('StaffBot')


def legacy_sql():
    rows = []

    def loc(owner, kind, name, world, x, y, z, at, yaw=12.5, pitch=-3):
        n = 'NULL' if name is None else "'" + name + "'"
        rows.append(f"INSERT INTO player_locations (player_uuid, location_type, home_name, world, x, y, z, yaw, pitch, timestamp) "
                    f"VALUES ('{owner}', '{kind}', {n}, '{world}', {x}, {y}, {z}, {yaw}, {pitch}, {at});")
    loc(PLAYER, 'HOME', 'base', 'world', 10.5, -61, 10.5, 1000)
    loc(PLAYER, 'HOME', 'base', 'world', 20.5, -61, 20.5, 2000)
    loc(PLAYER, 'HOME', 'Farm', 'world', 30.5, -61, -30.5, 1500)
    loc(PLAYER, 'HOME', 'far', 'world', 1000.5, -61, 1000.5, 1500)
    loc(PLAYER, 'HOME', 'void', 'world', 50000.5, -61, 50000.5, 1500)
    loc(PLAYER, 'HOME', 'my home', 'world', 1, -61, 1, 1500)
    loc(PLAYER, 'HOME', 'nether', 'old_skyblock', 5, 40, 5, 1500)
    loc(PLAYER, 'HOME', None, 'world', 0, -61, 0, 900)
    loc(GHOST, 'HOME', 'home', 'world', 5.5, -61, 5.5, 1000)
    loc(GHOST, 'LOGOUT', None, 'world', 40.5, -61, 40.5, 5000, 0, 0)
    loc(GHOST, 'LOGOUT', None, 'world', 41.5, -61, 41.5, 6000, 0, 0)
    loc(PLAYER, 'LOGOUT', None, 'world', 70.5, -61, 70.5, 6000, 0, 0)
    loc(PLAYER, 'DEATH', None, 'world', 9, -61, 9, 7000, 0, 0)
    return '\n'.join([
        "CREATE TABLE IF NOT EXISTS database_version (version INT PRIMARY KEY, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",
        "INSERT INTO database_version (version) VALUES (9);",
        "CREATE TABLE IF NOT EXISTS player_locations (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
        "location_type VARCHAR(50) NOT NULL, home_name VARCHAR(50), world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
        "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, timestamp BIGINT NOT NULL, "
        "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_home (player_uuid, location_type, home_name));",
        "CREATE TABLE IF NOT EXISTS warps (name VARCHAR(50) PRIMARY KEY, world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
        "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, creator_uuid VARCHAR(36) NOT NULL, creator_name VARCHAR(16) NOT NULL, "
        "is_public BOOLEAN DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP);",
        "CREATE TABLE IF NOT EXISTS player_max_homes (uuid VARCHAR(36) PRIMARY KEY, max_homes INT NOT NULL DEFAULT 1);",
        *rows,
        f"INSERT INTO warps (name, world, x, y, z, yaw, pitch, creator_uuid, creator_name, is_public) VALUES "
        f"('Spawn', 'world', 0.5, -61, 0.5, 90, 0, '{STAFF}', 'StaffBot', TRUE), "
        f"('shop', 'world', 50.5, -61, 50.5, 0, 0, '{STAFF}', 'StaffBot', FALSE), "
        f"('bad warp', 'world', 1, -61, 1, 0, 0, '{STAFF}', 'StaffBot', TRUE);",
        f"INSERT INTO player_max_homes (uuid, max_homes) VALUES ('{PLAYER}', 5);",
    ]) + '\n'


def build_legacy_db(lane):
    db = lane.path / 'plugins/Allium/database'
    db.mkdir(parents=True, exist_ok=True)
    script = RUN / (lane.name + '-legacy.sql')
    script.write_text(legacy_sql())
    # Each lane's database is written by the H2 build that will read it: the legacy jar's relocated
    # H2 on Spigot (what a real legacy server wrote), a plain H2 on Paper.
    driver = LEGACY_JAR if lane.kind == 'spigot' else H2_JAR
    driver_class = 'codes.castled.allium.libs.h2.Driver' if lane.kind == 'spigot' else 'org.h2.Driver'
    tool = RUN / 'legacy-fixture'
    if not (tool / 'LegacyFixture.class').exists():
        tool.mkdir(exist_ok=True)
        subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/javac', '--release', '17', '-d', str(tool), str(OWN.parent / 'LegacyFixture.java')], check=True)
    subprocess.run(['/usr/lib/jvm/java-17-openjdk/bin/java', '-cp', str(tool), 'LegacyFixture', str(driver), driver_class,
                    str(db / 'sfcore'), str(script)], check=True)
    imports = lane.path / 'plugins/Allium/import'
    imports.mkdir(parents=True, exist_ok=True)
    for jar in [LEGACY_JAR, H2_JAR]:
        shutil.copy2(jar, imports / jar.name)
    return db / 'sfcore.mv.db', driver


def use_driver(lane, jar):
    (lane.path / 'plugins/Allium/import/legacy-allium.yml').write_text(
        'source: database/sfcore.mv.db\ndriver: import/' + jar.name + '\nworlds: {}\n')


def console_wait(lane, command, fragment, timeout=60):
    start = len(lane.logs)
    lane.console(command)
    return lane.log_since(start, fragment, timeout)


def dry_run(lane):
    line = console_wait(lane, 'allium import legacy', 'Legacy import dry run legacy-')
    run = re.search(r'(legacy-\d{8}-\d{6}(?:-\d+)?)', line).group(1)
    folder = lane.path / 'plugins/Allium/import/legacy' / run
    plan = json.loads((folder / 'plan.json').read_text())
    records = [json.loads(v) for v in (folder / 'records.jsonl').read_text().splitlines() if v]
    return run, folder, plan, records


def find(records, domain, key):
    return next((r for r in records if r['domain'] == domain and r['key'] == key), None)


def legacy_import(lane, source, driver):
    staff, player = lane.bots['StaffBot'], lane.bots['PlayerBot']
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    # A warp of this server that a legacy warp of the same name must not replace.
    lane.perm('StaffBot', 'allium.setwarp')
    lane.fixture('tp StaffBot 60.5 -61 60.5 0 0')
    window(.5)
    staff.command('setwarp shop', 'Warp shop set')

    # H2 stores its class names in the file: the other H2 build cannot read it, and says why.
    use_driver(lane, H2_JAR if driver == LEGACY_JAR else LEGACY_JAR)
    console_wait(lane, 'allium import legacy', 'cannot read this database (error 90030)')
    require(True, lane.name + ': a driver from a different H2 build is refused with an explanation')
    use_driver(lane, driver)
    run1, folder1, plan, records = dry_run(lane)
    c = plan['counts']
    require(c['home']['import'] == 5 and c['home']['blocked'] == 2 and c['home']['skipped'] == 1,
            lane.name + ': dry run plans 5 homes, blocks 2 (a name with a space, an unloaded world), skips 1 superseded row', c['home'])
    base = find(records, 'home', PLAYER + '/base')
    require(base and base['status'] == 'import' and base['x'] == 20.5, lane.name + ': the newest of two legacy rows for one home wins', base)
    require(find(records, 'home', PLAYER + '/farm')['status'] == 'import', lane.name + ': legacy names are lowercased (Farm -> farm)')
    nether = find(records, 'home', PLAYER + '/nether')
    require(nether['status'] == 'blocked' and 'old_skyblock' in nether['reason'], lane.name + ': an unloaded world blocks the home, never substituted', nether)
    require(c['warp']['import'] == 1 and c['warp']['conflict'] == 1 and c['warp']['blocked'] == 1,
            lane.name + ': warps: spawn planned, shop a conflict with this server\'s warp, "bad warp" blocked', c['warp'])
    require(c['logout']['import'] == 2, lane.name + ': both players\' newest logout positions are planned', c['logout'])
    require(plan['unnamedHomes'] == 1 and plan['supersededLogoutRows'] == 1 and plan['otherLocationTypes'] == {'DEATH': 1},
            lane.name + ': unnamed homes, older logouts and other location types are counted, not imported', plan)
    require(driver.name in plan['driver'] and ('codes.castled.allium.libs.h2.Driver' if lane.kind == 'spigot' else 'org.h2.Driver') in plan['driver']
            and '2.1.214' in plan['h2'], lane.name + ': the H2 driver came from ' + driver.name, plan['driver'] + ' / ' + plan['h2'])
    require(plan['sourceSha256'] == before == hashlib.sha256(source.read_bytes()).hexdigest(),
            lane.name + ': the dry run hashed the source and left it unchanged')
    overrides = (folder1 / 'staff-overrides.yml').read_text()
    require(PLAYER + ': 5' in overrides, lane.name + ': legacy max homes become a staff-overrides snippet', overrides)
    summary = (folder1 / 'summary.txt').read_text()
    require('Needs a decision' in summary and 'shop' in summary and 'my home' in summary, lane.name + ': the summary lists what needs a decision', summary)

    console_wait(lane, 'allium import legacy apply ' + run1, 'players are online')
    require(True, lane.name + ': apply is refused while players are online')
    staff.disconnect()
    player.disconnect()
    window(2.5)  # each quit saves a logout position asynchronously
    console_wait(lane, 'allium import legacy apply ' + run1, 'the plan differs from ' + run1)
    require(True, lane.name + ': apply is refused when the plan changed since the dry run (PlayerBot\'s logout was saved here)')

    run2, folder2, plan2, records2 = dry_run(lane)
    p_logout = find(records2, 'logout', PLAYER)
    require(p_logout['status'] == 'skipped' and 'newer' in p_logout['reason'], lane.name + ': this server\'s newer logout position is kept', p_logout)
    line = console_wait(lane, 'allium import legacy apply ' + run2, 'Legacy import ' + run2 + ' applied')
    require('5 imported' in line, lane.name + ': apply imports the 5 homes', line.strip()[-200:])
    ghost = lane.fixture('travel ' + GHOST)
    mine = lane.fixture('travel ' + PLAYER)
    homes = {h['name']: h for h in mine['homes']}
    require(set(homes) == {'base', 'farm', 'far', 'void'} and homes['base']['x'] == 20.5 and homes['base']['z'] == 20.5
            and abs(homes['base']['yaw'] - 12.5) < 1e-6, lane.name + ': PlayerBot\'s homes are in the travel table', mine['homes'])
    require(ghost['homes'] and ghost['homes'][0]['name'] == 'home' and ghost['logout'] and ghost['logout'][0]['x'] == 41.5,
            lane.name + ': an offline-only player gets the home and the newest logout position', ghost)
    require(mine['logout'] and mine['logout'][0]['x'] != 70.5, lane.name + ': PlayerBot\'s logout saved by this server is untouched', mine['logout'])
    warps = {w['name']: w for w in ghost['warps']}
    require(set(warps) == {'spawn', 'shop'} and warps['spawn']['x'] == 0.5 and warps['shop']['x'] == 60.5,
            lane.name + ': spawn is imported; this server\'s shop is kept', ghost['warps'])
    ledger = {r['domain']: r['n'] for r in ghost['ledger']}
    require(ledger == {'home': 5, 'logout': 1, 'warp': 1} and [r['run_id'] for r in ghost['runs']] == [run2],
            lane.name + ': the ledger holds each applied record and the run', dict(ledger=ghost['ledger'], runs=ghost['runs']))
    require(hashlib.sha256(source.read_bytes()).hexdigest() == before, lane.name + ': the legacy database is unchanged after apply')

    console_wait(lane, 'allium import legacy apply ' + run2, 'was already applied')
    require(True, lane.name + ': a run cannot be applied twice')
    run3, _, plan3, _ = dry_run(lane)
    require(plan3['counts']['home']['import'] == 0 and plan3['counts']['home']['replayed'] == 5
            and plan3['counts']['warp']['replayed'] == 1, lane.name + ': a later dry run sees the ledger: nothing left to import', plan3['counts'])

    player = lane.bot('PlayerBot')
    staff = lane.bot('StaffBot')
    window(1)
    m = player.cmd('homes')
    line = player.chat(m, 'Homes:')
    require('base' in line['plain'] and 'farm' in line['plain'], lane.name + ': /homes lists the imported homes', line['plain'])
    lane.fixture('tp PlayerBot 0.5 -61 0.5 0 0')
    window(.5)
    player.chat(player.cmd('home base'), 'Teleported', 10)
    at = lane.fixture('where PlayerBot')
    require(at['x'] == 20.5 and at['z'] == 20.5, lane.name + ': /home base lands on the imported home', at)
    # Imported homes are far from players: their chunks are on disk, not loaded.
    lane.fixture('chunk 62 62')
    chunk = wait(lambda: (lambda c: c if not c['loaded'] else None)(lane.fixture('chunk 62 62')), 'far chunk unloaded', 60)
    require(chunk['generated'] and not chunk['loaded'], lane.name + ': the far home\'s chunk exists on disk and is not loaded', chunk)
    lane.fixture('tp PlayerBot 0.5 -61 0.5 0 0')
    window(.5)
    player.chat(player.cmd('home far'), 'Teleported', 12)
    at = lane.fixture('where PlayerBot')
    require(at['x'] == 1000.5 and at['z'] == 1000.5, lane.name + ': /home to an unloaded chunk loads it from disk and lands', at)
    player.chat(player.cmd('home void'), 'terrain this world never generated', 12)
    require(lane.fixture('where PlayerBot')['x'] == 1000.5, lane.name + ': a home in never-generated terrain is refused with that reason; nobody moves')
    lane.perm('StaffBot', 'allium.warps.spawn')
    lane.fixture('tp StaffBot 8.5 -61 8.5 0 0')
    window(.5)
    staff.chat(staff.cmd('warp spawn'), 'Teleported', 10)
    at = lane.fixture('where StaffBot')
    require(at['x'] == 0.5 and at['z'] == 0.5, lane.name + ': /warp spawn lands on the imported warp', at)


def legacy_economy(lane):
    logs = lane.logs[lane.boot_mark:]
    require(any('economy.starting-balance is a number' in v for v in logs) and any('Journalled SQLite economy enabled' in v for v in logs)
            and not any('Failed to enable module [economy]' in v for v in logs),
            lane.name + ': an unquoted legacy starting balance is read (with a warning) and the economy enables',
            [v for v in logs if 'economy' in v.lower()][:6])
    player = lane.bots['PlayerBot']
    line = player.command('balance', 'Balance:')
    require('100' in line['plain'], lane.name + ': /balance answers with the starting balance', line['plain'])


def import_main():
    build_helper()
    lane = Lane(A.suite, PORT)
    source, driver = build_legacy_db(lane)
    # A config carried over from legacy Allium: an unquoted starting balance used to disable the
    # whole economy ("economy unavailable"), so /balance and priced signs did nothing.
    cfg_path = lane.path / 'plugins/Allium/config.yml'
    cfg = yaml.safe_load(cfg_path.read_text())
    cfg['economy']['starting-balance'] = 100.0
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    try:
        lane.boot()
        setup(lane)
        case('legacy-config-economy', lambda: legacy_economy(lane))
        case('legacy-import', lambda: legacy_import(lane, source, driver))
    finally:
        try:
            lane.stop()
        except Exception as error:
            require(False, 'shutdown: ' + str(error))
        clean_logs(lane)
    manifest = dict(suite=A.suite, run=A.run_name, alliumSha256=A.sha256, sources=SOURCE_HASHES, passed=not failures,
                    checks=len(checks), failures=failures, cases=cases, seconds=round(time.time() - started, 1), results=checks)
    (RUN / 'manifest.json').write_text(json.dumps(manifest, indent=2, default=str))
    emit(f'RESULT {"PASS" if not failures else "FAIL"} checks={len(checks)} failures={len(failures)} manifest={RUN / "manifest.json"}')
    _sys.exit(0 if not failures else 1)


import_main()
