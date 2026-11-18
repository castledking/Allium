package codes.castled.allium.hide;
import codes.castled.allium.PluginStart;
import org.bukkit.Server;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HidePermissionResolverTest {
 public static final class PublicNode {String permission;Map<String,PublicNode> children=new HashMap<>();Object executor;PublicNode(String p){permission=p;}public String getPermission(){return permission;}public boolean hasPermission(CommandSender s){return permission==null||s.hasPermission(permission);}public boolean canUse(CommandSender s){return true;}public PublicNode getChild(String key){return children.get(key);}public Collection<PublicNode> getChildren(){return children.values();}public Object getExecutor(){return executor;}}
 public static final class FrameworkCommand extends Command {PublicNode root;FrameworkCommand(PublicNode root){super("shop");this.root=root;}public PublicNode getRoot(){return root;}public boolean execute(CommandSender s,String l,String[] a){throw new AssertionError("resolver must not execute");}}
 @Test void publicFrameworkTreeChecksLiteralPermissionsWithoutExecuting(){var f=fixture();var root=new PublicNode(null);root.children.put("open",new PublicNode("shop.open"));root.children.put("admin",new PublicNode("shop.admin"));var c=new FrameworkCommand(root);var d=f.resolver.resolve(f.player,"shop admin",Map.of("shop",c),Map.of());assertFalse(d.allowed());assertEquals(HidePermissionResolver.Source.NIGHTCORE,d.source());when(f.player.hasPermission("shop.open")).thenReturn(true);assertTrue(f.resolver.resolve(f.player,"shop open",Map.of("shop",c),Map.of()).allowed());}
 @Test void frameworkDefaultOpenIsOnlyAssumedForExistingRootExecutor(){var f=fixture();var root=new PublicNode(null);root.children.put("open",new PublicNode("shop.open"));root.executor=new Object();var c=new FrameworkCommand(root);assertFalse(f.resolver.resolve(f.player,"shop",Map.of("shop",c),Map.of()).allowed());when(f.player.hasPermission("shop.open")).thenReturn(true);assertTrue(f.resolver.resolve(f.player,"shop",Map.of("shop",c),Map.of()).allowed());}

 record Fixture(HidePermissionResolver resolver,Player player,PluginManager manager){}
 Fixture fixture(){var p=mock(PluginStart.class);var s=mock(Server.class);var m=mock(PluginManager.class);when(p.getServer()).thenReturn(s);when(s.getPluginManager()).thenReturn(m);when(m.getPermissions()).thenReturn(Set.of());var player=mock(Player.class);when(player.getEffectivePermissions()).thenReturn(Set.of());return new Fixture(new HidePermissionResolver(p),player,m);}
 @Test void explicitNativePermissionWinsOverManualOverride(){var f=fixture();var c=mock(Command.class);when(c.getPermission()).thenReturn("real.node");when(c.testPermissionSilent(f.player)).thenReturn(false);var d=f.resolver.resolve(f.player,"test",Map.of("test",c),Map.of("test","fake.allowed"));assertFalse(d.allowed());assertEquals(HidePermissionResolver.Source.BUKKIT,d.source());}
 @Test void unknownExistingCommandDefersButMissingCommandRefuses(){var f=fixture();var c=mock(Command.class);when(c.getName()).thenReturn("test");var d=f.resolver.resolve(f.player,"test",Map.of("test",c),Map.of());assertTrue(d.allowed());assertEquals(HidePermissionResolver.Source.UNKNOWN,d.source());assertFalse(f.resolver.resolve(f.player,"forged:test",Map.of("test",c),Map.of()).allowed());}
 @Test void overridesOnlyUnknownAndNeverGrantWithoutTheirPermission(){var f=fixture();var c=mock(Command.class);when(c.getName()).thenReturn("test");var commands=Map.of("test",c);assertFalse(f.resolver.resolve(f.player,"test",commands,Map.of("test","custom.required")).allowed());when(f.player.hasPermission("custom.required")).thenReturn(true);var d=f.resolver.resolve(f.player,"test",commands,Map.of("test","custom.required"));assertTrue(d.allowed());assertEquals(HidePermissionResolver.Source.OVERRIDE,d.source());}
 @Test void moduleCommandCandidatesPreferSpecificActionAndIgnoreDeniedWildcard(){var f=fixture();var owner=mock(org.bukkit.plugin.Plugin.class);when(owner.getName()).thenReturn("Nights");var c=mock(Command.class,withSettings().extraInterfaces(PluginIdentifiableCommand.class));when(((PluginIdentifiableCommand)c).getPlugin()).thenReturn(owner);when(c.getName()).thenReturn("shop");when(c.getAliases()).thenReturn(List.of("store"));when(f.manager.getPermissions()).thenReturn(Set.of(new Permission("nights.shop.command.open"),new Permission("nights.*")));var d=f.resolver.resolve(f.player,"store",Map.of("store",c),Map.of());assertEquals("nights.shop.command.open",d.permission());assertFalse(d.allowed());assertEquals(HidePermissionResolver.Source.REGISTERED,d.source());}
 @Test void broadDefaultFalseWildcardAloneIsNotACommandRequirement(){var f=fixture();var owner=mock(org.bukkit.plugin.Plugin.class);when(owner.getName()).thenReturn("Nights");var c=mock(Command.class,withSettings().extraInterfaces(PluginIdentifiableCommand.class));when(((PluginIdentifiableCommand)c).getPlugin()).thenReturn(owner);when(c.getName()).thenReturn("shop");when(c.getAliases()).thenReturn(List.of());when(f.manager.getPermissions()).thenReturn(Set.of(new Permission("nights.*")));assertEquals(HidePermissionResolver.Source.UNKNOWN,f.resolver.resolve(f.player,"shop",Map.of("shop",c),Map.of()).source());}
 @Test void ownerLevelCommandNodesWithoutAModuleNeverBreakCandidateDiscovery(){
  // A permission such as "allium.command.reload" (no module between owner and ".command.") made
  // /help throw StringIndexOutOfBoundsException while listing commands.
  var c=mock(Command.class);when(c.getName()).thenReturn("help");when(c.getAliases()).thenReturn(List.of());
  var registered=Set.of("allium.command.reload","allium.command.","allium.help.command.open","allium.");
  var out=HidePermissionResolver.candidates("allium",c,"help",List.of(),registered);
  assertTrue(out.contains("allium.help.command.open"),out.toString());
  assertTrue(out.contains("allium.command.help"),out.toString());
 }
}
