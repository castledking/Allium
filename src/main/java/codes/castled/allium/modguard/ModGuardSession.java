package codes.castled.allium.modguard;

import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;

final class ModGuardSession {

    final UUID uuid;
    final String name;
    final UUID token = UUID.randomUUID();
    boolean started;
    boolean excluded;
    boolean accessScheduled;
    boolean alertScheduled;

    String brand = "unknown";
    final Set<String> brands = new LinkedHashSet<>();
    final Set<String> channels = new LinkedHashSet<>();
    final Map<String, Detection.Mod> mods = new LinkedHashMap<>();
    final Map<String, String> modSources = new LinkedHashMap<>();
    final Map<String, Detection.Finding> findings = new LinkedHashMap<>();
    final Set<String> notifiedRules = new HashSet<>();
    final List<String> queuedAlerts = new ArrayList<>();

    String probeStatus = "Join-only resource check not run";
    String lunarStatus = "Not available";
    long windowStart;
    int windowCount;
    Instant joinedAt = Instant.now();

    ModGuardSession(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }
}
