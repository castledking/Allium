// Test-only S06c delivery client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25686-25688 only. Mode accept|decline (see pack-response.cjs); logs every pack
// offer and removal, and the disconnect reason if the server kicks it.
const {respond} = require('./pack-response.cjs');
const [lane, portText, version, mode, username = 'PackBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !(port >= 25686 && port <= 25688) || !['1.20.1', '1.21.8'].includes(version) || !['accept', 'decline'].includes(mode))
  throw Error('TEST ONLY: owned port 25686-25688, version 1.20.1|1.21.8, mode accept|decline');
const mcPath = process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol';
const mc = require(mcPath);
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(mcPath)]}));
const {flatten} = require('../baseline/text-components.cjs');
const text = c => { if (c === undefined || c === null) return null; if (typeof c === 'object' && c.type) c = nbt.simplify(c); return flatten(c).plain; };
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false, loaded = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'resource_pack_send')
    respond({phase: meta.state, uuid: null, url: packet.url, hash: packet.hash, forced: packet.forced, prompt: text(packet.promptMessage)},
      mode, false, result => client.write('resource_pack_receive', {result}));
  if (meta.name === 'add_resource_pack')
    respond({phase: meta.state, uuid: packet.uuid, url: packet.url, hash: packet.hash, forced: packet.forced, prompt: text(packet.promptMessage)},
      mode, true, result => client.write('resource_pack_receive', {uuid: packet.uuid, result}));
  if (meta.name === 'remove_resource_pack') console.log('DELIVG-PACK ' + JSON.stringify({event: 'removed', phase: meta.state, uuid: packet.uuid ?? null}));
  if (meta.name === 'kick_disconnect' || meta.name === 'disconnect') console.log('DELIVG-KICK ' + JSON.stringify({phase: meta.state, reason: text(packet.reason)}));
  if (meta.name === 'position') {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
    if (!loaded && version !== '1.20.1') { client.write('player_loaded', {}); loaded = true; }
  }
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('DELIVG-WIRE READY ' + version + ' port=' + port + ' mode=' + mode); }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('DELIVG-WIRE END ' + reason); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { if (line.trim() === 'stop') client.end('gate finished'); });
