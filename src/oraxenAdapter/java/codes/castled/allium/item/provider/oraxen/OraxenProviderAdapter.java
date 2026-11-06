package codes.castled.allium.item.provider.oraxen;

import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Isolated compileOnly binding to io.th0rgal:oraxen:1.211.0 (Java 21 API, adapter emitted
 * as Java 17). The bootstrap owns the exact tested API/server compatibility gates.
 *
 * <p>The pinned ReloadCommand.reloadItems calls loadItems synchronously, then emits only
 * OraxenItemsLoadedEvent. There is no pre-load event or asynchronous loading flag. Ordinary
 * Bukkit main-thread calls cannot interleave with that operation; arbitrary off-thread or
 * custom-mechanic reentry is not an established contract. We deliberately keep no item
 * template cache, including across direct API loadItems calls that emit no event.
 *
 * <p>The verified private static Map map distinguishes not-yet-initialized (null) from a
 * loaded empty registry; public getMap masks null with a fresh empty map. This probe reads
 * only, checks its declared shape, and fails explicitly if the pinned internals change.
 * Oraxen catches some parse failures internally and its post event has no success payload;
 * a retained registry following such a failure cannot be distinguished by this adapter.
 */
public final class OraxenProviderAdapter implements ItemProviderAdapter, Listener {
    public static final String PLUGIN_NAME = "Oraxen";

    interface NativeApi {
        boolean registryInitialized() throws ReflectiveOperationException;
        Set<String> itemNames();
        ItemStack build(String id);
        String identify(ItemStack stack);
    }

    private static final class PinnedApi implements NativeApi {
        private Field registry;

        @Override
        public boolean registryInitialized() throws ReflectiveOperationException {
            if (registry == null) {
                Field field = OraxenItems.class.getDeclaredField("map");
                if (field.getType() != Map.class || !Modifier.isStatic(field.getModifiers())
                        || !field.trySetAccessible()) {
                    throw new IllegalAccessException("OraxenItems.map must be an accessible static Map (pinned 1.211.0)");
                }
                registry = field;
            }
            return registry.get(null) != null;
        }

        @Override public Set<String> itemNames() { return OraxenItems.getNames(); }
        @Override public ItemStack build(String id) {
            ItemBuilder builder = OraxenItems.getItemById(id);
            return builder == null ? null : builder.build();
        }
        @Override public String identify(ItemStack stack) { return OraxenItems.getIdByItem(stack); }
    }

    private final Plugin hostPlugin;
    private final String providerVersion;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Runnable invalidateCallback;
    private final NativeApi api;
    private PluginManager pluginManager;
    private ProviderStatus currentStatus = ProviderStatus.LOADING;
    private long generation;

    public OraxenProviderAdapter(Plugin hostPlugin, String providerVersion,
                                 Consumer<String> infoLogger, Consumer<String> warningLogger,
                                 Runnable reloadInvalidateCallback) {
        this(hostPlugin, providerVersion, infoLogger, warningLogger, reloadInvalidateCallback, new PinnedApi());
    }

    OraxenProviderAdapter(Plugin hostPlugin, String providerVersion,
                          Consumer<String> infoLogger, Consumer<String> warningLogger,
                          Runnable reloadInvalidateCallback, NativeApi api) {
        this.hostPlugin = Objects.requireNonNull(hostPlugin, "hostPlugin");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.invalidateCallback = Objects.requireNonNull(reloadInvalidateCallback, "reloadInvalidateCallback");
        this.api = Objects.requireNonNull(api, "api");
    }

    public synchronized void initialize(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        pluginManager.registerEvents(this, hostPlugin);
        status();
    }

    private void invalidate() {
        generation++;
        invalidateCallback.run();
    }

