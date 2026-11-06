// Minimal 26.3 wire client. Packet IDs come from the tested server's own protocol details.
// World packets are deliberately ignored; actual command-tree and suggestion packets are asserted.
const net=require('node:net'), z=require('node:zlib'), fs=require('node:fs'), crypto=require('node:crypto');
const [lane,portText]=process.argv.slice(2), port=Number(portText);
if(!lane || ![25589,25592].includes(port)) throw Error('Only disposable loopback gate ports25589/25592 are allowed');
const ids=JSON.parse(fs.readFileSync(lane+'/plugins/ClientGate/state-ids.json')).wirePackets;
if(!ids) throw Error('Run clientgate wireids ids on the exact server before connecting');
function vi(n){const a=[];do{let b=n&127;n>>>=7;if(n)b|=128;a.push(b)}while(n);return Buffer.from(a)}
function read(b,o=0){let n=0,s=0;for(let i=o;i<b.length;i++){const c=b[i];n|=(c&127)<<s;if(!(c&128))return[n,i+1];s+=7;if(s>35)throw Error('bad varint')}return null}
function str(s){const b=Buffer.from(s);return Buffer.concat([vi(b.length),b])}
function string(b,o){const [n,start]=read(b,o);return[b.subarray(start,start+n).toString(),start+n]}
let state='login',compressed=false,buffer=Buffer.alloc(0),treeCount=0,ready=false;
const trees=[],replies=new Map(), result={server:'Paper26.3 protocol777',passed:false,trees,suggestions:{}};
const socket=net.connect({host:'127.0.0.1',port});
function send(id,data=Buffer.alloc(0)){let body=Buffer.concat([vi(id),data]);if(compressed)body=Buffer.concat([vi(0),body]);socket.write(Buffer.concat([vi(body.length),body]))}
function named(flow,name,data){send(ids[flow]['minecraft:'+name],data)}
socket.on('connect',()=>{
 const p=Buffer.alloc(2);p.writeUInt16BE(port);
 send(0,Buffer.concat([vi(777),str('127.0.0.1'),p,vi(2)]));
 const uuid=crypto.createHash('md5').update('OfflinePlayer:GateBot').digest();uuid[6]=(uuid[6]&15)|48;uuid[8]=(uuid[8]&63)|128;
 named('login_SERVERBOUND','hello',Buffer.concat([str('GateBot'),uuid]));
});
socket.on('data',chunk=>{try{
 buffer=Buffer.concat([buffer,chunk]);
 while(true){const length=read(buffer);if(!length||buffer.length<length[1]+length[0])break;
 let body=buffer.subarray(length[1],length[1]+length[0]);buffer=buffer.subarray(length[1]+length[0]);
 if(compressed){const [n,start]=read(body);body=n?z.inflateSync(body.subarray(start)):body.subarray(start)}
 const [id,start]=read(body);const payload=body.subarray(start);
 if(state==='login'){
  if(id===ids.login_CLIENTBOUND['minecraft:login_compression']){compressed=true;continue}
  if(id===ids.login_CLIENTBOUND['minecraft:login_finished']){state='configuration';named('login_SERVERBOUND','login_acknowledged');continue}
  if(id===ids.login_CLIENTBOUND['minecraft:login_disconnect'])throw Error('Login rejected: '+payload.toString());
 }else if(state==='configuration'){
  if(id===ids.configuration_CLIENTBOUND['minecraft:select_known_packs'])named('configuration_SERVERBOUND','select_known_packs',vi(0));
  if(id===ids.configuration_CLIENTBOUND['minecraft:cookie_request'])named('configuration_SERVERBOUND','cookie_response',Buffer.concat([payload,Buffer.from([0])]));
  if(id===ids.configuration_CLIENTBOUND['minecraft:keep_alive'])named('configuration_SERVERBOUND','keep_alive',payload);
  if(id===ids.configuration_CLIENTBOUND['minecraft:finish_configuration']){named('configuration_SERVERBOUND','finish_configuration');state='game';console.log('RAW26_PLAY')}
  if(id===ids.configuration_CLIENTBOUND['minecraft:disconnect'])throw Error('Configuration rejected: '+payload.toString());
 }else{
  if(id===ids.game_CLIENTBOUND['minecraft:keep_alive'])named('game_SERVERBOUND','keep_alive',payload);
  if(id===ids.game_CLIENTBOUND['minecraft:player_position']){const [tp,o]=read(payload);named('game_SERVERBOUND','accept_teleportation',Buffer.concat([vi(tp),payload.subarray(o,o+24),payload.subarray(o+48,o+56)]));}
  if(id===ids.game_CLIENTBOUND['minecraft:commands']){
   const tree={index:++treeCount,alliumGm:payload.includes(str('allium:gm')),alliumGamemode:payload.includes(str('allium:gamemode')),bytes:payload.length};trees.push(tree);if(tree.alliumGm)ready=true;
  }
  if(id===ids.game_CLIENTBOUND['minecraft:command_suggestions']){
   let o=0,r;[r,o]=read(payload,o);let start,len,count;[start,o]=read(payload,o);[len,o]=read(payload,o);[count,o]=read(payload,o);const matches=[];
   for(let i=0;i<count;i++){let value;[value,o]=string(payload,o);matches.push(value);if(payload[o++]!==0)throw Error('Unexpected rich tooltip in gate suggestions')}
   replies.set(r,matches);
  }
  if(id===ids.game_CLIENTBOUND['minecraft:disconnect'])throw Error('Play disconnected: '+payload.toString());
 }
 }
}catch(e){result.error=e.stack;socket.destroy();}});
socket.on('error',e=>{result.error=e.stack});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,label){const end=Date.now()+25000;while(!fn()){if(result.error)throw Error(result.error);if(Date.now()>end)throw Error('Timeout '+label);await delay(50)}}
async function query(id,text){named('game_SERVERBOUND','command_suggestion',Buffer.concat([vi(id),str(text)]));await until(()=>replies.has(id),text);const got=replies.get(id);result.suggestions[id+':'+text]=got;return got}
(async()=>{try{
 await until(()=>ready,'op command-tree packet');
 if(process.env.PROVIDER_GATE_MODE==='1') {
  console.log('PROVIDER_GATE_CLIENT_READY');
  await new Promise(resolve=>{const timer=setInterval(()=>{if(fs.existsSync(lane+'/provider-client-stop')){clearInterval(timer);resolve()}},100)});
  delete result.passed;
  result.connectionOnly=true;
  return;
 }
 if(!(await query(1,'/gm c')).includes('creative'))throw Error('Missing before creative');
 if(!(await query(2,'/allium:gm c')).includes('creative'))throw Error('Missing namespaced before creative');
 named('game_SERVERBOUND','chat_command',str('clientgate install before'));
 await until(()=>fs.existsSync(lane+'/plugins/ClientGate/state-before.json'),'install');
 const before=treeCount;named('game_SERVERBOUND','chat_command',str('clientgate yield after'));
 await until(()=>treeCount>before&&!trees.at(-1).alliumGm&&!trees.at(-1).alliumGamemode,'updated command-tree packet');
 if(!(await query(3,'/gm c')).includes('competitor-creative'))throw Error('Missing competitor completion');
 result.serverReport=JSON.parse(fs.readFileSync(lane+'/plugins/ClientGate/state-after.json'));
 if(!result.serverReport.yieldReturned)throw Error('Bridge returned false');
 result.passed=true;
}catch(e){result.error=e.stack;process.exitCode=1}finally{socket.end();fs.writeFileSync(lane+'/network-gate-raw26.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));}})();
