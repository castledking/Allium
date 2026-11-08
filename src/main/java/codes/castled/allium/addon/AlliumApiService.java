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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allium's {@link AlliumApi} service and addon gatekeeper.
 *
 * <p>When Allium enables, every other plugin is already loaded but not yet enabled, so each is
 * checked once: its {@code plugin.yml} {@code allium-api} range against {@link #API_VERSION}, and
 * its classloader for a bundled copy of the API. A refused addon cannot connect, and Allium disables
 * it as soon as Bukkit enables it (Bukkit offers no way to veto an enable), with the diagnostic
 * logged once at startup. Addons are installed and updated only with a restart.
 */
public final class AlliumApiService implements AlliumApi, Listener {

    /** The API version this Allium provides; kept equal to {@code alliumApiVersion} in gradle.properties. */
    public static final ApiVersion API_VERSION = new ApiVersion(1, 0, 0);

    /** Classes whose presence in an addon's own jar means it shaded the API. */
    static final List<Class<?>> API_PROBES = List.of(AlliumApi.class, CommandSpec.class, TaskScheduler.class);

    private final PluginStart allium;
    private final Map<String, AddonVerdict> verdicts = new ConcurrentHashMap<>();
    private final Map<String, AddonSession> sessions = new ConcurrentHashMap<>();

    public AlliumApiService(PluginStart allium) {
        this.allium = allium;
    }

    /** Registers the service and listener, then checks every installed plugin once. */
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
        allium.logger().info("Allium API " + API_VERSION + " ready; addons: " + (accepted.isEmpty() ? "none" : String.join(", ", accepted)) + ".");
    }

    private AddonVerdict check(Plugin plugin) {
        return verdicts.computeIfAbsent(plugin.getName(), name -> AddonVerdict.evaluate(name, declaredRange(plugin), API_VERSION, shadedApiClasses(plugin)));
    }

    @Nullable
    static String declaredRange(Plugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in == null) {
                return null;
            }
            String value = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)).getString("allium-api");
            return value == null ? null : value.trim();
        } catch (Exception e) {
            return null;
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

    /** Releases every addon's connection (Allium is disabling). */
    public void closeAll() {
        sessions.values().forEach(AddonSession::close);
        sessions.clear();
        allium.getServer().getServicesManager().unregister(AlliumApi.class, this);
    }

    public Map<String, AddonVerdict> verdicts() {
        return Map.copyOf(verdicts);
    }
}
