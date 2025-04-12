package net.survivalfun.core.listeners.security;

import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.utils.Text;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandErrorHandler implements Listener {

    private final JavaPlugin plugin;
    private final Lang lang;
    private final Set<String> knownCommands = new HashSet<>();

    public CommandErrorHandler(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;

        // Initialize known commands by getting all registered commands
        plugin.getServer().getCommandMap().getKnownCommands().keySet().forEach(cmd -> {
            // Store the base command without colon
            String baseCmd = cmd.contains(":") ? cmd.split(":", 2)[1] : cmd;
            knownCommands.add(baseCmd.toLowerCase());
        });

        plugin.getLogger().info("Registered " + knownCommands.size() + " known commands for custom error handling");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;

        String message = event.getMessage().substring(1); // Remove the slash
        String[] parts = message.split(" ");
        String command = parts[0].toLowerCase();

        // Check if this is an unknown command
        if (!isKnownCommand(command)) {
            // Cancel the event to prevent the default "Unknown command" message
            event.setCancelled(true);

            // Send our custom error message
            String customMessage = lang.get("unknown-command").replace("{command}", command);
            customMessage = customMessage.replace("{command}", command);
            Text.sendErrorMessage(event.getPlayer(), "unknown-command", lang, "{command}", command);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        String message = event.getCommand();
        if (message.startsWith("/")) message = message.substring(1);

        String[] parts = message.split(" ");
        String command = parts[0].toLowerCase();

        // Check if this is an unknown command for console
        if (!isKnownCommand(command)) {
            // Cancel the event to prevent the default "Unknown command" message
            event.setCancelled(true);

            // Send our custom error message to console
            String customMessage = lang.get("unknown-command").replace("{command}", command);
            customMessage = customMessage.replace("{command}", command);
            event.getSender().sendMessage(lang.get("error-prefix") + " " + customMessage);
        }
    }

    private boolean isKnownCommand(String command) {
        // Check if the command exists in our known commands set
        return knownCommands.contains(command.toLowerCase());
    }

    /**
     * Updates the known commands list
     * Should be called when plugins are enabled/disabled
     */
    public void updateKnownCommands() {
        knownCommands.clear();
        plugin.getServer().getCommandMap().getKnownCommands().keySet().forEach(cmd -> {
            String baseCmd = cmd.contains(":") ? cmd.split(":", 2)[1] : cmd;
            knownCommands.add(baseCmd.toLowerCase());
        });
        plugin.getLogger().info("Updated known commands list: " + knownCommands.size() + " commands registered");
    }
}
