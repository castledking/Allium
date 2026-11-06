#!/usr/bin/env python3
"""Exact CE Bukkit 26.9.1 on its advertised Spigot 26.2 lane, headless only."""
import datetime
import argparse
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

import yaml
from run_absent import ALLIUM, FIXTURE, JAVA, REPO, ROOT, sha

ATTEMPT = argparse.ArgumentParser(description=__doc__)
ATTEMPT.add_argument('--attempt', default='', help='Unique suffix for a fresh retry, e.g. r2')
SUFFIX = ATTEMPT.parse_args().attempt
if SUFFIX and not SUFFIX.isalnum():
    raise SystemExit('Attempt must contain only letters and numbers')
SUFFIX = '-' + SUFFIX if SUFFIX else ''
LANE = ROOT / ('26.2-ce-26.9.1' + SUFFIX)
LAUNCHER = Path('/mnt/storage/devops/.buildtools/spigot-26.2.jar')
PINS = Path('/tmp/allium-s05-20261106/pins/ce')
CE = PINS / 'craft-engine-bukkit-plugin-26.9.1.jar'
EVIDENCE = REPO / ('tests/live/providers/evidence/spigot-26.2-ce-26.9.1-20261106' + SUFFIX + '.log')
COMMAND = [JAVA, '-Xms256M', '-Xmx1600M', '-XX:ActiveProcessorCount=2', '-cp',
           'bundler/versions/spigot-26.2-R0.1-SNAPSHOT.jar:bundler/libraries/*',
           'org.bukkit.craftbukkit.Main', 'nogui', '--host', '127.0.0.1', '--port', '25626']


