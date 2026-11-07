package codes.castled.allium.protection;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Loads optional protection hooks without referencing their plugin classes from eagerly loaded
 * code. A protection plugin that is installed but whose hook cannot load gets a hook that denies
 * every check, so tools fail closed with a diagnostic instead of ignoring claims.
 */
public final class ProtectionHookBootstrap {

    static final String GRIEF_PREVENTION_HOOK =
            "codes.castled.allium.protection.griefprevention.GriefPreventionProtectionHook";
    /** Upstream GriefPrevention and SF's GriefPrevention3D (which also provides "GriefPrevention"). */
    static final List<String> GRIEF_PREVENTION_NAMES = List.of("GriefPrevention", "GriefPrevention3D");

    private ProtectionHookBootstrap() {
    }

    public static List<ProtectionHook> loadAll(@Nullable PluginManager pluginManager, Consumer<String> info, Consumer<String> warning) {
        List<ProtectionHook> hooks = new ArrayList<>();
        Plugin griefPrevention = findEnabled(pluginManager, GRIEF_PREVENTION_NAMES);
        if (griefPrevention != null) {
            hooks.add(load(GRIEF_PREVENTION_HOOK, "GriefPrevention", griefPrevention, info, warning));
        }
        return hooks;
    }

    @Nullable
    static Plugin findEnabled(@Nullable PluginManager pluginManager, List<String> names) {
        if (pluginManager == null) {
            return null;
        }
        for (String name : names) {
            Plugin plugin = pluginManager.getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        return null;
    }

    static ProtectionHook load(String className, String name, Plugin plugin, Consumer<String> info, Consumer<String> warning) {
        String version = plugin.getDescription().getVersion();
        try {
            ProtectionHook hook = (ProtectionHook) Class.forName(className, true, ProtectionHookBootstrap.class.getClassLoader())
                    .getConstructor()
                    .newInstance();
            info.accept("Protection hook enabled: " + name + " (" + plugin.getName() + " " + version + ").");
            return hook;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warning.accept("Protection hook for " + plugin.getName() + " " + version
                    + " could not load; Allium tools that act on entities are denied until it loads: " + e);
            return new DenyingHook(name);
        }
    }

    record DenyingHook(String name) implements ProtectionHook {
        @Override
        public ProtectionDecision checkEntityInteraction(Player player, Entity target, @Nullable Event cause) {
            return ProtectionDecision.deny(name, null);
        }
    }
}
