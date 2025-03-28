package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.lang.LangManager;
import net.survivalfun.core.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.List;

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
            return true;
        }

        // Check permissions
        if (!sender.hasPermission("core.gc")) {
            sender.sendMessage(ColorUtils.colorize(lang.get("commands.gc.no_permission")));
            return true;
        }

        // Send header from lang.yml
        String usageHeader = lang.get("commands.gc.usage_header");
        sender.sendMessage(ColorUtils.colorize(usageHeader));

        // Run command in async (to avoid lag for disk/memory checks)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Get system information
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory() / 1024 / 1024;
                long totalMemory = runtime.totalMemory() / 1024 / 1024;
                long freeMemory = runtime.freeMemory() / 1024 / 1024;

                OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
                String osName = osMXBean.getName();
                String osVersion = osMXBean.getVersion();
                String osArch = osMXBean.getArch();
                int availableProcessors = osMXBean.getAvailableProcessors();

                long totalDiskSpace = 0L, freeDiskSpace = 0L;
                double[] tpsArray = new double[]{20.0, 20.0, 20.0}; // Default values
                try {
                    // This is the proper Paper API method
                    tpsArray = Bukkit.getTPS(); // No need for instanceof check
                } catch (NoSuchMethodError e) {
                    // Fallback if not using Paper
                    plugin.getLogger().warning("Could not get TPS - Paper API not available");
                }

                String tps = formatTps(tpsArray[0]);

                // Create placeholder map
                HashMap<String, String> placeholders = new HashMap<>();
                placeholders.put("osName", osName);
                placeholders.put("osVersion", osVersion);
                placeholders.put("osArch", osArch);
                placeholders.put("availableProcessors", String.valueOf(availableProcessors));
                placeholders.put("maxRam", String.valueOf(maxMemory));
                placeholders.put("allocatedMemory", String.valueOf(totalMemory));
                placeholders.put("freeMemory", String.valueOf(freeMemory));
                placeholders.put("totalDiskSpace", String.valueOf(totalDiskSpace));
                placeholders.put("freeDiskSpace", String.valueOf(freeDiskSpace));
                placeholders.put("tps", tps);

                // Send messages from lang.yml with placeholders replaced
                List<String> messages = lang.getList("commands.gc.message");
                for (String line : messages) {
                    sender.sendMessage(ColorUtils.colorize(lang.format(line, placeholders)));
                }
            } catch (Exception e) {
                sender.sendMessage("Error: Failed to process system information");
            }
        });

        return true;
    }

    private String formatTps(double tps) {
        // Paper sometimes reports slightly above 20, so we cap it
        tps = Math.min(tps, 20.0);

        // Determine color based on TPS health
        String color;
        if (tps >= 18.0) {
            color = "&a"; // Green - healthy
        } else if (tps >= 15.0) {
            color = "&e"; // Yellow - minor lag
        } else {
            color = "&c"; // Red - severe lag
        }

        return color + String.format("%.1f", tps);
    }
}