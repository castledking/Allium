package codes.castled.allium.tradingcards.morph;

import java.util.List;
import java.util.Locale;
import org.bukkit.entity.EntityType;

/**
 * Which mobs a morph may imitate, and which of them a mob will fight back.
 *
 * <p>Kept apart from the service so the rule is inspectable and testable without
 * a running server — the lists are the design, and the service is the plumbing.
 */
public final class MorphRules {

    /**
     * Mob types a FABLED card may morph into.
     *
     * <p>Only entities with a LibsDisguises type and a sensible body. A player
     * or a boat would be a disguise with no mob behaviour behind it, and the
     * flight and hostile rules below assume something that can be attacked.
     */
    public static final List<EntityType> MORPHABLE = List.of(
        EntityType.ALLAY, EntityType.AXOLOTL, EntityType.BEE, EntityType.BLAZE,
        EntityType.CAT, EntityType.CHICKEN, EntityType.COD, EntityType.COW,
        EntityType.CREEPER, EntityType.DOLPHIN, EntityType.DROWNED, EntityType.ELDER_GUARDIAN,
        EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.EVOKER, EntityType.FOX,
        EntityType.FROG, EntityType.GHAST, EntityType.GUARDIAN, EntityType.HOGLIN,
        EntityType.HORSE, EntityType.HUSK, EntityType.IRON_GOLEM, EntityType.MAGMA_CUBE,
        EntityType.MOOSHROOM, EntityType.MULE, EntityType.OCELOT, EntityType.PANDA,
        EntityType.PARROT, EntityType.PIG, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
        EntityType.PILLAGER, EntityType.PLAYER, EntityType.POLAR_BEAR, EntityType.PUFFERFISH,
        EntityType.RABBIT, EntityType.RAVAGER, EntityType.SALMON, EntityType.SHEEP,
        EntityType.SHULKER, EntityType.SILVERFISH, EntityType.SKELETON,
        EntityType.SKELETON_HORSE, EntityType.SLIME, EntityType.SNOW_GOLEM, EntityType.SPIDER,
        EntityType.SQUID, EntityType.STRAY, EntityType.STRIDER, EntityType.TADPOLE,
        EntityType.TRADER_LLAMA, EntityType.TROPICAL_FISH, EntityType.TURTLE,
        EntityType.VEX, EntityType.VILLAGER, EntityType.WARDEN, EntityType.WITCH,
        EntityType.WITHER_SKELETON, EntityType.WOLF, EntityType.ZOGLIN, EntityType.ZOMBIE,
        EntityType.ZOMBIE_VILLAGER, EntityType.PHANTOM);

    /**
     * Types that will aggro a player wearing their own appearance.
     *
     * <p>These are the ones where the disguise would plausibly fool a mob: the
     * hostile mobs, plus the neutral ones that attack on provocation. A morphed
     * spider being attacked by a spider is the case that makes the feature feel
     * like something rather than a costume.
     */
    public static final List<EntityType> HOSTILE = List.of(
        EntityType.BLAZE, EntityType.CAVE_SPIDER, EntityType.CREEPER, EntityType.DROWNED,
        EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.EVOKER, EntityType.GHAST,
        EntityType.GUARDIAN, EntityType.HOGLIN, EntityType.HUSK, EntityType.MAGMA_CUBE,
        EntityType.PHANTOM, EntityType.PILLAGER, EntityType.RAVAGER, EntityType.SHULKER,
        EntityType.SILVERFISH, EntityType.SKELETON, EntityType.SLIME, EntityType.SPIDER,
        EntityType.STRAY, EntityType.VEX, EntityType.WARDEN, EntityType.WITCH,
        EntityType.WITHER, EntityType.WITHER_SKELETON, EntityType.ZOGLIN, EntityType.ZOMBIE,
        EntityType.ZOMBIE_VILLAGER, EntityType.PIGLIN_BRUTE,
        EntityType.BEE, EntityType.WOLF, EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM,
        EntityType.VINDICATOR);

    /**
     * Mobs allowed to fly while morphed.
     *
     * <p>Flight is the most abusable thing in the set, so it is opt-in per mob
     * and never on by default. Both are fliers with a real flight animation
     * rather than anything that could be used to cheese an area.
     */
    public static final List<EntityType> FLYING = List.of(
        EntityType.PARROT, EntityType.BEE, EntityType.PHANTOM, EntityType.GHAST,
        EntityType.VEX, EntityType.ALLAY);

    /**
     * Mob types that are exempt from the stealth rule.
     *
     * <p>Johnny the Vindicator is a vanilla pacifist with a custom name: he
     * never aggros, and a morphed player who happens to look like a vindicator
     * should not be dragged into a fight with him either. Any NAMED entity of
     * these types is exempt, not just one named Johnny, so a server that renames
     * its vindicators does not accidentally make them hostile to morphs.
     */
    public static final List<EntityType> NEVER_TARGETS_MORPHS = List.of(
        EntityType.VINDICATOR, EntityType.EVOKER);

    public static boolean isMorphable(EntityType type) {
        return type != null && MORPHABLE.contains(type);
    }

    public static boolean isHostile(EntityType type) {
        return type != null && HOSTILE.contains(type);
    }

    public static boolean canFly(EntityType type) {
        return type != null && FLYING.contains(type);
    }

    /**
     * True when this specific entity should be left alone.
     *
     * <p>Takes the entity rather than the type because the exemption is about a
     * named mob, and names are per-entity.
     */
    public static boolean isExempt(org.bukkit.entity.LivingEntity entity) {
        if (entity == null) return false;
        return isExempt(entity.getType(), entity.getCustomName());
    }

    /**
     * The same rule on plain values, so it can be tested without a server.
     *
     * <p>Split out because the entity form needs Bukkit and the rule itself does
     * not — the exemption is the one piece of targeting logic most likely to be
     * wrong and the easiest to get right with a test in hand.
     */
    public static boolean isExempt(EntityType type, String customName) {
        if (!NEVER_TARGETS_MORPHS.contains(type)) {
            return false;
        }
        return customName != null && !customName.isBlank();
    }

    /**
     * The whole targeting rule, on plain values.
     *
     * <p>This is the piece that decides whether a mob will fight a morphed
     * player, so it lives here as a pure function rather than inside the
     * service where it can only be reached with a running server.
     *
     * <p>In full: named vindicators and evokers are exempt; a non-hostile form is
     * never objected to; and a hostile form is invisible to mob AI until it has
     * struck something itself.
     *
     * @param form the entity type the player is disguised as, null if not morphed
     * @param provoked whether that player has already attacked something
     * @param stealthUntilAttacked the configured rule; false disables the gate
     * @param mob the mob doing the targeting
     * @param mobName the mob's custom name, null when unnamed
     */
    public static boolean morphMayTarget(EntityType form, boolean provoked,
                                         boolean stealthUntilAttacked,
                                         EntityType mob, String mobName) {
        if (isExempt(mob, mobName)) {
            return true;
        }
        if (!isHostile(form)) {
            // A morphed cow is not something a wolf objects to. Cancelling here
            // would make every animal form a free pass.
            return true;
        }
        return !stealthUntilAttacked || provoked;
    }

    /** The morphable type matching a card's mob, or null when it cannot morph. */
    public static EntityType resolve(String mobType) {
        if (mobType == null || mobType.isBlank()) {
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(mobType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return isMorphable(type) ? type : null;
    }

    /** Every morphable type, for a command listing. */
    public static List<String> names() {
        return MORPHABLE.stream().map(EntityType::name).sorted().toList();
    }
}
