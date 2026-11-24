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
    private final Map<String, String> bundledChecksums;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AddonManager(PluginStart plugin) {
        this(plugin, defaultDownloader(), plugin.getScheduler().boundedPool("Allium-Addons", 1, 16), AddonChecksums.loadBundled());
    }

    AddonManager(PluginStart plugin, Downloader downloader, java.util.concurrent.Executor io) {
        this(plugin, downloader, io, AddonChecksums.loadBundled());
    }

    AddonManager(PluginStart plugin, Downloader downloader, java.util.concurrent.Executor io, Map<String, String> bundledChecksums) {
        this.plugin = plugin;
        this.downloader = downloader;
        this.io = io;
        this.bundledChecksums = bundledChecksums == null ? Map.of() : Map.copyOf(bundledChecksums);
    }

    /** Validates that an addon download URL is a well-formed HTTPS URL with a non-blank host. */
    public static URI validateHttpsUrl(@Nullable String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("Addon download URL is blank");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException badUri) {
            throw new IOException("Invalid addon download URL: " + badUri.getMessage(), badUri);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Addon download URL must use HTTPS (got '" + scheme + "'): " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("Addon download URL has no host: " + url);
        }
        return uri;
    }

    public static Downloader defaultDownloader() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        return (url, targetFile) -> {
            URI uri = validateHttpsUrl(url);
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
            if (response.uri() != null) {
                try {
                    validateHttpsUrl(response.uri().toString());
                } catch (IOException downgrade) {
                    try (InputStream ignored = response.body()) {
                        // drain/close body
                    }
                    throw new IOException("Redirect left HTTPS: " + downgrade.getMessage(), downgrade);
                }
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

    /** Scans {@code plugins/Allium/addons/*.jar} via the shared {@link AddonInventory} and indexes valid addons by canonical config key. */
    public static Map<String, InstalledAddon> scanInstalled(@Nullable Path addonsDir) {
        Map<String, InstalledAddon> found = new LinkedHashMap<>();
        AddonInventory inventory = AddonInventory.scan(addonsDir, null);
        for (AddonInventory.ValidatedAddon addon : inventory.accepted()) {
            found.putIfAbsent(addon.key(), new InstalledAddon(addon.key(), addon.name(), addon.version(), addon.jarPath()));
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
        return ensureAutoDownloaded(dataFolder, config, pluginVersion, downloader, AddonChecksums.loadBundled(), infoLog, warnLog);
    }

    static int ensureAutoDownloaded(
            Path dataFolder,
            @Nullable FileConfiguration config,
            String pluginVersion,
            Downloader downloader,
            Map<String, String> bundledChecksums,
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
            DownloadOutcome outcome;
            try {
                String pinnedSha256 = AddonChecksums.configuredPin(config, entry, version).orElse(null);
                outcome = downloadAndInstallSync(addonsDir, entry, version, baseUrl, url, pinnedSha256, bundledChecksums, downloader);
            } catch (IOException badPin) {
                outcome = new DownloadOutcome(false, null, badPin.getMessage());
            }
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
                bundledChecksums,
                msg -> plugin.logger().info(msg),
                msg -> plugin.logger().warning(msg)
        );
    }

    /**
     * Downloads, verifies SHA-256 digest and release identity, runs shared runtime preflight, and
     * atomically installs into {@code addonsDir}. On any failure, any existing jar is preserved.
     */
    static DownloadOutcome downloadAndInstallSync(
            Path addonsDir,
            AddonCatalog.Entry entry,
            String version,
            @Nullable String baseUrl,
            String url,
            @Nullable String pinnedSha256,
            Map<String, String> bundledChecksums,
            Downloader downloader
    ) {
        try {
            validateHttpsUrl(url);
        } catch (IOException insecure) {
            return new DownloadOutcome(false, null, insecure.getMessage());
        }
        try {
            Files.createDirectories(addonsDir);
        } catch (IOException e) {
            return new DownloadOutcome(false, null, "could not create addons directory: " + e.getMessage());
        }
        Path temp = null;
        try {
            String expectedSha256 = resolveExpectedDigest(addonsDir, entry, version, baseUrl, pinnedSha256, bundledChecksums, downloader);
            temp = Files.createTempFile(addonsDir, ".addon-download-", ".tmp");
            downloader.download(url, temp);

            String actualSha256 = AddonChecksums.sha256Hex(temp);
            if (!AddonChecksums.digestsEqual(expectedSha256, actualSha256)) {
                return new DownloadOutcome(false, null, "SHA-256 checksum mismatch for " + entry.jarFileName(version)
                        + " (expected " + expectedSha256 + ", got " + actualSha256 + ")");
            }

            AlliumApiService.JarPreflight preflight = AlliumApiService.preflightJar(temp);
            if (!preflight.accepted()) {
                return new DownloadOutcome(false, null, preflight.error() != null
                        ? preflight.error()
                        : "downloaded jar failed addon preflight");
            }
            if (preflight.name() == null || !preflight.name().equalsIgnoreCase(entry.displayName())) {
                return new DownloadOutcome(false, null, "downloaded jar declares plugin '" + preflight.name()
                        + "', expected '" + entry.displayName() + "'");
            }
            if (preflight.version() == null || !normalizeReleaseVersion(preflight.version()).equals(normalizeReleaseVersion(version))) {
                return new DownloadOutcome(false, null, "downloaded jar declares version '" + preflight.version()
                        + "', expected '" + version + "'");
            }
            if (preflight.mainClass() == null) {
                return new DownloadOutcome(false, null, "downloaded jar plugin.yml is missing main class");
            }
            verifyJarContents(temp, preflight.mainClass());

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

    private static String resolveExpectedDigest(
            Path addonsDir,
            AddonCatalog.Entry entry,
            String version,
            @Nullable String baseUrl,
            @Nullable String pinnedSha256,
            Map<String, String> bundledChecksums,
            Downloader downloader
    ) throws IOException {
        if (pinnedSha256 != null && !pinnedSha256.isBlank()) {
            if (!AddonChecksums.isValidSha256(pinnedSha256)) {
                throw new IOException("pinned SHA-256 digest for " + entry.key() + " is not a valid 64-character hex string");
            }
            return AddonChecksums.normalizeSha256(pinnedSha256);
        }
        String jarName = entry.jarFileName(version);
        if (bundledChecksums != null) {
            String bundled = bundledChecksums.get(jarName);
            if (bundled != null && AddonChecksums.isValidSha256(bundled)) {
                return AddonChecksums.normalizeSha256(bundled);
            }
        }
        if (!AddonCatalog.isOfficialReleaseBase(baseUrl)) {
            throw new IOException("custom release mirror requires a pinned SHA-256 digest in addons.checksums." + entry.key());
        }
        String manifestUrl = AddonCatalog.checksumsUrl(baseUrl, version);
        validateHttpsUrl(manifestUrl);
        Path manifestTemp = Files.createTempFile(addonsDir, ".addon-checksums-", ".tmp");
        try {
            downloader.download(manifestUrl, manifestTemp);
            if (Files.size(manifestTemp) > AddonChecksums.MAX_MANIFEST_BYTES) {
                throw new IOException(AddonCatalog.CHECKSUMS_FILE + " exceeded maximum size");
            }
            Map<String, String> manifest = AddonChecksums.parseManifest(Files.readString(manifestTemp, StandardCharsets.UTF_8));
            String expected = manifest.get(jarName);
            if (expected == null) {
                throw new IOException("release " + AddonCatalog.CHECKSUMS_FILE + " has no SHA-256 digest for " + jarName);
            }
            return expected;
        } finally {
            try { Files.deleteIfExists(manifestTemp); } catch (IOException ignored) {}
        }
    }

    private static void verifyJarContents(Path jarPath, String mainClass) throws IOException {
        String mainEntryName = mainClass.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry mainEntry = jar.getJarEntry(mainEntryName);
            if (mainEntry == null || mainEntry.isDirectory()) {
                throw new IOException("downloaded jar is missing declared main class " + mainClass);
            }
            byte[] buffer = new byte[8192];
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    while (in.read(buffer) != -1) {
                        // Validate ZIP stream integrity and CRCs for every entry before install.
                    }
                }
            }
        }
    }

    private static String normalizeReleaseVersion(String version) {
        String trimmed = version.trim();
        return (trimmed.startsWith("v") || trimmed.startsWith("V")) ? trimmed.substring(1) : trimmed;
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
        FileConfiguration config = plugin.getConfig();
        String baseUrl = config.getString("addons.release-base-url", AddonCatalog.DEFAULT_RELEASE_BASE_URL);
        String version = AddonCatalog.releaseVersion(
                plugin.getDescription().getVersion(),
                config.getString("addons.release-version")
        );
        String url = AddonCatalog.downloadUrl(baseUrl, version, entry);
        String pinnedSha256;
        try {
            pinnedSha256 = AddonChecksums.configuredPin(config, entry, version).orElse(null);
        } catch (IOException badPin) {
            inFlight.remove(entry.key());
            plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_FAILED,
                    "{addon}", entry.displayName(), "{reason}", badPin.getMessage());
            return;
        }
        Path dir = addonsDir();
        plugin.getMessageManager().send(sender, Messages.ADDON_DOWNLOAD_STARTED,
                "{addon}", entry.displayName(), "{version}", version);
        try {
            io.execute(() -> {
                DownloadOutcome outcome;
                try {
                    outcome = downloadAndInstallSync(dir, entry, version, baseUrl, url, pinnedSha256, bundledChecksums, downloader);
                } finally {
                    inFlight.remove(entry.key());
                }
                plugin.getScheduler().runOnServer(() -> {
                    if (!outcome.success()) {
                        plugin.logger().warning("Addon download failed for " + entry.displayName() + ": " + outcome.message());
                        codes.castled.allium.command.essentials.UtilitySupport.send(plugin, sender, Messages.ADDON_DOWNLOAD_FAILED,
                                "{addon}", entry.displayName(), "{reason}", outcome.message());
                        return;
                    }
                    boolean saved = persistEnabledFlag(entry.key(), true);
                    if (!saved) {
                        plugin.logger().warning("Downloaded " + entry.displayName() + " but could not save config.yml.");
                    }
                    plugin.logger().info("Downloaded addon " + entry.displayName() + " (" + outcome.jarPath().getFileName()
                            + ") and enabled addons.enabled." + entry.key() + " in config.yml. Restart the server to load it.");
                    codes.castled.allium.command.essentials.UtilitySupport.send(plugin, sender, Messages.ADDON_DOWNLOAD_COMPLETE,
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
