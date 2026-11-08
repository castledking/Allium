// Test-only baseline client for 1.20.1 (763) and 1.20.6 (766). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25660-25664 only. Joins as the named bot, logs every system chat (flattened to
// text and per-character colours) and sound packet, and leaves on "stop".
const [lane, portText, version, username = 'BaseBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !(port >= 25660 && port <= 25664) || !['1.20.1', '1.20.6'].includes(version)) throw Error('TEST ONLY: owned port 25660-25664 and version 1.20.1|1.20.6');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol')]}));
const {flatten} = require('./text-components.cjs');
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('BASEG-WIRE READY ' + version + ' port=' + port + ' user=' + username); }
  if (meta.name === 'system_chat') {
    let content = packet.content;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(content), {overlay: !!(packet.isActionBar ?? packet.overlay)})));
  }
  if (meta.name === 'sound_effect' || meta.name === 'entity_sound_effect') {
    // ItemSoundHolder: {soundId} for a registry sound, or {data: {soundName, fixedRange}} inline.
    const s = packet.sound || {};
    const name = s.data ? s.data.soundName : null;
    console.log('BASEG-SOUND ' + JSON.stringify({packet: meta.name, name, id: s.soundId ?? null, volume: packet.volume, pitch: packet.pitch}));
  }
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('BASEG-WIRE END ' + reason); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { if (line.trim() === 'stop') client.end('gate finished'); });
