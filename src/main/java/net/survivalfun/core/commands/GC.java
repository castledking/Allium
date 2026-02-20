package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import net.survivalfun.core.managers.core.Text;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Lang lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            Text.sendDebugLog(ERROR, "LanguageManager not initialized when executing GC command");
            return true;
        }

        if (!sender.hasPermission("allium.gc")) {
            sender.sendMessage(lang.get("error-prefix")
                    + lang.get("no-permission"));
            return true;
        }

        sender.sendMessage(lang.get("gc.usage-header"));

        // Run everything on Folia's GlobalRegionScheduler (safe for Bukkit API)
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                // Declare as local variables
                final Runtime runtime = Runtime.getRuntime();
                final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();

                // System information
                final long maxMemory = runtime.maxMemory() / 1024 / 1024;
                final long totalMemory = runtime.totalMemory() / 1024 / 1024;
                final long freeMemory = runtime.freeMemory() / 1024 / 1024;

                final String osName = osMXBean.getName();
                final String osVersion = osMXBean.getVersion();
                final String osArch = osMXBean.getArch();
                final int availableProcessors = osMXBean.getAvailableProcessors();

                // Disk space information
                final long[] diskSpace = {0L, 0L}; // totalDiskSpace, freeDiskSpace
                try {
                    File serverRoot = new File(".").getAbsoluteFile();
                    FileStore store = Files.getFileStore(serverRoot.toPath());
                    diskSpace[0] = store.getTotalSpace() / 1024 / 1024 / 1024;
                    diskSpace[1] = store.getUsableSpace() / 1024 / 1024 / 1024;
                } catch (Exception e) {
                    Text.sendDebugLog(WARN, "Could not get disk space information", e);
                    sender.sendMessage(lang.get("error-prefix") + (lang.get("gc.disk-space-error")));
                }

                // TPS information
                final double[] tpsArray = new double[]{20.0, 20.0, 20.0};
                try {
                    System.arraycopy(Bukkit.getTPS(), 0, tpsArray, 0, 3);
                } catch (NoSuchMethodError e) {
                    Text.sendDebugLog(WARN, "Could not get TPS - Paper API not available", e);
                }
                final String tps = formatTps(tpsArray[0]);

                final AtomicInteger totalChunks = new AtomicInteger();
                final AtomicInteger totalEntities = new AtomicInteger();
                // Tile entities require region-thread access per chunk on Folia.
                // We avoid enumerating them here to remain Folia-safe.
                final AtomicInteger totalTileEntities = new AtomicInteger();
                final boolean folia = SchedulerAdapter.isFolia();
                final StringBuilder worldInfo = new StringBuilder();

                // World operations (safe here on GlobalRegionScheduler)
                for (World world : Bukkit.getWorlds()) {
                    Chunk[] loadedChunks = world.getLoadedChunks();
                    int chunks = loadedChunks.length;
                    int entities = world.getEntities().size();

                    int tileEntities;
                    if (folia) {
                        // Tile entity counting is skipped on Folia to avoid async world access
                        tileEntities = 0;
                    } else {
                        int tiles = 0;
                        for (Chunk loadedChunk : loadedChunks) {
                            try {
                                tiles += loadedChunk.getTileEntities().length;
                            } catch (Throwable ignored) {
                                // Some platforms may not support this call; keep graceful fallback
                            }
                        }
                        tileEntities = tiles;
                    }

                    totalChunks.addAndGet(chunks);
                    totalEntities.addAndGet(entities);
                    totalTileEntities.addAndGet(tileEntities);

                    String tileDisplay = folia ? "" : "Tiles: &f" + tileEntities;

                    worldInfo.append("\n&7- &f")
                            .append(world.getName())
                            .append(" &7(Chunks: &f").append(chunks)
                            .append("&7, Entities: &f").append(entities)
                            .append(tileDisplay)
                            .append("&7)");
                }

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

                // Calculate server uptime
                long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
                long uptimeSeconds = uptimeMillis / 1000;
                long uptimeMinutes = uptimeSeconds / 60;
                long uptimeHours = uptimeMinutes / 60;
                long uptimeDays = uptimeHours / 24;

                // Build uptime string only showing non-zero units
                StringBuilder uptimeBuilder = new StringBuilder();
                if (uptimeDays > 0) uptimeBuilder.append(String.format("%d days, ", uptimeDays));
                if (uptimeHours % 24 > 0 || uptimeBuilder.length() > 0) uptimeBuilder.append(String.format("%d hours, ", uptimeHours % 24));
                if (uptimeMinutes % 60 > 0 || uptimeBuilder.length() > 0) uptimeBuilder.append(String.format("%d minutes, ", uptimeMinutes % 60));
                if (uptimeSeconds % 60 > 0 || uptimeBuilder.length() == 0) uptimeBuilder.append(String.format("%d seconds", uptimeSeconds % 60));
                if (uptimeSeconds % 60 == 0 && uptimeBuilder.length() > 0) {
                    uptimeBuilder.setLength(uptimeBuilder.length() - 2);
                }

                sender.sendMessage(lang.get("gc.uptime").replace("{uptime}", uptimeBuilder.toString()));

                // Send messages from lang.yml with placeholders replaced
                List<String> messages = lang.getList("gc.message");
                for (String line : messages) {
                    sender.sendMessage(lang.format(line, placeholders));
                }
            } catch (Exception e) {
                sender.sendMessage(lang.get("error-prefix") + (lang.get("gc.world-info-error")));
                Text.sendDebugLog(ERROR, "Error executing GC command", e);
            }
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