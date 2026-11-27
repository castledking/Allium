package codes.castled.spawnercraft;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AddonPowerTools;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpawnerChangerTest {

    private static PersistentDataContainer pdc(Map<NamespacedKey, Object> values) {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.getKeys()).thenReturn(Set.copyOf(values.keySet()));
        when(data.get(any(NamespacedKey.class), any())).thenAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            PersistentDataType<?, ?> type = invocation.getArgument(1);
            Object val = values.get(key);
            if (val == null) return null;
            return type.getComplexType().isInstance(val) ? val : null;
        });
        return data;
    }

    @Test
    void naturalVanillaSpawnerIsAllowed() {
        assertFalse(SpawnerChanger.hasBlockedPersistentData(pdc(Map.of())));
    }

    @Test
    void unownedNaturalSpawnerMetaInitTagsStillAllowChanging() {
        Map<NamespacedKey, Object> naturalInit = new HashMap<>();
        naturalInit.put(new NamespacedKey("spawnermeta", "new"), 1);
        naturalInit.put(new NamespacedKey("spawnermeta", "stack"), 1);
        naturalInit.put(new NamespacedKey("spawnermeta", "levels"), new int[]{1, 1, 1, 1, 1, 1});
        naturalInit.put(new NamespacedKey("spawnermeta", "charges"), 0);
        naturalInit.put(new NamespacedKey("spawnermeta", "enabled"), 1);
        naturalInit.put(new NamespacedKey("spawnermeta", "type"), "ZOMBIE");
        assertFalse(SpawnerChanger.hasBlockedPersistentData(pdc(naturalInit)));
    }

    @Test
    void spawnerCraftCraftedSpawnerIsBlocked() {
        var data = pdc(Map.of(HeadRecipes.CRAFTED_SPAWNER_KEY, (byte) 1));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(data));
    }

    @Test
    void spawnerMetaOwnedUpgradedStackedChargedOrEmptySpawnersAreBlocked() {
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("spawnermeta", "owner"), UUID.randomUUID().toString()))));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("spawnermeta", "levels"), new int[]{1, 2, 1, 1, 1, 1}))));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("spawnermeta", "stack"), 2))));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("spawnermeta", "charges"), 5))));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("spawnermeta", "empty"), 1))));
    }

    @Test
    void upgradeableSpawnersPluginTaggedSpawnerIsBlocked() {
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("upgradeablespawners", "spawner"), "1"))));
        assertTrue(SpawnerChanger.hasBlockedPersistentData(
                pdc(Map.of(new NamespacedKey("upgradablespawners", "spawner"), "1"))));
    }

    @Test
    void activateRefusesUpgradableOrCraftedSpawnerWithoutOpeningMenu() {
        SpawnerCraft plugin = mock(SpawnerCraft.class);
        AddonConnection connection = mock(AddonConnection.class);
        AddonPowerTools powerTools = mock(AddonPowerTools.class);
        when(plugin.connection()).thenReturn(connection);
        when(connection.powerTools()).thenReturn(powerTools);

        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

        Block block = mock(Block.class);
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        PersistentDataContainer ownedPdc = pdc(Map.of(new NamespacedKey("spawnermeta", "owner"), UUID.randomUUID().toString()));
        when(block.getState()).thenReturn(spawner);
        when(spawner.getPersistentDataContainer()).thenReturn(ownedPdc);

        ItemStack tool = new ItemStack(Material.BLAZE_ROD);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, tool, block,
                org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        when(powerTools.mayModify(player, block, "change_spawner", event)).thenReturn(true);

        AddonPowerTools.Context ctx = new AddonPowerTools.Context(
                player, "allium:spawner_changer", tool, "main", "RIGHT", block, null, event);

        SpawnerChanger changer = new SpawnerChanger(plugin);
        assertFalse(changer.activate(ctx, "", Map.of()));
        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        verify(player).sendMessage(SpawnerChanger.BLOCKED_MESSAGE);
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
}
