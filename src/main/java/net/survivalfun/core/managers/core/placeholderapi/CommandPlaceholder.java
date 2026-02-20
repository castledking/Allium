package net.survivalfun.core.managers.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.security.CommandManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

public class CommandPlaceholder extends PlaceholderExpansion {

    private final PluginStart plugin;
    private final CommandManager commandManager;

    public CommandPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
        this.commandManager = plugin.getCommandManager();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "allium_command";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        // Expected formats:
        // <command>_canuse
        // <command>_cansee
        String[] parts = params.split("_", 2);
        if (parts.length != 2) {
            return "";
        }

        String command = parts[0].toLowerCase();
        String action = parts[1].toLowerCase();

        return switch (action) {
            case "canuse", "cansee" -> commandManager.shouldShowCommand(player, command) ? "yes" : "no";
            default -> "";
        };
    }
}
