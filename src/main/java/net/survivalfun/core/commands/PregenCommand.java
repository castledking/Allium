package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.world.OreGenerationManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Command for pre-generating chunks with custom ore generation.
 * Similar to Chunky but with integrated ore placement.
 */
public class PregenCommand implements CommandExecutor, TabCompleter {
    
    private final PluginStart plugin;
    private final OreGenerationManager oreManager;
    
    private final Map<String, GenerationTask> activeTasks = new HashMap<>();
    
    public PregenCommand(PluginStart plugin, OreGenerationManager oreManager) {
        this.plugin = plugin;
        this.oreManager = oreManager;
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "start" -> handleStart(sender, args);
            case "pause" -> handlePause(sender, args);
            case "resume" -> handleResume(sender, args);
            case "stop" -> handleStop(sender, args);
            case "status" -> handleStatus(sender);
            case "world" -> handleWorldGen(sender, args);
            default -> sendHelp(sender);
        }
        
        return true;
    }
    
    private void handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pregen.start")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /allium pregen start <radius> <world>");
            return;
        }
        
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
            if (radius < 1 || radius > 10000) {
                sender.sendMessage("§cRadius must be between 1 and 10000 chunks.");
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid radius. Please enter a number.");
            return;
        }
        
        World world = Bukkit.getWorld(args[2]);
        if (world == null) {
            sender.sendMessage("§cWorld '" + args[2] + "' not found.");
            return;
        }
        
        // Use player location or world spawn as center
        int centerX, centerZ;
        if (sender instanceof Player player) {
            centerX = player.getLocation().getBlockX() >> 4;
            centerZ = player.getLocation().getBlockZ() >> 4;
        } else {
            centerX = world.getSpawnLocation().getBlockX() >> 4;
            centerZ = world.getSpawnLocation().getBlockZ() >> 4;
        }
        
        startGeneration(sender, world, centerX, centerZ, radius);
    }
    
    private void startGeneration(CommandSender sender, World world, int centerX, int centerZ, int radius) {
        String taskId = world.getName() + "_" + UUID.randomUUID().toString().substring(0, 8);
        
        sender.sendMessage("§aStarting pre-generation for world §f" + world.getName());
        sender.sendMessage("§7Center: §f" + centerX + ", " + centerZ + " §7| Radius: §f" + radius + " chunks");
        sender.sendMessage("§7Estimated chunks: §f" + ((radius * 2 + 1) * (radius * 2 + 1)));
        sender.sendMessage("§7Custom ores (Silver/Galena) will be placed during generation.");
        
        GenerationTask task = new GenerationTask(world, centerX, centerZ, radius, taskId);
        activeTasks.put(taskId, task);
        
        CompletableFuture<Void> future = oreManager.startGeneration(world, centerX, centerZ, radius);
        
        future.whenComplete((result, error) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                activeTasks.remove(taskId);
                
                if (error != null) {
                    sender.sendMessage("§cGeneration failed: " + error.getMessage());
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Pregeneration failed", error);
                } else {
                    sender.sendMessage("§aPre-generation completed!");
                    sender.sendMessage("§7Chunks generated: §f" + oreManager.getGeneratedChunks());
                    sender.sendMessage("§7Custom ores placed: §f" + oreManager.getGeneratedOres());
                }
            });
        });
    }
    
    private void handlePause(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pregen.pause")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (!oreManager.isGenerating()) {
            sender.sendMessage("§cNo generation is currently running.");
            return;
        }
        
        oreManager.pauseGeneration();
        sender.sendMessage("§6Generation paused. Use §f/allium pregen resume §6to continue.");
    }
    
    private void handleResume(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pregen.resume")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (!oreManager.isGenerating()) {
            sender.sendMessage("§cNo generation is currently running.");
            return;
        }
        
        if (!oreManager.isPaused()) {
            sender.sendMessage("§cGeneration is not paused.");
            return;
        }
        
        oreManager.resumeGeneration();
        sender.sendMessage("§aGeneration resumed.");
    }
    
    private void handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pregen.stop")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (!oreManager.isGenerating()) {
            sender.sendMessage("§cNo generation is currently running.");
            return;
        }
        
        oreManager.stopGeneration();
        sender.sendMessage("§cGeneration stopped.");
        sender.sendMessage("§7Chunks generated: §f" + oreManager.getGeneratedChunks());
        sender.sendMessage("§7Custom ores placed: §f" + oreManager.getGeneratedOres());
    }
    
    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("allium.pregen.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (!oreManager.isGenerating()) {
            sender.sendMessage("§7No generation is currently running.");
            return;
        }
        
        sender.sendMessage("§6=== Generation Status ===");
        sender.sendMessage("§7Status: §f" + (oreManager.isPaused() ? "Paused" : "Running"));
        sender.sendMessage("§7Chunks generated: §f" + oreManager.getGeneratedChunks());
        sender.sendMessage("§7Custom ores placed: §f" + oreManager.getGeneratedOres());
        
        for (GenerationTask task : activeTasks.values()) {
            sender.sendMessage("§7World: §f" + task.world.getName());
            sender.sendMessage("§7Center: §f" + task.centerX + ", " + task.centerZ);
            sender.sendMessage("§7Radius: §f" + task.radius + " chunks");
        }
    }
    
    private void handleWorldGen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.pregen.world")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /allium pregen world <world-name> <radius> [center-x] [center-z]");
            return;
        }
        
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("§cWorld '" + args[1] + "' not found.");
            return;
        }
        
        int radius;
        try {
            radius = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid radius.");
            return;
        }
        
        int centerX, centerZ;
        if (args.length >= 5) {
            try {
                centerX = Integer.parseInt(args[3]);
                centerZ = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid center coordinates.");
                return;
            }
        } else {
            centerX = world.getSpawnLocation().getBlockX() >> 4;
            centerZ = world.getSpawnLocation().getBlockZ() >> 4;
        }
        
        startGeneration(sender, world, centerX, centerZ, radius);
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== Allium Pregen Help ===");
        sender.sendMessage("§e/allium pregen start <radius> <world> §7- Start generation from your location");
        sender.sendMessage("§e/allium pregen world <world> <radius> [cx] [cz] §7- Generate specific world");
        sender.sendMessage("§e/allium pregen pause §7- Pause generation");
        sender.sendMessage("§e/allium pregen resume §7- Resume generation");
        sender.sendMessage("§e/allium pregen stop §7- Stop generation");
        sender.sendMessage("§e/allium pregen status §7- Show generation status");
        sender.sendMessage("§7");
        sender.sendMessage("§7Note: Silver and Galena ores will be automatically placed");
        sender.sendMessage("§7during chunk generation using Oraxen noteblock states.");
    }
    
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("start", "pause", "resume", "stop", "status", "world");
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return worlds;
        }
        
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return worlds;
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Represents an active generation task
     */
    private record GenerationTask(World world, int centerX, int centerZ, int radius, String taskId) {}
}
