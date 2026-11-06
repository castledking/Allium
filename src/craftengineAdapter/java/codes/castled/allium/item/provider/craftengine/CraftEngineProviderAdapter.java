package codes.castled.allium.item.provider.craftengine;

import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.provider.ProviderConfigurationDiagnostics;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Isolated adapter for the CraftEngine 26.9 API, verified against 26.9.1 core/bukkit jars.
 * Provider types must remain in this compileOnly source set. There is no legacy API fallback.
 *
 * <p>CraftEngine has a completion event, but no registry-reload-start event. Every operation
 * polls its native lifecycle flags before accessing the registry. Its completion event runs
 * before those flags reset, so readiness is reconciled on the next server tick.
 */
public final class CraftEngineProviderAdapter implements ItemProviderAdapter, ProviderConfigurationDiagnostics, Listener {
    public static final String PLUGIN_NAME = "CraftEngine";
    private static final Pattern NATIVE_ID = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private final Plugin hostPlugin;
    private final String providerVersion;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Runnable reloadInvalidateCallback;
    private final CraftEngineAccess api;
    private final Consumer<Runnable> nextTick;
    private PluginManager pluginManager;
    private ProviderStatus currentStatus = ProviderStatus.LOADING;
    private boolean awaitingCompletion;
    private boolean completionObserved;
    private boolean completionDeferred;
    private boolean nativeReloading;
    private long completionSequence;

    public CraftEngineProviderAdapter(Plugin hostPlugin, String providerVersion,
                                      Consumer<String> infoLogger, Consumer<String> warningLogger,
                                      Runnable reloadInvalidateCallback) {
        this(hostPlugin, providerVersion, infoLogger, warningLogger, reloadInvalidateCallback,
                new CraftEngineNativeAccess(),
                task -> hostPlugin.getServer().getScheduler().runTask(hostPlugin, task));
    }

    CraftEngineProviderAdapter(Plugin hostPlugin, String providerVersion,
                               Consumer<String> infoLogger, Consumer<String> warningLogger,
                               Runnable reloadInvalidateCallback, CraftEngineAccess api,
                               Consumer<Runnable> nextTick) {
        this.hostPlugin = Objects.requireNonNull(hostPlugin, "hostPlugin");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.reloadInvalidateCallback = Objects.requireNonNull(reloadInvalidateCallback, "reloadInvalidateCallback");
        this.api = Objects.requireNonNull(api, "api");
        this.nextTick = Objects.requireNonNull(nextTick, "nextTick");
    }

    public synchronized void initialize(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        pluginManager.registerEvents(this, hostPlugin);
        refreshReadinessFromRegistry(false);
    }

    public synchronized void refreshReadinessFromRegistry(boolean isReload) {
        reconcileReadiness(true);
    }

    private ProviderStatus reconcileReadiness(boolean checkRegistry) {
        if (currentStatus == ProviderStatus.ABSENT) return currentStatus;
        try {
            if (pluginManager != null && !pluginManager.isPluginEnabled(PLUGIN_NAME)) {
                markDisabled();
                return currentStatus;
            }
            CraftEngineAccess.Lifecycle lifecycle = api.lifecycle();
            if (lifecycle.disabled()) {
                markDisabled();
                return currentStatus;
            }
            if (lifecycle.reloading()) {
                if (!nativeReloading) beginReload();
                nativeReloading = true;
                currentStatus = ProviderStatus.LOADING;
                return ProviderStatus.LOADING;
            }
            nativeReloading = false;
            if (lifecycle.initializing() || !lifecycle.fullyLoaded() || completionDeferred) {
                currentStatus = ProviderStatus.LOADING;
                return currentStatus;
            }
            if (awaitingCompletion && !completionObserved) {
                fail("reload ended without a successful CraftEngineReloadEvent", null);
                return currentStatus;
            }
            if (currentStatus == ProviderStatus.FAILED && !completionObserved) return currentStatus;
            if (checkRegistry || currentStatus != ProviderStatus.READY) {
                // An empty registry is valid after CraftEngine confirms initial/full loading.
                int count = api.loadedItemCount();
                boolean wasReady = currentStatus == ProviderStatus.READY;
                boolean reloaded = awaitingCompletion;
                currentStatus = ProviderStatus.READY;
                awaitingCompletion = false;
                completionObserved = false;
                if (!wasReady) {
                    infoLogger.accept("CraftEngine provider (" + providerVersion + ") READY (" + count + " items).");
                    if (reloaded) reloadInvalidateCallback.run();
                }
            }
            return currentStatus;
        } catch (Exception | LinkageError failure) {
            fail("readiness check", failure);
            return currentStatus;
        }
    }

    private void beginReload() {
        if (currentStatus == ProviderStatus.ABSENT) return;
        currentStatus = ProviderStatus.LOADING;
        awaitingCompletion = true;
        completionObserved = false;
        completionDeferred = false;
        ++completionSequence; // A preceding reload's queued callback cannot certify this cycle.
        reloadInvalidateCallback.run();
    }

