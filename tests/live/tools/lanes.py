"""Shared disposable-lane plumbing for the S10 tool gates (Phantom Obliterator, Lazy Axe).

The same lanes, ports (25650-25652), fixture (DisarmerGate) and protocol clients as the Mob
Disarmer gate. Never reads production data: only server and plugin binaries, immutable library
caches and the retained provider settings from the passing S05 coexist lane are copied. A fresh
world, player data, database and keyring every run.
"""
import hashlib, json, re, shutil, socket, subprocess, sys, threading, time, zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[2]
DISARMER = TOOLS / 'disarmer'
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

COEXIST = Path('/mnt/storage/.allium-validation/20261106/supplied-lanes/runs/paper-1.21.8-coexist')
LANES = {
    'spigot-1.20.1': {'port': 25650, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': '1.20.1',
                      'server': Path('/tmp/allium-server-cache/spigot-1.20.1.jar'), 'providers': []},
    # Paper 1.21.8-60: the official paperclip (sha256 8de7c52c..., the build S05 used), seeded with the
    # cache, libraries and versions the passing S05 coexist lane kept. Paperclip puts only the
    # server's own libraries on the classpath; the plugins' downloaded libraries stay theirs.
    'paper-1.21.8': {'port': 25651, 'java': '/usr/lib/jvm/java-21-openjdk/bin/java', 'client': '1.21.8',
                     'server': Path('/tmp/allium-s10-20261112/artifacts/paper-1.21.8-60.jar'), 'seed': COEXIST,
                     'providers': ['nexo', 'craftengine', 'oraxen']},
    'paper-26.3': {'port': 25652, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': 'raw26',
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane'), 'version': '26.3', 'providers': []},
}
# The same provider binaries the S05 coexist lane passed with (CraftEngine 26.9.1).
PROVIDER_JARS = {
    'nexo': COEXIST / 'plugins/nexo.jar',
    'craftengine': COEXIST / 'plugins/craftengine.jar',
    'oraxen': COEXIST / 'plugins/oraxen.jar',
}
# SF's GriefPrevention3D 19.0.5 plugin binary (sha256 da2cf6f8...), copied alone: no data.
GRIEFPREVENTION = Path('/tmp/allium-s10-20261112/artifacts/GriefPrevention3D-19.0.5.jar')
FAULTS = '-Dallium.fault-injection=true'


class Gate:
    """One lane: its server, its bot client, the fixture's JSON results and the check log."""

    def __init__(self, lane_name, run, allium_jar, griefprevention=None):
        self.spec = LANES[lane_name]
        self.lane_name = lane_name
        self.run = run
        self.lane = run / 'lane'
        self.allium_jar = allium_jar
        self.griefprevention = griefprevention
        self.port = self.spec['port']
        self.providers = self.spec['providers']
        self.server = self.client = None
        self.threads = []
        self.logs, self.client_logs, self.driver, self.failures = [], [], [], []
        self.data = self.lane / 'plugins/DisarmerGate'

    # -- checks -------------------------------------------------------------------------------
    def emit(self, message):
        self.driver.append(message)
        print(message, flush=True)

    def require(self, ok, label):
        if not ok:
            self.failures.append(label)
        self.emit(('PASS ' if ok else 'FAIL ') + label)
        return ok

    def wait(self, fn, label, timeout=90):
        end = time.monotonic() + timeout
        while True:
            value = fn()
            if value:
                return value
            if self.server and self.server.poll() is not None:
                raise RuntimeError(f'server exited {self.server.returncode} waiting for {label}')
            if time.monotonic() > end:
                raise RuntimeError('timeout ' + label)
            time.sleep(0.05)

    # -- lane ---------------------------------------------------------------------------------
    def build_fixture(self):
        out = self.run / 'fixture'
        classes = out / 'classes'
        classes.mkdir(parents=True)
        api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
        gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
        subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{self.allium_jar}',
                        '-d', str(classes), *map(str, sorted((DISARMER / 'plugin/gate').glob('*.java')))], check=True)
        shutil.copy2(DISARMER / 'plugin/plugin.yml', classes / 'plugin.yml')
        subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'DisarmerGate.jar'), '-C', str(classes), '.'], check=True)

    def write_lane(self, tool_files, seed=7101109, providers_setup=None):
        """tool_files: {name: yaml text} written to plugins/Allium/tools/ before the first start."""
        lane, spec = self.lane, self.spec
        lane.mkdir(parents=True)
        if 'template' in spec:
            for name in ['libraries', 'versions']:
                shutil.copytree(spec['template'] / name, lane / name)
        elif 'seed' in spec:
            for name in ['cache', 'libraries', 'versions']:
                shutil.copytree(spec['seed'] / name, lane / name)
            shutil.copy2(spec['server'], lane / 'server.jar')
        else:
            shutil.copy2(spec['server'], lane / 'server.jar')
        plugins = lane / 'plugins'
        (plugins / 'Allium/tools').mkdir(parents=True)
        shutil.copy2(self.allium_jar, plugins / 'Allium.jar')
        shutil.copy2(self.run / 'fixture/DisarmerGate.jar', plugins / 'DisarmerGate.jar')
        shutil.copy2(REPO / 'src/main/resources/config.yml', plugins / 'Allium/config.yml')
        for name, text in tool_files.items():
            (plugins / 'Allium/tools' / name).write_text(text)
        for provider in self.providers:
            shutil.copy2(PROVIDER_JARS[provider], plugins / (provider + '.jar'))
        if self.griefprevention:
            shutil.copy2(self.griefprevention, plugins / self.griefprevention.name)
        if providers_setup:
            providers_setup(plugins)
        (lane / 'eula.txt').write_text('eula=true\n')
        (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={self.port}
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
enable-query=false
enable-rcon=false
spawn-protection=0
difficulty=easy
view-distance=2
simulation-distance=2
level-name=world
level-seed={seed}
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
network-compression-threshold=256
''')
        (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')

    def argv(self):
        spec, lane = self.spec, self.lane
        if 'template' in spec:
            libs = {}

            def version(path):
                return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
            for jar in (lane / 'libraries').rglob('*.jar'):
                artifact = jar.parent.parent
                if artifact not in libs or version(jar) > version(libs[artifact]):
                    libs[artifact] = jar
            v = spec['version']
            cp = ':'.join(str(x.relative_to(lane)) for x in [lane / f'versions/{v}/paper-{v}.jar', *libs.values()])
            return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                    FAULTS, '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
        return [spec['java'], '-Xms512M', '-Xmx2G', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                FAULTS, '-jar', 'server.jar', '--nogui']

    def check_port(self):
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('127.0.0.1', self.port))

    # -- processes ------------------------------------------------------------------------------
    def _pump(self, proc, path, sink):
        with path.open('a') as f:
            for line in proc.stdout:
                f.write(line)
                f.flush()
                sink.append(line)

    def boot(self, label, enabled_markers=()):
        self.emit('BOOT ' + label)
        start = len(self.logs)
        self.server = subprocess.Popen(self.argv(), cwd=self.lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, bufsize=1)
        t = threading.Thread(target=self._pump, args=(self.server, self.run / 'server.log', self.logs), daemon=True)
        t.start()
        self.threads.append(t)
        self.wait(lambda: any('Done (' in x for x in self.logs[start:]), 'server Done', 300)
        self.require(any('S06A-GATE READY' in x for x in self.logs[start:]), label + ' fixture enabled')
        for marker in enabled_markers:
            self.require(any(marker in x for x in self.logs[start:]), f'{label} log has "{marker}"')
        return start

    def connect(self, label):
        start = len(self.client_logs)
        script = 'client-raw26.cjs' if self.spec['client'] == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(DISARMER / script), str(self.lane), str(self.port)] + ([] if self.spec['client'] == 'raw26' else [self.spec['client']])
        self.client = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        t = threading.Thread(target=self._pump, args=(self.client, self.run / 'client.log', self.client_logs), daemon=True)
        t.start()
        self.threads.append(t)
        self.wait(lambda: any('S06A-WIRE READY' in x for x in self.client_logs[start:]), label + ' client READY', 90)
        self.wait(lambda: self.status()['botOnline'], 'bot online', 30)
        if self.providers:
            self.wait(lambda: all(self.status()[x] == 'READY' for x in self.providers), 'providers READY', 120)
        time.sleep(3.5)  # past the 1.21.4+ client-loaded grace period on every lane

    def cmd_raw(self, text):
        self.server.stdin.write(text + '\n')
        self.server.stdin.flush()

    def cmd(self, text, timeout=30):
        """A fixture command: waits for its DONE line and fails on FAIL."""
        start = len(self.logs)
        self.cmd_raw(text)
        if text.startswith('dg '):
            tail = text[3:]
            self.wait(lambda: any(('S06A-DONE ' + tail) in x or ('S06A-FAIL ' + tail) in x for x in self.logs[start:]), text, timeout)
            if any(('S06A-FAIL ' + tail) in x for x in self.logs[start:]):
                raise RuntimeError('fixture failed: ' + text)

    def packet(self, text):
        self.client.stdin.write(text + '\n')
        self.client.stdin.flush()

    def result(self, name):
        path = self.data / (name + '.json')
        path.unlink(missing_ok=True)
        return path

    def read(self, path, timeout=30):
        self.wait(path.exists, path.name, timeout)
        return json.loads(path.read_text())

    def ask(self, command, name, timeout=30):
        """Runs `dg <command>` and returns the JSON the fixture writes as <name>.json."""
        path = self.result(name)
        self.cmd('dg ' + command, timeout)
        return self.read(path, timeout)

    def status(self):
        return self.ask('status', 'status')

    def shutdown(self):
        try:
            stop_process(self.client, lambda: self.packet('stop'), timeout=10)
        except Exception as e:
            self.failures.append('client cleanup ' + repr(e))
        try:
            stop_process(self.server, lambda: self.cmd_raw('stop'))
        except Exception as e:
            self.failures.append('server cleanup ' + repr(e))
        for t in self.threads:
            t.join(timeout=5)
        self.threads = []
        self.client = self.server = None

    def finish(self, extra):
        errors = [x.rstrip() for x in self.logs if re.search(r'Exception|SEVERE|ERROR\]', x) and 'S06A' not in x]
        listener = [x for x in self.logs if 'Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x))]
        self.require(not listener, 'no listener or Allium exceptions in the server log: ' + json.dumps(listener[:5]))
        hashes = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in (self.lane / 'plugins').glob('*.jar')} \
            if (self.lane / 'plugins').exists() else {}
        version = next((m.group(0) for x in self.logs for m in [re.search(r'This server is running .*', x)] if m), None)
        summary = {'lane': self.lane_name, 'port': self.port, 'server': version, 'client': self.spec['client'],
                   'providers': self.providers, 'checks': sum(1 for x in self.driver if x.startswith(('PASS ', 'FAIL '))),
                   'failures': self.failures, 'serverLogErrors': errors[:40], 'hashes': hashes, **extra}
        (self.run / 'summary.json').write_text(json.dumps(summary, indent=2))
        (self.run / 'driver.log').write_text('\n'.join(self.driver) + '\n')
        for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
            target = self.lane / name
            if target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            elif target.exists():
                target.unlink()
        self.emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'server', 'checks', 'failures']}))
        return summary


def nexo_items(plugins, items_yaml):
    """A minimal Nexo setup from the S05 coexist lane, with the given item definitions."""
    root = plugins / 'Nexo'
    (root / 'items').mkdir(parents=True)
    shutil.copy2(COEXIST / 'plugins/Nexo/settings.yml', root / 'settings.yml')
    (root / 'converter.yml').write_text('oraxenConverter:\n  convertItems: false\n  convertResourcePack: false\n'
                                        '  convertSettings: false\n  convertFurnitureOnLoad: false\n')
    (root / 'items/allium_gate.yml').write_text(items_yaml)


def craftengine_items(plugins, items_yaml):
    root = plugins / 'CraftEngine'
    root.mkdir()
    shutil.copytree(COEXIST / 'plugins/CraftEngine/libs', root / 'libs')
    with zipfile.ZipFile(PROVIDER_JARS['craftengine']) as archive:
        (root / 'commands.yml').write_bytes(archive.read('commands.yml'))
        config = archive.read('config.yml').decode()
    start = config.index('  workflows:')
    end = config.index('  # Players apply', start)
    config = config[:start] + '  workflows: {}\n  packs: {}\n\n' + config[end:]
    for old, new in [('send-on-join: true', 'send-on-join: false'), ('metrics: true', 'metrics: false'),
                     ('update-checker: true', 'update-checker: false')]:
        config = config.replace(old, new)
    (root / 'config.yml').write_text(config)
    pack = root / 'resources/allium_gate'
    (pack / 'configuration').mkdir(parents=True)
    (pack / 'pack.yml').write_text('namespace: allium_gate\nauthor: S10TestOnly\nversion: 1.0\n')
    (pack / 'configuration/items.yml').write_text(items_yaml)


def oraxen_items(plugins, items_yaml):
    root = plugins / 'Oraxen'
    (root / 'items').mkdir(parents=True)
    shutil.copy2(COEXIST / 'plugins/Oraxen/settings.yml', root / 'settings.yml')
    (root / 'items/allium_gate.yml').write_text(items_yaml)
