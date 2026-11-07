package codes.castled.allium.tool.disarmer;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which entities the Mob Disarmer may target.
 *
 * <p>Supported targets are non-player mobs. Players, armor stands and other non-mob entities are
 * never targets. Configured entity types are excluded (bosses by default), as are NPCs when
 * {@code excludeNpcs} is set. Protection is checked separately.
 */
public final class DisarmTargetPolicy {

    /** Vanilla bosses and boss-like mobs, excluded unless the server configures otherwise. */
    public static final Set<String> DEFAULT_EXCLUDED_TYPES = Set.of(
            "minecraft:ender_dragon",
            "minecraft:wither",
            "minecraft:elder_guardian",
            "minecraft:warden"
    );

    public enum Verdict {
        ALLOWED,
        PLAYER,
        NOT_A_MOB,
        EXCLUDED_TYPE,
        NPC
    }

    /**
     * Facts about a clicked entity, gathered by the platform adapter.
     *
     * @param entityType namespaced entity type, e.g. {@code minecraft:zombie}
     * @param mob        whether the entity is a non-player mob that can carry equipment
     * @param npc        whether another plugin marks the entity as an NPC
     */
    public record TargetFacts(String entityType, boolean player, boolean mob, boolean npc) {
        public TargetFacts {
            Objects.requireNonNull(entityType, "entityType");
        }
    }

    private final Set<String> excludedTypes;
    private final boolean excludeNpcs;

    public DisarmTargetPolicy(Collection<String> excludedTypes, boolean excludeNpcs) {
        Set<String> normalized = new TreeSet<>();
        for (String type : excludedTypes) {
            String key = normalizeType(type);
            if (key != null) {
                normalized.add(key);
            }
        }
        this.excludedTypes = Set.copyOf(normalized);
        this.excludeNpcs = excludeNpcs;
    }

    public Verdict evaluate(TargetFacts facts) {
        Objects.requireNonNull(facts, "facts");
        if (facts.player()) {
            return Verdict.PLAYER;
        }
        if (!facts.mob()) {
            return Verdict.NOT_A_MOB;
        }
        if (excludeNpcs && facts.npc()) {
            return Verdict.NPC;
        }
        String type = normalizeType(facts.entityType());
        if (type != null && excludedTypes.contains(type)) {
            return Verdict.EXCLUDED_TYPE;
        }
        return Verdict.ALLOWED;
    }

    public Set<String> excludedTypes() {
        return excludedTypes;
    }

    /**
     * Normalizes {@code ZOMBIE}, {@code zombie} and {@code minecraft:zombie} to
     * {@code minecraft:zombie}; returns {@code null} for blank input.
     */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }
}
