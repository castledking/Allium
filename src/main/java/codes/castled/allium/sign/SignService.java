package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Face PDC is the sole action authority, with no location cache to survive destruction. */
public final class SignService implements Listener,AutoCloseable {
    private final PluginStart plugin;
    private final Set<SignIdentity.Type> enabled=EnumSet.noneOf(SignIdentity.Type.class);
    private final Set<UUID> pending=new HashSet<>();
    private final SignClickBudget clicks=new SignClickBudget();
    private final int limit;
    private boolean closed;
    public SignService(PluginStart plugin){
        this.plugin=plugin;
        for(String raw:plugin.getConfig().getStringList("enabledSigns"))enabled.add(SignIdentity.Type.parse(raw));
        limit=plugin.getConfig().getInt("sign-use-per-second",4);if(limit<1||limit>100)throw new IllegalArgumentException("sign-use-per-second must be 1..100");
    }
    public void enable(){plugin.getServer().getPluginManager().registerEvents(this,plugin);}
    public void close(){closed=true;HandlerList.unregisterAll(this);}
    private NamespacedKey key(Side side){return new NamespacedKey(plugin,"sign_"+side.name().toLowerCase(Locale.ROOT));}
    private Optional<SignIdentity> identity(Sign sign,Side side){return SignIdentity.decode(sign.getPersistentDataContainer().get(key(side),PersistentDataType.STRING));}
    private boolean permission(Player p,String action,SignIdentity.Type type){return p.hasPermission("allium.signs."+action+"."+type.key());}
    private static SignIdentity.Type header(String line){
        if(line==null||!line.startsWith("[")||!line.endsWith("]"))return null;
        try{return SignIdentity.Type.parse(line.substring(1,line.length()-1));}catch(IllegalArgumentException error){return null;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void changing(SignChangeEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        Side side=event.getSide();var old=identity(sign,side);var type=header(event.getLine(0));
        if(old.isPresent()&&!permission(event.getPlayer(),"break",old.get().type())){event.setCancelled(true);return;}
        if(type==null){
            // Remove the old face authority only when the actual text edit survives protection.
            plugin.getScheduler().runOnServerLater(()->{if(!closed&&!event.isCancelled()&&event.getBlock().getState() instanceof Sign current){current.getPersistentDataContainer().remove(key(side));current.update(false,false);}},50);
            return;
        }
        boolean publicWarp=type==SignIdentity.Type.WARP&&"public".equalsIgnoreCase(event.getLine(2));
        if(!enabled.contains(type)||!permission(event.getPlayer(),"create",type)||publicWarp&&!event.getPlayer().hasPermission("allium.signs.create.warp.public")){event.setCancelled(true);return;}
        SignIdentity created;
        try{created=new SignIdentity(UUID.randomUUID(),type,Objects.toString(event.getLine(1),"").trim(),publicWarp);}
        catch(IllegalArgumentException invalid){event.setCancelled(true);return;}
        for(int i=0;i<4;i++)event.setLine(i,ChatColor.translateAlternateColorCodes('&',Objects.toString(event.getLine(i),"")));
        event.setLine(0,ChatColor.DARK_BLUE+"["+type.key()+"]");
        // Bukkit applies text after the event. Re-read then update PDC without overwriting either face.
        plugin.getScheduler().runOnServerLater(()->{
            if(closed||event.isCancelled()||!(event.getBlock().getState() instanceof Sign current))return;
            if(!Arrays.equals(current.getSide(side).getLines(),event.getLines()))return;
            current.getPersistentDataContainer().set(key(side),PersistentDataType.STRING,created.encode());current.update(false,false);
        },50);
    }
    private Side clickedSide(Sign sign,Player player){
        org.bukkit.util.Vector normal;
        var data=sign.getBlockData();
        if(data instanceof Directional directional)normal=directional.getFacing().getDirection();
        else if(data instanceof Rotatable rotatable)normal=rotatable.getRotation().getDirection();
        else return Side.FRONT;
        var toward=player.getEyeLocation().toVector().subtract(sign.getLocation().add(.5,.5,.5).toVector());
        return toward.dot(normal)>=0?Side.FRONT:Side.BACK;
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void using(PlayerInteractEvent event){
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getHand()!=EquipmentSlot.HAND||event.useInteractedBlock()==Event.Result.DENY||event.getClickedBlock()==null||!(event.getClickedBlock().getState() instanceof Sign sign))return;
        Side side=clickedSide(sign,event.getPlayer());var found=identity(sign,side);if(found.isEmpty())return;
        var identity=found.get();Player player=event.getPlayer();
        if(pending.contains(player.getUniqueId())||!enabled.contains(identity.type())||!permission(player,"use",identity.type())||!clicks.admit(player.getUniqueId(),System.nanoTime(),limit))return;
        pending.add(player.getUniqueId());
        // Let all protection handlers finish before any service can mutate state.
        plugin.getScheduler().runOnServerLater(()->{
            try{
            if(closed||event.isCancelled()||event.useInteractedBlock()==Event.Result.DENY||!player.isOnline()||!(event.getClickedBlock().getState() instanceof Sign current)
                ||!identity(current,side).filter(identity::equals).isPresent()||!permission(player,"use",identity.type()))return;
            execute(player,identity);
            }finally{pending.remove(player.getUniqueId());}
        },50);
    }
    private void execute(Player p,SignIdentity identity){
        String command=switch(identity.type()){
            case COLOR->null;case WARP->"warp";case KIT->"kit";case BALANCE->"balance";case MAIL->"mail";case INFO->"info";
            default->identity.type().key();
        };
        if(!plugin.getConfig().getBoolean("modules.signs",true)||command==null||plugin.getCommandManager().getFamilyPolicy().isDisabled(command))return;
        String module=switch(identity.type()){case WARP->"travel";case KIT->"kits";case BALANCE->"economy";case MAIL->"mail";default->"essentials";};
        var owner=plugin.getModuleManager().getModule(module);if(owner==null||!owner.isEnabled()||!plugin.getConfig().getBoolean("modules."+module,true)||identity.type()==SignIdentity.Type.WARP&&!plugin.getConfig().getBoolean("modules.warps",true))return;
        if(identity.type()==SignIdentity.Type.WARP){((TravelModule)owner).travel().useSign(p,identity.argument(),identity.publicWarp());return;}
        var renderer=plugin.getCommandManager().getRenderedCommands().get(command);var registered=plugin.getCommand(command);
        if(renderer==null||registered==null)return;
        String[] args=switch(identity.type()){case KIT->new String[]{identity.argument()};case MAIL->new String[]{"read"};default->new String[0];};
        renderer.onCommand(p,registered,command,args);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breaking(BlockBreakEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        for(Side side:Side.values())if(identity(sign,side).filter(id->!permission(event.getPlayer(),"break",id.type())).isPresent()){event.setCancelled(true);return;}
    }
    @EventHandler public void quit(PlayerQuitEvent event){clicks.forget(event.getPlayer().getUniqueId());pending.remove(event.getPlayer().getUniqueId());}
}
