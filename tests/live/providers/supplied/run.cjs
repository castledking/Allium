// Real 1.21.8 player inventory checks for supplied runtimes; no gameplay actions.
const fs = require('node:fs'), path = require('node:path'), {spawn} = require('node:child_process');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const {allReady, assertProviderChecks, assertReloadEvents} = require('./verification.cjs');
const [manifestPath] = process.argv.slice(2), spec = JSON.parse(fs.readFileSync(manifestPath));
const lane = path.resolve(spec.directory), port = spec.port;
if (!/^\/tmp\/allium-s05-\d{8}\/supplied\/runs\/[^/]+$/.test(lane) || ![25621, 25622].includes(port)) throw Error('Only owned disposable supplied-runtime lanes are allowed');
const delay = ms => new Promise(r => setTimeout(r, ms));
const logs = [];
async function run(cycle) {
  let output = '', closed = false, exitCode, client, forcedShutdown = false;
  const server = spawn(spec.java, ['-Xms256M', '-Xmx1G', '-Doraxen.autoDownloadDependencies=false', '-jar', spec.server,
    'nogui', '--host', '127.0.0.1', '--port', String(port)], {cwd: lane, stdio: ['pipe', 'pipe', 'pipe']});
  server.once('close', code => { closed = true; exitCode = code; });
  for (const stream of [server.stdout, server.stderr]) stream.on('data', b => { output += b; });
  async function until(test, label, timeout = 60000) {
    const end = Date.now() + timeout;
    while (!test()) {
      if (closed || Date.now() > end) throw Error(`Timed out/closed ${label}: ${output.slice(-2000)}`);
      await delay(100);
    }
  }
  async function command(prefix, action) {
    const start = output.length;
    server.stdin.write(prefix + ' ' + action + '\n');
    const marker = prefix === 'oraxengate' ? 'S05-ORAXEN-DONE ' + action
      : action === 'status' ? 'S05-STATUS'
      : action.startsWith('disable ') ? 'S05-DISABLED' : 'S05-DONE ' + action;
    await until(() => output.slice(start).includes(marker) || /S05-(ORAXEN-)?FAIL/.test(output.slice(start)), prefix + ' ' + action);
    if (/S05-(ORAXEN-)?FAIL/.test(output.slice(start))) throw Error(output.slice(start));
    return output.slice(start);
  }
  async function providersReady() {
    const deadline = Date.now() + 60000;
    do {
      const states = await command('providergate', 'status');
      if (allReady(states)) return;
      if (/=FAILED|=ABSENT/.test(states)) throw Error('Provider unavailable: ' + states);
      await delay(100);
    } while (Date.now() < deadline);
    throw Error('Providers did not become READY');
  }
  async function providerAction(action) {
    await providersReady();
    assertProviderChecks(await command('providergate', action), action);
  }
  try {
    await until(() => /Done \(/.test(output), 'boot', 180000);
    await command('oraxengate', 'status');
    if (spec.combination) {
      await providersReady();
    }
    if (cycle === 1) await command('oraxengate', 'smoke');
    client = mc.createClient({host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version: '1.21.8', hideErrors: true});
    client.on('error', e => { output += '\nCLIENT_ERROR ' + e + '\n'; });
    client.on('packet', (data, meta) => {
      if (meta.name === 'position' && data.teleportId !== undefined) client.write('teleport_confirm', {teleportId: data.teleportId});
    });
    await until(() => output.includes('GateBot joined the game'), 'actual player join', 45000);
    if (cycle === 1) {
      if (spec.combination) { await providerAction('create'); await providerAction('checkpoint'); }
      await command('oraxengate', 'create');
      await command('oraxengate', 'reload');
      await delay(1200);
      await command('oraxengate', 'verify');
      await command('oraxengate', 'nativeupdate');
      if (spec.combination) {
        const nexo = path.join(lane, 'plugins/Nexo/items/allium_gate.yml');
        fs.writeFileSync(nexo, fs.readFileSync(nexo, 'utf8').replace('PAPER', 'BOOK'));
        const ce = path.join(lane, 'plugins/CraftEngine/resources/allium_gate/configuration/items.yml');
        fs.writeFileSync(ce, "items:\n  sf_ores:silver_ingot:\n    material: gold_ingot\n    updater:\n      '2':\n        - type: reset\n          keep_components: [minecraft:custom_data]\n          keep_tags: [PublicBukkitValues]\n");
        const reloadStart = output.length;
        server.stdin.write('nexo reload items\nce reload\n');
        await providerAction('verify');
        assertReloadEvents(output.slice(reloadStart));
        await providerAction('nativeupdate');
      }
    } else {
      await command('oraxengate', 'verify');
      if (spec.combination) await providerAction('verify');
      await command('oraxengate', 'disable');
      if (spec.combination) {
        await command('providergate', 'status');
        await command('providergate', 'disable Nexo');
      }
    }
    server.stdin.write('save-all flush\n');
    await delay(1500);
    if (/Disk quota exceeded|No space left|Failed to save|CLIENT_ERROR|S05-(ORAXEN-)?FAIL/.test(output)) throw Error('Infrastructure/client/assertion failure in raw output');
    console.log(`S05-SUPPLIED-CYCLE-PASS ${spec.name} ${cycle}`);
  } finally {
    if (client) client.end('S05 complete');
    if (!closed) server.stdin.write('stop\n');
    for (let n = 0; n < 300 && !closed; n++) await delay(100);
    if (!closed) { forcedShutdown = true; server.kill('SIGTERM'); await delay(3000); }
    if (!closed) { server.kill('SIGKILL'); await delay(1000); }
    logs.push(output);
    fs.writeFileSync(path.join(lane, `boot-${cycle}.log`), output);
    if (/S05-(ORAXEN-)?FAIL/.test(output)) throw Error('Assertion failure in completed server output');
    if (forcedShutdown || !closed || exitCode !== 0) throw Error('Server failed clean shutdown: ' + exitCode);
  }
}
(async () => {
  const result = {name: spec.name, passed: false, combination: spec.combination, cycles: 2};
  try { for (const cycle of [1, 2]) await run(cycle); result.passed = true; }
  catch (error) { result.error = error.stack; process.exitCode = 1; }
  finally {
    fs.writeFileSync(path.join(lane, 'result.json'), JSON.stringify(result, null, 2));
    fs.writeFileSync(path.join(lane, 'evidence.log'), logs.join('\n').split('\n').filter(line => /\[OraxenGate\]|\[ProviderGate\]|\[Allium\]/.test(line)).join('\n') + '\n');
    console.log(JSON.stringify(result));
  }
})();
