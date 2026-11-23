package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
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
                    .filter(p -> !(context.sender() instanceof Player viewer)
                            || (BukkitServerThread.isFolia()
                            ? PlayerNameVisibility.canSeeTarget(viewer, p)
                            : PlayerNameVisibility.canSee(viewer, p)))
                    .count();
            if (server.getWorlds() != null) {
                worlds = server.getWorlds();
            }
        }

        var messages=plugin.getMessageManager();
        messages.send(context.sender(),Messages.GC_RUNTIME,"{uptime}",formatDuration(uptimeMillis),"{tps}",tps);
        String diskText=disk.isEmpty()?"":messages.resolve(Messages.GC_DISK,"{disk}",disk);
        messages.send(context.sender(),Messages.GC_MEMORY,"{used}",Long.toString(usedMb),"{allocated}",Long.toString(allocMb),"{max}",Long.toString(maxMb),"{disk}",diskText);
        messages.send(context.sender(),Messages.GC_PLAYERS,"{online}",Long.toString(visiblePlayers),"{max}",Integer.toString(maxPlayers),"{worlds}",Integer.toString(worlds.size()));
        for (World world : worlds) {
            int chunks = world.getLoadedChunks() == null ? 0 : world.getLoadedChunks().length;
            int entities = world.getEntities() == null ? 0 : world.getEntities().size();
            boolean paused = pauseCoordinator != null && pauseCoordinator.isPaused(world);
            messages.send(context.sender(),Messages.GC_WORLD,"{world}",world.getName(),"{chunks}",Integer.toString(chunks),"{entities}",Integer.toString(entities),"{paused}",paused?messages.resolve(Messages.GC_PAUSED):"");
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

    /** The last minute's TPS, as {@code /gc} shows it first; {@code N/A} on servers without Paper's {@code getTPS}. */
    public static String tps() {
        String all = readTps();
        if (all.startsWith("N/A")) {
            return "N/A";
        }
        int comma = all.indexOf(',');
        return comma < 0 ? all : all.substring(0, comma);
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
        } catch (java.lang.reflect.InvocationTargetException invoked) {
            if (invoked.getCause() instanceof UnsupportedOperationException && BukkitServerThread.isFolia()) {
                return "N/A (Folia regional)";
            }
        } catch (ReflectiveOperationException | LinkageError | UnsupportedOperationException ignored) {
        }
        return BukkitServerThread.isFolia() ? "N/A (Folia regional)" : "N/A (Spigot)";
    }

    public static String formatDuration(long millis) {
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
