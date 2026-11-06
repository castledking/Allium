package codes.castled.allium.item.provider;

import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers, version-checks, and reflectively instantiates isolated external item provider
 * adapters ({@code Nexo}, {@code CraftEngine}, {@code Oraxen}) without referencing any external
 * provider classes from eagerly loaded plugin code.
 *
 * <p>Guarantees that missing, disabled, or version-incompatible providers register a safe
 * {@link AbsentProviderAdapter} ({@link ProviderStatus#ABSENT}) with an explicit diagnostic and
 * never throw {@link NoClassDefFoundError}.
 */
public final class ProviderAdapterBootstrap {

    private static final Pattern LEADING_VERSION_NUMBERS = Pattern.compile("^\\D*(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    public record ProviderSpec(
            String providerKey,
            String bukkitPluginName,
            String adapterClassName,
            String apiProbeClassName,
            String supportedVersionLine,
            int majorVersion,
            int minorVersion,
            Set<String> verifiedRuntimeVersions
    ) {
        public ProviderSpec {
            verifiedRuntimeVersions = Set.copyOf(verifiedRuntimeVersions);
        }

        public boolean isVersionSupported(@Nullable String rawVersion) {
            if (rawVersion != null && verifiedRuntimeVersions.contains(rawVersion.trim())) return true;
            int[] parsed = parseVersionNumbers(rawVersion);
            if (parsed == null) {
                return false;
            }
            // Version lines are deliberately narrow. Future versions require API/gate review,
            // rather than being accepted merely because their number is larger.
            return parsed[0] == majorVersion && parsed[1] == minorVersion;
        }
    }

    public static final ProviderSpec NEXO_SPEC = new ProviderSpec(
            ProviderKey.NEXO,
            "Nexo",
            "codes.castled.allium.item.provider.nexo.NexoProviderAdapter",
            "com.nexomc.nexo.api.NexoItems",
            "1.29.x (API pin 1.29.1), verified runtime 1.30-dev-36f4ce686",
            1, 29,
            Set.of("1.30-dev-36f4ce686")
    );

    public static final ProviderSpec CRAFTENGINE_SPEC = new ProviderSpec(
            ProviderKey.CRAFTENGINE,
            "CraftEngine",
            "codes.castled.allium.item.provider.craftengine.CraftEngineProviderAdapter",
            "net.momirealms.craftengine.bukkit.api.CraftEngineItems",
            "26.9.x (API pin 26.9.1)",
            26, 9,
            Set.of()
    );

    public static final ProviderSpec ORAXEN_SPEC = new ProviderSpec(
            ProviderKey.ORAXEN,
            "Oraxen",
            "codes.castled.allium.item.provider.oraxen.OraxenProviderAdapter",
            "io.th0rgal.oraxen.api.OraxenItems",
            "1.211.x (API pin 1.211.0), verified runtime 1.220.1",
            1, 211,
            Set.of("1.220.1")
    );

    public static final List<ProviderSpec> ALL_SPECS = List.of(NEXO_SPEC, CRAFTENGINE_SPEC, ORAXEN_SPEC);

    private ProviderAdapterBootstrap() {
    }

    /**
     * Registers all external provider adapters onto {@code itemService}, checking {@code pluginManager}
     * for presence, enabled state, supported version line, and API class availability.
     */
    public static void registerAll(
            Plugin hostPlugin,
            @Nullable PluginManager pluginManager,
            ItemService itemService,
            Consumer<String> infoLogger,
            Consumer<String> warningLogger
    ) {
        Objects.requireNonNull(hostPlugin, "hostPlugin");
        Objects.requireNonNull(itemService, "itemService");
        Objects.requireNonNull(infoLogger, "infoLogger");
        Objects.requireNonNull(warningLogger, "warningLogger");

        for (ProviderSpec spec : ALL_SPECS) {
            ItemProviderAdapter adapter = loadSingleProvider(
                    spec,
                    hostPlugin,
                    pluginManager,
                    itemService::resetDiagnosticsOnReload,
                    infoLogger,
                    warningLogger
            );
            itemService.registerProvider(adapter);
        }
    }

    public static ItemProviderAdapter loadSingleProvider(
            ProviderSpec spec,
            Plugin hostPlugin,
            @Nullable PluginManager pluginManager,
            Runnable reloadInvalidateCallback,
            Consumer<String> infoLogger,
            Consumer<String> warningLogger
    ) {
        Plugin providerPlugin = pluginManager == null ? null : pluginManager.getPlugin(spec.bukkitPluginName());
        if (providerPlugin == null) {
            String message = spec.bukkitPluginName() + " is not installed; provider '"
                    + spec.providerKey() + "' is ABSENT (optional).";
            infoLogger.accept(message);
            return new AbsentProviderAdapter(spec.providerKey(), message);
        }

        if (!providerPlugin.isEnabled()) {
            String msg = spec.bukkitPluginName() + " plugin is present but not enabled; registering provider '"
                    + spec.providerKey() + "' as ABSENT.";
            warningLogger.accept(msg);
            return new AbsentProviderAdapter(spec.providerKey(), msg);
        }

        String rawVersion = providerPlugin.getDescription() != null
                ? providerPlugin.getDescription().getVersion()
                : null;
        if (!spec.isVersionSupported(rawVersion)) {
            String msg = spec.bukkitPluginName() + " version '" + rawVersion
                    + "' is incompatible (verified API line is " + spec.supportedVersionLine()
                    + "); registering provider '" + spec.providerKey() + "' as ABSENT.";
            warningLogger.accept(msg);
            return new AbsentProviderAdapter(spec.providerKey(), msg);
        }

        // Probe the provider API class using the provider plugin's classloader before loading the adapter
        try {
            Class.forName(spec.apiProbeClassName(), false, providerPlugin.getClass().getClassLoader());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException t) {
            String msg = spec.bukkitPluginName() + " (" + rawVersion + ") is enabled, but API class '"
                    + spec.apiProbeClassName() + "' could not be loaded ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "); registering provider '" + spec.providerKey() + "' as ABSENT.";
            warningLogger.accept(msg);
            return new AbsentProviderAdapter(spec.providerKey(), msg);
        }

        try {
            Class<?> adapterClass = Class.forName(
                    spec.adapterClassName(),
                    true,
                    hostPlugin.getClass().getClassLoader()
            );
            Constructor<?> constructor = adapterClass.getConstructor(
                    Plugin.class,
                    String.class,
                    Consumer.class,
                    Consumer.class,
                    Runnable.class
            );
            ItemProviderAdapter adapter = (ItemProviderAdapter) constructor.newInstance(
                    hostPlugin,
                    rawVersion,
                    infoLogger,
                    warningLogger,
                    reloadInvalidateCallback
            );
            Method initMethod = adapterClass.getMethod("initialize", PluginManager.class);
            initMethod.invoke(adapter, pluginManager);
            return adapter;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String msg = "Failed to initialize " + spec.bukkitPluginName() + " (" + rawVersion
                    + ") provider adapter (" + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + "); registering provider '" + spec.providerKey() + "' as ABSENT.";
            warningLogger.accept(msg);
            return new AbsentProviderAdapter(spec.providerKey(), msg);
        }
    }

    static int @Nullable [] parseVersionNumbers(@Nullable String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return null;
        }
        Matcher matcher = LEADING_VERSION_NUMBERS.matcher(rawVersion.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            return new int[]{major, minor, patch};
        } catch (NumberFormatException invalidVersion) {
            return null;
        }
    }

    /**
     * Safe fallback {@link ItemProviderAdapter} that always reports {@link ProviderStatus#ABSENT}
     * and never touches any external provider classes.
     */
    public static final class AbsentProviderAdapter implements ItemProviderAdapter {

        private final String providerName;
        private final String reason;

        public AbsentProviderAdapter(String providerName, String reason) {
            this.providerName = Objects.requireNonNull(providerName, "providerName").trim().toLowerCase(Locale.ROOT);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public String reason() {
            return reason;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public ProviderStatus status() {
            return ProviderStatus.ABSENT;
        }

        @Override
        public ResolutionResult createItem(String id) {
            return ResolutionResult.providerAbsent(ProviderKey.of(providerName, id), reason);
        }

        @Override
        public Optional<String> identifyItemId(ItemStack stack) {
            return Optional.empty();
        }
    }
}
