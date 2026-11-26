package codes.castled.allium;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Java 8 lifecycle contract implemented by both the modern Java 17 {@code PluginStart} runtime
 * (Minecraft 1.20-26.3) and the Java 8 {@code LegacyBukkitRuntime} (Minecraft 1.12.2-1.19.4).
 */
public interface AlliumRuntime {

    void onLoad();

    void onEnable();

    void onDisable();

    default boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return false;
    }

    default List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
