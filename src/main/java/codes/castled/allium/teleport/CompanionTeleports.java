package codes.castled.allium.teleport;
import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import java.util.*;
/** Connection-owned selections, claimed once on successful movement. */
public final class CompanionTeleports implements Listener,AutoCloseable {
 private static final class Selection {final Player owner;final Location start;final Set<UUID> pets=new LinkedHashSet<>(),entities=new LinkedHashSet<>();boolean petMode,entityMode,pending;long revision;Selection(Player p){owner=p;start=p.getLocation().clone();}}
 private record Glow(Entity entity,boolean original,Set<UUID> owners){}
 private final Map<UUID,Selection> selections=new HashMap<>();private final Map<UUID,Glow> glows=new HashMap<>();private final PluginStart plugin;private final DestinationSafety safety;private TaskHandle timer=TaskHandle.NONE;
 public CompanionTeleports(PluginStart plugin){this(plugin,new DestinationSafety());}
 CompanionTeleports(PluginStart plugin,DestinationSafety safety){this.plugin=plugin;this.safety=safety;}
 public void enable(){plugin.getServer().getPluginManager().registerEvents(this,plugin);timer=plugin.getScheduler().repeatOnServer(()->{for(var s:List.copyOf(selections.values()))if(!s.pending&&(!s.owner.isOnline()||plugin.getServer().getPlayer(s.owner.getUniqueId())!=s.owner||!s.owner.hasPermission("allium.admin")&&(s.owner.getWorld()!=s.start.getWorld()||s.owner.getLocation().distanceSquared(s.start)>900)))clear(s.owner.getUniqueId());return true;},1000,1000);}
 public void toggle(CommandSender actor,Player target,boolean pets){String permission=pets?"allium.tppet":"allium.tpmob";if(!actor.hasPermission(permission)||actor!=target&&!actor.hasPermission(permission+".others")||!target.hasPermission(permission)){send(actor,"Permission denied.");return;}var s=selections.computeIfAbsent(target.getUniqueId(),id->new Selection(target));boolean active=pets?s.petMode:s.entityMode;if(pets)s.petMode=!active;else s.entityMode=!active;if(active){var removed=new HashSet<>(pets?s.pets:s.entities);(pets?s.pets:s.entities).clear();for(UUID id:removed)if(!s.pets.contains(id)&&!s.entities.contains(id))release(target.getUniqueId(),id);}send(target,(pets?"Pet":"Entity")+" selection "+(active?"disabled.":"enabled. Right-click to select."));if(actor!=target)send(actor,"Selection mode updated for "+target.getName()+".");if(!s.petMode&&!s.entityMode)clear(target.getUniqueId());}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void click(PlayerInteractEntityEvent event){if(event.getHand()!=EquipmentSlot.HAND)return;var s=selections.get(event.getPlayer().getUniqueId());if(s==null)return;Entity e=event.getRightClicked();boolean pet=s.petMode&&owned(e,s.owner);boolean mob=s.entityMode&&!(e instanceof Player);if(!pet&&!mob)return;
  // Defer mutation until every protection listener has had its turn.
  plugin.getScheduler().runOnServerLater(()->{if(event.isCancelled()||selections.get(s.owner.getUniqueId())!=s||!e.isValid())return;select(s,e,pet);},50);
 }
 private void select(Selection s,Entity e,boolean pets){if(!(pets?s.petMode:s.entityMode)||pets&&!owned(e,s.owner)||plugin.getServer().getPlayer(s.owner.getUniqueId())!=s.owner)return;String permission=pets?"allium.tppet":"allium.tpmob";if(!s.owner.hasPermission(permission))return;Set<UUID> selected=pets?s.pets:s.entities;UUID id=e.getUniqueId();if(selected.remove(id)){if(!s.pets.contains(id)&&!s.entities.contains(id))release(s.owner.getUniqueId(),id);send(s.owner,"Deselected "+e.getType().name()+".");return;}int max=1;for(int i=1;i<=10;i++)if(s.owner.hasPermission(permission+"."+i))max=i;if(selected.size()>=max){send(s.owner,"Selection limit: "+max);return;}selected.add(id);var glow=glows.computeIfAbsent(id,k->new Glow(e,e.isGlowing(),new HashSet<>()));glow.owners().add(s.owner.getUniqueId());e.setGlowing(true);send(s.owner,"Selected "+e.getType().name()+".");}
 private static boolean owned(Entity e,Player p){return e instanceof Tameable t&&t.isTamed()&&t.getOwner()!=null&&p.getUniqueId().equals(t.getOwner().getUniqueId());}
 /** Snapshot of the player's current /tpe selection, without consuming it. */
 public List<LivingEntity> selectedMobs(Player player) {
  Selection selection = selections.get(player.getUniqueId());
  if (selection == null || selection.owner != player || !selection.entityMode
          || !player.hasPermission("allium.tpmob")) return List.of();

  List<LivingEntity> mobs = new ArrayList<>();
  for (UUID id : selection.entities) {
   Entity entity = plugin.getServer().getEntity(id);
   if (entity instanceof LivingEntity living && !(living instanceof Player)
           && living.isValid() && !living.isDead() && living.getWorld() == player.getWorld()) {
    mobs.add(living);
   }
  }
  return List.copyOf(mobs);
 }
 public boolean move(Player player,Location destination){var s=selections.get(player.getUniqueId());if(s==null||s.pets.isEmpty()&&s.entities.isEmpty())return false;var safe=safety.resolve(destination,TeleportSettings.read(plugin.getConfig()));if(safe.isEmpty()){send(player,"No safe destination for the selection.");return true;}var ids=new LinkedHashSet<>(s.pets);ids.addAll(s.entities);if(!selections.remove(player.getUniqueId(),s))return true;for(UUID id:ids)release(player.getUniqueId(),id);int moved=0;for(UUID id:ids){Entity e=plugin.getServer().getEntity(id);boolean authorized=e!=null&&e.isValid()&&!(e instanceof Player)&&(s.entities.contains(id)&&player.hasPermission("allium.tpmob")||s.pets.contains(id)&&player.hasPermission("allium.tppet")&&owned(e,player));if(authorized&&e.teleport(safe.get()))moved++;}clear(player.getUniqueId());send(player,"Teleported "+moved+" selected companion(s); selection disabled.");return true;}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event){var s=selections.get(event.getPlayer().getUniqueId());if(s==null||event.getTo()==null)return;Location destination=event.getTo().clone();s.pending=true;long revision=++s.revision;plugin.getScheduler().runOnServerLater(()->{if(s.revision!=revision)return;s.pending=false;if(!event.isCancelled()&&selections.get(event.getPlayer().getUniqueId())==s&&event.getPlayer().isOnline()&&event.getPlayer().getWorld()==destination.getWorld()&&event.getPlayer().getLocation().distanceSquared(destination)<4)move(event.getPlayer(),destination);},50);}
 @EventHandler public void quit(PlayerQuitEvent event){clear(event.getPlayer().getUniqueId());}
 private void release(UUID owner,UUID entity){var glow=glows.get(entity);if(glow!=null){glow.owners().remove(owner);if(glow.owners().isEmpty()){if(glow.entity().isValid())glow.entity().setGlowing(glow.original());glows.remove(entity);}}}
 private void clear(UUID player){var s=selections.remove(player);if(s!=null){var all=new HashSet<>(s.pets);all.addAll(s.entities);for(UUID id:all)release(player,id);}}
 private void send(CommandSender sender,String text){plugin.getMessageManager().send(sender,Messages.TELEPORT_SELECTION,"{message}",text);}
 public void close(){timer.cancel();HandlerList.unregisterAll(this);for(UUID id:List.copyOf(selections.keySet()))clear(id);}
}
