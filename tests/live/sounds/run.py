#!/usr/bin/env python3
"""S08 gate: message sound preferences (/messagesounds, /msounds, /mfx) on Spigot 1.20.1 and Paper 26.3.

Two real protocol bots and the console. Every volume is read from the sound packet the recipient
receives for a real private message:
- the volume and toggle/reset rules, with zero and refused input;
- persistence across a restart;
- a config change through /allium reload;
- volume and pitch that a lang entry leaves out falling back separately to sounds.defaults;
- a disabled or invalid sound;
- the dry-run-first legacy lang.yml import.

It reuses the S07 bots and the MsgGate fixture (permissions, faults), on the same owned ports.
Disposable lanes only; exits nonzero on any failed check.
"""
import argparse, hashlib, json, re, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
MSG = REPO / 'tests/live/messaging'
LEGACY_LANG = Path('/mnt/storage/repos/Allium/src/main/resources/lang.yml')  # the pre-recode plugin's bundled file
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'port': 25692, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'),
                      'jar': Path('/tmp/allium-server-cache/spigot-1.20.1.jar')},
    'paper-26.3': {'port': 25693, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane')},
}
A, B = 'MsgA', 'MsgB'
ORB = 'minecraft:entity.experience_orb.pickup'
PLING = 'minecraft:block.note_block.pling'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s08-20261108/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s08-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s08-YYYYMMDD/runs root')
spec = LANES[a.lane]
run = a.run_root.resolve() / a.run_name
if run.exists():
    p.error('run directory exists; choose a fresh --run-name')
run.mkdir(parents=True)
lane = run / 'lane'
port = spec['port']
driver, failures, evidence = [], [], {}
logs = []
server = None
bots = {}
sound_name = ORB


def emit(message):
    driver.append(message)
    print(message, flush=True)


def require(ok, label):
    if not ok:
        failures.append(label)
    emit(('PASS ' if ok else 'FAIL ') + label)
    return ok


def pump(proc, path, sink):
    with path.open('a') as f:
        for line in proc.stdout:
            f.write(line)
            f.flush()
            sink.append(line)


def wait(fn, label, timeout=30):
    end = time.monotonic() + timeout
    while True:
        value = fn()
        if value:
            return value
        if server is not None and server.poll() is not None:
            raise RuntimeError(f'server exited {server.returncode} waiting for {label}')
        if time.monotonic() > end:
            raise RuntimeError('timeout ' + label)
        time.sleep(0.05)


def strip(text):
    return re.sub('§.', '', text)


class Bot:
    def __init__(self, name):
        self.name = name
        self.proc = None
        self.lines = []
        self.last_command = 0.0

    def connect(self):
        kind, version = spec['client']
        script = 'client-raw26.cjs' if kind == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(MSG / script), str(lane), str(port)] + ([version] if version else []) + [self.name]
        start = len(self.lines)
        self.proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=pump, args=(self.proc, run / f'client-{self.name}.log', self.lines), daemon=True).start()
        wait(lambda: any('BASEG-WIRE READY' in x for x in self.lines[start:]), self.name + ' READY', 120)
        time.sleep(0.5)

    def disconnect(self):
        if self.proc is not None and self.proc.poll() is None:
            stop_process(self.proc, lambda: (self.proc.stdin.write('stop\n'), self.proc.stdin.flush()), timeout=15)
        self.proc = None
        time.sleep(0.7)

    def write(self, line):
        self.proc.stdin.write(line + '\n')
        self.proc.stdin.flush()

    def cmd(self, command):
        # The server kicks for spam above about one command a second sustained (its counter gains 20
        # per command and loses 1 per tick), so each bot sends at most one per second.
        pause = 1.05 - (time.monotonic() - self.last_command)
        if pause > 0:
            time.sleep(pause)
        self.last_command = time.monotonic()
        start = len(self.lines)
        self.write('cmd ' + command)
        return start

    def chats(self, start):
        return [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-CHAT ')]

    def sounds(self, start, name=None):
        found = [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-SOUND ')]
        return [s for s in found if name is None or s['name'] == name]

    def chat(self, start, fragment, timeout=10):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), f'{self.name} chat "{fragment}"', timeout)

    def says(self, command, fragment):
        """Runs a command and returns the plain chat line containing fragment."""
        start = self.cmd(command)
        return self.chat(start, fragment)['plain']

    def tab(self, text):
        start = len(self.lines)
        self.write('tab ' + text)
        line = wait(lambda: next((x for x in self.lines[start:] if x.startswith('MSGG-TAB ')), None), f'{self.name} tab "{text}"', 10)
        return json.loads(line.split(' ', 1)[1])['matches']


