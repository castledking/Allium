package codes.castled.allium.modguard;

import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Which detections stop a player joining, and disconnecting them for it. */
final class AccessPolicy {
    private final TaskScheduler scheduler;
    private final ModGuard modGuard;
    private final Map<String, Boolean> choices = new HashMap<>();
    final NetworkDisconnect disconnects;
    boolean enabled = true;

    AccessPolicy(Plugin plugin, TaskScheduler scheduler, ModGuard modGuard) {
        this.scheduler = scheduler;
        this.modGuard = modGuard;
        this.disconnects = new NetworkDisconnect(plugin);
    }

    void configure(ConfigurationSection config) {
        Map<String, Boolean> next = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("access-control.mods");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                if (section.isBoolean(id + ".allow-join")) next.put(id.toLowerCase(Locale.ROOT), section.getBoolean(id + ".allow-join"));
            }
        }
        disconnects.configure(NetworkDisconnect.Mode.parse(config.getString("access-control.disconnect-mode", "AUTO")));
        enabled = config.getBoolean("access-control.enabled", true);
        choices.clear();
        choices.putAll(next);
    }

    /** Whether a player with this detection may stay. Unlisted rules allow only INFO detections. */
    boolean allowed(String rule, Detection.Severity severity) {
        return choices.getOrDefault(rule.toLowerCase(Locale.ROOT), severity == Detection.Severity.INFO);
    }

    void check(ModGuardSession session) {
        if (!enabled || session == null || session.accessScheduled || session.excluded) return;
        boolean refused = session.findings.values().stream().anyMatch(f -> !allowed(f.rule(), f.severity()));
        if (!refused) return;
        session.accessScheduled = true;
        scheduler.runOnServer(() -> {
            session.accessScheduled = false;
            Player player = Bukkit.getPlayer(session.uuid);
            if (!enabled || modGuard.session(session.uuid) != session || player == null || !player.isOnline()
                    || player.isOp() || modGuard.bypassed(player)) return;
            List<String> names = session.findings.values().stream()
                    .filter(f -> !allowed(f.rule(), f.severity()))
                    .map(Detection.Finding::name).distinct().sorted().limit(8).toList();
            if (!names.isEmpty()) disconnects.disconnect(player, modGuard.kickMessage(String.join(", ", names), player.getName()));
        });
    }
}
