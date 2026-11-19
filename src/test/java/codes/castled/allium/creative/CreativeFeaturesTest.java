package codes.castled.allium.creative;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreativeFeaturesTest {

    @Test
    void marksAreUniqueSortedPositionsWithinAChunk() {
        int a = CreativePlacedBlocks.pack(3, -61, 15), b = CreativePlacedBlocks.pack(19, -61, 31), c = CreativePlacedBlocks.pack(3, 64, 15);
        assertEquals(a, b, "x and z are taken within the chunk");
        assertTrue(a != c && a != CreativePlacedBlocks.pack(4, -61, 15) && a != CreativePlacedBlocks.pack(3, -60, 15));
        int[] marks = CreativePlacedBlocks.with(CreativePlacedBlocks.with(new int[0], c), a);
        assertArrayEquals(new int[]{Math.min(a, c), Math.max(a, c)}, marks);
        assertSame(marks, CreativePlacedBlocks.with(marks, a), "adding twice changes nothing");
        assertArrayEquals(new int[]{c}, CreativePlacedBlocks.without(marks, a));
        assertSame(marks, CreativePlacedBlocks.without(marks, CreativePlacedBlocks.pack(0, 0, 0)));
    }

    @Test
    void doorsAndBedsAreMarkedWithTheirOtherHalf() {
        Block lower = mock(Block.class), upper = mock(Block.class);
        Bisected door = mock(Bisected.class);
        when(door.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        when(lower.getBlockData()).thenReturn(door);
        when(lower.getRelative(BlockFace.UP)).thenReturn(upper);
        assertEquals(List.of(lower, upper), CreativePlacedBlocks.structure(lower));

        Block foot = mock(Block.class), head = mock(Block.class);
        Bed bed = mock(Bed.class);
        when(bed.getPart()).thenReturn(Bed.Part.FOOT);
        when(bed.getFacing()).thenReturn(BlockFace.NORTH);
        when(foot.getBlockData()).thenReturn(bed);
        when(foot.getRelative(BlockFace.NORTH)).thenReturn(head);
        assertEquals(List.of(foot, head), CreativePlacedBlocks.structure(foot));
    }

    @Test
    void builtMobsAreCheckedOverTheirWholeShape() {
        assertEquals(3, CreativePlacedBlocks.buildShape(CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN).size());
        assertEquals(7, CreativePlacedBlocks.buildShape(CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM).size());
        assertEquals(11, CreativePlacedBlocks.buildShape(CreatureSpawnEvent.SpawnReason.BUILD_WITHER).size());
        assertTrue(CreativePlacedBlocks.buildShape(CreatureSpawnEvent.SpawnReason.NATURAL).isEmpty());
    }

    @Test
    void placedBlockSettingsDefaultToNoDropsAndMobBuildsRefused() {
        var defaults = CreativePlacedBlocks.Settings.read(null);
        assertTrue(defaults.enabled() && !defaults.deny() && defaults.preventMobBuilds());
        var config = new YamlConfiguration();
        config.set("survival-break", "deny");
        config.set("prevent-mob-builds", false);
        var read = CreativePlacedBlocks.Settings.read(config);
        assertTrue(read.deny() && !read.preventMobBuilds());
    }

    @Test
    void itemSettingsReadLoreArmourAndSwitches() {
        var config = new YamlConfiguration();
        config.set("item-lore.enabled", true);
        config.set("menu-items.refuse-potions", true);
        config.set("armor.enabled", true);
        config.set("armor.helmet.material", "golden_helmet");
        config.set("armor.boots.color", "#FF0000");
        List<String> warnings = new ArrayList<>();
        config.set("armor.leggings.material", "nonsense");
        var settings = CreativeItems.Settings.read(config, warnings::add);
        assertTrue(settings.loreEnabled() && settings.refusePotions() && settings.armorEnabled() && settings.sanitize(),
                "unset switches are on by default");
        assertEquals(CreativeItems.Settings.ALLIUM_PINK, settings.armor().get(0).color());
        assertEquals(List.of("&5Creative {item}", "&7Taken by {player}"), settings.loreLines());
        assertEquals(Material.GOLDEN_HELMET, settings.armor().get(0).material());
        assertEquals(Material.LEATHER_LEGGINGS, settings.armor().get(2).material(), "an unknown material keeps the default");
        assertEquals(Color.fromRGB(255, 0, 0), settings.armor().get(3).color());
        assertEquals(1, warnings.size());
        var defaults = CreativeItems.Settings.read(null, warnings::add);
        assertTrue(defaults.loreEnabled() && defaults.armorEnabled() && defaults.clearEffects() && defaults.refuseEnchanted());
        config.set("item-lore.enabled", false);
        config.set("armor.enabled", false);
        var off = CreativeItems.Settings.read(config, warnings::add);
        assertFalse(off.loreEnabled() || off.armorEnabled());
    }

    @Test
    void enchantedItemsAndBooksAreTold() {
        ItemStack sword = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(sword.getItemMeta()).thenReturn(meta);
        assertFalse(CreativeItems.enchanted(sword));
        when(meta.hasEnchants()).thenReturn(true);
        assertTrue(CreativeItems.enchanted(sword));
        ItemStack book = mock(ItemStack.class);
        EnchantmentStorageMeta stored = mock(EnchantmentStorageMeta.class);
        when(book.getItemMeta()).thenReturn(stored);
        when(stored.hasStoredEnchants()).thenReturn(true);
        assertTrue(CreativeItems.enchanted(book));
    }

    @Test
    void potionsWithCustomEffectsAreTold() {
        ItemStack potion = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(potion.getItemMeta()).thenReturn(meta);
        when(meta.hasCustomEffects()).thenReturn(true);
        assertTrue(CreativeItems.hasEffects(potion));
        ItemStack stone = mock(ItemStack.class);
        when(stone.getItemMeta()).thenReturn(mock(ItemMeta.class));
        assertFalse(CreativeItems.hasEffects(stone));
    }
}
