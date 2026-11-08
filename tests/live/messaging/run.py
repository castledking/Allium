#!/usr/bin/env python3
"""S07 gate: private messaging on Spigot 1.20.1 and Paper 26.3, with two real protocol bots and the console.

Send and reply (including the console, and a reply target going offline); every block path, each with
the sender's message checked and silence (no chat, no sound) for the recipient; untrusted input; tab
completion with vanish; and /msgtoggle and /ignore persistence across a restart. The MsgGate fixture
vanishes players through the common "vanished" metadata, mutes through Allium's MuteSource hook,
grants permissions and arms faults. Disposable lanes only; exits nonzero on any failed check.
"""
import argparse, hashlib, json, re, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / 'tests/live/providers/trigger'))
from process_cleanup import stop_process  # noqa: E402

LANES = {
    'spigot-1.20.1': {'port': 25692, 'java': '/usr/lib/jvm/java-17-openjdk/bin/java', 'client': ('mcp', '1.20.1'),
                      'jar': Path('/tmp/allium-server-cache/spigot-1.20.1.jar')},
    'paper-26.3': {'port': 25693, 'java': '/usr/lib/jvm/java-25-openjdk/bin/java', 'client': ('raw26', None),
                   'template': Path('/tmp/allium-s05-20261105/provider-gate/lane')},
}
A, B = 'MsgA', 'MsgB'
SOUND = 'minecraft:entity.experience_orb.pickup'

p = argparse.ArgumentParser()
p.add_argument('--lane', choices=sorted(LANES), required=True)
p.add_argument('--run-name', required=True)
p.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s07-20261108/runs'))
p.add_argument('--allium-jar', type=Path, default=REPO / 'build/libs/Allium.jar')
a = p.parse_args()
if not re.fullmatch('[a-zA-Z0-9_.-]+', a.run_name):
    p.error('invalid run-name')
if not re.fullmatch(r'/tmp/allium-s07-\d{8}/runs', str(a.run_root.resolve())):
    p.error('require an owned /tmp/allium-s07-YYYYMMDD/runs root')
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

    def connect(self):
        kind, version = spec['client']
        script = 'client-raw26.cjs' if kind == 'raw26' else 'client-mcp.cjs'
        args = ['node', str(HERE / script), str(lane), str(port)] + ([version] if version else []) + [self.name]
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
        start = len(self.lines)
        self.write('cmd ' + command)
        return start

    def chats(self, start):
        return [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-CHAT ')]

    def sounds(self, start):
        return [json.loads(x.split(' ', 1)[1]) for x in self.lines[start:] if x.startswith('BASEG-SOUND ')]

    def chat(self, start, fragment, timeout=10):
        return wait(lambda: next((c for c in self.chats(start) if fragment in c['plain']), None), f'{self.name} chat "{fragment}"', timeout)

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


def console_says(fragment, start, timeout=10):
    return wait(lambda: next((strip(x).strip() for x in logs[start:] if fragment in strip(x)), None), 'console "' + fragment + '"', timeout)


def build_fixture():
    out = run / 'fixture'
    classes = out / 'classes'
    classes.mkdir(parents=True)
    api = '/tmp/allium-lane-spigot-1.20/bundler/libraries/spigot-api-1.20.1-R0.1-SNAPSHOT.jar'
    gson = next((LANES['paper-26.3']['template'] / 'libraries/com/google/code/gson').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk/bin/javac', '--release', '17', '-cp', f'{api}:{gson}:{a.allium_jar}',
                    '-d', str(classes), str(HERE / 'plugin/gate/MsgGate.java')], check=True)
    shutil.copy2(HERE / 'plugin/plugin.yml', classes / 'plugin.yml')
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
level-seed=7011108
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
    require(any('Private messaging enabled' in x for x in logs[start:]) and any('MSGG-GATE READY' in x for x in logs[start:]),
            f'{label}: Allium\'s messaging module and the fixture enabled')


