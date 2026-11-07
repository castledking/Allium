// Test-only S06b pack gate client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25660-25662 only. Mode accept|decline; see pack-offer.cjs.
const fs = require('node:fs');
const {handleOffer} = require('./pack-offer.cjs');
const [lane, portText, version, mode, configPath] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25660, 25661, 25662].includes(port) || !['1.20.1', '1.21.8'].includes(version) || !['accept', 'decline'].includes(mode))
  throw Error('TEST ONLY: owned port 25660-25662, version 1.20.1|1.21.8, mode accept|decline');
const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const client = mc.createClient({host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version});
let ready = false, loaded = false, offers = 0;
const big = (k, v) => typeof v === 'bigint' ? v.toString() : v;
function logSlot(where, slot, item) {
  const text = JSON.stringify(item, big);
  if (text && /custom_model_data|CustomModelData/.test(text)) console.log('S06B-SLOT ' + JSON.stringify({where, slot, item: JSON.parse(text)}));
}
client.on('packet', (packet, meta) => {
  if (meta.name === 'resource_pack_send')
    handleOffer({url: packet.url, hash: packet.hash, uuid: null, mode, cfg, downloadedStatus: false, offerIndex: `${mode}-${offers++}`},
      result => client.write('resource_pack_receive', {result}));
  if (meta.name === 'add_resource_pack')
    handleOffer({url: packet.url, hash: packet.hash, uuid: packet.uuid, mode, cfg, downloadedStatus: true, offerIndex: `${mode}-${offers++}`},
      result => client.write('resource_pack_receive', {uuid: packet.uuid, result}));
  if (meta.name === 'window_items') packet.items.forEach((item, i) => logSlot('window_items', i, item));
  if (meta.name === 'set_slot') logSlot('set_slot', packet.slot, packet.item);
  if (meta.name === 'set_player_inventory') logSlot('set_player_inventory', packet.slotId, packet.contents);
  if (meta.name === 'position') {
    client.write('teleport_confirm', {teleportId: packet.teleportId});
    if (!loaded && version !== '1.20.1') { client.write('player_loaded', {}); loaded = true; }
  }
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('S06B-WIRE READY ' + version + ' port=' + port + ' mode=' + mode); }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('S06B-WIRE END ' + reason); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  if (line.trim() === 'stop') client.end('gate finished');
});
