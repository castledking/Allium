package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ToolParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code allium:disarm} parameters one definition supplies. They tune the disarm; they
 * cannot change its steps, which {@code DisarmBehavior} owns.
 *
 * @param chargeVariants appearance sets; a tool shown as any item in a set is swapped to the
 *                       set's entry for its new charge count ({@link #BROKEN_VARIANT} for broken)
 * @param radius         maximum player-to-mob distance in blocks; 0 leaves reach to the server
 */
public record DisarmParameters(
        DisarmerRules rules,
        List<Map<Integer, ProviderKey>> chargeVariants,
        double radius,
        DisarmTargetPolicy targetPolicy,
        DisarmDropRule dropRule,
        long pickupBlockMillis,
        long clickGuardMillis,
        long statusCooldownMillis
) implements ToolParameters {

    public static final int BROKEN_VARIANT = -1;

    public DisarmParameters {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(targetPolicy, "targetPolicy");
        Objects.requireNonNull(dropRule, "dropRule");
        List<Map<Integer, ProviderKey>> sets = new ArrayList<>();
        for (Map<Integer, ProviderKey> set : chargeVariants) {
            sets.add(Collections.unmodifiableMap(new LinkedHashMap<>(set)));
        }
        chargeVariants = List.copyOf(sets);
    }

    @Override
    public Set<ProviderKey> extraAppearances() {
        Set<ProviderKey> keys = new LinkedHashSet<>();
        chargeVariants.forEach(set -> keys.addAll(set.values()));
        return keys;
    }

    /**
     * The appearance a tool currently shown as {@code current} should have for this state, or
     * empty when it is in no variant set or the set has no entry for the state. A broken tool
     * without a {@code broken} entry uses the {@code 0} entry.
     */
    public Optional<ProviderKey> variantFor(ProviderKey current, DisarmerCharges state) {
        int slot = state.broken() ? BROKEN_VARIANT : state.charges();
        for (Map<Integer, ProviderKey> set : chargeVariants) {
            if (set.containsValue(current)) {
                ProviderKey target = set.get(slot);
                if (target == null && state.broken()) {
                    target = set.get(0);
                }
                return Optional.ofNullable(target);
            }
        }
        return Optional.empty();
    }
}
