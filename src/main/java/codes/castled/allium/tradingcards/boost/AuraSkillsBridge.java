package codes.castled.allium.tradingcards.boost;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.stat.Stat;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.stat.Stats;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier;
import java.util.logging.Logger;
import java.util.function.BiConsumer;

/**
 * The one place AuraSkills is touched.
 *
 * <p>Isolated because AuraSkills has two sharp edges that are easy to get wrong
 * and invisible when they are wrong:
 *
 * <ul>
 *   <li><b>Modifiers persist to disk.</b> They are written on logout unless
 *       {@code setNonPersistent()} is called, so a boost applied for an
 *       equipped card survives a relog with no card in the slot. The player
 *       keeps a buff they cannot see and cannot get rid of.
 *   <li><b>Modifiers do not expire on their own.</b> {@code makeTemporary()}
 *       only sets a field; the expiry queue lives in an internal class the API
 *       does not expose. Every boost here is therefore removed by
 *       {@link #remove} on unequip rather than being left to time out.
 * </ul>
 *
 * <p>Every modifier is named {@code card:<player>:<boost>}. AuraSkills keys
 * modifiers by name in a concurrent map and silently replaces on a collision,
 * so the name is what stops two cards on one player overwriting each other and
 * leaving a boost behind forever.
 */
public final class AuraSkillsBridge {

    private final Logger logger;
    private final String namespace;

    public AuraSkillsBridge(Logger logger, String namespace) {
        this.logger = logger;
        this.namespace = namespace;
    }

