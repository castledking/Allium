package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class GcCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final TimePauseCoordinator pauseCoordinator;

    public GcCommand(PluginStart plugin, TimePauseCoordinator pauseCoordinator) {
        this.plugin = plugin;
        this.pauseCoordinator = pauseCoordinator;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.GC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (context.args().length != 0) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long allocMb = runtime.totalMemory() / (1024L * 1024L);
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        String tps = readTps();
        String disk = readDiskUsage();

        var server = plugin.getServer();
        long visiblePlayers = 0L;
        int maxPlayers = 0;
        List<World> worlds = List.of();
        if (server != null) {
            maxPlayers = server.getMaxPlayers();
            visiblePlayers = server.getOnlinePlayers().stream()
                    .filter(p -> !(context.sender() instanceof Player viewer) || PlayerNameVisibility.canSee(viewer, p))
                    .count();
            if (server.getWorlds() != null) {
                worlds = server.getWorlds();
            }
        }

        context.sender().sendMessage("[Allium] Runtime uptime: " + formatDuration(uptimeMillis) + ", TPS: " + tps);
        context.sender().sendMessage("[Allium] Memory: " + usedMb + " MB used / " + allocMb + " MB allocated / " + maxMb + " MB max"
                + (disk.isEmpty() ? "" : ", Disk free: " + disk));
        context.sender().sendMessage("[Allium] Players: " + visiblePlayers + "/" + maxPlayers
                + ", Worlds (" + worlds.size() + "):");
        for (World world : worlds) {
            int chunks = world.getLoadedChunks() == null ? 0 : world.getLoadedChunks().length;
            int entities = world.getEntities() == null ? 0 : world.getEntities().size();
            boolean paused = pauseCoordinator != null && pauseCoordinator.isPaused(world);
            context.sender().sendMessage("  - " + world.getName() + ": " + chunks + " chunks, "
                    + entities + " entities" + (paused ? " [TIME PAUSED]" : ""));
        }
    }

    private String readDiskUsage() {
        try {
            Path root = plugin.getDataFolder() != null ? plugin.getDataFolder().toPath() : Path.of(".");
            if (!Files.exists(root)) {
                root = Path.of(".");
            }
            FileStore store = Files.getFileStore(root);
            double freeGb = store.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
            double totalGb = store.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.ROOT, "%.1f/%.1f GB", freeGb, totalGb);
        } catch (Exception ignored) {
            return "";
        }
    }

    static String readTps() {
        try {
            if (Bukkit.getServer() == null) {
                return "N/A";
            }
            var method = Bukkit.class.getMethod("getTPS");
            Object result = method.invoke(null);
            if (result instanceof double[] tps && tps.length > 0) {
                StringBuilder sb = new StringBuilder();
                int count = Math.min(3, tps.length);
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[i])));
                }
                return sb.toString();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return "N/A (Spigot)";
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
