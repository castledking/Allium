package codes.castled.allium.pack.delivery;

import codes.castled.allium.pack.PackMerger;
import codes.castled.allium.pack.ResourcePackSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Discovers resource packs bundled inside addon jars in {@code plugins/Allium/addons/} during
 * {@code onLoad()} and {@code /allium reload}, without loading addon classes.
 *
 * <p>An addon opts in by declaring {@code allium-pack: <jar-entry>} in its {@code plugin.yml}.
 * Its delivery mode is read from key {@code resource-pack.delivery} in the addon's own data folder,
 * {@code plugins/<Name>/config.yml} (Bukkit gives a plugin that folder wherever its jar lives),
 * then {@code plugins/Allium/addons/<Name>/config.yml}, then the jar's bundled {@code config.yml},
 * and finally defaults to {@code allium}:
 * <ul>
 *   <li>{@code allium} — merged into Allium's core pack before standalone export/self-host or
 *       ResourcePackManager mixer export.</li>
 *   <li>{@code rspm} — exported as {@code <Name>_resource_pack.zip} directly into
 *       ResourcePackManager's mixer directory, regardless of Allium's own pack owner.</li>
 *   <li>{@code none} — skipped; the server owner hosts the addon's pack manually.</li>
 * </ul>
 */
public final class AddonPacks {

    public enum Mode {
        ALLIUM, RSPM, NONE;

        static Mode parse(@Nullable String raw, String addonName, PackExport.Logs logs) {
            if (raw == null || raw.isBlank()) {
                return ALLIUM;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "allium" -> ALLIUM;
                case "rspm", "rpm", "rpm-export" -> RSPM;
                case "none" -> NONE;
                default -> {
                    logs.warning().accept("Addon " + addonName + " has invalid resource-pack.delivery '" + raw
                            + "' (expected allium, rspm, or none); defaulting to allium.");
                    yield ALLIUM;
                }
            };
        }
    }

    public record Discovered(String addonName, Mode mode, byte[] zipBytes) {
    }

    public record RspmExportResult(
            PackExport.ExportStatus status,
            java.util.Map<Path, String> exported,
            List<Path> removedStale
    ) {
        public RspmExportResult {
            exported = exported == null ? java.util.Map.of() : java.util.Map.copyOf(exported);
            removedStale = removedStale == null ? List.of() : List.copyOf(removedStale);
        }
    }

    private AddonPacks() {
    }

    /** Scans {@code <dataFolder>/addons/*.jar} in sorted order for {@code allium-pack} contributions. */
    public static List<Discovered> discover(Path dataFolder, PackExport.Logs logs) {
        return discover(dataFolder, null, logs);
    }