def prepare():
    LANE.mkdir(parents=True, exist_ok=False)
    for port in (25626, 25627):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    manifest_hashes = {}
    with zipfile.ZipFile(LAUNCHER) as archive:
        for group in ('libraries', 'versions'):
            for line in archive.read('META-INF/' + group + '.list').decode().splitlines():
                digest, name = line.split(' *', 1)
                packaged = 'META-INF/' + group + '/' + name
                if packaged not in archive.namelist():
                    # BuildTools shades the Minecraft jar into its server; do not fake a dependency.
                    manifest_hashes[group + '/' + name] = {'manifest_sha256': digest, 'packaged': False}
                    continue
                data = archive.read(packaged)
                assert hashlib.sha256(data).hexdigest() == digest
                target = LANE / 'bundler' / group / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                manifest_hashes[group + '/' + name] = {'sha256': digest, 'packaged': True}
    version = json.loads((PINS / 'version-prX5nH7G.json').read_text())
    file = version['files'][0]
    assert file['filename'] == CE.name
    assert hashlib.sha512(CE.read_bytes()).hexdigest() == file['hashes']['sha512']
    assert '26.2' in version['game_versions'] and 'spigot' in version['loaders']
    plugins = LANE / 'plugins'
    plugins.mkdir()
    for name, source in [('Allium.jar', ALLIUM), ('LaneGate.jar', FIXTURE), (CE.name, CE)]:
        shutil.copy2(source, plugins / name)
    (plugins / 'LaneGate').mkdir()
    (plugins / 'LaneGate/config.yml').write_text('expected: [craftengine]\n')
    ce_dir = plugins / 'CraftEngine'
    ce_dir.mkdir()
    with zipfile.ZipFile(CE) as archive:
        config = yaml.safe_load(archive.read('config.yml'))
    config['metrics'] = False
    config['update-checker'] = False
    # Keep this S05 run free of resource-pack workflows/delivery and external listeners.
    pack = config['resource-pack']
    pack['workflows'] = {}
    pack['packs'] = {}
    pack['presets'] = {}
    pack['delivery']['send-on-join'] = False
    pack['self-host']['ip'] = '127.0.0.1'
    pack['self-host']['port'] = '25627'
    (ce_dir / 'config.yml').write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False))
    resource = ce_dir / 'resources/s05_spigot'
    (resource / 'configuration').mkdir(parents=True)
    (resource / 'pack.yml').write_text('namespace: sf_ores\nenable: true\nauthor: S05 validation\nversion: 1.0\n')
    shutil.copy2(Path(__file__).with_name('ce-item.yml'), resource / 'configuration/items.yml')
    (LANE / 'eula.txt').write_text('eula=true\n')
    (LANE / 'server.properties').write_text('''server-ip=127.0.0.1
server-port=25626
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
motd=S05 disposable Spigot exact CE
''')
    inputs = [LAUNCHER, ALLIUM, FIXTURE, CE, resource / 'configuration/items.yml', ce_dir / 'config.yml']
    metadata = {
        'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
        'provenance': {
            'server': 'Cached genuine BuildTools bundle /mnt/storage/devops/.buildtools/spigot-26.2.jar; buildtools-26.2.log records BuildTools #200 --rev 26.2 --remapped successful July 30 2026.',
            'ce': file['url'], 'ce_metadata': str(PINS / 'version-prX5nH7G.json'),
            'ce_sha512_verified': True, 'ce_supported_game_versions': version['game_versions'],
            'ce_supported_loaders': version['loaders'],
        },
        'java': subprocess.check_output([JAVA, '-version'], stderr=subprocess.STDOUT, text=True),
        'input_sha256': {str(p): sha(p) for p in inputs},
        'bundler_manifest': manifest_hashes,
        'argv': COMMAND, 'lane': str(LANE),
        'limitations': 'No actual player. LaneGate checkpoint is YAML-serialized ItemStack, not a player inventory. No core-five command-packet, updater-trigger, stack-quantity or S06 pack claims.',
    }
    (ROOT / ('26.2-ce-26.9.1' + SUFFIX + '-inputs.json')).write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def run(log, restore=False):
    log.write('\n--- ' + ('RESTART / SERIALIZED CHECKPOINT' if restore else 'INITIAL BOOT / NATIVE RELOAD') + ' ---\n')
    log.flush()
    p = subprocess.Popen(COMMAND, cwd=LANE, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
    (ROOT / ('26.2-ce-26.9.1' + SUFFIX + '.pid')).write_text(str(p.pid) + '\n')
    lines = queue.Queue()
    def read():
        for line in p.stdout:
            lines.put(line)
        lines.put(None)
    threading.Thread(target=read, daemon=True).start()
    markers = set()
    output = []
    stopped = False
    ready = False
    def send(cmd):
        log.write('HARNESS CONSOLE> ' + cmd + '\n')
        log.flush()
        p.stdin.write(cmd + '\n')
        p.stdin.flush()
    deadline = time.monotonic() + 300
    try:
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if p.poll() is not None:
                    break
                continue
            if line is None:
                break
            log.write(line)
            log.flush()
            output.append(line)
            for marker in ('S05-LANE-READY', 'S05-LANE-PROBE-PASS', 'S05-LANE-RELOAD-PASS',
                           'S05-LANE-RESTORE-PASS', 'S05-LANE-FAIL'):
                if marker in line:
                    markers.add(marker)
            if 'S05-LANE-READY' in line and not ready:
                ready = True
                for cmd in ('version', 'plugins', 'lanegate restore' if restore else 'lanegate probe'):
                    send(cmd)
            if 'S05-LANE-PROBE-PASS' in line and not restore:
                send('lanegate awaitreload')
                send('ce reload')
            if any(marker in line for marker in ('S05-LANE-RELOAD-PASS', 'S05-LANE-RESTORE-PASS', 'S05-LANE-FAIL', 'Disk quota exceeded')) and not stopped:
                send('stop')
                stopped = True
    finally:
        if p.poll() is None:
            if not stopped:
                send('stop')
            try:
                p.wait(timeout=45)
            except subprocess.TimeoutExpired:
                p.terminate()
                try:
                    p.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait(timeout=10)
        while True:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                break
            if line is None:
                break
            log.write(line)
            output.append(line)
    text = ''.join(output)
    wanted = {'S05-LANE-READY', 'S05-LANE-RESTORE-PASS'} if restore else {'S05-LANE-READY', 'S05-LANE-PROBE-PASS', 'S05-LANE-RELOAD-PASS'}
    result = {'stage': 'serialized_checkpoint_restart' if restore else 'boot_and_native_reload',
              'exit': p.returncode, 'markers': sorted(markers),
              'infrastructure_failure': 'Disk quota exceeded' if 'Disk quota exceeded' in text else None,
              'allium_disabled': '[Allium] Disabling Allium' in text,
              'craftengine_disabled': '[CraftEngine] Disabling CraftEngine' in text,
              'pass': wanted.issubset(markers) and 'S05-LANE-FAIL' not in markers and p.returncode == 0 and 'Disk quota exceeded' not in text}
    log.write('\nSTAGE RESULT ' + json.dumps(result) + '\n')
    log.flush()
    print(json.dumps(result), flush=True)
    return result


def main():
    metadata = prepare()
    with EVIDENCE.open('x') as log:
        log.write(json.dumps(metadata, indent=2) + '\n')
        results = [run(log)]
        if results[0]['pass']:
            results.append(run(log, restore=True))
        final = {'pass': len(results) == 2 and all(r['pass'] for r in results), 'stages': results,
                 'allium_sha256_after': sha(ALLIUM), 'installed_allium_sha256': sha(LANE / 'plugins/Allium.jar')}
        log.write('\n--- HARNESS RESULT ---\n' + json.dumps(final, indent=2) + '\n')
    print(json.dumps(final, indent=2))
    return 0 if final['pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
