// Test-only actual 1.21.8 protocol client. Requires external minecraft-protocol1.68.0.
const fs=require('node:fs');
const [lane,portText]=process.argv.slice(2),port=Number(portText);
if(!lane||![25610,25611,25612].includes(port))throw Error('TEST ONLY owned loopback25610-12');
const mc=require(process.env.MC_PROTOCOL_MODULE||'/tmp/allium-s05-20261105/client/node_modules/minecraft-protocol');
const client=mc.createClient({host:'127.0.0.1',port,username:'TriggerBot',auth:'offline',version:'1.21.8'});
const actions=JSON.parse(fs.readFileSync(lane+'/plugins/CETriggerGate/action-ids.json'));
let stateId=0,ready=false;
client.on('packet',(packet,meta)=>{
 if(meta.name==='position'){
  client.write('teleport_confirm',{teleportId:packet.teleportId});
  console.log('CE-WIRE TELEPORT_ACK '+packet.teleportId+' '+JSON.stringify([packet.x,packet.y,packet.z]));
 }
 if((meta.name==='window_items'||meta.name==='set_slot')&&packet.windowId===0)stateId=packet.stateId;
 if(meta.name==='declare_commands'&&!ready){ready=true;console.log('CE-WIRE READY protocol1.21.8 port='+port)}
});
client.on('error',e=>{console.error(e);process.exitCode=1});
client.on('end',reason=>{console.log('CE-WIRE END '+reason);process.exit(process.exitCode||0)});
require('node:readline').createInterface({input:process.stdin}).on('line',line=>{try{
 const [action,...args]=line.trim().split(/\s+/);if(!ready)throw Error('wait for READY');
 if(action==='click'||action==='rightclick'){
  const button=action==='rightclick'?1:0;
  client.write('window_click',{windowId:0,stateId,slot:36,mouseButton:button,mode:0,changedSlots:[],cursorItem:null});
  console.log('CE-WIRE SEND window_click slot36 button='+button+' PICKUP state='+stateId);
 }else if(action==='drop'||action==='drop-one'){
  const key=action==='drop-one'?'DROP_ITEM':'DROP_ALL_ITEMS';if(!Number.isInteger(actions[key]))throw Error('Missing native action '+key);
  client.write('block_dig',{status:actions[key],location:{x:0,y:0,z:0},face:0,sequence:0});
  console.log('CE-WIRE SEND block_dig '+key+'='+actions[key]);
 }else if(action==='move'){
  const [x,y,z]=args.map(Number);client.write('position',{x,y,z,flags:{onGround:false,hasHorizontalCollision:false}});
  console.log('CE-WIRE SEND position '+JSON.stringify([x,y,z]));
 }else if(action==='stop')client.end('diagnostic finished');
 else throw Error('Unknown action '+action);
}catch(e){console.error(e);process.exitCode=1;client.end('error')}});
