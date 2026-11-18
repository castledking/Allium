package codes.castled.allium.player;
import codes.castled.allium.PluginStart;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.scheduler.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FlightRestorationTest {
 static final class Fixture {
  final PluginStart plugin=mock(PluginStart.class);final Player player=mock(Player.class);final Map<NamespacedKey,Byte> bytes=new HashMap<>();
  Fixture(){when(plugin.getName()).thenReturn("Allium");var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));doReturn(List.of(player)).when(server).getOnlinePlayers();when(plugin.getScheduler()).thenReturn(mock(TaskScheduler.class));when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));when(player.isOnline()).thenReturn(true);when(player.isOnGround()).thenReturn(true);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);var data=mock(PersistentDataContainer.class);when(player.getPersistentDataContainer()).thenReturn(data);when(data.getOrDefault(any(NamespacedKey.class),eq(PersistentDataType.BYTE),anyByte())).thenAnswer(c->bytes.getOrDefault(c.getArgument(0),c.getArgument(2)));doAnswer(c->{bytes.put(c.getArgument(0),c.getArgument(2));return null;}).when(data).set(any(NamespacedKey.class),eq(PersistentDataType.BYTE),anyByte());}
 }
 @Test void slowfallPreferenceSurvivesServiceReplacementAndSavesNativeData(){var f=new Fixture();var first=new FlightRestoration(f.plugin);first.toggle(f.player,"off");assertTrue(new FlightRestoration(f.plugin).disabled(f.player));first.toggle(f.player,"on");assertFalse(first.disabled(f.player));verify(f.player,times(2)).saveData();}
 @Test void restoreRequiresCurrentFlightPermission(){var f=new Fixture();f.bytes.put(new NamespacedKey("allium","flight_allowed_v1"),(byte)1);f.bytes.put(new NamespacedKey("allium","flight_flying_v1"),(byte)1);when(f.player.hasPermission("allium.fly")).thenReturn(true);new FlightRestoration(f.plugin).enable();verify(f.player).setAllowFlight(true);verify(f.player).setFlying(true);when(f.player.hasPermission("allium.fly")).thenReturn(false);new FlightRestoration(f.plugin).enable();verify(f.player).setAllowFlight(false);}
 @Test void safeLoginChecksAThreeBlockGapAndExcludesWater(){var p=mock(Player.class);var world=mock(World.class);when(p.getLocation()).thenReturn(new Location(world,0,80,0));when(world.getMinHeight()).thenReturn(-64);var air=mock(Block.class);when(air.isPassable()).thenReturn(true);when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(air);assertTrue(FlightRestoration.needsSafeLogin(p));when(p.isInWater()).thenReturn(true);assertFalse(FlightRestoration.needsSafeLogin(p));}
 @Test void groundedLoginDoesNotReadBlocks(){var p=mock(Player.class);when(p.isOnGround()).thenReturn(true);assertFalse(FlightRestoration.needsSafeLogin(p));verify(p,never()).getLocation();}
}
