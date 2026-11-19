package codes.castled.allium.hide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HideSettingsTest {
 @Test void malformedFlagsAndListsNeverSilentlyDisableProtection(){for(String text:List.of("settings: {enabled: banana}","groups: []","groups: {default: {commands: [123]}}","settings: {allow-namespaced-suggestions: hello}")){var c=YamlConfiguration.loadConfiguration(new java.io.StringReader(text));assertThrows(IllegalArgumentException.class,()->HideSettings.read(c,Map.of()));}}

 @Test void defaultsOptInAndNamespaceWhitelist(){var c=new YamlConfiguration();var s=HideSettings.read(c,Map.of());assertFalse(s.enabled());assertTrue(s.namespacedVisible("help",false));assertFalse(s.namespacedVisible("unknown:help",false));assertTrue(s.namespacedVisible("unknown:help",true));c.set("settings.allow-namespaced-suggestions",List.of("allium:*"));c.set("settings.hide-namespaced-commands-for-ops",true);s=HideSettings.read(c,Map.of());assertTrue(s.namespacedVisible("allium:help",false));assertFalse(s.namespacedVisible("allium:help",true));}
 @Test void implicitParentsAreCaseInsensitiveAndValidatedBeforeReplacement(){var c=new YamlConfiguration();c.set("groups.Parent.commands",List.of("help"));c.set("groups.Parent.tabcompletes",List.of("^"));c.set("groups.child.commands",List.of("mail"));var s=HideSettings.read(c,Map.of("child",Set.of("parent")));assertTrue(HidePolicy.allows(s.policy().select(List.of("child")),"help",false));c.set("groups.child.inherits.commands",List.of("missing"));assertThrows(IllegalArgumentException.class,()->HideSettings.read(c,Map.of()));}
 @Test void manualMappingsPreserveDottedPermissionAndRejectDuplicates(){var c=new YamlConfiguration();c.set("permission-overrides",List.of(Map.of("command","shop","permission","nights.shop.open")));assertEquals("nights.shop.open",HideSettings.read(c,Map.of()).permissionOverrides().get("shop"));c.set("permission-overrides",List.of(Map.of("command","shop","permission","a"),Map.of("command","shop","permission","b")));assertThrows(IllegalArgumentException.class,()->HideSettings.read(c,Map.of()));}
 @Test void aLegacyUnsafeListCannotHideAlliumsOwnCommands(){
  var c=new YamlConfiguration();c.set("settings.enabled",true);c.set("settings.unsafe-commands-list",List.of("plugins","msg","r","bukkit:msg"));
  c.set("groups.default.commands",List.of("msg","r"));c.set("groups.default.tabcompletes",List.of("^"));
  var s=HideSettings.read(c,Map.of()).keepingVisible(label->Set.of("msg","r").contains(label));
  assertEquals(Set.of("plugins","bukkit:msg"),s.unsafe());
  var v=new HideService.Viewer(s,s.policy().select(List.of("default")),false,Map.of(),0);
  assertTrue(HideService.packetVisible(v,"msg"));assertFalse(HideService.packetVisible(v,"plugins"));
 }
 @Test void packetSnapshotUsesAliasOwnerAndBlacklistPriority(){var c=new YamlConfiguration();c.set("settings.enabled",true);c.set("groups.staff.commands",List.of("provider:*"));c.set("groups.staff.tabcompletes",List.of("^"));c.set("groups.alt.whitelist",false);c.set("groups.alt.commands",List.of("shop admin"));c.set("groups.alt.tabcompletes",List.of("^"));var s=HideSettings.read(c,Map.of());var v=new HideService.Viewer(s,s.policy().select(List.of("staff","alt")),false,Map.of("shop",List.of("provider:shop")),0);assertTrue(HideService.packetVisible(v,"shop open"));assertFalse(HideService.packetVisible(v,"shop admin"));}
}
