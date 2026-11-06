package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

/** Disposable-server item contract gate; it implements no gameplay behavior. */
public final class ProviderGate extends JavaPlugin implements Listener {
    private final Map<String,String> signatures = new LinkedHashMap<>();
    private final Map<String,String> uuids = new LinkedHashMap<>();
    private ItemService items;
    private boolean failed;
    private final List<String> history = new ArrayList<>();
    @Override public void onEnable() {
        items=PluginStart.getInstance().getItemService();
        items.registerPrivilegedBehavior("allium:gate_tool");
        for(String key:List.of("nexo","craftengine")) {
            String signature=getConfig().getString("expected."+key+".signature");
            String uuid=getConfig().getString("expected."+key+".uuid");
            if(signature!=null&&uuid!=null){signatures.put(key,signature);uuids.put(key,uuid);}
        }
        for(String name:List.of("Nexo","CraftEngine")) {
            Plugin provider=getServer().getPluginManager().getPlugin(name);
            if(provider==null)continue;
            String event=name.equals("Nexo")?"com.nexomc.nexo.api.events.NexoPreItemsLoadEvent":"net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent";
            try {
                Class<? extends Event> type=Class.forName(event,true,provider.getClass().getClassLoader()).asSubclass(Event.class);
                getServer().getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(listener,e)-> {
                    try {
                        if(getConfig().getBoolean("test.force-event-failure",false))throw new IllegalStateException("injected provider event assertion failure");
                        String key=name.equals("Nexo")?"nexo":"craftengine";
                        ResolutionStatus status=items.create(new ItemRef(ProviderKey.of(key,key.equals("nexo")?"gate_wand":"sf_ores:silver_ingot"),null,1,Map.of())).status();
                        check("reload-event-loading-"+key,status==ResolutionStatus.PROVIDER_LOADING);
                        history.add(name+" "+event+" "+status);
                    }catch(Exception|LinkageError failure){fail(failure);}
                },this);
            }catch(Exception ex){throw new IllegalStateException(ex);}
        }
        getLogger().info("S05-INITIAL "+states());
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                if(failed){cancel();return;}
                try {
                    Map<String,ProviderStatus> current=states();
                    if(current.containsValue(ProviderStatus.FAILED))throw new IllegalStateException("provider FAILED "+current);
                    if(++ticks>120)throw new IllegalStateException("readiness timeout "+current);
                    if(!current.containsValue(ProviderStatus.LOADING)){getLogger().info("S05-READY "+current);cancel();}
                }catch(Exception|LinkageError failure){fail(failure);cancel();}
            }
        }.runTaskTimer(this,1,20);
    }
    private Map<String,ProviderStatus> states(){Map<String,ProviderStatus> map=new LinkedHashMap<>();for(String key:List.of("nexo","craftengine","oraxen"))map.put(key,items.getProvider(key).orElseThrow().status());return map;}
    private void check(String label,boolean value){if(!value)throw new IllegalStateException(label);getLogger().info("S05-PASS "+label);}
    private void fail(Throwable failure){failed=true;getLogger().severe("S05-FAIL "+failure);}
    private Player player(){Player p=Bukkit.getPlayerExact("GateBot");if(p==null)throw new IllegalStateException("Connect actual GateBot protocol client first");return p;}
    private String tag(ItemStack s,org.bukkit.NamespacedKey key){return s.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);}
    private String id(String key){return key.equals("nexo")?"gate_wand":"sf_ores:silver_ingot";}
    private int slot(String key){return key.equals("nexo")?0:1;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(failed){fail(new IllegalStateException("preceding assertion failed; refusing subsequent PASS"));return true;}
        try {
            String action=args.length==0?"status":args[0];
            if(action.equals("status")){getLogger().info("S05-STATUS "+states()+" history="+history);return true;}
            if(action.equals("disable")){String name=args[1];getServer().getPluginManager().disablePlugin(getServer().getPluginManager().getPlugin(name));getLogger().info("S05-DISABLED "+states());return true;}
            if(action.equals("smoke")) {
                items.registerPrivilegedBehavior("allium:gate_tool");
                for(String key:List.of("nexo","craftengine")) if(items.getProvider(key).orElseThrow().status()==ProviderStatus.READY) {
                    ProviderKey ref=ProviderKey.of(key,id(key));
                    ItemStack stack=items.create(new ItemRef(ref,"allium:gate_tool",1,Map.of("charges","2"))).itemStack().orElseThrow();
                    check(key+"-standalone-native-id",items.identify(stack).orElseThrow().appearance().equals(ref));
                    check(key+"-standalone-signature",items.identifyBehavior(stack).orElseThrow().equals("allium:gate_tool"));
                }
                getLogger().info("S05-DONE smoke");return true;
            }
            Player p=player();
            for(String key:List.of("nexo","craftengine")){
                if(items.getProvider(key).orElseThrow().status()!=ProviderStatus.READY)continue;
                if(action.equals("create")){
                    items.registerPrivilegedBehavior("allium:gate_tool");
                    ItemRef ref=new ItemRef(ProviderKey.of(key,id(key)),"allium:gate_tool",1,Map.of("charges","2"));
                    ItemStack stack=items.create(ref).itemStack().orElseThrow();
                    p.getInventory().setItem(slot(key),stack);
                    stack=p.getInventory().getItem(slot(key));
                    signatures.put(key,tag(stack,ItemService.KEY_SIGNATURE));uuids.put(key,tag(stack,ItemService.KEY_INSTANCE_UUID));
                    check(key+"-create-native-id",items.identify(stack).orElseThrow().appearance().equals(ref.appearance()));
                    check(key+"-create-signed",items.identifyBehavior(stack).orElseThrow().equals("allium:gate_tool"));
                    check(key+"-strict-carrier",!items.matches(stack,ItemRef.parse(stack.getType().name())));
                    getLogger().info("S05-CREATED "+key+" material="+stack.getType()+" uuid="+uuids.get(key));
                }else if(action.equals("checkpoint")){
                    ItemStack stack=p.getInventory().getItem(slot(key));
                    check(key+"-checkpoint-valid-signature",items.identifyBehavior(stack).orElseThrow().equals("allium:gate_tool"));
                    check(key+"-checkpoint-spent-state",items.getState(stack,"charges").orElseThrow().equals("2"));
                    signatures.put(key,tag(stack,ItemService.KEY_SIGNATURE));uuids.put(key,tag(stack,ItemService.KEY_INSTANCE_UUID));
                    getConfig().set("expected."+key+".signature",signatures.get(key));
                    getConfig().set("expected."+key+".uuid",uuids.get(key));
                    getConfig().set("expected."+key+".amount",stack.getAmount());
                    saveConfig();
                }else if(action.equals("verify")||action.equals("nativeupdate")){
                    check(key+"-baseline-exists",signatures.containsKey(key)&&uuids.containsKey(key));
                    ItemStack stack=p.getInventory().getItem(slot(key));
                    if(action.equals("nativeupdate")){
                        ItemStack changed=updateNative(key,stack);p.getInventory().setItem(slot(key),changed);
                        stack=p.getInventory().getItem(slot(key));
                    }
                    check(key+"-held-signature-unchanged",Objects.equals(signatures.get(key),tag(stack,ItemService.KEY_SIGNATURE)));
                    check(key+"-held-uuid-unchanged",Objects.equals(uuids.get(key),tag(stack,ItemService.KEY_INSTANCE_UUID)));
                    check(key+"-held-state-unchanged",items.getState(stack,"charges").orElseThrow().equals("2"));
                    check(key+"-held-still-verifies",items.identifyBehavior(stack).orElseThrow().equals("allium:gate_tool"));
                    if(getConfig().contains("expected."+key+".amount"))check(key+"-held-amount-unchanged",stack.getAmount()==getConfig().getInt("expected."+key+".amount"));
                    getLogger().info("S05-HELD "+key+" material="+stack.getType());
                    ItemStack fresh=items.create(new ItemRef(ProviderKey.of(key,id(key)),null,1,Map.of())).itemStack().orElseThrow();
                    getLogger().info("S05-FRESH "+key+" material="+fresh.getType());
                }
            }
            getLogger().info("S05-DONE "+action);
        }catch(Exception|LinkageError ex){fail(ex);ex.printStackTrace();}
        return true;
    }
    private ItemStack updateNative(String key,ItemStack old)throws Exception{
        Plugin plugin=getServer().getPluginManager().getPlugin(key.equals("nexo")?"Nexo":"CraftEngine");ClassLoader loader=plugin.getClass().getClassLoader();
        if(key.equals("nexo")){
            Class<?> type=Class.forName("com.nexomc.nexo.items.ItemUpdater",true,loader);Object companion=type.getField("Companion").get(null);
            return(ItemStack)companion.getClass().getMethod("updateItem",ItemStack.class).invoke(companion,old);
        }
        Class<?> engineType=Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine",true,loader);Object engine=engineType.getMethod("instance").invoke(null);
        Object manager=engineType.getMethod("itemManager").invoke(engine);Object wrapped=manager.getClass().getMethod("wrap",Object.class).invoke(manager,old);
        Class<?> itemType=Class.forName("net.momirealms.craftengine.core.item.Item",true,loader);
        Method empty=Class.forName("net.momirealms.craftengine.core.item.ItemBuildContext",true,loader).getMethod("empty");
        Object result=manager.getClass().getMethod("updateItem",itemType,Supplier.class).invoke(manager,wrapped,(Supplier<Object>)()->{try{return empty.invoke(null);}catch(Exception e){throw new IllegalStateException(e);}});
        boolean updated=(boolean)result.getClass().getMethod("updated").invoke(result);check("craftengine-native-updater-ran",updated);
        Object finalItem=result.getClass().getMethod("finalItem").invoke(result);Object nativeStack=itemType.getMethod("minecraftItem").invoke(finalItem);
        Class<?> util=Class.forName("net.momirealms.craftengine.bukkit.util.ItemStackUtils",true,loader);
        for(Method method:util.getMethods())if(method.getName().equals("getBukkitStack")&&method.getParameterCount()==1&&method.getParameterTypes()[0].isInstance(nativeStack))return(ItemStack)method.invoke(null,nativeStack);
        throw new IllegalStateException("No verified native stack conversion");
    }
}
