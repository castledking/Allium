package codes.castled.allium.creative;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreativeProtectionsTest {

    @Test
    void blacklistedCommandsMatchTheCommandOrItWithMoreArguments() {
        List<String> blocked = List.of(CreativeManager.commandLine("give"), CreativeManager.commandLine("/Gamemode  Survival"));
        assertTrue(CreativeManager.commandBlocked(blocked, "/give Bob diamond"));
        assertTrue(CreativeManager.commandBlocked(blocked, "/minecraft:give Bob"));
        assertTrue(CreativeManager.commandBlocked(blocked, "/gamemode survival Bob"));
        assertFalse(CreativeManager.commandBlocked(blocked, "/gamemode creative"));
        assertFalse(CreativeManager.commandBlocked(blocked, "/giveaway"));
    }

    @Test
    void settingsReadItemsCommandsAndTnt() {
        var config = new YamlConfiguration();
        config.set("blacklist.items", List.of("flint_and_steel", "lava_bucket", "nonsense"));
        config.set("blacklist.commands", List.of("/Give"));
        var settings = CreativeManager.Settings.read(config, Logger.getAnonymousLogger());
        assertEquals(java.util.Set.of(Material.FLINT_AND_STEEL, Material.LAVA_BUCKET), settings.blockedItems());
        assertEquals(List.of("give"), settings.blockedCommands());
        assertTrue(settings.protectTnt());
        assertTrue(CreativeManager.Settings.read(null, Logger.getAnonymousLogger()).protectTnt());
    }

    @Test
    void aContainerItemIsFilledOnlyWhenItsInventoryHoldsSomething() {
        Inventory inventory = mock(Inventory.class);
        Container chest = mock(Container.class);
        when(chest.getInventory()).thenReturn(inventory);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        when(meta.hasBlockState()).thenReturn(true);
        when(meta.getBlockState()).thenReturn(chest);
        ItemStack item = mock(ItemStack.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(inventory.isEmpty()).thenReturn(true);
        assertFalse(CreativeManager.filledContainer(item));
        when(inventory.isEmpty()).thenReturn(false);
        assertTrue(CreativeManager.filledContainer(item));
        assertFalse(CreativeManager.filledContainer(null));
    }

    @Test
    void tntIsLitByThePlayerOrTheShooterOfABurningArrow() {
        Player player = mock(Player.class);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);
        assertSame(player, CreativeManager.primingPlayer(player));
        assertSame(player, CreativeManager.primingPlayer(arrow));
        assertNull(CreativeManager.primingPlayer(null));
    }
}
