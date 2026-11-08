// Test-only S07 messaging client for 1.20.1 (763). Requires external minecraft-protocol 1.68.0. Owned loopback
// ports 25692-25693 only. Logs every system chat (flattened to text and per-character colours) and sound packet,
// sends "cmd <command>" lines as typed chat commands, "tab <text>" as a tab completion request, and leaves on "stop".
const [lane, portText, version, username = 'BaseBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !(port >= 25692 && port <= 25693) || !['1.20.1'].includes(version)) throw Error('TEST ONLY: owned port 25692-25693 and version 1.20.1');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol')]}));
const {flatten} = require('../baseline/text-components.cjs');
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (meta.name === 'declare_commands' && !ready) { ready = true; console.log('BASEG-WIRE READY ' + version + ' port=' + port + ' user=' + username); }
  if (meta.name === 'system_chat') {
    let content = packet.content;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    const raw = typeof content === 'string' ? content : JSON.stringify(content);
    console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(content), {overlay: !!(packet.isActionBar ?? packet.overlay),
      interactive: /clickEvent|hoverEvent|click_event|hover_event|"insertion"/.test(String(raw))})));
  }
  if (meta.name === 'sound_effect' || meta.name === 'entity_sound_effect') {
    // ItemSoundHolder: {soundId} for a registry sound, or {data: {soundName, fixedRange}} inline.
    const s = packet.sound || {};
    const name = s.data ? s.data.soundName : null;
    console.log('BASEG-SOUND ' + JSON.stringify({packet: meta.name, name, id: s.soundId ?? null, volume: packet.volume, pitch: packet.pitch}));
  }
  if (meta.name === 'tab_complete') console.log('MSGG-TAB ' + JSON.stringify({id: packet.transactionId, matches: (packet.matches || []).map(m => m.match)}));
});
client.on('error', e => { console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('BASEG-WIRE END ' + reason); process.exit(process.exitCode || 0); });
let tabId = 0;
require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  const text = line.replace(/\r?\n$/, '');
  if (text.trim() === 'stop') { client.end('gate finished'); return; }
  if (text.startsWith('cmd ')) { (client.chat || client._signedChat)('/' + text.slice(4)); console.log('BASEG-WIRE SENT /' + text.slice(4)); }
  if (text.startsWith('tab ')) { const id = ++tabId; client.write('tab_complete', {transactionId: id, text: '/' + text.slice(4)}); console.log('MSGG-TABREQ ' + JSON.stringify({id, text: text.slice(4)})); }
});
