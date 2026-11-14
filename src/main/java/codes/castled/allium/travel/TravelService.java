package codes.castled.allium.travel;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Server-thread caches and admission; JDBC runs only on the bounded storage worker. */
public final class TravelService implements Listener {
    private static final Map<PluginStart,TravelService> OWNERS=new WeakHashMap<>();
    private final PluginStart plugin;
    private final TravelRepository repository;
    private final TeleportService teleports;
    private final ThreadPoolExecutor io;
    private final boolean homesEnabled,warpsEnabled;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private Map<String,SavedLocation> warps=Map.of();
    private final Map<String,Long> warpRevisions=new HashMap<>();
    private final Map<String,Long> warpEpochs=new HashMap<>();
    private final Set<String> blockedPermissions=new HashSet<>();
    private Settings settings;
    private boolean warpsLoaded,closed;
    private long settingsEpoch;

    private static final class Session {
        final Player player;
        Map<String,SavedLocation> homes=Map.of();
        final Map<String,Long> revisions=new HashMap<>();
        boolean loaded,busy;
        Confirmation confirmation;
        Session(Player player){this.player=player;}
    }
    private record Confirmation(boolean warp,String name,long revision,SavedLocation point,long expires) {}
    private record Settings(int base,Map<UUID,Integer> overrides,long homeConfirm,long warpConfirm) {
        static Settings read(PluginStart p) {
            var c=p.getConfig();Map<UUID,Integer> overrides=new HashMap<>();
            for(String parent:List.of("homes","warps"))if(c.isSet(parent)&&!(c.get(parent) instanceof org.bukkit.configuration.ConfigurationSection))
                throw new IllegalArgumentException(parent+" must be a configuration section");
            var section=c.getConfigurationSection("homes.staff-overrides");
            if(c.isSet("homes.staff-overrides")&&section==null)throw new IllegalArgumentException("homes.staff-overrides must be a UUID-to-limit section");
            if(section!=null)for(String id:section.getKeys(false))overrides.put(UUID.fromString(id),integer(section.get(id),"staff override",0,1000));
            return new Settings(integer(c.get("homes.default-limit",1),"default home limit",0,1000),Map.copyOf(overrides),
                    integer(c.get("homes.overwrite-confirm-seconds",30),"home confirmation",1,300)*1000L,
                    integer(c.get("warps.overwrite-confirm-seconds",30),"warp confirmation",1,300)*1000L);
        }
        private static int integer(Object v,String name,int min,int max) {
            if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)
                throw new IllegalArgumentException(name+" must be an integer from "+min+" to "+max);
            return n.intValue();
        }
    }
    public TravelService(PluginStart plugin,TravelRepository repository,TeleportService teleports,ThreadPoolExecutor io) {
        this.plugin=plugin;this.repository=repository;this.teleports=teleports;this.io=io;settings=Settings.read(plugin);
        homesEnabled=plugin.getConfig().getBoolean("modules.homes",true);warpsEnabled=plugin.getConfig().getBoolean("modules.warps",true);
    }
    public void enable() {
        synchronized(OWNERS){TravelService previous=OWNERS.get(plugin);
            if(previous!=null&&previous!=this&&(!previous.closed||!previous.io.isTerminated()))throw new IllegalStateException("Previous travel storage has not drained");
            OWNERS.put(plugin,this);}
        plugin.getServer().getPluginManager().registerEvents(this,plugin);loadWarps(0);
        for(Player p:plugin.getServer().getOnlinePlayers())join(p);
    }
    public void disable() {
        closed=true;HandlerList.unregisterAll(this);sessions.clear();io.shutdown();
        try{if(!io.awaitTermination(3,TimeUnit.SECONDS))plugin.logger().severe("Travel shutdown timed out; outstanding SQL tasks="+io.getQueue().size());}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    public boolean reloadSettings(){try{settings=Settings.read(plugin);settingsEpoch++;sessions.values().forEach(s->s.confirmation=null);return true;}
        catch(IllegalArgumentException e){plugin.logger().warning("Kept previous travel settings: "+e.getMessage());return false;}}
    public boolean ready(UUID id){Session s=sessions.get(id);return s!=null&&same(s)&&s.loaded;}
    public boolean pending(UUID id){Session s=sessions.get(id);return s!=null&&s.busy;}
    public boolean warpsReady(){return warpsLoaded;}
    public int limit(Player p) {
        List<Integer> grants=new ArrayList<>();
        for(var permission:p.getEffectivePermissions())if(permission.getValue()&&p.hasPermission(permission.getPermission())){
            String node=permission.getPermission().toLowerCase(Locale.ROOT),suffix=null;
            if(node.startsWith("allium.sethome.multiple."))suffix=node.substring("allium.sethome.multiple.".length());
            else if(node.startsWith("allium.sethome."))suffix=node.substring("allium.sethome.".length());
            if(suffix!=null&&suffix.matches("[0-9]{1,9}"))try{grants.add(Integer.parseInt(suffix));}catch(NumberFormatException ignored){}
        }
        if(p.hasPermission("allium.sethome.unlimited"))grants.add(HomePolicy.HARD_CAP);
        return HomePolicy.maximum(settings.base(),grants,settings.overrides().getOrDefault(p.getUniqueId(),0));
    }
    public List<String> completeHomes(Player p){return completeHomes(p,"");}
    public List<String> completeWarps(Player p){return completeWarps(p,"");}
    public List<String> completeHomes(Player p,String prefix){Session s=sessions.get(p.getUniqueId());return !homesEnabled||s==null||!same(s)||!s.loaded?List.of():s.homes.keySet().stream().filter(n->n.startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().limit(50).toList();}
    public List<String> completeWarps(Player p,String prefix){return !warpsEnabled||!warpsLoaded||!p.hasPermission("allium.warp")?List.of():warps.keySet().stream().filter(n->access(p,n)&&n.startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().limit(50).toList();}
    public void list(Player p,boolean warp) {
        if(!permission(p,warp?"warps":"homes"))return;
        if(warp&&!warpsLoaded){send(p,Messages.TRAVEL_LOADING);return;}
        if(!warp&&session(p)==null)return;
        List<String> names=warp?warps.keySet().stream().filter(n->p.hasPermission("allium.warp")&&access(p,n)).sorted().toList():sessions.get(p.getUniqueId()).homes.keySet().stream().sorted().toList();
        if(names.isEmpty()){send(p,warp?Messages.WARP_NONE:Messages.HOME_NONE);return;}
        String rendered=String.join(", ",names.stream().limit(1000).toList())+(names.size()>1000?" (first 1000 of "+names.size()+")":"");
        send(p,warp?Messages.WARP_LIST:Messages.HOME_LIST,"{warps}",rendered,"{homes}",rendered);
    }
    public void use(Player p,String raw,boolean warp) {
        if(!permission(p,warp?"warp":"home"))return;
        String name=name(p,raw);if(name==null)return;
        Session s=session(p);if(s==null||!admit(s))return;
        if(warp&&!warpsLoaded){s.busy=false;send(p,Messages.TRAVEL_LOADING);return;}
        if(warp&&!access(p,name)){s.busy=false;notFound(p,name,true);return;}
        readEntry(s,name,warp,found->{
            if(found.isEmpty()){forget(s,name,warp);notFound(p,name,warp);return;}
            StoredDestination entry=found.get();remember(s,entry,warp);
            if(warp&&!access(p,name)){notFound(p,name,true);return;}
            teleports.teleport(p,()->location(entry.location()),warp?"warp":"home",()->same(s)&&s.loaded
                    &&(!warp||warpsLoaded&&access(p,name))&&Objects.equals((warp?warpRevisions:s.revisions).get(name),entry.revision())
                    &&Objects.equals((warp?warps:s.homes).get(name),entry.location()));
        });
    }
    public void set(Player p,String raw,boolean warp,boolean confirm) {
        if(!permission(p,warp?"setwarp":"sethome"))return;
        String name=name(p,raw);if(name==null)return;Session s=session(p);if(s==null||!admit(s))return;
        if(warp&&!warpsLoaded){s.busy=false;send(p,Messages.TRAVEL_LOADING);return;}
        SavedLocation point=saved(p.getLocation());
        if(confirm){
            Confirmation token=s.confirmation;s.confirmation=null;
            if(token==null||token.warp()!=warp||!token.name().equals(name)||System.currentTimeMillis()>=token.expires()){
                s.busy=false;send(p,Messages.TRAVEL_CONFIRM_EXPIRED);return;
            }
            invalidate(s,name,warp);
            query(s,warp,()->warp?repository.overwriteWarp(name,token.revision(),token.point()):repository.overwriteHome(p.getUniqueId(),name,token.revision(),token.point()),
                    result->finishMutation(s,name,warp,result));return;
        }
        s.confirmation=null;int limit=limit(p);long generation=settingsEpoch;
        query(s,warp,()->warp?repository.createWarp(name,point):repository.createHome(p.getUniqueId(),name,point,limit),result->{
            if(result==TravelRepository.MutationResult.EXISTS){
                if(!admit(s))return;
                readEntry(s,name,warp,found->{
                    if(generation!=settingsEpoch){send(p,Messages.TRAVEL_CHANGED);return;}
                    if(found.isEmpty()){send(p,Messages.TRAVEL_CHANGED);return;}
                    s.confirmation=new Confirmation(warp,name,found.get().revision(),point,System.currentTimeMillis()+(warp?settings.warpConfirm():settings.homeConfirm()));
                    send(p,Messages.TRAVEL_CONFIRM,"{command}","/"+(warp?"setwarp ":"sethome ")+name+" confirm");
                });
            }else finishMutation(s,name,warp,result);
        });
    }
    public void delete(Player p,String raw,boolean warp) {
        if(!permission(p,warp?"delwarp":"delhome"))return;
        String name=name(p,raw);if(name==null)return;Session s=session(p);if(s==null||!admit(s))return;
        s.confirmation=null;
        readEntry(s,name,warp,found->{
            if(found.isEmpty()){forget(s,name,warp);notFound(p,name,warp);return;}
            if(!permission(p,warp?"delwarp":"delhome")||!admit(s))return;
            invalidate(s,name,warp);
            query(s,warp,()->warp?repository.deleteWarp(name,found.get().revision()):repository.deleteHome(p.getUniqueId(),name,found.get().revision()),
                    result->finishMutation(s,name,warp,result));
        });
    }
    private void finishMutation(Session s,String name,boolean warp,TravelRepository.MutationResult result) {
        switch(result){
            case LIMIT->send(s.player,Messages.TRAVEL_LIMIT,"{limit}",Integer.toString(limit(s.player)));
            case STALE,EXISTS->send(s.player,Messages.TRAVEL_CHANGED);
            case MISSING->{forget(s,name,warp);notFound(s.player,name,warp);}
            case DELETED->{forget(s,name,warp);send(s.player,warp?Messages.WARP_DELETED:Messages.HOME_DELETED,"{name}",name);}
            case CREATED,UPDATED->{
                invalidate(s,name,warp);
                if(!admit(s))return;
                readEntry(s,name,warp,entry->{
                    if(entry.isPresent()){remember(s,entry.get(),warp);send(s.player,warp?Messages.WARP_SET:Messages.HOME_SET,"{name}",name);}
                    else {forget(s,name,warp);send(s.player,Messages.TRAVEL_CHANGED);}
                });
            }
        }
    }
    private void invalidate(Session s,String name,boolean warp){
        if(warp){warpEpochs.merge(name,1L,Long::sum);warpRevisions.remove(name);}else s.revisions.remove(name);
    }
    private void readEntry(Session s,String name,boolean warp,Consumer<Optional<StoredDestination>> done){
        long epoch=warpEpochs.getOrDefault(name,0L);
        query(s,()->warp?repository.warp(name):repository.home(s.player.getUniqueId(),name),entry->{
            if(warp&&epoch!=warpEpochs.getOrDefault(name,0L)){send(s.player,Messages.TRAVEL_CHANGED);return;}
            done.accept(entry);
        });
    }
    private void remember(Session s,StoredDestination e,boolean warp) {
        if(warp){Map<String,SavedLocation> copy=new HashMap<>(warps);copy.put(e.name(),e.location());ensurePermission(e.name());warps=Map.copyOf(copy);warpRevisions.put(e.name(),e.revision());}
        else {Map<String,SavedLocation> copy=new HashMap<>(s.homes);copy.put(e.name(),e.location());s.homes=Map.copyOf(copy);s.revisions.put(e.name(),e.revision());}
    }
    private void forget(Session s,String name,boolean warp){
        if(warp){Map<String,SavedLocation> copy=new HashMap<>(warps);copy.remove(name);warps=Map.copyOf(copy);warpRevisions.remove(name);}
        else {Map<String,SavedLocation> copy=new HashMap<>(s.homes);copy.remove(name);s.homes=Map.copyOf(copy);s.revisions.remove(name);}
    }
    private boolean access(Player p,String name){return warps.containsKey(name)&&!blockedPermissions.contains(name)&&p.hasPermission("allium.warps."+name);}
    private void ensurePermission(String name){
        var manager=plugin.getServer().getPluginManager();String node="allium.warps."+name;Permission permission=manager.getPermission(node);
        if(permission==null){manager.addPermission(new Permission(node,PermissionDefault.FALSE));blockedPermissions.remove(name);}
        else if(permission.getDefault()!=PermissionDefault.FALSE){
            if(blockedPermissions.add(name))plugin.logger().severe("Warp access blocked: "+node+" already has a non-false default; grant access explicitly with a false-default node");
        }else blockedPermissions.remove(name);
    }
    private boolean permission(Player p,String command){
        if(command.contains("warp")?!warpsEnabled:!homesEnabled){send(p,Messages.MODULE_DISABLED);return false;}
        if(p.hasPermission("allium."+command))return true;send(p,Messages.NO_PERMISSION);return false;
    }
    private String name(Player p,String raw){try{return TravelNames.normalize(raw);}catch(IllegalArgumentException e){send(p,Messages.TRAVEL_NAME_INVALID);return null;}}
    private void notFound(Player p,String name,boolean warp){send(p,warp?Messages.WARP_NOT_FOUND:Messages.HOME_NOT_FOUND,"{name}",name);}
    private Session session(Player p){Session s=sessions.get(p.getUniqueId());if(s==null||!same(s)||!s.loaded){send(p,Messages.TRAVEL_LOADING);return null;}return s;}
    private boolean admit(Session s){if(s.busy){send(s.player,Messages.TRAVEL_BUSY);return false;}s.busy=true;return true;}
    @FunctionalInterface private interface Read<T>{T get()throws SQLException;}
    private <T> void query(Session s,Read<T> work,Consumer<T> done){
        query(s,false,work,done);
    }
    private <T> void query(Session s,boolean warpMutation,Read<T> work,Consumer<T> done){
        try{io.execute(()->{try{T result=work.get();server(()->{if(same(s)){s.busy=false;done.accept(result);}else if(warpMutation)loadWarps(0);});}
            catch(SQLException|RuntimeException e){plugin.logger().warning("Travel storage failed for "+s.player.getUniqueId()+": "+e.getMessage());
                server(()->{if(warpMutation){warpsLoaded=false;loadWarps(0);}if(same(s)){s.busy=false;s.loaded=false;s.confirmation=null;send(s.player,Messages.TRAVEL_STORAGE_FAILED);load(s,0);}});}});}
        catch(RejectedExecutionException e){s.busy=false;send(s.player,Messages.TRAVEL_STORAGE_FAILED);}
    }
    private void join(Player p){Session s=new Session(p);sessions.put(p.getUniqueId(),s);load(s,0);}
    private void load(Session s,int attempt){
        if(closed||!same(s))return;
        try{io.execute(()->{try{var homes=repository.homes(s.player.getUniqueId());server(()->{if(same(s)){s.homes=homes;s.loaded=true;}});}
            catch(SQLException|RuntimeException e){server(()->retry(()->load(s,attempt+1),attempt,"homes "+s.player.getUniqueId()));}});}
        catch(RejectedExecutionException e){retry(()->load(s,attempt+1),attempt,"homes "+s.player.getUniqueId());}
    }
    private void loadWarps(int attempt){
        if(closed)return;
        Map<String,Long> admittedEpochs=Map.copyOf(warpEpochs);
        try{io.execute(()->{try{var loaded=repository.warpEntries();server(()->{loaded.keySet().forEach(this::ensurePermission);
                    Map<String,SavedLocation> locations=new HashMap<>(warps);
                    Set<String> names=new HashSet<>(locations.keySet());names.addAll(loaded.keySet());
                    for(String name:names){
                        if(!Objects.equals(admittedEpochs.getOrDefault(name,0L),warpEpochs.getOrDefault(name,0L)))continue;
                        StoredDestination row=loaded.get(name);
                        if(row==null){locations.remove(name);warpRevisions.remove(name);}
                        else{locations.put(name,row.location());warpRevisions.put(name,row.revision());}
                    }
                    warps=Map.copyOf(locations);warpsLoaded=true;});}
            catch(SQLException|RuntimeException e){server(()->retry(()->loadWarps(attempt+1),attempt,"warps"));}});}
        catch(RejectedExecutionException e){retry(()->loadWarps(attempt+1),attempt,"warps");}
    }
    private void retry(Runnable task,int attempt,String what){if(closed)return;if(attempt<3)plugin.getScheduler().runOnServerLater(task,250L*(attempt+1));else plugin.logger().warning("Travel data unavailable after bounded retries: "+what);}
    private void server(Runnable task){if(!closed)plugin.getScheduler().runOnServer(()->{if(!closed)task.run();});}
    private boolean same(Session s){return !closed&&sessions.get(s.player.getUniqueId())==s&&s.player.isOnline()&&plugin.getServer().getPlayer(s.player.getUniqueId())==s.player;}
    private SavedLocation saved(Location l){return new SavedLocation(l.getWorld().getUID(),l.getWorld().getName(),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());}
    private Location location(SavedLocation l){var world=plugin.getServer().getWorld(l.worldId());return world==null?null:new Location(world,l.x(),l.y(),l.z(),l.yaw(),l.pitch());}
    private void send(Player p,Messages key,String... args){if(p.isOnline()&&plugin.getServer().getPlayer(p.getUniqueId())==p)plugin.getMessageManager().send(p,key,args);}
    @EventHandler(priority=EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e){join(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e){Session s=sessions.get(e.getPlayer().getUniqueId());if(s!=null&&s.player==e.getPlayer())sessions.remove(e.getPlayer().getUniqueId());}
}