    private void refreshReadiness(boolean loadCompleted) {
        try {
            if (!api.registryInitialized()) {
                if (loadCompleted) {
                    fail("completing item load", new IllegalStateException(
                            "OraxenItemsLoadedEvent fired without an initialized registry"));
                    return;
                }
                if (currentStatus != ProviderStatus.LOADING) {
                    currentStatus = ProviderStatus.LOADING;
                    invalidate();
                }
                return;
            }
            Set<String> names = Objects.requireNonNull(api.itemNames(), "Oraxen getNames registry is null");
            if (currentStatus != ProviderStatus.READY) {
                currentStatus = ProviderStatus.READY;
                infoLogger.accept("Oraxen provider (" + providerVersion + ") READY (" + names.size() + " items).");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail("reading pinned 1.211.0 readiness/registry", failure);
        }
    }

    private void fail(String operation, Throwable failure) {
        currentStatus = ProviderStatus.FAILED;
        invalidate();
        warningLogger.accept("Oraxen provider (" + providerVersion + ") FAILED " + operation + ": "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onOraxenItemsLoaded(OraxenItemsLoadedEvent event) {
        if (currentStatus == ProviderStatus.ABSENT) return;
        invalidate();
        refreshReadiness(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (PLUGIN_NAME.equals(event.getPlugin().getName())) markDisabled();
    }

    public synchronized void markDisabled() {
        if (currentStatus == ProviderStatus.ABSENT) return;
        currentStatus = ProviderStatus.ABSENT;
        invalidate();
        HandlerList.unregisterAll(this);
        warningLogger.accept("Oraxen was disabled; provider is ABSENT.");
    }

    @Override public String providerName() { return ProviderKey.ORAXEN; }
    @Override public boolean hasAuthoritativeNativeIdCheck() { return true; }

    @Override
    public synchronized ProviderStatus status() {
        if (currentStatus == ProviderStatus.ABSENT) return currentStatus;
        if (pluginManager != null && !pluginManager.isPluginEnabled(PLUGIN_NAME)) markDisabled();
        if (currentStatus != ProviderStatus.ABSENT && currentStatus != ProviderStatus.FAILED) refreshReadiness(false);
        return currentStatus;
    }

    private ResolutionResult unavailable(ProviderKey key, ProviderStatus status) {
        return switch (status) {
            case ABSENT -> ResolutionResult.providerAbsent(key, "Oraxen is not installed or enabled");
            case LOADING -> ResolutionResult.providerLoading(key, "Oraxen item registry has not initialized");
            case FAILED -> ResolutionResult.providerFailed(key, "Oraxen provider is FAILED; see its lifecycle diagnostic");
            case READY -> throw new IllegalArgumentException("READY is available");
        };
    }

    @Override
    public synchronized ResolutionResult createItem(String id) {
        ProviderKey key = ProviderKey.of(ProviderKey.ORAXEN, id);
        ProviderStatus state = status();
        if (state != ProviderStatus.READY) return unavailable(key, state);
        long before = generation;
        try {
            ItemStack built = api.build(id.trim());
            ProviderStatus after = status();
            if (after != ProviderStatus.READY) return unavailable(key, after);
            if (before != generation) {
                return ResolutionResult.providerLoading(key, "Oraxen registry changed while building; retry resolution");
            }
            if (built == null || built.getType().isAir()) {
                return ResolutionResult.unknownId(key, "Unknown or empty Oraxen item ID: '" + id.trim() + "'");
            }
            // Native components, PDC, display name/model and amount survive without modification.
            return ResolutionResult.resolved(key, built.clone());
        } catch (RuntimeException | LinkageError failure) {
            fail("building '" + id.trim() + "'", failure);
            return unavailable(key, ProviderStatus.FAILED);
        }
    }

    @Override
    public synchronized Optional<String> identifyItemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || status() != ProviderStatus.READY) return Optional.empty();
        try {
            // Authoritative provider API inspection only; never rewrite inventory state/signatures.
            String id = api.identify(stack);
            return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (RuntimeException | LinkageError failure) {
            fail("identifying native item", failure);
            return Optional.empty();
        }
    }
}
