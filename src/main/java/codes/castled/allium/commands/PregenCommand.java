package codes.castled.allium.commands;

import codes.castled.allium.managers.world.OreGenerationManager;
import codes.castled.allium.scheduler.SchedulerAdapter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** /pregen and /allium pregen; generation never starts during plugin enable. */
public final class PregenCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final OreGenerationManager manager;
    public PregenCommand(Plugin plugin, OreGenerationManager manager) { this.plugin = plugin; this.manager = manager; }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("allium.pregen." + action) && !sender.hasPermission("allium.pregen")) {
            sender.sendMessage("§cYou do not have permission to use this command."); return true;
        }
        try {
            switch (action) {
                case "start", "world" -> start(sender, args, action.equals("world"));
                case "pause" -> { manager.pauseGeneration(); sender.sendMessage(manager.isPaused() ? "§eGeneration paused." : "§7No active generation."); }
                case "resume" -> { manager.resumeGeneration(); sender.sendMessage(manager.isGenerating() ? "§aGeneration resumed." : "§7No active generation."); }
                case "stop" -> { manager.stopGeneration(); sender.sendMessage("§eGeneration stopped."); }
                case "status" -> sender.sendMessage("§6Ore pregen: §f" + (manager.isGenerating() ? (manager.isPaused() ? "Paused" : "Running") : "Idle")
                    + " §7| " + manager.getWorldName() + " | " + manager.getGeneratedChunks() + "/" + manager.getTotalChunks()
                    + " chunks | " + manager.getGeneratedOres() + " ores | CE blocks " + (manager.ready() ? "ready" : "missing/disabled"));
                default -> help(sender);
            }
        } catch (IllegalArgumentException e) { sender.sendMessage("§c" + e.getMessage()); }
        return true;
    }

    private void start(CommandSender sender, String[] args, boolean explicit) {
        if (args.length < 3 || (explicit && args.length == 4)) { help(sender); return; }
        World world = Bukkit.getWorld(args[explicit ? 1 : 2]);
        if (world == null) throw new IllegalArgumentException("World not found.");
        int radius;
        int x, z;
        try {
            radius = Integer.parseInt(args[explicit ? 2 : 1]);
            var center = sender instanceof Player player && player.getWorld().equals(world) && !explicit
                ? player.getLocation() : world.getSpawnLocation();
            x = explicit && args.length >= 5 ? Integer.parseInt(args[3]) : center.getBlockX() >> 4;
            z = explicit && args.length >= 5 ? Integer.parseInt(args[4]) : center.getBlockZ() >> 4;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Radius and chunk coordinates must be whole numbers."); }
        var future = manager.startGeneration(world, x, z, radius);
        if (!future.isCompletedExceptionally()) sender.sendMessage("§aStarting pre-generation: §f" + world.getName()
            + " §7at chunk " + x + ", " + z + " | " + manager.getTotalChunks() + " chunks.");
        future.whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Runnable message = () -> {
                Throwable cause = error;
                while (cause != null && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof CancellationException) return;
                sender.sendMessage(cause == null ? "§aPre-generation completed: §f" + manager.getGeneratedChunks()
                    + " chunks, " + manager.getGeneratedOres() + " ores."
                    : "§cPre-generation failed: " + cause.getMessage());
            };
            if (sender instanceof Player player) SchedulerAdapter.runEntity(plugin, player, message, null);
            else SchedulerAdapter.runGlobal(plugin, message);
        });
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6/pregen start <radius-in-chunks> <world>");
        sender.sendMessage("§6/pregen world <world> <radius-in-chunks> [center-chunk-x center-chunk-z]");
        sender.sendMessage("§6/pregen pause|resume|stop|status");
        sender.sendMessage("§7Also available as /allium pregen. Uses configured CraftEngine ore blocks.");
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("allium.pregen") && !sender.hasPermission("allium.pregen.status")) return List.of();
        List<String> options = args.length == 1 ? List.of("start", "world", "pause", "resume", "stop", "status")
            : (args.length == 2 && args[0].equalsIgnoreCase("world") || args.length == 3 && args[0].equalsIgnoreCase("start"))
                ? Bukkit.getWorlds().stream().map(World::getName).toList() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
