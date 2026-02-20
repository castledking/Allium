package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.listeners.jobs.WitherSpawnBlocker;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WitherToggle implements CommandExecutor, TabCompleter {

    private final WitherSpawnBlocker witherSpawnBlocker;
    private final Lang lang;

    public WitherToggle(PluginStart plugin, WitherSpawnBlocker witherSpawnBlocker) {
        this.witherSpawnBlocker = witherSpawnBlocker;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.withertoggle")) {
            Text.sendErrorMessage(sender, "no-permission", lang, "{cmd}", label);
            return true;
        }

        if (args.length == 0) {
            boolean newState = !witherSpawnBlocker.isWitherSpawnBlocked();
            witherSpawnBlocker.setWitherSpawnBlocked(newState, sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "on":
            case "enable":
            case "enabled":
            case "true":
                witherSpawnBlocker.setWitherSpawnBlocked(true, sender);
                return true;
            case "off":
            case "disable":
            case "disabled":
            case "false":
                witherSpawnBlocker.setWitherSpawnBlocked(false, sender);
                return true;
            case "status":
            case "state":
                boolean blocked = witherSpawnBlocker.isWitherSpawnBlocked();
                String statusKey = blocked ? "wither-toggle.status-disabled" : "wither-toggle.status-enabled";
                lang.sendMessage(sender, statusKey,
                        Sound.ENTITY_WITHER_AMBIENT, 0.5f, 1.0f,
                        Particle.SMOKE, 10, 0.5, 0.5, 0.5, 0.1);
                return true;
            default:
                lang.sendMessage(sender, "wither-toggle.usage", 
                        "{command}", label,
                        Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = Arrays.asList("on", "off", "status");
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], options, completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
