package codes.castled.allium.item.provider.craftengine;

import codes.castled.allium.item.AlliumItemDefinition;
import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionStatus;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adapter/ItemService boundary tests. Native inventory updater retention needs the separate live gate. */
class CraftEngineStatePreservationTest {
    private static final NamespacedKey NATIVE_ID = new NamespacedKey("craftengine", "test_native_id");

    @Test
    void heldItemRemainsIntactDuringReloadAndSharedRefreshPreservesSignedState() {
        var api = mock(CraftEngineAccess.class);
        var ready = new CraftEngineAccess.Lifecycle(false, false, false, true);
        when(api.lifecycle()).thenReturn(ready);
        when(api.nativeId(any())).thenAnswer(invocation -> ((ItemStack) invocation.getArgument(0))
                .getItemMeta().getPersistentDataContainer().get(NATIVE_ID, PersistentDataType.STRING));
        var material = new Material[]{Material.IRON_INGOT};
        when(api.build(anyString())).thenAnswer(invocation -> {
            ItemStack stack = stack(material[0], 1, new HashMap<>());
            stack.getItemMeta().getPersistentDataContainer().set(NATIVE_ID, PersistentDataType.STRING, invocation.getArgument(0));
            return stack;
        });
        var ticks = new ArrayDeque<Runnable>();
        var diagnostics = new ArrayList<String>();
        var service = new ItemService(type -> stack(type, 1, new HashMap<>()), ItemHmacSigner.randomEphemeral(), diagnostics::add);
        var adapter = new CraftEngineProviderAdapter(mock(Plugin.class), "26.9.1", ignored -> {}, diagnostics::add,
                service::resetDiagnosticsOnReload, api, ticks::addLast);
        var plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        adapter.initialize(plugins);
        service.registerProvider(adapter);
        service.registerAlliumItem(new AlliumItemDefinition("probe", Material.PAPER, "allium:probe", true,
                null, null, Map.of("charges", "3")));
        ProviderKey appearance = ProviderKey.parse("craftengine:sf_ores:silver_ingot");
        service.bindAppearanceToBehavior(appearance, "allium:probe");
        ItemStack held = service.create(ItemRef.of(appearance).withAmount(4).withState("charges", "2"))
                .itemStack().orElseThrow();
        var heldPdc = held.getItemMeta().getPersistentDataContainer();
        String uuid = heldPdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING);
        String signature = heldPdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING);
        assertNotNull(uuid);
        assertNotNull(signature);
        assertEquals(Optional.of("allium:probe"), service.identifyBehavior(held));

        when(api.lifecycle()).thenReturn(new CraftEngineAccess.Lifecycle(false, true, false, true));
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(appearance.id()).status());
        assertEquals(signature, heldPdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        assertEquals("2", heldPdc.get(new NamespacedKey("allium", "state_charges"), PersistentDataType.STRING));
        adapter.onCraftEngineReload(null);
        when(api.lifecycle()).thenReturn(ready);
        material[0] = Material.GOLD_INGOT;
        ticks.removeFirst().run();
        ItemStack refreshed = service.swapOrRefreshAppearance(held, appearance).itemStack().orElseThrow();
        assertEquals(Material.GOLD_INGOT, refreshed.getType());
        assertEquals(4, refreshed.getAmount());
        assertEquals(appearance, service.identify(refreshed).orElseThrow().appearance());
        assertEquals(Optional.of("allium:probe"), service.identifyBehavior(refreshed));
        assertEquals(Optional.of("2"), service.getState(refreshed, "charges"));
        var refreshedPdc = refreshed.getItemMeta().getPersistentDataContainer();
        assertEquals(uuid, refreshedPdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        assertEquals(signature, refreshedPdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        assertEquals("sf_ores:silver_ingot", refreshedPdc.get(ItemService.KEY_ITEM_ID, PersistentDataType.STRING));

        ProviderKey nextAppearance = ProviderKey.parse("craftengine:other_pack:silver_ingot");
        ItemStack swapped = service.swapOrRefreshAppearance(refreshed, nextAppearance).itemStack().orElseThrow();
        assertEquals(nextAppearance, service.identify(swapped).orElseThrow().appearance());
        assertEquals(Optional.of("allium:probe"), service.identifyBehavior(swapped));
        assertEquals(Optional.of("2"), service.getState(swapped, "charges"));
        assertEquals(uuid, swapped.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        assertNotEquals(signature, swapped.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        assertEquals("sf_ores:silver_ingot", heldPdc.get(ItemService.KEY_ITEM_ID, PersistentDataType.STRING));
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @SuppressWarnings("unchecked")
    private static ItemStack stack(Material material, int initialAmount, Map<NamespacedKey, Object> data) {
        var amount = new AtomicInteger(initialAmount);
        var pdc = mock(PersistentDataContainer.class);
        doAnswer(invocation -> {
            data.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), any(), any());
        when(pdc.get(any(NamespacedKey.class), any())).thenAnswer(invocation -> data.get(invocation.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), any())).thenAnswer(invocation -> data.containsKey(invocation.getArgument(0)));
        when(pdc.getKeys()).thenAnswer(invocation -> data.keySet());
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(invocation -> amount.get());
        doAnswer(invocation -> {
            amount.set(invocation.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.setItemMeta(any())).thenReturn(true);
        when(stack.clone()).thenAnswer(invocation -> stack(material, amount.get(), new HashMap<>(data)));
        return stack;
    }
}
