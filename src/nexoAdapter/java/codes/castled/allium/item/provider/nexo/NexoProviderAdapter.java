package codes.castled.allium.item.provider.nexo;

import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.api.events.NexoPreItemsLoadEvent;
import com.nexomc.nexo.items.ItemBuilder;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Optional Nexo 1.29.1 API binding. The API is Java 21; this isolated adapter is emitted as
 * Java 17 bytecode and must only be loaded after the bootstrap's provider/JVM/API checks.
 * No provider classes are bundled with Allium.
 *
 * <p>Nexo schedules its initial load on the next tick and can publish a nonempty registry
 * before external item deferrals finish. Registry size is therefore not a readiness signal.
 * The pinned API has no public readiness accessor. Its verified private static boolean
 * {@code loading} is read (never written), with failure reported as FAILED. Initial probing
 * is deferred one tick, after Nexo's queued load, to distinguish an empty completed load
 * from the pre-start value of that flag. Reloads use the actual pre/post item events added
 * in Nexo 1.29. The version support matrix is owned by the bootstrap/docs, not inferred here.
 */
public final class NexoProviderAdapter implements ItemProviderAdapter, Listener {
    public static final String PLUGIN_NAME = "Nexo";

    /** Small fixture boundary; the production implementation below calls the real pinned API. */
    interface NativeApi {
        boolean isLoading() throws ReflectiveOperationException;
        Set<String> itemNames();
        ItemStack build(String id);
        String identify(ItemStack stack);
    }

    private static final class PinnedApi implements NativeApi {
        private Field loading;

        @Override
        public boolean isLoading() throws ReflectiveOperationException {
            if (loading == null) {
                Field field = NexoItems.class.getDeclaredField("loading");
                if (field.getType() != boolean.class || !Modifier.isStatic(field.getModifiers())
                        || !field.trySetAccessible()) {
                    throw new IllegalAccessException("NexoItems.loading must be an accessible static boolean (pinned 1.29.1)");
                }
                loading = field;
            }
            return loading.getBoolean(null);
        }

        @Override
        public Set<String> itemNames() {
            return NexoItems.itemNames();
        }

        @Override
        public ItemStack build(String id) {
            ItemBuilder builder = NexoItems.itemFromId(id);
            return builder == null ? null : builder.build();
        }

        @Override
        public String identify(ItemStack stack) {
            return NexoItems.idFromItem(stack);
        }
    }

    private final Plugin hostPlugin;
    private final String providerVersion;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warningLogger;
    private final Runnable invalidateCallback;
    private final NativeApi api;
    private final Consumer<Runnable> scheduleInitialCheck;
    private PluginManager pluginManager;
    private ProviderStatus currentStatus = ProviderStatus.LOADING;
    private boolean probingEnabled;
    private long generation;

    public NexoProviderAdapter(Plugin hostPlugin, String providerVersion,
                               Consumer<String> infoLogger, Consumer<String> warningLogger,
                               Runnable reloadInvalidateCallback) {
        this(hostPlugin, providerVersion, infoLogger, warningLogger, reloadInvalidateCallback,
                new PinnedApi(), task -> hostPlugin.getServer().getScheduler().runTask(hostPlugin, task));
    }

    NexoProviderAdapter(Plugin hostPlugin, String providerVersion,
                        Consumer<String> infoLogger, Consumer<String> warningLogger,
                        Runnable reloadInvalidateCallback, NativeApi api, Consumer<Runnable> scheduleInitialCheck) {
        this.hostPlugin = Objects.requireNonNull(hostPlugin, "hostPlugin");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.infoLogger = Objects.requireNonNull(infoLogger, "infoLogger");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.invalidateCallback = Objects.requireNonNull(reloadInvalidateCallback, "reloadInvalidateCallback");
        this.api = Objects.requireNonNull(api, "api");
        this.scheduleInitialCheck = Objects.requireNonNull(scheduleInitialCheck, "scheduleInitialCheck");
    }