    public synchronized void markDisabled() {
        if (currentStatus == ProviderStatus.ABSENT) return;
        currentStatus = ProviderStatus.ABSENT;
        ++completionSequence;
        reloadInvalidateCallback.run();
        HandlerList.unregisterAll(this);
        warningLogger.accept("CraftEngine disabled; provider is ABSENT.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onCraftEngineReload(CraftEngineReloadEvent event) {
        if (currentStatus == ProviderStatus.ABSENT) return;
        beginReload();
        // The pinned event precedes the flag reset. Queries from other event listeners
        // must treat that still-set flag as this completion's cycle, not a new reload.
        nativeReloading = true;
        completionObserved = true;
        completionDeferred = true;
        long sequence = ++completionSequence;
        // In 26.9.1 both first-load and reload completion fire before final flags reset.
        try {
            nextTick.accept(() -> finishReload(sequence));
        } catch (Exception | LinkageError failure) {
            completionDeferred = false;
            completionObserved = false;
            fail("scheduling reload readiness", failure);
        }
    }

    private synchronized void finishReload(long sequence) {
        if (currentStatus == ProviderStatus.ABSENT || sequence != completionSequence) return;
        completionDeferred = false;
        // In the pinned implementation flags clear in the same call that fires the event.
        // If still busy next tick, another reload may have started without an idle poll.
        // Require its own completion; retaining the old success could certify partial data.
        nativeReloading = false;
        reconcileReadiness(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) markDisabled();
    }

    @Override
    public String providerName() {
        return ProviderKey.CRAFTENGINE;
    }

    @Override
    public synchronized ProviderStatus status() {
        return reconcileReadiness(false);
    }

    @Override
    public boolean hasAuthoritativeNativeIdCheck() {
        return true;
    }

    @Override
    public synchronized List<String> configurationWarnings(Set<String> managedItemIds) throws ReflectiveOperationException {
        if (status() != ProviderStatus.READY) return List.of();
        List<String> warnings = new ArrayList<>();
        for (String id : managedItemIds.stream().sorted().toList()) {
            if (NATIVE_ID.matcher(id).matches() && api.hasResetUpdater(id)) {
                warnings.add("Allium-managed CraftEngine item '" + id + "' has a reset updater. Stock CE 26.9.1/26.9.2 "
                        + "resets lose stack quantity (7 -> 1), even with metadata retention. Every reset in its version chain "
                        + "must retain keep_components: [minecraft:custom_data] and keep_tags: [PublicBukkitValues], "
                        + "and use a verified count-preserving provider fix (buildItem(context, item.count())). "
                        + "See docs/s05-provider-matrix.md; Allium does not fingerprint the installed binary or edit provider configuration.");
            }
        }
        return List.copyOf(warnings);
    }

    @Override
    public synchronized ResolutionResult createItem(String id) {
        ProviderKey key = ProviderKey.of(ProviderKey.CRAFTENGINE, id);
        ProviderStatus readiness = status();
        if (readiness != ProviderStatus.READY) return unavailable(key, readiness);
        if (!NATIVE_ID.matcher(key.id()).matches()) {
            return ResolutionResult.unknownId(key, "CraftEngine requires a full namespace:id");
        }
        try {
            // Build afresh: definitions may contain dynamic processors. Never cache signed/stateful stacks.
            ItemStack built = api.build(key.id());
            readiness = status();
            if (readiness != ProviderStatus.READY) return unavailable(key, readiness);
            if (built == null || built.getType().isAir() || built.getAmount() < 1) {
                return ResolutionResult.unknownId(key, "Unknown CraftEngine item: " + key.id());
            }
            return ResolutionResult.resolved(key, built.clone());
        } catch (Exception | LinkageError failure) {
            fail("building " + key, failure);
            return ResolutionResult.providerFailed(key, "CraftEngine failed to build " + key.id());
        }
    }

    private ResolutionResult unavailable(ProviderKey key, ProviderStatus readiness) {
        if (readiness == ProviderStatus.ABSENT) {
            return ResolutionResult.providerAbsent(key, "CraftEngine is disabled or absent");
        }
        if (readiness == ProviderStatus.LOADING) {
            return ResolutionResult.providerLoading(key, "CraftEngine is loading or reloading");
        }
        return ResolutionResult.providerFailed(key, "CraftEngine adapter failed; awaiting successful reload");
    }

    @Override
    public synchronized Optional<String> identifyItemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() < 1 || status() != ProviderStatus.READY) {
            return Optional.empty();
        }
        try {
            // This reads CraftEngine's native tag, never Allium PDC, display names, or model data.
            String id = api.nativeId(stack);
            if (status() != ProviderStatus.READY || id == null || !NATIVE_ID.matcher(id).matches()) {
                return Optional.empty();
            }
            return Optional.of(id);
        } catch (Exception | LinkageError failure) {
            fail("reading native item ID", failure);
            return Optional.empty();
        }
    }

    private void fail(String operation, Throwable failure) {
        boolean changed = currentStatus != ProviderStatus.FAILED;
        currentStatus = ProviderStatus.FAILED;
        if (changed) {
            warningLogger.accept("CraftEngine provider (" + providerVersion + ") FAILED: " + operation
                    + (failure == null ? "" : " (" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")"));
        }
    }
}
