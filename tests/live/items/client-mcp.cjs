// Test-only S04 give-gate client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25670-25672 only. It joins as GateBot, stays online as a /give target, and leaves on "stop".
const [lane, portText, version] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25670, 25671, 25672].includes(port) || !['1.20.1', '1.21.8'].includes(version)) throw Error('TEST ONLY: owned port 25670-25672 and version 1.20.1|1.21.8');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const client = mc.createClient({host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version});
let ready = false, loaded = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
    if (!loaded && version !== '1.20.1') { client.write('player_loaded', {}); loaded = true; }
  }
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('S04G-WIRE READY ' + version + ' port=' + port); }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('S04G-WIRE END ' + reason); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { if (line.trim() === 'stop') client.end('gate finished'); });
