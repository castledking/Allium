package codes.castled.allium.managers.core;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityAlertManager {

    public enum AlertType {
        GAMEMODE_AUDIT,
        CHAT_FILTER,
        MODGUARD
    }

    private final PluginStart plugin;
    private final Map<UUID, EnumSet<AlertType>> disabledAlerts = new ConcurrentHashMap<>();

    public SecurityAlertManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(Player player, AlertType type) {
        EnumSet<AlertType> disabled = disabledAlerts.get(player.getUniqueId());
        return disabled == null || !disabled.contains(type);
    }

    public boolean toggle(Player player, AlertType type) {
        boolean enabled = !isEnabled(player, type);
        setEnabled(player, type, enabled);
        return enabled;
    }

    public void setEnabled(Player player, AlertType type, boolean enabled) {
        disabledAlerts.compute(player.getUniqueId(), (uuid, disabled) -> {
            EnumSet<AlertType> next = disabled == null ? EnumSet.noneOf(AlertType.class) : EnumSet.copyOf(disabled);
            if (enabled) {
                next.remove(type);
            } else {
                next.add(type);
            }
            return next.isEmpty() ? null : next;
        });
    }

    public void broadcastGamemodeAudit(Player target, String cause, GameMode from, GameMode to) {
        if (target.hasPermission("allium.gamemode.creative")) {
            return;
        }

        String fromText = from == null ? "unknown" : from.name();
        String toText = to == null ? "unknown" : to.name();
        String consoleMessage = "[Alerts] Gamemode audit: " + target.getName() + " (" + target.getUniqueId() + ") "
            + cause + " [" + fromText + " -> " + toText + "] in " + target.getWorld().getName();
        plugin.getLogger().info(consoleMessage);

        var playerMessage = Text.colorize(
            "&8[&cAlerts&8] &e" + target.getName() + " &7" + cause
                + " &8[&f" + fromText + " &7-> &f" + toText + "&8] &7in &f" + target.getWorld().getName()
        );

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (!online.hasPermission("allium.alerts")) {
                continue;
            }
            if (!isEnabled(online, AlertType.GAMEMODE_AUDIT)) {
                continue;
            }
            online.sendMessage(playerMessage);
        }
    }

    public void broadcastChatFilterAlert(Player target, String message, String filterType, String reason) {
        String consoleMessage = "[Alerts] Chat filter: " + target.getName() + " (" + target.getUniqueId() + ") "
            + filterType + " - " + reason + ": \"" + message + "\"";
        plugin.getLogger().info(consoleMessage);

        var playerMessage = Text.colorize(
            "&8[&cAlerts&8] &e" + target.getName() + " &7triggered " + filterType
                + " &8[&f" + reason + "&8]\n"
                + "&7Message: &r\"" + message + "\""
        );

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (!online.hasPermission("allium.alerts")) {
                continue;
            }
            if (!isEnabled(online, AlertType.CHAT_FILTER)) {
                continue;
            }
            online.sendMessage(playerMessage);
        }
    }

    public void broadcastModGuardAlert(Player target, String message, String staffPermission) {
        var playerMessage = Text.colorize(message);

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.hasPermission(staffPermission)) {
                continue;
            }
            if (!isEnabled(online, AlertType.MODGUARD)) {
                continue;
            }
            online.sendMessage(playerMessage);
        }
    }
}
