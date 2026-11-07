// Test-only real protocol client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25650-25652 only. One "click" emulates a vanilla right-click on an entity whose
// interaction passes client-side: INTERACT_AT then INTERACT for the main hand, then the same for the off hand.
const [lane, portText, version] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25650, 25651, 25652].includes(port) || !['1.20.1', '1.21.8'].includes(version)) throw Error('TEST ONLY: owned port 25650-25652 and version 1.20.1|1.21.8');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const client = mc.createClient({host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version});
let ready = false, loaded = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
    if (!loaded && version !== '1.20.1') { client.write('player_loaded', {}); loaded = true; }
    console.log('S06A-WIRE TELEPORT_ACK ' + packet.teleportId);
  }
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('S06A-WIRE READY ' + version + ' port=' + port); }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('S06A-WIRE END ' + reason); process.exit(process.exitCode || 0); });
function click(target) {
  for (const hand of [0, 1]) {
    client.write('use_entity', {target, mouse: 2, x: 0, y: 1, z: 0, hand, sneaking: false});
    client.write('use_entity', {target, mouse: 0, hand, sneaking: false});
  }
}
require('node:readline').createInterface({input: process.stdin}).on('line', line => { try {
  const [action, arg] = line.trim().split(/\s+/);
  if (action === 'stop') { client.end('gate finished'); return; }
  if (!ready) throw Error('wait for READY');
  const target = Number(arg);
  if (!Number.isInteger(target)) throw Error('entity id required');
  if (action === 'click') click(target);
  else if (action === 'double') { click(target); click(target); }
  else throw Error('unknown action ' + action);
  console.log('S06A-WIRE SEND ' + action + ' entity=' + target);
} catch (e) { console.error(e); process.exitCode = 1; client.end('error'); } });
