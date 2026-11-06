package codes.castled.allium.item.provider.oraxen;

import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionStatus;
import io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Focused fixture at the native API boundary; lifecycle uses the actual pinned event. */
class OraxenProviderAdapterTest {
    private final NativeFixture api = new NativeFixture();
    private final Plugin host = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<String> warnings = new ArrayList<>();
    private final AtomicInteger invalidations = new AtomicInteger();
    private OraxenProviderAdapter adapter;

    @BeforeEach
    void initialize() {
        when(manager.isPluginEnabled("Oraxen")).thenReturn(true);
        adapter = new OraxenProviderAdapter(host, "1.211.0", ignored -> {}, warnings::add,
                invalidations::incrementAndGet, api);
        adapter.initialize(manager);
    }

    @Test
    void uninitializedRegistryLoadsLaterAndEmptyCompletedRegistryIsReady() {
        verify(manager).registerEvents(adapter, host);
        assertEquals(ProviderStatus.LOADING, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("Missing").status());
        api.initialized = true;
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        assertEquals(ProviderStatus.READY, adapter.status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("Missing").status());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void alreadyLoadedEmptyRegistryDoesNotRequireAnotherEvent() {
        api.initialized = true;
        assertEquals(ProviderStatus.READY, adapter.status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("Missing").status());
    }

    @Test
    void postEventWithNoInitializedRegistryReportsFailureInsteadOfLoadingForever() {
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        assertEquals(ProviderStatus.FAILED, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem("Missing").status());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("without an initialized registry"));
    }

