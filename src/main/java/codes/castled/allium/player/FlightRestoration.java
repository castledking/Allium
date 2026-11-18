package codes.castled.allium.player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import java.util.*;

/** Native playerdata persists flight preferences; all player APIs stay on the server thread. */
public final class FlightRestoration implements Listener,AutoCloseable {
 private final PluginStart plugin;
 private final NamespacedKey slowOff,allow,flying;
 private final Map<Player,Boolean> previous=new IdentityHashMap<>();
 private final Set<Player> protectedFall=Collections.newSetFromMap(new IdentityHashMap<>());
 private final Map<Player,boolean[]> worldFlight=new IdentityHashMap<>();
 private TaskHandle timer=TaskHandle.NONE;
 private boolean closed;
 public FlightRestoration(PluginStart plugin){this.plugin=plugin;slowOff=new NamespacedKey(plugin,"slowfall_disabled_v1");allow=new NamespacedKey(plugin,"flight_allowed_v1");flying=new NamespacedKey(plugin,"flight_flying_v1");}
 public void enable(){plugin.getServer().getPluginManager().registerEvents(this,plugin);for(Player p:plugin.getServer().getOnlinePlayers())restore(p);timer=plugin.getScheduler().repeatOnServer(()->{if(closed)return false;for(Player p:plugin.getServer().getOnlinePlayers())tick(p);return true;},50,250);}
 private static boolean nativeFlight(Player p){return p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR;}
 public boolean disabled(Player p){return p.getPersistentDataContainer().getOrDefault(slowOff,PersistentDataType.BYTE,(byte)0)!=0;}
 public void toggle(Player p,String input){
  boolean off=disabled(p);
  switch(input.toLowerCase(Locale.ROOT)){case ""->off=!off;case "on","enable","true"->off=false;case "off","disable","false"->off=true;case "status","state"->{send(p,off);return;}default->throw new IllegalArgumentException("Use /toggleslowfall [on|off|status]");}
  p.getPersistentDataContainer().set(slowOff,PersistentDataType.BYTE,(byte)(off?1:0));if(off)removeOwned(p);p.saveData();send(p,off);
 }
 private void send(Player p,boolean off){plugin.getMessageManager().send(p,Messages.SLOWFALL_TOGGLE,"{state}",off?"&cdisabled":"&aenabled");}
 public void changed(Player p){if(!p.getAllowFlight()&&!nativeFlight(p)&&airborne(p))protect(p);remember(p);p.saveData();}
 private void remember(Player p){if(!nativeFlight(p)){p.getPersistentDataContainer().set(allow,PersistentDataType.BYTE,(byte)(p.getAllowFlight()?1:0));p.getPersistentDataContainer().set(flying,PersistentDataType.BYTE,(byte)(p.isFlying()?1:0));}previous.put(p,p.getAllowFlight());}
 private static boolean airborne(Player p){return !p.isOnGround()&&!p.isInWater()&&!p.isFlying();}
 public static boolean needsSafeLogin(Player p){
  if(p.isInWater()||p.isOnGround())return false;
  Location at=p.getLocation();World w=at.getWorld();if(w==null)return false;
  // Essentials checks more than two unsafe blocks below, rather than every join above a slab.
  for(int depth=0;depth<3;depth++){int y=at.getBlockY()-depth;if(y<w.getMinHeight())return true;var block=w.getBlockAt(at.getBlockX(),y,at.getBlockZ());if(!block.isPassable()&&!block.isLiquid())return false;}
  return true;
 }
 private void restore(Player p){
  if(closed||!p.isOnline())return;
  if(!nativeFlight(p)){
   boolean granted=p.hasPermission("allium.fly");boolean saved=p.getPersistentDataContainer().getOrDefault(allow,PersistentDataType.BYTE,(byte)0)!=0;
   boolean safe=p.hasPermission("allium.fly.safelogin")&&needsSafeLogin(p);
   boolean enabled=granted&&saved||safe;p.setAllowFlight(enabled);
   if(enabled){p.setFallDistance(0);p.setFlying(safe||p.getPersistentDataContainer().getOrDefault(flying,PersistentDataType.BYTE,(byte)0)!=0);}
   else if(airborne(p)&&(granted||saved))protect(p);
  }
  previous.put(p,p.getAllowFlight());
 }
 private void protect(Player p){if(disabled(p)||nativeFlight(p)||p.isFlying())return;var existing=p.getPotionEffect(PotionEffectType.SLOW_FALLING);if(existing==null){p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,60,0,false,false,true));protectedFall.add(p);}}
 private void removeOwned(Player p){if(!protectedFall.remove(p))return;var e=p.getPotionEffect(PotionEffectType.SLOW_FALLING);if(e!=null&&e.getAmplifier()==0&&e.getDuration()<=60&&!e.hasParticles())p.removePotionEffect(PotionEffectType.SLOW_FALLING);}
 private void tick(Player p){boolean was=previous.getOrDefault(p,false);if(was&&!p.getAllowFlight()&&airborne(p))protect(p);previous.put(p,p.getAllowFlight());if(protectedFall.contains(p)){if(!airborne(p)||disabled(p)){removeOwned(p);return;}var e=p.getPotionEffect(PotionEffectType.SLOW_FALLING);if(e==null||e.getAmplifier()==0&&e.getDuration()<20&&!e.hasParticles())p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,60,0,false,false,true));}}
 @EventHandler public void join(PlayerJoinEvent e){Player p=e.getPlayer();plugin.getScheduler().runOnServerLater(()->{if(plugin.getServer().getPlayer(p.getUniqueId())==p)restore(p);},50);}
 @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){remember(e.getPlayer());previous.remove(e.getPlayer());protectedFall.remove(e.getPlayer());worldFlight.remove(e.getPlayer());}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(e.getTo()!=null&&e.getTo().getWorld()!=e.getFrom().getWorld())worldFlight.put(e.getPlayer(),new boolean[]{e.getPlayer().getAllowFlight(),e.getPlayer().isFlying()});}
 @EventHandler public void world(PlayerChangedWorldEvent e){Player p=e.getPlayer();boolean[] before=worldFlight.remove(p);plugin.getScheduler().runOnServerLater(()->{if(closed||!p.isOnline()||nativeFlight(p))return;if(before!=null&&before[0]&&p.hasPermission("allium.fly")){p.setAllowFlight(true);p.setFlying(before[1]);}else if(airborne(p))protect(p);previous.put(p,p.getAllowFlight());},50);}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mode(PlayerGameModeChangeEvent e){Player p=e.getPlayer();GameMode old=p.getGameMode();plugin.getScheduler().runOnServerLater(()->{if(closed||!p.isOnline()||p.getGameMode()!=e.getNewGameMode())return;if(nativeFlight(p))removeOwned(p);else if(old==GameMode.CREATIVE||old==GameMode.SPECTATOR){if(p.hasPermission("allium.fly"))p.setAllowFlight(true);if(airborne(p))protect(p);}previous.put(p,p.getAllowFlight());},50);}
 public void close(){closed=true;timer.cancel();HandlerList.unregisterAll(this);for(Player p:plugin.getServer().getOnlinePlayers()){remember(p);removeOwned(p);p.saveData();}previous.clear();worldFlight.clear();}
}
