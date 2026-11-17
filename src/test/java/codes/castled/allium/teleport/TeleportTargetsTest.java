package codes.castled.allium.teleport;
import codes.castled.allium.PluginStart;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TeleportTargetsTest {
 Player owner,a,b,hidden;World world;Server server;TeleportTargets targets;
 Player player(String name,double x){var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn(name);when(p.getWorld()).thenReturn(world);when(p.getLocation()).thenReturn(new Location(world,x,80,0));when(p.isValid()).thenReturn(true);when(p.canSee(org.mockito.ArgumentMatchers.any(Player.class))).thenReturn(true);when(p.getType()).thenReturn(EntityType.PLAYER);when(p.getScoreboardTags()).thenReturn(Set.of());when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);return p;}
 @BeforeEach void setup(){PlayerNameVisibility.setVanishHooks(null,null);var plugin=mock(PluginStart.class);server=mock(Server.class);world=mock(World.class);when(world.getSpawnLocation()).thenReturn(new Location(world,0,80,0));when(server.getWorld("world")).thenReturn(world);when(plugin.getServer()).thenReturn(server);targets=new TeleportTargets(plugin);owner=player("Owner",0);a=player("A",2);b=player("B",6);hidden=player("Hidden",1);doReturn(List.of(owner,a,b,hidden)).when(world).getPlayers();when(owner.canSee(hidden)).thenReturn(false);when(owner.hasPermission("allium.selectors")).thenReturn(true);when(owner.hasPermission("allium.command.selector.all")).thenReturn(true);}
 @Test void nearestExcludesSelfAndHiddenBeforeLimiting(){assertEquals(List.of(a),targets.resolve(owner,"@p"));}
 @Test void rangesSortLimitAndNameNegationFollowLegacy(){assertEquals(List.of(b),targets.resolve(owner,"@a[distance=1..10,name=!A,sort=furthest,limit=1]"));assertEquals(List.of(a),targets.resolve(owner,"@a[x=0,y=80,z=0,dx=4,dy=1,dz=1,name=!Owner]"));}
 @Test void selectorsNeedGeneralAndSpecificPermission(){when(owner.hasPermission("allium.selectors")).thenReturn(false);assertThrows(SecurityException.class,()->targets.resolve(owner,"@a"));when(owner.hasPermission("allium.selectors")).thenReturn(true);when(owner.hasPermission("allium.command.selector.all")).thenReturn(false);assertThrows(SecurityException.class,()->targets.resolve(owner,"@a"));assertThrows(SecurityException.class,()->targets.resolve(owner,"@e"));}
 @Test void malformedAndOversizedSelectorsRefuse(){for(String s:List.of("@x","@a[unknown=x]","@a[limit=129]","@a[x=NaN]","@a[world=missing]","@a[limit=1,limit=2]"))assertThrows(IllegalArgumentException.class,()->targets.resolve(owner,s),s);}
 @Test void tagsGamemodeAndWorldFilter(){when(a.getScoreboardTags()).thenReturn(Set.of("test"));assertEquals(List.of(a),targets.resolve(owner,"@a[world=world,tag=test,gamemode=survival]"));assertEquals(List.of(owner),targets.resolve(owner,"@s"));}
}
