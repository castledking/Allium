package codes.castled.allium.tradingcards.morph;

import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Decides who may morph, and remembers who has.
 *
 * <p>The disguise itself is cosmetic — see {@link DisguiseBridge} — so this is
 * where the actual behaviour of a morph lives: who is wearing one, who has drawn
 * aggro, and what happens when they are hit.
 *
 * <p>Two records, both needed for the targeting rule:
 *
 * <ul>
 *   <li><b>who is morphed</b>, so a target can be recognised as an imposter
 *       rather than a genuine member of the species;
 *   <li><b>who has struck</b>, so a mob that witnessed the attack comes, and one
 *       that spawned afterwards does not. That asymmetry is what makes the morph
 *       feel vanilla: it falls out of per-mob memory rather than a global flag.
 * </ul>
 */
public class MorphService {

    private final DisguiseBridge disguises;
    private final Config config;

    /** Players currently morphed, to what. */
    private final ConcurrentHashMap<UUID, EntityType> morphed = new ConcurrentHashMap<>();

    /** Mobs that have seen this player attack and may now target them. */
    private final Set<UUID> provoked = ConcurrentHashMap.newKeySet();

    /**
     * Players we granted flight to.
     *
     * <p>Tracked rather than inferred from the config, because flight may also
     * come from a rank or another plugin. Only a grant we made is ours to take
     * back.
     */
    private final Set<UUID> flown = ConcurrentHashMap.newKeySet();

    /**
     * When a timed morph runs out, in epoch millis.
     *
     * <p>Absent for a toggle, which is the shipped default: {@code duration: 0}
     * means the morph lasts until the player says otherwise, not forever, and
     * not on a timer nobody asked for.
     */
    private final ConcurrentHashMap<UUID, Long> expiresAt = new ConcurrentHashMap<>();

    /**
     * @param minimumTier  the lowest tier that may morph
     * @param durationSeconds 0 for a toggle that never expires
     * @param deactivateBelowHealth 0 to disable; 10 for half of vanilla 20
     * @param allowFlight  whether fliers may take flight at all
     * @param stealthUntilAttacked cancel targeting until the player strikes
     * @param mobsTargetDisguised pass through to LibsDisguises; false makes the
     *                            morphed player invisible to mob AI entirely
     */
    public record Config(
        boolean enabled,
        codes.castled.allium.tradingcards.card.Tier minimumTier,
        int durationSeconds,
        double deactivateBelowHealth,
        boolean allowFlight,
        boolean stealthUntilAttacked,
        boolean mobsTargetDisguised
    ) {
        public static Config defaults() {
            return new Config(true,
                codes.castled.allium.tradingcards.card.Tier.FABLED, 0, 0.0,
                false, true, true);
        }
    }

    public MorphService(DisguiseBridge disguises, Config config) {
        this.disguises = disguises;
        this.config = config;
    }

    /** Why a morph was refused. */
    public enum Denial {
        DISABLED("Morphing is disabled."),
        NO_BRIDGE("The morph plugin is not available."),
        NO_CARD("Equip a trading card first."),
        TOO_LOW_TIER("That card is not rare enough to morph."),
        NOT_MORPHABLE("That mob cannot be imitated."),
        FAILED("The morph did not take.");

        private final String defaultMessage;

        Denial(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String message() {
            return defaultMessage;
        }
    }

    public record Result(boolean morphed, Denial denial, String message) {
        static Result ok(EntityType type) {
            return new Result(true, null, "You are now a " + pretty(type) + ".");
        }

        static Result no(Denial denial, String message) {
            return new Result(false, denial, message);
        }
    }

    /**
     * Toggles a morph for a player, given the card they have equipped.
     *
     * <p>Takes the equipped card rather than a stack, because a morphed player
     * cannot unequip the card that granted the morph without losing it — and
     * the card is still in the Relique slot either way, so the check stays true
     * for the whole time they are wearing it.
     */
    public Result toggle(Player player, TradingCardData card) {
        if (!config.enabled()) {
            return Result.no(Denial.DISABLED, Denial.DISABLED.message());
        }
        if (!disguises.isReady()) {
            return Result.no(Denial.NO_BRIDGE, Denial.NO_BRIDGE.message());
        }
        if (isMorphed(player)) {
            clear(player);
            return new Result(false, null, "You are yourself again.");
        }
        if (card == null) {
            return Result.no(Denial.NO_CARD, Denial.NO_CARD.message());
        }
        if (!card.tier().atLeast(config.minimumTier())) {
            return Result.no(Denial.TOO_LOW_TIER,
                "You need a " + config.minimumTier() + " card to morph.");
        }
        EntityType target = MorphRules.resolve(card.mob());
        if (target == null) {
            return Result.no(Denial.NOT_MORPHABLE,
                "A " + pretty(EntityType.valueOf(card.mob())) + " card cannot morph.");
        }
        if (disguises.toggle(player, target, config.mobsTargetDisguised)
            != DisguiseBridge.MorphState.APPLIED) {
            return Result.no(Denial.FAILED, Denial.FAILED.message());
        }
        morphed.put(player.getUniqueId(), target);
        // A fresh morph starts unprovoked. Carrying over who was attacked
        // before would mean re-morphing to escape consequences, which is the
        // exact opposite of the rule.
        provoked.remove(player.getUniqueId());
        if (config.allowFlight() && MorphRules.canFly(target)) {
            player.setAllowFlight(true);
            flown.add(player.getUniqueId());
        }
        if (isTimed()) {
            expiresAt.put(player.getUniqueId(),
                System.currentTimeMillis() + config.durationSeconds() * 1000L);
        }
        return Result.ok(target);
    }

