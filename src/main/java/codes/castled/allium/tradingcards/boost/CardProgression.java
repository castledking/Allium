package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Grants xp to a card, levels it up, and says so in chat.
 *
 * <p>Levelling adds {@code boost-per-level} to **one** of the card's signature
 * boosts, chosen at random. Not all of them: a card that grew evenly would be
 * interchangeable with any other card at the same level, and there would be no
 * reason to keep levelling this one rather than another. The randomness is the
 * point — after 30 levels the ratio between a card's boosts is a record of where
 * those levels happened to land.
 *
 * <p>Bonus boosts are never touched. They are a drop-time gamble, and letting
 * levels re-roll them would mean levelling could grant boosts the drop never
 * awarded.
 */
public class CardProgression implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BoostService boosts;
    private final EquippedCardTracker tracker;
    private final ProgressRules rules;
    private final java.util.logging.Logger logger;

    /** The xp curve and what a level costs. */
    public record ProgressRules(int maximumLevel, double boostPerLevel, int boostsPerLevel) {}

    public CardProgression(BoostService boosts, EquippedCardTracker tracker,
                           ProgressRules rules, java.util.logging.Logger logger) {
        this.boosts = boosts;
        this.tracker = tracker;
        this.rules = rules;
        this.logger = logger;
    }

    // ==================== xp ====================

    /**
     * Awards xp to a card, levelling it as many times as that xp covers.
     *
     * <p>Multiple levels in one award are applied in order, each announcing
     * itself, because a single large payout (a breeding cooldown expiring, a
     * quest) should read as the same progression a player would have seen one
     * level at a time.
     *
     * @return how many levels were gained
     */
    public int award(ItemStack card, UUID owner, double xp) {
        if (card == null || xp <= 0) return 0;
        var read = TradingCardData.read(card);
        if (read.isEmpty()) return 0;
        TradingCardData data = read.get();

        int levels = 0;
        double remaining = xp;
        int level = data.level();
        while (remaining > 0 && level < rules.maximumLevel()) {
            double needed = xpForNextLevel(level);
            if (needed <= 0) break;
            if (remaining < needed) {
                break;
            }
            remaining -= needed;
            level++;
            levels++;
        }
        if (levels == 0) {
            return 0;
        }
        // The total is written, not a per-level delta, so a card's effective
        // power never depends on replaying its xp history.
        TradingCardData.write(card, data.withLevel(level));
        return levels;
    }

    /**
     * How many levels {@code xp} covers from {@code level}, writing nothing.
     *
     * <p>Split out from {@link #award} so a caller can ask the question without
     * the side effects — a menu showing "2 levels away", or a source levelling
     * a card it is not holding.
     *
     * <p>Multiple levels in one award are counted in order, each against the
     * curve at its own level, so a single large payout reads as the same
     * progression a player would have seen one level at a time.
     */
    public int levelsGained(int level, double xp, int maximumLevel) {
        if (xp <= 0) return 0;
        int gained = 0;
        int current = level;
        double remaining = xp;
        while (remaining > 0 && current < maximumLevel) {
            double needed = xpForNextLevel(current);
            if (needed <= 0 || remaining < needed) {
                break;
            }
            remaining -= needed;
            current++;
            gained++;
        }
        return gained;
    }

    /**
     * What an award does to a card's level and banked xp.
     *
     * @param levels levels gained
     * @param xp     what is left over toward the next one
     * @param level  the level the card ends up at
     */
    public record Progression(int levels, double xp, int level) {
        public boolean levelled() {
            return levels > 0;
        }
    }

    /**
     * Applies an award against a card that already has banked xp.
     *
     * <p>Levels are bought in order, each against the curve at its own level, so
     * a single large payout reads as the same progression a player would have
     * seen one level at a time. Whatever cannot buy a whole level stays banked
     * rather than being discarded — without that, a card at level 0 earning 10
     * xp of a 500 xp level would lose it every time.
     *
     * @param banked xp already carried toward the next level
     */
    public Progression award(int level, double banked, double xp, int maximumLevel) {
        int gained = 0;
        int current = level;
        double remaining = Math.max(0.0, banked) + Math.max(0.0, xp);
        while (current < maximumLevel) {
            double needed = xpForNextLevel(current);
            if (needed <= 0) {
                // A zero-cost level would loop forever, and would give xp away for
                // free. Treated as a stop rather than a climb.
                break;
            }
            if (remaining < needed) {
                break;
            }
            remaining -= needed;
            current++;
            gained++;
        }
        // At the ceiling the remainder is dropped rather than banked: there is no
        // next level to be partway towards, and holding it would make a maxed
        // card look like it is still progressing.
        double carried = current >= maximumLevel ? 0.0 : remaining;
        return new Progression(gained, carried, current);
    }

    /**
     * How far through the level after {@code level} a given xp gets a card.
     *
     * @return 0..1, where 1 means the next level is reached
     */
    public double progressTowards(int level, double xp, double nextLevelCost) {
        if (nextLevelCost <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, xp / nextLevelCost));
    }

    /**
     * Supplies the cost of a level, so the curve lives in the xp config rather
     * than here. Implemented by a method reference onto {@code XpConfig}.
     */
    public interface XpCurve {
        double xpFor(int level);
    }

    private volatile XpCurve curve = level -> 25;

    public void curve(XpCurve curve) {
        this.curve = curve == null ? level -> 25 : curve;
    }

    public double xpForNextLevel(int level) {
        return Math.max(0.0, curve.xpFor(level));
    }

    // ==================== signature growth ====================

    /**
     * Chooses which of a card's signatures grow by one level.
     *
     * <p>Without replacement, so a level that grows two boosts cannot put both
     * increments on the same one. Returns the chosen ids, which the caller uses
     * to recompute the card's signature amounts.
     */
    public List<String> chooseSignaturesToGrow(List<String> signatures) {
        if (signatures == null || signatures.isEmpty()) return List.of();
        List<String> pool = new ArrayList<>(signatures);
        List<String> chosen = new ArrayList<>();
        int count = Math.min(Math.max(1, rules.boostsPerLevel()), pool.size());
        for (int i = 0; i < count; i++) {
            chosen.add(pool.remove(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        return List.copyOf(chosen);
    }

    // ==================== announcement ====================

    /**
     * Tells the owner their card levelled, with the increment.
     *
     * <p>Chat rather than a title or action bar: levelling is a persistent fact
     * about an item, not a moment that should interrupt whatever they are doing,
     * and an action bar is gone before they look up. The {@code 40 → 41} pair is
     * the part that earns its place — a lone "now level 41" is noise once a card
     * is at 60, and watching the number tick is most of the appeal.
     *
     * <p>The card's display name is substituted as an unparsed placeholder, not
     * concatenated into the template: it is player-authored via /rename, so a
     * card named {@code <red>} would otherwise recolour the rest of the line.
     */
    public void announce(Player owner, String cardName, int previous, int current,
                         String template, boolean broadcast) {
        if (owner == null) return;
        String line = template
            .replace("<card>", cardName)
            .replace("<previous>", Integer.toString(previous))
            .replace("<level>", Integer.toString(current));
        if (broadcast) {
            owner.getServer().sendMessage(MM.deserialize(line));
        } else {
            owner.sendMessage(MM.deserialize(line));
        }
        try {
            owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        } catch (Throwable ignored) {
            // A sound is not worth failing a level-up over.
        }
    }

    /**
     * Records xp banked on the equipped card without touching its boosts.
     *
     * <p>Most awards do not finish a level, and those change nothing a boost is
     * derived from, so re-applying every boost on every kill would be work for
     * nothing. The tracker still has to hold the new total, or the next award
     * starts again from the old one.
     */
    public void bank(Player player, TradingCardData updated) {
        if (player == null || updated == null) return;
        if (tracker.isEmpty(player.getUniqueId())) return;
        tracker.set(player.getUniqueId(), updated, tracker.granted(player.getUniqueId()));
    }

    /** Re-applies an equipped card's boosts, after its level changed. */
    public void refreshEquipped(Player player, TradingCardData updated) {
        if (player == null || updated == null) return;
        if (tracker.isEmpty(player.getUniqueId())) return;
        List<String> granted = boosts.apply(player, updated);
        tracker.set(player.getUniqueId(), updated, granted);
    }

    // ==================== cleanup ====================

    /**
     * Drops every trace of a player's boosts.
     *
     * <p>Called on quit and on module disable, before the database flush — the
     * same quiesce-then-persist ordering the harvest module uses. Without it an
     * AuraSkills modifier can outlive the session, and because modifiers are
     * persisted on logout, it can outlive the card.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boosts.detachAll(id);
        tracker.clear(id);
    }

    public int trackedCards() {
        return tracker.trackedPlayers();
    }

    /** The level rules this service enforces, so a caller can read the ceiling. */
    public ProgressRules rules() {
        return rules;
    }
}
