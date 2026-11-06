package codes.castled.allium.item.provider.nexo;

import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionStatus;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.api.events.NexoPreItemsLoadEvent;
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

/** Real pinned lifecycle event types, with a focused fixture at the static API boundary. */
class NexoProviderAdapterTest {
    private final NativeFixture api = new NativeFixture();
    private final Plugin host = mock(Plugin.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final List<Runnable> nextTick = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final AtomicInteger invalidations = new AtomicInteger();
    private NexoProviderAdapter adapter;

    @BeforeEach
    void initialize() {
        when(manager.isPluginEnabled("Nexo")).thenReturn(true);
        adapter = new NexoProviderAdapter(host, "1.29.1", ignored -> {}, warnings::add,
                invalidations::incrementAndGet, api, nextTick::add);
        adapter.initialize(manager);
    }

    @Test
    void waitsForQueuedInitialLoadEvenWhenFlagHasNotYetBeenSet() {
        // The verified flag defaults to false before Nexo's own next-tick task starts.
        assertEquals(ProviderStatus.LOADING, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("Anything").status());
        verify(manager).registerEvents(adapter, host);
        api.loading = true;
        nextTick.get(0).run();
        assertEquals(ProviderStatus.LOADING, adapter.status());
        api.loading = false;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        assertEquals(ProviderStatus.READY, adapter.status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("Anything").status());
    }

    @Test
    void lateAttachToCompletedEmptyRegistryIsReadyAfterInitialCheck() {
        nextTick.get(0).run();
        assertEquals(ProviderStatus.READY, adapter.status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("Missing").status());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void publishedNonemptyRegistryCannotBypassInProgressAsyncDeferrals() {
        api.items.put("PublishedEarly", stack(Material.PAPER, 1, "PublishedEarly", "native"));
        api.loading = true;
        nextTick.get(0).run();
        assertEquals(ProviderStatus.LOADING, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("PublishedEarly").status());
        api.loading = false;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        assertEquals(ResolutionStatus.RESOLVED, adapter.createItem("PublishedEarly").status());
    }

    @Test
    void realPreAndPostEventsInvalidateReadinessAndKeepNativeBuildsFreshAndCaseDistinct() throws Exception {
        assertNotNull(NexoProviderAdapter.class.getMethod("onNexoPreItemsLoad", NexoPreItemsLoadEvent.class)
                .getAnnotation(EventHandler.class));
        assertNotNull(NexoProviderAdapter.class.getMethod("onNexoItemsLoaded", NexoItemsLoadedEvent.class)
                .getAnnotation(EventHandler.class));
        api.items.put("Staff", stack(Material.PAPER, 4, "Staff", "v1"));
        api.items.put("staff", stack(Material.STICK, 1, "staff", "lowercase"));
        nextTick.get(0).run();
        ItemStack first = adapter.createItem("Staff").itemStack().orElseThrow();
        assertEquals(4, first.getAmount());
        assertEquals(Material.STICK, adapter.createItem("staff").itemStack().orElseThrow().getType());
        assertNotSame(first, adapter.createItem("Staff").itemStack().orElseThrow());
        assertEquals(List.of("Staff", "staff", "Staff"), api.buildCalls);

        api.loading = true;
        adapter.onNexoPreItemsLoad(new NexoPreItemsLoadEvent());
        assertEquals(ProviderStatus.LOADING, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("Staff").status());
        api.items.put("Staff", stack(Material.DIAMOND, 2, "Staff", "v2"));
        api.loading = false;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        ItemStack reloaded = adapter.createItem("Staff").itemStack().orElseThrow();
        assertEquals(Material.DIAMOND, reloaded.getType());
        assertEquals(List.of("Staff", "staff", "Staff", "Staff"), api.buildCalls);
        assertEquals(2, invalidations.get());
        assertEquals("v1", data(first, "nexo", "extra"));
    }

    @Test
    void flagProbeAlsoClosesGapBeforeOurPreEventHandlerRuns() {
        nextTick.get(0).run();
        api.loading = true; // Another plugin calls Allium from an earlier pre-event listener.
        assertEquals(ProviderStatus.LOADING, adapter.status());
        assertEquals(1, invalidations.get());
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("x").status());
        api.loading = false;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void lifecycleAndIdentifyNeverRewriteNativeMetadataOrAlliumStateAndSignature() {
        ItemStack inventoryItem = stack(Material.PAPER, 7, "CaseSensitive", "extra");
        api.items.put("CaseSensitive", inventoryItem);
        nextTick.get(0).run();
        ItemStack created = adapter.createItem("CaseSensitive").itemStack().orElseThrow();
        assertEquals(Optional.of("CaseSensitive"), adapter.identifyItemId(created));
        assertTrue(adapter.hasAuthoritativeNativeIdCheck());
        assertEquals(Optional.empty(), adapter.identifyItemId(null));
        assertEquals(Optional.empty(), adapter.identifyItemId(new ItemStack(Material.AIR)));
        assertEquals("extra", data(created, "nexo", "extra"));
        assertEquals("signed-original", data(created, "allium", "signature"));
        assertEquals("2", data(created, "allium", "state_charges"));
        assertEquals("instance-original", data(created, "allium", "instance_uuid"));

        api.loading = true;
        adapter.onNexoPreItemsLoad(new NexoPreItemsLoadEvent());
        assertEquals(Optional.empty(), adapter.identifyItemId(inventoryItem));
        api.loading = false;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        Plugin provider = mock(Plugin.class);
        when(provider.getName()).thenReturn("Nexo");
        adapter.onPluginDisable(new PluginDisableEvent(provider));
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, adapter.createItem("CaseSensitive").status());
        assertEquals("signed-original", data(inventoryItem, "allium", "signature"));
        assertEquals("2", data(inventoryItem, "allium", "state_charges"));
        verify(inventoryItem, never()).setItemMeta(any());
        verify(inventoryItem, never()).setAmount(anyInt());
        verify(created, never()).setItemMeta(any());
        verify(created, never()).setAmount(anyInt());
        verify(inventoryItem.getItemMeta().getPersistentDataContainer(), never()).set(any(), any(), any());
        // A queued initial check or stray post event must not resurrect a disabled provider.
        nextTick.get(0).run();
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        assertEquals(ProviderStatus.ABSENT, adapter.status());
    }

    @Test
    void readinessProbeFailureIsFailedWithDiagnosticAndCanRecoverOnRealPostEvent() {
        api.probeFailure = new NoSuchFieldException("loading");
        nextTick.get(0).run();
        assertEquals(ProviderStatus.FAILED, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem("x").status());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("pinned 1.29.1 readiness"));
        assertTrue(warnings.get(0).contains("NoSuchFieldException"));
        api.probeFailure = null;
        adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void missedDisableEventIsDetectedAndStaysAbsent() {
        nextTick.get(0).run();
        when(manager.isPluginEnabled("Nexo")).thenReturn(false);
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        when(manager.isPluginEnabled("Nexo")).thenReturn(true);
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        assertEquals(1, invalidations.get());
    }

    @Test
    void reloadDuringNativeBuildCannotReturnOrCacheStaleStack() {
        api.items.put("Staff", stack(Material.PAPER, 1, "Staff", "old"));
        nextTick.get(0).run();
        api.onBuild = () -> {
            api.loading = true;
            adapter.onNexoPreItemsLoad(new NexoPreItemsLoadEvent());
            api.items.put("Staff", stack(Material.DIAMOND, 1, "Staff", "new"));
            api.loading = false;
            adapter.onNexoItemsLoaded(new NexoItemsLoadedEvent());
        };
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem("Staff").status());
        api.onBuild = () -> {};
        assertEquals(Material.DIAMOND, adapter.createItem("Staff").itemStack().orElseThrow().getType());
    }

