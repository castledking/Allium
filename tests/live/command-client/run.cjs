// Validation-only: an authenticated offline protocol connection to loopback disposable servers.
const mc = require(process.env.MCPROTOCOL_MODULE || 'minecraft-protocol');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const [version, lane, java, jar, portText] = process.argv.slice(2);
if (!version || !lane || !java || !jar || !portText) throw Error('Usage: run.cjs <version> <disposable-lane> <java> <server.jar> <port>');
const port = Number(portText);
if (port < 25589 || port > 25596) throw Error('Use a reserved disposable gate port, never production');
let output = '', client, treeCount = 0;
const trees = [], suggestions = new Map(), errors = [];
const result = { version, lane, port, passed: false, trees, suggestions: {}, errors };
if (version === '26.3') throw Error('Use raw-26.cjs with packet IDs from the exact running server');

const server = spawn(java, ['-Xms256M', '-Xmx1G', '-jar', jar, 'nogui', '--host', '127.0.0.1', '--port', String(port)], { cwd: lane, stdio: ['pipe', 'pipe', 'pipe'] });
let closed = false;
server.once('close', () => { closed = true; });
const stream = fs.createWriteStream(path.join(lane, 'network-gate-server.log'));
for (const channel of [server.stdout, server.stderr]) channel.on('data', b => { output += b.toString(); stream.write(b); });
const delay = ms => new Promise(r => setTimeout(r, ms));
async function until(predicate, label, timeout = 20000) {
  const end = Date.now() + timeout;
  while (!predicate()) { if (Date.now() > end || closed) throw Error(`Timed out/closed: ${label}; ${output.slice(-1600)}`); await delay(50); }
}
function command(text) { server.stdin.write(text + '\n'); }
async function suggest(id, text) {
  client.write('tab_complete', { transactionId: id, text });
  await until(() => suggestions.has(id), 'suggestions ' + text);
  const matches = suggestions.get(id).matches.map(m => m.match);
  result.suggestions[id + ':' + text] = matches;
  return matches;
}
(async () => {
  try {
    await until(() => /Done \(/.test(output), 'server boot', 120000);
    client = mc.createClient({ host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version, hideErrors: true });
    client.on('error', e => errors.push(e.message));
    client.on('packet', (data, meta) => {
      if (meta.name === 'declare_commands') {
        const names = data.nodes.map(n => n.name || n.extraNodeData?.name).filter(Boolean);
        trees.push({ index: ++treeCount, names });
      }
      if (meta.name === 'tab_complete') suggestions.set(data.transactionId, data);
      if (meta.name === 'position' && data.teleportId !== undefined) client.write('teleport_confirm', { teleportId: data.teleportId });
    });
    await until(() => output.includes('GateBot joined the game'), 'real player login', 45000);
    command('op GateBot');
    await until(() => trees.some(t => t.names.includes('allium:gm')), 'operator command-tree packet');
    if (!(await suggest(1, '/gm c')).includes('creative')) throw Error('Missing gm creative completion before yield');
    if (!(await suggest(2, '/allium:gm c')).includes('creative')) throw Error('Missing namespaced completion before yield');
    command('clientgate install before');
    await until(() => output.includes('CLIENTGATE_DONE before'), 'competitor installation');
    const before = treeCount;
    command('clientgate yield after');
    await until(() => output.includes('CLIENTGATE_DONE after'), 'bridge yield');
    await until(() => treeCount > before && !trees.at(-1).names.includes('allium:gm'), 'updated wire command tree');
    if (trees.at(-1).names.includes('allium:gamemode')) throw Error('Disabled canonical namespaced root still advertised');
    if (!(await suggest(3, '/gm c')).includes('competitor-creative')) throw Error('Competitor completion not visible after yield');
    result.beforeTreeIndex = before;
    result.afterTreeIndex = treeCount;
    result.serverReport = JSON.parse(fs.readFileSync(path.join(lane, 'plugins/ClientGate/state-after.json'), 'utf8'));
    if (!result.serverReport.yieldReturned) throw Error('Bridge reported yield failure');
    result.passed = true;
    console.log(JSON.stringify({ version, passed: true, commandTrees: treeCount, suggestions: result.suggestions, access: result.serverReport.access }));
  } catch (e) {
    result.failure = e.stack;
    console.error(JSON.stringify({ version, passed: false, failure: e.message, errors }));
    process.exitCode = 1;
  } finally {
    if (client) client.end('validation finished');
    if (!closed) command('stop');
    for (let n=0; !closed && n<100; n++) await delay(100);
    if (!closed) server.kill('SIGTERM');
    fs.writeFileSync(path.join(lane, 'network-gate-result.json'), JSON.stringify(result, null, 2));
    stream.end();
  }
})();
