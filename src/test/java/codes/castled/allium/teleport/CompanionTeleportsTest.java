package codes.castled.allium.teleport;
import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class CompanionTeleportsTest {
 PluginStart plugin;Player owner;Wolf wolf;CompanionTeleports service;Deque<Runnable> callbacks;Location point;
 @BeforeEach void setup(){plugin=mock(PluginStart.class);var server=mock(Server.class);var scheduler=mock(TaskScheduler.class);when(plugin.getServer()).thenReturn(server);when(plugin.getScheduler()).thenReturn(scheduler);when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));owner=mock(Player.class);when(owner.getUniqueId()).thenReturn(UUID.randomUUID());when(owner.hasPermission(anyString())).thenReturn(true);when(owner.isOnline()).thenReturn(true);var world=mock(World.class);point=new Location(world,0,80,0);when(owner.getLocation()).thenReturn(point);when(owner.getWorld()).thenReturn(world);when(server.getPlayer(owner.getUniqueId())).thenReturn(owner);wolf=mock(Wolf.class);when(wolf.getUniqueId()).thenReturn(UUID.randomUUID());when(wolf.getOwner()).thenReturn(owner);when(wolf.isTamed()).thenReturn(true);when(wolf.isValid()).thenReturn(true);when(wolf.getType()).thenReturn(EntityType.WOLF);when(server.getEntity(wolf.getUniqueId())).thenReturn(wolf);when(wolf.teleport(any(Location.class))).thenReturn(true);var safety=mock(DestinationSafety.class);when(safety.resolve(any(),any())).thenAnswer(c->Optional.of(c.getArgument(0)));service=new CompanionTeleports(plugin,safety);callbacks=new ArrayDeque<>();when(scheduler.runOnServerLater(any(Runnable.class),anyLong())).thenAnswer(c->{callbacks.add(c.getArgument(0));return codes.castled.allium.scheduler.TaskHandle.NONE;});}
 void click(boolean cancel,EquipmentSlot hand){var event=new PlayerInteractEntityEvent(owner,wolf,hand);event.setCancelled(cancel);service.click(event);while(!callbacks.isEmpty())callbacks.remove().run();}
 @Test void selectionClaimsOnlyOnceAndRestoresGlow(){service.toggle(owner,owner,true);click(false,EquipmentSlot.HAND);assertTrue(service.move(owner,point));assertFalse(service.move(owner,point));verify(wolf,times(1)).teleport(point);verify(wolf).setGlowing(true);verify(wolf).setGlowing(false);}
 @Test void cancelledOrOffhandInteractionDoesNotSelect(){service.toggle(owner,owner,true);click(true,EquipmentSlot.HAND);click(false,EquipmentSlot.OFF_HAND);assertFalse(service.move(owner,point));verify(wolf,never()).setGlowing(true);}
 @Test void foreignPetAndPermissionRevocationRefuseMovement(){service.toggle(owner,owner,true);when(wolf.getOwner()).thenReturn(mock(AnimalTamer.class));click(false,EquipmentSlot.HAND);assertFalse(service.move(owner,point));when(wolf.getOwner()).thenReturn(owner);click(false,EquipmentSlot.HAND);when(owner.hasPermission("allium.tppet")).thenReturn(false);service.move(owner,point);verify(wolf,never()).teleport(any(Location.class));}
 @Test void toggleAndCloseRestorePreexistingGlow(){when(wolf.isGlowing()).thenReturn(true);service.toggle(owner,owner,true);click(false,EquipmentSlot.HAND);service.close();verify(wolf,never()).setGlowing(false);assertFalse(service.move(owner,point));}
}