    /** True when AuraSkills is loaded and its API is usable. */
    public boolean isAvailable() {
        try {
            AuraSkillsApi.get();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs {@code work} with a loaded user, or does nothing.
     *
     * <p>Returns false when the user is not loaded. An offline player's
     * {@code SkillsUser} is a shell whose setters are silent no-ops, so
     * calling into one looks like it succeeded and grants nothing — which is
     * worse than a visible refusal.
     */
    public boolean withUser(java.util.UUID player, java.util.function.Consumer<SkillsUser> work) {
        if (!isAvailable()) return false;
        try {
            SkillsUser user = AuraSkillsApi.get().getUser(player);
            if (user == null || !user.isLoaded()) {
                return false;
            }
            work.accept(user);
            return true;
        } catch (Throwable t) {
            logger.warning("[tradingcards] AuraSkills rejected a boost: " + t);
            return false;
        }
    }

    /**
     * Adds a flat AuraSkills stat modifier.
     *
     * <p>Non-persistent and explicitly named, for the two reasons above. The
     * user may not be loaded, in which case nothing is applied and the caller
     * is told so — the caller is expected to retry, not to assume success.
     */
    public boolean addStat(java.util.UUID player, String boostId, String statName, double amount) {
        Stat stat = resolveStat(statName);
        if (stat == null) {
            logger.warning("[tradingcards] Unknown AuraSkills stat '" + statName
                + "' for boost '" + boostId + "'");
            return false;
        }
        return withUser(player, user -> {
            StatModifier modifier = new StatModifier(
                name(player, boostId), stat, amount, AuraSkillsModifier.Operation.ADD);
            // Without this the boost is written to AuraSkills' storage on
            // logout and outlives the card that granted it.
            modifier.setNonPersistent();
            // Re-adding the same name replaces silently, which is what we want
            // when a card's level changes: one modifier, not two.
            user.removeStatModifier(name(player, boostId));
            user.addStatModifier(modifier);
        });
    }

    /**
     * Adds a percentage multiplier to an AuraSkills <i>trait</i>.
     *
     * <p>Traits are not stats: {@code mining} and {@code farming} describe how
     * a stat is earned, so a card that rewards mining more has to move the
     * trait, not the stat. Adding to the stat instead would give the player more
     * of a number while their mining rate stayed where it was — which reads as
     * working, and is not.
     *
     * <p>Non-persistent for the same reason as {@link #addStat}: AuraSkills
     * writes modifiers to disk on logout unless told not to, so an unremoved
     * boost would survive the card.
     *
     * @param percent the multiplier as a percentage, so 10 is +10%. This is
     *                AuraSkills' own unit, not a fraction.
     */
    public boolean addTrait(java.util.UUID player, String boostId, String traitName,
                            double percent) {
        Trait trait = resolveTrait(traitName);
        if (trait == null) {
            logger.warning("[tradingcards] Unknown AuraSkills trait '" + traitName
                + "' for boost '" + boostId + "'");
            return false;
        }
        if (percent == 0.0) {
            return false;
        }
        return withUser(player, user -> {
            TraitModifier modifier = new TraitModifier(
                name(player, boostId), trait, percent, AuraSkillsModifier.Operation.MULTIPLY);
            modifier.setNonPersistent();
            // Same name as any stat boost would be, so removal is identical and
            // the two cannot both be left behind by a partial teardown.
            user.removeTraitModifier(name(player, boostId));
            user.addTraitModifier(modifier);
        });
    }

    /**
     * Looks a trait up by name, case-insensitively.
     *
     * <p>Resolved from the loaded traits rather than a compiled-in list,
     * because AuraSkills lets servers register their own — and a card naming one
     * of those should work without an Allium rebuild.
     */
    private Trait resolveTrait(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String wanted = raw.trim();
        try {
            for (Trait trait : AuraSkillsApi.get().getGlobalRegistry().getTraits()) {
                if (trait != null && trait.name().equalsIgnoreCase(wanted)) {
                    return trait;
                }
            }
        } catch (Throwable ignored) {
            // AuraSkills not loaded, or too old to have a global registry. The
            // built-in enum below still resolves, so this is not fatal.
        }
        for (Traits trait : Traits.values()) {
            if (trait.name().equalsIgnoreCase(wanted)) {
                return trait;
            }
        }
        return null;
    }

    /** Removes a previously added stat modifier. Safe to call when absent. */
    public boolean removeStat(java.util.UUID player, String boostId) {
        return withUser(player, user -> user.removeStatModifier(name(player, boostId)));
    }

    /** Every boost id currently applied to a player, for teardown. */
    public java.util.List<String> appliedTo(java.util.UUID player) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        String prefix = namespace + ":";
        withUser(player, user -> {
            user.getStatModifiers().keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> ids.add(key.substring(prefix.length())));
        });
        return ids;
    }

    /** Removes every boost this plugin applied to a player. */
    public void removeAll(java.util.UUID player) {
        withUser(player, user ->
            user.getStatModifiers().keySet().stream()
                .filter(key -> key.startsWith(namespace + ":"))
                .toList()
                .forEach(user::removeStatModifier));
    }

    /** The player's current level for a stat, including modifiers. */
    public double statLevel(java.util.UUID player, String statName) {
        Stat stat = resolveStat(statName);
        if (stat == null) return 0.0;
        double[] out = {0.0};
        withUser(player, user -> out[0] = user.getStatLevel(stat));
        return out[0];
    }

    /**
     * Resolves a stat name against AuraSkills' enum.
     *
     * <p>The enum has exactly nine constants. There is no haste, no armour, no
     * knockback resistance and no looting in it, which is why those boosts are
     * attributes or {@code CARD_*} mechanisms rather than stats we failed to
     * find.
     */
    public Stat resolveStat(String statName) {
        if (statName == null || statName.isBlank()) return null;
        try {
            return Stats.valueOf(statName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Names a modifier so it can only ever be removed by this plugin. */
    public String name(java.util.UUID player, String boostId) {
        return namespace + ":" + player + ":" + boostId;
    }

    /** Exposed for tests that assert the naming contract. */
    public String namespace() {
        return namespace;
    }

    /** A no-op consumer, used when a bridge is unavailable. */
    public static final BiConsumer<java.util.UUID, String> NOOP = (a, b) -> { };
}
