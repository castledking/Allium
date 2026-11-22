package codes.castled.allium.travel;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
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
    /** {@code /home bed}, {@code /bed} and the {@code bed} entry of {@code /homes}. */
    public static final String PERMISSION_BED="allium.home.bed";
    /** {@code /home <player>:<home>}. */
    public static final String PERMISSION_HOME_OTHERS="allium.home.others";
    /** {@code /homes <player>}. */
    public static final String PERMISSION_HOMES_OTHERS="allium.homes.others";
    /** {@code /home <player>:bed}. */
    public static final String PERMISSION_HOME_OTHERS_BED="allium.home.others.bed";
    /** {@code /delhome <player>:<home>}. */
    public static final String PERMISSION_DELHOME_OTHERS="allium.delhome.others";
    /** {@code /renamehome <player>:<home> <newhome>}. */
    public static final String PERMISSION_RENAMEHOME_OTHERS="allium.renamehome.others";
    /** {@code /warp <warp> <player>}. */
    public static final String PERMISSION_WARP_OTHERS="allium.warp.others";
    /** {@code /warpinfo <warp>}. */
    public static final String PERMISSION_WARPINFO="allium.warpinfo";
    /** {@code /core sethomes}. */
    public static final String PERMISSION_SETHOMES="allium.sethomes";
    /** Daytime right-click bed updates {@code /home bed}. */
    public static final String PERMISSION_SETHOME_BED="allium.sethome.bed";
    /** Points compass at first home when {@code homes.compass-towards-home-perm: true}. */
    public static final String PERMISSION_HOME_COMPASS="allium.home.compass";
    public static final String BED="bed";
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
        int dbOverride=-1;
        boolean loaded,busy;
        Confirmation confirmation;
        Session(Player player){this.player=player;}
    }
    private record Confirmation(boolean warp,String name,long revision,SavedLocation point,long expires) {}
    private record Settings(int base,Map<UUID,Integer> overrides,long homeConfirm,long warpConfirm,boolean compassTowardsHome) {
        static Settings read(PluginStart p) {
            var c=p.getConfig();Map<UUID,Integer> overrides=new HashMap<>();
            for(String parent:List.of("homes","warps"))if(c.isSet(parent)&&!(c.get(parent) instanceof org.bukkit.configuration.ConfigurationSection))
                throw new IllegalArgumentException(parent+" must be a configuration section");
            var section=c.getConfigurationSection("homes.staff-overrides");
            if(c.isSet("homes.staff-overrides")&&section==null)throw new IllegalArgumentException("homes.staff-overrides must be a UUID-to-limit section");
            if(section!=null)for(String id:section.getKeys(false))overrides.put(UUID.fromString(id),integer(section.get(id),"staff override",0,1000));
            return new Settings(integer(c.get("homes.default-limit",1),"default home limit",0,1000),Map.copyOf(overrides),
                    integer(c.get("homes.overwrite-confirm-seconds",30),"home confirmation",1,300)*1000L,
                    integer(c.get("warps.overwrite-confirm-seconds",30),"warp confirmation",1,300)*1000L,
                    c.getBoolean("homes.compass-towards-home-perm",false));
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
    public boolean reloadSettings(){try{settings=Settings.read(plugin);settingsEpoch++;sessions.values().forEach(s->{s.confirmation=null;updateCompass(s);});return true;}
        catch(IllegalArgumentException e){plugin.logger().warning("Kept previous travel settings: "+e.getMessage());return false;}}
    /** The cached default /home, for death respawns where database access cannot be awaited. */
    public Location respawnHome(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || !session.loaded) return null;
        SavedLocation point = session.homes.get("home");
        return point == null ? null : location(point);
    }

    public boolean ready(UUID id){Session s=sessions.get(id);return s!=null&&same(s)&&s.loaded;}
    public boolean pending(UUID id){Session s=sessions.get(id);return s!=null&&s.busy;}
    public boolean warpsReady(){return warpsLoaded;}
    /** Server thread: how many homes an online player has, or -1 while they are loading. */
    public int homeCount(UUID id){Session s=sessions.get(id);return s==null||!same(s)||!s.loaded?-1:s.homes.size();}
    public int permissionLimit(Player p) {
        if(p.hasPermission("allium.sethome.unlimited")||p.hasPermission("allium.sethome.*"))return HomePolicy.HARD_CAP;
        List<Integer> grants=new ArrayList<>();
        for(var permission:p.getEffectivePermissions())if(permission.getValue()&&p.hasPermission(permission.getPermission())){
            String node=permission.getPermission().toLowerCase(Locale.ROOT),suffix=null;
            if(node.startsWith("allium.sethome.multiple."))suffix=node.substring("allium.sethome.multiple.".length());
            else if(node.startsWith("allium.sethome."))suffix=node.substring("allium.sethome.".length());
            if(suffix!=null&&suffix.matches("[0-9]{1,9}"))try{grants.add(Integer.parseInt(suffix));}catch(NumberFormatException ignored){}
        }
        return HomePolicy.maximum(settings.base(),grants,0);
    }
    public int limit(Player p) {
        int perm=permissionLimit(p);
        int cfg=settings.overrides().getOrDefault(p.getUniqueId(),-1);
        Session s=sessions.get(p.getUniqueId());
        int db=s!=null&&same(s)?s.dbOverride:-1;
        return HomePolicy.bounded(Math.max(perm,Math.max(cfg,db)));
    }
    public List<String> completeHomes(Player p){return completeHomes(p,"");}
    public List<String> completeWarps(Player p){return completeWarps(p,"");}
    public List<String> completeHomes(Player p,String prefix){return completeHomes(p,prefix,"home");}
    /**
     * Names for {@code /sethome}, {@code /home} or {@code /delhome}: the player's own homes, {@code bed}
     * for {@code /home}, and for staff {@code Name:} for each visible online player, then
     * {@code Name:home} from that player's loaded homes. Memory only: offline owners complete nothing.
     */
    public List<String> completeHomes(Player p,String prefix,String command){
        Session s=sessions.get(p.getUniqueId());if(!homesEnabled||s==null||!same(s)||!s.loaded)return List.of();
        String lower=prefix.toLowerCase(Locale.ROOT);boolean home=command.equals("home");
        String others=home?PERMISSION_HOME_OTHERS:command.equals("delhome")?PERMISSION_DELHOME_OTHERS:command.equals("renamehome")?PERMISSION_RENAMEHOME_OTHERS:null;
        boolean staff=others!=null&&p.hasPermission(others);
        int colon=lower.indexOf(':');
        if(colon>=0){
            if(!staff)return List.of();
            Player owner=plugin.getServer().getPlayerExact(prefix.substring(0,colon));
            if(owner==null||owner==p||!codes.castled.allium.naming.PlayerNameVisibility.canSee(p,owner))return List.of();
            Session o=sessions.get(owner.getUniqueId());if(o==null||!same(o)||!o.loaded)return List.of();
            var names=new TreeSet<>(o.homes.keySet());if(home&&p.hasPermission(PERMISSION_HOME_OTHERS_BED))names.add(BED);
            String rest=lower.substring(colon+1);
            return names.stream().filter(n->n.startsWith(rest)).limit(50).map(n->owner.getName()+":"+n).toList();
        }
        var out=new ArrayList<String>();
        var names=new TreeSet<>(s.homes.keySet());if(home&&p.hasPermission(PERMISSION_BED))names.add(BED);
        names.stream().filter(n->n.startsWith(lower)).forEach(out::add);
        if(staff)plugin.getServer().getOnlinePlayers().stream()
                .filter(o->o!=p&&codes.castled.allium.naming.PlayerNameVisibility.canSee(p,o)&&o.getName().toLowerCase(Locale.ROOT).startsWith(lower))
                .map(o->o.getName()+":").sorted().forEach(out::add);
        return out.stream().limit(50).toList();
    }
    public List<String> completeWarps(Player p,String prefix){
        if(!warpsEnabled||!warpsLoaded||(!p.hasPermission("allium.warp")&&!p.hasPermission(PERMISSION_WARPINFO)))return List.of();
        boolean infoOnly=!p.hasPermission("allium.warp")&&p.hasPermission(PERMISSION_WARPINFO);
        return warps.keySet().stream().filter(n->(infoOnly||access(p,n))&&n.startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().limit(50).toList();
    }
    /** {@code /warpinfo <warp>}: shows world, coordinates and permission node. Players or console. */
    public void warpInfo(CommandSender sender,String raw){
        if(!warpsEnabled){send(sender,Messages.MODULE_DISABLED);return;}
        if(!sender.hasPermission(PERMISSION_WARPINFO)){send(sender,Messages.NO_PERMISSION);return;}
        if(!warpsLoaded){send(sender,Messages.TRAVEL_LOADING);return;}
        String name;
        try{name=TravelNames.normalize(raw);}catch(IllegalArgumentException invalid){send(sender,Messages.TRAVEL_NAME_INVALID);return;}
        SavedLocation point=warps.get(name);
        if(point==null){send(sender,Messages.WARP_NOT_FOUND,"{name}",name);return;}
        send(sender,Messages.WARP_INFO,
                "{name}",name,
                "{world}",point.worldName(),
                "{x}",String.format(Locale.ROOT,"%.1f",point.x()),
                "{y}",String.format(Locale.ROOT,"%.1f",point.y()),
                "{z}",String.format(Locale.ROOT,"%.1f",point.z()),
                "{yaw}",String.format(Locale.ROOT,"%.1f",point.yaw()),
                "{pitch}",String.format(Locale.ROOT,"%.1f",point.pitch()),
                "{permission}","allium.warps."+name);
    }
    /** {@code /warp <warp> <player>}: sends an online player to a warp. Players or console. */
    public void warpOther(CommandSender actor,String rawWarp,String targetName){
        if(!warpsEnabled){send(actor,Messages.MODULE_DISABLED);return;}
        if(!actor.hasPermission("allium.warp")||!actor.hasPermission(PERMISSION_WARP_OTHERS)){send(actor,Messages.NO_PERMISSION);return;}
        if(!warpsLoaded){send(actor,Messages.TRAVEL_LOADING);return;}
        String name;
        try{name=TravelNames.normalize(rawWarp);}catch(IllegalArgumentException invalid){send(actor,Messages.TRAVEL_NAME_INVALID);return;}
        if(actor instanceof Player p&&!access(p,name)){send(actor,Messages.WARP_NOT_FOUND,"{name}",name);return;}
        SavedLocation point=warps.get(name);
        if(point==null){send(actor,Messages.WARP_NOT_FOUND,"{name}",name);return;}
        Player target=teleports.find(actor,targetName);
        if(target==null){send(actor,Messages.PLAYER_NOT_FOUND,"{player}",codes.castled.allium.messaging.MessagingService.safeName(targetName));return;}
        if(target==actor){use(target,name,true);return;}
        Location destination=location(point);
        if(destination==null){send(actor,Messages.TELEPORT_UNSAFE);return;}
        teleports.forceLocation(actor,target,destination,"warp");
        send(actor,Messages.WARP_OTHER,"{player}",target.getName(),"{name}",name);
    }
    /** {@code /renamehome <home> <newhome>}: renames one of the player's own homes. */
    public void rename(Player p,String rawOld,String rawNew){
        if(!permission(p,"renamehome"))return;
        String oldName=name(p,rawOld);if(oldName==null)return;
        String newName=name(p,rawNew);if(newName==null)return;
        if(oldName.equals(BED)||newName.equals(BED)){send(p,Messages.HOME_BED_RESERVED);return;}
        if(oldName.equals(newName)){send(p,Messages.HOME_EXISTS,"{name}",newName);return;}
        Session s=session(p);if(s==null||!admit(s))return;
        s.confirmation=null;
        readEntry(s,oldName,false,found->{
            if(found.isEmpty()){forget(s,oldName,false);notFound(p,oldName,false);return;}
            if(!permission(p,"renamehome")||!admit(s))return;
            long rev=found.get().revision();
            query(s,()->repository.renameHome(p.getUniqueId(),oldName,newName,rev),result->{
                switch(result){
                    case UPDATED->{
                        if(!admit(s))return;
                        readEntry(s,newName,false,renamed->{
                            forget(s,oldName,false);
                            if(renamed.isPresent()){remember(s,renamed.get(),false);send(p,Messages.HOME_RENAMED,"{old}",oldName,"{name}",newName);}
                            else send(p,Messages.TRAVEL_CHANGED);
                        });
                    }
                    case EXISTS->send(p,Messages.HOME_EXISTS,"{name}",newName);
                    case MISSING->{forget(s,oldName,false);notFound(p,oldName,false);}
                    default->send(p,Messages.TRAVEL_CHANGED);
                }
            });
        });
    }
    /** {@code /renamehome <player>:<home> <newhome>}: renames another player's home, online or offline. */
    public void renameOther(Player p,String owner,String rawOld,String rawNew){
        if(!permission(p,"renamehome"))return;
        if(!p.hasPermission(PERMISSION_RENAMEHOME_OTHERS)){send(p,Messages.NO_PERMISSION);return;}
        String oldName=name(p,rawOld.isEmpty()?"home":rawOld);if(oldName==null)return;
        String newName=name(p,rawNew);if(newName==null)return;
        if(oldName.equals(BED)||newName.equals(BED)){send(p,Messages.HOME_BED_RESERVED);return;}
        if(oldName.equals(newName)){send(p,Messages.HOME_EXISTS,"{name}",newName);return;}
        resolveOwner(p,owner,(id,ownerName)->{
            if(id.equals(p.getUniqueId())){rename(p,oldName,newName);return;}
            Session s=session(p);if(s==null||!admit(s))return;
            query(s,()->repository.home(id,oldName),found->{
                if(found.isEmpty()){forgetOwner(id,oldName);send(p,Messages.HOME_NOT_FOUND_OTHER,"{player}",ownerName,"{name}",oldName);return;}
                if(!p.hasPermission(PERMISSION_RENAMEHOME_OTHERS)||!admit(s))return;
                long rev=found.get().revision();
                SavedLocation loc=found.get().location();
                query(s,()->repository.renameHome(id,oldName,newName,rev),result->{
                    switch(result){
                        case UPDATED->{
                            forgetOwner(id,oldName);
                            Session onlineOwner=sessions.get(id);
                            if(onlineOwner!=null&&same(onlineOwner)){
                                Map<String,SavedLocation> copy=new HashMap<>(onlineOwner.homes);
                                copy.put(newName,loc);onlineOwner.homes=Map.copyOf(copy);
                                onlineOwner.revisions.remove(oldName);
                                updateCompass(onlineOwner);
                            }
                            plugin.logger().info("Home audit actor="+p.getUniqueId()+" owner="+id+" old="+oldName+" new="+newName+" action=rename");
                            send(p,Messages.HOME_RENAMED_OTHER,"{player}",ownerName,"{old}",oldName,"{name}",newName);
                        }
                        case EXISTS->send(p,Messages.HOME_EXISTS,"{name}",newName);
                        case MISSING->{forgetOwner(id,oldName);send(p,Messages.HOME_NOT_FOUND_OTHER,"{player}",ownerName,"{name}",oldName);}
                        default->send(p,Messages.TRAVEL_CHANGED);
                    }
                });
            });
        });
    }
    /** {@code /core sethomes <player> [number|+N|-N|-1]}: view or override a player's max homes. */
    public void sethomesAdmin(CommandSender sender,String[] args){
        if(!homesEnabled){send(sender,Messages.MODULE_DISABLED);return;}
        if(!sender.hasPermission(PERMISSION_SETHOMES)&&!sender.hasPermission("allium.admin")){send(sender,Messages.NO_PERMISSION);return;}
        if(args.length<1||args.length>2){
            plugin.getMessageManager().send(sender,Messages.INVALID_ARGS,"{usage}","/allium sethomes <player> [number|+N|-N|-1]");
            return;
        }
        String rawPlayer=args[0];
        String rawValue=args.length==2?args[1].trim():null;
        Player onlineExact=plugin.getServer().getPlayerExact(rawPlayer);
        if(onlineExact!=null){
            runSethomesAdmin(sender,onlineExact.getUniqueId(),onlineExact.getName(),rawValue);
            return;
        }
        if(!codes.castled.allium.naming.NameCache.isValidName(rawPlayer)){send(sender,Messages.PLAYER_NOT_FOUND,"{player}",rawPlayer);return;}
        var names=plugin.getPlayerNames();
        if(names==null){send(sender,Messages.TRAVEL_LOADING);return;}
        names.resolve(rawPlayer).whenComplete((found,error)->server(()->{
            if(sender instanceof Player p&&!p.isOnline())return;
            if(error!=null){send(sender,Messages.TRAVEL_STORAGE_FAILED);return;}
            if(found==null||found.isEmpty()){send(sender,Messages.PLAYER_NOT_FOUND,"{player}",rawPlayer);return;}
            runSethomesAdmin(sender,found.get().uuid(),found.get().name(),rawValue);
        }));
    }
    private void runSethomesAdmin(CommandSender sender,UUID targetId,String targetName,String rawValue){
        try{
            io.execute(()->{
                try{
                    OptionalInt currentDb=repository.homeLimitOverride(targetId);
                    int count=repository.homeCount(targetId);
                    server(()->{
                        if(sender instanceof Player p&&!p.isOnline())return;
                        Player online=plugin.getServer().getPlayer(targetId);
                        int cfgOverride=settings.overrides().getOrDefault(targetId,-1);
                        int dbOverride=currentDb.orElse(-1);
                        int effectiveOverride=Math.max(cfgOverride,dbOverride);
                        int perm=online!=null?permissionLimit(online):-1;
                        int effective=perm>=0?HomePolicy.bounded(Math.max(perm,effectiveOverride)):Math.max(effectiveOverride,settings.base());
                        if(rawValue==null){
                            send(sender,Messages.HOME_LIMIT_INFO,
                                    "{player}",targetName,
                                    "{effective}",formatHomeLimit(effective),
                                    "{permissions}",perm<0?"unknown (offline)":formatHomeLimit(perm),
                                    "{override}",effectiveOverride<0?"none":Integer.toString(effectiveOverride),
                                    "{count}",Integer.toString(count));
                            return;
                        }
                        int parsed;
                        if(rawValue.startsWith("+")||(rawValue.startsWith("-")&&rawValue.length()>1&&!rawValue.equals("-1"))){
                            if(effective>=HomePolicy.HARD_CAP){
                                send(sender,Messages.HOME_LIMIT_UNLIMITED_RELATIVE,"{player}",targetName);
                                return;
                            }
                            try{
                                int delta=Integer.parseInt(rawValue.substring(1));
                                parsed=effective+(rawValue.startsWith("+")?delta:-delta);
                            }catch(NumberFormatException invalid){send(sender,Messages.HOME_LIMIT_INVALID);return;}
                        }else{
                            try{parsed=Integer.parseInt(rawValue);}
                            catch(NumberFormatException invalid){send(sender,Messages.HOME_LIMIT_INVALID);return;}
                        }
                        if(parsed<-1||parsed>HomePolicy.HARD_CAP){send(sender,Messages.HOME_LIMIT_INVALID);return;}
                        final int toSave=parsed;
                        io.execute(()->{
                            try{
                                repository.setHomeLimitOverride(targetId,toSave);
                                server(()->{
                                    Session onlineSession=sessions.get(targetId);
                                    if(onlineSession!=null&&same(onlineSession))onlineSession.dbOverride=toSave;
                                    Player nowOnline=plugin.getServer().getPlayer(targetId);
                                    int newPerm=nowOnline!=null?permissionLimit(nowOnline):-1;
                                    int newEffOverride=Math.max(settings.overrides().getOrDefault(targetId,-1),toSave);
                                    int newEffective=newPerm>=0?HomePolicy.bounded(Math.max(newPerm,newEffOverride)):Math.max(newEffOverride,settings.base());
                                    if(sender instanceof Player p&&!p.isOnline())return;
                                    if(toSave<0){
                                        send(sender,Messages.HOME_LIMIT_CLEARED,"{player}",targetName,"{effective}",formatHomeLimit(newEffective));
                                    }else{
                                        send(sender,Messages.HOME_LIMIT_SET,"{player}",targetName,"{override}",Integer.toString(toSave),"{effective}",formatHomeLimit(newEffective));
                                    }
                                });
                            }catch(SQLException|RuntimeException e){
                                plugin.logger().warning("Failed to update home limit override for "+targetId+": "+e.getMessage());
                                server(()->send(sender,Messages.TRAVEL_STORAGE_FAILED));
                            }
                        });
                    });
                }catch(SQLException|RuntimeException e){
                    plugin.logger().warning("Failed to read home limit override for "+targetId+": "+e.getMessage());
                    server(()->send(sender,Messages.TRAVEL_STORAGE_FAILED));
                }
            });
        }catch(RejectedExecutionException busy){send(sender,Messages.TRAVEL_STORAGE_FAILED);}
    }
    private static String formatHomeLimit(int limit){return limit>=HomePolicy.HARD_CAP?"unlimited":Integer.toString(limit);}
    public void list(Player p,boolean warp) {
        if(!permission(p,warp?"warps":"homes"))return;
        if(warp&&!warpsLoaded){send(p,Messages.TRAVEL_LOADING);return;}
        if(!warp&&session(p)==null)return;
        List<String> names=warp?warps.keySet().stream().filter(n->p.hasPermission("allium.warp")&&access(p,n)).sorted().toList():sessions.get(p.getUniqueId()).homes.keySet().stream().sorted().toList();
        // Holders of allium.home.bed see their bed last: plain when set, struck through when not.
        String bed=warp||!p.hasPermission(PERMISSION_BED)?null:plugin.getMessageManager().resolve(RespawnPoints.of(p)==null?Messages.HOME_BED_MISSING_ENTRY:Messages.HOME_BED_ENTRY);
        if(names.isEmpty()&&bed==null){send(p,warp?Messages.WARP_NONE:Messages.HOME_NONE);return;}
        String rendered=render(names);
        if(bed!=null)rendered=rendered.isEmpty()?bed:rendered+", "+bed;
        send(p,warp?Messages.WARP_LIST:Messages.HOME_LIST,"{warps}",rendered,"{homes}",rendered);
    }
    private static String render(List<String> names){
        return String.join(", ",names.stream().limit(1000).toList())+(names.size()>1000?" (first 1000 of "+names.size()+")":"");
    }
    /** {@code /homes <player>}: another player's homes, online or not, read from storage. */
    public void listOther(Player p,String owner){
        if(!permission(p,"homes"))return;
        if(!p.hasPermission(PERMISSION_HOMES_OTHERS)){send(p,Messages.NO_PERMISSION);return;}
        listOwner(p,owner,PERMISSION_HOMES_OTHERS);
    }
    private void listOwner(Player p,String owner,String node){
        resolveOwner(p,owner,(id,ownerName)->{
            if(id.equals(p.getUniqueId())){list(p,false);return;}
            Session s=session(p);if(s==null||!admit(s))return;
            query(s,()->repository.homes(id),homes->{
                if(!p.hasPermission(node)){send(p,Messages.NO_PERMISSION);return;}
                List<String> names=homes.keySet().stream().sorted().toList();
                if(names.isEmpty()){send(p,Messages.HOME_NONE_OTHER,"{player}",ownerName);return;}
                send(p,Messages.HOME_LIST_OTHER,"{player}",ownerName,"{homes}",render(names));
            });
        });
    }
    /**
     * {@code /home <player>:<home>}: teleports staff to another player's home. As in legacy
     * Allium, {@code <player>:} lists that player's homes and {@code <player>:bed} goes to their bed.
     */
    public void useOther(Player p,String owner,String rawHome){
        if(!permission(p,"home"))return;
        if(!p.hasPermission(PERMISSION_HOME_OTHERS)){send(p,Messages.NO_PERMISSION);return;}
        if(rawHome.isEmpty()){listOwner(p,owner,PERMISSION_HOME_OTHERS);return;}
        if(BED.equalsIgnoreCase(rawHome)){bedOther(p,owner);return;}
        String name=name(p,rawHome);if(name==null)return;
        resolveOwner(p,owner,(id,ownerName)->{
            if(id.equals(p.getUniqueId())){use(p,name,false);return;}
            Session s=session(p);if(s==null||!admit(s))return;
            query(s,()->repository.home(id,name),found->{
                if(found.isEmpty()){send(p,Messages.HOME_NOT_FOUND_OTHER,"{player}",ownerName,"{name}",name);return;}
                SavedLocation point=found.get().location();
                plugin.logger().info("Home audit actor="+p.getUniqueId()+" owner="+id+" home="+name);
                teleports.teleport(p,()->location(point),"home",()->same(s)&&p.hasPermission(PERMISSION_HOME_OTHERS));
            });
        });
    }
    /**
     * {@code /home <player>:bed}: another player's bed or respawn anchor. An online owner's is checked
     * as theirs would be; an offline owner's is the respawn point saved in their player file.
     */
    private void bedOther(Player p,String owner){
        if(!p.hasPermission(PERMISSION_HOME_OTHERS_BED)){send(p,Messages.NO_PERMISSION);return;}
        resolveOwner(p,owner,(id,ownerName)->{
            if(id.equals(p.getUniqueId())){bed(p);return;}
            if(RespawnPoints.of(plugin.getServer().getOfflinePlayer(id))==null){send(p,Messages.HOME_BED_MISSING_OTHER,"{player}",ownerName);return;}
            plugin.logger().info("Home audit actor="+p.getUniqueId()+" owner="+id+" home=bed");
            teleports.teleport(p,()->RespawnPoints.of(plugin.getServer().getOfflinePlayer(id)),"home",
                    ()->p.isOnline()&&p.hasPermission(PERMISSION_HOME_OTHERS_BED));
        });
    }
    /**
     * {@code /delhome <player>:<home>}: deletes another player's home at the revision just read, so a
     * home the owner changed in between is reported as changed rather than deleted. An online owner's
     * loaded homes are updated at once.
     */
    public void deleteOther(Player p,String owner,String rawHome){
        if(!permission(p,"delhome"))return;
        if(!p.hasPermission(PERMISSION_DELHOME_OTHERS)){send(p,Messages.NO_PERMISSION);return;}
        String name=name(p,rawHome.isEmpty()?"home":rawHome);if(name==null)return;
        resolveOwner(p,owner,(id,ownerName)->{
            if(id.equals(p.getUniqueId())){delete(p,name,false);return;}
            Session s=session(p);if(s==null||!admit(s))return;
            query(s,()->repository.home(id,name),found->{
                if(found.isEmpty()){forgetOwner(id,name);send(p,Messages.HOME_NOT_FOUND_OTHER,"{player}",ownerName,"{name}",name);return;}
                if(!p.hasPermission(PERMISSION_DELHOME_OTHERS)){send(p,Messages.NO_PERMISSION);return;}
                if(!admit(s))return;
                long revision=found.get().revision();
                query(s,()->repository.deleteHome(id,name,revision),result->{
                    switch(result){
                        case DELETED->{
                            forgetOwner(id,name);
                            plugin.logger().info("Home audit actor="+p.getUniqueId()+" owner="+id+" home="+name+" action=delete");
                            send(p,Messages.HOME_DELETED_OTHER,"{player}",ownerName,"{name}",name);
                        }
                        case MISSING->{forgetOwner(id,name);send(p,Messages.HOME_NOT_FOUND_OTHER,"{player}",ownerName,"{name}",name);}
                        default->send(p,Messages.TRAVEL_CHANGED);
                    }
                });
            });
        });
    }
    private void forgetOwner(UUID id,String name){
        Session o=sessions.get(id);if(o==null||!same(o))return;
        forget(o,name,false);
        if(o.confirmation!=null&&!o.confirmation.warp()&&o.confirmation.name().equals(name))o.confirmation=null;
    }
    /** {@code /bed} and {@code /home bed}: the player's bed or respawn anchor, checked again when the teleport lands. */
    public void bed(Player p){
        if(!homesEnabled){send(p,Messages.MODULE_DISABLED);return;}
        if(!p.hasPermission(PERMISSION_BED)){send(p,Messages.NO_PERMISSION);return;}
        if(RespawnPoints.of(p)==null){send(p,Messages.HOME_BED_MISSING);return;}
        teleports.teleport(p,()->RespawnPoints.of(p),"home",()->p.isOnline()&&p.hasPermission(PERMISSION_BED));
    }
    /** Name lookups go through the shared name index, so offline players resolve too. */
    private void resolveOwner(Player p,String owner,java.util.function.BiConsumer<UUID,String> then){
        if(!codes.castled.allium.naming.NameCache.isValidName(owner)){send(p,Messages.PLAYER_NOT_FOUND,"{player}",owner);return;}
        var names=plugin.getPlayerNames();if(names==null){send(p,Messages.TRAVEL_LOADING);return;}
        names.resolve(owner).whenComplete((found,error)->server(()->{
            if(!p.isOnline())return;
            if(error!=null){send(p,Messages.TRAVEL_STORAGE_FAILED);return;}
            if(found==null||found.isEmpty()){send(p,Messages.PLAYER_NOT_FOUND,"{player}",owner);return;}
            then.accept(found.get().uuid(),found.get().name());
        }));
    }
    public void use(Player p,String raw,boolean warp) {use(p,raw,warp,false);}
    /** Public access is frozen only in a server-owned sign whose creator has the extra permission. */
    public void useSign(Player p,String raw,boolean publicAccess){use(p,raw,true,publicAccess);}
    /** Non-mutating readiness and access precheck for priced {@code [Warp]} signs before charging. */
    public boolean canUseSign(Player p,String raw,boolean publicAccess){
        if(!permission(p,"warp"))return false;
        String name=name(p,raw);if(name==null)return false;
        Session s=session(p);if(s==null)return false;
        if(!warpsLoaded){send(p,Messages.TRAVEL_LOADING);return false;}
        if(!publicAccess&&!access(p,name)){notFound(p,name,true);return false;}
        if(!warps.containsKey(name)){notFound(p,name,true);return false;}
        return true;
    }
    private void use(Player p,String raw,boolean warp,boolean publicAccess) {
        if(!permission(p,warp?"warp":"home"))return;
        if(!warp&&BED.equalsIgnoreCase(raw)&&p.hasPermission(PERMISSION_BED)){bed(p);return;}
        String name=name(p,raw);if(name==null)return;
        Session s=session(p);if(s==null)return;
        // Like Essentials, a plain /home with no homes set goes to the bed when there is one.
        if(!warp&&name.equals("home")&&s.homes.isEmpty()&&!s.busy&&p.hasPermission(PERMISSION_BED)&&RespawnPoints.of(p)!=null){bed(p);return;}
        if(!admit(s))return;
        if(warp&&!warpsLoaded){s.busy=false;send(p,Messages.TRAVEL_LOADING);return;}
        if(warp&&!publicAccess&&!access(p,name)){s.busy=false;notFound(p,name,true);return;}
        readEntry(s,name,warp,found->{
            if(found.isEmpty()){forget(s,name,warp);notFound(p,name,warp);return;}
            StoredDestination entry=found.get();remember(s,entry,warp);
            if(warp&&!publicAccess&&!access(p,name)){notFound(p,name,true);return;}
            teleports.teleport(p,()->location(entry.location()),warp?"warp":"home",()->same(s)&&s.loaded
                    &&(!warp||warpsLoaded&&(publicAccess||access(p,name)))&&Objects.equals((warp?warpRevisions:s.revisions).get(name),entry.revision())
                    &&Objects.equals((warp?warps:s.homes).get(name),entry.location()));
        });
    }
    public void set(Player p,String raw,boolean warp,boolean confirm) {
        if(!permission(p,warp?"setwarp":"sethome"))return;
        String name=name(p,raw);if(name==null)return;
        if(!warp&&name.equals(BED)){send(p,Messages.HOME_BED_RESERVED);return;}Session s=session(p);if(s==null||!admit(s))return;
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
        else {Map<String,SavedLocation> copy=new HashMap<>(s.homes);copy.put(e.name(),e.location());s.homes=Map.copyOf(copy);s.revisions.put(e.name(),e.revision());updateCompass(s);}
    }
    private void forget(Session s,String name,boolean warp){
        if(warp){Map<String,SavedLocation> copy=new HashMap<>(warps);copy.remove(name);warps=Map.copyOf(copy);warpRevisions.remove(name);}
        else {Map<String,SavedLocation> copy=new HashMap<>(s.homes);copy.remove(name);s.homes=Map.copyOf(copy);s.revisions.remove(name);updateCompass(s);}
    }
    private void updateCompass(Session s){
        if(!homesEnabled||!settings.compassTowardsHome()||s==null||!same(s)||!s.loaded||s.homes.isEmpty())return;
        if(!s.player.hasPermission(PERMISSION_HOME_COMPASS))return;
        SavedLocation first=s.homes.containsKey("home")?s.homes.get("home"):s.homes.entrySet().stream()
                .min(Map.Entry.comparingByKey()).map(Map.Entry::getValue).orElse(null);
        if(first==null)return;
        Location target=location(first);
        if(target!=null)s.player.setCompassTarget(target);
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
        try{io.execute(()->{try{var homes=repository.homes(s.player.getUniqueId());var rawOverride=repository.homeLimitOverride(s.player.getUniqueId());int override=rawOverride==null?-1:rawOverride.orElse(-1);
                server(()->{if(same(s)){s.homes=homes;s.dbOverride=override;s.loaded=true;updateCompass(s);}});}
            catch(SQLException|RuntimeException e){server(()->retry(()->load(s,attempt+1),attempt,"homes "+s.player.getUniqueId()));}});}
        catch(RejectedExecutionException e){retry(()->load(s,attempt+1),attempt,"homes "+s.player.getUniqueId());}
    }
    /** Reloads warps from storage (after an import wrote them). Server thread. */
    public void refreshWarps(){loadWarps(0);}
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
    private void send(CommandSender sender,Messages key,String... args){
        if(sender instanceof Player p){send(p,key,args);return;}
        plugin.getMessageManager().send(sender,key,args);
    }
    private void send(Player p,Messages key,String... args){if(p.isOnline()&&plugin.getServer().getPlayer(p.getUniqueId())==p)plugin.getMessageManager().send(p,key,args);}
    @EventHandler(priority=EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e){join(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e){Session s=sessions.get(e.getPlayer().getUniqueId());if(s!=null&&s.player==e.getPlayer())sessions.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void onRespawn(PlayerRespawnEvent e){server(()->updateCompass(sessions.get(e.getPlayer().getUniqueId())));}
    @EventHandler(priority=EventPriority.MONITOR) public void onWorldChange(PlayerChangedWorldEvent e){updateCompass(sessions.get(e.getPlayer().getUniqueId()));}
    /** {@code allium.sethome.bed}: right-clicking a bed during daytime updates {@code /home bed}. */
    @EventHandler(priority=EventPriority.NORMAL,ignoreCancelled=true) public void onDaytimeBedClick(PlayerInteractEvent e){
        if(!homesEnabled||e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getHand()!=EquipmentSlot.HAND)return;
        var block=e.getClickedBlock();
        if(block==null||!isBed(block.getType()))return;
        Player player=e.getPlayer();
        if(player.isSneaking()||!player.hasPermission(PERMISSION_SETHOME_BED))return;
        World world=block.getWorld();
        if(world.getEnvironment()!=World.Environment.NORMAL||world.getTime()>=12541L||world.isThundering())return;
        if(RespawnPoints.set(player,block.getLocation())){
            e.setCancelled(true);
            send(player,Messages.HOME_BED_SET);
        }
    }
    private static boolean isBed(Material type){return type!=null&&type.name().endsWith("_BED");}
}