    public synchronized void initialize(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        pluginManager.registerEvents(this, hostPlugin);
        if (!pluginManager.isPluginEnabled(PLUGIN_NAME)) {
            markDisabled();
            return;
        }
        try {
            scheduleInitialCheck.accept(() -> {
                synchronized (NexoProviderAdapter.this) {
                    if (currentStatus == ProviderStatus.ABSENT) return;
                    probingEnabled = true;
                    refreshReadiness();
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            fail("scheduling initial readiness check", failure);
        }
    }

    private void invalidate() {
        generation++;
        invalidateCallback.run();
    }

    private void refreshReadiness() {
        if (currentStatus == ProviderStatus.ABSENT) return;
        try {
            if (api.isLoading()) {
                if (currentStatus != ProviderStatus.LOADING) {
                    currentStatus = ProviderStatus.LOADING;
                    invalidate();
                }
                return;
            }
            Set<String> names = Objects.requireNonNull(api.itemNames(), "Nexo itemNames registry is null");
            if (currentStatus != ProviderStatus.READY) {
                currentStatus = ProviderStatus.READY;
                infoLogger.accept("Nexo provider (" + providerVersion + ") READY (" + names.size() + " items).");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail("reading pinned 1.29.1 readiness flag/registry", failure);
        }
    }

    private void fail(String operation, Throwable failure) {
        currentStatus = ProviderStatus.FAILED;
        invalidate();
        warningLogger.accept("Nexo provider (" + providerVersion + ") FAILED " + operation + ": "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public synchronized void onNexoPreItemsLoad(NexoPreItemsLoadEvent event) {
        if (currentStatus == ProviderStatus.ABSENT) return;
        probingEnabled = true;
        currentStatus = ProviderStatus.LOADING;
        invalidate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void onNexoItemsLoaded(NexoItemsLoadedEvent event) {
        if (currentStatus == ProviderStatus.ABSENT) return;
        probingEnabled = true;
        invalidate();
        refreshReadiness();
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
        warningLogger.accept("Nexo was disabled; provider is ABSENT.");
    }

    @Override
    public String providerName() {
        return ProviderKey.NEXO;
    }

    @Override
    public synchronized ProviderStatus status() {
        if (currentStatus == ProviderStatus.ABSENT) return currentStatus;
        if (pluginManager != null && !pluginManager.isPluginEnabled(PLUGIN_NAME)) markDisabled();
        if (currentStatus != ProviderStatus.ABSENT && currentStatus != ProviderStatus.FAILED && probingEnabled) {
            refreshReadiness();
        }
        return currentStatus;
    }

    @Override
    public boolean hasAuthoritativeNativeIdCheck() {
        return true;
    }

    private ResolutionResult unavailable(ProviderKey key, ProviderStatus status) {
        return switch (status) {
            case ABSENT -> ResolutionResult.providerAbsent(key, "Nexo is not installed or enabled");
            case LOADING -> ResolutionResult.providerLoading(key, "Nexo is loading or reloading its item registry");
            case FAILED -> ResolutionResult.providerFailed(key, "Nexo provider is FAILED; see its lifecycle diagnostic");
            case READY -> throw new IllegalArgumentException("READY is available");
        };
    }

    @Override
    public synchronized ResolutionResult createItem(String id) {
        ProviderKey key = ProviderKey.of(ProviderKey.NEXO, id);
        ProviderStatus state = status();
        if (state != ProviderStatus.READY) return unavailable(key, state);
        // Nexo's entries map uses exact, case-sensitive IDs.
        String nativeId = id.trim();
        long before = generation;
        try {
            // Let Nexo build each request; caching ItemStacks could duplicate provider-generated
            // unique tags or freeze dynamic processors. Only lifecycle generations are retained.
            ItemStack built = api.build(nativeId);
            ProviderStatus after = status();
            if (after != ProviderStatus.READY) return unavailable(key, after);
            if (before != generation) {
                return ResolutionResult.providerLoading(key, "Nexo registry changed while building; retry resolution");
            }
            if (built == null || built.getType().isAir()) {
                return ResolutionResult.unknownId(key, "Unknown or empty Nexo item ID: '" + nativeId + "'");
            }
            return ResolutionResult.resolved(key, built.clone());
        } catch (RuntimeException | LinkageError failure) {
            fail("building '" + nativeId + "'", failure);
            return unavailable(key, ProviderStatus.FAILED);
        }
    }

    @Override
    public synchronized Optional<String> identifyItemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || status() != ProviderStatus.READY) return Optional.empty();
        try {
            // Inspection only: never refresh or rewrite inventory items or Allium PDC/state/signatures.
            String id = api.identify(stack);
            return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (RuntimeException | LinkageError failure) {
            fail("identifying native item", failure);
            return Optional.empty();
        }
    }
}
