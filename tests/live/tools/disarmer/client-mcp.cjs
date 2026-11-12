// Test-only real protocol client for 1.20.1 (763) and 1.21.8 (772). Requires external minecraft-protocol 1.68.0.
// Owned loopback ports 25650-25652 only. One "click" emulates a vanilla right-click on an entity whose
// interaction passes client-side: INTERACT_AT then INTERACT for the main hand, then the same for the off hand.
const [lane, portText, version] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25650, 25651, 25652].includes(port) || !['1.20.1', '1.21.8'].includes(version)) throw Error('TEST ONLY: owned port 25650-25652 and version 1.20.1|1.21.8');
const mc = require(process.env.MC_PROTOCOL_MODULE || '/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const client = mc.createClient({host: '127.0.0.1', port, username: 'GateBot', auth: 'offline', version});
let ready = false, loaded = false, entityId = 0, windowId = 0, stateId = 0;
client.on('packet', (packet, meta) => {
  if (meta.name === 'login') entityId = packet.entityId;
  if (meta.name === 'open_window') { windowId = packet.windowId; console.log('S06A-WIRE WINDOW ' + windowId + ' ' + packet.inventoryType); }
  if ((meta.name === 'window_items' || meta.name === 'set_slot') && packet.stateId !== undefined) stateId = packet.stateId;
  if (meta.name === 'system_chat') console.log('S06A-WIRE CHAT ' + JSON.stringify(packet.content || packet.formattedMessage || packet));
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
let sequence = 1;
// Right-click on air with the main hand: the vanilla client sends a use-item packet for any held item.
function use() {
  client.write('arm_animation', {hand: 0});
  client.write('use_item', version === '1.20.1' ? {hand: 0, sequence: sequence++} : {hand: 0, sequence: sequence++, rotation: {x: 0, y: 0}});
}
// Start breaking a block from above; with an instant-mine tool the server breaks it on this packet alone.
function dig(x, y, z) {
  client.write('block_dig', {status: 0, location: {x, y, z}, face: 1, sequence: sequence++});
  client.write('arm_animation', {hand: 0});
}
// Mines a block for real: start, wait for the server-side break progress, then finish.
function mine(x, y, z, ms) {
  client.write('block_dig', {status: 0, location: {x, y, z}, face: 1, sequence: sequence++});
  client.write('arm_animation', {hand: 0});
  setTimeout(() => {
    client.write('block_dig', {status: 2, location: {x, y, z}, face: 1, sequence: sequence++});
    console.log('S06A-WIRE SEND mined ' + [x, y, z].join(' '));
  }, ms);
}
// Right-clicks the top face of a block with the main hand.
function useOn(x, y, z) {
  const packet = {location: {x, y, z}, direction: 1, hand: 0, cursorX: 0.5, cursorY: 1, cursorZ: 0.5, insideBlock: false, sequence: sequence++};
  if (version !== '1.20.1') packet.worldBorderHit = false;
  client.write('block_place', packet);
  client.write('arm_animation', {hand: 0});
}
const extra = {
  mine: ([x, y, z, ms]) => mine(x, y, z, ms || 1500),
  sneak: ([on]) => client.write('entity_action', {entityId, actionId: on ? 0 : 1, jumpBoost: 0}),
  slot: ([n]) => client.write('held_item_slot', {slotId: n}),
  useon: ([x, y, z]) => useOn(x, y, z),
  // A shift-click (mode 1) or pickup (mode 0) of a slot in the open window.
  wclick: ([slot, mode]) => client.write('window_click', {windowId, stateId, slot, mouseButton: 0, mode: mode || 0, changedSlots: [], cursorItem: {present: false}}),
  enchant: ([n]) => client.write('enchant_item', {windowId, enchantment: n}),
};
require('node:readline').createInterface({input: process.stdin}).on('line', line => { try {
  const [action, ...args] = line.trim().split(/\s+/);
  if (action === 'stop') { client.end('gate finished'); return; }
  if (!ready) throw Error('wait for READY');
  if (action === 'use') { use(); console.log('S06A-WIRE SEND use'); return; }
  if (extra[action]) {
    const nums = args.map(v => v === 'on' ? 1 : v === 'off' ? 0 : Number(v));
    if (!nums.every(Number.isInteger)) throw Error(action + ' needs integer arguments');
    extra[action](nums);
    console.log('S06A-WIRE SEND ' + action + ' ' + args.join(' '));
    return;
  }
  if (action === 'dig') {
    const [x, y, z] = args.map(Number);
    if (![x, y, z].every(Number.isInteger)) throw Error('dig x y z');
    dig(x, y, z);
    console.log('S06A-WIRE SEND dig ' + [x, y, z].join(' '));
    return;
  }
  const target = Number(args[0]);
  if (!Number.isInteger(target)) throw Error('entity id required');
  if (action === 'click') click(target);
  else if (action === 'double') { click(target); click(target); }
  else throw Error('unknown action ' + action);
  console.log('S06A-WIRE SEND ' + action + ' entity=' + target);
} catch (e) { console.error(e); process.exitCode = 1; client.end('error'); } });
