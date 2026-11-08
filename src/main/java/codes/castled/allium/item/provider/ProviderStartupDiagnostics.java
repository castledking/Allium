package codes.castled.allium.item.provider;

import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import codes.castled.allium.scheduler.TaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Startup warnings only: no vendor config edits, inventory repairs or provider reloads. */
public final class ProviderStartupDiagnostics {
    private final ItemService items;
    private final PluginManager plugins;
    private final Consumer<String> warningLogger;
    private final Set<String> inspectedProviders = new HashSet<>();
    private boolean migrationInspected;

    public ProviderStartupDiagnostics(ItemService items, PluginManager plugins, Consumer<String> warningLogger) {
        this.items = items;
        this.plugins = plugins;
        this.warningLogger = warningLogger;
    }

    public void install(TaskScheduler scheduler) {
        // Definitions/aliases registered during onEnable must be visible. CE can finish
        // asynchronously much later; a single next-tick check would miss its definitions.
        scheduler.repeatOnServer(() -> !inspect(), TaskScheduler.MILLIS_PER_TICK, 1_000L);
    }

    /** @return whether all startup inspections are complete; LOADING providers stay pending. */
    boolean inspect() {
        if (!migrationInspected) {
            migrationInspected = true;
            inspectMigration();
        }
        boolean complete = true;
        for (ProviderAdapterBootstrap.ProviderSpec spec : ProviderAdapterBootstrap.ALL_SPECS) {
            if (inspectedProviders.contains(spec.providerKey())) continue;
            ItemProviderAdapter adapter = items.getProvider(spec.providerKey()).orElse(null);
            if (!(adapter instanceof ProviderConfigurationDiagnostics diagnostics)) {
                inspectedProviders.add(spec.providerKey());
                continue;
            }
            ProviderStatus status = adapter.status();
            if (status == ProviderStatus.LOADING) {
                complete = false;
                continue;
            }
            if (status == ProviderStatus.READY) {
                // Snapshot now, after readiness, rather than when the task was installed.
                Set<String> managed = items.appearanceBindings().keySet().stream()
                        .filter(key -> key.provider().equals(spec.providerKey()))
                        .map(ProviderKey::id).collect(Collectors.toUnmodifiableSet());
                try {
                    diagnostics.configurationWarnings(managed).forEach(warningLogger);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    warningLogger.accept(spec.bukkitPluginName() + " startup configuration inspection unavailable ("
                            + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                            + "); review docs/s05-provider-matrix.md. No provider configuration was changed.");
                }
            }
            inspectedProviders.add(spec.providerKey());
        }
        return complete;
    }

    private void inspectMigration() {
        Plugin nexo = plugins.getPlugin("Nexo");
        // The converter can rewrite persisted Oraxen identities even before Oraxen is READY.
        if (nexo == null || !nexo.isEnabled() || plugins.getPlugin("Oraxen") == null) return;
        try {
            migrationWarning(nexo.getDataFolder().toPath().resolve("converter.yml")).forEach(warningLogger);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            warningLogger.accept("Nexo + Oraxen are present, but Nexo converter.yml could not be inspected ("
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + "). Review oraxenConverter before boot to preserve native identities; no configuration was changed.");
        }
    }

    static List<String> migrationWarning(Path converterFile) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        // Nexo Converter.OraxenConverter(ConfigurationSection) defaults each switch to true,
        // including a missing section/file. Reading YAML avoids its lazy converter() getter,
        // whose initialization can reload/save provider configuration.
        if (Files.exists(converterFile)) config.load(converterFile.toFile());
        List<String> enabled = new ArrayList<>();
        for (String key : List.of("convertItems", "convertResourcePack", "convertSettings", "convertFurnitureOnLoad")) {
            if (config.getBoolean("oraxenConverter." + key, true)) enabled.add(key);
        }
        if (enabled.isEmpty()) return List.of();
        return List.of("Nexo + Oraxen are present with Nexo oraxenConverter enabled ("
                + String.join(", ", enabled) + "). Automatic migration can rewrite Oraxen native identities as Nexo; "
                + "Allium signatures do not authorize that changed identity. To preserve both providers, disable all four "
                + "oraxenConverter switches in plugins/Nexo/converter.yml before boot; see docs/s05-provider-matrix.md. "
                + "Allium has not changed provider configuration or re-signed items.");
    }
}
