package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.HashMap;
import java.util.List;

public class GC implements CommandExecutor {
    private final PluginStart plugin;

    public GC(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        LangManager lang = plugin.getLangManager();
        if (lang == null) {
            sender.sendMessage("Error: Language system not initialized");
            return true;
        }

        // Check permissions
        if (!sender.hasPermission("core.gc")) {
            sender.sendMessage(deserialize(lang.get("commands.gc.no_permission"), sender));
            return true;
        }

        // Send header from lang.yml
        String usageHeader = lang.get("commands.gc.usage_header");
        sender.sendMessage(deserialize(usageHeader, sender));

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
                try {
                    for (FileStore store : FileSystems.getDefault().getFileStores()) {
                        totalDiskSpace += store.getTotalSpace();
                        freeDiskSpace += store.getUsableSpace();
                    }
                } catch (Exception e) {
                    totalDiskSpace = -1;
                    freeDiskSpace = -1;
                }

                totalDiskSpace /= (1024 * 1024 * 1024);
                freeDiskSpace /= (1024 * 1024 * 1024);

                // Get TPS using Spigot's API
                String tps = "20";

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
                    sender.sendMessage(deserialize(lang.format(line, placeholders), sender));
                }
            } catch (Exception e) {
                sender.sendMessage("Error: Failed to process system information");
            }
        });

        return true;
    }


    private String deserialize(String message, CommandSender sender) {
        // Convert color codes for both console and players
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
            // Console understands § codes directly
            return message.replace('&', '§');
        } else {
            // Players need the color codes translated through ChatColor
            return ChatColor.translateAlternateColorCodes('&', message);
        }
    }
}