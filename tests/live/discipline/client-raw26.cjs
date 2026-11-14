// Test-only S19 discipline client for protocol 777 (Paper 26.3). Owned loopback ports 25790-25792 only.
const net = require('node:net'), z = require('node:zlib'), fs = require('node:fs'), crypto = require('node:crypto');
const {flatten} = require('./text-components.cjs');
const [lane, portText, username = 'BaseBot'] = process.argv.slice(2), port = Number(portText);
if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw Error('bad username');
if (!lane || !([25790, 25791, 25792].includes(port))) throw Error('TEST ONLY: owned port 25790-25792');
function extractTranslate(obj) {
  if (!obj || typeof obj !== 'object' || !obj.translate) return {};
  const args = Array.isArray(obj.with) ? obj.with.map(w => flatten(w).plain) : [];
  return {translate: obj.translate, withPlain: args};
}
const ids = JSON.parse(fs.readFileSync(lane + '/plugins/DisciplineGate/wire-ids.json')).wirePackets;
function vi(n) { const a = []; do { let b = n & 127; n >>>= 7; if (n) b |= 128; a.push(b); } while (n); return Buffer.from(a); }
function read(b, o = 0) { let n = 0, s = 0; for (let i = o; i < b.length; i++) { const c = b[i]; n |= (c & 127) << s; if (!(c & 128)) return [n, i + 1]; s += 7; if (s > 35) throw Error('bad varint'); } return null; }
function str(s) { const b = Buffer.from(s); return Buffer.concat([vi(b.length), b]); }
function readStr(b, o) { const [n, start] = read(b, o); return [b.subarray(start, start + n).toString('utf8'), start + n]; }
function nbtPayload(b, o, type) {
  switch (type) {
    case 1: return [b.readInt8(o), o + 1];
    case 2: return [b.readInt16BE(o), o + 2];
    case 3: return [b.readInt32BE(o), o + 4];
    case 4: return [Number(b.readBigInt64BE(o)), o + 8];
    case 5: return [b.readFloatBE(o), o + 4];
    case 6: return [b.readDoubleBE(o), o + 8];
    case 7: { const n = b.readInt32BE(o); return [[...b.subarray(o + 4, o + 4 + n)], o + 4 + n]; }
    case 8: { const n = b.readUInt16BE(o); return [b.subarray(o + 2, o + 2 + n).toString('utf8'), o + 2 + n]; }
    case 9: { const t = b[o], n = b.readInt32BE(o + 1); let p = o + 5; const out = []; for (let i = 0; i < n; i++) { const [v, q] = nbtPayload(b, p, t); out.push(v); p = q; } return [out, p]; }
    case 10: { const out = {}; let p = o; while (true) { const t = b[p++]; if (t === 0) break; const n = b.readUInt16BE(p); const key = b.subarray(p + 2, p + 2 + n).toString('utf8'); p += 2 + n; const [v, q] = nbtPayload(b, p, t); out[key] = v; p = q; } return [out, p]; }
    case 11: { const n = b.readInt32BE(o); const out = []; for (let i = 0; i < n; i++) out.push(b.readInt32BE(o + 4 + 4 * i)); return [out, o + 4 + 4 * n]; }
    case 12: { const n = b.readInt32BE(o); return [[], o + 4 + 8 * n]; }
    default: throw Error('nbt type ' + type);
  }
}
function readNbt(b, o) { return nbtPayload(b, o + 1, b[o]); }
let state = 'login', compressed = false, buffer = Buffer.alloc(0), ready = false;
let pos = {x: 0, y: 64, z: 0};
const socket = net.connect({host: '127.0.0.1', port});
function send(id, data = Buffer.alloc(0)) { let body = Buffer.concat([vi(id), data]); if (compressed) body = Buffer.concat([vi(0), body]); socket.write(Buffer.concat([vi(body.length), body])); }
function named(flow, name, data) { const id = ids[flow]['minecraft:' + name]; if (id === undefined) throw Error('Unknown packet ' + flow + '/' + name); send(id, data); }
function game(name, data) { named('game_SERVERBOUND', name, data); }
socket.on('connect', () => {
  const uuid = crypto.createHash('md5').update('OfflinePlayer:' + username).digest(); uuid[6] = (uuid[6] & 15) | 48; uuid[8] = (uuid[8] & 63) | 128;
  const p = Buffer.alloc(2); p.writeUInt16BE(port); send(0, Buffer.concat([vi(777), str('127.0.0.1'), p, vi(2)]));
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
      if (id === ids.login_CLIENTBOUND['minecraft:login_disconnect']) { const [reason] = readStr(payload, 0); console.log('DISC-GKICK ' + JSON.stringify(flatten(reason))); }
    } else if (state === 'configuration') {
      if (id === ids.configuration_CLIENTBOUND['minecraft:select_known_packs']) named('configuration_SERVERBOUND', 'select_known_packs', vi(0));
      if (id === ids.configuration_CLIENTBOUND['minecraft:cookie_request']) named('configuration_SERVERBOUND', 'cookie_response', Buffer.concat([payload, Buffer.from([0])]));
      if (id === ids.configuration_CLIENTBOUND['minecraft:keep_alive']) named('configuration_SERVERBOUND', 'keep_alive', payload);
      if (id === ids.configuration_CLIENTBOUND['minecraft:finish_configuration']) { named('configuration_SERVERBOUND', 'finish_configuration'); state = 'game'; }
      if (id === ids.configuration_CLIENTBOUND['minecraft:disconnect']) { const [reason] = readNbt(payload, 0); console.log('DISC-GKICK ' + JSON.stringify(flatten(reason))); }
    } else {
      if (id === ids.game_CLIENTBOUND['minecraft:keep_alive']) game('keep_alive', payload);
      if (id === ids.game_CLIENTBOUND['minecraft:player_position']) {
        const [tp, o] = read(payload);
        const flags = payload.readInt32BE(o + 56);
        pos = {
          x: payload.readDoubleBE(o) + ((flags & 1) ? pos.x : 0),
          y: payload.readDoubleBE(o + 8) + ((flags & 2) ? pos.y : 0),
          z: payload.readDoubleBE(o + 16) + ((flags & 4) ? pos.z : 0),
          yaw: payload.readFloatBE(o + 48) + ((flags & 8) ? (pos.yaw || 0) : 0),
          pitch: payload.readFloatBE(o + 52) + ((flags & 16) ? (pos.pitch || 0) : 0)
        };
        const ack = Buffer.alloc(32);
        ack.writeDoubleBE(pos.x,0); ack.writeDoubleBE(pos.y,8); ack.writeDoubleBE(pos.z,16);
        ack.writeFloatBE(pos.yaw,24); ack.writeFloatBE(pos.pitch,28);
        game('accept_teleportation', Buffer.concat([vi(tp), ack]));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:system_chat']) {
        const [component, o] = readNbt(payload, 0);
        console.log('BASEG-CHAT ' + JSON.stringify(Object.assign(flatten(component), extractTranslate(component), {overlay: payload[o] === 1,
          rawComponent: component, interactive: /click_event|hover_event|clickEvent|hoverEvent|"insertion"/.test(JSON.stringify(component))})));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:disguised_chat'] || id === ids.game_CLIENTBOUND['minecraft:player_chat']) {
        console.log('BASEG-RAWCHAT ' + payload.toString('utf8'));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:command_suggestions']) {
        let [tid, p] = read(payload); [, p] = read(payload, p); [, p] = read(payload, p); let n; [n, p] = read(payload, p);
        const matches = [];
        for (let k = 0; k < n; k++) { let m; [m, p] = readStr(payload, p); matches.push(m); if (payload[p++] === 1) { const [, q] = readNbt(payload, p); p = q; } }
        console.log('MSGG-TAB ' + JSON.stringify({id: tid, matches}));
      }
      if (id === ids.game_CLIENTBOUND['minecraft:commands'] && !ready) { ready = true; game('client_information', Buffer.concat([str('en_us'), Buffer.from([2, 0, 1, 127, 1, 0, 1, 0])])); game('player_loaded'); game('client_tick_end'); console.log('BASEG-WIRE READY protocol777 port=' + port + ' user=' + username); }
      if (id === ids.game_CLIENTBOUND['minecraft:disconnect']) {
        let kickPlain = '';
        try { const [comp] = readNbt(payload, 0); kickPlain = flatten(comp).plain; } catch (_) { kickPlain = payload.toString('hex'); }
        console.log('DISC-GKICK ' + JSON.stringify({plain: kickPlain}));
      }
    }
  }
} catch (e) { console.log('DISC-GCLIENTERROR '+JSON.stringify({message:e.message})); console.error(e.stack); process.exitCode = 1; socket.destroy(); } });
socket.on('error', e => { console.log((['ECONNRESET','EPIPE'].includes(e.code) ? 'DISC-GTRANSPORT ' : 'DISC-GCLIENTERROR ') + JSON.stringify({code:e.code,message:e.message})); console.error(e); process.exitCode = 1; });
socket.on('close', () => { console.log('BASEG-WIRE END'); process.exit(process.exitCode || 0); });
let tabId = 0;
require('node:readline').createInterface({input: process.stdin}).on('line', line => {
  const text = line.replace(/\r?\n$/, '');
  if (text.trim() === 'stop') { socket.end(); return; }
  if (!ready) return;
  if (text.startsWith('cmd ')) { game('chat_command', str(text.slice(4))); game('client_tick_end'); console.log('BASEG-WIRE SENT /' + text.slice(4)); }
  if (text.startsWith('say ')) {
    const msg = text.slice(4);
    const ts = Buffer.alloc(8); ts.writeBigInt64BE(BigInt(Date.now()));
    const salt = Buffer.alloc(8);
    game('chat', Buffer.concat([str(msg), ts, salt, Buffer.from([0, 0, 0, 0, 0, 0])]));
    game('client_tick_end');
    console.log('BASEG-WIRE SAY ' + msg);
  }
  if (text.startsWith('tab ')) { const id = ++tabId; game('command_suggestion', Buffer.concat([vi(id), str('/' + text.slice(4))])); console.log('MSGG-TABREQ ' + JSON.stringify({id, text: text.slice(4)})); }
  if (text.startsWith('look ')) {
    const [yaw, pitch] = text.slice(5).trim().split(/\s+/).map(Number);
    const buf = Buffer.alloc(9); buf.writeFloatBE(yaw, 0); buf.writeFloatBE(pitch, 4); buf[8] = 1;
    pos.yaw = yaw; pos.pitch = pitch;
    game('move_player_rot', buf); game('client_tick_end');
    console.log('DISC-GLOOK ' + JSON.stringify({yaw, pitch}));
  }
  if (text.startsWith('move ')) {
    const [dx, dz] = text.slice(5).trim().split(/\s+/).map(Number);
    pos.x += dx; pos.z += dz;
    const buf = Buffer.alloc(25);
    buf.writeDoubleBE(pos.x, 0);
    buf.writeDoubleBE(pos.y, 8);
    buf.writeDoubleBE(pos.z, 16);
    buf.writeUInt8(1, 24);
    game('move_player_pos', buf);
    game('client_tick_end');
    console.log('DISC-GMOVED ' + JSON.stringify(pos));
  }
});

