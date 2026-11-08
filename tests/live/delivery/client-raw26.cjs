// Test-only S06c delivery client for protocol 777 (Paper 26.3) with no npm dependencies; packet IDs come from
// the running server's catalog (DeliveryGate wire-ids.json). Owned loopback ports 25686-25688 only.
// Mode accept|decline (see pack-response.cjs); logs every pack offer and removal, and any kick.
const net = require('node:net'), z = require('node:zlib'), fs = require('node:fs'), crypto = require('node:crypto');
const {respond} = require('./pack-response.cjs');
const {flatten} = require('../baseline/text-components.cjs');
const [lane, portText, mode, username = 'PackBot'] = process.argv.slice(2), port = Number(portText);
if (!lane || !(port >= 25686 && port <= 25688) || !['accept', 'decline'].includes(mode)) throw Error('TEST ONLY: owned port 25686-25688, mode accept|decline');
const ids = JSON.parse(fs.readFileSync(lane + '/plugins/DeliveryGate/wire-ids.json')).wirePackets;
function vi(n) { const a = []; do { let b = n & 127; n >>>= 7; if (n) b |= 128; a.push(b); } while (n); return Buffer.from(a); }
function read(b, o = 0) { let n = 0, s = 0; for (let i = o; i < b.length; i++) { const c = b[i]; n |= (c & 127) << s; if (!(c & 128)) return [n, i + 1]; s += 7; if (s > 35) throw Error('bad varint'); } return null; }
function str(s) { const b = Buffer.from(s); return Buffer.concat([vi(b.length), b]); }
function readStr(b, o) { const [n, start] = read(b, o); return [b.subarray(start, start + n).toString('utf8'), start + n]; }
function nbt(b, o, t) {
  switch (t) {
    case 1: return [b.readInt8(o), o + 1]; case 2: return [b.readInt16BE(o), o + 2]; case 3: return [b.readInt32BE(o), o + 4];
    case 4: return [0, o + 8]; case 5: return [b.readFloatBE(o), o + 4]; case 6: return [b.readDoubleBE(o), o + 8];
    case 7: { const n = b.readInt32BE(o); return [[], o + 4 + n]; }
    case 8: { const n = b.readUInt16BE(o); return [b.subarray(o + 2, o + 2 + n).toString('utf8'), o + 2 + n]; }
    case 9: { const tt = b[o], n = b.readInt32BE(o + 1); let p = o + 5; const out = []; for (let i = 0; i < n; i++) { const [v, q] = nbt(b, p, tt); out.push(v); p = q; } return [out, p]; }
    case 10: { const out = {}; let p = o; while (true) { const tt = b[p++]; if (tt === 0) break; const n = b.readUInt16BE(p); const k = b.subarray(p + 2, p + 2 + n).toString('utf8'); p += 2 + n; const [v, q] = nbt(b, p, tt); out[k] = v; p = q; } return [out, p]; }
    case 11: { const n = b.readInt32BE(o); return [[], o + 4 + 4 * n]; } case 12: { const n = b.readInt32BE(o); return [[], o + 4 + 8 * n]; }
    default: throw Error('nbt type ' + t);
  }
}
const component = (b, o) => { const [v, q] = nbt(b, o + 1, b[o]); return [flatten(v).plain, q]; };
let state = 'login', compressed = false, buffer = Buffer.alloc(0), ready = false;
const socket = net.connect({host: '127.0.0.1', port});
function send(id, data = Buffer.alloc(0)) { let body = Buffer.concat([vi(id), data]); if (compressed) body = Buffer.concat([vi(0), body]); socket.write(Buffer.concat([vi(body.length), body])); }
function named(flow, name, data) { const id = ids[flow]['minecraft:' + name]; if (id === undefined) throw Error('Unknown packet ' + flow + '/' + name); send(id, data); }
function game(name, data) { named('game_SERVERBOUND', name, data); }
function offer(phase, payload) {
  const uuidBytes = payload.subarray(0, 16);
  const hex = uuidBytes.toString('hex');
  const uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const [url, o1] = readStr(payload, 16);
  const [hash, o2] = readStr(payload, o1);
  const forced = payload[o2] === 1;
  const prompt = payload[o2 + 1] === 1 ? component(payload, o2 + 2)[0] : null;
  const flow = phase === 'configuration' ? 'configuration_SERVERBOUND' : 'game_SERVERBOUND';
  respond({phase, uuid, url, hash, forced, prompt}, mode, true, action => named(flow, 'resource_pack', Buffer.concat([uuidBytes, vi(action)])));
}
function pop(phase, payload) {
  let uuid = null;
  if (payload[0] === 1) { const hex = payload.subarray(1, 17).toString('hex'); uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`; }
  console.log('DELIVG-PACK ' + JSON.stringify({event: 'removed', phase, uuid}));
}
socket.on('connect', () => {
  const p = Buffer.alloc(2); p.writeUInt16BE(port); send(0, Buffer.concat([vi(777), str('127.0.0.1'), p, vi(2)]));
  const uuid = crypto.createHash('md5').update('OfflinePlayer:' + username).digest(); uuid[6] = (uuid[6] & 15) | 48; uuid[8] = (uuid[8] & 63) | 128;
  named('login_SERVERBOUND', 'hello', Buffer.concat([str(username), uuid]));
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
      if (id === ids.configuration_CLIENTBOUND['minecraft:resource_pack_push']) offer('configuration', payload);
      if (id === ids.configuration_CLIENTBOUND['minecraft:resource_pack_pop']) pop('configuration', payload);
      if (id === ids.configuration_CLIENTBOUND['minecraft:finish_configuration']) { named('configuration_SERVERBOUND', 'finish_configuration'); state = 'game'; }
      if (id === ids.configuration_CLIENTBOUND['minecraft:disconnect']) { console.log('DELIVG-KICK ' + JSON.stringify({phase: 'configuration', reason: component(payload, 0)[0]})); socket.destroy(); }
    } else {
      if (id === ids.game_CLIENTBOUND['minecraft:keep_alive']) game('keep_alive', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:resource_pack_push']) offer('game', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:resource_pack_pop']) pop('game', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:player_position']) {
        const [tp, o] = read(payload);
        game('accept_teleportation', Buffer.concat([vi(tp), payload.subarray(o, o + 24), payload.subarray(o + 48, o + 56)]));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:commands'] && !ready) { ready = true; game('player_loaded'); game('client_tick_end'); console.log('DELIVG-WIRE READY protocol777 port=' + port + ' mode=' + mode); }
      if (id === ids.game_CLIENTBOUND['minecraft:disconnect']) { console.log('DELIVG-KICK ' + JSON.stringify({phase: 'game', reason: component(payload, 0)[0]})); socket.destroy(); }
    }
  }
} catch (e) { console.error(e.stack); process.exitCode = 1; socket.destroy(); } });
socket.on('error', e => { console.error(e); process.exitCode = 1; });
socket.on('close', () => { console.log('DELIVG-WIRE END'); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { if (line.trim() === 'stop') socket.end(); });
