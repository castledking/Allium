// Test-only S04 give-gate client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25670-25672 only. It joins (as GateBot unless named), stays online as a /give target,
// sends "cmd <command>" lines as a real chat command (unsigned, offline mode), and leaves on "stop".
const [lane, portText, version, username = 'GateBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25670, 25671, 25672].includes(port) || !['1.20.1', '1.21.8'].includes(version)) throw Error('TEST ONLY: owned port 25670-25672 and version 1.20.1|1.21.8');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false, loaded = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
    if (!loaded && version !== '1.20.1') { client.write('player_loaded', {}); loaded = true; }
  }
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('S04G-WIRE READY ' + version + ' port=' + port + ' user=' + username); }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('S04G-WIRE END ' + reason); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { try {
  const text = line.trim();
  if (text === 'stop') { client.end('gate finished'); return; }
  if (!ready) throw Error('wait for READY');
  if (text.startsWith('cmd ')) {
    (client.chat || client._signedChat)('/' + text.slice(4));
    console.log('S04G-WIRE SENT /' + text.slice(4));
  } else throw Error('unknown action ' + text);
} catch (e) { console.error(e); process.exitCode = 1; client.end('error'); } });
