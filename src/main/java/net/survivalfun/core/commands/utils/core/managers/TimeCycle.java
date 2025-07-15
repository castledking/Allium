package net.survivalfun.core.commands.utils.core.managers;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles time-related commands for setting, adding, or viewing the time in Minecraft worlds.
 * Supports /time, /day, and /night commands with subcommands for setting or adding time,
 * with optional world targeting or wildcard (*) for all loaded worlds.
 */
public class TimeCycle implements CommandExecutor, TabCompleter {
    private final PluginStart plugin;

    protected String viewPermission = "core.time";
    protected String setPermission = "core.time.set";
    protected String addPermission = "core.time.add";
    protected int day = 0;
    protected int noon = 6000;
    protected int afternoon = 9000;
    protected int night = 14000;
    protected int sunrise = 23000;
    protected int sunset = 12000;
    protected int morning = 1000;
    protected int midnight = 18000;

    public TimeCycle(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmd = label.toLowerCase();

        if (cmd.equals("time")) {
            return handleTimeCommand(sender, args);
        } else if (cmd.equals("day")) {
            return handleDayCommand(sender, args);
        } else if (cmd.equals("night")) {
            return handleNightCommand(sender, args);
        }

        sender.sendMessage("§cUnknown command.");
        return false;
    }

    /**
     * Handles the /time command for viewing or modifying the world time.
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleTimeCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return handleTimeView(sender, args);
        }

        if (args[0].equalsIgnoreCase("set")) {
            return handleTimeSet(sender, args);
        } else if (args[0].equalsIgnoreCase("add")) {
            return handleTimeAdd(sender, args);
        }

        sender.sendMessage(plugin.getLangManager().get("command-usage").replace("{cmd}", "time")
                .replace("{args}", "set <value> [world] | add <value> [world]"));
        return true;
    }

    /**
     * Handles viewing the current time in a world (/time).
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleTimeView(CommandSender sender, String[] args) {
        if (!sender.hasPermission(viewPermission)) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /time", "view the time", true);
            return true;
        }

        World targetWorld = getTargetWorld(sender, args, false);
        if (targetWorld == null) {
            Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", args.length > 0 ? args[0] : "default");
            return true;
        }

        long ticks = targetWorld.getTime();
        String time24h = ticksTo24h(ticks);
        String time12h = ticksTo12h(ticks);
        sender.sendMessage(plugin.getLangManager().get("time.check")
                .replace("{24h}", time24h)
                .replace("{12h}", time12h)
                .replace("{ticks}", String.valueOf(ticks)));
        return true;
    }

    /**
     * Handles setting the time in a world (/time set, /day set, /night set).
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleTimeSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission(setPermission)) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /" + args[0], "set the time", true);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getLangManager().get("command-usage").replace("{cmd}", args[0])
                    .replace("{args}", "set <value> [world]"));
            return true;
        }

        long timeValue = getTimeValue(args[1]);
        if (timeValue == -1 || timeValue < 0 || timeValue >= 24000) {
            Text.sendErrorMessage(sender, "invalid", plugin.getLangManager(), "{arg}", args[1]);
            return true;
        }

        if (args.length >= 3 && args[2].equals("*")) {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                Text.sendErrorMessage(sender, "no-worlds", plugin.getLangManager(), "{world}", "any");
                return true;
            }
            for (World world : worlds) {
                world.setTime(timeValue);
            }
            String worldsList = String.join(", ", worlds.stream().map(World::getName).collect(Collectors.toList()));
            sender.sendMessage(plugin.getLangManager().get("time.set").replace("{time}", args[1]).replace("{worlds}", worldsList));
            return true;
        }

        World targetWorld = getTargetWorld(sender, args, true);
        if (targetWorld == null) {
            Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", args.length > 2 ? args[2] : "default");
            return true;
        }

        targetWorld.setTime(timeValue);
        sender.sendMessage(plugin.getLangManager().get("time.set").replace("{time}", args[1]).replace("{worlds}", targetWorld.getName()));
        return true;
    }

    /**
     * Handles adding time to a world (/time add, /day add, /night add).
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleTimeAdd(CommandSender sender, String[] args) {
        if (!sender.hasPermission(addPermission)) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /" + args[0], "add to the time", true);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getLangManager().get("command-usage").replace("{cmd}", args[0])
                    .replace("{args}", "add <value> [world]"));
            return true;
        }

        long timeValue;
        try {
            timeValue = Long.parseLong(args[1]);
            if (timeValue < 0) {
                throw new NumberFormatException("Negative ticks not allowed");
            }
        } catch (NumberFormatException e) {
            Text.sendErrorMessage(sender, "invalid", plugin.getLangManager(), "{arg}", args[1]);
            return true;
        }

        if (args.length >= 3 && args[2].equals("*")) {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                Text.sendErrorMessage(sender, "no-worlds", plugin.getLangManager(), "{world}", "any");
                return true;
            }
            for (World world : worlds) {
                long newTime = (world.getTime() + timeValue) % 24000;
                if (newTime < 0) newTime += 24000;
                world.setTime(newTime);
            }
            String worldsList = String.join(", ", worlds.stream().map(World::getName).collect(Collectors.toList()));
            sender.sendMessage(plugin.getLangManager().get("time.add").replace("{time}", String.valueOf(timeValue)).replace("{worlds}", worldsList));
            return true;
        }

        World targetWorld = getTargetWorld(sender, args, true);
        if (targetWorld == null) {
            Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", args.length > 2 ? args[2] : "default");
            return true;
        }

        long newTime = (targetWorld.getTime() + timeValue) % 24000;
        if (newTime < 0) newTime += 24000;
        targetWorld.setTime(newTime);
        sender.sendMessage(plugin.getLangManager().get("time.add").replace("{time}", String.valueOf(timeValue)).replace("{worlds}", targetWorld.getName()));
        return true;
    }

    /**
     * Handles the /day command, setting time to day (0 ticks) by default or modifying time.
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleDayCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(setPermission)) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /day", "set the time", true);
            return true;
        }

        if (args.length == 0) {
            World targetWorld = getTargetWorld(sender, args, false);
            if (targetWorld == null) {
                Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", "default");
                return true;
            }
            targetWorld.setTime(day);
            sender.sendMessage(plugin.getLangManager().get("time.set").replace("{time}", "day").replace("{worlds}", targetWorld.getName()));
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            return handleTimeSet(sender, args);
        } else if (args[0].equalsIgnoreCase("add")) {
            return handleTimeAdd(sender, args);
        }

        sender.sendMessage(plugin.getLangManager().get("command-usage").replace("{cmd}", "day")
                .replace("{args}", "set <value> [world] | add <value> [world]"));
        return true;
    }

    /**
     * Handles the /night command, setting time to night (14000 ticks) by default or modifying time.
     *
     * @param sender The command sender.
     * @param args   Command arguments.
     * @return True if the command was handled successfully.
     */
    private boolean handleNightCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(setPermission)) {
            Text.sendErrorMessage(sender, "no-permission", plugin.getLangManager(), "use /night", "set the time", true);
            return true;
        }

        if (args.length == 0) {
            World targetWorld = getTargetWorld(sender, args, false);
            if (targetWorld == null) {
                Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", "default");
                return true;
            }
            targetWorld.setTime(night);
            sender.sendMessage(plugin.getLangManager().get("time.set").replace("{time}", "night").replace("{worlds}", targetWorld.getName()));
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            return handleTimeSet(sender, args);
        } else if (args[0].equalsIgnoreCase("add")) {
            return handleTimeAdd(sender, args);
        }

        World targetWorld = getTargetWorld(sender, args, false);
        if (targetWorld == null) {
            Text.sendErrorMessage(sender, "world-not-found", plugin.getLangManager(), "{world}", args[0]);
            return true;
        }
        targetWorld.setTime(night);
        sender.sendMessage(plugin.getLangManager().get("time.set").replace("{time}", "night").replace("{worlds}", targetWorld.getName()));
        return true;
    }

    /**
     * Determines the target world for a command, handling specific worlds, player location, or default world.
     *
     * @param sender      The command sender.
     * @param args        Command arguments.
     * @param isSubcommand Whether the command includes a subcommand (set/add).
     * @return The target world, or null if no valid world is found.
     */
    private World getTargetWorld(CommandSender sender, String[] args, boolean isSubcommand) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }

        World targetWorld = null;
        if (isSubcommand && args.length >= 3 && !args[2].equals("*")) {
            targetWorld = Bukkit.getWorld(args[2]);
        } else if (!isSubcommand && args.length >= 1 && !args[0].equals("*")) {
            targetWorld = Bukkit.getWorld(args[0]);
        }

        if (targetWorld == null && sender instanceof Player player) {
            targetWorld = player.getWorld();
        } else if (targetWorld == null) {
            targetWorld = worlds.get(0);
        }

        return targetWorld;
    }

    /**
     * Converts a time string or tick value to a tick value.
     *
     * @param arg The time string (e.g., "day", "noon") or numeric tick value.
     * @return The corresponding tick value, or -1 if invalid.
     */
    private long getTimeValue(String arg) {
        switch (arg.toLowerCase()) {
            case "day":
                return day;
            case "noon":
                return noon;
            case "afternoon":
                return afternoon;
            case "night":
                return night;
            case "sunrise":
                return sunrise;
            case "sunset":
                return sunset;
            case "morning":
                return morning;
            case "midnight":
                return midnight;
            default:
                try {
                    return Long.parseLong(arg);
                } catch (NumberFormatException e) {
                    return -1;
                }
        }
    }

    /**
     * Converts tick value to a 24-hour time format.
     *
     * @param ticks The tick value (0–23999).
     * @return A string in the format "HH:mm".
     */
    private String ticksTo24h(long ticks) {
        long hours = (ticks / 1000 + 6) % 24;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format("%02d:%02d", hours, minutes);
    }

    /**
     * Converts tick value to a 12-hour time format with AM/PM.
     *
     * @param ticks The tick value (0–23999).
     * @return A string in the format "HH:mm AM/PM".
     */
    private String ticksTo12h(long ticks) {
        long hours = (ticks / 1000 + 6) % 24;
        String period = hours >= 12 ? "PM" : "AM";
        hours = hours % 12;
        if (hours == 0) hours = 12;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format("%02d:%02d %s", hours, minutes, period);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        String cmd = alias.toLowerCase();

        if (cmd.equals("time") || cmd.equals("day") || cmd.equals("night")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                if (sender.hasPermission(viewPermission)) {
                    completions.add("");
                }
                if (sender.hasPermission(setPermission)) {
                    completions.add("set");
                }
                if (sender.hasPermission(addPermission)) {
                    completions.add("add");
                }
                return completions;
            } else if (args.length == 2 && args[0].equalsIgnoreCase("set") && sender.hasPermission(setPermission)) {
                return List.of("day", "noon", "afternoon", "night", "sunrise", "sunset", "morning", "midnight", "0", "1000", "6000", "9000", "12000", "13000", "18000", "23000");
            } else if (args.length == 2 && args[0].equalsIgnoreCase("add") && sender.hasPermission(addPermission)) {
                return List.of("1000", "6000", "9000", "12000");
            } else if (args.length == 3 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
                String input = args[2].toLowerCase();
                List<String> worldNames = new ArrayList<>();
                worldNames.add("*");
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase().startsWith(input)) {
                        worldNames.add(world.getName());
                    }
                }
                return worldNames;
            }
        }

        return Collections.emptyList();
    }
}