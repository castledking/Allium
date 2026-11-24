package codes.castled.allium.addon;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Single validated inventory of addon jars under {@code plugins/Allium/addons/}.
 *
 * <p>Shared by runtime addon loading ({@link AlliumApiService}), CLI/install tooling
 * ({@link AddonManager}), and resource-pack assembly ({@code AddonPacks} / {@code PackGeneration})
 * so pack discovery never includes an incompatible, shaded, corrupt, or duplicate addon jar that
 * runtime validation will refuse.
 */
public final class AddonInventory {

    public enum RejectionReason {
        CORRUPT,
        INVALID_DESCRIPTOR,
        NOT_AN_ADDON,
        INCOMPATIBLE_API,
        SHADED_API,
        MISSING_PACK,
        DUPLICATE
    }

    public record ValidatedAddon(
            String key,
            String name,
            String version,
            Path jarPath,
            AlliumApiService.JarPreflight preflight,
            boolean enabledInConfig,
            @Nullable String packResource
    ) {
        public ValidatedAddon {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(jarPath, "jarPath");
            Objects.requireNonNull(preflight, "preflight");
        }
    }

    public record RejectedAddon(
            Path jarPath,
            @Nullable String name,
            RejectionReason reason,
            String detail,
            @Nullable AddonVerdict verdict
    ) {
        public RejectedAddon {
            Objects.requireNonNull(jarPath, "jarPath");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private final @Nullable Path addonsDir;
    private final List<ValidatedAddon> accepted;
    private final List<RejectedAddon> rejected;
    private final Map<String, ValidatedAddon> byKey;

    private AddonInventory(
            @Nullable Path addonsDir,
            List<ValidatedAddon> accepted,
            List<RejectedAddon> rejected
    ) {
        this.addonsDir = addonsDir;
        this.accepted = List.copyOf(accepted);
        this.rejected = List.copyOf(rejected);
        Map<String, ValidatedAddon> index = new LinkedHashMap<>();
        for (ValidatedAddon addon : accepted) {
            index.put(addon.key(), addon);
        }
        this.byKey = Collections.unmodifiableMap(index);
    }

    public static AddonInventory empty(@Nullable Path addonsDir) {
        return new AddonInventory(addonsDir, List.of(), List.of());
    }

    /**
     * Scans {@code addonsDir} in deterministic filename order, validates every jar with
     * {@link AlliumApiService#preflightJar(Path)}, rejects duplicates and invalid jars, and returns
     * accepted addons in topological dependency order.
     */
    public static AddonInventory scan(@Nullable Path addonsDir, @Nullable FileConfiguration config) {
        if (addonsDir == null || !Files.isDirectory(addonsDir)) {
            return empty(addonsDir);
        }
        List<Path> jars;
        try (Stream<Path> entries = Files.list(addonsDir)) {
            jars = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            return empty(addonsDir);
        }
        if (jars.isEmpty()) {
            return empty(addonsDir);
        }

        Map<Path, ValidatedAddon> acceptedByPath = new LinkedHashMap<>();
        Map<Path, YamlConfiguration> acceptedMeta = new LinkedHashMap<>();
        Map<String, Path> claimedByKey = new LinkedHashMap<>();
        List<RejectedAddon> rejected = new ArrayList<>();

        for (Path jar : jars) {
            AlliumApiService.JarPreflight preflight = AlliumApiService.preflightJar(jar);
            if (!preflight.accepted()) {
                rejected.add(toRejected(jar, preflight));
                continue;
            }
            String name = preflight.name();
            if (name == null) {
                rejected.add(new RejectedAddon(jar, null, RejectionReason.INVALID_DESCRIPTOR,
                        "plugin.yml has no name", preflight.verdict()));
                continue;
            }
            String key = AddonCatalog.configKey(name);
            Path existing = claimedByKey.putIfAbsent(key, jar);
            if (existing != null) {
                rejected.add(new RejectedAddon(
                        jar,
                        name,
                        RejectionReason.DUPLICATE,
                        "duplicate addon jar for " + name + " (already provided by " + existing.getFileName() + ")",
                        preflight.verdict()
                ));
                continue;
            }
            String version = preflight.version() == null ? "?" : preflight.version();
            boolean enabled = config == null || AddonCatalog.isEnabledInConfig(config, name);
            String packResource = preflight.meta() == null ? null : trimToNull(preflight.meta().getString("allium-pack"));
            acceptedByPath.put(jar, new ValidatedAddon(key, name, version, jar, preflight, enabled, packResource));
            acceptedMeta.put(jar, preflight.meta());
        }

        List<ValidatedAddon> ordered = new ArrayList<>(acceptedByPath.size());
        for (Path path : AlliumApiService.orderAddons(acceptedMeta)) {
            ValidatedAddon addon = acceptedByPath.get(path);
            if (addon != null) {
                ordered.add(addon);
            }
        }
        return new AddonInventory(addonsDir, ordered, rejected);
    }

    private static RejectedAddon toRejected(Path jar, AlliumApiService.JarPreflight preflight) {
        String detail = preflight.error() != null ? preflight.error() : "addon jar failed preflight";
        AddonVerdict verdict = preflight.verdict();
        RejectionReason reason;
        if (verdict != null) {
            reason = switch (verdict.status()) {
                case NOT_AN_ADDON -> RejectionReason.NOT_AN_ADDON;
                case SHADED_API -> RejectionReason.SHADED_API;
                case VERSION_MISMATCH, INVALID_DECLARATION, WRONG_DIRECTORY -> RejectionReason.INCOMPATIBLE_API;
                case ACCEPTED -> detail.contains("allium-pack") ? RejectionReason.MISSING_PACK : RejectionReason.INVALID_DESCRIPTOR;
            };
        } else if (detail.startsWith("corrupt or unreadable jar")) {
            reason = RejectionReason.CORRUPT;
        } else {
            reason = RejectionReason.INVALID_DESCRIPTOR;
        }
        return new RejectedAddon(jar, preflight.name(), reason, detail, verdict);
    }

    @Nullable
    public Path addonsDir() {
        return addonsDir;
    }

    /** All validated, non-duplicate addons in topological dependency order (including config-disabled ones). */
    public List<ValidatedAddon> accepted() {
        return accepted;
    }

    /** Validated, non-duplicate addons that are enabled in {@code addons.enabled.*}. */
    public List<ValidatedAddon> enabled() {
        return accepted.stream().filter(ValidatedAddon::enabledInConfig).toList();
    }

    /** Jars skipped due to corruption, invalid descriptor, incompatible/shaded API, missing pack, or duplicate ID. */
    public List<RejectedAddon> rejected() {
        return rejected;
    }

    /** Accepted addons indexed by canonical {@link AddonCatalog#configKey(String)}. */
    public Map<String, ValidatedAddon> byKey() {
        return byKey;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
