package codes.castled.allium;

import codes.castled.allium.legacy.LegacyBukkitRuntime;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.logging.Level;

/**
 * Single Java 8 {@link JavaPlugin} entry point for the unified Bukkit/Spigot/Paper/Purpur/Folia
 * Allium jar (Minecraft 1.12.2 through 26.3).
 *
 * <p>Compiled with {@code --release 8} against Spigot 1.12.2 API and containing zero direct
 * references to Java 17 classes, so Java 8-16 JVMs can load and run the 1.12.2-1.19.4
 * compatibility runtime without ever touching Java 17 bytecode, while Java 17+ servers running
 * Minecraft 1.20-26.3 reflectively boot the full {@code PluginStart} runtime.
 */
public final class AlliumBootstrap extends JavaPlugin {

    private static final String MODERN_RUNTIME_CLASS = "codes.castled.allium.PluginStart";

    private AlliumRuntime runtime;
    private boolean unsupportedVersion;

    private AlliumRuntime resolveRuntime() {
        if (runtime != null) {
            return runtime;
        }
        ServerVersion version = ServerVersion.parse(getServer().getBukkitVersion());
        int javaMajor = ServerVersion.currentJavaMajor();
        if (!version.isSupported()) {
            unsupportedVersion = true;
            return null;
        }
        if (version.supportsModernRuntime(javaMajor)) {
            try {
                Class<?> modernClass = Class.forName(MODERN_RUNTIME_CLASS, true, getClass().getClassLoader());
                Constructor<?> ctor = modernClass.getConstructor(JavaPlugin.class);
                runtime = (AlliumRuntime) ctor.newInstance(this);
                return runtime;
            } catch (Throwable failure) {
                getLogger().log(Level.SEVERE,
                        "Failed to initialize modern Allium runtime on Minecraft " + version
                                + " (Java " + javaMajor + "); falling back to compatibility runtime.",
                        failure);
            }
        }
        runtime = new LegacyBukkitRuntime(this, version, javaMajor);
        return runtime;
    }

    @Override
    public void onLoad() {
        AlliumRuntime target = resolveRuntime();
        if (target != null) {
            target.onLoad();
        }
    }

    @Override
    public void onEnable() {
        AlliumRuntime target = resolveRuntime();
        if (target == null || unsupportedVersion) {
            getLogger().severe("Allium requires Minecraft 1.12.2 or newer (detected "
                    + getServer().getBukkitVersion() + "). Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        target.onEnable();
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            try {
                runtime.onDisable();
            } finally {
                runtime = null;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return runtime != null && runtime.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return runtime != null ? runtime.onTabComplete(sender, command, alias, args)
                : super.onTabComplete(sender, command, alias, args);
    }
}