def stop_server():
    global server
    if server is not None and server.poll() is None:
        stop_process(server, lambda: console('stop'), timeout=90)
    server = None


def silent(bot, start, seconds=1.5):
    """No chat line and no message sound (world sounds, such as landing, are not the message's)."""
    time.sleep(seconds)
    return not bot.chats(start) and not [s for s in bot.sounds(start) if s['name'] == SOUND]


CANNOT = "MsgB can't receive messages right now."


def blocked(label, expect_fragment):
    """A: /msg MsgB <label>; returns (A's reply, whether MsgB stayed silent)."""
    a_start, b_start = len(bots[A].lines), len(bots[B].lines)
    bots[A].cmd(f'msg MsgB {label}')
    reply = bots[A].chat(a_start, expect_fragment)
    quiet = silent(bots[B], b_start)
    return reply['plain'], quiet


def send_and_reply():
    a, b = bots[A], bots[B]
    a_start, b_start = len(a.lines), len(b.lines)
    a.cmd('msg MsgB hello there')
    got = b.chat(b_start, 'hello there')
    mine = a.chat(a_start, 'hello there')
    sound = wait(lambda: next(iter(b.sounds(b_start)), None), 'recipient sound', 5)
    require(got['plain'] == '[MsgA → me] hello there' and mine['plain'] == '[me → MsgB] hello there',
            f'send: MsgB got "{got["plain"]}" and MsgA saw "{mine["plain"]}"')
    require(sound['name'] == SOUND and abs(sound['volume'] - 0.6) < 1e-6,
            f'the recipient heard the received sound at 0.6 (its lang volume 0.6 x MsgB\'s volume 1.0): {sound}')
    a_start = len(a.lines)
    b.cmd('r hi back')
    require(a.chat(a_start, 'hi back')['plain'] == '[MsgB → me] hi back', 'reply: MsgB\'s /r reached MsgA, its last partner')

    a_start, log_start = len(a.lines), len(logs)
    console('msg MsgA from the console')
    require(a.chat(a_start, 'from the console')['plain'] == '[Console → me] from the console', 'the console can send')
    a.cmd('r ok console')
    console_says('[MsgA → me] ok console', log_start)
    a_start = len(a.lines)
    console('reply back to you')
    require(a.chat(a_start, 'back to you')['plain'] == '[Console → me] back to you',
            'MsgA can reply to the console, and the console can reply (to MsgA, its last partner)')
    evidence['send'] = {'received': got, 'sent': mine, 'sound': sound}


