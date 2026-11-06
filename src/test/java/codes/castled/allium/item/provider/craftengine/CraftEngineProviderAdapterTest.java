package codes.castled.allium.item.provider.craftengine;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionStatus;
import org.bukkit.Material;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CraftEngineProviderAdapterTest {
    private static final String ID = "sf_ores:silver_ingot";
    private static final CraftEngineAccess.Lifecycle READY = new CraftEngineAccess.Lifecycle(false, false, false, true);
    private static final CraftEngineAccess.Lifecycle RELOADING = new CraftEngineAccess.Lifecycle(false, true, false, true);
    private CraftEngineAccess api;
    private PluginManager plugins;
    private CraftEngineProviderAdapter adapter;
    private Deque<Runnable> nextTick;
    private AtomicInteger invalidations;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        api = mock(CraftEngineAccess.class);
        when(api.lifecycle()).thenReturn(READY);
        plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(true);
        nextTick = new ArrayDeque<>();
        invalidations = new AtomicInteger();
        warnings = new ArrayList<>();
        adapter = new CraftEngineProviderAdapter(mock(Plugin.class), "26.9.1", ignored -> {}, warnings::add,
                invalidations::incrementAndGet, api, nextTick::addLast);
    }

    @Test
    void completeEmptyRegistryIsReadyRatherThanPermanentlyLoading() {
        adapter.initialize(plugins);
        assertEquals(ProviderStatus.READY, adapter.status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem(ID).status());
        assertTrue(adapter.hasAuthoritativeNativeIdCheck());
    }

    @Test
    void populatedRegistryDuringEnableStillReportsLoading() {
        when(api.loadedItemCount()).thenReturn(100);
        when(api.lifecycle()).thenReturn(new CraftEngineAccess.Lifecycle(false, false, true, false));
        adapter.initialize(plugins);
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(ID).status());
        verify(api, never()).loadedItemCount();
        verify(api, never()).build(anyString());
    }

    @Test
    void firstLoadCompletionWaitsUntilNextTickAndFullyLoadedFlag() {
        when(api.lifecycle()).thenReturn(new CraftEngineAccess.Lifecycle(false, false, false, false));
        adapter.initialize(plugins);
        adapter.onCraftEngineReload(null);
        assertEquals(ProviderStatus.LOADING, adapter.status());
        when(api.lifecycle()).thenReturn(READY);
        assertEquals(ProviderStatus.LOADING, adapter.status(), "event handling is deferred even after flags reset");
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void nativeReloadFlagImmediatelyGatesCreationAndIdentificationWithoutStartEvent() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(ID).status());
        assertEquals(Optional.empty(), adapter.identifyItemId(new ItemStack(Material.PAPER)));
        assertEquals(1, invalidations.get(), "a loading window invalidates diagnostics only once");
        verify(api, never()).build(anyString());
        verify(api, never()).nativeId(any());
    }

    @Test
    void completionEventWhileReloadFlagIsSetBecomesReadyAfterDeferredTick() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        assertEquals(ProviderStatus.LOADING, adapter.status());
        adapter.onCraftEngineReload(null);
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(ID).status());
        when(api.lifecycle()).thenReturn(READY);
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status(), "regression: event precedes native flag reset");
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem(ID).status());
    }

    @Test
    void flagsStillSetOnDeferredTickRequireFreshCompletionRatherThanReuseOldSuccess() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run();
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(ID).status());
        when(api.lifecycle()).thenReturn(READY);
        assertEquals(ProviderStatus.FAILED, adapter.status());
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void failedReloadAfterPriorCompletionCannotReuseThatSuccess() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        adapter.status();
        adapter.onCraftEngineReload(null); // Reload A succeeded; its callback is still queued.
        when(api.lifecycle()).thenReturn(READY);
        assertEquals(ProviderStatus.LOADING, adapter.status());
        when(api.lifecycle()).thenReturn(RELOADING); // A distinct observed reload B starts.
        assertEquals(ProviderStatus.LOADING, adapter.status());
        when(api.lifecycle()).thenReturn(READY); // B fails without a completion event.
        nextTick.removeFirst().run();
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem(ID).status());
        verify(api, never()).build(anyString());
    }

    @Test
    void reloadStillActiveAtPriorCompletionsDeferredTickRequiresFreshSuccess() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run(); // B started before the next tick; no idle poll was possible.
        when(api.lifecycle()).thenReturn(READY); // B fails, so no new completion evidence exists.
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem(ID).status());
    }

    @Test
    void precedingDeferredCallbackCannotFinishASecondSuccessfulReload() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        adapter.onCraftEngineReload(null);
        when(api.lifecycle()).thenReturn(READY);
        adapter.status();
        when(api.lifecycle()).thenReturn(RELOADING);
        adapter.status();
        adapter.onCraftEngineReload(null);
        when(api.lifecycle()).thenReturn(READY);
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.LOADING, adapter.status(), "only B's callback can reconcile B");
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void reloadWithoutSuccessEventFailsRatherThanReturningUnknownFromPartialRegistry() {
        adapter.initialize(plugins);
        when(api.lifecycle()).thenReturn(RELOADING);
        assertEquals(ProviderStatus.LOADING, adapter.status());
        when(api.lifecycle()).thenReturn(READY);
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem(ID).status());
        verify(api, never()).build(anyString());
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void apiLinkageFailureIsFailedAndSuccessfulReloadCanRecover() {
        when(api.loadedItemCount()).thenThrow(new NoSuchMethodError("wrong CraftEngine API"));
        adapter.initialize(plugins);
        assertEquals(ResolutionStatus.PROVIDER_FAILED, adapter.createItem(ID).status());
        assertEquals(1, warnings.size());
        doReturn(0).when(api).loadedItemCount();
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.READY, adapter.status());
    }

    @Test
    void runtimeDisableByEventCannotBeReversedByQueuedReloadCompletion() {
        adapter.initialize(plugins);
        adapter.onCraftEngineReload(null);
        Plugin provider = mock(Plugin.class);
        when(provider.getName()).thenReturn("CraftEngine");
        adapter.onPluginDisable(new PluginDisableEvent(provider));
        nextTick.removeFirst().run();
        assertEquals(ProviderStatus.ABSENT, adapter.status());
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, adapter.createItem(ID).status());
    }

    @Test
    void runtimeDisableIsAlsoDetectedWithoutEvent() {
        adapter.initialize(plugins);
        when(plugins.isPluginEnabled("CraftEngine")).thenReturn(false);
        assertEquals(ResolutionStatus.PROVIDER_ABSENT, adapter.createItem(ID).status());
        assertEquals(1, invalidations.get());
        adapter.status();
        assertEquals(1, invalidations.get());
    }

    @Test
    void fullNamespaceAndFreshNativeBuildsSurviveRepeatedCreationAndReload() {
        adapter.initialize(plugins);
        ItemStack firstNative = new ItemStack(Material.IRON_INGOT, 3);
        ItemStack secondNative = new ItemStack(Material.GOLD_INGOT);
        when(api.build(ID)).thenReturn(firstNative, secondNative);
        var first = adapter.createItem(" SF_ORES:SILVER_INGOT ");
        assertEquals(ProviderKey.parse("craftengine:sf_ores:silver_ingot"), first.requestedKey());
        assertNotSame(firstNative, first.itemStack().orElseThrow());
        first.itemStack().orElseThrow().setAmount(12);
        assertEquals(3, firstNative.getAmount());
        adapter.onCraftEngineReload(null);
        nextTick.removeFirst().run();
        assertEquals(Material.GOLD_INGOT, adapter.createItem(ID).itemStack().orElseThrow().getType());
        verify(api, times(2)).build(ID);
    }

    @Test
    void nativeIdIsAuthoritativeAndItsFullNamespaceIsPreserved() {
        adapter.initialize(plugins);
        ItemStack stack = new ItemStack(Material.PAPER);
        when(api.nativeId(stack)).thenReturn("sf_ores:silver_ingot").thenReturn(null);
        assertEquals(Optional.of(ID), adapter.identifyItemId(stack));
        assertTrue(adapter.identifyItemId(stack).isEmpty());
        verify(api, times(2)).nativeId(stack);
    }

    @Test
    void reloadStartingDuringNativeBuildReturnsLoadingNotUnknownId() {
        adapter.initialize(plugins);
        when(api.build(ID)).thenAnswer(invocation -> {
            when(api.lifecycle()).thenReturn(RELOADING);
            return null;
        });
        assertEquals(ResolutionStatus.PROVIDER_LOADING, adapter.createItem(ID).status());
    }

    @Test
    void unknownMalformedAndAirNeverResolve() {
        adapter.initialize(plugins);
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("silver_ingot").status());
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem("sf:ores:silver_ingot").status());
        when(api.build(ID)).thenReturn(new ItemStack(Material.AIR));
        assertEquals(ResolutionStatus.UNKNOWN_ID, adapter.createItem(ID).status());
        assertTrue(adapter.identifyItemId(null).isEmpty());
        assertTrue(adapter.identifyItemId(new ItemStack(Material.AIR)).isEmpty());
        verify(api, never()).nativeId(any());
    }
}