    private static class NativeFixture implements NexoProviderAdapter.NativeApi {
        boolean loading;
        ReflectiveOperationException probeFailure;
        final Map<String, ItemStack> items = new HashMap<>();
        final List<String> buildCalls = new ArrayList<>();
        Runnable onBuild = () -> {};

        @Override public boolean isLoading() throws ReflectiveOperationException {
            if (probeFailure != null) throw probeFailure;
            return loading;
        }
        @Override public Set<String> itemNames() { return items.keySet(); }
        @Override public ItemStack build(String id) {
            buildCalls.add(id);
            ItemStack value = items.get(id);
            onBuild.run();
            return value;
        }
        @Override public String identify(ItemStack stack) { return data(stack, "nexo", "id"); }
    }

    private static String data(ItemStack stack, String namespace, String key) {
        return stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(namespace, key), PersistentDataType.STRING);
    }

    private static ItemStack stack(Material material, int amount, String id, String extra) {
        Map<NamespacedKey, String> values = Map.of(
                new NamespacedKey("nexo", "id"), id,
                new NamespacedKey("nexo", "extra"), extra,
                new NamespacedKey("allium", "state_charges"), "2",
                new NamespacedKey("allium", "signature"), "signed-original",
                new NamespacedKey("allium", "instance_uuid"), "instance-original");
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getDisplayName()).thenReturn("Native name " + id);
        when(meta.getCustomModelData()).thenReturn(1234);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.clone()).thenAnswer(call -> stack(material, amount, id, extra));
        return stack;
    }
}
