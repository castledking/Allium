package trigger;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Test-only observer/seeder. No callEvent, native updateItem, appearance refresh or post-baseline signing. */
public final class CETriggerGate extends JavaPlugin implements Listener {
    private ItemService service;
    private String name, mode;
    private Map<String,Object> baseline;
    private org.bukkit.entity.Item entity;
    private int clicks, drops, pickups;
    private final Map<String,Object> events = new LinkedHashMap<>();
    private final com.google.gson.Gson json = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || !Set.of(25610,25611,25612).contains(getServer().getPort()))
            throw new IllegalStateException("TEST ONLY: require owned loopback25610-25612");
        service=PluginStart.getInstance().getItemService();
        service.registerPrivilegedBehavior("allium:trigger_gate");
        getServer().getPluginManager().registerEvents(this,this);
        try { write("wire-ids", Map.of("wirePackets",wirePacketIds())); } catch(Exception e) { getLogger().warning("CE-TRIGGER wire catalog unavailable (use external protocol client): "+e); }
        getLogger().info("CE-TRIGGER runtime="+Bukkit.getVersion()+" java="+System.getProperty("java.version")+" CE="+Bukkit.getPluginManager().getPlugin("CraftEngine").getDescription().getVersion());
    }
    private Player player() { return Objects.requireNonNull(Bukkit.getPlayerExact("TriggerBot"),"connect TriggerBot"); }
    private void write(String file,Object data) throws Exception {
        getDataFolder().mkdirs();
        Path p=getDataFolder().toPath().resolve(file+".json");
        Path tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,json.toJson(data));
        Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);
    }
    private Object wrapped(ItemStack stack) throws Exception {
        Class<?> ce=Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine");
        Object manager=ce.getMethod("itemManager").invoke(ce.getMethod("instance").invoke(null));
        return manager.getClass().getMethod("wrap",Object.class).invoke(manager,stack);
    }
    private Class<?> itemType() throws Exception { return Class.forName("net.momirealms.craftengine.core.item.Item"); }
    private Object nativeTag(ItemStack stack,String key) throws Exception {
        return itemType().getMethod("getSparrowTag",Object[].class).invoke(wrapped(stack),new Object[]{new Object[]{key}});
    }
    private ItemStack addRawSentinel(ItemStack stack) throws Exception {
        Object item=wrapped(stack);
        itemType().getMethod("setTag",Object.class,Object[].class).invoke(item,"raw-custom-data-retained",new Object[]{"trigger:sentinel"});
        Object nativeStack=itemType().getMethod("minecraftItem").invoke(item);
        for(Method m:Class.forName("net.momirealms.craftengine.bukkit.util.ItemStackUtils").getMethods())
            if(m.getName().equals("getBukkitStack")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(nativeStack))return (ItemStack)m.invoke(null,nativeStack);
        throw new IllegalStateException("native conversion");
    }
    private Map<String,Object> snapshot(ItemStack s) {
        Map<String,Object> out=new LinkedHashMap<>();
        if(s==null||s.getType().isAir()){out.put("empty",true);return out;}
        out.put("material",s.getType().toString());out.put("amount",s.getAmount());
        PersistentDataContainer p=s.getItemMeta().getPersistentDataContainer();
        Map<String,Object> pdc=new TreeMap<>();
        for(NamespacedKey k:p.getKeys()) {
            Object v;
            if(p.has(k,PersistentDataType.STRING))v=Map.of("type","STRING","value",p.get(k,PersistentDataType.STRING));
            else if(p.has(k,PersistentDataType.INTEGER))v=Map.of("type","INTEGER","value",p.get(k,PersistentDataType.INTEGER));
            else if(p.has(k,PersistentDataType.BYTE_ARRAY))v=Map.of("type","BYTE_ARRAY","value",Base64.getEncoder().encodeToString(p.get(k,PersistentDataType.BYTE_ARRAY)));
            else if(p.has(k,PersistentDataType.LONG))v=Map.of("type","LONG","value",p.get(k,PersistentDataType.LONG));
            else v="UNSUPPORTED_PDC_TYPE";
            pdc.put(k.toString(),v);
        }
        out.put("pdc",pdc);
        out.put("uuid",p.get(ItemService.KEY_INSTANCE_UUID,PersistentDataType.STRING));
        out.put("signature",p.get(ItemService.KEY_SIGNATURE,PersistentDataType.STRING));
        out.put("charges",service.getState(s,"charges").orElse("MISSING"));
        out.put("behavior",service.identifyBehavior(s).orElse("UNVERIFIED"));
        out.put("identity",service.identify(s).map(r->r.appearance().toString()).orElse("UNKNOWN"));
        try {out.put("nativeVersion",String.valueOf(nativeTag(s,"craftengine:version")));out.put("rawSentinel",String.valueOf(nativeTag(s,"trigger:sentinel")));}catch(Exception ex){throw new RuntimeException(ex);}
        return out;
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] a) {
        try {
            Player p=player();
            if(a[0].equals("seed")) {
                name=a[1];mode=a[2];int count=Integer.parseInt(a[3]);
                p.setGameMode(GameMode.SURVIVAL);p.setInvulnerable(true);p.setGravity(false);
                // Fresh test-only arena in a disposable world; no production/world/player data is copied.
                Location base=new Location(p.getWorld(),0.5,100,0.5);
                p.getWorld().getBlockAt(0,99,0).setType(Material.STONE);
                p.teleport(base);p.getInventory().clear();p.setItemOnCursor(null);p.getInventory().setHeldItemSlot(0);
                for(org.bukkit.entity.Item old:p.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class))old.remove();
                clicks=drops=pickups=0;events.clear();entity=null;
                ItemStack stack=service.create(new ItemRef(ProviderKey.of("craftengine","trigger:"+name),"allium:trigger_gate",count,Map.of("charges","9","note","existing-state"))).itemStack().orElseThrow();
                service.setState(stack,"charges","2"); // The only spend/re-sign happens BEFORE the comparison baseline.
                var meta=stack.getItemMeta();var data=meta.getPersistentDataContainer();
                data.set(new NamespacedKey(this,"integer"),PersistentDataType.INTEGER,37);
                data.set(new NamespacedKey(this,"bytes"),PersistentDataType.BYTE_ARRAY,new byte[]{0,1,-1,42});
                data.set(new NamespacedKey(this,"long"),PersistentDataType.LONG,9876543210L);
                stack.setItemMeta(meta);stack=addRawSentinel(stack);
                if(mode.equals("pickup")) {
                    entity=p.getWorld().dropItem(base.clone().add(3,0,0),stack);entity.setGravity(false);entity.setVelocity(new org.bukkit.util.Vector());entity.setPickupDelay(0);
                    stack=entity.getItemStack();
                } else {p.getInventory().setItem(0,stack);stack=p.getInventory().getItem(0);}
                baseline=snapshot(stack);write(name+"-baseline",baseline);
                getLogger().info("CE-TRIGGER BASELINE case="+name+" "+json.toJson(baseline).replace('\n',' '));
            } else if(a[0].equals("before")) {
                Map<String,Object> now=snapshot(mode.equals("pickup")?entity.getItemStack():p.getInventory().getItem(0));
                Map<String,Object> result=new LinkedHashMap<>();result.put("snapshot",now);result.put("unchangedSinceSeed",baseline.equals(now));
                result.put("events",Map.of("click",clicks,"drop",drops,"pickup",pickups));write(name+"-before",result);
            } else if(a[0].equals("observe")) {
                List<Object> inventory=new ArrayList<>();
                for(int i=0;i<p.getInventory().getSize();i++) {ItemStack s=p.getInventory().getItem(i);if(s!=null&&!s.getType().isAir())inventory.add(Map.of("slot",i,"stack",snapshot(s)));}
                List<Object> entities=new ArrayList<>();
                for(org.bukkit.entity.Item item:p.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class))entities.add(Map.of("entityId",item.getEntityId(),"uuid",item.getUniqueId().toString(),"valid",item.isValid(),"stack",snapshot(item.getItemStack())));
                Map<String,Object> result=new LinkedHashMap<>();result.put("case",name);result.put("baseline",baseline);
                result.put("inventory",inventory);result.put("cursor",snapshot(p.getItemOnCursor()));result.put("entities",entities);
                result.put("events",events);result.put("counts",Map.of("click",clicks,"drop",drops,"pickup",pickups));
                write(name+"-result",result);getLogger().info("CE-TRIGGER OBSERVED "+name);
            } else throw new IllegalArgumentException("seed|before|observe");
        } catch(Throwable t){getLogger().severe("CE-TRIGGER ERROR "+t);t.printStackTrace();return true;}
        return true;
    }
    private void logEvent(String phase,Event e,ItemStack stack) {
        Map<String,Object> record=new LinkedHashMap<>();record.put("class",e.getClass().getName());record.put("stack",snapshot(stack));
        record.put("cancelled",((Cancellable)e).isCancelled());
        boolean nativeCall=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f->f.getClassName().startsWith("net.minecraft."));
        record.put("nativeServerCallStack",nativeCall);events.put(phase,record);
        getLogger().info("CE-TRIGGER EVENT case="+name+" phase="+phase+" "+json.toJson(record).replace('\n',' '));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void clickBefore(InventoryClickEvent e) {if(e.getWhoClicked().getName().equals("TriggerBot")){clicks++;logEvent("click-lowest",e,e.getCurrentItem());}}
    @EventHandler(priority=EventPriority.MONITOR) public void clickAfter(InventoryClickEvent e) {if(e.getWhoClicked().getName().equals("TriggerBot")){logEvent("click-monitor",e,e.getCurrentItem());events.put("click-details",Map.of("click",e.getClick().toString(),"action",e.getAction().toString(),"rawSlot",e.getRawSlot(),"slot",e.getSlot(),"gamemode",e.getWhoClicked().getGameMode().toString()));}}
    @EventHandler(priority=EventPriority.LOWEST) public void dropBefore(PlayerDropItemEvent e) {if(e.getPlayer().getName().equals("TriggerBot")){drops++;entity=e.getItemDrop();entity.setPickupDelay(32767);entity.setGravity(false);entity.setVelocity(new org.bukkit.util.Vector());logEvent("drop-lowest",e,entity.getItemStack());}}
    @EventHandler(priority=EventPriority.MONITOR) public void dropAfter(PlayerDropItemEvent e) {if(e.getPlayer().getName().equals("TriggerBot"))logEvent("drop-monitor",e,e.getItemDrop().getItemStack());}
    @EventHandler(priority=EventPriority.LOWEST) public void pickupBefore(EntityPickupItemEvent e) {if(e.getEntity().getName().equals("TriggerBot")){pickups++;logEvent("pickup-lowest",e,e.getItem().getItemStack());}}
    @EventHandler(priority=EventPriority.MONITOR) public void pickupAfter(EntityPickupItemEvent e) {if(e.getEntity().getName().equals("TriggerBot"))logEvent("pickup-monitor",e,e.getItem().getItemStack());}
    private Map<String, Object> wirePacketIds() throws Exception {
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
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            String id = packetType.getMethod("id").invoke(args[0]).toString();
                            ids.put(id, (Integer) args[1]);
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