def block_paths():
    a, b = bots[A], bots[B]
    replies = {}
    b_start = len(b.lines)
    b.cmd('msgtoggle off')
    b.chat(b_start, 'no longer accept private messages')
    replies['toggled off'], quiet = blocked('blocked one', "can't receive")
    require(replies['toggled off'].endswith(CANNOT) and quiet, f'toggled off: "{replies["toggled off"]}", and MsgB saw and heard nothing')
    b_start = len(b.lines)
    b.cmd('msgtoggle on')
    b.chat(b_start, 'now accept private messages')
    b.cmd('ignore MsgA')
    b.chat(b_start, 'now ignoring')
    replies['ignored'], quiet = blocked('blocked two', "can't receive")
    require(replies['ignored'] == replies['toggled off'] and quiet,
            'ignored: exactly the same sender message as toggled off, and silence for MsgB')
    # allium.msg.bypass.ignore, granted explicitly, reaches a player who ignores the sender.
    fixture('perm MsgA allium.msg.bypass.ignore true')
    b_start = len(b.lines)
    a.cmd('msg MsgB ignore bypass')
    reached = b.chat(b_start, 'ignore bypass')
    fixture('perm MsgA allium.msg.bypass.ignore unset')
    again, quiet = blocked('blocked again', "can't receive")
    require(reached['plain'] == '[MsgA → me] ignore bypass' and again == replies['ignored'] and quiet,
            'allium.msg.bypass.ignore, granted explicitly, reaches a player who ignores the sender; '
            'once it is unset the same send is blocked again, with silence for MsgB')
    b.cmd('unignore MsgA')
    b.chat(b_start, 'no longer ignoring')

    fixture('vanish MsgB on')
    replies['vanished'], quiet = blocked('blocked three', 'not found')
    offline_start = len(a.lines)
    a.cmd('msg Nobody123 hello')
    offline = a.chat(offline_start, 'not found')['plain']
    require(replies['vanished'] == offline.replace('Nobody123', 'MsgB') and quiet,
            f'vanished: MsgA sees "{replies["vanished"]}", word for word the offline message, and MsgB nothing')
    tab_hidden = a.tab('msg ')
    fixture('perm MsgA allium.vanish.see true')
    tab_seeing = a.tab('msg ')
    a_start, b_start = len(a.lines), len(b.lines)
    a.cmd('msg MsgB staff can see you')
    seen = b.chat(b_start, 'staff can see you')
    fixture('perm MsgA allium.vanish.see unset')
    fixture('vanish MsgB off')
    tab_back = a.tab('msg ')
    require(B not in tab_hidden and B in tab_seeing and B in tab_back and A in tab_hidden,
            f'tab completion: no vanished MsgB for MsgA ({tab_hidden}), but listed with allium.vanish.see ({tab_seeing}) and after unvanishing')
    require(seen is not None, 'with allium.vanish.see, MsgA can message the vanished MsgB')

    fixture('mute MsgA on')
    replies['muted'], quiet = blocked('blocked four', 'muted')
    fixture('mute MsgA off')
    require(replies['muted'].endswith('You are muted.') and quiet, f'muted sender: "{replies["muted"]}", and MsgB nothing')

    a_start = len(a.lines)
    a.cmd('msg MsgA me')
    require(a.chat(a_start, "can't message yourself")['plain'].endswith("You can't message yourself."), 'messaging yourself is refused')

    fixture('perm MsgA allium.msg.bypass.toggle true')
    b_start = len(b.lines)
    b.cmd('msgtoggle off')
    b.chat(b_start, 'no longer accept')
    b_start = len(b.lines)
    a.cmd('msg MsgB staff bypass')
    require(b.chat(b_start, 'staff bypass') is not None, 'allium.msg.bypass.toggle, granted explicitly, reaches a player with messages off')
    fixture('perm MsgA allium.msg.bypass.toggle unset')
    b_start = len(b.lines)
    b.cmd('msgtoggle on')
    b.chat(b_start, 'now accept')

    # Unloaded preferences fail closed: MsgB rejoins with every preference load failing.
    fixture('fault preferences.load-fail 20')
    b.disconnect()
    b.connect()
    replies['unloaded'], quiet = blocked('blocked five', 'Try again shortly')
    require(replies['unloaded'].endswith("MsgB's settings are still loading. Try again shortly.") and quiet,
            f'unloaded preferences fail closed: "{replies["unloaded"]}", and MsgB nothing')
    fixture('fault preferences.load-fail 0')
    b.disconnect()
    b.connect()
    evidence['blocked'] = replies


def reply_target_offline():
    a, b = bots[A], bots[B]
    b_start = len(b.lines)
    a.cmd('msg MsgB before you go')
    b.chat(b_start, 'before you go')
    b.disconnect()
    a_start = len(a.lines)
    a.cmd('r are you there')
    reply = a.chat(a_start, 'nobody to reply to')['plain']
    require(reply.endswith('You have nobody to reply to.'), f'a reply target that went offline is cleared: "{reply}"')
    b.connect()


