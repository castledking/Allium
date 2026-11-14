// Test-only S17 moderation client for 1.20.1 (763). Owned loopback ports 25770-25771 only.
// Supports "cmd <command>", "say <message>", "tab <text>", and "stop".
const [lane, portText, version, username = 'BaseBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !([25770, 25771].includes(port)) || !['1.20.1'].includes(version)) throw Error('TEST ONLY: owned port 25770-25771 and version 1.20.1');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol');
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol')]}));
const {flatten} = require('../baseline/text-components.cjs');
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false;
client.on('packet', (packet, meta) => {
  if (meta.name === 'position') client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (meta.name === 'declare_commands' && !ready) { ready = true; client.write('settings', {locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true}); console.log('BASEG-WIRE READY ' + version + ' port=' + port + ' user=' + username); }
  if (meta.name === 'system_chat') {
    let content = packet.content;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    const raw = typeof content === 'string' ? content : JSON.stringify(content);
    console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(content), {overlay: !!(packet.isActionBar ?? packet.overlay),
      interactive: /clickEvent|hoverEvent|click_event|hover_event|"insertion"/.test(String(raw))})));
  }
  if (meta.name === 'player_chat') {
    const plain = packet.plainMessage || '';
    console.log('BASEG-CHAT ' + JSON.stringify({plain, overlay: false, interactive: false}));
  }
  if (meta.name === 'tab_complete') console.log('MSGG-TAB ' + JSON.stringify({id: packet.transactionId, matches: (packet.matches || []).map(m => m.match)}));
});
client.on('error', e => { console.log((['ECONNRESET','EPIPE'].includes(e.code) ? 'MODG-TRANSPORT ' : 'MODG-CLIENTERROR ') + JSON.stringify({code:e.code,message:e.message})); console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('BASEG-WIRE END ' + reason); process.exit(process.exitCode || 0); });
let tabId = 0;
require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  const text = line.replace(/\r?\n$/, '');
  if (text.trim() === 'stop') { client.end('gate finished'); return; }
  if (text.startsWith('cmd ')) { (client.chat || client._signedChat)('/' + text.slice(4)); console.log('BASEG-WIRE SENT /' + text.slice(4)); }
  if (text.startsWith('say ')) { (client.chat || client._signedChat)(text.slice(4)); console.log('BASEG-WIRE SAY ' + text.slice(4)); }
  if (text.startsWith('tab ')) { const id = ++tabId; client.write('tab_complete', {transactionId: id, text: '/' + text.slice(4)}); console.log('MSGG-TABREQ ' + JSON.stringify({id, text: text.slice(4)})); }
});
