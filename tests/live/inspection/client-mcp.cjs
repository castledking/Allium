// Test-only S24 inspection client for 1.20.1 (763). Owned loopback ports 25800-25802 only.
// Supports "cmd <command>", "tab <text>", "click <slot> [button] [mode]", "drag <s1> [s2...]", "close", and "stop".
const [lane, portText, version, username = 'InspBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !([25800, 25801, 25802].includes(port)) || !['1.20.1'].includes(version)) {
  throw Error('TEST ONLY: owned port 25800-25802 and version 1.20.1');
}
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol');
const nbt = require(require.resolve('prismarine-nbt', {paths: [require.resolve(process.env.MC_PROTOCOL_MODULE || '/mnt/storage/.allium-validation/infra/client/node_modules/minecraft-protocol')]}));
const {flatten} = require('../baseline/text-components.cjs');

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

client.on('packet', (packet, meta) => {
  if (meta.name === 'position') client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (meta.name === 'respawn') {
    client.write('settings', {locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true});
  }
  if (meta.name === 'declare_commands' && !ready) {
    ready = true;
    client.write('settings', {locale: 'en_US', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true});
    console.log('INSP-WIRE READY ' + version + ' port=' + port + ' user=' + username);
  }
  if (meta.name === 'system_chat') {
    let content = packet.content;
    if (content && typeof content === 'object' && content.type) content = nbt.simplify(content);
    console.log('INSP-CHAT ' + JSON.stringify(Object.assign(flatten(content), extractTranslate(content), {overlay: !!(packet.isActionBar ?? packet.overlay)})));
  }
  if (meta.name === 'tab_complete') {
    console.log('INSP-TAB ' + JSON.stringify({id: packet.transactionId, matches: (packet.matches || []).map(m => m.match)}));
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
    const count = (packet.items || []).length;
    console.log('INSP-CONTENT ' + JSON.stringify({windowId: packet.windowId, stateId: packet.stateId, count}));
  }
  if (meta.name === 'set_slot') {
    if (packet.stateId !== undefined) stateIds[packet.windowId] = packet.stateId;
    console.log('INSP-SLOT ' + JSON.stringify({windowId: packet.windowId, stateId: packet.stateId, slot: packet.slot, present: !!(packet.item && packet.item.present)}));
  }
  if (meta.name === 'close_window') {
    console.log('INSP-CLOSE ' + JSON.stringify({windowId: packet.windowId}));
    if (packet.windowId === openWindowId) openWindowId = 0;
  }
});

client.on('error', e => {
  console.log((['ECONNRESET', 'EPIPE'].includes(e.code) ? 'INSP-TRANSPORT ' : 'INSP-CLIENTERROR ') + JSON.stringify({code: e.code, message: e.message}));
  console.error(e);
  process.exitCode = 1;
});
client.on('end', reason => { console.log('INSP-WIRE END ' + reason); process.exit(process.exitCode || 0); });

let tabId = 0;
function sendClick(slot, mouseButton = 0, mode = 0) {
  const wid = openWindowId;
  const sid = stateIds[wid] || 0;
  client.write('window_click', {
    windowId: wid,
    stateId: sid,
    slot,
    mouseButton,
    mode,
    changedSlots: [],
    cursorItem: {present: false}
  });
  console.log('INSP-CLICK ' + JSON.stringify({windowId: wid, stateId: sid, slot, button: mouseButton, mode}));
}

require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  const text = line.replace(/\r?\n$/, '');
  if (text.trim() === 'stop') { client.end('gate finished'); return; }
  if (!ready) return;
  if (text.startsWith('cmd ')) {
    (client.chat || client._signedChat)('/' + text.slice(4));
    console.log('INSP-WIRE SENT /' + text.slice(4));
  } else if (text.startsWith('tab ')) {
    const id = ++tabId;
    client.write('tab_complete', {transactionId: id, text: '/' + text.slice(4)});
    console.log('INSP-TABREQ ' + JSON.stringify({id, text: text.slice(4)}));
  } else if (text.startsWith('click ')) {
    const [slotText, btnText = '0', modeText = '0'] = text.slice(6).trim().split(/\s+/);
    sendClick(Number(slotText), Number(btnText), Number(modeText));
  } else if (text.startsWith('drag ')) {
    const slots = text.slice(5).trim().split(/\s+/).map(Number);
    sendClick(-999, 0, 5);
    for (const s of slots) sendClick(s, 1, 5);
    sendClick(-999, 2, 5);
  } else if (text.trim() === 'close') {
    if (openWindowId !== 0) {
      const wid = openWindowId;
      client.write('close_window', {windowId: wid});
      openWindowId = 0;
      console.log('INSP-CLOSE-SENT ' + JSON.stringify({windowId: wid}));
    }
  }
});
