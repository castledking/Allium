#!/usr/bin/env python3
"""S03 player-preferences gate against real servers, real protocol clients and a real database.

The PrefGate fixture drives Allium's preference sessions from the console (the commands that will
do this in-game belong to S07/S08) and reports what they hold. Every database check reads the row
independently: Python's sqlite3 on the lane's allium.db, or the mariadb client inside the
disposable allium-s03-mariadb container. Disposable lanes only: server binaries and library
caches are copied, worlds and player data are fresh. Exits nonzero on any failed check.
"""
import argparse, hashlib, json, os, re, shutil, socket, sqlite3, subprocess, sys, threading, time, uuid as uuidlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CLIENTS = REPO / 'tests/live/items'
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': '1.20.1',
                      'server': Path('/tmp/allium-server-cache/spigot-1.20.1.jar')},
    'paper-26.3': {'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': 'raw26',
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane')},
}
PORTS = [25680, 25681]
MARIADB = {'container': 'allium-s03-mariadb', 'port': 33306, 'database': 'allium_s03', 'user': 'allium',
           'password': Path('/tmp/allium-s03-mariadb.pw')}
FAULTS = '-Dallium.fault-injection=true'
BOT = 'PrefBot'
SLOW_MS = 6000
COLUMNS = ['acceptMessages', 'acceptPayments', 'acceptTeleportRequests', 'messageSoundVolume', 'messageSoundRestore', 'version']

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--backend', choices=['sqlite', 'mysql'], required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s03-20261107/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s03-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s03-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
mysql = a.backend == 'mysql'
password = MARIADB['password'].read_text().strip() if mysql else None


def offline_uuid(name):
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuidlib.UUID(bytes=bytes(digest)))


UUID = offline_uuid(BOT)
driver, failures, evidence = [], [], {}
servers, bots = [], []


def emit(message):
    driver.append(message)
    print(message, flush=True)


def require(ok, label):
    if not ok:
        failures.append(label)
    emit(('PASS ' if ok else 'FAIL ') + label)
    return ok


def wait(fn, label, timeout=60, server=None):
    end = time.monotonic() + timeout
    while True:
        value = fn()
        if value:
            return value
        if server is not None and server.proc is not None and server.proc.poll() is not None:
            raise RuntimeError(f'{server.name} exited {server.proc.returncode} waiting for {label}')
        if time.monotonic() > end:
            raise RuntimeError('timeout ' + label)
        time.sleep(0.05)


def pump(proc, path, sink):
    with path.open('a') as f:
        for line in proc.stdout:
            f.write(line)
            f.flush()
            sink.append(line)


