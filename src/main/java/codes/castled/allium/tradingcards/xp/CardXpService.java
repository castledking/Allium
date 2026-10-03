package codes.castled.allium.tradingcards.xp;

import codes.castled.allium.tradingcards.boost.CardProgression;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;

/**
 * Decides which card earns xp from an event, and awards it.
 *
 * <p>One card per player, and it is the <b>equipped</b> one. Not a card in the
 * inventory: xp follows the card the player committed to, which is what makes
 * the Relique slot mean something beyond its boosts. A player carrying nine
 * cards cannot bank xp in all of them.
 *
 * <p>Every source funnels through here so the anti-farm gates cannot be
 * forgotten by a new one. A source that awards xp without asking
 * {@link XpAntiFarmStore} first is a repeatable xp fountain, and that is the
 * easiest mistake to make when adding the fifth source.
 */
public class CardXpService {

    private final XpConfig config;
    private final XpAntiFarmStore antiFarm;
    private final CardProgression progression;
    private final EquippedCardLookup equipped;
    private final LongSupplier clock;

    /** Supplies the card a player has equipped, or null. */
    @FunctionalInterface
    public interface EquippedCardLookup {
        Optional<TradingCardData> equipped(UUID player);
    }

    /** A card that can be written back to, so the level survives the award. */
    @FunctionalInterface
    public interface CardWriter {
        void write(UUID player, TradingCardData updated);
    }

    /**
     * Supplies the card multiplier from whatever the equipped card grants.
     *
     * <p>A separate dependency rather than a hard reference to the boost
     * service, so the xp path has no opinion about how boosts are stored — and so
     * a server without Relique installed still pays xp at 1x instead of failing
     * to award it at all.
     */
    @FunctionalInterface
    public interface CardMultiplier {
        double multiplierFor(UUID player);
    }

    private CardWriter writer = (player, card) -> { };
    private CardMultiplier multiplier = player -> 1.0;

    public CardXpService(XpConfig config, XpAntiFarmStore antiFarm,
                          CardProgression progression, EquippedCardLookup equipped,
                          LongSupplier clock) {
        this.config = config;
        this.antiFarm = antiFarm;
        this.progression = progression;
        this.equipped = equipped;
        this.clock = clock;
    }

    public void writer(CardWriter writer) {
        this.writer = writer == null ? (player, card) -> { } : writer;
    }

    /** Supplies the equipped card's xp multiplier, floored so it cannot zero an award. */
    public void multiplier(CardMultiplier multiplier) {
        this.multiplier = multiplier == null ? player -> 1.0 : multiplier;
    }

    /**
     * Awards xp from a named source, applying every gate for it.
     *
     * <p>Checks, in order: the source is enabled, a card is equipped, the
     * plain cooldown has elapsed, the per-mob cooldown has elapsed, and the
     * claim is recorded. Nothing is written until the award succeeds, so a card
     * is never levelled by a source that then refuses to pay.
     *
     * @param amount    the source's own value — a kill's worth of xp, a
     *                  completed quest's units. Multiplied by the source's
     *                  multiplier when it has one.
     * @param mobKey    the mob type involved, for per-mob cooldowns; may be null
     * @param claimKey  a one-shot key for sources that may only pay once per
     *                  key, such as an advancement; may be null
     * @return what happened, for the caller's message
     */
    public Award award(Player player, String sourceId, double amount,
                       String mobKey, String claimKey) {
        XpConfig.Source source = config.source(sourceId);
        if (!source.enabled()) {
            return Award.skipped(sourceId, "disabled");
        }
        var card = equipped.equipped(player.getUniqueId());
        if (card.isEmpty()) {
            return Award.skipped(sourceId, "no card equipped");
        }
        long now = clock.getAsLong();

        if (source.cooldownMillis() > 0
            && !antiFarm.claimCooldown(player.getUniqueId(), sourceId, source.cooldownMillis(), now)) {
            return Award.cool(sourceId, antiFarm.cooldownRemaining(
                player.getUniqueId(), sourceId, source.cooldownMillis(), now));
        }
        if (mobKey != null && source.perMobCooldown()
            && !antiFarm.claimMobCooldown(player.getUniqueId(),
                mobKey.toLowerCase(Locale.ROOT), source.perMobCooldownMillis(), now)) {
            return Award.cool(sourceId, antiFarm.mobCooldownRemaining(
                player.getUniqueId(), mobKey.toLowerCase(Locale.ROOT),
                source.perMobCooldownMillis(), now));
        }
        if (claimKey != null && !antiFarm.claimAdvancement(player.getUniqueId(), claimKey)) {
            return Award.skipped(sourceId, "already paid for this one");
        }

        // The card's own bonus applies on top of the source's multiplier, and
        // is floored at 1x so a card can never make levelling slower than doing
        // nothing at all.
        double cardMultiplier = Math.max(1.0, multiplier.multiplierFor(player.getUniqueId()));
        double xp = (source.usesMultiplier() ? amount * source.multiplier() : source.xp())
            * cardMultiplier;
        if (xp <= 0.0) {
            return Award.skipped(sourceId, "awards nothing");
        }
        return apply(player, card.get(), xp, sourceId);
    }