def console(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()


def fixture(text):
    start = len(logs)
    console('mgg ' + text)
    line = wait(lambda: next((x for x in logs[start:] if f'MSGG-DONE {text} => ' in x or f'MSGG-FAIL {text} => ' in x), None), 'mgg ' + text)
    if 'MSGG-FAIL' in line:
        raise RuntimeError(line.strip())
    time.sleep(0.2)


def console_says(fragment, start, timeout=15):
    return wait(lambda: next((strip(x).strip() for x in logs[start:] if fragment in strip(x)), None), 'console "' + fragment + '"', timeout)


def reload():
    start = len(logs)
    console('allium reload')
    console_says('Configuration reloaded', start)
    time.sleep(0.3)


counter = [0]


def notify(sender=A, recipient=B):
    """sender messages recipient; returns (the recipient's chat line, its message sounds)."""
    counter[0] += 1
    tag = f'ping{counter[0]}'
    r = bots[recipient]
    start = len(r.lines)
    bots[sender].cmd(f'msg {recipient} {tag}')
    line = r.chat(start, tag)
    time.sleep(1.2)
    return line, r.sounds(start, sound_name)


def heard(expected_volume, expected_pitch, label, sender=A, recipient=B):
    line, sounds = notify(sender, recipient)
    ok = len(sounds) == 1 and abs(sounds[0]['volume'] - expected_volume) < 1e-4 and abs(sounds[0]['pitch'] - expected_pitch) < 1e-4
    require(ok, f'{label}: {recipient} heard {sound_name} at volume {expected_volume}, pitch {expected_pitch} ({sounds})')
    return sounds


def silent(label, sender=A, recipient=B):
    line, sounds = notify(sender, recipient)
    require(line is not None and not sounds, f'{label}: the message arrived ("{line["plain"]}") with no sound ({sounds})')


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(MSG / 'plugin/gate/MsgGate.java')], check=True)
    shutil.copy2(MSG / 'plugin/plugin.yml', classes / 'plugin.yml')
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/jar', 'cf', str(out / 'MsgGate.jar'), '-C', str(classes), '.'], check=True)


