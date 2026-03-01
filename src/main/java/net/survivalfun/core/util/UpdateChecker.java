package net.survivalfun.core.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Scanner;
import java.util.function.Consumer;

/**
 * Spigot update checker. See: https://www.spigotmc.org/wiki/creating-an-update-checker-that-checks-for-updates/
 */
public class UpdateChecker {

    private static final String API_URL = "https://api.spigotmc.org/legacy/update.php?resource=%d";

    private final JavaPlugin plugin;
    private final int resourceId;

    public UpdateChecker(JavaPlugin plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    /**
     * Fetches the latest version from Spigot API asynchronously.
     *
     * @param consumer Receives the latest version string, or null on error
     */
    public void getVersion(Consumer<String> consumer) {
        SchedulerAdapter.runAsync(() -> {
            try (InputStream is = new URL(String.format(API_URL, resourceId)).openStream();
                 Scanner scanner = new Scanner(is)) {
                if (scanner.hasNext()) {
                    String version = scanner.next().trim();
                    SchedulerAdapter.run(() -> consumer.accept(version));
                } else {
                    SchedulerAdapter.run(() -> consumer.accept(null));
                }
            } catch (IOException e) {
                plugin.getLogger().info("Unable to check for updates: " + e.getMessage());
                SchedulerAdapter.run(() -> consumer.accept(null));
            }
        });
    }

    /**
     * Checks for updates and notifies based on config.
     * Call this after plugin enable with a short delay.
     *
     * @param notifyMode off = disabled, console = log only, in-game = allium.admin on join, both = console + in-game
     */
    public static void checkForUpdates(JavaPlugin plugin, int resourceId, boolean enabled,
                                       String notifyMode, String currentVersion) {
        if (!enabled || notifyMode == null || "off".equalsIgnoreCase(notifyMode.trim())) {
            return;
        }

        boolean notifyConsole = "console".equalsIgnoreCase(notifyMode) || "both".equalsIgnoreCase(notifyMode);
        boolean notifyInGame = "in-game".equalsIgnoreCase(notifyMode) || "both".equalsIgnoreCase(notifyMode);

        new UpdateChecker(plugin, resourceId).getVersion(latestVersion -> {
            if (latestVersion == null || latestVersion.isEmpty()) {
                return;
            }
            if (currentVersion.equals(latestVersion)) {
                return;
            }

            String message = "[Allium] A new update is available! You are on v" + currentVersion
                    + ", latest is v" + latestVersion + ". Download: https://www.spigotmc.org/resources/allium.126462/";

            if (notifyConsole) {
                plugin.getLogger().info(message);
            }

            if (notifyInGame) {
                net.survivalfun.core.listeners.UpdateCheckerListener.setUpdateAvailable(message);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("allium.admin")) {
                        p.sendMessage(net.kyori.adventure.text.Component.text(message)
                                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
                    }
                }
            }
        });
    }
}