class Server:
    def __init__(self, name, port):
        self.name, self.port = name, port
        self.dir = run / name
        self.logs = []
        self.proc = None
        self.log_thread = None
        # Log lines from here on belong to a deliberate database outage and are not error-checked.
        self.quiet_from = None

    def write(self):
        lane = self.dir
        lane.mkdir()
        if a.lane == 'paper-26.3':
            for name in ['libraries', 'versions']:
                shutil.copytree(spec['template'] / name, lane / name)
        else:
            shutil.copy2(spec['server'], lane / 'server.jar')
        plugins = lane / 'plugins'
        (plugins / 'Allium').mkdir(parents=True)
        shutil.copy2(a.allium_jar, plugins / 'Allium.jar')
        shutil.copy2(run / 'fixture/PrefGate.jar', plugins / 'PrefGate.jar')
        config = (REPO / 'src/main/resources/config.yml').read_text()
        if mysql:
            for old, new in [('  type: SQLITE\n', '  type: MYSQL\n'), ('    host: localhost\n', '    host: 127.0.0.1\n'),
                             ('    port: 3306\n', f'    port: {MARIADB["port"]}\n'),
                             ('    database: allium\n', f'    database: {MARIADB["database"]}\n'),
                             ('    username: root\n', f'    username: {MARIADB["user"]}\n'),
                             ('    password: ""\n', f'    password: "{password}"\n'),
                             ('      maximum-pool-size: 10\n', '      maximum-pool-size: 4\n'),
                             ('      minimum-idle: 2\n', '      minimum-idle: 1\n')]:
                if old not in config:
                    raise RuntimeError('config injection failed: ' + old.strip())
                config = config.replace(old, new)
        # Retry timings for the gate: 1.5 s, then 3 s, then 3 s (three retries).
        for old, new in [('    first-delay-millis: 1000\n', '    first-delay-millis: 1500\n'),
                         ('    max-delay-millis: 15000\n', '    max-delay-millis: 3000\n'),
                         ('    retries: 4\n', '    retries: 3\n')]:
            if old not in config:
                raise RuntimeError('retry config injection failed: ' + old.strip())
            config = config.replace(old, new)
        (plugins / 'Allium/config.yml').write_text(config)
        (lane / 'eula.txt').write_text('eula=true\n')
        (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={self.port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
level-name=world
level-seed=6031107
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
''')
        # connection-throttle 0: the gate reconnects within seconds on purpose.
        (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')

    def set_line(self, old, new):
        path = self.dir / 'plugins/Allium/config.yml'
        text = path.read_text()
        if old not in text:
            raise RuntimeError('config line not found: ' + old.strip())
        path.write_text(text.replace(old, new, 1))

    def set_default(self, key, value):
        path = self.dir / 'plugins/Allium/config.yml'
        text = path.read_text()
        old = f'    {key}: true\n'
        if old not in text:
            raise RuntimeError('default not found: ' + key)
        path.write_text(text.replace(old, f'    {key}: {str(value).lower()}\n', 1))

    def argv(self):
        if a.lane == 'paper-26.3':
            libs = {}

            def version(path):
                return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
            for jar in (self.dir / 'libraries').rglob('*.jar'):
                artifact = jar.parent.parent
                if artifact not in libs or version(jar) > version(libs[artifact]):
                    libs[artifact] = jar
            cp = ':'.join(str(x.relative_to(self.dir)) for x in [self.dir / 'versions/26.3/paper-26.3.jar', *libs.values()])
            return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                    FAULTS, '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
        return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                FAULTS, '-jar', 'server.jar', '--nogui']

    def boot(self, label):
        emit(f'BOOT {self.name} {label}')
        start = len(self.logs)
        self.proc = subprocess.Popen(self.argv(), cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.log_thread = threading.Thread(target=pump, args=(self.proc, run / f'server-{self.name}.log', self.logs), daemon=True)
        self.log_thread.start()
        wait(lambda: any('Done (' in x for x in self.logs[start:]), self.name + ' Done', 300, self)
        require(any('S03G-GATE READY' in x and 'module=true' in x for x in self.logs[start:]), f'{self.name} {label}: fixture and preferences module enabled')
        line = next((x for x in self.logs[start:] if 'Preferences enabled:' in x), '')
        m = re.search(r'Preferences enabled: (\w+) schema v(\d+), (\d+) storage lane', line)
        expect = ('MYSQL', '2', '4') if mysql else ('SQLITE', '2', '1')
        require(m is not None and m.groups() == expect,
                f'{self.name} {label}: {expect[0]} storage at schema v2 with {expect[2]} lane(s): {line.strip()[-120:]}')
        return start

    def console(self, text):
        self.proc.stdin.write(text + '\n')
        self.proc.stdin.flush()

    def ack(self, text):
        """Sends a fixture command and returns once the fixture acknowledged it (or failed at once)."""
        start = len(self.logs)
        self.console('pg ' + text)
        wait(lambda: any(f'S03G-ACK {text}' in x or f'S03G-DONE {text} => ' in x or f'S03G-FAIL {text} => ' in x
                         for x in self.logs[start:]), self.name + ' ack ' + text, 30, self)
        return start

    def result(self, text, start, timeout=30):
        """('DONE', json) or ('FAIL', message) for the first outcome of `text` logged after `start`."""
        def find():
            for x in self.logs[start:]:
                for kind in ('DONE', 'FAIL'):
                    marker = f'S03G-{kind} {text} => '
                    if marker in x:
                        payload = x.split(marker, 1)[1].strip()
                        return kind, (json.loads(payload) if kind == 'DONE' else payload)
            return None
        return wait(find, self.name + ' result ' + text, timeout, self)

    def pg(self, text, timeout=30, expect_fail=False):
        kind, payload = self.result(text, self.ack(text), timeout)
        if (kind == 'FAIL') != expect_fail:
            raise RuntimeError(f'{self.name} pg {text}: unexpected {kind} {payload}')
        return payload

    def view(self):
        return self.pg('view ' + BOT)

    def stop(self):
        if self.proc is not None and self.proc.poll() is None:
            stop_process(self.proc, lambda: self.console('stop'), timeout=60)
        self.proc = None


class Bot:
    def __init__(self, server):
        self.server = server
        self.proc = None
        self.logs = []

    def connect(self, label):
        s = self.server
        script = 'client-raw26.cjs' if spec['client'] == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(CLIENTS / script), str(s.dir), str(s.port)] + ([] if spec['client'] == 'raw26' else [spec['client']]) + [BOT]
        env = dict(os.environ, S04G_BUNGEE='0', S04G_WIRE_IDS=str(s.dir / 'plugins/PrefGate/wire-ids.json'))
        start = len(self.logs)
        self.proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, bufsize=1, env=env)
        threading.Thread(target=pump, args=(self.proc, run / f'client-{s.name}.log', self.logs), daemon=True).start()
        wait(lambda: any('S04G-WIRE READY' in x for x in self.logs[start:]), f'{s.name} {label} client READY', 90, s)
        wait(lambda: s.view()['online'], f'{s.name} {label} bot online', 30, s)

    def connect_denied(self, label):
        """Starts a client whose login is expected to be refused; returns its log once it exits."""
        s = self.server
        script = 'client-raw26.cjs' if spec['client'] == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(CLIENTS / script), str(s.dir), str(s.port)] + ([] if spec['client'] == 'raw26' else [spec['client']]) + [BOT]
        env = dict(os.environ, S04G_BUNGEE='0', S04G_WIRE_IDS=str(s.dir / 'plugins/PrefGate/wire-ids.json'))
        start = len(self.logs)
        proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1, env=env)
        thread = threading.Thread(target=pump, args=(proc, run / f'client-{s.name}.log', self.logs), daemon=True)
        thread.start()
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()
            raise RuntimeError(label + ': the client was not refused')
        thread.join(timeout=5)
        return self.logs[start:]

    def disconnect(self):
        proc, self.proc = self.proc, None
        stop_process(proc, lambda: (proc.stdin.write('stop\n'), proc.stdin.flush()), timeout=15)
        wait(lambda: not self.server.view()['online'], self.server.name + ' bot offline', 30, self.server)


def db_row():
    """The stored row, read independently of Allium; None when the player has no row."""
    query = ('SELECT accept_messages, accept_payments, accept_teleport_requests, message_sound_volume, '
             'message_sound_restore, version, updated_at FROM allium_preferences WHERE uuid = ')
    if mysql:
        out = subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD', MARIADB['container'], 'mariadb', '-u', MARIADB['user'],
                              '-B', '-N', '-e', query + f"'{UUID}'", MARIADB['database']],
                             env=dict(os.environ, MYSQL_PWD=password), capture_output=True, text=True, check=True).stdout.strip()
        row = None if not out else [None if v == 'NULL' else v for v in out.split('\t')]
    else:
        con = sqlite3.connect(str(servers[0].dir / 'plugins/Allium/allium.db'), timeout=10)
        try:
            row = con.execute(query + '?', (UUID,)).fetchone()
        finally:
            con.close()
    if row is None:
        return None
    flag = lambda v: None if v is None else bool(int(v))
    number = lambda v: None if v is None else round(float(v), 6)
    return {'acceptMessages': flag(row[0]), 'acceptPayments': flag(row[1]), 'acceptTeleportRequests': flag(row[2]),
            'messageSoundVolume': number(row[3]), 'messageSoundRestore': number(row[4]), 'version': int(row[5]),
            'updatedAt': int(row[6])}


def fields(row):
    return None if row is None else {k: (round(row[k], 6) if isinstance(row[k], float) else row[k]) for k in COLUMNS}


def reset_mariadb():
    """Empties the disposable gate database (every table Allium created there, foreign keys included)."""
    sql = lambda q: subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD', MARIADB['container'], 'mariadb', '-u', MARIADB['user'],
                                    '-B', '-N', '-e', q, MARIADB['database']],
                                   env=dict(os.environ, MYSQL_PWD=password), check=True, capture_output=True, text=True).stdout
    tables = sql('SHOW TABLES').split()
    if any(not re.fullmatch(r'allium_[a-z_]+', t) for t in tables):
        raise RuntimeError(f'unexpected tables in the gate database: {tables}')
    if tables:
        sql('SET FOREIGN_KEY_CHECKS = 0; DROP TABLE ' + ', '.join(tables) + '; SET FOREIGN_KEY_CHECKS = 1')
    emit(f'MARIADB reset: dropped {tables}')


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/PrefGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'PrefGate.jar'), '-C', str(classes), '.'], check=True)


DEFAULT_EFFECTIVE = {'acceptMessages': True, 'acceptPayments': True, 'acceptTeleportRequests': True,
                     'messageSoundVolume': 1.0, 'volumeOverridden': False, 'socialSpy': False}


def round_trip(A, bot):
    bot.connect('first join')
    joins = A.pg('joins')
    require(joins and joins[-1]['state'] == 'READY', f'a normal join is READY at join (the pre-login wait covered the load): {joins[-1:]}')
    v = A.view()
    require(v['state'] == 'READY' and v['stored']['version'] == 0 and v['effective'] == DEFAULT_EFFECTIVE,
            f'a new player inherits the configured defaults: {v["effective"]}')
    require(db_row() is None, 'joining wrote no row: defaults are never stored')

    row = A.pg(f'set {BOT} accept-messages false')
    require(row['version'] == 1 and row['acceptMessages'] is False and row['acceptPayments'] is None,
            f'accept-messages false is stored alone (version {row["version"]})')
    row = A.pg(f'volume {BOT} 0.4')
    require(row['version'] == 2 and row['messageSoundVolume'] == 0.4, 'message-sound-volume 0.4 is stored')
    for bad in ['NaN', 'Infinity', '1.5', '-0.1']:
        message = A.pg(f'volume {BOT} {bad}', expect_fail=True)
        require('IllegalArgumentException' in message, f'volume {bad} is rejected: {message[-70:]}')
    require(db_row()['version'] == 2, 'rejected volumes wrote nothing')
    t = A.pg(f'toggle {BOT}')
    stored = t['view']['stored']
    require(t['volume'] == 0.0 and t['outcome'] == 'MUTED' and stored['messageSoundVolume'] == 0.0
            and stored['messageSoundRestore'] == 0.4 and stored['version'] == 3,
            f'toggle from 0.4 mutes and remembers 0.4 as the restoration state: {fields(stored)}')
    db = db_row()
    expected = {'acceptMessages': False, 'acceptPayments': None, 'acceptTeleportRequests': None,
                'messageSoundVolume': 0.0, 'messageSoundRestore': 0.4, 'version': 3}
    require(fields(db) == expected, f'{a.backend} round trip: the stored row matches: {fields(db)}')
    evidence['roundTrip'] = {'row': db}
    bot.disconnect()
    bot.connect('rejoin')
    v = A.view()
    require(v['state'] == 'READY' and fields(v['stored']) == expected and db_row()['version'] == 3,
            'quit and rejoin load the same row and write nothing')


def restart(A, bot):
    before = db_row()
    bot.disconnect()
    A.stop()
    A.set_default('accept-payments', False)
    A.boot('restart (accept-payments default now false)')
    bot.connect('after restart')
    v = A.view()
    eff = v['effective']
    require(v['state'] == 'READY' and fields(v['stored']) == fields(before),
            f'after a restart the session holds the stored row, restoration state included: {fields(v["stored"])}')
    require(eff['acceptPayments'] is False and v['stored']['acceptPayments'] is None and eff['acceptMessages'] is False
            and eff['messageSoundVolume'] == 0.0,
            f'an unset field follows the changed default (accept-payments false) while stored values stay: {eff}')
    require(db_row() == before, 'the restart and the join wrote nothing (accept-payments is still unset in the database)')

    t = A.pg(f'toggle {BOT}')
    s = t['view']['stored']
    require(t['volume'] == 0.4 and t['outcome'] == 'RESTORED' and s['messageSoundVolume'] == 0.4 and s['messageSoundRestore'] is None,
            f'toggle after the restart restores 0.4 from the persisted restoration state: {fields(s)}')
    row = A.pg(f'reset {BOT}')
    v = A.view()
    require(row['messageSoundVolume'] is None and row['messageSoundRestore'] is None
            and v['effective']['messageSoundVolume'] == 1.0 and not v['effective']['volumeOverridden'],
            'reset removes the override and the restoration state; the configured 1.0 applies')
    t = A.pg(f'toggle {BOT}')
    require(t['volume'] == 0.0 and t['outcome'] == 'MUTED' and t['view']['stored']['messageSoundRestore'] is None,
            'toggle with no override mutes and stores no restoration value')
    t = A.pg(f'toggle {BOT}')
    s = t['view']['stored']
    require(t['volume'] == 1.0 and t['outcome'] == 'FALLBACK' and not t['stillMuted'] and s['messageSoundVolume'] is None
            and s['messageSoundRestore'] is None,
            'toggle back with nothing to restore returns to the configured fallback (1.0, not still muted)')
    A.pg(f'volume {BOT} 0.3')
    db = db_row()
    require(fields(db) == {'acceptMessages': False, 'acceptPayments': None, 'acceptTeleportRequests': None,
                           'messageSoundVolume': 0.3, 'messageSoundRestore': None, 'version': 8},
            f'the row after the restart steps: {fields(db)}')


def slow_reconnect(A, bot):
    before = db_row()
    stats0 = A.pg('stats')
    bot.disconnect()
    A.pg(f'fault preferences.load-slow 2')
    t0 = time.monotonic()
    bot.connect('slow join')
    joined = time.monotonic() - t0
    first = A.pg('joins')[-1]
    require(first['state'] == 'LOADING', f'with a {SLOW_MS} ms load the player joins after the 2 s pre-login wait still LOADING ({joined:.1f} s)')
    v = A.view()
    require(v['state'] == 'LOADING' and v['effective'] is None and v['stored'] is None,
            'while LOADING nothing is enforced: no effective preferences, no defaults')
    message = A.pg(f'toggle {BOT}', expect_fail=True)
    require('LOADING' in message, f'a toggle while LOADING is refused: {message[-60:]}')
    bot.disconnect()
    reconnect_at = time.monotonic() - t0
    bot.connect('reconnect during the slow load')
    second = A.pg('joins')[-1]
    require(reconnect_at < SLOW_MS / 1000.0 and second['state'] == 'LOADING' and second['generation'] > first['generation'],
            f'reconnected {reconnect_at:.1f} s into the first {SLOW_MS / 1000:.0f} s load; the new session is LOADING '
            f'(generation {first["generation"]} -> {second["generation"]})')
    text = f'set {BOT} accept-teleport-requests false'
    start = A.ack(text)
    v = A.view()
    require(v['state'] == 'LOADING', 'a change made during LOADING is accepted while the session is still LOADING')
    kind, row = A.result(text, start, timeout=30)
    require(kind == 'DONE' and row['version'] == before['version'] + 1, f'the change was saved once (version {row.get("version") if kind == "DONE" else row})')
    v = wait(lambda: (lambda x: x if x['state'] == 'READY' else None)(A.view()), 'session READY', 30, A)
    expected = dict(fields(before), acceptTeleportRequests=False, version=before['version'] + 1)
    require(v['generation'] == second['generation'] and fields(v['stored']) == expected,
            f'the reconnected session is READY with the real row plus the change, not defaults: {fields(v["stored"])}')
    stats = A.pg('stats')
    stale = stats['staleLoadsDiscarded'] - stats0['staleLoadsDiscarded']
    require(stale == 1 and stats['loadFailures'] == stats0['loadFailures'],
            f'the first, slow load finished after its session was replaced and was discarded (stale +{stale})')
    tripped = A.pg('tripped preferences.load-slow')['tripped']
    db = db_row()
    require(tripped == 2 and fields(db) == expected,
            f'both loads were slow ({tripped}); the database changed only by the explicit patch: {fields(db)}')
    evidence['slowReconnect'] = {'joinedSeconds': round(joined, 2), 'reconnectSeconds': round(reconnect_at, 2),
                                 'firstJoin': first, 'secondJoin': second, 'statsBefore': stats0, 'statsAfter': stats,
                                 'rowBefore': before, 'rowAfter': db}


def load_retries(A, bot):
    """Failed loads: retried in the background with backoff (1.5 s, 3 s, 3 s), capped, never defaults."""
    before = db_row()
    stats0 = A.pg('stats')
    accept = lambda: A.pg(f'accept {BOT} accept-messages')

    bot.disconnect()
    A.pg('fault preferences.load-fail 1')
    start = len(A.logs)
    bot.connect('join through a one-load blip')
    join = A.pg('joins')[-1]
    v = A.view()
    require(join['state'] == 'READY' and v['loadAttempts'] == 2 and fields(v['stored']) == fields(before)
            and any(f'Loaded preferences for {UUID} on attempt 2' in x for x in A.logs[start:]),
            'a load that failed once was retried 1.5 s later inside the 2 s pre-login wait: READY at join with the real row')

    bot.disconnect()
    A.pg('fault preferences.load-fail 2')
    t0 = time.monotonic()
    bot.connect('join during a longer outage')
    join = A.pg('joins')[-1]
    v = A.view()
    a = accept()
    require(join['state'] == 'FAILED' and v['state'] == 'FAILED' and v['effective'] is None,
            f'two failed loads: the player joins with preferences FAILED and nothing effective ({time.monotonic() - t0:.1f} s)')
    require(a['acceptance'] == 'UNAVAILABLE' and 'Try again shortly' in a.get('message', ''),
            f'fail closed: messages to them are refused as unavailable, with "{a.get("message", "")[-60:]}"')
    message = A.pg(f'toggle {BOT}', expect_fail=True)
    require('FAILED' in message, 'a toggle on a FAILED session is refused')
    v = wait(lambda: (lambda x: x if x['state'] == 'READY' else None)(A.view()), 'background recovery', 15, A)
    recovered = time.monotonic() - t0
    require(v['loadAttempts'] == 3 and fields(v['stored']) == fields(before) and accept()['acceptance'] == 'REFUSES',
            f'the third attempt, in the background {recovered:.1f} s after joining began, loaded the real row '
            '(accept-messages false: REFUSES, not the default)')

    bot.disconnect()
    A.pg('fault preferences.load-fail 10')
    start = len(A.logs)
    t0 = time.monotonic()
    bot.connect('join during an outage that outlasts the retries')
    gave_up = wait(lambda: next((x.strip() for x in A.logs[start:] if f'Gave up loading preferences for {UUID}' in x), None),
                   'give up', 20, A)
    elapsed = time.monotonic() - t0
    time.sleep(4.0)
    tripped = A.pg('tripped preferences.load-fail')['tripped']
    v = A.view()
    require(v['state'] == 'FAILED' and v['loadAttempts'] == 4 and tripped == 4 and accept()['acceptance'] == 'UNAVAILABLE'
            and 'after 4 attempts' in gave_up,
            f'retries are capped: 4 attempts in {elapsed:.1f} s, none in the next 4 s; still FAILED and unavailable')
    A.pg('fault preferences.load-fail 0')
    bot.disconnect()
    bot.connect('rejoin after the outage')
    v = A.view()
    stats = A.pg('stats')
    require(v['state'] == 'READY' and fields(v['stored']) == fields(before) and db_row() == before,
            'the next join loads the real row, and none of the failures wrote anything')
    evidence['loadRetries'] = {'statsBefore': stats0, 'statsAfter': stats, 'recoveredSeconds': round(recovered, 2),
                               'gaveUp': gave_up[-160:], 'gaveUpSeconds': round(elapsed, 2)}


def denied_login(A, bot):
    """Another plugin denies the login before Allium's MONITOR listener: no session, no load."""
    bot.disconnect()
    stats0 = A.pg('stats')
    A.pg('deny-next')
    start = len(A.logs)
    client = bot.connect_denied('a login another plugin denies')
    stats = A.pg('stats')
    v = A.view()
    denied = any('S03G-DENIED ' + BOT in x for x in A.logs[start:])
    shown = any('another plugin denied this login' in x for x in client + A.logs[start:])
    require(denied and shown and not any('S04G-WIRE READY' in x for x in client),
            'the fixture denied the login at pre-login (HIGH) and the client was refused with its reason')
    require(stats['sessionsBegun'] == stats0['sessionsBegun'] and stats['loads'] == stats0['loads'] and v['state'] == 'NONE',
            f'Allium began no session and ran no load for the denied login (sessions {stats0["sessionsBegun"]} -> {stats["sessionsBegun"]})')
    evidence['deniedLogin'] = {'priority': 'HIGH', 'statsBefore': stats0, 'statsAfter': stats,
                               'stateAfter': v['state'], 'clientRefused': denied and shown,
                               'clientReady': any('S04G-WIRE READY' in x for x in client)}
    bot.connect('join after the denial')
    require(A.view()['state'] == 'READY', 'the next, allowed login loads normally')


def concurrent(A, bot):
    before = db_row()
    count = 100
    if mysql:
        B = Server('b', PORTS[1])
        servers.append(B)
        B.write()
        B.boot('second server on the same MariaDB')
        bot_b = Bot(B)
        bots.append(bot_b)
        bot_b.connect('second session for the same player')
        v = B.view()
        require(v['state'] == 'READY' and fields(v['stored']) == fields(before),
                'a second server holds its own READY session for the same UUID with the same row')
        ta, tb = f'burst {BOT} A {count}', f'burst {BOT} B {count}'
        sa, sb = len(A.logs), len(B.logs)
        A.console('pg ' + ta)
        B.console('pg ' + tb)
        ra = A.result(ta, sa, 60)
        rb = B.result(tb, sb, 60)
        label = 'two servers, one session each'
    else:
        B = None
        pair = f'burst-pair {BOT} {count}'
        ta, tb = pair + ' A', pair + ' B'
        start = A.ack(pair)
        ra = A.result(ta, start, 60)
        rb = A.result(tb, start, 60)
        label = "Allium's session and an independent store on its own connection to the same file"
    require(ra[0] == 'DONE' and rb[0] == 'DONE' and ra[1]['ok'] == count and rb[1]['ok'] == count
            and ra[1]['failed'] == 0 and rb[1]['failed'] == 0,
            f'{label}: {count} + {count} concurrent writes all succeeded')
    db = db_row()
    require(db['acceptMessages'] is True and db['acceptPayments'] is False and db['acceptTeleportRequests'] is False
            and db['version'] == before['version'] + 2 * count
            and db['messageSoundVolume'] in (round(0.001 * count, 3), round(0.5 + 0.001 * count, 3)),
            f'no lost update: each writer kept its own field, the shared volume holds one writer\'s last value '
            f'({db["messageSoundVolume"]}) and the version rose by exactly {2 * count} ({before["version"]} -> {db["version"]})')
    versions = sorted([ra[1]['last']['version'], rb[1]['last']['version']])
    interleaved = versions != [before['version'] + count, before['version'] + 2 * count]
    require(interleaved, f'the two writers overlapped: their last writes landed at versions {versions}, '
                         f'not one after the other ({before["version"] + count}, {before["version"] + 2 * count})')
    evidence['concurrent'] = {'mode': label, 'count': count, 'rowBefore': before, 'rowAfter': db,
                              'lastA': ra[1]['last'], 'lastB': rb[1]['last'], 'interleaved': interleaved}
    bot.disconnect()
    bot.connect('rejoin after the concurrent writes')
    require(fields(A.view()['stored']) == fields(db), 'after rejoining, the session matches the database')
    if B is not None:
        bot_b.disconnect()
        bot_b.connect('rejoin on the second server')
        require(fields(B.view()['stored']) == fields(db), 'the second server, after rejoining, matches the database too')
        bot_b.disconnect()
        bots.remove(bot_b)
        B.stop()


def drain_on_stop(A, bot):
    """/stop with a slow load holding the player's storage lane and 400 writes queued behind it."""
    bot.disconnect()
    before = db_row()
    A.pg('fault preferences.load-slow 1')
    bot.connect('join with a slow load before /stop')
    count = 400
    text = f'burst {BOT} A {count}'
    start = len(A.logs)
    # Both lines reach the console together: the writes queue behind the load, then the server stops.
    A.console('pg ' + text + '\nstop')
    A.proc.wait(timeout=90)
    code = A.proc.returncode
    A.proc = None
    # The server kicks the bot on shutdown; its exit status is not part of the check.
    try:
        bot.proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        bot.proc.kill()
    bot.proc = None
    draining = next((x.strip() for x in A.logs[start:] if 'Saving preferences: finishing' in x), None)
    timed_out = any('did not finish within' in x for x in A.logs[start:])
    db = db_row()
    queued = int(re.search(r'finishing (\d+) queued', draining).group(1)) if draining else 0
    require(code == 0 and not timed_out and queued >= count and db['version'] == before['version'] + count
            and db['acceptMessages'] is True and db['messageSoundVolume'] == round(0.001 * count, 3),
            f'/stop during a slow load with {count} writes behind it: {queued} storage tasks were queued when Allium '
            f'disabled, and all {count} writes were saved before the pool closed (version {before["version"]} -> {db["version"]})')
    evidence['drainOnStop'] = {'count': count, 'queuedAtDisable': queued, 'rowBefore': before, 'rowAfter': db}


def expected_writes(count):
    """Writer A's patches, as the fixture builds them."""
    return [{'accept-messages': i % 2 == 1, 'message-sound-volume': round((i + 1) / 1000.0, 3)} for i in range(count)]


def parse_write(description):
    uuid, body = description.split(' ', 1)
    out = {}
    for part in body.strip('{}').split(', '):
        key, value = part.split('=', 1)
        out[key] = None if value == 'unset' else (value == 'true' if value in ('true', 'false') else round(float(value), 3))
    return uuid, out


def unreachable_at_stop(A, bot):
    """/stop while the database is unreachable: the 3 s timeout fires, every unsaved write is named, shutdown continues."""
    A.set_line('    shutdown-timeout-millis: 10000\n', '    shutdown-timeout-millis: 3000\n')
    A.boot('restart with a 3 s shutdown timeout')
    bot.connect('join before the database goes away')
    before = db_row()
    A.quiet_from = len(A.logs)
    count = 20
    lock = None
    if mysql:
        subprocess.run(['docker', 'pause', MARIADB['container']], check=True, capture_output=True)
        how = 'MariaDB paused (docker pause)'
    else:
        lock = sqlite3.connect(str(A.dir / 'plugins/Allium/allium.db'), isolation_level=None, timeout=10)
        lock.execute('BEGIN EXCLUSIVE')
        how = 'another process holds the SQLite write lock'
    try:
        start = len(A.logs)
        t0 = time.monotonic()
        A.console(f'pg burst {BOT} A {count}\nstop')
        A.proc.wait(timeout=120)
        stopped = time.monotonic() - t0
        code = A.proc.returncode
        A.proc = None
        A.log_thread.join(timeout=5)
        # Capture the cutoff before restoring either database. Shutdown must finish while the
        # outage persists, including when the database never returns during the server's life.
        if A.log_thread.is_alive():
            raise RuntimeError('server log pump did not finish after shutdown')
        lines = list(A.logs[start:])
    finally:
        if mysql:
            subprocess.run(['docker', 'unpause', MARIADB['container']], check=True, capture_output=True)
        else:
            lock.rollback()
            lock.close()
    try:
        bot.proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        bot.proc.kill()
    bot.proc = None
    header = next((x.strip() for x in lines if 'Preference storage did not finish within 3000 ms' in x), None)
    lost = [x.split('not saved: ', 1)[1].strip() for x in lines if 'not saved: ' in x and 'preference write' not in x]
    unknown = [x.split('outcome unknown: ', 1)[1].strip() for x in lines if 'outcome unknown: ' in x]
    db = wait(db_row, 'database reachable again', 30)
    saved = db['version'] - before['version']
    patches = expected_writes(count)
    listed = [parse_write(d) for d in unknown + lost]
    # The named writes are exactly the last ones submitted, in order; the database holds the rest
    # (plus, at most, the one that was in progress).
    exact = (len(lost) == 19 and len(unknown) == 1 and all(u == UUID for u, _ in listed)
             and [w for _, w in listed] == patches and saved == 0
             and header is not None and '19 preference write(s) were not saved, and 1 may or may not' in header)
    require(header is not None and code == 0 and stopped < 60,
            f'{how}: shutdown waited the 3 s timeout, logged it and exited normally in {stopped:.1f} s')
    require(exact, f'the log names exactly the writes that were not saved: {len(lost)} not saved, {len(unknown)} in progress, '
                   f'and the database holds the other {saved} of {count}')
    evidence['unreachableAtStop'] = {'how': how, 'stopSeconds': round(stopped, 1), 'header': header, 'notSaved': len(lost),
                                     'inProgress': unknown, 'notSavedWrites': lost, 'saved': saved, 'rowBefore': before,
                                     'rowAfter': db, 'databaseUnavailableUntilExit': True, 'serverExitCode': code}


summary = {}
try:
    for port in PORTS:
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', port))
    if mysql:
        reset_mariadb()
    build_fixture()
    A = Server('a', PORTS[0])
    servers.append(A)
    A.write()
    A.boot('initial')
    bot = Bot(A)
    bots.append(bot)
    round_trip(A, bot)
    restart(A, bot)
    slow_reconnect(A, bot)
    load_retries(A, bot)
    denied_login(A, bot)
    concurrent(A, bot)
    drain_on_stop(A, bot)
    unreachable_at_stop(A, bot)
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    for b in bots:
        if b.proc is not None:
            try:
                stop_process(b.proc, lambda: (b.proc.stdin.write('stop\n'), b.proc.stdin.flush()), timeout=15)
            except Exception as e:
                failures.append('client cleanup ' + repr(e))
    for s in servers:
        try:
            s.stop()
        except Exception as e:
            failures.append(f'{s.name} cleanup ' + repr(e))
    logs = [x for s in servers for x in s.logs[:s.quiet_from]]
    expected = lambda x: 'S03G' in x or 'fault injection' in x
    allium_errors = [x.strip() for x in logs if not expected(x)
                     and ('Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x)))]
    require(not allium_errors, 'no listener or Allium exceptions in the server logs: ' + json.dumps(allium_errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    hashes = {}
    if servers and (servers[0].dir / 'plugins').exists():
        hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (servers[0].dir / 'plugins').glob('*.jar')}
    summary = {'lane': a.lane, 'backend': a.backend, 'ports': PORTS if mysql else PORTS[:1], 'server': version,
               'client': spec['client'], 'uuid': UUID, 'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))),
               'failures': failures, 'hashes': hashes, 'evidence': evidence,
               'testSources': {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [HERE / 'run.py', HERE / 'plugin/gate/PrefGate.java']}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    for s in servers:
        for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
            target = s.dir / name
            if target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            elif target.exists():
                target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'backend', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
