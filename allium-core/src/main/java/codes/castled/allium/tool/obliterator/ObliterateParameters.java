package codes.castled.allium.tool.obliterator;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.definition.ToolParameters;
import codes.castled.allium.tool.disarmer.DisarmParameters;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerRules;

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
 * The {@code allium:obliterate_phantoms} parameters one definition supplies. They tune the use;
 * the steps (scan, protection, recheck, spend first, kill, refund when nothing died) belong to
 * {@code ObliterateBehavior}.
 *
 * @param entityTypes    namespaced types that are targets, {@code minecraft:phantom} by default
 * @param requireUnnamed spare targets with a custom name (name tags, plugins)
 * @param maxTargets     the nearest this many targets are taken per use
 * @param attributedKill damage each target as the player (protection, arenas and loot all see it)
 *                       rather than setting its health to 0
 * @param importLegacy   adopt pre-recode Phantom Obliterators in place at use
 */
public record ObliterateParameters(
        DisarmerRules rules,
        List<Map<Integer, ProviderKey>> chargeVariants,
        double radius,
        Set<String> entityTypes,
        boolean requireUnnamed,
        boolean excludeNpcs,
        int maxTargets,
        boolean attributedKill,
        long clickGuardMillis,
        long statusCooldownMillis,
        boolean importLegacy
) implements ToolParameters {

    public ObliterateParameters {
        Objects.requireNonNull(rules, "rules");
        entityTypes = Set.copyOf(entityTypes);
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

    /** As {@link DisarmParameters#variantFor}: the appearance for this state, if the tool is in a set. */
    public Optional<ProviderKey> variantFor(ProviderKey current, DisarmerCharges state) {
        int slot = state.broken() ? DisarmParameters.BROKEN_VARIANT : state.charges();
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
