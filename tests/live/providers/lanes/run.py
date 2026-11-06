#!/usr/bin/env python3
"""Run one explicit, local disposable lane; use a manifest to pin every input."""
import argparse
import hashlib
import json
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(spec):
    lane = Path(spec["directory"]).resolve()
    temporary = re.fullmatch(r"/tmp/allium-s05-\d{8}/lanes/runs/[^/]+", str(lane))
    archived = re.fullmatch(r"/mnt/storage/\.allium-validation/\d{8}/[a-z0-9-]+/runs/[^/]+", str(lane))
    if not temporary and not archived:
        raise ValueError("Use a fresh owned /tmp/allium-s05-YYYYMMDD/lanes/runs/ or /mnt/storage/.allium-validation/YYYYMMDD/SUITE/runs/ lane")
    port = spec["port"]
    if not 25630 <= port <= 25649:
        raise ValueError("Only reserved loopback lane ports 25630–25649 are allowed")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    lane.mkdir(parents=True, exist_ok=False)
    if spec.get("library_cache"):
        # Immutable Maven cache only; never reuse worlds, player data or plugin state.
        shutil.copytree(spec["library_cache"], lane / "libraries")
    plugins = lane / "plugins"
    plugins.mkdir()
    for key in ("allium", "fixture"):
        shutil.copy2(spec[key], plugins / ("Allium.jar" if key == "allium" else "LaneGate.jar"))
    expected = spec["providers"]
    template = Path(spec["template"])
    for provider in expected:
        shutil.copy2(spec[provider], plugins / (provider + ".jar"))
        name = {"nexo": "Nexo", "craftengine": "CraftEngine", "oraxen": "Oraxen"}[provider]
        root = plugins / name
        root.mkdir()
        settings = "settings.yml" if provider in ("nexo", "oraxen") else "config.yml"
        source_settings = Path(spec[provider + "_settings"]) if provider + "_settings" in spec else template / "plugins" / name / settings
        shutil.copy2(source_settings, root / settings)
        libs = template / "plugins" / name / "libs"
        if libs.exists():
            shutil.copytree(libs, root / "libs")
    # No credentials, keyrings, native databases, player data or world data are copied.
    if "nexo" in expected:
        path = plugins / "Nexo/items"
        path.mkdir()
        (path / "gate.yml").write_text("gate_wand:\n  material: IRON_INGOT\n  itemname: Allium Lane Gate\n")
        (plugins / "Nexo/converter.yml").write_text(
            "oraxenConverter:\n  convertItems: false\n  convertResourcePack: false\n"
            "  convertSettings: false\n  convertFurnitureOnLoad: false\n")
    if "craftengine" in expected:
        pack = plugins / "CraftEngine/resources/allium_gate"
        (pack / "configuration").mkdir(parents=True)
        (pack / "pack.yml").write_text("namespace: sf_ores\nauthor: AlliumGate\nversion: 1.0\n")
        (pack / "configuration/items.yml").write_text("items:\n  sf_ores:silver_ingot:\n    material: iron_ingot\n")
    if "oraxen" in expected:
        path = plugins / "Oraxen/items"
        path.mkdir()
        (path / "gate.yml").write_text("gate_wand:\n  material: IRON_INGOT\n  itemname: Allium Oraxen Lane Gate\n")
    (plugins / "LaneGate").mkdir()
    (plugins / "LaneGate/config.yml").write_text("expected: " + json.dumps(expected) + "\n")
    (lane / "eula.txt").write_text("eula=true\n")
    (lane / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n"
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":2}],"biome":"minecraft:plains"}\n'
        "generate-structures=false\nallow-nether=false\nmax-tick-time=60000\n"
    )
    result = {"name": spec["name"], "providers": expected, "passed": False,
              "inputs": {k: {"path": spec[k], "sha256": digest(spec[k])}
                         for k in ("server", "allium", "fixture", *expected)},
              "java": spec["java"], "scope": "native API, YAML serialization, reload and restart; no player client"}
    lines = []

    def boot():
        proc = subprocess.Popen([spec["java"], "-Xms256M", "-Xmx1G", "-jar", spec["server"],
                                 "nogui", "--host", "127.0.0.1", "--port", str(port)],
                                cwd=lane, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)
        events = queue.Queue()
        def read():
            for line in proc.stdout:
                lines.append(line.rstrip())
                events.put(line)
        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        return proc, events, reader

    def send(proc, command):
        proc.stdin.write(command + "\n")
        proc.stdin.flush()

    def wait(proc, events, marker, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = events.get(timeout=0.5)
            except queue.Empty:
                if proc.poll() is not None:
                    raise RuntimeError("Server exited before " + marker)
                continue
            if "S05-LANE-FAIL" in line:
                raise RuntimeError(line.strip())
            if marker in line:
                print(spec["name"], marker, flush=True)
                return
        raise TimeoutError(marker)

    def stop(proc, reader):
        if proc.poll() is None:
            send(proc, "stop")
            try:
                proc.wait(timeout=40)
            except subprocess.TimeoutExpired:
                proc.terminate()
                try:
                    proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=15)
                raise RuntimeError("Server required forced termination")
        reader.join(timeout=2)
        if reader.is_alive():
            raise RuntimeError("Server output was not fully drained")
        if proc.returncode != 0:
            raise RuntimeError("Server exit " + str(proc.returncode))

    proc = None
    try:
        proc, events, reader = boot()
        wait(proc, events, "S05-LANE-READY")
        send(proc, "lanegate probe")
        wait(proc, events, "S05-LANE-PROBE-PASS", 30)
        if "nexo" in expected:
            path = plugins / "Nexo/items/gate.yml"
            path.write_text(path.read_text().replace("IRON_INGOT", "GOLD_INGOT"))
            send(proc, "nexo reload items")
        if "craftengine" in expected:
            path = plugins / "CraftEngine/resources/allium_gate/configuration/items.yml"
            path.write_text("items:\n  sf_ores:silver_ingot:\n    material: gold_ingot\n")
            send(proc, "ce reload")
        if "oraxen" in expected:
            send(proc, "oraxen reload items")
        send(proc, "lanegate awaitreload")
        wait(proc, events, "S05-LANE-RELOAD-PASS")
        stop(proc, reader)
        proc, events, reader = boot()
        wait(proc, events, "S05-LANE-READY")
        send(proc, "lanegate restore")
        wait(proc, events, "S05-LANE-RESTORE-PASS", 30)
        stop(proc, reader)
        if any("S05-LANE-FAIL" in line for line in lines):
            raise RuntimeError("Assertion failure in completed server output")
        result["passed"] = True
    except Exception as failure:
        result["error"] = str(failure)
    finally:
        if proc is not None and proc.poll() is None:
            try:
                stop(proc, reader)
            except Exception as failure:
                result["shutdownError"] = str(failure)
        (lane / "complete-server.log").write_text("\n".join(lines) + "\n")
        (lane / "evidence.log").write_text("\n".join(line for line in lines if "[LaneGate]" in line or
            ("[Allium]" in line and ("provider" in line or "enabled" in line or "disabled" in line))) + "\n")
        (lane / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result["passed"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    raise SystemExit(0 if run(json.loads(args.manifest.read_text())) else 1)
