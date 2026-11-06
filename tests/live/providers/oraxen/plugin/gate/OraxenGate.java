package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Disposable S05 native adapter validation; no gameplay actions. */
public final class OraxenGate extends JavaPlugin implements Listener {
    private static final String ID="Gate_Oraxen", BEHAVIOR="allium:oraxen_gate_tool";
    private ItemService items;
    private int events;
    private ClassLoader nativeLoader;
    public void onEnable() {
        items=PluginStart.getInstance().getItemService();
        items.registerPrivilegedBehavior(BEHAVIOR);
        Plugin p=Bukkit.getPluginManager().getPlugin("Oraxen");
        check("real-plugin-enabled",p!=null&&p.isEnabled());
        nativeLoader=p.getClass().getClassLoader();
        try {
            Class<? extends Event> type=Class.forName("io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent",true,nativeLoader).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(listener,event)->{
                events++; getLogger().info("S05-ORAXEN-POSTLOAD events="+events+" status="+status());
            },this);
        }catch(Exception e){throw new IllegalStateException(e);}
        getLogger().info("S05-ORAXEN-INITIAL "+status());
        Bukkit.getScheduler().runTask(this,()->getLogger().info("S05-ORAXEN-DEFERRED "+status()));
    }
    private ProviderStatus status(){return items.getProvider("oraxen").orElseThrow().status();}
    private void check(String name,boolean ok){if(!ok)throw new IllegalStateException(name);getLogger().info("S05-ORAXEN-PASS "+name);}
    private ItemStack fresh(){return items.create(new ItemRef(ProviderKey.of("oraxen",ID),BEHAVIOR,1,Map.of("charges","3"))).itemStack().orElseThrow();}
    private Object nativeCall(String type,String method,Class<?>[] args,Object... values)throws Exception{
        return Class.forName(type,true,nativeLoader).getMethod(method,args).invoke(null,values);
    }
    private String tag(ItemStack stack,NamespacedKey key){return stack.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);}
    private Player player(){return Objects.requireNonNull(Bukkit.getPlayerExact("GateBot"),"actual GateBot required");}
    private void identity(ItemStack stack)throws Exception{
        Object nativeId=nativeCall("io.th0rgal.oraxen.api.OraxenItems","getIdByItem",new Class<?>[]{ItemStack.class},stack);
        if (!ID.equals(nativeId)) {
            Object nexoId=null;
            Plugin nexo=Bukkit.getPluginManager().getPlugin("Nexo");
            if(nexo!=null&&nexo.isEnabled())nexoId=Class.forName("com.nexomc.nexo.api.NexoItems",true,nexo.getClass().getClassLoader()).getMethod("idFromItem",ItemStack.class).invoke(null,stack);
            getLogger().warning("S05-ORAXEN-IDENTITY native="+nativeId+" nexo="+nexoId+" keys="+stack.getItemMeta().getPersistentDataContainer().getKeys());
        }
        check("native-id-case",ID.equals(nativeId));
        check("allium-native-id",items.identify(stack).orElseThrow().appearance().equals(ProviderKey.of("oraxen",ID)));
        check("signature-valid",items.identifyBehavior(stack).orElseThrow().equals(BEHAVIOR));
        check("strict-carrier",!items.matches(stack,ItemRef.parse(stack.getType().name())));
    }
    private void verify(Player player)throws Exception{
        ItemStack stack=Objects.requireNonNull(player.getInventory().getItem(2));
        check("checkpoint-exists",getConfig().contains("signature"));
        check("held-signature",Objects.equals(getConfig().getString("signature"),tag(stack,ItemService.KEY_SIGNATURE)));
        check("held-uuid",Objects.equals(getConfig().getString("uuid"),tag(stack,ItemService.KEY_INSTANCE_UUID)));
        check("held-spent-charges",items.getState(stack,"charges").orElseThrow().equals("2"));
        check("held-amount",stack.getAmount()==getConfig().getInt("amount"));
        identity(stack);
        getLogger().info("S05-ORAXEN-HELD material="+stack.getType()+" amount="+stack.getAmount());
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if (!(sender instanceof ConsoleCommandSender)) return false;
        String action=args.length==0?"status":args[0];
        try {
            switch(action){
                case "status" -> getLogger().info("S05-ORAXEN-STATUS "+status());
                case "smoke" -> {
                    check("ready",status()==ProviderStatus.READY);
                    ItemStack stack=fresh(); identity(stack);
                    check("fresh-paper",stack.getType()==Material.PAPER);
                    check("unknown-id",items.create(new ItemRef(ProviderKey.of("oraxen","__missing_gate__"),null,1,Map.of())).status()==ResolutionStatus.UNKNOWN_ID);
                    check("case-sensitive",items.create(new ItemRef(ProviderKey.of("oraxen",ID.toLowerCase(Locale.ROOT)),null,1,Map.of())).status()==ResolutionStatus.UNKNOWN_ID);
                    ItemStack second=fresh();
                    check("fresh-signed-instances",!Objects.equals(tag(stack,ItemService.KEY_INSTANCE_UUID),tag(second,ItemService.KEY_INSTANCE_UUID)));
                }
                case "create" -> {
                    Player player=player(); ItemStack stack=fresh(); items.setState(stack,"charges","2");
                    player.getInventory().setItem(2,stack); stack=player.getInventory().getItem(2);
                    identity(stack);
                    getConfig().set("signature",tag(stack,ItemService.KEY_SIGNATURE));
                    getConfig().set("uuid",tag(stack,ItemService.KEY_INSTANCE_UUID));
                    getConfig().set("amount",stack.getAmount()); saveConfig();
                    verify(player);
                }
                case "reload" -> {
                    Path fixture=Bukkit.getPluginManager().getPlugin("Oraxen").getDataFolder().toPath().resolve("items/allium_gate.yml");
                    Files.writeString(fixture,ID+":\n  material: BOOK\n  itemname: Allium Oraxen Gate\n");
                    int before=events;
                    nativeCall("io.th0rgal.oraxen.commands.ReloadCommand","reloadItems",new Class<?>[]{CommandSender.class},sender);
                    check("real-postload-event",events>before);
                    check("ready-after-reload",status()==ProviderStatus.READY);
                    ItemStack stack=fresh(); identity(stack); check("fresh-book-after-reload",stack.getType()==Material.BOOK);
                }
                case "verify" -> verify(player());
                case "nativeupdate" -> {
                    Player player=player();
                    ItemStack updated=(ItemStack)nativeCall("io.th0rgal.oraxen.items.ItemUpdater","updateItem",new Class<?>[]{ItemStack.class},player.getInventory().getItem(2));
                    player.getInventory().setItem(2,updated); verify(player);
                    check("native-updater-book",player.getInventory().getItem(2).getType()==Material.BOOK);
                }
                case "disable" -> {
                    Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("Oraxen"));
                    check("disabled-absent",status()==ProviderStatus.ABSENT);
                    check("disabled-create-absent",items.create(new ItemRef(ProviderKey.of("oraxen",ID),null,1,Map.of())).status()==ResolutionStatus.PROVIDER_ABSENT);
                }
                default -> throw new IllegalArgumentException(action);
            }
            getLogger().info("S05-ORAXEN-DONE "+action);
        }catch(Throwable e){getLogger().severe("S05-ORAXEN-FAIL "+action+" "+e);e.printStackTrace();}
        return true;
    }
}