def write_lane():
    lane.mkdir()
    if 'template' in spec:
        for d in ['libraries', 'versions']:
            shutil.copytree(spec['template'] / d, lane / d)
    else:
        shutil.copy2(spec['jar'], lane / 'server.jar')
    (lane / 'plugins/Allium').mkdir(parents=True)
    shutil.copy2(a.allium_jar, lane / 'plugins/Allium.jar')
    shutil.copy2(run / 'fixture/MsgGate.jar', lane / 'plugins/MsgGate.jar')
    shutil.copy2(REPO / 'src/main/resources/config.yml', lane / 'plugins/Allium/config.yml')
    # The pre-recode plugin's own bundled lang.yml, as an upgraded server would still have it.
    shutil.copy2(LEGACY_LANG, lane / 'plugins/Allium/lang.yml')
    (lane / 'eula.txt').write_text('eula=true\n')
    (lane / 'bukkit.yml').write_text('settings:\n  allow-end: false\n  connection-throttle: 0\n')
    (lane / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
white-list=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
level-name=world
level-seed=8011108
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":2}}],"biome":"minecraft:plains"}}
generate-structures=false
allow-nether=false
pause-when-empty-seconds=-1
max-tick-time=120000
''')


def argv():
    if 'template' in spec:
        libs = {}

        def version(path):
            return tuple(int(v) if v.isdigit() else v for v in re.findall(r'\d+|[^\d]+', path.parent.name))
        for jar in (lane / 'libraries').rglob('*.jar'):
            artifact = jar.parent.parent
            if artifact not in libs or version(jar) > version(libs[artifact]):
                libs[artifact] = jar
        cp = ':'.join(str(x.relative_to(lane)) for x in [lane / 'versions/26.3/paper-26.3.jar', *libs.values()])
        return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                '-Dallium.fault-injection=true', '-cp', cp, 'org.bukkit.craftbukkit.Main', '--nogui']
    return [spec['java'], '-Xms512M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-Dallium.fault-injection=true', '-jar', 'server.jar', '--nogui']


def boot(label):
    global server
    emit('BOOT ' + label)
    start = len(logs)
    server = subprocess.Popen(argv(), cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1)
    threading.Thread(target=pump, args=(server, run / 'server.log', logs), daemon=True).start()
    wait(lambda: any('Done (' in x for x in logs[start:]), label + ' Done', 600)
    require(any('/messagesounds' in x and 'Private messaging enabled' in x for x in logs[start:])
            and any('MSGG-GATE READY' in x for x in logs[start:]), f'{label}: messaging with /messagesounds and the fixture enabled')


def stop_server():
    global server
    if server is not None and server.poll() is None:
        stop_process(server, lambda: console('stop'), timeout=90)
    server = None


def config_set(**values):
    """Sets sounds.* values in the lane's config.yml (keys: volume, pitch, fallback)."""
    path = lane / 'plugins/Allium/config.yml'
    text = path.read_text()
    patterns = {'volume': (r'(  defaults:\n(?:    #.*\n)*    volume: )[^\n]*', None),
                'pitch': (r'(  defaults:\n(?:    #.*\n|    volume: .*\n)*    pitch: )[^\n]*', None),
                'fallback': (r'(  messages:\n(?:    #.*\n)*    fallback-volume: )[^\n]*', None)}
    for key, value in values.items():
        pattern = patterns[key][0]
        text, n = re.subn(pattern, lambda m: m.group(1) + str(value), text, count=1)
        if n != 1:
            raise RuntimeError('could not set sounds ' + key)
    path.write_text(text)


def lang_path():
    return lane / 'plugins/Allium/lang/en_US.yml'


def set_received(body):
    """Replaces messaging.received in the lane's en_US.yml with the given indented lines."""
    path = lang_path()
    text = path.read_text()
    new, n = re.subn(r'(?m)^  received:\n(?:    .*\n)+', '  received:\n' + body, text, count=1)
    if n != 1:
        raise RuntimeError('could not rewrite messaging.received')
    path.write_text(new)


RECEIVED_TEXT = '    text: "&7[&f{sender} &7→ &fme&7] &f{message}"\n'


def status(bot, fragment):
    return bots[bot].says('mfx', fragment)


def basics():
    b = bots[B]
    heard(0.6, 1.0, 'default: lang volume 0.6 × the fallback 1.0, pitch from sounds.defaults')
    start = b.cmd('mfx')
    line = b.chat(start, 'Message sound volume')['plain']
    usage_seen = b.chat(start, '/mfx volume <0-1>')
    require(line.endswith('Message sound volume: 1 (the server default).') and usage_seen is not None,
            f'/mfx with no arguments reports the default and shows usage: "{line}"')
    for alias in ('msounds', 'messagesounds'):
        require(b.says(alias, 'Message sound volume').endswith('(the server default).'), f'/{alias} is the same command')

    require(b.says('mfx volume 0.5', 'set to').endswith('Message sound volume set to 0.5.'), '/mfx volume 0.5 is accepted')
    heard(0.3, 1.0, 'override 0.5: 0.6 × 0.5')
    bad = ['NaN', 'Infinity', '-0.1', '1.5', 'abc', '1e309', '0x1p-1', '0.5f']
    replies = [b.says(f'mfx volume {x}', 'not a volume') for x in bad]
    require(all(r.endswith('is not a volume from 0 to 1; nothing changed.') for r in replies),
            f'refused without a change: {bad}')
    usage = b.says('mfx volume', 'Usage')
    other = b.says('mfx loud', '/mfx volume <0-1>')
    require('/mfx volume <0-1>' in usage and '/mfx toggle' in other, 'a missing volume and an unknown action print usage only')
    heard(0.3, 1.0, 'after the refused inputs the override is still 0.5')

    require(b.says('mfx volume 0', 'muted').endswith('Message sounds muted. /mfx toggle turns them back on.'), '/mfx volume 0 mutes')
    silent('volume 0')
    require(status(B, 'muted').endswith('turns them back on at 0.5.'), '/mfx reports the mute and what a toggle restores')
    require(b.says('mfx toggle', 'back on').endswith('Message sounds are back on at 0.5.'), 'a toggle from 0 restores 0.5')
    heard(0.3, 1.0, 'restored')
    require(b.says('mfx toggle', 'muted').endswith('Message sounds muted. /mfx toggle turns them back on.'), 'a toggle from 0.5 mutes')
    silent('toggled off')
    b.says('mfx toggle', 'back on')
    heard(0.3, 1.0, 'toggled back on')
    require(b.says('mfx reset', 'You now follow').endswith("You now follow the server's message sound volume, 1."),
            '/mfx reset follows the server default')
    heard(0.6, 1.0, 'after reset')
    require('Message sound volume: 1 (the server default).' in status(B, 'Message sound volume'), 'the status says default again')

    start = len(logs)
    console('mfx')
    console_says('can only be used by players', start)
    require(True, 'the console gets "players only" from /mfx')
    tabs = b.tab('mfx ')
    values = b.tab('mfx volume ')
    require(set(tabs) == {'volume', 'toggle', 'reset'} and values == ['0', '0.25', '0.5', '0.75', '1'],
            f'tab completion: {tabs}, {values}')
    evidence['basics'] = {'refused': dict(zip(bad, replies)), 'tabs': tabs, 'volumeTabs': values}


def blocked_is_silent():
    b = bots[B]
    b.says('msgtoggle off', 'no longer accept')
    a_start, b_start = len(bots[A].lines), len(b.lines)
    bots[A].cmd('msg MsgB blocked')
    bots[A].chat(a_start, "can't receive")
    time.sleep(1.5)
    require(not b.chats(b_start) and not b.sounds(b_start, sound_name),
            'a blocked send is still silent for the recipient (no chat, no message sound; world sounds are not the message\'s)')
    b.says('msgtoggle on', 'now accept')


def persistence():
    b = bots[B]
    b.says('mfx volume 0.5', 'set to')
    b.says('mfx toggle', 'muted')
    bots[A].says('mfx volume 0.8', 'set to')
    for bot in bots.values():
        bot.disconnect()
    stop_server()
    boot('restart')
    for bot in bots.values():
        bot.connect()
    silent('after a restart MsgB is still muted')
    require(b.says('mfx toggle', 'back on').endswith('back on at 0.5.'), 'after a restart the toggle still restores 0.5')
    heard(0.3, 1.0, 'restored after the restart')
    b.says('mfx reset', 'You now follow')
    heard(0.48, 1.0, 'MsgA kept its 0.8 override across the restart', sender=B, recipient=A)


def config_changes():
    b = bots[B]
    config_set(fallback=0.25)
    reload()
    heard(0.15, 1.0, 'fallback 0.25 after /allium reload: a reset player follows it (0.6 × 0.25)')
    heard(0.48, 1.0, 'an override (MsgA 0.8) is independent of the fallback', sender=B, recipient=A)
    require(status(B, 'Message sound volume').endswith('Message sound volume: 0.25 (the server default).'), '/mfx shows the new default')
    config_set(fallback=0)
    reload()
    silent('fallback 0 mutes a reset player')
    require(b.says('mfx toggle', 'stay muted').endswith('so message sounds stay muted. Use /mfx volume <0-1>.'),
            'a toggle with nothing to restore and a silent default says sounds stay muted')
    b.says('mfx volume 0.5', 'set to')
    heard(0.3, 1.0, 'an override still works with a silent default')
    config_set(fallback=1.0)
    reload()
    b.says('mfx reset', 'You now follow')
    heard(0.6, 1.0, 'back to fallback 1.0')


def independent_defaults():
    global sound_name
    config_set(volume=0.8, pitch=1.5)
    reload()
    heard(0.6, 1.5, 'entry with volume 0.6 and no pitch: the pitch comes from sounds.defaults.pitch 1.5')
    set_received(RECEIVED_TEXT + f'    sound: {ORB}\n    pitch: 0.7\n')
    reload()
    require('volume' not in lang_path().read_text().split('  received:\n', 1)[1].split('  cannot-receive', 1)[0],
            'the reload did not put the omitted volume back into the lang file')
    heard(0.8, 0.7, 'entry with pitch 0.7 and no volume: the volume comes from sounds.defaults.volume 0.8')
    set_received(RECEIVED_TEXT + f'    sound: {ORB}\n')
    reload()
    heard(0.8, 1.5, 'entry with only a sound: both come from sounds.defaults, separately')
    set_received(RECEIVED_TEXT + f'    sound: {ORB}\n    volume: 0\n')
    reload()
    silent('entry volume 0')
    log_start = len(logs)
    set_received(RECEIVED_TEXT + f'    sound: {ORB}\n    volume: 0.6\n    pitch: 3.0\n')
    reload()
    heard(0.6, 1.5, 'an out-of-range pitch (3.0) counts as omitted')
    heard(0.6, 1.5, 'and again')
    warnings = [x for x in logs[log_start:] if 'pitch' in x and 'Message sound' in x]
    require(len(warnings) == 1, f'one bounded diagnostic for the bad pitch, not one per message: {len(warnings)}')
    log_start = len(logs)
    set_received(RECEIVED_TEXT + '    sound: none\n')
    reload()
    silent('sound: none switches the sound off')
    require(not [x for x in logs[log_start:] if 'sound' in x.lower() and ('WARN' in x or 'Invalid' in x)],
            'sound: none logs no warning')
    # Back to the shipped entry and defaults for the importer.
    shutil.copy2(run / 'en_US.shipped.yml', lang_path())
    config_set(volume=1.0, pitch=1.0)
    reload()
    heard(0.6, 1.0, 'the shipped entry again')


def unloaded():
    b = bots[B]
    b.says('mfx volume 0.7', 'set to')
    fixture('fault preferences.load-fail 20')
    b.disconnect()
    b.connect()
    line = b.says('mfx volume 0.2', 'still loading')
    require(line.endswith("MsgB's settings are still loading. Try again shortly."), f'/mfx fails closed while preferences load: "{line}"')
    fixture('fault preferences.load-fail 0')
    b.disconnect()
    b.connect()
    require(status(B, 'Message sound volume').endswith('Message sound volume: 0.7 (your setting).'),
            'nothing changed while they were loading')
    b.says('mfx reset', 'You now follow')


def legacy_import():
    global sound_name
    target = lang_path()
    before = hashlib.sha256(target.read_bytes()).hexdigest()
    legacy_before = hashlib.sha256((lane / 'plugins/Allium/lang.yml').read_bytes()).hexdigest()
    start = import_start = len(logs)
    console('allium import lang')
    report = console_says('Dry run:', start)
    console_says('not portable yet', start)
    require(hashlib.sha256(target.read_bytes()).hexdigest() == before, f'the dry run wrote nothing: "{report}"')
    start = len(logs)
    console('allium import lang apply')
    applied = console_says('Imported', start)
    backups = sorted((lane / 'plugins/Allium/lang').glob('en_US.yml.before-import-*'))
    require(len(backups) == 1 and hashlib.sha256(backups[0].read_bytes()).hexdigest() == before,
            f'apply wrote the messages and kept a byte-identical backup: "{applied}"')
    sound_name = PLING
    line, sounds = notify()
    require(line['plain'].startswith('(From MsgA) ping') and len(sounds) == 1
            and abs(sounds[0]['volume'] - 0.5) < 1e-4 and abs(sounds[0]['pitch'] - 1.5) < 1e-4,
            f'the imported format and its [sound] line are live: "{line["plain"]}" {sounds}')
    a_bot = bots[A]
    a_start = len(a_bot.lines)
    a_bot.cmd('msg MsgB sent-sound')
    a_bot.chat(a_start, 'sent-sound')
    time.sleep(1.2)
    sent = a_bot.sounds(a_start, PLING)
    require(len(sent) == 1 and abs(sent[0]['volume'] - 0.5) < 1e-4 and abs(sent[0]['pitch'] - 2.0) < 1e-4,
            f'the sender hears the imported msg.sent sound (0.5, 2): {sent}')
    start = len(logs)
    console('allium import lang apply')
    console_says('Nothing to import', start)
    require(hashlib.sha256((lane / 'plugins/Allium/lang.yml').read_bytes()).hexdigest() == legacy_before,
            'a second apply changes nothing, and the legacy lang.yml is untouched')
    evidence['import'] = {'report': report, 'applied': applied,
                          'log': [strip(x).strip() for x in logs[import_start:] if re.search(r'Lang import|  (WRITE|KEPT|SAME) |note:|not portable', x)][:80]}


summary = {}
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    build_fixture()
    write_lane()
    boot('initial')
    shutil.copy2(lang_path(), run / 'en_US.shipped.yml')
    for name in (A, B):
        bots[name] = Bot(name)
        bots[name].connect()
    basics()
    blocked_is_silent()
    persistence()
    config_changes()
    independent_defaults()
    unloaded()
    legacy_import()
except BaseException as e:
    failures.append('HARNESS ' + repr(e))
    emit('ERROR ' + repr(e))
finally:
    for bot in bots.values():
        try:
            bot.disconnect()
        except Exception as e:
            failures.append('client cleanup ' + repr(e))
    try:
        stop_server()
    except Exception as e:
        failures.append('server cleanup ' + repr(e))
    expected = lambda x: 'fault injection' in x or 'Could not load preferences' in x or 'Gave up loading preferences' in x
    errors = [x.strip() for x in logs if not expected(x) and ('Could not pass event' in x or ('Allium' in x and re.search(r'Exception|SEVERE', x)))]
    require(not errors, 'no listener or Allium exceptions in the server log: ' + json.dumps(errors[:5]))
    version = next((m.group(0) for x in logs for m in [re.search(r'This server is running .*', x)] if m), None)
    summary = {'lane': a.lane, 'port': port, 'server': version, 'client': spec['client'],
               'checks': sum(1 for x in driver if x.startswith(('PASS ', 'FAIL '))), 'failures': failures, 'evidence': evidence,
               'hashes': {'Allium.jar': hashlib.sha256(a.allium_jar.read_bytes()).hexdigest()},
               'testSources': {str(f.relative_to(REPO)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in sorted(list(HERE.rglob('*')) + list(MSG.glob('client-*.cjs')) + list(MSG.rglob('MsgGate.java')))
                               if f.is_file() and f.suffix in ('.py', '.java', '.yml', '.cjs')}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2))
    (run / 'driver.log').write_text('\n'.join(driver) + '\n')
    for name in ['libraries', 'versions', 'cache', 'bundler', 'world', 'world_nether', 'world_the_end', 'server.jar']:
        target = lane / name
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
        elif target.exists():
            target.unlink()
    emit('SUMMARY ' + json.dumps({k: summary[k] for k in ['lane', 'server', 'checks', 'failures']}))
sys.exit(1 if failures else 0)
