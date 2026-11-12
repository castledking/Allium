// Test-only protocol 777 (Paper 26.3) client with no npm dependencies; packet IDs come from the exact
// running server's catalog (DisarmerGate wire-ids.json). Owned loopback ports 25650-25652 only.
// 26.x merged INTERACT_AT into ServerboundInteractPacket(entityId VarInt, hand VarInt, location LpVec3, sneaking bool);
// a zero location encodes as the single byte 0. One "click" sends the main hand, then the off hand, as vanilla does
// when the main-hand interaction passes client-side.
const net = require('node:net'), z = require('node:zlib'), fs = require('node:fs'), crypto = require('node:crypto');
const [lane, portText] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25650, 25651, 25652].includes(port)) throw Error('TEST ONLY: require owned port 25650-25652');
const ids = JSON.parse(fs.readFileSync(lane + '/plugins/DisarmerGate/wire-ids.json')).wirePackets;
function vi(n) { const a = []; do { let b = n & 127; n >>>= 7; if (n) b |= 128; a.push(b); } while (n); return Buffer.from(a); }
function read(b, o = 0) { let n = 0, s = 0; for (let i = o; i < b.length; i++) { const c = b[i]; n |= (c & 127) << s; if (!(c & 128)) return [n, i + 1]; s += 7; if (s > 35) throw Error('bad varint'); } return null; }
function str(s) { const b = Buffer.from(s); return Buffer.concat([vi(b.length), b]); }
let state = 'login', compressed = false, buffer = Buffer.alloc(0), ready = false;
const socket = net.connect({host: '127.0.0.1', port});
function send(id, data = Buffer.alloc(0)) { let body = Buffer.concat([vi(id), data]); if (compressed) body = Buffer.concat([vi(0), body]); socket.write(Buffer.concat([vi(body.length), body])); }
function named(flow, name, data) { const id = ids[flow]['minecraft:' + name]; if (id === undefined) throw Error('Unknown packet ' + flow + '/' + name); send(id, data); }
function game(name, data) { named('game_SERVERBOUND', name, data); }
socket.on('connect', () => {
  const p = Buffer.alloc(2); p.writeUInt16BE(port); send(0, Buffer.concat([vi(777), str('127.0.0.1'), p, vi(2)]));
  const uuid = crypto.createHash('md5').update('OfflinePlayer:GateBot').digest(); uuid[6] = (uuid[6] & 15) | 48; uuid[8] = (uuid[8] & 63) | 128;
  named('login_SERVERBOUND', 'hello', Buffer.concat([str('GateBot'), uuid]));
});
socket.on('data', chunk => { try {
  buffer = Buffer.concat([buffer, chunk]);
  while (true) {
    const length = read(buffer); if (!length || buffer.length < length[1] + length[0]) break;
    let body = buffer.subarray(length[1], length[1] + length[0]); buffer = buffer.subarray(length[1] + length[0]);
    if (compressed) { const [n, start] = read(body); body = n ? z.inflateSync(body.subarray(start)) : body.subarray(start); }
    const [id, start] = read(body), payload = body.subarray(start);
    if (state === 'login') {
      if (id === ids.login_CLIENTBOUND['minecraft:login_compression']) { compressed = true; continue; }
      if (id === ids.login_CLIENTBOUND['minecraft:login_finished']) { state = 'configuration'; named('login_SERVERBOUND', 'login_acknowledged'); continue; }
      if (id === ids.login_CLIENTBOUND['minecraft:login_disconnect']) throw Error('Login rejected ' + payload.toString('hex'));
    } else if (state === 'configuration') {
      if (id === ids.configuration_CLIENTBOUND['minecraft:select_known_packs']) named('configuration_SERVERBOUND', 'select_known_packs', vi(0));
      if (id === ids.configuration_CLIENTBOUND['minecraft:cookie_request']) named('configuration_SERVERBOUND', 'cookie_response', Buffer.concat([payload, Buffer.from([0])]));
      if (id === ids.configuration_CLIENTBOUND['minecraft:keep_alive']) named('configuration_SERVERBOUND', 'keep_alive', payload);
      if (id === ids.configuration_CLIENTBOUND['minecraft:finish_configuration']) { named('configuration_SERVERBOUND', 'finish_configuration'); state = 'game'; console.log('S06A-WIRE PLAY protocol777 port=' + port); }
      if (id === ids.configuration_CLIENTBOUND['minecraft:disconnect']) throw Error('Configuration disconnected ' + payload.toString('hex'));
    } else {
      if (id === ids.game_CLIENTBOUND['minecraft:keep_alive']) game('keep_alive', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:player_position']) {
        const [tp, o] = read(payload);
        // 26.x acknowledges with the id plus the absolute position and rotation it received.
        game('accept_teleportation', Buffer.concat([vi(tp), payload.subarray(o, o + 24), payload.subarray(o + 48, o + 56)]));
        // As a vanilla client does after a teleport: report the new position, standing on the ground
        // (ServerboundMovePlayerPacket.PosRot: x, y, z, yRot, xRot, flags; 1 = on ground).
        if (ready) { game('move_player_pos_rot', Buffer.concat([payload.subarray(o, o + 24), payload.subarray(o + 48, o + 56), Buffer.from([1])])); game('client_tick_end'); }
        console.log('S06A-WIRE TELEPORT_ACK ' + tp + ' ' + JSON.stringify([payload.readDoubleBE(o), payload.readDoubleBE(o + 8), payload.readDoubleBE(o + 16)]));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:commands'] && !ready) { ready = true; game('player_loaded'); game('client_tick_end'); console.log('S06A-WIRE READY protocol777 port=' + port); }
      if (id === ids.game_CLIENTBOUND['minecraft:disconnect']) throw Error('Play disconnected ' + payload.toString('hex'));
    }
  }
} catch (e) { console.error(e.stack); process.exitCode = 1; socket.destroy(); } });
socket.on('error', e => { console.error(e); process.exitCode = 1; });
socket.on('close', () => process.exit(process.exitCode || 0));
function click(target) {
  for (const hand of [0, 1]) game('interact', Buffer.concat([vi(target), vi(hand), Buffer.from([0]), Buffer.from([0])]));
  game('client_tick_end');
}
let sequence = 1;
function f32(n) { const b = Buffer.alloc(4); b.writeFloatBE(n); return b; }
function blockPos(x, y, z) {
  const b = Buffer.alloc(8);
  // Packed as an unsigned 64-bit value: negative x sets the top bit, which a signed write rejects.
  b.writeBigUInt64BE(BigInt.asUintN(64, (BigInt(x) & 0x3FFFFFFn) << 38n | (BigInt(z) & 0x3FFFFFFn) << 12n | (BigInt(y) & 0xFFFn)));
  return b;
}
// Right-click on air with the main hand: ServerboundUseItemPacket(hand, sequence, yRot, xRot).
// No arm swing: it is cosmetic, and 26.x renamed its packet.
function use() {
  game('use_item', Buffer.concat([vi(0), vi(sequence++), f32(0), f32(0)]));
  game('client_tick_end');
}
// ServerboundPlayerActionPacket START_DESTROY_BLOCK from above; an instant-mine tool breaks it at once.
function dig(x, y, z) {
  game('player_action', Buffer.concat([vi(0), blockPos(x, y, z), Buffer.from([1]), vi(sequence++)]));
  game('client_tick_end');
}
// Mines a block for real: START_DESTROY_BLOCK, wait for the server-side progress, STOP_DESTROY_BLOCK.
// 26.x inserted CHANGE_DESTROY_DIRECTION at ordinal 1, so STOP is 3 (2 is now ABORT).
function mine(x, y, z, ms) {
  game('player_action', Buffer.concat([vi(0), blockPos(x, y, z), Buffer.from([1]), vi(sequence++)]));
  game('client_tick_end');
  setTimeout(() => {
    game('player_action', Buffer.concat([vi(3), blockPos(x, y, z), Buffer.from([1]), vi(sequence++)]));
    game('client_tick_end');
    console.log('S06A-WIRE SEND mined ' + [x, y, z].join(' '));
  }, ms);
}
// ServerboundUseItemOnPacket: hand, BlockHitResult(pos, face UP, cursor, inside, worldBorderHit), sequence.
function useOn(x, y, z) {
  game('use_item_on', Buffer.concat([vi(0), blockPos(x, y, z), vi(1), f32(0.5), f32(1), f32(0.5), Buffer.from([0, 0]), vi(sequence++)]));
  game('client_tick_end');
}
const extra = {
  mine: ([x, y, z, ms]) => mine(x, y, z, ms || 1500),
  // ServerboundPlayerInputPacket: one byte of input flags; 0x20 is shift (sneak).
  sneak: ([on]) => { game('player_input', Buffer.from([on ? 0x20 : 0])); game('client_tick_end'); },
  slot: ([n]) => { const b = Buffer.alloc(2); b.writeInt16BE(n); game('set_carried_item', b); game('client_tick_end'); },
  useon: ([x, y, z]) => useOn(x, y, z),
};
require('node:readline').createInterface({input: process.stdin}).on('line', line => { try {
  const [action, ...args] = line.trim().split(/\s+/);
  if (action === 'stop') { socket.end(); return; }
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
} catch (e) { console.error(e.stack); process.exitCode = 1; socket.destroy(); } });
