package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.delivery.ItemGiveEvent;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Only prepares fixtures and observes state; all tested mail operations arrive over real clients. */
public final class MailGate extends JavaPlugin implements Listener {
    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Set<String> vanishOnJoin = new HashSet<>();
    private final Map<String, Integer> costs = new LinkedHashMap<>();
    private boolean costAllowed = true;
    private Object initialMail;
    private final Set<UUID> cancelled = new HashSet<>();
    private final Map<UUID,Integer> giveEvents = new HashMap<>();
    @Override public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || getServer().getPort()<25720 || getServer().getPort()>25725)
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25720-25725 required");
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try { Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"), json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8); }
            catch (ReflectiveOperationException e) { getLogger().info("S12G legacy protocol client required"); }
            initialMail = mail();
            Class<?> hook = Class.forName("codes.castled.allium.mail.MailService$CostPolicy");
            Object proxy = Proxy.newProxyInstance(hook.getClassLoader(), new Class[]{hook}, (p,m,a) -> {
                String key = m.getName() + ":" + a[1];
                costs.merge(key, 1, Integer::sum);
                return costAllowed;
            });
            mail().getClass().getMethod("setCostPolicy", hook).invoke(mail(), proxy);
        } catch (Exception e) { throw new IllegalStateException(e); }
        messaging().messaging().setMuteSource(muted::contains);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S12G-GATE READY " + getServer().getBukkitVersion());
    }
    private Object mail() throws Exception {
        Object module = allium.getModuleManager().getModule("mail");
        return module.getClass().getMethod("mail").invoke(module);
    }
    private MessagingModule messaging() { return (MessagingModule) allium.getModuleManager().getModule("messaging"); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        String text = String.join(" ",args);
        try {
            Map<String,Object> out = new LinkedHashMap<>();
            if (args[0].equals("online")) {
                out.put("players",getServer().getOnlinePlayers().stream().map(Player::getName).toList());
            } else if (args[0].equals("arm")) {
                FaultInjection.arm(args[1], Integer.parseInt(args[2])); out.put("armed", FaultInjection.armed(args[1]));
            } else if (args[0].equals("fault")) {
                out.put("tripped",FaultInjection.tripped(args[1])); out.put("armed",FaultInjection.armed(args[1]));
            } else if (args[0].equals("reset")) {
                FaultInjection.reset();
            } else if (args[0].equals("cost")) {
                if(args.length>1) costAllowed = args[1].equals("allow");
                out.putAll(costs); out.put("allowed",costAllowed); out.put("sameService", initialMail == mail());
            } else if (args[0].equals("vanishjoin")) {
                if (args[2].equals("on")) vanishOnJoin.add(args[1]); else vanishOnJoin.remove(args[1]);
            } else if (args[0].equals("stored")) {
                UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+args[1]).getBytes(StandardCharsets.UTF_8));
                UUID other=UUID.nameUUIDFromBytes("OfflinePlayer:MailA".getBytes(StandardCharsets.UTF_8));
                try(Connection c=allium.getDatabaseManager().getConnection()) {
                    try(PreparedStatement q=c.prepareStatement("SELECT accept_messages FROM allium_preferences WHERE uuid=?")) {
                        q.setString(1,id.toString());try(ResultSet r=q.executeQuery()) {out.put("acceptMessages",r.next()?r.getObject(1):null);}
                    }
                    try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM allium_ignores WHERE owner=? AND ignored=?")) {
                        q.setString(1,id.toString());q.setString(2,other.toString());try(ResultSet r=q.executeQuery()) {r.next();out.put("ignoresMailA",r.getInt(1)>0);}
                    }
                }
            } else if (args[0].equals("box")) {
                // Raw rows include PENDING: read-only DB observation; no storage mutations here.
                UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:"+args[1]).getBytes(StandardCharsets.UTF_8));
                List<Object> rows = new ArrayList<>();
                try (Connection c = allium.getDatabaseManager().getConnection();
                     PreparedStatement s=c.prepareStatement("SELECT id,kind,body,state,unread,items,claim_owner,claim_token,origin FROM allium_mail WHERE recipient=? ORDER BY created_at,id")) {
                    s.setString(1,id.toString());
                    try(ResultSet r=s.executeQuery()) { while(r.next()) {
                        Map<String,Object> row=new LinkedHashMap<>();
                        for(String k:List.of("id","kind","body","state","claim_owner","claim_token","origin")) row.put(k,r.getString(k));
                        row.put("unread",r.getBoolean("unread"));
                        row.put("encodedLength",r.getString("items")==null?0:r.getString("items").length()); rows.add(row);
                    }}
                }
                out.put("rows",rows);
            } else {
                Player target=getServer().getPlayerExact(args[1]);
                if(target==null) throw new IllegalArgumentException("online player required");
                switch(args[0]) {
                    case "perm" -> {
                        PermissionAttachment p=permissions.computeIfAbsent(target.getUniqueId(),id->target.addAttachment(this));
                        p.setPermission(args[2],Boolean.parseBoolean(args[3])); target.updateCommands();
                        out.put("permission",target.hasPermission(args[2]));
                    }
                    case "vanish" -> {
                        if(args[2].equals("on")) target.setMetadata("vanished",new FixedMetadataValue(this,true));
                        else target.removeMetadata("vanished",this);
                    }
                    case "mute" -> {
                        if(args[2].equals("on")) muted.add(target.getUniqueId()); else muted.remove(target.getUniqueId());
                        out.put("muted",muted.contains(target.getUniqueId()));
                    }
                    case "cancel" -> {
                        if(args[2].equals("on")) cancelled.add(target.getUniqueId()); else cancelled.remove(target.getUniqueId());
                        out.put("cancelled",cancelled.contains(target.getUniqueId()));
                    }
                    case "empty" -> { target.getInventory().clear(); target.saveData(); }
                    case "give" -> {
                        target.getInventory().setHeldItemSlot(0);
                        target.getInventory().setItem(0,new ItemStack(Material.valueOf(args[2]),Integer.parseInt(args[3]))); target.saveData();
                    }
                    case "room" -> {
                        // Full storage with optional room in one DIAMOND stack.
                        target.getInventory().clear();
                        for(int i=0;i<36;i++) target.getInventory().setItem(i,new ItemStack(Material.STONE,64));
                        int room=Integer.parseInt(args[2]);
                        if(room>0) target.getInventory().setItem(35,new ItemStack(Material.DIAMOND,64-room));
                        target.saveData();
                    }
                    case "tool" -> {
                        var result=allium.getItemService().createForBehavior("allium:mob_disarmer",1);
                        if(!result.isResolved()) throw new IllegalStateException(result.diagnosticMessage());
                        ItemStack stack=result.itemStack().orElseThrow();
                        allium.getItemService().setState(stack,"gate","s12-state");
                        target.getInventory().setHeldItemSlot(0); target.getInventory().setItem(0,stack); target.saveData();
                        out.put("item", describe(stack));
                    }
                    case "state" -> {
                        PreferencesModule p=(PreferencesModule)allium.getModuleManager().getModule("preferences");
                        out.put("loaded",p.effective(target.getUniqueId()).isPresent());
                        out.put("acceptMessages",p.effective(target.getUniqueId()).map(v->v.acceptMessages()).orElse(null));
                        var list=messaging().ignores().list(target.getUniqueId());
                        out.put("ignoresMailA",list==null?null:list.contains(UUID.nameUUIDFromBytes("OfflinePlayer:MailA".getBytes(StandardCharsets.UTF_8))));
                        out.put("ignoreLoaded",messaging().ignores().list(target.getUniqueId())!=null);
                        out.put("sameService",initialMail==mail());
                        out.put("playerDataDirectory",mail().getClass().getMethod("playerDataDirectory").invoke(mail()).toString());
                        out.put("giveEvents",giveEvents.getOrDefault(target.getUniqueId(),0));
                        Object service=mail();
                        Field lf=service.getClass().getDeclaredField("locks");lf.setAccessible(true);
                        Object locks=lf.get(service);
                        boolean locked=(Boolean)locks.getClass().getMethod("locked",Player.class).invoke(locks,target);
                        Field rf=service.getClass().getDeclaredField("recovering");rf.setAccessible(true);
                        boolean recovering=((Set<?>)rf.get(service)).contains(target);
                        out.put("mailLocked",locked);out.put("mailRecovering",recovering);out.put("mailReady",!locked&&!recovering);
                        out.put("receipts",target.getPersistentDataContainer().get(new NamespacedKey(allium,"mail_receipts"),PersistentDataType.STRING));
                        List<Object> stacks=new ArrayList<>();
                        for(int i=0;i<36;i++) {ItemStack s=target.getInventory().getItem(i); if(s!=null&&!s.getType().isAir()) {Map<String,Object> row=describe(s);row.put("slot",i);stacks.add(row);}}
                        out.put("stacks",stacks);
                    }
                    default -> throw new IllegalArgumentException("unknown action");
                }
            }
            getLogger().info("S12G-DONE " + text + " => " + json(out));
        } catch(Throwable e) { getLogger().info("S12G-FAIL " + text + " => " + e); }
        return true;
    }
    private Map<String,Object> describe(ItemStack s) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("material",s.getType().name());out.put("amount",s.getAmount());
        ItemRef ref=allium.getItemService().identify(s).orElse(null);
        out.put("behavior",ref==null?null:ref.behaviorId().orElse(null));out.put("state",ref==null?null:ref.stateData());
        var p=s.getItemMeta().getPersistentDataContainer();
        for(String k:List.of("signature","instance_uuid"))out.put(k,p.get(new NamespacedKey("allium",k),PersistentDataType.STRING));
        return out;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void give(ItemGiveEvent e) {
        giveEvents.merge(e.getTarget().getUniqueId(),1,Integer::sum);
        if(cancelled.contains(e.getTarget().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e) {
        if(vanishOnJoin.contains(e.getPlayer().getName())) e.getPlayer().setMetadata("vanished",new FixedMetadataValue(this,true));
    }
    @EventHandler public void quit(PlayerQuitEvent e) {permissions.remove(e.getPlayer().getUniqueId()); muted.remove(e.getPlayer().getUniqueId());}
    private static String json(Object v) {
        if(v==null)return "null";
        if(v instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(v instanceof Collection<?> c)return "["+String.join(",",c.stream().map(MailGate::json).toList())+"]";
        if(v instanceof Number||v instanceof Boolean)return v.toString();
        return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
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
                                if (method.getName().equals("accept")) ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
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
