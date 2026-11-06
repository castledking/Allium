#!/usr/bin/env python3
"""Disposable genuine Spigot 26.3 baseline. No player/command coverage claimed."""
import datetime
import hashlib
import json
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import threading
import time
import zipfile

REPO = Path(__file__).resolve().parents[4]
ROOT = Path('/tmp/allium-s05-20261106/spigot')
LANE = ROOT / '26.3-absent'
SOURCE = Path('/tmp/allium-lane-spigot-26.3/bundler')
LAUNCHER = Path('/mnt/storage/devops/.buildtools/spigot-26.3.jar')
ALLIUM = REPO / 'build/libs/Allium.jar'
FIXTURE = Path('/tmp/allium-s05-20261106/lanes/fixture/LaneGate.jar')
JAVA = '/usr/lib/jvm/java-25-openjdk/bin/java'
EVIDENCE = REPO / 'tests/live/providers/evidence/spigot-26.3-absent-20261106.log'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    # Refuse reuse: no accidental overwrites of any pre-existing lane/world.
    LANE.mkdir(parents=True, exist_ok=False)
    with socket.socket() as check:
        check.bind(('127.0.0.1', 25625))
    shutil.copytree(SOURCE, LANE / 'bundler')
    plugins = LANE / 'plugins'
    (plugins / 'LaneGate').mkdir(parents=True)
    shutil.copy2(ALLIUM, plugins / 'Allium.jar')
    shutil.copy2(FIXTURE, plugins / 'LaneGate.jar')
    (plugins / 'LaneGate/config.yml').write_text('expected: []\n')
    (LANE / 'eula.txt').write_text('eula=true\n')
    (LANE / 'server.properties').write_text('''server-ip=127.0.0.1
server-port=25625
online-mode=false
enforce-secure-profile=false
enable-query=false
enable-rcon=false
level-name=s05-fresh-world
generate-structures=false
view-distance=2
simulation-distance=2
max-players=2
spawn-protection=0
motd=S05 disposable Spigot baseline
''')
    server = LANE / 'bundler/versions/spigot-26.3-R0.1-SNAPSHOT.jar'
    with zipfile.ZipFile(LAUNCHER) as archive:
        expected_server_sha = archive.read('META-INF/versions.list').decode().split()[0]
    assert sha(server) == expected_server_sha, 'Extracted server differs from launcher manifest'
    for src in SOURCE.rglob('*'):
        if src.is_file():
            assert sha(src) == sha(LANE / 'bundler' / src.relative_to(SOURCE))
    inputs = [LAUNCHER, ALLIUM, FIXTURE, server]
    metadata = {
        'scope': 'S05 Spigot 26.3 all optional providers absent',
        'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
        'provenance': 'User-supplied local BuildTools launcher; only immutable extracted bundler copied from prior disposable lane. No world/player/secret copied.',
        'lane': str(LANE), 'providers': [], 'fixture_expected': [],
        'input_sha256': {str(path): sha(path) for path in inputs},
        'copied_bundler_sha256': {str(p.relative_to(LANE)): sha(p) for p in sorted((LANE / 'bundler').rglob('*')) if p.is_file()},
        'java': subprocess.check_output([JAVA, '-version'], stderr=subprocess.STDOUT, text=True),
        'limitations': 'Headless console fixture; no actual player, core-five command packets, native-provider item/reload, or inventory-retention claims.',
    }
    command = [JAVA, '-Xms256M', '-Xmx1400M', '-XX:ActiveProcessorCount=2', '-cp',
               'bundler/versions/spigot-26.3-R0.1-SNAPSHOT.jar:bundler/libraries/*',
               'org.bukkit.craftbukkit.Main', 'nogui', '--host', '127.0.0.1', '--port', '25625']
    metadata['argv'] = command
    (ROOT / '26.3-absent-inputs.json').write_text(json.dumps(metadata, indent=2) + '\n')
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    with EVIDENCE.open('x') as log:
        log.write(json.dumps(metadata, indent=2) + '\n--- RAW SERVER OUTPUT ---\n')
        log.flush()
        process = subprocess.Popen(command, cwd=LANE, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
        (ROOT / '26.3-absent.pid').write_text(str(process.pid) + '\n')
        lines = queue.Queue()
        def read():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)
        threading.Thread(target=read, daemon=True).start()
        output = []
        sent = False
        probe = False
        stopped = False
        failed = False
        deadline = time.monotonic() + 240
        def send(text):
            log.write('HARNESS CONSOLE> ' + text + '\n')
            log.flush()
            process.stdin.write(text + '\n')
            process.stdin.flush()
        try:
            while time.monotonic() < deadline:
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    if process.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                output.append(line)
                log.write(line)
                log.flush()
                if 'Done (' in line and not sent:
                    sent = True
                    for cmd in ('version', 'plugins', 'lanegate probe'):
                        send(cmd)
                if 'S05-LANE-FAIL' in line or 'Exception' in line or 'Could not load' in line:
                    failed = True
                if 'S05-LANE-PROBE-PASS' in line and not stopped:
                    probe = True
                    send('stop')
                    stopped = True
        finally:
            if process.poll() is None:
                if not stopped:
                    send('stop')
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
            while True:
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    break
                if line is None:
                    break
                output.append(line)
                log.write(line)
            text = ''.join(output)
            result = {
                'server_exit': process.returncode, 'boot_done': sent, 'console_probe_pass': probe,
                'stop_requested': stopped, 'allium_enabled': '[Allium] Enabling Allium' in text,
                'allium_disabled': '[Allium] Disabling Allium' in text,
                'fresh_input_allium_sha256': sha(plugins / 'Allium.jar'),
                'build_allium_sha256_after': sha(ALLIUM),
                'failure_marker_seen': failed,
            }
            result['pass'] = (sent and probe and stopped and process.returncode == 0
                              and result['allium_enabled'] and result['allium_disabled'] and not failed)
            log.write('\n--- HARNESS RESULT ---\n' + json.dumps(result, indent=2) + '\n')
            print(json.dumps(result, indent=2))
            print(str(EVIDENCE))
    return 0 if result['pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
