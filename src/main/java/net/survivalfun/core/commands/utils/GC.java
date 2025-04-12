package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicInteger;

public class GC implements CommandExecutor {
    private final PluginStart plugin;

    public GC(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        LangManager lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            plugin.getLogger().log(Level.SEVERE, "LanguageManager not initialized when executing GC command");
            return true;
        }

        if (!sender.hasPermission("core.gc")) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        sender.sendMessage(lang.get("commands.gc.usage-header"));

        // Run async part for safe operations
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Declare as final variables for use in lambda
            final Runtime runtime = Runtime.getRuntime();
            final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();

            // System information (safe to do async)
            final long maxMemory = runtime.maxMemory() / 1024 / 1024;
            final long totalMemory = runtime.totalMemory() / 1024 / 1024;
            final long freeMemory = runtime.freeMemory() / 1024 / 1024;

            final String osName = osMXBean.getName();
            final String osVersion = osMXBean.getVersion();
            final String osArch = osMXBean.getArch();
            final int availableProcessors = osMXBean.getAvailableProcessors();

            // Disk space information (safe async)
            final long[] diskSpace = {0L, 0L}; // totalDiskSpace, freeDiskSpace

            try {
                File serverRoot = new File(".").getAbsoluteFile();
                FileStore store = Files.getFileStore(serverRoot.toPath());
                diskSpace[0] = store.getTotalSpace() / 1024 / 1024 / 1024;
                diskSpace[1] = store.getUsableSpace() / 1024 / 1024 / 1024;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not get disk space information", e);
                sender.sendMessage(lang.get("error-prefix") + (lang.get("commands.gc.disk-space-error")));
            }

            // TPS information (safe async)
            final double[] tpsArray = new double[]{20.0, 20.0, 20.0};
            try {
                System.arraycopy(Bukkit.getTPS(), 0, tpsArray, 0, 3);
            } catch (NoSuchMethodError e) {
                plugin.getLogger().log(Level.WARNING, "Could not get TPS - Paper API not available", e);
            }

            final String tps = formatTps(tpsArray[0]);

            // Prepare atomic counters for world stats
            final AtomicInteger totalChunks = new AtomicInteger();
            final AtomicInteger totalEntities = new AtomicInteger();
            final AtomicInteger totalTileEntities = new AtomicInteger();
            final StringBuilder worldInfo = new StringBuilder();

            // Schedule sync task for world operations
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    for (World world : Bukkit.getWorlds()) {
                        Chunk[] loadedChunks = world.getLoadedChunks();
                        int chunks = loadedChunks.length;
                        int entities = world.getEntities().size();

                        int tileEntities = 0;
                        for (Chunk chunk : loadedChunks) {
                            tileEntities += chunk.getTileEntities().length;
                        }

                        totalChunks.addAndGet(chunks);
                        totalEntities.addAndGet(entities);
                        totalTileEntities.addAndGet(tileEntities);

                        worldInfo.append("\n&7- &f")
                                .append(world.getName())
                                .append(" &7(Chunks: &f").append(chunks)
                                .append("&7, Entities: &f").append(entities)
                                .append("&7, Tiles: &f").append(tileEntities)
                                .append("&7)");
                    }

                    // Now back to async for sending messages
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        // Create placeholder map
                        HashMap<String, String> placeholders = new HashMap<>();
                        placeholders.put("osName", osName);
                        placeholders.put("osVersion", osVersion);
                        placeholders.put("osArch", osArch);
                        placeholders.put("availableProcessors", String.valueOf(availableProcessors));
                        placeholders.put("maxRam", String.valueOf(maxMemory));
                        placeholders.put("allocatedMemory", String.valueOf(totalMemory));
                        placeholders.put("freeMemory", String.valueOf(freeMemory));
                        placeholders.put("totalDiskSpace", String.valueOf(diskSpace[0]));
                        placeholders.put("freeDiskSpace", String.valueOf(diskSpace[1]));
                        placeholders.put("tps", tps);
                        placeholders.put("worldCount", String.valueOf(Bukkit.getWorlds().size()));
                        placeholders.put("totalChunks", String.valueOf(totalChunks.get()));
                        placeholders.put("totalEntities", String.valueOf(totalEntities.get()));
                        placeholders.put("totalTileEntities", String.valueOf(totalTileEntities.get()));
                        placeholders.put("worldInfo", worldInfo.toString());

                        // Send messages from lang_en.yml with placeholders replaced
                        List<String> messages = lang.getList("commands.gc.message");
                        for (String line : messages) {
                            sender.sendMessage(lang.format(line, placeholders));
                        }
                    });
                } catch (Exception e) {
                    sender.sendMessage(lang.get("error-prefix") + (lang.get("commands.gc.world-info-error")));
                    plugin.getLogger().log(Level.SEVERE, "Error gathering world information in GC command", e);
                }
            });
        });
        return true;
    }

    private String formatTps(double tps) {
        tps = Math.min(tps, 20.0);
        String color;
        if (tps >= 18.0) {
            color = "&a";
        } else if (tps >= 15.0) {
            color = "&e";
        } else {
            color = "&c";
        }
        return color + String.format("%.1f", tps);
    }
}