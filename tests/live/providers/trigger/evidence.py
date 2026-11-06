#!/usr/bin/env python3
"""Render retained native JSON results + wire/driver logs as reviewable evidence."""
import argparse
import json
import re
from pathlib import Path


def emit(label, value):
    print(label + ' ' + json.dumps(value, sort_keys=True))


def render(run):
    summary = json.loads((run / 'summary.json').read_text())
    gaps = []

    def read_json(path):
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError) as error:
            record = {'file': str(path.relative_to(run)), 'error': str(error)}
            gaps.append(record)
            emit('EVIDENCE_UNAVAILABLE', record)
            return None

    def log(label, candidates):
        for path in candidates:
            if path.is_file():
                print(label + ' ' + str(path))
                content = path.read_text()
                print(content.rstrip())
                return content
        emit(label + '_ABSENT', {'searched': [str(path) for path in candidates]})
        return None

    failures = summary.get('failures')
    exits = [summary.get('serverExit'), summary.get('clientExit')]
    if failures or any(code is not None and code != 0 for code in exits):
        result = 'FAIL; inspect recorded assertions and exits'
    elif failures == [] and exits == [0, 0]:
        result = 'PASS all recorded invariants'
    else:
        result = 'INCOMPLETE; assertions or process exits not recorded'
    print('S05 CE native trigger diagnostic / 2026-11-06')
    print('RESULT: ' + result)
    print('Result reflects summary.json; missing evidence is labelled separately. No unrecorded stage is inferred.')
    print('Native packet/event fixture; no manually fired Bukkit events.')
    print('Seeder spends charges9->2 before baseline only; no post-baseline refresh/re-sign/native-update calls.')
    print('Fresh world/player data/Allium DB/keyring. Only runtime jars/libraries and CE template config copied.')
    print('Hosting/workflows/packs disabled before boot; send-on-join false. item update triggers click/drop/pick-up true; attack false.')
    print('API pin26.9.1. Runtime hashes, descriptors and retained source provenance identify the tested native artifact.')
    print('RUN ' + str(run))
    emit('SUMMARY', summary)
    emit('RUNTIME_HASHES_RECORDED', summary.get('hashes'))
    if summary.get('artifact') is None:
        print('ARTIFACT_PROVENANCE_ABSENT summary has no artifact descriptor/source provenance; use recorded runtime hashes when available.')
    else:
        emit('RUNTIME_ARTIFACT_RECORDED', summary['artifact'])
        if not summary['artifact'].get('provenance'):
            print('SOURCE_BUILD_PROVENANCE_ABSENT no source-build provenance recorded in summary.')
    emit('SHUTDOWN_STATUS_RECORDED', {key: summary.get(key) for key in ['serverExit', 'clientExit']})
    server_path = run / 'server.log'
    print('SERVER_RUNTIME_AND_STOP ' + str(server_path))
    if server_path.is_file():
        server = server_path.read_text()
        for line in server.splitlines():
            if any(marker in line for marker in ['Loading Paper ', 'CE-TRIGGER runtime=', 'CraftEngine provider (', 'Stopping server', 'All dimensions are saved', 'All RegionFile I/O tasks to complete']):
                print(line)
        emit('SERVER_LOG_ERRORS', [line for line in server.splitlines() if any(marker in line.lower() for marker in ['disk quota exceeded', 'no space left on device', 'ce-trigger error', 'exception'])])
        emit('SHUTDOWN_MARKERS_OBSERVED', {'stoppingServer': 'Stopping server' in server, 'allDimensionsSaved': 'All dimensions are saved' in server})
    else:
        print('SERVER_LOG_ABSENT runtime/shutdown markers unavailable')
    if log('ASSERTIONS_FROM_DRIVER', [run / 'driver.log', run.parent / (run.name + '.log')]) is None:
        print('Driver assertion text was not retained here; summary failures/exits are not a reconstructed driver log.')
    log('WIRE_PACKETS_FROM_ACTUAL_CLIENT', [run / 'client.log'])
    launch = run / 'launch.json'
    if launch.is_file():
        emit('LAUNCH_RECORDED', read_json(launch))

    # Keep full JSON objects, including future fields, rather than selecting a few snapshots.
    retained = {path.name: read_json(path) for path in sorted(run.glob('*.json')) if path.name not in ['summary.json', 'launch.json']}
    result_files = {name: data for name, data in retained.items() if isinstance(data, dict) and all(key in data for key in ['baseline', 'events', 'counts', 'inventory', 'cursor', 'entities'])}
    if 'cases' in summary:
        emit('CASE_METADATA_RECORDED', summary['cases'])
    else:
        emit('CASE_METADATA_ABSENT', {'discoveredResultCases': sorted({str(data.get('case', name[:-5])) for name, data in result_files.items()}), 'source': 'retained result JSON; no case plan recorded'})

    printed = set()
    if 'observations' in summary:
        emit('OBSERVATION_METADATA_RECORDED', summary['observations'])
        for observation in summary['observations']:
            emit('OBSERVATION', observation)
            filename = observation.get('file')
            if not isinstance(filename, str) or Path(filename).name != filename:
                record = {'observation': observation, 'error': 'require a run-local result filename'}
                gaps.append(record)
                emit('EVIDENCE_UNAVAILABLE', record)
                continue
            data = retained.get(filename)
            if filename not in retained:
                data = read_json(run / filename)
            if data is not None:
                emit('STAGE_RESULT_JSON', {'file': filename, 'result': data})
                printed.add(filename)
    else:
        print('OBSERVATION_METADATA_ABSENT legacy run; stages are not reconstructed.')
        # These are the actual retained files, not an assumed twelve-case matrix.
        if 'cases' in summary:
            for case in summary['cases']:
                filename = case['name'] + '.json'
                if filename not in result_files:
                    record = {'file': filename, 'error': 'legacy case result not retained'}
                    gaps.append(record)
                    emit('EVIDENCE_UNAVAILABLE', record)

    print('RETAINED_SERVER_OWNED_JSONL')
    print('Run-local stage copies are immutable evidence. Fixture *-before/*-result/*-rearm files retain their last write only; earlier stages are not inferred from them.')
    for filename, data in retained.items():
        if filename not in printed and data is not None:
            emit('RETAINED_RUN_JSON', {'file': filename, 'data': data})
    fixture = run / 'lane/plugins/CETriggerGate'
    fixture_files = sorted(fixture.glob('*.json'))
    if not fixture_files:
        print('SERVER_FIXTURE_RECORDS_ABSENT ' + str(fixture))
    for path in fixture_files:
        data = read_json(path)
        if data is not None:
            emit('SERVER_FIXTURE_JSON', {'file': str(path.relative_to(run)), 'data': data})
    emit('EVIDENCE_READ_GAPS', gaps)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_name')
    parser.add_argument('--run-root', type=Path, default=Path('/tmp/allium-s05-20261106/ce-triggers'))
    args = parser.parse_args()
    root = args.run_root.resolve()
    if root != Path('/tmp/allium-s05-20261106/ce-triggers') and not re.fullmatch(r'/mnt/storage/\.allium-validation/\d{8}/ce-count-fix/runs', str(root)):
        parser.error('require an owned run root')
    run = (root / args.run_name).resolve()
    if run.parent != root:
        parser.error('require an owned run name')
    render(run)


if __name__ == '__main__':
    main()
