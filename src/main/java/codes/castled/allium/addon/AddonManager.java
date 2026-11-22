package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.config.YamlFiles;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Downloads, installs, and toggles official Allium addons under {@code plugins/Allium/addons/}.
 */
public final class AddonManager {

    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;

    @FunctionalInterface
    public interface Downloader {
        void download(String url, Path targetFile) throws IOException;
    }

    public record InstalledAddon(String key, String name, String version, Path jarPath) {}

    public record DownloadOutcome(boolean success, @Nullable Path jarPath, String message) {}

    private final PluginStart plugin;
    private final Downloader downloader;
    private final java.util.concurrent.Executor io;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AddonManager(PluginStart plugin) {
        this(plugin, defaultDownloader(), plugin.getScheduler().boundedPool("Allium-Addons", 1, 16));
    }

    AddonManager(PluginStart plugin, Downloader downloader, java.util.concurrent.Executor io) {
        this.plugin = plugin;
        this.downloader = downloader;
        this.io = io;
    }

    public static Downloader defaultDownloader() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        return (url, targetFile) -> {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                throw new IOException("Unsupported download URL scheme: " + scheme);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Allium-AddonManager/1.0")
                    .header("Accept", "application/octet-stream")
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", interrupted);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) {
                    // drain/close body
                }
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            try (InputStream in = response.body(); var out = Files.newOutputStream(targetFile)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Addon jar exceeded maximum size (" + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + " MiB)");
                    }
                    out.write(buffer, 0, read);
                }
                if (total == 0) {
                    throw new IOException("Downloaded file was empty");
                }
            }
        };
    }

    /** Scans {@code plugins/Allium/addons/*.jar} and indexes valid addon jars by canonical config key. */
    public static Map<String, InstalledAddon> scanInstalled(@Nullable Path addonsDir) {
        Map<String, InstalledAddon> found = new LinkedHashMap<>();
        if (addonsDir == null || !Files.isDirectory(addonsDir)) return found;
        List<Path> jars;
        try (Stream<Path> entries = Files.list(addonsDir)) {
            jars = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException ignored) {
            return found;
        }
        for (Path jar : jars) {
            YamlConfiguration meta = readPluginYml(jar);
            if (meta == null) continue;
            String name = trimToNull(meta.getString("name"));
            String api = trimToNull(meta.getString("allium-api"));
            if (name == null || api == null) continue;
            String key = AddonCatalog.configKey(name);
            String ver = trimToNull(meta.getString("version"));
            found.putIfAbsent(key, new InstalledAddon(key, name, ver == null ? "?" : ver, jar));
        }
        return found;
    }

    public Path addonsDir() {
        return plugin.getDataFolder().toPath().resolve("addons");
    }

    public Map<String, InstalledAddon> installed() {
        return scanInstalled(addonsDir());
    }

    /** Official addon keys that are NOT currently installed in {@code plugins/Allium/addons/}. */
    public List<String> nonInstalledOfficialKeys() {
        Map<String, InstalledAddon> present = installed();
        List<String> out = new ArrayList<>();
        for (AddonCatalog.Entry entry : AddonCatalog.official()) {
            if (!present.containsKey(entry.key())) {
                out.add(entry.key());
            }
        }
        return out;
    }

    /** Tab-completion candidates for {@code /allium addon enable|disable <addon>}. */
    public List<String> toggleCandidates(boolean enable) {
        FileConfiguration config = plugin.getConfig();
        Map<String, InstalledAddon> present = installed();
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (AddonCatalog.Entry entry : AddonCatalog.official()) {
            keys.put(entry.key(), AddonCatalog.isEnabledInConfig(config, entry.displayName()));
        }
        for (InstalledAddon addon : present.values()) {
            keys.putIfAbsent(addon.key(), AddonCatalog.isEnabledInConfig(config, addon.name()));
        }
        List<String> out = new ArrayList<>();
        for (var e : keys.entrySet()) {
            if (enable ? !e.getValue() : e.getValue()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Startup auto-downloader: when {@code addons.auto-download: true}, downloads any official addon
     * explicitly enabled in {@code addons.enabled.*} whose jar is not yet present in {@code addons/}.
     */
    public static int ensureAutoDownloaded(
            Path dataFolder,
            @Nullable FileConfiguration config,
            String pluginVersion,
            Downloader downloader,
            Consumer<String> infoLog,
            Consumer<String> warnLog
    ) {
        if (dataFolder == null || config == null || !config.getBoolean("addons.auto-download", false)) {
            return 0;
        }
        Path addonsDir = dataFolder.resolve("addons");
        Map<String, InstalledAddon> present = scanInstalled(addonsDir);
        String baseUrl = config.getString("addons.release-base-url", AddonCatalog.DEFAULT_RELEASE_BASE_URL);
        String version = AddonCatalog.releaseVersion(pluginVersion, config.getString("addons.release-version"));
        int downloaded = 0;
        for (AddonCatalog.Entry entry : AddonCatalog.official()) {
            if (!AddonCatalog.isExplicitlyEnabled(config, entry)) continue;
            if (present.containsKey(entry.key())) continue;
            String url = AddonCatalog.downloadUrl(baseUrl, version, entry);
            infoLog.accept("Auto-downloading official addon " + entry.displayName() + " from " + url + "...");
            DownloadOutcome outcome = downloadAndInstallSync(addonsDir, entry, version, url, downloader);
            if (outcome.success()) {
                downloaded++;
                infoLog.accept("Installed " + entry.displayName() + " -> " + addonsDir.getFileName() + "/" + outcome.jarPath().getFileName());
            } else {
                warnLog.accept("Could not auto-download addon " + entry.displayName() + ": " + outcome.message());
            }
        }
        return downloaded;
    }

    public int ensureAutoDownloaded() {
        return ensureAutoDownloaded(
                plugin.getDataFolder().toPath(),
                plugin.getConfig(),
                plugin.getDescription().getVersion(),
                downloader,
                msg -> plugin.logger().info(msg),
                msg -> plugin.logger().warning(msg)
        );
    }

    /** Downloads, validates as a compatible Allium addon jar, and atomically installs into {@code addonsDir}. */
    static DownloadOutcome downloadAndInstallSync(
            Path addonsDir,
            AddonCatalog.Entry entry,
            String version,
            String url,
            Downloader downloader
    ) {
        try {
            Files.createDirectories(addonsDir);
        } catch (IOException e) {
            return new DownloadOutcome(false, null, "could not create addons directory: " + e.getMessage());
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(addonsDir, ".addon-download-", ".tmp");
            downloader.download(url, temp);
            YamlConfiguration meta = readPluginYml(temp);
            if (meta == null) {
                return new DownloadOutcome(false, null, "downloaded file is not a valid plugin jar (missing plugin.yml)");
            }
            String foundName = trimToNull(meta.getString("name"));
            if (foundName == null || !foundName.equalsIgnoreCase(entry.displayName())) {
                return new DownloadOutcome(false, null, "downloaded jar declares plugin '" + foundName
                        + "', expected '" + entry.displayName() + "'");
            }
            String declaredApi = trimToNull(meta.getString("allium-api"));
            if (declaredApi == null) {
                return new DownloadOutcome(false, null, "downloaded jar is missing allium-api in plugin.yml");
            }
            AddonVerdict verdict = AddonVerdict.evaluate(foundName, declaredApi, AlliumApiService.API_VERSION, List.of(), false);
            if (verdict.refused()) {
                return new DownloadOutcome(false, null, verdict.message());
            }
            Path target = addonsDir.resolve(entry.jarFileName(version));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            return new DownloadOutcome(true, target, "installed " + target.getFileName());
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new DownloadOutcome(false, null, reason);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    /** Handles {@code /allium addon download <addon>} asynchronously off the server thread. */
    public void downloadCommand(CommandSender sender, String rawAddon) {
        Optional<AddonCatalog.Entry> found = AddonCatalog.find(rawAddon);
        if (found.isEmpty()) {
            plugin.getMessageManager().send(sender, Messages.ADDON_UNKNOWN, "{addon}", rawAddon);
            return;
        }
        AddonCatalog.Entry entry = found.get();
        Map<String, InstalledAddon> present = installed();
        if (present.containsKey(entry.key())) {
            InstalledAddon existing = present.get(entry.key());
            boolean enabled = AddonCatalog.isEnabledInConfig(plugin.getConfig(), entry.displayName());
            if (enabled) {
                plugin.getMessageManager().send(sender, Messages.ADDON_ALREADY_INSTALLED,
                        "{addon}", entry.displayName(), "{file}", existing.jarPath().getFileName().toString());
            } else {
                plugin.getMessageManager().send(sender, Messages.ADDON_ALREADY_INSTALLED_DISABLED,
                        "{addon}", entry.displayName(), "{key}", entry.key());
            }
            return;
        }
        if (!inFlight.add(entry.key())) {
            plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_IN_PROGRESS, "{addon}", entry.displayName());
            return;
        }
        String baseUrl = plugin.getConfig().getString("addons.release-base-url", AddonCatalog.DEFAULT_RELEASE_BASE_URL);
        String version = AddonCatalog.releaseVersion(
                plugin.getDescription().getVersion(),
                plugin.getConfig().getString("addons.release-version")
        );
        String url = AddonCatalog.downloadUrl(baseUrl, version, entry);
        Path dir = addonsDir();
        plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_STARTED,
                "{addon}", entry.displayName(), "{version}", version);
        try {
            io.execute(() -> {
                DownloadOutcome outcome;
                try {
                    outcome = downloadAndInstallSync(dir, entry, version, url, downloader);
                } finally {
                    inFlight.remove(entry.key());
                }
                plugin.getScheduler().runOnServer(() -> {
                    if (!outcome.success()) {
                        plugin.logger().warning("Addon download failed for " + entry.displayName() + ": " + outcome.message());
                        plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_FAILED,
                                "{addon}", entry.displayName(), "{reason}", outcome.message());
                        return;
                    }
                    boolean saved = persistEnabledFlag(entry.key(), true);
                    if (!saved) {
                        plugin.logger().warning("Downloaded " + entry.displayName() + " but could not save config.yml.");
                    }
                    plugin.logger().info("Downloaded addon " + entry.displayName() + " (" + outcome.jarPath().getFileName()
                            + ") and enabled addons.enabled." + entry.key() + " in config.yml. Restart the server to load it.");
                    plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_COMPLETE,
                            "{addon}", entry.displayName(),
                            "{file}", outcome.jarPath().getFileName().toString(),
                            "{key}", entry.key());
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            inFlight.remove(entry.key());
            plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_FAILED,
                    "{addon}", entry.displayName(), "{reason}", "download queue is busy");
        }
    }

    /** Handles {@code /allium addon enable|disable <addon>}. */
    public void toggleCommand(CommandSender sender, String rawAddon, boolean enable) {
        Map<String, InstalledAddon> present = installed();
        Optional<AddonCatalog.Entry> official = AddonCatalog.find(rawAddon);
        String key;
        String displayName;
        if (official.isPresent()) {
            key = official.get().key();
            displayName = official.get().displayName();
        } else {
            String candidateKey = rawAddon.trim().toLowerCase(Locale.ROOT);
            InstalledAddon custom = present.get(candidateKey);
            if (custom == null) {
                plugin.getMessageManager().send(sender, Messages.ADDON_UNKNOWN, "{addon}", rawAddon);
                return;
            }
            key = custom.key();
            displayName = custom.name();
        }

        boolean current = AddonCatalog.isEnabledInConfig(plugin.getConfig(), displayName);
        boolean isInstalled = present.containsKey(key);
        if (current == enable) {
            if (enable && !isInstalled) {
                plugin.getMessageManager().send(sender, Messages.ADDON_ENABLED_NOT_INSTALLED,
                        "{addon}", displayName, "{key}", key);
            } else {
                plugin.getMessageManager().send(sender, enable ? Messages.ADDON_ALREADY_ENABLED : Messages.ADDON_ALREADY_DISABLED,
                        "{addon}", displayName);
            }
            return;
        }

        if (!persistEnabledFlag(key, enable)) {
            plugin.getMessageManager().send(sender, Messages.ADDON_CONFIG_SAVE_FAILED, "{addon}", displayName);
            return;
        }

        if (enable) {
            if (isInstalled) {
                plugin.getMessageManager().send(sender, Messages.ADDON_ENABLED_RESTART,
                        "{addon}", displayName, "{key}", key);
            } else {
                plugin.getMessageManager().send(sender, Messages.ADDON_ENABLED_NOT_INSTALLED,
                        "{addon}", displayName, "{key}", key);
            }
        } else {
            plugin.getMessageManager().send(sender, Messages.ADDON_DISABLED_RESTART,
                    "{addon}", displayName, "{key}", key);
        }
    }

    /** Handles {@code /allium addon list}. */
    public void listCommand(CommandSender sender) {
        Map<String, InstalledAddon> present = installed();
        FileConfiguration config = plugin.getConfig();
        AlliumApiService api = plugin.getAddonApi();
        plugin.getMessageManager().send(sender, Messages.ADDON_LIST_HEADER);
        for (AddonCatalog.Entry entry : AddonCatalog.official()) {
            InstalledAddon jar = present.get(entry.key());
            boolean enabled = AddonCatalog.isEnabledInConfig(config, entry.displayName());
            boolean running = api != null && api.isLoaded(entry.displayName());
            String status = statusLabel(jar != null, enabled, running);
            plugin.getMessageManager().send(sender, Messages.ADDON_LIST_ENTRY,
                    "{addon}", entry.displayName(),
                    "{key}", entry.key(),
                    "{status}", status,
                    "{description}", entry.description());
        }
        for (InstalledAddon addon : present.values()) {
            if (AddonCatalog.find(addon.key()).isPresent()) continue;
            boolean enabled = AddonCatalog.isEnabledInConfig(config, addon.name());
            boolean running = api != null && api.isLoaded(addon.name());
            plugin.getMessageManager().send(sender, Messages.ADDON_LIST_ENTRY,
                    "{addon}", addon.name(),
                    "{key}", addon.key(),
                    "{status}", statusLabel(true, enabled, running),
                    "{description}", addon.jarPath().getFileName().toString());
        }
    }

    private static String statusLabel(boolean installed, boolean enabled, boolean running) {
        if (running) return enabled ? "&aenabled (running)" : "&edisabling after restart";
        if (installed && enabled) return "&eenabled (restart to load)";
        if (installed) return "&cdisabled (jar installed)";
        return enabled ? "&7not installed (enabled in config)" : "&7not installed";
    }

    boolean persistEnabledFlag(String key, boolean enabled) {
        plugin.getConfig().set("addons.enabled." + key, enabled);
        Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            if (Files.isRegularFile(configPath)) {
                yaml.load(configPath.toFile());
            } else {
                yaml.loadFromString(plugin.getConfig().saveToString());
            }
            yaml.set("addons.enabled." + key, enabled);
            Files.createDirectories(configPath.getParent());
            YamlFiles.replace(configPath, yaml);
            return true;
        } catch (Exception e) {
            plugin.logger().warning("Could not save addons.enabled." + key + " to config.yml: " + e.getMessage());
            return false;
        }
    }

    @Nullable
    static YamlConfiguration readPluginYml(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
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
