package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.*;
import codes.castled.allium.economy.*;
import codes.castled.allium.message.Messages;
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
    private SignEditorGuard editorGuard;
    private final PaidRepairService paidRepairs;
    public SignService(PluginStart plugin){this(plugin,null);}
    public SignService(PluginStart plugin,PaidRepairService paidRepairs){
        this.plugin=plugin;this.paidRepairs=paidRepairs;
        for(String raw:plugin.getConfig().getStringList("enabledSigns"))enabled.add(SignIdentity.Type.parse(raw));
        limit=plugin.getConfig().getInt("sign-use-per-second",4);if(limit<1||limit>100)throw new IllegalArgumentException("sign-use-per-second must be 1..100");
    }
    public void enable(){plugin.getServer().getPluginManager().registerEvents(this,plugin);editorGuard=new SignEditorGuard(plugin,this);}
    public void close(){closed=true;if(editorGuard!=null)editorGuard.close();HandlerList.unregisterAll(this);}
    private NamespacedKey key(Side side){return new NamespacedKey(plugin,"sign_"+side.name().toLowerCase(Locale.ROOT));}
    private Optional<SignIdentity> identity(Sign sign,Side side){return SignIdentity.decode(sign.getPersistentDataContainer().get(key(side),PersistentDataType.STRING));}
    private boolean permission(Player p,String action,SignIdentity.Type type){return p.hasPermission("allium.signs."+action+"."+type.key());}
    /**
     * The type named by a header line. Color codes are ignored: re-editing a face keeps each line's
     * existing style on Paper, so an Allium header arrives as {@code §1[repair]}.
     */
    static SignIdentity.Type header(String line){
        line=plain(line);
        if(line==null||!line.startsWith("[")||!line.endsWith("]"))return null;
        try{return SignIdentity.Type.parse(line.substring(1,line.length()-1));}catch(IllegalArgumentException error){return null;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void changing(SignChangeEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        Side side=event.getSide();var old=identity(sign,side);var type=header(event.getLine(0));
        if(old.isPresent()&&!permission(event.getPlayer(),"break",old.get().type())){event.setCancelled(true);return;}
        if(type!=null&&!enabled.contains(type)){
            plugin.getScheduler().runOnServerLater(()->{if(closed||event.isCancelled())return;if(event.getBlock().getState() instanceof Sign current&&old.isPresent()&&identity(current,side).filter(old.get()::equals).isPresent()){current.getPersistentDataContainer().remove(key(side));current.update(false,false);}disabled(event.getPlayer(),type);},50);
            return;
        }
        if(type==null){
            // Remove the old face authority only when the actual text edit survives protection.
            plugin.getScheduler().runOnServerLater(()->{if(!closed&&!event.isCancelled()&&event.getBlock().getState() instanceof Sign current){current.getPersistentDataContainer().remove(key(side));current.update(false,false);}},50);
            return;
        }
        boolean publicWarp=type==SignIdentity.Type.WARP&&"public".equalsIgnoreCase(plain(event.getLine(2)));
        if(!permission(event.getPlayer(),"create",type)||publicWarp&&!event.getPlayer().hasPermission("allium.signs.create.warp.public")){event.setCancelled(true);return;}
        String argument=Objects.toString(plain(event.getLine(1)),"");
        Money price=new Money(0);
        if(type==SignIdentity.Type.REPAIR){
            argument=argument.isEmpty()?"hand":argument.toLowerCase(Locale.ROOT);
            if(!Set.of("hand","all").contains(argument)){
                invalid(event,type,2,"<hand|all>","Use hand or all.");
                return;
            }
            try{price=EconomyDisplay.read(plugin.getConfig()).parsePrice(Objects.toString(plain(event.getLine(2)),""));}
            catch(IllegalArgumentException|ArithmeticException error){
                invalid(event,type,3,"<price>","Enter a non-negative amount with up to two decimal places.");
                return;
            }
        }
        SignIdentity created;
        try{created=new SignIdentity(UUID.randomUUID(),type,argument,publicWarp,price);}
        catch(IllegalArgumentException invalid){
            invalid(event,type,2,"<name>","Enter a name with 1–32 letters, digits, underscores or hyphens.");
            return;
        }
        for(int i=0;i<4;i++)event.setLine(i,ChatColor.translateAlternateColorCodes('&',Objects.toString(event.getLine(i),"")));
        event.setLine(0,ChatColor.DARK_BLUE+displayHeader(type));
        if(type==SignIdentity.Type.REPAIR){
            event.setLine(1,argument.equals("all")?"All":"Hand");
            if(!Objects.toString(plain(event.getLine(2)),"").isBlank())
                event.setLine(2,EconomyDisplay.read(plugin.getConfig()).format(price));
        }
        // Bukkit applies text after the event. Re-read then update PDC without overwriting either face.
        plugin.getScheduler().runOnServerLater(()->{
            if(closed||event.isCancelled()||!(event.getBlock().getState() instanceof Sign current))return;
            if(!Arrays.equals(current.getSide(side).getLines(),event.getLines()))return;
            current.getPersistentDataContainer().set(key(side),PersistentDataType.STRING,created.encode());current.update(false,false);
        },50);
    }
    /** A sign line without color codes (null stays null). */
    static String plain(String line){return line==null?null:ChatColor.stripColor(line).trim();}
    private static String displayHeader(SignIdentity.Type type){
        String name=type.key();
        return "["+Character.toUpperCase(name.charAt(0))+name.substring(1)+"]";
    }
    private void invalid(SignChangeEvent event,SignIdentity.Type type,int line,String hint,String options){
        event.setLine(0,ChatColor.RED+displayHeader(type));
        event.setLine(line-1,ChatColor.RED+hint);
        plugin.getMessageManager().send(event.getPlayer(),Messages.SIGN_INVALID_LINE,
                "{line}",Integer.toString(line),"{options}",options);
        Side side=event.getSide();
        plugin.getScheduler().runOnServerLater(()->{
            if(closed||event.isCancelled()||!(event.getBlock().getState() instanceof Sign current))return;
            if(!Arrays.equals(current.getSide(side).getLines(),event.getLines()))return;
            current.getPersistentDataContainer().remove(key(side));
            current.update(false,false);
        },50);
    }
    public boolean blocksEditor(Sign sign,Side side,String cause){return !closed&&"INTERACT".equals(cause)&&identity(sign,side).filter(id->id.type()!=SignIdentity.Type.COLOR&&enabled.contains(id.type())).isPresent();}
    private void disabled(Player player,SignIdentity.Type type){plugin.getMessageManager().send(player,codes.castled.allium.message.Messages.SIGN_DISABLED,"{type}",type.key());}
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
        if(!enabled.contains(identity.type())){if(clicks.admit(player.getUniqueId(),System.nanoTime(),limit))disabled(player,identity.type());return;}
        if(pending.contains(player.getUniqueId())||!permission(player,"use",identity.type())||!clicks.admit(player.getUniqueId(),System.nanoTime(),limit))return;
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
        if(identity.type()==SignIdentity.Type.REPAIR&&identity.price().minor()>0){
            // Reserve, repair and save with a receipt, then capture: see PaidRepairService.
            if(paidRepairs==null)plugin.getMessageManager().send(p,Messages.SIGN_PAID_REPAIR_DISABLED);
            else paidRepairs.start(p,identity.argument(),identity.price());
            return;
        }
        String[] args=switch(identity.type()){case KIT,REPAIR->new String[]{identity.argument()};case MAIL->new String[]{"read"};default->new String[0];};
        renderer.onCommand(p,registered,command,args);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breaking(BlockBreakEvent event){
        if(!(event.getBlock().getState() instanceof Sign sign))return;
        for(Side side:Side.values())if(identity(sign,side).filter(id->!permission(event.getPlayer(),"break",id.type())).isPresent()){event.setCancelled(true);return;}
    }
    @EventHandler public void quit(PlayerQuitEvent event){clicks.forget(event.getPlayer().getUniqueId());pending.remove(event.getPlayer().getUniqueId());}
}
