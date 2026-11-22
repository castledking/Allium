package codes.castled.allium.addon;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.AddonRefusedException;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumApi;
import codes.castled.allium.api.ApiVersion;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Allium's {@link AlliumApi} service and addon gatekeeper.
 *
 * <p>When Allium enables, every other plugin already loaded by Bukkit is checked once: its
 * {@code plugin.yml} {@code allium-api} range against {@link #API_VERSION}, its classloader for a
 * bundled copy of the API, and whether it requires {@code plugins/Allium/addons/}. Allium then
 * scans {@code plugins/Allium/addons/*.jar} and loads and enables valid addon jars directly. A
 * refused addon cannot connect, and Allium disables it as soon as Bukkit enables it (Bukkit offers
 * no way to veto an enable), with the diagnostic logged once at startup. Addons are installed and
 * updated only with a restart.
 */
public final class AlliumApiService implements AlliumApi, Listener {

    /** The API version this Allium provides; kept equal to {@code alliumApiVersion} in gradle.properties. */
    public static final ApiVersion API_VERSION = new ApiVersion(1, 3, 0);

    /** Classes whose presence in an addon's own jar means it shaded the API. */
    static final List<Class<?>> API_PROBES = List.of(AlliumApi.class, CommandSpec.class, TaskScheduler.class);

    private final PluginStart allium;
    private final Map<String, AddonVerdict> verdicts = new ConcurrentHashMap<>();
    private final Map<String, AddonSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> managedAddonNames = ConcurrentHashMap.newKeySet();
    private final List<Plugin> managedAddons = new ArrayList<>();

    public AlliumApiService(PluginStart allium) {
        this.allium = allium;
    }

    /** Registers the service and listener, checks installed plugins, and loads addons from {@code plugins/Allium/addons/}. */
    public void install() {
        allium.getServer().getServicesManager().register(AlliumApi.class, this, allium, ServicePriority.Normal);
        allium.getServer().getPluginManager().registerEvents(this, allium);
        List<String> accepted = new ArrayList<>();
        for (Plugin plugin : allium.getServer().getPluginManager().getPlugins()) {
            if (plugin == allium) {
                continue;
            }
            AddonVerdict verdict = check(plugin);
            if (verdict.refused()) {
                allium.logger().severe("Refusing addon " + plugin.getName() + ": " + verdict.message()
                        + " It will be disabled when it enables; Allium and other addons keep running.");
            } else if (verdict.status() == AddonVerdict.Status.ACCEPTED) {
                accepted.add(plugin.getName() + " (" + verdict.declared() + ")");
            }
        }
        loadAddonsFromDirectory(accepted);
        allium.logger().info("Allium API " + API_VERSION + " ready; addons: " + (accepted.isEmpty() ? "none" : String.join(", ", accepted)) + ".");
    }

    @Nullable
    Path addonsDirectory() {
        File dataFolder = allium.getDataFolder();
        return dataFolder == null ? null : normalizePath(dataFolder.toPath().resolve("addons"));
    }

    private void loadAddonsFromDirectory(List<String> accepted) {
        Path dir = addonsDirectory();
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            allium.logger().warning("Could not create addons directory " + dir + ": " + e.getMessage());
            return;
        }
        List<Path> jars;
        try (Stream<Path> entries = Files.list(dir)) {
            jars = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            allium.logger().warning("Could not list addons directory " + dir + ": " + e.getMessage());
            return;
        }
        if (jars.isEmpty()) {
            return;
        }

        var metadata = new java.util.LinkedHashMap<Path, YamlConfiguration>();
        jars.forEach(jar -> metadata.put(jar, readJarPluginYml(jar)));
        PluginManager pm = allium.getServer().getPluginManager();
        for (Path jarPath : orderAddons(metadata)) {
            String fileName = jarPath.getFileName().toString();
            YamlConfiguration meta = metadata.get(jarPath);
            if (meta == null) {
                allium.logger().warning("Ignoring " + fileName + " in plugins/Allium/addons/: missing a valid plugin.yml.");
                continue;
            }
            String name = trimToNull(meta.getString("name"));
            if (name == null) {
                allium.logger().warning("Ignoring " + fileName + " in plugins/Allium/addons/: plugin.yml has no name.");
                continue;
            }
            String declared = trimToNull(meta.getString("allium-api"));
            if (declared == null) {
                allium.logger().warning("Ignoring " + name + " (" + fileName + ") in plugins/Allium/addons/: "
                        + "missing allium-api declaration in plugin.yml.");
                continue;
            }
            AddonVerdict preCheck = AddonVerdict.evaluate(name, declared, API_VERSION, List.of(), false);
            if (preCheck.refused()) {
                verdicts.putIfAbsent(name, preCheck);
                allium.logger().severe("Refusing addon " + name + " (" + fileName + "): " + preCheck.message());
                continue;
            }
            if (pm.getPlugin(name) != null) {
                allium.logger().severe("Skipping addon " + name + " (" + fileName + ") in plugins/Allium/addons/: "
                        + "a plugin named " + name + " is already loaded (remove any duplicate jar from plugins/).");
                continue;
            }

            Plugin loaded;
            try {
                loaded = pm.loadPlugin(jarPath.toFile());
            } catch (InvalidPluginException | InvalidDescriptionException | RuntimeException | LinkageError e) {
                allium.logger().severe("Failed to load addon " + name + " (" + fileName + ") from plugins/Allium/addons/: " + e.getMessage());
                continue;
            }
            if (loaded == null) {
                allium.logger().severe("Failed to load addon " + name + " (" + fileName + ") from plugins/Allium/addons/: loader returned null.");
                continue;
            }

            managedAddonNames.add(loaded.getName());
            AddonVerdict verdict = check(loaded);
            if (verdict.refused()) {
                allium.logger().severe("Refusing addon " + loaded.getName() + " (" + fileName + "): " + verdict.message());
                continue;
            }

            try {
                loaded.onLoad();
            } catch (Throwable t) {
                allium.logger().severe("Error during onLoad for addon " + loaded.getName() + ": " + t.getMessage());
                continue;
            }
            try {
                pm.enablePlugin(loaded);
            } catch (Throwable t) {
                allium.logger().severe("Error enabling addon " + loaded.getName() + ": " + t.getMessage());
                continue;
            }
            if (loaded.isEnabled()) {
                managedAddons.add(loaded);
                accepted.add(loaded.getName() + " (" + verdict.declared() + ")");
            } else {
                allium.logger().warning("Addon " + loaded.getName() + " (" + fileName + ") did not stay enabled.");
            }
        }
    }

    @Nullable
    static List<Path> orderAddons(Map<Path, YamlConfiguration> metadata) {
        Map<String, Path> byName = new java.util.HashMap<>();
        metadata.forEach((path, meta) -> {
            if (meta != null && meta.isString("name")) byName.putIfAbsent(meta.getString("name"), path);
        });
        Set<Path> visited = new java.util.HashSet<>();
        List<Path> ordered = new ArrayList<>();
        for (Path path : metadata.keySet()) visitAddon(path, metadata, byName, visited, ordered);
        return ordered;
    }

    private static void visitAddon(Path path, Map<Path, YamlConfiguration> metadata, Map<String, Path> byName,
                                   Set<Path> visited, List<Path> ordered) {
        if (!visited.add(path)) return;
        YamlConfiguration meta = metadata.get(path);
        if (meta != null) {
            List<String> dependencies = new ArrayList<>(meta.getStringList("depend"));
            dependencies.addAll(meta.getStringList("softdepend"));
            for (String dependency : dependencies) {
                Path peer = byName.get(dependency);
                if (peer != null) visitAddon(peer, metadata, byName, visited, ordered);
            }
        }
        ordered.add(path);
    }

    private static YamlConfiguration readJarPluginYml(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AddonVerdict check(Plugin plugin) {
        return verdicts.computeIfAbsent(plugin.getName(), name -> {
            YamlConfiguration yml = readPluginYml(plugin);
            String declared = yml == null ? null : trimToNull(yml.getString("allium-api"));
            boolean requiresAddonDir = yml != null && yml.getBoolean("allium-addon-dir", false);
            boolean wrongDir = requiresAddonDir
                    && !managedAddonNames.contains(name)
                    && !isJarInside(plugin, addonsDirectory());
            return AddonVerdict.evaluate(name, declared, API_VERSION, shadedApiClasses(plugin), wrongDir);
        });
    }

    @Nullable
    static YamlConfiguration readPluginYml(Plugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static String declaredRange(Plugin plugin) {
        YamlConfiguration yml = readPluginYml(plugin);
        return yml == null ? null : trimToNull(yml.getString("allium-api"));
    }

    static boolean isJarInside(Plugin plugin, @Nullable Path addonsDir) {
        if (addonsDir == null) {
            return false;
        }
        try {
            CodeSource source = plugin.getClass().getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                return false;
            }
            URI uri = location.toURI();
            Path jarPath = normalizePath(Path.of(uri));
            Path parent = jarPath.getParent();
            return parent != null && normalizePath(parent).equals(normalizePath(addonsDir));
        } catch (Exception e) {
            return false;
        }
    }

    private static Path normalizePath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** API classes that the plugin's own classloader defines instead of resolving Allium's. */
    static List<String> shadedApiClasses(Plugin plugin) {
        List<String> shaded = new ArrayList<>();
        ClassLoader loader = plugin.getClass().getClassLoader();
        for (Class<?> ours : API_PROBES) {
            try {
                Class<?> theirs = Class.forName(ours.getName(), false, loader);
                if (theirs != ours) {
                    shaded.add(ours.getName());
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // Not visible to that plugin at all: nothing shaded.
            }
        }
        return shaded;
    }

    @Override
    public ApiVersion version() {
        return API_VERSION;
    }

    @Override
    public AddonConnection connect(Plugin addon) {
        AddonVerdict verdict = check(addon);
        if (verdict.status() == AddonVerdict.Status.NOT_AN_ADDON) {
            throw new AddonRefusedException(addon.getName() + " must declare the Allium API range it needs in its plugin.yml, "
                    + "for example allium-api: \"^" + API_VERSION.major() + "." + API_VERSION.minor() + "\".");
        }
        if (verdict.refused()) {
            throw new AddonRefusedException(verdict.message());
        }
        if (!addon.isEnabled()) {
            throw new AddonRefusedException(addon.getName() + " can connect only while it is enabled (call connect from onEnable).");
        }
        return sessions.computeIfAbsent(addon.getName(), name -> new AddonSession(allium, addon));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnable(PluginEnableEvent event) {
        AddonVerdict verdict = verdicts.get(event.getPlugin().getName());
        if (verdict == null || !verdict.refused()) {
            return;
        }
        if (event.getPlugin().isEnabled()) {
            allium.logger().warning("Disabling refused addon " + event.getPlugin().getName() + ": " + verdict.status() + ".");
            allium.getServer().getPluginManager().disablePlugin(event.getPlugin());
        } else {
            // An AlliumAddon disables itself when connect refuses it, before its own code runs.
            allium.logger().info("Refused addon " + event.getPlugin().getName() + " disabled itself (" + verdict.status() + ").");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        AddonSession session = sessions.remove(event.getPlugin().getName());
        if (session != null) {
            session.close();
        }
    }

    /** Disables managed directory addons and releases every addon's connection (Allium is disabling). */
    public void closeAll() {
        PluginManager pm = allium.getServer().getPluginManager();
        for (int i = managedAddons.size() - 1; i >= 0; i--) {
            Plugin addon = managedAddons.get(i);
            if (addon.isEnabled()) {
                try {
                    pm.disablePlugin(addon);
                } catch (RuntimeException e) {
                    allium.logger().warning("Error disabling addon " + addon.getName() + ": " + e.getMessage());
                }
            }
        }
        managedAddons.clear();
        managedAddonNames.clear();
        sessions.values().forEach(AddonSession::close);
        sessions.clear();
        allium.getServer().getServicesManager().unregister(AlliumApi.class, this);
    }

    public Map<String, AddonVerdict> verdicts() {
        return Map.copyOf(verdicts);
    }
}
