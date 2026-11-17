package codes.castled.allium.item.library;
import org.junit.jupiter.api.Test;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EnchantPolicyTest {
 @Test void rawEnchantmentsAreAlsoClampedByThePolicy(){var stack=sword();var meta=mock(ItemMeta.class);var e=enchant("minecraft:sharpness",true);when(stack.getItemMeta()).thenReturn(meta);when(meta.getEnchants()).thenReturn(Map.of(e,99));assertEquals(List.of("@enchant|unsafe-level|minecraft:sharpness|diamond_sword||5"),EnchantmentAdapter.validateRaw(stack,false));verify(meta).addEnchant(e,5,false);verify(stack).setItemMeta(meta);}
 @Test void rawIncompatibleEnchantsCannotBypassValidation(){var stack=sword();var meta=mock(ItemMeta.class);var e=enchant("plugin:helmet_only",false);when(stack.getItemMeta()).thenReturn(meta);when(meta.getEnchants()).thenReturn(Map.of(e,1));assertThrows(ItemBuildException.class,()->EnchantmentAdapter.validateRaw(stack,false));verify(stack,never()).setItemMeta(meta);}

 @Test void rawEnchantmentsUseSamePolicy(){var e=enchant("plugin:keen",true);var meta=mock(ItemMeta.class);when(meta.getEnchants()).thenReturn(Map.of(e,99));var item=sword();when(item.getItemMeta()).thenReturn(meta);assertEquals(List.of("@enchant|unsafe-level|plugin:keen|diamond_sword||5"),EnchantmentAdapter.validateRaw(item,false));verify(meta).addEnchant(e,5,false);}
 @Test void rawConflictsCannotBypassTheSwitch(){var a=enchant("minecraft:sharpness",true);var b=enchant("minecraft:smite",true);when(a.conflictsWith(b)).thenReturn(true);var meta=mock(ItemMeta.class);when(meta.getEnchants()).thenReturn(Map.of(a,1,b,1));var item=sword();when(item.getItemMeta()).thenReturn(meta);assertThrows(ItemBuildException.class,()->EnchantmentAdapter.validateRaw(item,false));verify(item,never()).setItemMeta(meta);}

 Enchantment enchant(String id,boolean compatible){var e=mock(Enchantment.class);when(e.getKey()).thenReturn(NamespacedKey.fromString(id));when(e.getMaxLevel()).thenReturn(5);when(e.canEnchantItem(org.mockito.ArgumentMatchers.any())).thenReturn(compatible);return e;}
 ItemStack sword(){var s=mock(ItemStack.class);when(s.getType()).thenReturn(Material.DIAMOND_SWORD);return s;}
 @Test void registeredPluginNamespacesAreKeptAndOnlyCompatibleEnchantmentsComplete(){var a=enchant("minecraft:sharpness",true);var b=enchant("plugin:keen",true);var c=enchant("plugin:helmet_only",false);assertEquals(List.of("plugin:keen","sharpness"),EnchantCompletions.names(List.of(a,b,c),sword(),"",List.of()));assertEquals(List.of("plugin:keen"),EnchantCompletions.names(List.of(a,b,c),sword(),"plugin:",List.of()));}
 @Test void safePolicyClampsLevelsWithLegacyNotice(){var e=enchant("plugin:keen",true);var meta=mock(ItemMeta.class);assertEquals("@enchant|unsafe-level|plugin:keen|diamond_sword||5",EnchantmentAdapter.apply(sword(),meta,e,"10",false));verify(meta).addEnchant(e,5,false);}
 @Test void conflictingAndIncompatibleEnchantsAreRefusedBeforeMutation(){var a=enchant("minecraft:sharpness",true);var b=enchant("plugin:clash",true);when(b.conflictsWith(a)).thenReturn(true);var meta=mock(ItemMeta.class);when(meta.getEnchants()).thenReturn(Map.of(a,1));assertTrue(assertThrows(ItemBuildException.class,()->EnchantmentAdapter.apply(sword(),meta,b,"1",false)).getMessage().contains("conflicting-enchants"));verify(meta,never()).addEnchant(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyBoolean());var c=enchant("plugin:helmet",false);assertTrue(assertThrows(ItemBuildException.class,()->EnchantmentAdapter.apply(sword(),meta,c,"1",false)).getMessage().contains("unsafe-enchant"));}
 @Test void unsafeModePermitsConflictsAndAboveMax(){var e=enchant("plugin:helmet",false);var meta=mock(ItemMeta.class);EnchantmentAdapter.apply(sword(),meta,e,"20",true);verify(meta).addEnchant(e,20,true);}
}
