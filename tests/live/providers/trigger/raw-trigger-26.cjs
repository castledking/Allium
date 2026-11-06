// Test-only protocol777 transport, derived from the S05 raw helper. Shared helper is untouched.
// Only owned loopback ports25610-25612. No offline mock/player implementation.
const net=require('node:net'),z=require('node:zlib'),fs=require('node:fs'),crypto=require('node:crypto');
const [lane,portText]=process.argv.slice(2),port=Number(portText);
if(!lane||![25610,25611,25612].includes(port))throw Error('TEST ONLY: require owned port25610-25612');
const ids=JSON.parse(fs.readFileSync(lane+'/plugins/CETriggerGate/wire-ids.json')).wirePackets;
const actions=JSON.parse(fs.readFileSync(lane+'/plugins/CETriggerGate/action-ids.json'));
function vi(n){const a=[];do{let b=n&127;n>>>=7;if(n)b|=128;a.push(b)}while(n);return Buffer.from(a)}
function read(b,o=0){let n=0,s=0;for(let i=o;i<b.length;i++){const c=b[i];n|=(c&127)<<s;if(!(c&128))return[n,i+1];s+=7;if(s>35)throw Error('bad varint')}return null}
function str(s){const b=Buffer.from(s);return Buffer.concat([vi(b.length),b])}
let state='login',compressed=false,buffer=Buffer.alloc(0),stateId=0,ready=false,position;
const socket=net.connect({host:'127.0.0.1',port});
function send(id,data=Buffer.alloc(0)){let body=Buffer.concat([vi(id),data]);if(compressed)body=Buffer.concat([vi(0),body]);socket.write(Buffer.concat([vi(body.length),body]))}
function named(flow,name,data){const id=ids[flow]['minecraft:'+name];if(id===undefined)throw Error('Unknown packet '+flow+'/'+name);send(id,data)}
function game(name,data){named('game_SERVERBOUND',name,data)}
socket.on('connect',()=>{
 const p=Buffer.alloc(2);p.writeUInt16BE(port);send(0,Buffer.concat([vi(777),str('127.0.0.1'),p,vi(2)]));
 const uuid=crypto.createHash('md5').update('OfflinePlayer:TriggerBot').digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;
 named('login_SERVERBOUND','hello',Buffer.concat([str('TriggerBot'),uuid]));
});
socket.on('data',chunk=>{try{
 buffer=Buffer.concat([buffer,chunk]);while(true){const length=read(buffer);if(!length||buffer.length<length[1]+length[0])break;
 let body=buffer.subarray(length[1],length[1]+length[0]);buffer=buffer.subarray(length[1]+length[0]);
 if(compressed){const [n,start]=read(body);body=n?z.inflateSync(body.subarray(start)):body.subarray(start)}
 const [id,start]=read(body),payload=body.subarray(start);
 if(state==='login'){
  if(id===ids.login_CLIENTBOUND['minecraft:login_compression']){compressed=true;continue}
  if(id===ids.login_CLIENTBOUND['minecraft:login_finished']){state='configuration';named('login_SERVERBOUND','login_acknowledged');continue}
  if(id===ids.login_CLIENTBOUND['minecraft:login_disconnect'])throw Error('Login rejected '+payload.toString('hex'));
 }else if(state==='configuration'){
  if(id===ids.configuration_CLIENTBOUND['minecraft:select_known_packs'])named('configuration_SERVERBOUND','select_known_packs',vi(0));
  if(id===ids.configuration_CLIENTBOUND['minecraft:cookie_request'])named('configuration_SERVERBOUND','cookie_response',Buffer.concat([payload,Buffer.from([0])]));
  if(id===ids.configuration_CLIENTBOUND['minecraft:keep_alive'])named('configuration_SERVERBOUND','keep_alive',payload);
  if(id===ids.configuration_CLIENTBOUND['minecraft:finish_configuration']){named('configuration_SERVERBOUND','finish_configuration');state='game';console.log('CE-WIRE PLAY protocol777 port='+port)}
  if(id===ids.configuration_CLIENTBOUND['minecraft:disconnect'])throw Error('Configuration disconnected '+payload.toString('hex'));
 }else{
  if(id===ids.game_CLIENTBOUND['minecraft:keep_alive'])game('keep_alive',payload);
  if(id===ids.game_CLIENTBOUND['minecraft:player_position']){
   const [tp,o]=read(payload);position=[payload.readDoubleBE(o),payload.readDoubleBE(o+8),payload.readDoubleBE(o+16)];
   game('accept_teleportation',Buffer.concat([vi(tp),payload.subarray(o,o+24),payload.subarray(o+48,o+56)]));
   console.log('CE-WIRE TELEPORT_ACK '+tp+' '+JSON.stringify(position));
  }
  if(id===ids.game_CLIENTBOUND['minecraft:container_set_content']||id===ids.game_CLIENTBOUND['minecraft:container_set_slot']){
   const [container,o]=read(payload);if(container===0)[stateId]=read(payload,o);
  }
  if(id===ids.game_CLIENTBOUND['minecraft:commands']&&!ready){ready=true;game('player_loaded');game('client_tick_end');console.log('CE-WIRE READY')}
  if(id===ids.game_CLIENTBOUND['minecraft:disconnect'])throw Error('Play disconnected '+payload.toString('hex'));
 }
 }
}catch(e){console.error(e.stack);process.exitCode=1;socket.destroy();}});
socket.on('error',e=>{console.error(e);process.exitCode=1});
socket.on('close',()=>process.exit(process.exitCode||0));
require('node:readline').createInterface({input:process.stdin}).on('line',line=>{try{
 const [action,...args]=line.trim().split(/\s+/);if(!ready)throw Error('wait for READY');
 if(action==='click'||action==='rightclick'){
  const slot=Buffer.alloc(2);slot.writeInt16BE(36); // player hotbar slot0, native PICKUP left click
  // No predicted slot deltas; empty hashed cursor prediction requests authoritative resync.
  // Server executes the genuine click and returns its actual cursor/inventory; observer asserts it.
  const button=action==='rightclick'?1:0;
  game('container_click',Buffer.concat([vi(0),vi(stateId),slot,Buffer.from([button]),vi(0),vi(0),Buffer.from([0])]));
  console.log('CE-WIRE SEND container_click slot36 button='+button+' PICKUP state='+stateId);
 }else if(action==='drop'||action==='drop-one'){
  const key=action==='drop-one'?'DROP_ITEM':'DROP_ALL_ITEMS';if(!Number.isInteger(actions[key]))throw Error('Missing native action '+key);
  game('player_action',Buffer.concat([vi(actions[key]),Buffer.alloc(8),Buffer.from([0]),vi(0)]));
  console.log('CE-WIRE SEND player_action '+key+'='+actions[key]);
 }else if(action==='move'){
  const b=Buffer.alloc(25);args.map(Number).forEach((v,i)=>b.writeDoubleBE(v,8*i));b[24]=0;
  game('move_player_pos',b);game('client_tick_end');position=args.map(Number);console.log('CE-WIRE SEND move_player_pos '+JSON.stringify(position));
 }else if(action==='stop'){socket.end();}
 else throw Error('Unknown command '+action);
}catch(e){console.error(e.stack);process.exitCode=1;socket.destroy();}});
