// Test-only S06b pack gate client for protocol 777 (Paper 26.3) with no npm dependencies; packet IDs come
// from the running server's catalog (PackGate wire-ids.json). Owned loopback ports 25660-25662 only.
// ClientboundResourcePackPushPacket: uuid (16 bytes), url, hash (string), required (bool), optional prompt.
// ServerboundResourcePackPacket: uuid, action VarInt (see pack-offer.cjs).
const net = require('node:net'), z = require('node:zlib'), fs = require('node:fs'), crypto = require('node:crypto');
const {handleOffer} = require('./pack-offer.cjs');
const [lane, portText, mode, configPath] = process.argv.slice(2), port = Number(portText);
if (!lane || ![25660, 25661, 25662].includes(port) || !['accept', 'decline'].includes(mode)) throw Error('TEST ONLY: owned port 25660-25662, mode accept|decline');
const cfg = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const ids = JSON.parse(fs.readFileSync(lane + '/plugins/PackGate/wire-ids.json')).wirePackets;
function vi(n) { const a = []; do { let b = n & 127; n >>>= 7; if (n) b |= 128; a.push(b); } while (n); return Buffer.from(a); }
function read(b, o = 0) { let n = 0, s = 0; for (let i = o; i < b.length; i++) { const c = b[i]; n |= (c & 127) << s; if (!(c & 128)) return [n, i + 1]; s += 7; if (s > 35) throw Error('bad varint'); } return null; }
function str(s) { const b = Buffer.from(s); return Buffer.concat([vi(b.length), b]); }
function readStr(b, o) { const [n, start] = read(b, o); return [b.subarray(start, start + n).toString('utf8'), start + n]; }
let state = 'login', compressed = false, buffer = Buffer.alloc(0), ready = false, offers = 0;
const socket = net.connect({host: '127.0.0.1', port});
function send(id, data = Buffer.alloc(0)) { let body = Buffer.concat([vi(id), data]); if (compressed) body = Buffer.concat([vi(0), body]); socket.write(Buffer.concat([vi(body.length), body])); }
function named(flow, name, data) { const id = ids[flow]['minecraft:' + name]; if (id === undefined) throw Error('Unknown packet ' + flow + '/' + name); send(id, data); }
function game(name, data) { named('game_SERVERBOUND', name, data); }
function offer(phase, payload) {
  const uuid = payload.subarray(0, 16);
  const [url, o1] = readStr(payload, 16);
  const [hash] = readStr(payload, o1);
  const flow = phase === 'configuration' ? 'configuration_SERVERBOUND' : 'game_SERVERBOUND';
  handleOffer({url, hash, uuid: uuid.toString('hex'), mode, cfg, downloadedStatus: true, offerIndex: `${mode}-${offers++}`},
    action => named(flow, 'resource_pack', Buffer.concat([uuid, vi(action)])));
}
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
      if (id === ids.configuration_CLIENTBOUND['minecraft:resource_pack_push']) offer('configuration', payload);
      if (id === ids.configuration_CLIENTBOUND['minecraft:finish_configuration']) { named('configuration_SERVERBOUND', 'finish_configuration'); state = 'game'; }
      if (id === ids.configuration_CLIENTBOUND['minecraft:disconnect']) throw Error('Configuration disconnected ' + payload.toString('hex'));
    } else {
      if (id === ids.game_CLIENTBOUND['minecraft:keep_alive']) game('keep_alive', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:resource_pack_push']) offer('game', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:player_position']) {
        const [tp, o] = read(payload);
        game('accept_teleportation', Buffer.concat([vi(tp), payload.subarray(o, o + 24), payload.subarray(o + 48, o + 56)]));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:commands'] && !ready) { ready = true; game('player_loaded'); game('client_tick_end'); console.log('S06B-WIRE READY protocol777 port=' + port + ' mode=' + mode); }
      if (id === ids.game_CLIENTBOUND['minecraft:disconnect']) throw Error('Play disconnected ' + payload.toString('hex'));
    }
  }
} catch (e) { console.error(e.stack); process.exitCode = 1; socket.destroy(); } });
socket.on('error', e => { console.error(e); process.exitCode = 1; });
socket.on('close', () => { console.log('S06B-WIRE END'); process.exit(process.exitCode || 0); });
require('node:readline').createInterface({input: process.stdin}).on('line', line => { if (line.trim() === 'stop') socket.end(); });
