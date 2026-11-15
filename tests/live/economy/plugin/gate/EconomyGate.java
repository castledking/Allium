package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.*;
import codes.castled.allium.economy.EconomyResult.*;
import codes.castled.allium.module.*;
import codes.castled.allium.mail.*;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.preference.*;
import net.milkbowl.vault.economy.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;


import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.management.ManagementFactory;

/** Read-only observations, real Vault API calls and controlled workload/journal boundaries. */
public final class EconomyGate extends JavaPlugin {
    private PluginStart allium;
    private EconomyRuntime runtime;
    private Economy vault;
    private SignsGate signs;
    private final Map<UUID,PermissionAttachment> grants=new HashMap<>();
    private final ExecutorService background=Executors.newFixedThreadPool(5);
    private final AtomicBoolean load=new AtomicBoolean();
    private final AtomicInteger writeRounds=new AtomicInteger();
    private volatile Map<String,Object> benchmark=Map.of("done",false),parallel=Map.of("done",false);
    @Override public void onEnable(){
        if(!getServer().getIp().equals("127.0.0.1")||getServer().getPort()<25800||getServer().getPort()>25802)throw new IllegalStateException("owned loopback only");
        allium=(PluginStart)getServer().getPluginManager().getPlugin("Allium");
        runtime=((EconomyModule)allium.getModuleManager().getModule("economy")).runtime();
        vault=getServer().getServicesManager().load(Economy.class);
        if(runtime==null||!runtime.ready()||vault==null||!vault.getName().equals("Allium"))throw new IllegalStateException("actual Allium Vault provider required");
        try {Files.createDirectories(getDataFolder().toPath());try{Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),json(Map.of("wirePackets",WireIds.dump())));}catch(ReflectiveOperationException legacy){getLogger().info("S20G legacy client");}}
        catch(Exception error){throw new IllegalStateException(error);}
        getLogger().info("S20G-GATE READY provider="+vault.getClass().getName());
    }
    @Override public void onDisable(){load.set(false);background.shutdownNow();}
    private UUID uuid(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));}
    private Player player(String name){var p=getServer().getPlayerExact(name);if(p==null)throw new IllegalArgumentException("online player required");return p;}
    private org.bukkit.OfflinePlayer account(String name){Player p=getServer().getPlayerExact(name);return p==null?getServer().getOfflinePlayer(uuid(name)):p;}
    private static Map<String,Object> found(Balance result){
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind",result.getClass().getSimpleName());
        if(result instanceof Found f){out.put("book",f.book().minor());out.put("reserved",f.reserved().minor());out.put("available",f.available().minor());out.put("version",f.version());}
        return out;
    }
    private static Map<String,Object> response(EconomyResponse r){Map<String,Object> out=new LinkedHashMap<>();out.put("success",r.transactionSuccess());out.put("amount",r.amount);out.put("balance",r.balance);out.put("error",r.errorMessage);return out;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender))return true;String text=String.join(" ",args);
        try{
            Map<String,Object> out=new LinkedHashMap<>();switch(args[0]){
                case "online" -> out.put("players",getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "perm" -> {var p=player(args[1]);grants.computeIfAbsent(p.getUniqueId(),id->p.addAttachment(this)).setPermission(args[2],Boolean.parseBoolean(args[3]));p.updateCommands();out.put("permission",p.hasPermission(args[2]));}
                case "state" -> {out.putAll(found(runtime.engine().balance(uuid(args[1]))));out.put("uuid",uuid(args[1]).toString());out.put("ready",runtime.ready());out.put("provider",vault.getClass().getName());}
                case "fault" -> {codes.castled.allium.core.FaultInjection.arm(args[1],Integer.parseInt(args[2]));out.put("armed",codes.castled.allium.core.FaultInjection.armed(args[1]));}
                case "vault" -> {
                    long start=System.nanoTime();var p=account(args[1]);String action=args[2];
                    if(action.equals("balance"))out.put("balance",vault.getBalance(p));
                    else if(action.equals("string"))out.put("balance",vault.getBalance(args[1]));
                    else if(action.equals("has"))out.put("has",vault.has(p,Double.parseDouble(args[3])));
                    else out.putAll(response(action.equals("withdraw")?vault.withdrawPlayer(p,Double.parseDouble(args[3])):vault.depositPlayer(p,Double.parseDouble(args[3]))));
                    out.put("ms",(System.nanoTime()-start)/1e6);
                }
                case "signs" -> {if(signs==null)signs=new SignsGate(allium,this);out.putAll(signs.run(player(args[1]),Arrays.copyOfRange(args,2,args.length)));}
                case "adminhold" -> {
                    UUID id=UUID.randomUUID(),payer=uuid(args[1]);
                    var repository=new MailRepository(allium.getDatabaseManager().connectionSource(),codes.castled.allium.storage.SqlDialect.SQLITE,System::currentTimeMillis);
                    var intent=MailRecord.pendingGift(id,uuid("MoneyA"),payer,args[1],"operator fixture","fixture-preserved",1,"gate",System.currentTimeMillis());
                    var result=runtime.reservations().reserve(EconomyReservations.Kind.MAIL,id,payer,new Money(1),c->repository.send(c,intent,MailLimits.DEFAULTS,true)==MailRepository.SendResult.SAVED);
                    if(!(result instanceof Committed))throw new SQLException("hold failed");out.put("id",id.toString());
                }
                case "paidstate" -> {
                    var p=player(args[1]);UUID id=p.getUniqueId();out.put("uuid",id.toString());out.putAll(found(runtime.engine().balance(id)));
                    Map<String,Integer> items=new TreeMap<>();for(var item:p.getInventory().getStorageContents())if(item!=null&&!item.getType().isAir())items.merge(item.getType().name(),item.getAmount(),Integer::sum);out.put("items",items);
                    var kits=((KitsModule)allium.getModuleManager().getModule("kits")).kits();var mail=((MailModule)allium.getModuleManager().getModule("mail")).mail();
                    out.put("kitReady",kits.ready(p));out.put("mailBusy",mail.busy(p));
                    out.put("kitReceipt",Objects.toString(p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(allium,"kit_receipts"),org.bukkit.persistence.PersistentDataType.STRING),""));
                    out.put("mailReceipt",Objects.toString(p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(allium,"mail_receipts"),org.bukkit.persistence.PersistentDataType.STRING),""));
                    try(var c=allium.getDatabaseManager().getConnection()){
                        var rows=new ArrayList<Map<String,Object>>();
                        try(var q=c.prepareStatement("SELECT workflow_kind,workflow_id,amount_minor,state FROM allium_economy_reservations WHERE account_uuid=? ORDER BY created_at,workflow_id")){q.setString(1,id.toString());try(var r=q.executeQuery()){while(r.next())rows.add(Map.of("kind",r.getString(1),"id",r.getString(2),"amount",r.getLong(3),"state",r.getString(4)));}}out.put("reservations",rows);
                        for(var entry:Map.of("kitPending","SELECT count(*) FROM allium_kit_deliveries WHERE player_uuid=?","kitReceipts","SELECT count(*) FROM allium_kit_claim_receipts WHERE player_uuid=?","kitUsage","SELECT COALESCE(sum(claim_count),0) FROM allium_kit_usages WHERE player_uuid=?","sentPending","SELECT count(*) FROM allium_mail WHERE sender=? AND state='PENDING'").entrySet()){
                            try(var q=c.prepareStatement(entry.getValue())){q.setString(1,id.toString());try(var r=q.executeQuery()){r.next();out.put(entry.getKey(),r.getLong(1));}}
                        }
                        var box=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT id,kind,state FROM allium_mail WHERE recipient=? ORDER BY created_at,id")){q.setString(1,id.toString());try(var r=q.executeQuery()){while(r.next())box.add(Map.of("id",r.getString(1),"kind",r.getString(2),"state",r.getString(3)));}}out.put("mailbox",box);
                    }
                }
                case "seeditems" -> {
                    var p=player(args[1]);var kits=((KitsModule)allium.getModuleManager().getModule("kits")).kits();var mail=((MailModule)allium.getModuleManager().getModule("mail")).mail();
                    if(!kits.ready(p)||mail.busy(p))throw new IllegalStateException("cannot seed items during recovery");
                    p.getInventory().setStorageContents(new org.bukkit.inventory.ItemStack[36]);p.getInventory().setHeldItemSlot(0);
                    if(!args[2].equals("AIR"))p.getInventory().setItem(0,new org.bukkit.inventory.ItemStack(org.bukkit.Material.valueOf(args[2]),Integer.parseInt(args[3])));
                    p.saveData();out.put("saved",true);
                }
                case "fillinventory" -> {
                    var p=player(args[1]);if(!((KitsModule)allium.getModuleManager().getModule("kits")).kits().ready(p)||((MailModule)allium.getModuleManager().getModule("mail")).mail().busy(p))throw new IllegalStateException("recovery active");
                    var slots=new org.bukkit.inventory.ItemStack[36];for(int i=0;i<slots.length;i++)slots[i]=new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE,64);p.getInventory().setStorageContents(slots);p.saveData();out.put("saved",true);
                }
                case "retrycapture" -> {
                    var reservations=runtime.reservations();var kind=EconomyReservations.Kind.valueOf(args[1]);var id=UUID.fromString(args[2]);
                    EconomyResult.Mutation result;
                    if(args.length>3&&args[3].equals("SPEND")){var row=reservations.find(kind,id).orElseThrow();result=reservations.spend(kind,id,row.account(),row.amount(),row.intentHash(),c->{throw new SQLException("committed retry must not enter workflow");});}
                    else result=reservations.capture(kind,id,c->{throw new SQLException("committed retry must not enter workflow");});
                    out.put("kind",result.getClass().getSimpleName());if(result instanceof Committed done)out.put("replay",done.replay());
                }
                case "operation" -> {
                    UUID id=UUID.fromString(args[1]);var kind=EconomyRequest.Kind.valueOf(args[2]);
                    UUID from=uuid(args[3]),to=args[4].equals("-")?null:uuid(args[4]);
                    var request=runtime.engine().request(id,"GATE",id.toString(),kind,"GATE",from,to,new Money(Long.parseLong(args[5])));
                    var result=runtime.synchronous(request);out.put("kind",result.getClass().getSimpleName());
                    if(result instanceof Committed c){out.put("replay",c.replay());out.put("source",found(c.source()));}
                    if(result instanceof Unknown u)out.put("unknown",u.operationId().toString());
                    if(result instanceof Rejected r)out.put("reason",r.reason());
                }
                case "recovervault" -> {
                    UUID id=UUID.fromString(args[1]);var request=runtime.engine().request(id,"VAULT",id.toString(),EconomyRequest.Kind.valueOf(args[2]),"VAULT",uuid(args[3]),null,new Money(Long.parseLong(args[4])));
                    var result=runtime.synchronous(request);out.put("kind",result.getClass().getSimpleName());if(result instanceof Committed c)out.put("replay",c.replay());
                }
                case "parallel" -> {
                    String name=args[1];int n=Integer.parseInt(args[2]);parallel=Map.of("done",false);var p=account(name);
                    List<CompletableFuture<Boolean>> calls=new ArrayList<>();for(int i=0;i<n;i++)calls.add(CompletableFuture.supplyAsync(()->vault.withdrawPlayer(p,.01).transactionSuccess(),background));
                    CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).whenComplete((nothing,error)->parallel=Map.of("done",true,"successful",calls.stream().filter(f->f.isDone()&&!f.isCompletedExceptionally()&&f.join()).count(),"error",error==null?"":error.toString()));out.put("started",true);
                }
                case "parallelstate" -> out.putAll(parallel);
                case "benchmark" -> {startBenchmark(args[1],Boolean.parseBoolean(args[2]));out.put("started",true);}
                case "benchmarkstate" -> out.putAll(benchmark);
                case "realistic" -> {startRealistic(args[1],Integer.parseInt(args[2]),args.length>3?args[3]:"pooled");out.put("started",true);}
                default -> throw new IllegalArgumentException("unknown fixture command");
            }
            getLogger().info("S20G-DONE "+text+" => "+json(out));
        }catch(Throwable error){getLogger().info("S20G-FAIL "+text+" => "+error);}
        return true;
    }
    private void startBenchmark(String name,boolean separate)throws Exception {
        if(load.get())throw new IllegalStateException("benchmark already active");
        benchmark=Map.of("done",false);load.set(true);writeRounds.set(0);
        UUID writer=uuid("WriteLoad");var preferences=(PreferencesModule)allium.getModuleManager().getModule("preferences");
        var moderation=(ModerationModule)allium.getModuleManager().getModule("moderation");
        var mail=new MailRepository(allium.getDatabaseManager().connectionSource(),allium.getDatabaseManager().dialect(),System::currentTimeMillis);
        var errors=new CopyOnWriteArrayList<String>();
        var loadWork=background.submit(()->{
            try {while(load.get()){
                preferences.repository().apply(writer,PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES,(writeRounds.get()%2)==0));
                moderation.repository().recordAudit("BENCH","Gate",writer,"WriteLoad","FULL contention");
                long now=System.currentTimeMillis();mail.send(MailRecord.text(UUID.randomUUID(),writer,null,"Gate","load","gate",now,0),new MailLimits(10000,10000,1000,256,0,64),true);
                writeRounds.incrementAndGet();Thread.sleep(10);
            }}catch(InterruptedException expected){Thread.currentThread().interrupt();}
            catch(Exception error){errors.add(error.toString());}
        });
        Economy measured=vault;
        java.sql.Connection prototypeConnection=null;
        if(separate){
            var path=getDataFolder().toPath().resolve("economy-benchmark.db");Files.deleteIfExists(path);
            prototypeConnection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);
            final var physical=prototypeConnection;
            codes.castled.allium.storage.ConnectionSource source=()->(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close"))return null;
                try{return method.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
            });
            var engine=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true);
            UUID id=uuid(name);engine.execute(engine.request(UUID.randomUUID(),"BENCH","open",EconomyRequest.Kind.OPEN,"GATE",id,null,engine.settings().starting()));
            measured=new VaultEconomyProvider(engine,n->id,engine::ready,engine::balance,engine::execute);
        }
        final Economy provider=measured;final var physical=prototypeConnection;var player=account(name);var calls=new ArrayList<Double>();var ticks=new ArrayList<Double>();var cpu=new ArrayList<Double>();
        var bean=ManagementFactory.getThreadMXBean();long mainThread=Thread.currentThread().getId();long[] lastCpu={bean.isThreadCpuTimeSupported()?bean.getThreadCpuTime(mainThread):0};int[] batch={0};
        new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
            long nowCpu=bean.isThreadCpuTimeSupported()?bean.getThreadCpuTime(mainThread):0;cpu.add((nowCpu-lastCpu[0])/1e6);lastCpu[0]=nowCpu;long tick=System.nanoTime();
            for(int i=0;i<5;i++){
                long start=System.nanoTime();var deposited=provider.depositPlayer(player,.01);calls.add((System.nanoTime()-start)/1e6);if(!deposited.transactionSuccess())errors.add("deposit "+deposited.errorMessage);
                start=System.nanoTime();var withdrawn=provider.withdrawPlayer(player,.01);calls.add((System.nanoTime()-start)/1e6);if(!withdrawn.transactionSuccess())errors.add("withdraw "+withdrawn.errorMessage);
            }
            ticks.add((System.nanoTime()-tick)/1e6);
            if(++batch[0]>=100){cancel();load.set(false);try{loadWork.get(10,TimeUnit.SECONDS);if(physical!=null)physical.close();}catch(Exception error){errors.add("writer completion "+error);}benchmark=Map.of("done",true,"separate",separate,"callsMs",calls,"tickEconomyWallMs",ticks,"serverThreadCpuPerTickMs",cpu,"writerRounds",writeRounds.get(),"errors",errors);}
        }}.runTaskTimer(this,1,1);
    }
    private void startRealistic(String name,int seconds,String mode)throws Exception {
        if(seconds<600||!load.compareAndSet(false,true))throw new IllegalArgumentException("at least 600s, one workload at a time");
        benchmark=Map.of("done",false);
        if(!Set.of("pooled","dedicated").contains(mode))throw new IllegalArgumentException("read mode");
        var timings=new ConcurrentLinkedQueue<EconomyTiming.Sample>();
        if(Boolean.getBoolean("allium.economy.timing"))runtime.engine().observeTiming(timings::add);
        java.sql.Connection dedicated=null;Economy selected=vault;
        if(mode.equals("dedicated")){
            dedicated=DriverManager.getConnection("jdbc:sqlite:file:"+allium.getDataFolder().toPath().resolve("allium.db").toAbsolutePath()+"?mode=ro");
            try(var s=dedicated.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=1000");}
            final var physical=dedicated;
            codes.castled.allium.storage.ConnectionSource readSource=()->(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(m.getName().equals("close"))return null;try{return m.invoke(physical,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
            selected=new VaultEconomyProvider(runtime.engine(),n->uuid(n),runtime::ready,id->runtime.engine().balance(id,readSource),runtime::synchronous);
            // All reads remain independent autocommit SELECTs, no retained snapshot or cached balance.
        }
        final var readConnection=dedicated;final Economy provider=selected;
        var checkpoints=new CopyOnWriteArrayList<Map<String,Object>>();
        var preferences=(PreferencesModule)allium.getModuleManager().getModule("preferences");
        var moderation=(ModerationModule)allium.getModuleManager().getModule("moderation");
        var mail=new MailRepository(allium.getDatabaseManager().connectionSource(),allium.getDatabaseManager().dialect(),System::currentTimeMillis);
        UUID writer=uuid("RealisticLoad");var errors=new CopyOnWriteArrayList<String>();
        int preferenceMs=Integer.getInteger("allium.bench.preference-ms",2000),moderationMs=Integer.getInteger("allium.bench.moderation-ms",10000),mailMs=Integer.getInteger("allium.bench.mail-ms",15000);
        if(Math.min(preferenceMs,Math.min(moderationMs,mailMs))<100)throw new IllegalArgumentException("background intervals >=100ms");
        long begin=System.nanoTime();var counts=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        var workers=new ArrayList<Future<?>>();
        for(String type:List.of("preferences","moderation","mail"))workers.add(background.submit(()->{
            int interval=type.equals("preferences")?preferenceMs:type.equals("moderation")?moderationMs:mailMs;
            try{while(load.get()){
                Thread.sleep(interval);if(!load.get())break;
                if(type.equals("preferences"))preferences.repository().apply(writer,PreferenceChange.set(PreferenceKey.ACCEPT_MESSAGES,(counts.getOrDefault(type,0)%2)==0));
                if(type.equals("moderation"))moderation.repository().recordAudit("REALISTIC","Gate",writer,"RealisticLoad","paced FULL workload");
                if(type.equals("mail")){long now=System.currentTimeMillis();mail.send(MailRecord.text(UUID.randomUUID(),writer,null,"Gate","load","gate",now,0),new MailLimits(10000,10000,1000,256,0,64),true);}
                counts.merge(type,1,Integer::sum);
            }}catch(InterruptedException expected){Thread.currentThread().interrupt();}catch(Exception error){errors.add(type+": "+error);}
        }));
        if(Boolean.getBoolean("allium.economy.timing"))workers.add(background.submit(()->{
            try{while(load.get()){
                Thread.sleep(30000);if(!load.get())break;
                long epoch=System.currentTimeMillis(),nano=System.nanoTime();Map<String,Object> probe=new LinkedHashMap<>();probe.put("startEpochMillis",epoch);probe.put("startNano",nano);
                try(var c=DriverManager.getConnection("jdbc:sqlite:"+allium.getDataFolder().toPath().resolve("allium.db"));var s=c.createStatement()){
                    s.execute("PRAGMA busy_timeout=1000");long statement=System.nanoTime();
                    try(var r=s.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")){r.next();probe.put("busy",r.getInt(1));probe.put("walFrames",r.getInt(2));probe.put("checkpointedFrames",r.getInt(3));}
                    probe.put("statementMs",(System.nanoTime()-statement)/1e6);
                }catch(Exception error){probe.put("error",error.toString());}
                probe.put("totalMs",(System.nanoTime()-nano)/1e6);checkpoints.add(probe);
            }}catch(InterruptedException expected){Thread.currentThread().interrupt();}
        }));
        var writes=new ArrayList<Double>();var reads=new ArrayList<Double>();var ticks=new ArrayList<Double>();var cpu=new ArrayList<Double>();var player=account(name);
        var bean=ManagementFactory.getThreadMXBean();int[] tickNumber={0},writeNumber={0};
        new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
            int phase=tickNumber[0]%20;int n=phase==0?3:(phase==10||phase==15?1:0);
            long start=System.nanoTime(),cpuStart=bean.getCurrentThreadCpuTime();
            for(int i=0;i<n;i++){
                long call=System.nanoTime();var result=(writeNumber[0]++%2==0)?provider.depositPlayer(player,.01):provider.withdrawPlayer(player,.01);
                writes.add((System.nanoTime()-call)/1e6);if(!result.transactionSuccess())errors.add("Vault write: "+result.errorMessage);
            }
            for(int i=0;i<(phase%2==0?2:3);i++){long call=System.nanoTime();try{provider.getBalance(player);}catch(Exception error){errors.add("Vault read: "+error);}reads.add((System.nanoTime()-call)/1e6);}
            cpu.add((bean.getCurrentThreadCpuTime()-cpuStart)/1e6);ticks.add((System.nanoTime()-start)/1e6);tickNumber[0]++;
            if(tickNumber[0]%1200==0)getLogger().info("S20G realistic progress seconds="+(System.nanoTime()-begin)/1e9+" ticks="+tickNumber[0]);
            if(tickNumber[0]>=seconds*20 && System.nanoTime()-begin>=seconds*1_000_000_000L){
                cancel();load.set(false);
                // Publish on worker completion; never join sleepers on the server thread.
                runtime.engine().observeTiming(null);
                long activeSecondsNano=System.nanoTime()-begin;
                background.submit(()->{try{for(var work:workers)work.get(40,TimeUnit.SECONDS);if(readConnection!=null)readConnection.close();}catch(Exception error){errors.add("completion: "+error);}
                    Map<String,Object> result=new LinkedHashMap<>();result.put("done",true);result.put("readMode",mode);result.put("activeSeconds",activeSecondsNano/1e9);result.put("checkpoints",checkpoints);
                    result.put("timings",timings.stream().map(t->Map.of("kind",t.kind(),"threadId",t.threadId(),"startEpochMillis",t.startEpochMillis(),"startNano",t.startNano(),"totalNanos",t.totalNanos(),"connectionNanos",t.connectionNanos(),"statementNanos",t.statementNanos(),"commitNanos",t.commitNanos(),"coordinatorNanos",t.coordinatorNanos(),"otherNanos",t.otherNanos())).toList());
                    result.put("elapsedSeconds",(System.nanoTime()-begin)/1e9);
                    result.put("writeCallsMs",writes);result.put("readCallsMs",reads);result.put("tickEconomyWallMs",ticks);result.put("tickEconomyCpuMs",cpu);result.put("backgroundWrites",counts);result.put("backgroundIntervalsMs",Map.of("preferences",preferenceMs,"moderation",moderationMs,"mail",mailMs));result.put("errors",errors);
                    try{Files.writeString(getDataFolder().toPath().resolve("realistic-"+mode+".json"),json(result));benchmark=Map.of("done",true,"file","realistic-"+mode+".json");}catch(Exception error){benchmark=Map.of("done",true,"error",error.toString());}
                });
            }
        }}.runTaskTimer(this,1,1);
    }
    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(EconomyGate::json).toList()) + "]";
        }
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "\"" + v.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    static final class WireIds {
        static Map<String, Object> dump() throws ReflectiveOperationException {
            Map<String, Object> result = new LinkedHashMap<>();
            Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
            Class<?> detailsType = Class.forName("net.minecraft.network.ProtocolInfo$Details");
            Class<?> visitor = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.PacketType");
            for (String phase : List.of("game", "configuration", "login")) {
                String capital = phase.substring(0, 1).toUpperCase() + phase.substring(1);
                Class<?> protocols = Class.forName("net.minecraft.network.protocol." + phase + "." + capital + "Protocols");
                for (String flow : List.of("CLIENTBOUND", "SERVERBOUND")) {
                    Object template = protocols.getField(flow + "_TEMPLATE").get(null);
                    Object details = provider.getMethod("details").invoke(template);
                    Map<String, Integer> ids = new LinkedHashMap<>();
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class[]{visitor},
                            (proxy, method, a) -> {
                                if (method.getName().equals("accept")) {
                                    ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
                                }
                                return null;
                            });
                    detailsType.getMethod("listPackets", visitor).invoke(details, callback);
                    result.put(phase + "_" + flow, ids);
                }
            }
            return result;
        }
    }
}

