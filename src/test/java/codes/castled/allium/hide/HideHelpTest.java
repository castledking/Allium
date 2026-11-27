package codes.castled.allium.hide;
import codes.castled.allium.PluginStart;
import codes.castled.allium.message.*;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HideHelpTest {
 record Fixture(HideService service,PluginStart plugin,Player player,Command guarded){}
 Fixture fixture(boolean resolver)throws Exception{
  var plugin=mock(PluginStart.class);var server=mock(Server.class);var manager=mock(org.bukkit.plugin.PluginManager.class);var scheduler=mock(TaskScheduler.class);
  when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(manager);when(manager.getPermissions()).thenReturn(Set.of());when(plugin.getScheduler()).thenReturn(scheduler);when(plugin.getDataFolder()).thenReturn(new java.io.File("/tmp/hide-help-unit"));when(plugin.getMessageManager()).thenReturn(mock(MessageManager.class));
  var player=mock(Player.class);when(player.getServer()).thenReturn(server);doReturn(List.of(player)).when(server).getOnlinePlayers();when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getEffectivePermissions()).thenReturn(Set.of());
  var c=new YamlConfiguration();c.set("settings.enabled",true);c.set("settings.use-allium-permission-resolver",resolver);c.set("groups.default.commands",List.of("party invite","guarded","help"));c.set("groups.default.tabcompletes",List.of("party invite","guarded","hidden"));
  var party=mock(Command.class);when(party.getName()).thenReturn("party");when(party.getDescription()).thenReturn("Party");
  var guarded=mock(Command.class);when(guarded.getName()).thenReturn("guarded");when(guarded.getPermission()).thenReturn("secret.required");
  var service=new HideService(plugin);set(service,"settings",HideSettings.read(c,Map.of()));set(service,"commands",Map.of("party",party,"guarded",guarded,"hidden",party,"help",party));
  return new Fixture(service,plugin,player,guarded);
 }
 static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
 @Test void helpRequiresExecutionCompletionAndResolvedPermission()throws Exception{var f=fixture(true);assertEquals(List.of("party invite"),f.service.helpEntries(f.player).stream().map(HideService.HelpEntry::path).toList());when(f.guarded.testPermissionSilent(f.player)).thenReturn(true);assertEquals(List.of("guarded","party invite"),f.service.helpEntries(f.player).stream().map(HideService.HelpEntry::path).toList());}
 @Test void allowedButUnauthorizedUsesLegacyNoPermissionKey()throws Exception{var f=fixture(true);var event=new PlayerCommandPreprocessEvent(f.player,"/guarded");f.service.command(event);assertTrue(event.isCancelled());verify(f.plugin.getMessageManager()).send(f.player,Messages.HIDE_NO_PERMISSION,"{cmd}","guarded");}
 @Test void disablingResolverDelegatesNativePermissionsButKeepsHideRules()throws Exception{var f=fixture(false);var event=new PlayerCommandPreprocessEvent(f.player,"/guarded");f.service.command(event);assertFalse(event.isCancelled());assertTrue(f.service.helpEntries(f.player).stream().anyMatch(e->e.path().equals("guarded")));var denied=new PlayerCommandPreprocessEvent(f.player,"/party kick");f.service.command(denied);assertTrue(denied.isCancelled());}
 @Test void suggestionsOnlyConsiderVisibleExecutableRoots(){assertEquals("help",HideService.closest("hel",List.of("help","party invite")));assertNull(HideService.closest("secret",List.of("help","party invite")));assertNull(HideService.closest("party",List.of("party invite")));}
 @Test void opWithBypassCanRunWorldEditDoubleSlashCommands() throws Exception {
  var f = fixture(true);
  var owner = mock(org.bukkit.plugin.Plugin.class);
  when(owner.getName()).thenReturn("WorldEdit");
  var wand = mock(Command.class, withSettings().extraInterfaces(PluginIdentifiableCommand.class));
  when(((PluginIdentifiableCommand) wand).getPlugin()).thenReturn(owner);
  when(wand.getName()).thenReturn("/wand");
  when(wand.getAliases()).thenReturn(List.of());
  set(f.service, "commands", Map.of("/wand", wand, "worldedit:/wand", wand));

  // OP / bypass player runs //wand (registered as /wand) and //set stone (dynamic/unregistered in knownCommands)
  when(f.player.hasPermission(HideService.BYPASS)).thenReturn(true);
  var wandEvent = new PlayerCommandPreprocessEvent(f.player, "//wand");
  f.service.command(wandEvent);
  assertFalse(wandEvent.isCancelled(), "OP with allium.hide.bypass must be able to execute //wand");

  var setEvent = new PlayerCommandPreprocessEvent(f.player, "//set stone");
  f.service.command(setEvent);
  assertFalse(setEvent.isCancelled(), "OP with allium.hide.bypass must be able to execute dynamic //set");

  // Non-OP player without //wand in hide.yml is still blocked
  when(f.player.hasPermission(HideService.BYPASS)).thenReturn(false);
  var blockedEvent = new PlayerCommandPreprocessEvent(f.player, "//wand");
  f.service.command(blockedEvent);
  assertTrue(blockedEvent.isCancelled());
 }
}