    /**
     * Levels a card and writes it back.
     *
     * <p>Separated from {@link #award} so a source that needs to level a card
     * it is not holding — a card in a chest, say — can use the same level-up
     * and announcement without also going through the equipped-card check.
     */
    public Award apply(Player player, TradingCardData card, double xp, String sourceId) {
        int maxLevel = progression.rules().maximumLevel();
        // The card carries what it has already banked, so the remainder survives
        // to the next award instead of being thrown away with this one.
        CardProgression.Progression result = progression.award(
            card.level(), card.xp(), xp, maxLevel);
        if (!result.levelled()) {
            return Award.progress(sourceId, xp, 0, progression.progressTowards(
                card.level(), result.xp(), config.xpForLevel(card.level())));
        }
        TradingCardData updated = card.withLevel(result.level()).withXp(result.xp());
        writer.write(player.getUniqueId(), updated);
        progression.refreshEquipped(player, updated);
        progression.announce(player, cardLabel(card), card.level(), updated.level(),
            announceTemplate, announceBroadcast);
        return Award.progress(sourceId, xp, result.levels(), progression.progressTowards(
            updated.level(), result.xp(), config.xpForLevel(updated.level())));
    }

    /** The config's level-up message, which the module supplies. */
    private volatile String announceTemplate =
        "<gradient:#d4a04a:#8a5a2a>[Cards]</gradient> <yellow>Your <card> is now level <green><level></green>! <dark_green><previous></dark_green> <white>→</white> <green><level></green>";
    private volatile boolean announceBroadcast = false;

    public void announcement(String template, boolean broadcast) {
        if (template != null && !template.isBlank()) {
            this.announceTemplate = template;
        }
        this.announceBroadcast = broadcast;
    }

    private static String cardLabel(TradingCardData card) {
        String mob = card.mob();
        if (mob == null || mob.isBlank()) return "Trading Card";
        String lower = mob.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1) + " Trading Card";
    }

    /** The curve, so the progression service reads costs from the same place. */
    public CardProgression.XpCurve curve() {
        return config::xpForLevel;
    }

    public XpConfig config() {
        return config;
    }

    public XpAntiFarmStore antiFarm() {
        return antiFarm;
    }

    /** What an award did. */
    public record Award(
        String sourceId,
        boolean paid,
        boolean gated,
        int levels,
        double xp,
        double progress,
        long cooldownRemaining,
        String reason
    ) {
        static Award skipped(String sourceId, String reason) {
            return new Award(sourceId, false, true, 0, 0, 0, 0, reason);
        }

        static Award cool(String sourceId, long remaining) {
            return new Award(sourceId, false, true, 0, 0, 0, remaining, "on cooldown");
        }

        static Award progress(String sourceId, double xp, int levels, double progress) {
            return new Award(sourceId, true, false, levels, xp, progress, 0, null);
        }
    }
}
