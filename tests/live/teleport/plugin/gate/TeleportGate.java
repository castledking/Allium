package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.message.Messages;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.module.PreferencesModule;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Console-only fixture. Commands, requests, movement, respawn and completions use real packets. */
public final class TeleportGate extends JavaPlugin implements Listener {
    private PluginStart allium;
    private Object initialService;
    private final Map<UUID,PermissionAttachment> permissions=new HashMap<>();
    private final Map<UUID,List<Object>> events=new HashMap<>();
    private boolean preparing;
    private long eventSequence;
    @Override public void onEnable() {
        if(!getServer().getIp().equals("127.0.0.1") || !Set.of(25740,25741).contains(getServer().getPort()))
            throw new IllegalStateException("TEST ONLY: owned 127.0.0.1:25740/25741 required");
        allium=(PluginStart)getServer().getPluginManager().getPlugin("Allium");
        try {
            initialService=service();
            Files.createDirectories(getDataFolder().toPath());
            try {
                Map<String,Object> wire=new LinkedHashMap<>();
                wire.put("wirePackets",WireIds.dump());
                wire.put("codecs",NativePackets.proofs());
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),json(wire));
            } catch(ClassNotFoundException legacy) {getLogger().info("S13G legacy protocol 763 client required");}
        } catch(Exception e) {throw new IllegalStateException(e);}
        getServer().getPluginManager().registerEvents(this,this);
        for(World w:getServer().getWorlds()) {w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);}
        getLogger().info("S13G-GATE READY "+getServer().getBukkitVersion());
    }
    private Object service() throws Exception {
        Object m=allium.getModuleManager().getModule("teleport");
        return m.getClass().getMethod("teleports").invoke(m);
    }
    private Map<String,Object> location(Location l) {
        Map<String,Object> o=new LinkedHashMap<>();
        o.put("world",l.getWorld().getName());o.put("worldUUID",l.getWorld().getUID().toString());
        o.put("x",l.getX());o.put("y",l.getY());o.put("z",l.getZ());o.put("yaw",l.getYaw());o.put("pitch",l.getPitch());
        return o;
    }
    private void record(Player p,String type,Map<String,Object> detail) {
        var e=new LinkedHashMap<String,Object>(detail);e.put("type",type);e.put("preparing",preparing);e.put("time",System.currentTimeMillis());e.put("sequence",++eventSequence);
        var list=events.computeIfAbsent(p.getUniqueId(),id->new ArrayList<>());
        if(list.size()>=500)list.remove(0);list.add(e);
    }
    private Map<String,Object> state(Player p) throws Exception {
        Map<String,Object> o=location(p.getLocation());
        var prefs=(PreferencesModule)allium.getModuleManager().getModule("preferences");
        o.put("uuid",p.getUniqueId().toString());o.put("loaded",prefs.effective(p.getUniqueId()).isPresent());
        o.put("acceptTeleportRequests",prefs.effective(p.getUniqueId()).map(v->v.acceptTeleportRequests()).orElse(null));
        Object teleports=service();
        for(String method:List.of("ready","warming","historyPending"))o.put(method,teleports.getClass().getMethod(method,UUID.class).invoke(teleports,p.getUniqueId()));
        o.put("pendingRequests",teleports.getClass().getMethod("pendingRequests").invoke(teleports));
        o.put("op",p.isOp());o.put("toggleBypass",p.hasPermission("allium.teleport.bypass.toggle"));o.put("toggleBypassExplicit",p.isPermissionSet("allium.teleport.bypass.toggle"));
        o.put("sameService",service()==initialService);o.put("dead",p.isDead());o.put("health",p.getHealth());
        o.put("eventSequence",eventSequence);o.put("events",events.getOrDefault(p.getUniqueId(),List.of()));
        o.put("safeFeet",p.getLocation().getBlock().isPassable());
        o.put("safeHead",p.getLocation().add(0,1,0).getBlock().isPassable());
        o.put("floor",p.getLocation().subtract(0,1,0).getBlock().getType().name());
        // Read-only structural observation; no mutation of service internals.
        Map<String,Object> internal=new LinkedHashMap<>();
        Object s=service();
        for(Field f:s.getClass().getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers()))continue;
            f.setAccessible(true);Object value=f.get(s);
            if(value instanceof Map<?,?> m)internal.put(f.getName()+"Size",m.size());
            if(value instanceof Collection<?> c)internal.put(f.getName()+"Size",c.size());
        }
        o.put("internal",internal);return o;
    }
    private void pad(World w,int x,int z,String type) {
        // Unsafe volume exceeds the maximum configured search bounds, avoiding accidental safe edges.
        int r=type.equals("safe")?4:10;
        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            for(int y=62;y<=88;y++)w.getBlockAt(x+dx,y,z+dz).setType(Material.AIR,false);
            if(!type.equals("void")&&!type.equals("fall"))w.getBlockAt(x+dx,79,z+dz).setType(type.equals("lava")?Material.LAVA:Material.STONE,false);
            if(type.equals("headspace"))for(int y=80;y<=88;y++)w.getBlockAt(x+dx,y,z+dz).setType(Material.STONE,false);
        }
        if(type.equals("headspace"))w.getBlockAt(x,80,z).setType(Material.AIR,false);
        if(type.equals("adjust"))w.getBlockAt(x,81,z).setType(Material.STONE,false);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a) {
        if(!(sender instanceof ConsoleCommandSender))return true;
        String text=String.join(" ",a);
        try {
            Map<String,Object> out=new LinkedHashMap<>();
            switch(a[0]) {
                case "online" -> out.put("players",getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "config" -> {for(String key:allium.getConfig().getKeys(true))if(key.startsWith("teleport.")||key.equals("preferences.preload-wait-millis"))out.put(key,allium.getConfig().get(key));out.put("sameService",service()==initialService);}
                case "arm" -> {FaultInjection.arm(a[1],Integer.parseInt(a[2]));out.put("armed",FaultInjection.armed(a[1]));}
                case "fault" -> {out.put("tripped",FaultInjection.tripped(a[1]));out.put("armed",FaultInjection.armed(a[1]));}
                case "reset" -> FaultInjection.reset();
                case "message" -> {
                    Messages message=Messages.valueOf(a[1]);
                    out.put("key",message.getKey());
                    out.put("plain",TextFormatter.parse(allium.getMessageManager().resolve(message,"{player}",a[2])).toPlainText());
                }
                case "world" -> {
                    World w=getServer().getWorld(a[1]);
                    if(w==null)w=getServer().createWorld(new WorldCreator(a[1]).type(WorldType.FLAT).generateStructures(false)
                            .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:stone\",\"height\":2}],\"biome\":\"minecraft:plains\"}"));
                    out.put("worldUUID",w.getUID().toString());
                }
                case "rows" -> {
                    List<Object> tables=new ArrayList<>();
                    try(Connection c=allium.getDatabaseManager().getConnection();ResultSet t=c.getMetaData().getTables(null,null,"allium_%",new String[]{"TABLE"})) {
                        while(t.next()) {String name=t.getString("TABLE_NAME");
                            if(!name.matches("allium_.*(teleport|spawn|back|cooldown|preferences).*"))continue;
                            List<Object> rows=new ArrayList<>();
                            try(Statement q=c.createStatement();ResultSet rs=q.executeQuery("SELECT * FROM "+name)) {
                                while(rs.next()) {Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnName(i),rs.getObject(i));rows.add(row);}
                            }
                            tables.add(Map.of("table",name,"rows",rows));
                        }
                    }
                    out.put("tables",tables);
                }
                default -> {
                    Player p=getServer().getPlayerExact(a[1]);if(p==null)throw new IllegalArgumentException("online player required");
                    switch(a[0]) {
                        case "state" -> out.putAll(state(p));
                        case "clear" -> {events.remove(p.getUniqueId());out.put("eventSequence",eventSequence);}
                        case "perm" -> {var att=permissions.computeIfAbsent(p.getUniqueId(),id->p.addAttachment(this));att.setPermission(a[2],Boolean.parseBoolean(a[3]));p.updateCommands();out.put("permission",p.hasPermission(a[2]));}
                        case "op" -> {p.setOp(Boolean.parseBoolean(a[2]));out.put("op",p.isOp());}
                        case "unsetperm" -> {var att=permissions.get(p.getUniqueId());if(att!=null)att.unsetPermission(a[2]);out.put("permission",p.hasPermission(a[2]));out.put("explicit",p.isPermissionSet(a[2]));}
                        case "vanish" -> {if(a[2].equals("on"))p.setMetadata("vanished",new FixedMetadataValue(this,true));else p.removeMetadata("vanished",this);out.put("vanished",p.hasMetadata("vanished"));}
                        case "place" -> {
                            World w=getServer().getWorld(a[2]);if(w==null)throw new IllegalArgumentException("world not prepared");
                            int x=Integer.parseInt(a[3]),z=Integer.parseInt(a[4]);String type=a[5];
                            preparing=true;
                            try {
                                p.setGameMode(GameMode.SURVIVAL);p.setAllowFlight(true);p.setFlying(true);p.setInvulnerable(true);p.setHealth(20);p.setFireTicks(0);
                                pad(w,x,z,type);
                                double y=type.equals("void")?w.getMinHeight()-8:80;
                                if(!p.teleport(new Location(w,x+.5,y,z+.5)))throw new IllegalStateException("fixture placement denied");
                                out.putAll(location(p.getLocation()));
                            }finally {preparing=false;}
                        }
                        case "damage" -> {p.setInvulnerable(false);p.damage(Double.parseDouble(a[2]));out.put("health",p.getHealth());p.setInvulnerable(true);}
                        case "movebytes" -> out.putAll(NativePackets.move(Double.parseDouble(a[2]),Double.parseDouble(a[3]),Double.parseDouble(a[4])));
                        case "respawnbytes" -> out.putAll(NativePackets.respawn());
                        default -> throw new IllegalArgumentException("unknown fixture action");
                    }
                }
            }
            getLogger().info("S13G-DONE "+text+" => "+json(out));
        }catch(Throwable e){getLogger().info("S13G-FAIL "+text+" => "+e);e.printStackTrace();}
        return true;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e) {
        if(e.getTo()!=null&&e.getFrom().getWorld().equals(e.getTo().getWorld())&&e.getFrom().distanceSquared(e.getTo())>0)record(e.getPlayer(),"move",Map.of("from",location(e.getFrom()),"to",location(e.getTo())));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void teleport(PlayerTeleportEvent e){record(e.getPlayer(),"teleport",Map.of("cause",e.getCause().name(),"from",location(e.getFrom()),"to",location(e.getTo()),"cancelled",e.isCancelled()));}
    @EventHandler(priority=EventPriority.HIGHEST) public void holdEnvironmentalDamage(EntityDamageEvent e){if(e.getEntity() instanceof Player p && p.isInvulnerable())e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p)record(p,"damage",Map.of("cause",e.getCause().name(),"amount",e.getFinalDamage(),"cancelled",e.isCancelled()));}
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e){record(e.getEntity(),"death",location(e.getEntity().getLocation()));}
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e){record(e.getPlayer(),"respawn",location(e.getRespawnLocation()));}
    @EventHandler(priority=EventPriority.MONITOR) public void command(PlayerCommandPreprocessEvent e){record(e.getPlayer(),"command",Map.of("command",e.getMessage(),"cancelled",e.isCancelled()));}
    @EventHandler public void quit(PlayerQuitEvent e){permissions.remove(e.getPlayer().getUniqueId());}
    static String json(Object v) {
        if(v==null)return "null";
        if(v instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(v instanceof Collection<?> c)return "["+String.join(",",c.stream().map(TeleportGate::json).toList())+"]";
        if(v instanceof Number||v instanceof Boolean)return v.toString();
        return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}
