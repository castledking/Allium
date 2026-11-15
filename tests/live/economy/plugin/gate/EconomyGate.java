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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.management.ManagementFactory;

/** Read-only observations, real Vault API calls and controlled workload/journal boundaries. */
public final class EconomyGate extends JavaPlugin {
    private PluginStart allium;
    private EconomyRuntime runtime;
    private Economy vault;
    private final Map<UUID,PermissionAttachment> grants=new HashMap<>();
    private final ExecutorService background=Executors.newFixedThreadPool(4);
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
                case "realistic" -> {startRealistic(args[1],Integer.parseInt(args[2]));out.put("started",true);}
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
    private void startRealistic(String name,int seconds)throws Exception {
        if(seconds<600||!load.compareAndSet(false,true))throw new IllegalArgumentException("at least 600s, one workload at a time");
        benchmark=Map.of("done",false);
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
        var writes=new ArrayList<Double>();var reads=new ArrayList<Double>();var ticks=new ArrayList<Double>();var cpu=new ArrayList<Double>();var player=account(name);
        var bean=ManagementFactory.getThreadMXBean();int[] tickNumber={0},writeNumber={0};
        new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){
            int phase=tickNumber[0]%20;int n=phase==0?3:(phase==10||phase==15?1:0);
            long start=System.nanoTime(),cpuStart=bean.getCurrentThreadCpuTime();
            for(int i=0;i<n;i++){
                long call=System.nanoTime();var result=(writeNumber[0]++%2==0)?vault.depositPlayer(player,.01):vault.withdrawPlayer(player,.01);
                writes.add((System.nanoTime()-call)/1e6);if(!result.transactionSuccess())errors.add("Vault write: "+result.errorMessage);
            }
            for(int i=0;i<(phase%2==0?2:3);i++){long call=System.nanoTime();try{vault.getBalance(player);}catch(Exception error){errors.add("Vault read: "+error);}reads.add((System.nanoTime()-call)/1e6);}
            cpu.add((bean.getCurrentThreadCpuTime()-cpuStart)/1e6);ticks.add((System.nanoTime()-start)/1e6);tickNumber[0]++;
            if(tickNumber[0]%1200==0)getLogger().info("S20G realistic progress seconds="+(System.nanoTime()-begin)/1e9+" ticks="+tickNumber[0]);
            if(tickNumber[0]>=seconds*20 && System.nanoTime()-begin>=seconds*1_000_000_000L){
                cancel();load.set(false);
                // Publish on worker completion; never join sleepers on the server thread.
                background.submit(()->{try{for(var work:workers)work.get(20,TimeUnit.SECONDS);}catch(Exception error){errors.add("completion: "+error);}
                    Map<String,Object> result=new LinkedHashMap<>();result.put("done",true);result.put("elapsedSeconds",(System.nanoTime()-begin)/1e9);
                    result.put("writeCallsMs",writes);result.put("readCallsMs",reads);result.put("tickEconomyWallMs",ticks);result.put("tickEconomyCpuMs",cpu);result.put("backgroundWrites",counts);result.put("backgroundIntervalsMs",Map.of("preferences",preferenceMs,"moderation",moderationMs,"mail",mailMs));result.put("errors",errors);
                    try{Files.writeString(getDataFolder().toPath().resolve("realistic.json"),json(result));benchmark=Map.of("done",true,"file","realistic.json");}catch(Exception error){benchmark=Map.of("done",true,"error",error.toString());}
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
