package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CommandManager {

    private final PluginStart plugin;
    private final Map<String, CommandExecutor> commands = new LinkedHashMap<>();

    public CommandManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void register(String name, CommandExecutor executor) {
        commands.put(name, executor);
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (executor instanceof TabCompleter) {
                cmd.setTabCompleter((TabCompleter) executor);
            }
        } else {
            plugin.logger().warning("Command '" + name + "' is not defined in plugin.yml.");
        }
    }

    public void registerAll() {
        // Future commands are registered here as modules add them
    }

    public void unregisterAll() {
        commands.clear();
    }

    public Map<String, CommandExecutor> getCommands() {
        return commands;
    }
}
