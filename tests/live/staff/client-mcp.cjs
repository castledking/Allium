// Test-only staff tooling gate client for 1.20.1 (763). Owned loopback ports 25860-25862 only.
// Supports cmd, say, tab, look, move, signuse/signedit/signbreak, click <slot> [button] [mode], drag, close, stop.
const [lane, portText, version, username = 'BaseBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !([25860, 25861, 25862].includes(port)) || !['1.20.1'].includes(version)) throw Error('TEST ONLY: owned port 25860-25862 and version 1.20.1');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol');
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol')]}));
const {flatten} = require('./text-components.cjs');
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
function extractTranslate(raw) {
  let obj = raw;
  if (typeof obj === 'string') { try { obj = JSON.parse(obj); } catch (_) { return {}; } }
  if (!obj || typeof obj !== 'object' || !obj.translate) return {};
  const args = Array.isArray(obj.with) ? obj.with.map(w => flatten(w).plain) : [];
  return {translate: obj.translate, withPlain: args};
}
const client = mc.createClient({host: '127.0.0.1', port, username, auth: 'offline', version});
let ready = false;
let openWindowId = 0;
const stateIds = {};
let pos = {x: 0, y: 64, z: 0};
client.on('packet', (packet, meta) => {
  if (meta.name === 'sound_effect' || meta.name === 'entity_sound_effect') {
    // ItemSoundHolder: {soundId} for a registry sound, or {data: {soundName, fixedRange}} inline.
    const s = packet.sound || {};
    console.log('BASEG-SOUND ' + JSON.stringify({packet: meta.name, name: s.data ? s.data.soundName : null, id: s.soundId ?? null,
      volume: packet.volume, pitch: packet.pitch}));
  }
  if (meta.name === 'teams') {
    console.log('BASEG-TEAM ' + JSON.stringify({team: packet.team, mode: packet.mode, color: packet.formatting ?? null, players: packet.players || []}));
  }
  if (meta.name === 'action_bar') {
    let content = packet.text;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(content), {overlay: true, actionBar: true, interactive: false})));
  }
  if (meta.name === 'position') {
    pos = {x: packet.x, y: packet.y, z: packet.z};
    client.write('teleport_confirm', {teleportId: packet.teleportId});
  }
  if (meta.name === 'respawn') { client.write('settings', {locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true}); }
  if (meta.name === 'declare_commands' && !ready) { ready = true; client.write('settings', {locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true}); console.log('BASEG-WIRE READY ' + version + ' port=' + port + ' user=' + username); }
  if (meta.name === 'system_chat') {
    let content = packet.content;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    const raw = typeof content === 'string' ? content : JSON.stringify(content);
    console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(content), extractTranslate(content), {overlay: !!(packet.isActionBar ?? packet.overlay),
      rawComponent: content, interactive: /clickEvent|hoverEvent|click_event|hover_event|"insertion"/.test(String(raw))})));
  }
  if (meta.name === 'open_window') {
    openWindowId = packet.windowId;
    stateIds[openWindowId] = stateIds[openWindowId] || 0;
    let title = packet.windowTitle;
    if (title && typeof title === 'object' && title.type) title = nbt.simplify(title);
    console.log('INSP-OPEN ' + JSON.stringify({windowId: packet.windowId, inventoryType: packet.inventoryType, title: flatten(title).plain}));
  }
  if (meta.name === 'window_items') {
    if (packet.stateId !== undefined) stateIds[packet.windowId] = packet.stateId;
    console.log('INSP-CONTENT ' + JSON.stringify({windowId: packet.windowId, stateId: packet.stateId, count: (packet.items || []).length}));
  }
  if (meta.name === 'set_slot') {
    if (packet.stateId !== undefined) stateIds[packet.windowId] = packet.stateId;
  }
  if (meta.name === 'close_window') {
    console.log('INSP-CLOSE ' + JSON.stringify({windowId: packet.windowId}));
    if (packet.windowId === openWindowId) openWindowId = 0;
  }
  if (meta.name === 'open_sign_entity') console.log('SIGN-EDITOR opened');
  if (meta.name === 'player_chat') {
    const plain = packet.plainMessage || '';
    console.log('BASEG-CHAT ' + JSON.stringify({plain, overlay: false, interactive: false}));
  }
  if (meta.name === 'kick_disconnect' || meta.name === 'disconnect') {
    let reason = packet.reason;
    if (reason && typeof reason === 'object' && reason.type) reason = nbt.simplify(reason);
    console.log('DISC-GKICK ' + JSON.stringify(flatten(reason)));
  }
  if (meta.name === 'tab_complete') console.log('MSGG-TAB ' + JSON.stringify({id: packet.transactionId, matches: (packet.matches || []).map(m => m.match)}));
});
client.on('error', e => { console.log((['ECONNRESET','EPIPE'].includes(e.code) ? 'DISC-GTRANSPORT ' : 'DISC-GCLIENTERROR ') + JSON.stringify({code:e.code,message:e.message})); console.error(e); process.exitCode = 1; });
client.on('end', reason => { console.log('BASEG-WIRE END ' + reason); process.exit(process.exitCode || 0); });
let tabId = 0;
function sendClick(slot, mouseButton = 0, mode = 0) {
  const wid = openWindowId, sid = stateIds[wid] || 0;
  client.write('window_click', {windowId: wid, stateId: sid, slot, mouseButton, mode, changedSlots: [], cursorItem: {present: false}});
  console.log('INSP-CLICK ' + JSON.stringify({windowId: wid, stateId: sid, slot, button: mouseButton, mode}));
}
require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  const text = line.replace(/\r?\n$/, '');
  if(text.startsWith('signuse ')){
    const [x,y,z,hand=0]=text.slice(8).split(/\s+/).map(Number);
    client.write('block_place',{hand,location:{x,y,z},direction:3,cursorX:.5,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:0});console.log('SIGN-SENT use');
  }
  if(text.startsWith('signedit ')){
    const data=JSON.parse(text.slice(9));client.write('update_sign',{location:data.location,isFrontText:data.front,text1:data.lines[0],text2:data.lines[1],text3:data.lines[2],text4:data.lines[3]});console.log('SIGN-SENT edit');
  }
  if(text.startsWith('signbreak ')){
    const [x,y,z]=text.slice(10).split(/\s+/).map(Number);client.write('block_dig',{status:0,location:{x,y,z},face:3,sequence:0});console.log('SIGN-SENT break');
  }
  if (text.trim() === 'stop') { client.end('gate finished'); return; }
  if (text.startsWith('click ')) { const [s, b = '0', m = '0'] = text.slice(6).trim().split(/\s+/); sendClick(Number(s), Number(b), Number(m)); }
  if (text.startsWith('drag ')) { const slots = text.slice(5).trim().split(/\s+/).map(Number); sendClick(-999, 0, 5); for (const s of slots) sendClick(s, 1, 5); sendClick(-999, 2, 5); }
  if (text.trim() === 'close' && openWindowId !== 0) { const wid = openWindowId; client.write('close_window', {windowId: wid}); openWindowId = 0; console.log('INSP-CLOSE-SENT ' + JSON.stringify({windowId: wid})); }
  if (text.startsWith('cmd ')) { (client.chat || client._signedChat)('/' + text.slice(4)); console.log('BASEG-WIRE SENT /' + text.slice(4)); }
  if (text.startsWith('say ')) { (client.chat || client._signedChat)(text.slice(4)); console.log('BASEG-WIRE SAY ' + text.slice(4)); }
  if (text.startsWith('tab ')) { const id = ++tabId; client.write('tab_complete', {transactionId: id, text: '/' + text.slice(4)}); console.log('MSGG-TABREQ ' + JSON.stringify({id, text: text.slice(4)})); }
  if (text.startsWith('look ')) {
    const [yaw, pitch] = text.slice(5).trim().split(/\s+/).map(Number);
    client.write('look', {yaw, pitch, onGround: true});
    console.log('DISC-GLOOK ' + JSON.stringify({yaw, pitch}));
  }
  if (text.startsWith('move ')) {
    const [dx, dz] = text.slice(5).trim().split(/\s+/).map(Number);
    pos.x += dx; pos.z += dz;
    client.write('position', {x: pos.x, y: pos.y, z: pos.z, onGround: true});
    console.log('DISC-GMOVED ' + JSON.stringify(pos));
  }
});