def untrusted_input():
    a, b = bots[A], bots[B]
    typed = '&cRed <gradient:#ff0000:#00ff00>grad</gradient> #00ff00hex <b>bold</b> <click:run_command:/op MsgA>op me</click>'
    b_start = len(b.lines)
    a.cmd('msg MsgB ' + typed)
    got = b.chat(b_start, 'op me')
    body = got['plain'][len('[MsgA → me] '):]
    colors = set(got['colors'][len('[MsgA → me] '):])
    require(body == typed and colors == {'#ffffff'} and not got.get('interactive'),
            f'without permissions every code, tag and the click attempt arrive literally, all in the template\'s white: "{body}"')
    url = 'see https://example.com/page and www.example.org'
    b_start = len(b.lines)
    a.cmd('msg MsgB ' + url)
    linked = b.chat(b_start, 'example.org')
    require(linked['plain'].endswith(url) and not linked.get('interactive'),
            'URLs in a message arrive as plain text: no click or hover data reaches the recipient')
    fixture('perm MsgA allium.msg.color true')
    b_start = len(b.lines)
    a.cmd('msg MsgB &cRed #00ff00hex')
    got = b.chat(b_start, 'Red')
    body_colors = got['colors'][len('[MsgA → me] '):]
    require(got['plain'].endswith('Red #00ff00hex') and body_colors[0] == '#ff5555' and set(body_colors[4:]) == {'#ff5555'},
            'with allium.msg.color, &c applies, while the hex code (allium.msg.hex) stays literal')
    fixture('perm MsgA allium.msg.color unset')
    evidence['untrusted'] = {'typed': typed}


def toggle_syntax():
    log_start = len(logs)
    console('msgtoggle')
    console_says('Usage', log_start)
    log_start = len(logs)
    console('msgtoggle foo bar baz')
    console_says('Usage', log_start)
    log_start = len(logs)
    console('msgtoggle NotAPlayer')
    console_says('not found', log_start)
    b_start = len(bots[B].lines)
    log_start = len(logs)
    console('msgtoggle off MsgB')
    console_says('MsgB no longer accepts private messages', log_start)
    bots[B].chat(b_start, 'no longer accept')
    tab = bots[A].tab('msgtoggle ')
    require(set(tab) == {'on', 'off'}, f'console syntax: a bare /msgtoggle and bad arguments only print usage; others are set by name; '
                                      f'a player without allium.msgtoggle.others is offered only on/off ({tab})')


def persistence():
    a, b = bots[A], bots[B]
    b_start = len(b.lines)
    b.cmd('ignore MsgA')
    b.chat(b_start, 'now ignoring')
    for bot in (a, b):
        bot.disconnect()
    stop_server()
    boot('restart')
    for bot in (a, b):
        bot.connect()
    b_start = len(b.lines)
    b.cmd('ignore')
    listed = b.chat(b_start, 'You are ignoring')['plain']
    reply, quiet = blocked('after restart', "can't receive")
    b_start = len(b.lines)
    b.cmd('msgtoggle')
    flipped = b.chat(b_start, 'accept private messages')['plain']
    b.cmd('unignore MsgA')
    b.chat(b_start, 'no longer ignoring')
    b_start = len(b.lines)
    a.cmd('msg MsgB welcome back')
    require(listed.endswith('You are ignoring: MsgA') and reply.endswith(CANNOT) and quiet
            and flipped.endswith('You now accept private messages.') and b.chat(b_start, 'welcome back') is not None,
            'after a restart: MsgB still ignores MsgA and still has messages off (a bare /msgtoggle turns them on); '
            'after /unignore the message arrives')


summary = {}
try:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(('127.0.0.1', port))
    build_fixture()
    write_lane()
    boot('initial')
    for name in (A, B):
        bots[name] = Bot(name)
        bots[name].connect()
    send_and_reply()
    block_paths()
    reply_target_offline()
    untrusted_input()
    toggle_syntax()
    persistence()
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
                               for f in sorted(HERE.rglob('*')) if f.is_file() and f.suffix in ('.py', '.java', '.yml', '.cjs')}}
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