    /**
     * Scans {@code <dataFolder>/addons/*.jar} in sorted order for {@code allium-pack} contributions,
     * skipping addons disabled under {@code addons.enabled.*} in {@code config}.
     */
    public static List<Discovered> discover(Path dataFolder,
                                            @Nullable org.bukkit.configuration.file.FileConfiguration config,
                                            PackExport.Logs logs) {
        if (dataFolder == null) {
            return List.of();
        }
        Path addonsDir = dataFolder.resolve("addons");
        if (!Files.isDirectory(addonsDir)) {
            return List.of();
        }
        List<Path> jars;
        try (Stream<Path> entries = Files.list(addonsDir)) {
            jars = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            logs.warning().accept("Could not scan " + addonsDir + " for addon resource packs: " + e.getMessage());
            return List.of();
        }

        List<Discovered> found = new ArrayList<>();
        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                YamlConfiguration pluginYml = readYamlEntry(jar, "plugin.yml");
                if (pluginYml == null) continue;
                String name = trimToNull(pluginYml.getString("name"));
                String packResource = trimToNull(pluginYml.getString("allium-pack"));
                if (name == null || packResource == null) continue;
                if (config != null && !codes.castled.allium.addon.AddonCatalog.isEnabledInConfig(config, name)) {
                    continue;
                }

                Mode mode = readDeliveryMode(dataFolder.toAbsolutePath().getParent(), addonsDir, jar, name, logs);
                if (mode == Mode.NONE) {
                    logs.info().accept("Addon " + name + " resource pack delivery is set to none; skipping.");
                    continue;
                }
                JarEntry packEntry = jar.getJarEntry(packResource);
                if (packEntry == null || packEntry.isDirectory()) {
                    logs.warning().accept("Addon " + name + " declares allium-pack: '" + packResource
                            + "' but that entry is missing from " + jarPath.getFileName() + "; skipping.");
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(packEntry)) {
                    bytes = in.readAllBytes();
                }
                if (bytes.length == 0) {
                    logs.warning().accept("Addon " + name + " pack entry '" + packResource + "' is empty; skipping.");
                    continue;
                }
                found.add(new Discovered(name, mode, bytes));
            } catch (IOException e) {
                logs.warning().accept("Could not inspect addon jar " + jarPath.getFileName() + " for resource packs: " + e.getMessage());
            }
        }
        return List.copyOf(found);
    }

    /** Merges every {@link Mode#ALLIUM} contribution into {@code base}, returning a new {@link PackSource}. */
    public static PackSource mergeIntoBase(PackSource base, List<Discovered> discovered, PackExport.Logs logs) {
        List<PackMerger.Contribution> contributions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Discovered d : discovered) {
            if (d.mode() == Mode.ALLIUM) {
                contributions.add(new PackMerger.Contribution(d.addonName(), d.zipBytes()));
                names.add(d.addonName());
            }
        }
        if (contributions.isEmpty()) {
            return base;
        }
        try {
            PackMerger.Result merged = PackMerger.merge(base.bytes(), contributions);
            for (String warning : merged.warnings()) {
                logs.warning().accept(warning);
            }
            String sha1 = PackExport.sha1(merged.bytes());
            String sourceLabel = base.source() + " + " + String.join(", ", names);
            logs.info().accept("Merged addon resource pack(s) [" + String.join(", ", names)
                    + "] into Allium's core pack (" + merged.bytes().length + " bytes, SHA-1 " + sha1 + ").");
            return new PackSource(merged.bytes(), sha1, sourceLabel, base.override());
        } catch (IOException e) {
            logs.warning().accept("Could not merge addon resource pack(s) " + names + ": " + e.getMessage()
                    + "; delivering base pack only.");
            return base;
        }
    }

    /** Exports every {@link Mode#RSPM} contribution into ResourcePackManager's mixer directory and reconciles stale addon exports. */
    public static RspmExportResult exportRspm(Path pluginsFolder, ResourcePackSettings settings, List<Discovered> discovered, PackExport.Logs logs) {
        return exportRspm(pluginsFolder.resolve("Allium"), pluginsFolder, settings, discovered, logs);
    }

    public static RspmExportResult exportRspm(Path dataFolder, Path pluginsFolder, ResourcePackSettings settings,
                                              List<Discovered> discovered, PackExport.Logs logs) {
        List<Discovered> rspmPacks = discovered.stream().filter(d -> d.mode() == Mode.RSPM).toList();
        if (rspmPacks.isEmpty()) {
            List<Path> removed = PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_ADDON, java.util.Map.of(), settings.dryRun(), logs);
            return new RspmExportResult(settings.dryRun() ? PackExport.ExportStatus.DRY_RUN : PackExport.ExportStatus.UNCHANGED,
                    java.util.Map.of(), removed);
        }
        Path configured = Path.of(settings.mixerDirectory());
        Path mixer = configured.isAbsolute() ? configured : pluginsFolder.resolve(configured);
        String prefix = settings.dryRun() ? "[rspm dry run] " : "";
        if (!Files.isDirectory(mixer)) {
            logs.warning().accept(prefix + "ResourcePackManager's mixer directory " + mixer
                    + " does not exist; could not export addon pack(s). Install ResourcePackManager or set resource-pack.rpm-export.mixer-directory.");
            return new RspmExportResult(PackExport.ExportStatus.MISSING_DIRECTORY, java.util.Map.of(), List.of());
        }
        java.util.Map<Path, String> exported = new java.util.LinkedHashMap<>();
        boolean anyWritten = false;
        boolean anyFailed = false;
        for (Discovered d : rspmPacks) {
            String safeName = d.addonName().replaceAll("[^A-Za-z0-9._-]", "_");
            Path target = mixer.resolve(safeName + "_resource_pack.zip");
            String sha1 = PackExport.sha1(d.zipBytes());
            try {
                if (settings.dryRun()) {
                    logs.info().accept(prefix + "Would write addon " + d.addonName() + " pack to " + target
                            + " (" + d.zipBytes().length + " bytes, SHA-1 " + sha1 + "). Nothing was written.");
                    exported.put(target, sha1);
                    continue;
                }
                boolean changed = PackExport.writeIfDifferent(target, d.zipBytes());
                if (changed) anyWritten = true;
                exported.put(target, sha1);
                logs.info().accept((changed ? "Wrote" : "Kept") + " addon " + d.addonName()
                        + " resource pack in ResourcePackManager's mixer: " + target
                        + " (" + d.zipBytes().length + " bytes, SHA-1 " + sha1 + ").");
            } catch (IOException e) {
                anyFailed = true;
                logs.warning().accept("Could not write addon " + d.addonName() + " pack to " + target + ": " + e.getMessage());
            }
        }
        if (settings.dryRun()) {
            PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_ADDON, exported, true, logs);
            return new RspmExportResult(PackExport.ExportStatus.DRY_RUN, exported, List.of());
        }
        List<Path> removed = anyFailed
                ? List.of()
                : PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_ADDON, exported, false, logs);
        return new RspmExportResult(anyWritten ? PackExport.ExportStatus.WRITTEN : PackExport.ExportStatus.UNCHANGED, exported, removed);
    }

    private static Mode readDeliveryMode(@Nullable Path pluginsDir, Path addonsDir, JarFile jar, String addonName,
                                         PackExport.Logs logs) {
        List<Path> externalConfigs = new ArrayList<>();
        if (pluginsDir != null) externalConfigs.add(pluginsDir.resolve(addonName).resolve("config.yml"));
        externalConfigs.add(addonsDir.resolve(addonName).resolve("config.yml"));
        for (Path externalConfig : externalConfigs) {
            if (!Files.isRegularFile(externalConfig)) continue;
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(externalConfig.toFile());
                if (cfg.contains("resource-pack.delivery")) {
                    return Mode.parse(cfg.getString("resource-pack.delivery"), addonName, logs);
                }
            } catch (RuntimeException e) {
                logs.warning().accept("Could not read " + externalConfig + ": " + e.getMessage());
            }
        }
        YamlConfiguration bundled = readYamlEntry(jar, "config.yml");
        if (bundled != null && bundled.contains("resource-pack.delivery")) {
            return Mode.parse(bundled.getString("resource-pack.delivery"), addonName, logs);
        }
        return Mode.ALLIUM;
    }

    @Nullable
    private static YamlConfiguration readYamlEntry(JarFile jar, String entryName) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null || entry.isDirectory()) return null;
        try (InputStream in = jar.getInputStream(entry)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