/** Disposable fixture: real blocks, editor and client packets; no SignService invocation. */
final class SignsGate implements Listener {
    private final PluginStart allium;
    private final org.bukkit.plugin.Plugin host;
    private boolean protectedUse,protectedBreak,protectedCreate;
    private int extensions;
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pistonEvent(BlockPistonExtendEvent event){if(event.getBlock().getX()==319)extensions++;}
    private final int x=320,y=65,z=320;
    SignsGate(PluginStart allium,org.bukkit.plugin.Plugin host){this.allium=allium;this.host=host;host.getServer().getPluginManager().registerEvents(this,host);}
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent event){if(protectedUse&&event.getClickedBlock()!=null&&event.getClickedBlock().getX()==x)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breaking(BlockBreakEvent event){if(protectedBreak&&event.getBlock().getX()==x)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void change(SignChangeEvent event){if(protectedCreate&&event.getBlock().getX()==x)event.setCancelled(true);}
    Map<String,Object> run(Player p,String[] args){
        var world=p.getWorld();var block=world.getBlockAt(x,y,z);String action=args[0];
        switch(action){
            case "prepare"->{
                world.setGameRule(GameRule.NATURAL_REGENERATION,false);p.setGameMode(GameMode.CREATIVE);p.closeInventory();
                world.getBlockAt(x,y-1,z).setType(Material.STONE);world.getBlockAt(x,y+1,z).setType(Material.STONE);
                block.setType(Material.AIR);
                block.setType(args.length>1&&args[1].equals("hanging")?Material.OAK_HANGING_SIGN:Material.OAK_SIGN);
                var data=block.getBlockData();if(data instanceof Rotatable r){r.setRotation(BlockFace.SOUTH);block.setBlockData(r);}
                p.teleport(new Location(world,x+.5,y,z+2.5));
            }
            case "editor"->{Side side=Side.valueOf(args[1]);p.teleport(new Location(world,x+.5,y,side==Side.FRONT?z+2.5:z-1.5));p.openSign((Sign)block.getState(),side);}
            case "wax"->{var sign=(Sign)block.getState();sign.setWaxed(Boolean.parseBoolean(args[1]));sign.update(false,false);}
            case "protect"->{protectedCreate=Boolean.parseBoolean(args[1]);protectedUse=Boolean.parseBoolean(args[2]);protectedBreak=Boolean.parseBoolean(args[3]);}
            case "breakready"->{p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.AIR));}
            case "resetplayer"->{p.closeInventory();p.setHealth(10);p.getInventory().clear();var sword=new org.bukkit.inventory.ItemStack(Material.DIAMOND_SWORD);var meta=(org.bukkit.inventory.meta.Damageable)sword.getItemMeta();meta.setDamage(100);sword.setItemMeta(meta);p.getInventory().setItem(0,sword);}
            case "away"->{for(var player:world.getPlayers())player.teleport(new Location(world,0,70,0));world.setKeepSpawnInMemory(false);}
            case "unload"->{if(!world.unloadChunk(x>>4,z>>4,true))throw new IllegalStateException("chunk refused unload");world.getChunkAt(x>>4,z>>4).load();}
            case "return"->p.teleport(new Location(world,x+.5,y,args.length>1&&args[1].equals("BACK")?z-1.5:z+2.5));
            case "explode"->world.createExplosion(x+.5,y+.5,z+.5,6,false,true);
            case "pistonoff"->world.getBlockAt(x-2,y-1,z).setType(Material.AIR);
            case "piston"->{
                // Move the support sideways, then retract the temporary supporting head.
                world.getBlockAt(x+1,y-1,z).setType(Material.AIR);world.getBlockAt(x-1,y-1,z).setType(Material.PISTON);var data=(org.bukkit.block.data.Directional)world.getBlockAt(x-1,y-1,z).getBlockData();data.setFacing(BlockFace.EAST);world.getBlockAt(x-1,y-1,z).setBlockData(data);
                world.getBlockAt(x,y-1,z).setType(Material.STONE);world.getBlockAt(x-2,y-1,z).setType(Material.REDSTONE_BLOCK);
            }
            case "state"->{}
            default->throw new IllegalArgumentException("unknown signs fixture");
        }
        var out=new LinkedHashMap<String,Object>();out.put("x",x);out.put("y",y);out.put("z",z);out.put("material",block.getType().name());
        out.put("px",p.getLocation().getX());out.put("pz",p.getLocation().getZ());out.put("health",p.getHealth());out.put("window",p.getOpenInventory().getType().name());
        var item=p.getInventory().getItemInMainHand();out.put("damage",item.hasItemMeta()&&item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d?d.getDamage():0);
        out.put("extensions",extensions);out.put("piston",world.getBlockAt(x-1,y-1,z).getBlockData().getAsString());out.put("powered",world.getBlockAt(x-1,y-1,z).isBlockPowered());out.put("support",world.getBlockAt(x,y-1,z).getType().name());
        if(block.getState() instanceof Sign sign)for(var side:Side.values())out.put(side.name(),Objects.toString(sign.getPersistentDataContainer().get(new NamespacedKey(allium,"sign_"+side.name().toLowerCase(Locale.ROOT)),PersistentDataType.STRING),""));
        return out;
    }
}
