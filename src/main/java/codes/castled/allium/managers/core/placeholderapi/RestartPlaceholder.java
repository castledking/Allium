package codes.castled.allium.managers.core.placeholderapi;

import codes.castled.allium.PluginStart;
import codes.castled.allium.commands.AutoRestartCommand;
import codes.castled.allium.managers.core.Text;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for auto-restart related placeholders.
 *
 * <p>Supported placeholders:</p>
 * <ul>
 *   <li>{@code %allium_restart_time%} – human-readable time until next restart (e.g. "1h 30m")</li>
 *   <li>{@code %allium_restart_seconds%} – seconds until next restart</li>
 *   <li>{@code %allium_restart_scheduled%} – "true" if a restart is scheduled, "false" otherwise</li>
 * </ul>
 */
public class RestartPlaceholder {

    private final PluginStart plugin;

    public RestartPlaceholder(PluginStart plugin) {
        this.plugin = plugin;
    }

    public String onPlaceholderRequest(Player player, @NotNull String params) {
        AutoRestartCommand ar = plugin.getAutoRestartCommand();
        if (ar == null) {
            return null;
        }

        return switch (params) {
            case "restart_time" -> {
                if (!ar.isRestartScheduled()) {
                    yield "";
                }
                long seconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(
                        ar.getRestartTime() - System.currentTimeMillis()));
                yield Text.formatTime(seconds);
            }
            case "restart_seconds" -> {
                if (!ar.isRestartScheduled()) {
                    yield "0";
                }
                long seconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(
                        ar.getRestartTime() - System.currentTimeMillis()));
                yield String.valueOf(seconds);
            }
            case "restart_scheduled" -> String.valueOf(ar.isRestartScheduled());
            default -> null;
        };
    }
}
