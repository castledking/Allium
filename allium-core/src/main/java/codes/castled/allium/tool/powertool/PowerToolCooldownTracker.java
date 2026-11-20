package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks scoped cooldowns ({@code player}, {@code item}, {@code global}, or shared {@code cooldown-id})
 * and click-guard debounce windows for powertools.
 */
public final class PowerToolCooldownTracker {

    private final LongSupplier clock;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> clickGuards = new ConcurrentHashMap<>();

    public PowerToolCooldownTracker(LongSupplier clock) {
        this.clock = clock;
    }

    /** Returns {@code true} if the click is admitted (not inside the debounce window). */
    public boolean tryAcquireClickGuard(UUID playerId, PowerToolTrigger trigger, long guardMillis) {
        if (guardMillis <= 0L) {
            return true;
        }
        long now = clock.getAsLong();
        String key = playerId + ":" + trigger.id();
        Long last = clickGuards.get(key);
        if (last != null && (now - last) < guardMillis) {
            return false;
        }
        clickGuards.put(key, now);
        return true;
    }

    public long remainingMillis(
            UUID playerId,
            String toolId,
            @Nullable String instanceUuid,
            PowerToolTrigger trigger,
            String slotId,
            PowerToolCooldownScope scope,
            @Nullable String customCooldownId
    ) {
        String key = key(playerId, toolId, instanceUuid, trigger, slotId, scope, customCooldownId);
        Long expires = cooldowns.get(key);
        if (expires == null) {
            return 0L;
        }
        long rem = expires - clock.getAsLong();
        if (rem <= 0L) {
            cooldowns.remove(key, expires);
            return 0L;
        }
        return rem;
    }

    public void setCooldown(
            UUID playerId,
            String toolId,
            @Nullable String instanceUuid,
            PowerToolTrigger trigger,
            String slotId,
            PowerToolCooldownScope scope,
            @Nullable String customCooldownId,
            long durationMillis
    ) {
        if (durationMillis <= 0L) {
            return;
        }
        String key = key(playerId, toolId, instanceUuid, trigger, slotId, scope, customCooldownId);
        cooldowns.put(key, clock.getAsLong() + durationMillis);
    }

    public void clearPlayer(UUID playerId) {
        String guardPrefix = playerId + ":";
        clickGuards.keySet().removeIf(k -> k.startsWith(guardPrefix));
        String cdPrefix = "player:" + playerId + ":";
        cooldowns.keySet().removeIf(k -> k.startsWith(cdPrefix));
    }

    public void cleanupExpired() {
        long now = clock.getAsLong();
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
    }

    public void clearAll() {
        cooldowns.clear();
        clickGuards.clear();
    }

    static String key(
            UUID playerId,
            String toolId,
            @Nullable String instanceUuid,
            PowerToolTrigger trigger,
            String slotId,
            PowerToolCooldownScope scope,
            @Nullable String customCooldownId
    ) {
        String bucket = (customCooldownId != null && !customCooldownId.isBlank())
                ? "id:" + customCooldownId.trim().toLowerCase(java.util.Locale.ROOT)
                : toolId + ":" + trigger.id() + ":" + slotId;
        return switch (scope) {
            case GLOBAL -> "global:" + bucket;
            case ITEM -> "item:" + (instanceUuid != null && !instanceUuid.isBlank() ? instanceUuid : toolId) + ":" + trigger.id() + ":" + slotId;
            case PLAYER -> "player:" + playerId + ":" + bucket;
        };
    }
}