    /**
     * Records that a morphed player struck a mob, opening it to aggro.
     *
     * <p>Only the mobs that witnessed it become hostile to this player. A mob
     * that spawns afterwards has no memory of an event it never saw, which is
     * the whole trick.
     */
    public void recordAttack(Player attacker, org.bukkit.entity.LivingEntity victim) {
        if (!isMorphed(attacker) || !config.stealthUntilAttacked()) {
            return;
        }
        EntityType form = morphed.get(attacker.getUniqueId());
        if (form == null || !MorphRules.isHostile(victim.getType())) {
            return;
        }
        provoked.add(attacker.getUniqueId());
    }

    /** True when this morphed player has drawn aggro by attacking. */
    public boolean isProvoked(UUID player) {
        return provoked.contains(player);
    }

    /**
     * Whether a mob may target a morphed player.
     *
     * <p>The rule in full: a morphed player shaped like a hostile is not a target
     * until they have struck something. Named vindicators and evokers are exempt
     * entirely, because Johnny is a vanilla pacifist and a morph should not drag
     * its wearer into a fight with him.
     */
    public boolean mayTarget(Player target, org.bukkit.entity.LivingEntity mob) {
        if (mob == null) {
            return true;
        }
        return MorphRules.morphMayTarget(
            morphed.get(target.getUniqueId()),
            provoked.contains(target.getUniqueId()),
            config.stealthUntilAttacked(),
            mob.getType(), mob.getCustomName());
    }

    public boolean isMorphed(Player player) {
        return morphed.containsKey(player.getUniqueId());
    }

    /** The entity type a player is morphed as, by our own record. */
    public EntityType formOf(Player player) {
        return morphed.get(player.getUniqueId());
    }

    /**
     * Drops a morph that has run out of time or fallen below the health floor.
     *
     * <p>Needs a repeating check rather than an event hook: there is no Bukkit
     * event for "health crossed a threshold", and a timed effect that is cancelled
     * by a disconnect has to survive the reconnect. One shared task over morphed
     * players only, not one per player.
     *
     * @return true when a morph was ended
     */
    public boolean tick(Player player) {
        if (!isMorphed(player)) {
            return false;
        }
        if (isTimed()) {
            Long expiry = expiresAt.get(player.getUniqueId());
            if (expiry != null && System.currentTimeMillis() >= expiry) {
                clear(player);
                return true;
            }
        }
        if (config.deactivateBelowHealth() > 0.0
            && player.getHealth() <= config.deactivateBelowHealth()) {
            clear(player);
            return true;
        }
        return false;
    }

    /** Ends a morph and restores the player. */
    public void clear(Player player) {
        if (morphed.remove(player.getUniqueId()) == null && !disguises.isDisguised(player)) {
            return;
        }
        if (disguises.isReady()) {
            disguises.undisguise(player);
        }
        provoked.remove(player.getUniqueId());
        expiresAt.remove(player.getUniqueId());
        // Only a grant we made is ours to revoke. Clearing it unconditionally
        // would take flight away from a player whose rank gives it to them.
        if (flown.remove(player.getUniqueId())) {
            player.setAllowFlight(false);
        }
        disguisedForget(player);
    }

    private void disguisedForget(Player player) {
        if (disguises.isReady()) {
            disguises.forget(player.getUniqueId());
        }
    }

    /** Clears a player on quit, so nothing outlives the session. */
    public void handleQuit(Player player) {
        morphed.remove(player.getUniqueId());
        provoked.remove(player.getUniqueId());
        flown.remove(player.getUniqueId());
        expiresAt.remove(player.getUniqueId());
        disguisedForget(player);
    }

    /** Clears every morph, for module disable. */
    public void clearAll() {
        for (UUID id : Set.copyOf(morphed.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                clear(player);
            } else {
                morphed.remove(id);
                provoked.remove(id);
            }
        }
        morphed.clear();
        provoked.clear();
        flown.clear();
        expiresAt.clear();
    }

    public int morphedCount() {
        return morphed.size();
    }

    public int provokedCount() {
        return provoked.size();
    }

    /** True when this morph is on a timer rather than a toggle. */
    public boolean isTimed() {
        return config.durationSeconds() > 0;
    }

    /**
     * Seconds until a timed morph expires; 0 for a toggle, which never does.
     *
     * <p>Clamped at zero so a morph that has already lapsed but not yet been
     * swept reports remaining 0 rather than a negative count.
     */
    public int secondsRemaining(Player player) {
        if (!isMorphed(player) || !isTimed()) {
            return 0;
        }
        Long expiry = expiresAt.get(player.getUniqueId());
        if (expiry == null) {
            return 0;
        }
        long millis = expiry - System.currentTimeMillis();
        return millis <= 0 ? 0 : (int) Math.ceil(millis / 1000.0);
    }

    public Config config() {
        return config;
    }

    public DisguiseBridge disguises() {
        return disguises;
    }

    private static String pretty(EntityType type) {
        String lower = type.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
