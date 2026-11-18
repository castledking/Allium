package codes.castled.allium.item.library;
import org.bukkit.Material;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
class PotionAdapterTest {
 @Test void legacyBasePotionsRetainExtendedAndUpgradedFlags(){
  var meta=mock(PotionMeta.class);
  PotionAdapter.basePotion(meta,"long_swiftness");verify(meta).setBasePotionData(new PotionData(PotionType.SPEED,true,false));
  PotionAdapter.basePotion(meta,"strong_regeneration");verify(meta).setBasePotionData(new PotionData(PotionType.REGEN,false,true));
 }
 @Test void legacyEffectsMatchingAVanillaPotionAreThatPotion(){
  // "/i potion;poison" was a Water Bottle carrying poison; it is now a Potion of Poison.
  var poison=mock(PotionMeta.class);PotionAdapter.legacyEffect(poison,"poison:0:1",Material.POTION);
  verify(poison).setBasePotionData(new PotionData(PotionType.POISON,false,false));verify(poison,never()).addCustomEffect(any(),anyBoolean());
  var swift=mock(PotionMeta.class);PotionAdapter.legacyEffect(swift,"speed:0:2",Material.SPLASH_POTION);
  verify(swift).setBasePotionData(new PotionData(PotionType.SPEED,false,true));
  var longLeap=mock(PotionMeta.class);PotionAdapter.legacyEffect(longLeap,"jump:480:1",Material.POTION);
  verify(longLeap).setBasePotionData(new PotionData(PotionType.JUMP,true,false));
  var arrow=mock(PotionMeta.class);PotionAdapter.legacyEffect(arrow,"instant_heal:0:2",Material.TIPPED_ARROW);
  verify(arrow).setBasePotionData(new PotionData(PotionType.INSTANT_HEAL,false,true));
  var turtle=mock(PotionMeta.class);PotionAdapter.legacyEffect(turtle,"turtle_master:0:1",Material.POTION);
  verify(turtle).setBasePotionData(new PotionData(PotionType.TURTLE_MASTER,false,false));
 }
 @Test void variantsFollowVanillaDurationsPerCarrier(){
  assertEquals("poison",VanillaPotions.variant("poison",0,1,1));
  assertEquals("long_poison",VanillaPotions.variant("poison",90,1,1));
  assertEquals("strong_poison",VanillaPotions.variant("poison",0,2,1));
  assertNull(VanillaPotions.variant("poison",60,1,1),"a custom duration stays custom");
  assertNull(VanillaPotions.variant("poison",0,3,1),"no Poison III potion exists");
  assertEquals("strong_slowness",VanillaPotions.variant("slowness",20,4,1));
  assertNull(VanillaPotions.variant("weakness",0,2,1));
  assertEquals("long_swiftness",VanillaPotions.variant("swiftness",120,1,4),"a lingering cloud applies a quarter");
  assertEquals("swiftness",VanillaPotions.variant("swiftness",0,1,8));
  assertEquals("harming",VanillaPotions.variant("harming",7,1,1),"instant effects ignore durations");
  assertEquals("swiftness",VanillaPotions.key("speed"));assertEquals("leaping",VanillaPotions.key("jump_boost"));
  assertEquals("healing",VanillaPotions.key("minecraft:instant_health"));assertNull(VanillaPotions.key("haste"));
 }
 /** Stands in for CraftBukkit 1.20.1's CraftMetaItem, which keeps the display name as component JSON. */
 static abstract class LegacyCraftMeta implements PotionMeta { private String displayName; @Override public abstract PotionMeta clone(); }
 @Test void customPotionsAreNamedAfterTheirVanillaPotion(){
  var meta=mock(LegacyCraftMeta.class,withSettings().defaultAnswer(RETURNS_DEFAULTS));
  assertTrue(PotionAdapter.name(meta,"poison",Material.SPLASH_POTION));
  assertEquals("{\"translate\":\"item.minecraft.splash_potion.effect.poison\",\"italic\":false}",readJson(meta));
  var arrow=mock(LegacyCraftMeta.class);assertTrue(PotionAdapter.name(arrow,"swiftness",Material.TIPPED_ARROW));
  assertTrue(readJson(arrow).contains("item.minecraft.tipped_arrow.effect.swiftness"));
  var named=mock(LegacyCraftMeta.class);when(named.hasDisplayName()).thenReturn(true);
  assertFalse(PotionAdapter.name(named,"poison",Material.POTION),"an item with a name keeps it");
 }
 private static String readJson(Object meta){
  try{var f=LegacyCraftMeta.class.getDeclaredField("displayName");f.setAccessible(true);return (String)f.get(meta);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
 }
}
