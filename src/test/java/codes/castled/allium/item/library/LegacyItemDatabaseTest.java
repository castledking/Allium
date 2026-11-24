package codes.castled.allium.item.library;
import codes.castled.allium.PluginStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LegacyItemDatabaseTest {
 @Test void numericIdsStillResolveButNeverComplete(){var db=new LegacyItemDatabase(Map.of("1","STONE","1:1","GRANITE","10cd","MUSIC_DISC_WARD"));assertEquals("GRANITE",db.lookup("1:1"));assertEquals("STONE",db.lookup("1"));assertFalse(db.complete("1",List.of()).contains("1"));assertFalse(db.complete("1:",List.of()).contains("1:1"));assertTrue(db.complete("10c",List.of()).contains("10cd"));}

 @TempDir Path dir;
 @Test void fullCompressedDatabaseExportsOnceAndPreservesOperatorFile()throws Exception{var p=mock(PluginStart.class);when(p.getDataFolder()).thenReturn(dir.toFile());when(p.getResource(anyString())).thenAnswer(c->getClass().getClassLoader().getResourceAsStream(c.getArgument(0)));when(p.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());var db=LegacyItemDatabase.load(p);assertEquals("POTION potion:long_poison",db.lookup("acid2pot"),"Essentials' vanilla potion");assertEquals("SPAWNER;ALLAY",db.lookup("allay_spawner"));assertEquals("GRANITE",db.lookup("1:1"));assertNull(db.lookup("allium:acid2pot"));assertTrue(db.complete("allium:",List.of("allium:mob_disarmer")).contains("allium:mob_disarmer"));Files.writeString(dir.resolve("itemdb.yml"),"aliases:\n  custom: DIAMOND\n  dsword: STONE_SWORD\n");var mine=LegacyItemDatabase.load(p);assertEquals("DIAMOND",mine.lookup("custom"));
  assertEquals("STONE_SWORD",mine.lookup("dsword"),"the server's file wins");assertEquals("NETHERITE_PICKAXE",mine.lookup("npick"),"bundled aliases reach an older file");assertEquals("NETHERITE_SWORD",mine.lookup("nsword"));}
 private PluginStart plugin(){var p=mock(PluginStart.class);when(p.getDataFolder()).thenReturn(dir.toFile());when(p.getResource(anyString())).thenAnswer(c->getClass().getClassLoader().getResourceAsStream(c.getArgument(0)));when(p.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());return p;}
 @Test void essentialsNumericVariantsAndBareIdsResolveExactly()throws Exception{var db=LegacyItemDatabase.load(plugin());assertEquals("STONE",db.lookup("1"));assertEquals("POLISHED_GRANITE",db.lookup("1:2"));assertEquals("SPRUCE_LOG",db.lookup("17:1"));assertEquals("RED_WOOL",db.lookup("35:14"));assertEquals("ZOMBIE_SPAWN_EGG",db.lookup("383:54"));assertEquals("SPAWNER;ZOMBIE",db.lookup("52:54"));assertEquals("POTION potion:long_swiftness",db.lookup("373:8258"));assertEquals("SPLASH_POTION potion:swiftness",db.lookup("373:16386"));assertEquals("POTION;awkward",db.lookup("373:16"));}
 @Test void existingWrongDefaultsUpgradeWithBackupAndKeepCustomAliasesAndIds()throws Exception{String old="aliases:\n  special: DIAMOND\nlegacy-ids:\n  '1:2': POLISHED_GRANITE_SLAB\n  '35:14': GOLD_BLOCK\n  '9000': EMERALD\n";Files.writeString(dir.resolve("itemdb.yml"),old);var db=LegacyItemDatabase.load(plugin());assertEquals("POLISHED_GRANITE",db.lookup("1:2"));assertEquals("GOLD_BLOCK",db.lookup("35:14"));assertEquals("EMERALD",db.lookup("9000"));assertEquals("DIAMOND",db.lookup("special"));assertEquals(old,Files.readString(dir.resolve("itemdb.yml.before-legacy-id-fix")));String upgraded=Files.readString(dir.resolve("itemdb.yml"));LegacyItemDatabase.load(plugin());assertEquals(upgraded,Files.readString(dir.resolve("itemdb.yml")));}
 @Test void essentialsSyncAddsNewItemsMeaningsAndCompletesPotions()throws Exception{var db=LegacyItemDatabase.load(plugin());
  assertEquals("BLACK_CUSHION",db.lookup("blackcushion"),"26.x items from Essentials");assertEquals("POPLAR_LOG",db.lookup("poplarlog"));
  assertEquals("NOTE_BLOCK",db.lookup("nblock"),"Essentials' meaning");assertEquals("TRIAL_SPAWNER",db.lookup("trialspawner"));
  assertEquals("POTION;LINGERING;180",db.lookup("lingeringpotion"),"kept: a lingering water potion");
  assertEquals("POTION potion:long_swiftness",db.lookup("potionofswiftnesslong"));
  assertEquals("LINGERING_POTION potion:swiftness",db.lookup("swiftness_lingering_potion"));
  assertEquals("TIPPED_ARROW potion:swiftness",db.lookup("swiftness_tipped_arrow"));
  assertEquals("POTION potion:strength",db.lookup("373:8201"));
  assertEquals("POTION potion:night_vision",db.lookup("373:38"));
  assertTrue(db.complete("potionofswiftn",List.of()).contains("potionofswiftnesslong"),"potion aliases complete");}
 @Test void renamedItemsResolveUnderEitherName(){
  // The test API is Spigot 1.20(.0): GRASS, SCUTE and CHAIN exist there; SHORT_GRASS, TURTLE_SCUTE and IRON_CHAIN do not.
  var db=new LegacyItemDatabase(Map.of("tallgrass","SHORT_GRASS","scutes","TURTLE_SCUTE","ichain","IRON_CHAIN","3:1","SHORT_GRASS"));
  assertEquals("GRASS",db.lookup("tallgrass"));assertEquals("SCUTE",db.lookup("scutes"));assertEquals("CHAIN",db.lookup("ichain"));assertEquals("GRASS",db.lookup("3:1"));
  assertEquals("CHAIN;x",LegacyItemDatabase.adapt("IRON_CHAIN;x"));assertEquals("GRASS",LegacyItemDatabase.adapt("GRASS"),"a name this server has stays");
  assertTrue(db.complete("tallg",List.of()).contains("tallgrass"),"a renamed item's alias completes");}
 @Test void anUneditedCopyOfOldAliasesUpgradesWithABackupAndAdminChoicesStay()throws Exception{
  String old="aliases:\n  nblock: NETHERITE_BLOCK\n  acid2pot: POTION;POISON;120\n  TrialSpawner: SPAWNER;TRIAL\n  dsword: GOLD_SWORD\n  mine: DIAMOND\n  lingeringpotion: POTION;LINGERING;180\n";
  Files.writeString(dir.resolve("itemdb.yml"),old);var db=LegacyItemDatabase.load(plugin());
  assertEquals("NOTE_BLOCK",db.lookup("nblock"),"an unedited old default takes the synced value");
  assertEquals("POTION potion:long_poison",db.lookup("acid2pot"));assertEquals("TRIAL_SPAWNER",db.lookup("trialspawner"),"keys keep any case");
  assertEquals("GOLD_SWORD",db.lookup("dsword"),"an admin's own value stays");assertEquals("DIAMOND",db.lookup("mine"));
  assertEquals("POTION;LINGERING;180",db.lookup("lingeringpotion"));
  assertEquals(old,Files.readString(dir.resolve("itemdb.yml.before-alias-sync")),"the original is saved once");
  String upgraded=Files.readString(dir.resolve("itemdb.yml"));assertTrue(upgraded.contains("nblock: NOTE_BLOCK"));
  LegacyItemDatabase.load(plugin());assertEquals(upgraded,Files.readString(dir.resolve("itemdb.yml")),"a second start changes nothing");}
 @Test void canonicalMaterialsWinAndCompletionIsBounded(){var db=new LegacyItemDatabase(Map.of("stone","DIAMOND","dsword","DIAMOND_SWORD","bad","NO_SUCH_MATERIAL"));assertNull(db.lookup("stone"));assertEquals("DIAMOND_SWORD",db.lookup("dsword"));assertTrue(db.complete("diamond_s",List.of()).contains("diamond_sword"));assertFalse(db.complete("bad",List.of()).contains("bad"));assertTrue(db.complete("",List.of()).size()<=64);}
}
