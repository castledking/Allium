package codes.castled.allium.modguard;

import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Which detections stop a player joining, and disconnecting them for it. */
final class AccessPolicy {
    private final Plugin plugin;
    private final TaskScheduler scheduler;
    private final ModGuard modGuard;
    private volatile Policy policy = new Policy(true, Map.of());
    final NetworkDisconnect disconnects;

    AccessPolicy(Plugin plugin, TaskScheduler scheduler, ModGuard modGuard) {
        this.plugin = plugin;
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
        policy = new Policy(config.getBoolean("access-control.enabled", true), Map.copyOf(next));
    }

    boolean enabled() {
        return policy.enabled();
    }

    void check(ModGuardSession session) {
        Policy current = policy;
        if (!current.enabled() || session == null || session.accessScheduled || session.excluded) return;
        boolean refused = session.findings.values().stream().anyMatch(f -> !current.allowed(f));
        if (!refused) return;
        session.accessScheduled = true;
        Player player = session.player;
        BukkitServerThread.runFor(plugin, scheduler, player, () -> {
            session.accessScheduled = false;
            Policy latest = policy;
            if (!latest.enabled() || !player.isOnline() || !modGuard.monitoring(session) || modGuard.bypassed(player)) return;
            List<String> names = session.findings.values().stream()
                    .filter(f -> !latest.allowed(f))
                    .map(Detection.Finding::name).distinct().sorted().limit(8).toList();
            if (!names.isEmpty()) disconnects.disconnect(player, modGuard.kickMessage(String.join(", ", names), player.getName()));
        }, () -> {}, 0);
    }

    private record Policy(boolean enabled, Map<String, Boolean> choices) {
        /** Unlisted rules permit only INFO findings. */
        boolean allowed(Detection.Finding finding) {
            return choices.getOrDefault(finding.rule().toLowerCase(Locale.ROOT), finding.severity() == Detection.Severity.INFO);
        }
    }
}
