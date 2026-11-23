package codes.castled.crops;

import codes.castled.allium.api.AddonConnection;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KitchenDroppedPieCoolingTest {

    private CropsPlugin plugin;
    private AddonConnection connection;
    private YamlConfiguration kitchenCfg;
    private AtomicLong now;
    private Map<UUID, Item> loadedEntities;

    @BeforeEach
    void setUp() throws Exception {
        now = new AtomicLong(10_000L);
        loadedEntities = new HashMap<>();

        plugin = mock(CropsPlugin.class);
        when(plugin.getName()).thenReturn("Crops");
        connection = mock(AddonConnection.class);
        when(connection.isFolia()).thenReturn(false);
        when(plugin.connection()).thenReturn(connection);

        when(plugin.item(anyString(), anyInt())).thenAnswer(inv -> mockPieStack(inv.getArgument(0), inv.getArgument(1)));
        when(plugin.reference(any())).thenAnswer(inv -> {
            ItemStack s = inv.getArgument(0);
            return s == null ? "" : s.toString();
        });

        kitchenCfg = new YamlConfiguration();
        kitchenCfg.loadFromString("""
                enabled: true
                pies:
                  enabled: true
                  cooling:
                    time: 10m
                    dropped-items: true
                  crust:
                    item: allium:pie_crust
                    model: allium:pie_crust_placed
                  models:
                    item: allium:{state}_{type}_pie
                    placed: allium:{state}_{type}_pie_placed{slice}
                    filled: allium:{type}_pie_filled
                  types:
                    apple:
                      filling: minecraft:apple
                """);
    }

    private ItemStack mockPieStack(String ref, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.PAPER);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.toString()).thenReturn(ref);
        return stack;
    }

    private Item mockDroppedPie(UUID id, String initialRef, int amount, Map<NamespacedKey, Object> pdcMap,
                                AtomicBoolean valid, AtomicReference<ItemStack> stackRef) {
        stackRef.set(mockPieStack(initialRef, amount));
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.get(inv.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(inv -> pdcMap.containsKey(inv.getArgument(0)));
        doAnswer(inv -> {
            pdcMap.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        doAnswer(inv -> {
            pdcMap.remove(inv.getArgument(0));
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));

        Item item = mock(Item.class);
        when(item.getUniqueId()).thenReturn(id);
        when(item.isValid()).thenAnswer(inv -> valid.get());
        when(item.getPersistentDataContainer()).thenReturn(pdc);
        when(item.getItemStack()).thenAnswer(inv -> stackRef.get());
        doAnswer(inv -> {
            stackRef.set(inv.getArgument(0));
            return null;
        }).when(item).setItemStack(any(ItemStack.class));
        return item;
    }

    @Test
    void droppedPieCoolingPersistsAcrossChunkUnloadReloadAndCompletesAfterRemainingTime() {
        Kitchen kitchen = new Kitchen(plugin, mock(ModelWorld.class), kitchenCfg, now::get, loadedEntities::get);
        UUID dropId = UUID.randomUUID();
        Map<NamespacedKey, Object> pdcMap = new HashMap<>();
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<ItemStack> stackRef = new AtomicReference<>();
        Item drop = mockDroppedPie(dropId, "allium:baked_apple_pie", 2, pdcMap, valid, stackRef);
        loadedEntities.put(dropId, drop);

        // 1. Spawn baked pie at t=10_000 -> due at 10_000 + 600_000 = 610_000
        kitchen.dropped(new ItemSpawnEvent(drop));
        NamespacedKey dueKey = new NamespacedKey(plugin, "pie_cooling_due");
        assertEquals(610_000L, pdcMap.get(dueKey));
        assertEquals(1, kitchen.trackedCoolingDropCount());

        // 2. Chunk unloads at t=200_000 -> removed from RAM map, deadline stays in entity PDC
        now.set(200_000L);
        valid.set(false);
        loadedEntities.remove(dropId);
        kitchen.onEntitiesUnload(new EntitiesUnloadEvent(mock(Chunk.class), List.of(drop)));
        assertEquals(0, kitchen.trackedCoolingDropCount());
        assertEquals(610_000L, pdcMap.get(dueKey));

        // 3. Chunk reloads at t=400_000 (not yet expired) -> re-tracked with original deadline
        now.set(400_000L);
        valid.set(true);
        loadedEntities.put(dropId, drop);
        kitchen.onEntitiesLoad(new EntitiesLoadEvent(mock(Chunk.class), List.of(drop)));
        assertEquals(1, kitchen.trackedCoolingDropCount());
        assertEquals("allium:baked_apple_pie", stackRef.get().toString());

        // 4. Advance past 610_000 -> coolDrops cools it to cold_apple_pie and clears deadline key
        now.set(610_001L);
        kitchen.coolDrops();
        assertEquals("allium:cold_apple_pie", stackRef.get().toString());
        assertEquals(2, stackRef.get().getAmount());
        assertFalse(pdcMap.containsKey(dueKey));
        assertEquals(0, kitchen.trackedCoolingDropCount());
    }

    @Test
    void droppedPieExpiredWhileUnloadedOrAcrossServerRestartCoolsImmediatelyOnLoadAndPickup() {
        Kitchen beforeRestart = new Kitchen(plugin, mock(ModelWorld.class), kitchenCfg, now::get, loadedEntities::get);
        UUID dropId = UUID.randomUUID();
        Map<NamespacedKey, Object> pdcMap = new HashMap<>();
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<ItemStack> stackRef = new AtomicReference<>();
        Item drop = mockDroppedPie(dropId, "allium:baked_apple_pie", 1, pdcMap, valid, stackRef);

        beforeRestart.dropped(new ItemSpawnEvent(drop));
        NamespacedKey dueKey = new NamespacedKey(plugin, "pie_cooling_due");
        assertEquals(610_000L, pdcMap.get(dueKey));

        // Simulate server restart + 15 minutes elapsed while chunk/server was down
        now.set(1_000_000L);
        Kitchen afterRestart = new Kitchen(plugin, mock(ModelWorld.class), kitchenCfg, now::get, loadedEntities::get);
        afterRestart.onEntitiesLoad(new EntitiesLoadEvent(mock(Chunk.class), List.of(drop)));
        assertEquals("allium:cold_apple_pie", stackRef.get().toString());
        assertFalse(pdcMap.containsKey(dueKey));
        assertEquals(0, afterRestart.trackedCoolingDropCount());

        // Also test pickup-triggered immediate cooling if entity was already loaded and expired before tick ran
        UUID pickupDropId = UUID.randomUUID();
        Map<NamespacedKey, Object> pickupPdc = new HashMap<>();
        AtomicReference<ItemStack> pickupStack = new AtomicReference<>();
        Item pickupDrop = mockDroppedPie(pickupDropId, "allium:baked_apple_pie", 1, pickupPdc, new AtomicBoolean(true), pickupStack);
        pickupPdc.put(dueKey, 500_000L); // already expired at now=1_000_000
        afterRestart.onPickupCoolingDrop(new EntityPickupItemEvent(mock(Player.class), pickupDrop, 0));
        assertEquals("allium:cold_apple_pie", pickupStack.get().toString());
        assertFalse(pickupPdc.containsKey(dueKey));
    }
}