    @Test
    void synchronousReloadUsesRealPostEventAndNewBuildsPreserveCase() throws Exception {
        assertNotNull(OraxenProviderAdapter.class.getMethod("onOraxenItemsLoaded", OraxenItemsLoadedEvent.class)
                .getAnnotation(EventHandler.class));
        api.initialized = true;
        api.items.put("Staff", stack(Material.PAPER, 3, "Staff", "old"));
        api.items.put("staff", stack(Material.STICK, 1, "staff", "lower"));
        ItemStack old = adapter.createItem("Staff").itemStack().orElseThrow();
        assertEquals(Material.STICK, adapter.createItem("staff").itemStack().orElseThrow().getType());
        assertEquals(3, old.getAmount());
        // Pinned reloadItems completes loadItems synchronously and only then fires this event.
        api.items = new HashMap<>(Map.of("Staff", stack(Material.DIAMOND, 2, "Staff", "new")));
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        ItemStack updated = adapter.createItem("Staff").itemStack().orElseThrow();
        assertEquals(Material.DIAMOND, updated.getType());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("staff").status());
        assertEquals("old", data(old, "oraxen", "extra"));
        assertEquals("new", data(updated, "oraxen", "extra"));
        assertEquals(1, invalidations.get());
        assertEquals(List.of("Staff", "staff", "Staff", "staff"), api.buildCalls);
    }

    @Test
    void directNativeRegistryReplacementWithoutPostEventCannotServeStaleTemplate() {
        api.initialized = true;
        api.items.put("Native", stack(Material.PAPER, 1, "Native", "old"));
        ItemStack first = adapter.createItem("Native").itemStack().orElseThrow();
        api.items = new HashMap<>(Map.of("Native", stack(Material.DIAMOND, 1, "Native", "new")));
        ItemStack second = adapter.createItem("Native").itemStack().orElseThrow();
        assertEquals(Material.PAPER, first.getType());
        assertEquals(Material.DIAMOND, second.getType());
        assertNotSame(api.items.get("Native"), second);
        assertEquals(2, api.buildCalls.size());
    }

    @Test
    void nativeApiIdentityAndLifecycleLeaveInventoryStateAndSignaturesUntouched() {
        api.initialized = true;
        ItemStack inventory = stack(Material.PAPER, 5, "MixedCase", "metadata");
        api.items.put("MixedCase", inventory);
        ItemStack created = adapter.createItem("MixedCase").itemStack().orElseThrow();
        assertEquals(Optional.of("MixedCase"), adapter.identifyItemId(created));
        assertTrue(adapter.hasAuthoritativeNativeIdCheck());
        assertEquals("Native MixedCase", created.getItemMeta().getDisplayName());
        assertEquals(8123, created.getItemMeta().getCustomModelData());
        assertEquals("metadata", data(created, "oraxen", "extra"));
        assertEquals("signed-original", data(created, "allium", "signature"));
        assertEquals("2", data(created, "allium", "state_charges"));
        assertEquals("uuid-original", data(created, "allium", "instance_uuid"));
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        Plugin provider = mock(Plugin.class);
        when(provider.getName()).thenReturn("Oraxen");
        adapter.onPluginDisable(new PluginDisableEvent(provider));
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        assertEquals(Optional.empty(), adapter.identifyItemId(inventory));
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, adapter.createItem("MixedCase").status());
        assertEquals("signed-original", data(inventory, "allium", "signature"));
        assertEquals("2", data(inventory, "allium", "state_charges"));
        verify(inventory, never()).setItemMeta(any());
        verify(inventory, never()).setAmount(anyInt());
        verify(created, never()).setItemMeta(any());
        verify(created, never()).setAmount(anyInt());
        verify(inventory.getItemMeta().getPersistentDataContainer(), never()).set(any(), any(), any());
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        assertEquals(ProviderStatus.ABSENT, adapter.status());
    }

    @Test
    void unreadablePinnedRegistryIsFailedWithDiagnosticAndPostEventCanRecover() {
        api.probeFailure = new NoSuchFieldException("map");
        assertEquals(ProviderStatus.FAILED, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem("x").status());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("1.211.0"));
        assertTrue(warnings.get(0).contains("NoSuchFieldException"));
        api.probeFailure = null;
        api.initialized = true;
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void missingNativeIdAirAndNullAreNotRecognized() {
        api.initialized = true;
        assertEquals(Optional.empty(), adapter.identifyItemId(null));
        assertEquals(Optional.empty(), adapter.identifyItemId(new ItemStack(Material.AIR)));
        ItemStack vanilla = mock(ItemStack.class);
        when(vanilla.getType()).thenReturn(Material.PAPER);
        api.identityOverride = "";
        assertEquals(Optional.empty(), adapter.identifyItemId(vanilla));
    }

    @Test
    void missedDisableIsDetectedAndNoLaterPostEventRevivesProvider() {
        api.initialized = true;
        assertEquals(ProviderStatus.READY, adapter.status());
        when(manager.isPluginEnabled("Oraxen")).thenReturn(false);
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        when(manager.isPluginEnabled("Oraxen")).thenReturn(true);
        adapter.onOraxenItemsLoaded(new OraxenItemsLoadedEvent());
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        assertEquals(1, invalidations.get());
    }

    private static final class NativeFixture implements OraxenProviderAdapter.NativeApi {
        boolean initialized;
        ReflectiveOperationException probeFailure;
        Map<String, ItemStack> items = new HashMap<>();
        final List<String> buildCalls = new ArrayList<>();
        String identityOverride;
        @Override public boolean registryInitialized() throws ReflectiveOperationException {
            if (probeFailure != null) throw probeFailure;
            return initialized;
        }
        @Override public Set<String> itemNames() { return items.keySet(); }
        @Override public ItemStack build(String id) { buildCalls.add(id); return items.get(id); }
        @Override public String identify(ItemStack stack) {
            return identityOverride == null ? data(stack, "oraxen", "id") : identityOverride;
        }
    }

    private static String data(ItemStack stack, String namespace, String key) {
        return stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(namespace, key), PersistentDataType.STRING);
    }

    private static ItemStack stack(Material material, int amount, String id, String extra) {
        Map<NamespacedKey, String> values = Map.of(
                new NamespacedKey("oraxen", "id"), id,
                new NamespacedKey("oraxen", "extra"), extra,
                new NamespacedKey("allium", "state_charges"), "2",
                new NamespacedKey("allium", "signature"), "signed-original",
                new NamespacedKey("allium", "instance_uuid"), "uuid-original");
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getDisplayName()).thenReturn("Native " + id);
        when(meta.getCustomModelData()).thenReturn(8123);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.clone()).thenAnswer(call -> stack(material, amount, id, extra));
        return stack;
    }
}
