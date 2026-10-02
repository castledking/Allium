package codes.castled.allium.tradingcards.boost;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.item.TradingCardData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Applies and removes a card's boosts.
 *
 * <p>Three rules govern everything here, and each exists because getting it
 * wrong is silent:
 *
 * <ol>
 *   <li><b>Removal is by name, never by list.</b> AuraSkills and the attribute
 *       modifiers are keyed, and a boost whose removal is skipped outlives the
 *       card. Every apply records exactly what it added, and the teardown reads
 *       that record rather than recomputing it.
 *   <li><b>Attribute modifiers are engine-owned.</b> A Bukkit attribute
 *       modifier disappears with the entity, so unequip only has to null out our
 *       references; AuraSkills state has to be removed explicitly.
 *   <li><b>Apply is idempotent.</b> Re-equipping a card, reloading the config,
 *       or re-logging must not stack two copies of the same boost. Every apply
 *       removes the previous one first.
 * </ol>
 */
public class BoostService {

    private final Logger logger;
    private final AuraSkillsBridge aura;
    private final Map<String, BoostCatalog.LoadResult> catalogHolder = new LinkedHashMap<>();

    /**
     * The same derivation the card's lore reads.
     *
     * <p>Held rather than passed per call because applying a boost and drawing a
     * card are both frequent, and the two must never disagree about what a card
     * is worth. Set by the module alongside the catalogue; null falls back to the
     * catalogue amount, so a card is never left without a value.
     */
    private volatile StartingBoosts startingBoosts;
    private volatile List<codes.castled.allium.tradingcards.card.QualityBand> bands =
        List.of();

    /** What a player currently has applied, by boost id. */
    private final Map<UUID, Map<String, AppliedBoost>> applied = new LinkedHashMap<>();

    /**
     * Permission nodes granted while a card is equipped, one attachment per
     * player. Held here rather than granted through the permissions plugin so
     * removal is exact and cannot outlive the card.
     */
    private final Map<UUID, PermissionAttachment> attachments = new LinkedHashMap<>();

    private volatile BoostCatalog.LoadResult catalog = new BoostCatalog.LoadResult(
        Map.of(), Map.of(), Map.of(), Map.of(), 3, 0.35, 6, List.of());
    private volatile double boostPerLevel = 1.0;
    private volatile double scaleMin = 0.6;
    private volatile double scaleMax = 1.9;

    public BoostService(Logger logger, AuraSkillsBridge aura) {
        this.logger = logger;
        this.aura = aura;
    }

    /** One applied boost, remembered so it can be undone exactly. */
    public record AppliedBoost(
        String boostId,
        BoostMechanism mechanism,
        Attribute attribute,
        UUID modifierId,
        String auraModifierName,
        String permission,
        double value
    ) {
        public AppliedBoost(String boostId, BoostMechanism mechanism, Attribute attribute,
                            UUID modifierId, String auraModifierName, String permission) {
            this(boostId, mechanism, attribute, modifierId, auraModifierName, permission, 0.0);
        }
    }

    /** Installs the starting-boost derivation, so applied values match the lore. */
    public void startingBoosts(StartingBoosts boosts,
                               List<codes.castled.allium.tradingcards.card.QualityBand> bands) {
        this.startingBoosts = boosts;
        this.bands = bands == null ? List.of() : List.copyOf(bands);
    }

    public void configure(BoostCatalog.LoadResult loaded, double boostPerLevel,
                          double scaleMin, double scaleMax) {
        this.catalog = loaded;
        this.boostPerLevel = boostPerLevel;
        this.scaleMin = scaleMin;
        this.scaleMax = scaleMax;
    }

    public BoostCatalog.LoadResult catalog() {
        return catalog;
    }

    // ==================== apply ====================

    /**
     * Applies every boost a card grants, replacing anything previously applied.
     *
     * <p>Remove-then-apply rather than add: a card whose level changed should
     * grant the new value, not the new value on top of the old one.
     *
     * @return the boost ids actually applied
     */
    public List<String> apply(Player player, TradingCardData card) {
        if (player == null) return List.of();
        remove(player);

        Map<String, BoostDefinition> boosts = catalog.boosts();
        if (boosts.isEmpty()) {
            return List.of();
        }
        List<String> granted = new ArrayList<>();

        // Signatures first: they are the card's identity and the numbers a
        // player compares between cards.
        List<StartingBoosts.Value> derived = startingBoosts == null
            ? List.of()
            : startingBoosts.valuesFor(card, bands);
        for (int i = 0; i < card.signatures().size(); i++) {
            String signatureId = card.signatures().get(i);
            BoostDefinition boost = boosts.get(signatureId);
            if (boost == null) {
                logger.fine("[tradingcards] Card " + card.cardId() + " names unknown "
                    + "signature '" + signatureId + "'; skipped");
                continue;
            }
            // The same derivation the lore reads. A signature is a scale boost
            // rather than a flat one, so it is clamped; everything else takes the
            // derived value outright.
            double value;
            if (i < derived.size()) {
                value = derived.get(i).total(startingBoosts.rules());
                if (boost.mechanism() == BoostMechanism.CARD_SCALE) {
                    value = Math.max(scaleMin, Math.min(scaleMax, value));
                }
            } else {
                value = boost.totalAt(card.level(), boostPerLevel, scaleMin, scaleMax);
            }
            if (applyOne(player, boost, value)) {
                granted.add(boost.id());
            }
        }

        // Bonuses are flat: the same value at every level, because levelling
        // grows the card's identity rather than its loot. They are read by
        // consumers rather than applied here, so a bonus boost whose source
        // plugin is absent costs the player nothing they would otherwise have.
        for (String bonusId : card.bonuses()) {
            BoostDefinition boost = boosts.get(bonusId);
            if (boost == null) {
                logger.fine("[tradingcards] Card " + card.cardId() + " names unknown "
                    + "bonus '" + bonusId + "'; skipped");
                continue;
            }
            double value = boost.totalAt(0, boostPerLevel, scaleMin, scaleMax);
            if (applyOne(player, boost, value)) {
                granted.add(boost.id());
            }
        }

        if (!granted.isEmpty()) {
            applied.put(player.getUniqueId(), currentApplied(player));
        }
        return List.copyOf(granted);
    }

    private Map<String, AppliedBoost> currentApplied(Player player) {
        Map<String, AppliedBoost> map = new LinkedHashMap<>();
        applied.getOrDefault(player.getUniqueId(), Map.of())
            .forEach((k, v) -> map.put(k, v));
        return map;
    }

    private boolean applyOne(Player player, BoostDefinition boost, double value) {
        return switch (boost.mechanism()) {
            case AURASKILL_STAT -> {
                String stat = boost.option("stat", "");
                boolean ok = aura.addStat(player.getUniqueId(), boost.id(), stat, value);
                if (ok) {
                    record(player, new AppliedBoost(boost.id(), boost.mechanism(), null, null,
                        aura.name(player.getUniqueId(), boost.id()), null));
                }
                yield ok;
            }
            case AURASKILL_TRAIT -> {
                // Traits are AuraSkills' percent multipliers on how a stat is
                // earned, so the amount is read as a percentage rather than a
                // flat addition — "+1 mining" is meaningless to that plugin.
                String trait = boost.option("trait", "");
                boolean ok = aura.addTrait(player.getUniqueId(), boost.id(), trait, value);
                if (ok) {
                    record(player, new AppliedBoost(boost.id(), boost.mechanism(), null, null,
                        aura.name(player.getUniqueId(), boost.id()), null, value));
                }
                yield ok;
            }
            case CARD_XP_MULTIPLIER, CARD_SELL_MULTIPLIER,
                 CARD_MONEY_ON_QUEST, CARD_TOKENS_ON_QUEST, CARD_TOKEN_DROP_CHANCE,
                 CARD_TOKEN_MULTIPLIER, CARD_WAYPOINT_RANGE, CARD_WAYPOINT_MULTIPLIER,
                 CARD_DISARM, CARD_CROP_QUALITY -> {
                // No state held here: these are multipliers and flat rates that
                // a consumer reads while the card is equipped. Recorded so the
                // value is queryable per player and so teardown stays uniform.
                record(player, new AppliedBoost(boost.id(), boost.mechanism(), null, null, null,
                    null, value));
                yield true;
            }
            case ATTRIBUTE -> {
                Attribute attribute = resolve(boost.option("attribute", ""));
                if (attribute == null || value <= 0) {
                    yield false;
                }
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    yield false;
                }
                // A unique modifier id per player+boost: two players, or two
                // cards, must never collide on the same attribute instance.
                UUID modifierId = modifierId(player, boost.id());
                instance.addModifier(new AttributeModifier(modifierId, "tradingcard", value,
                    attributeOperation(boost)));
                record(player, new AppliedBoost(boost.id(), boost.mechanism(), attribute,
                    modifierId, null, null));
                yield true;
            }
            case CARD_SCALE -> {
                // Vanilla has a SCALE attribute, so this is an ordinary
                // modifier. Clamped rather than rejected: a card that rolled a
                // factor outside the legal range grants the strongest version
                // of it that fits inside a door instead of granting nothing.
                if (value <= 0.0) {
                    yield false;
                }
                double clamped = Math.max(scaleMin, Math.min(scaleMax, value));
                AttributeInstance instance = player.getAttribute(Attribute.SCALE);
                if (instance == null) {
                    yield false;
                }
                UUID modifierId = modifierId(player, boost.id());
                instance.addModifier(new AttributeModifier(modifierId, "tradingcard", clamped,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
                record(player, new AppliedBoost(boost.id(), boost.mechanism(), Attribute.SCALE,
                    modifierId, null, null, clamped));
                yield true;
            }
            case CARD_FLIGHT_SPEED -> {
                // Only meaningful while flying, but FLYING_SPEED is an ordinary
                // attribute and holds its value, so no listener is needed.
                AttributeInstance instance = player.getAttribute(Attribute.FLYING_SPEED);
                if (instance == null || value <= 0.0) {
                    yield false;
                }
                UUID modifierId = modifierId(player, boost.id());
                instance.addModifier(new AttributeModifier(modifierId, "tradingcard", value,
                    AttributeModifier.Operation.ADD_SCALAR));
                record(player, new AppliedBoost(boost.id(), boost.mechanism(),
                    Attribute.FLYING_SPEED, modifierId, null, null, value));
                yield true;
            }
            case CARD_PERMISSION -> {
                String node = boost.option("permission", "");
                if (node.isBlank()) {
                    yield false;
                }
                org.bukkit.plugin.Plugin plugin = pluginOf(player);
                if (plugin == null) {
                    yield false;
                }
                PermissionAttachment attachment = attachments.computeIfAbsent(
                    player.getUniqueId(), id -> player.addAttachment(plugin));
                attachment.setPermission(node, true);
                record(player, new AppliedBoost(boost.id(), boost.mechanism(), null, null, null, node));
                yield true;
            }
        };
    }

    /**
     * The modifier id for a boost on a player.
     *
     * <p>Derived rather than random so a re-apply replaces its own modifier
     * rather than accumulating a second one on the same attribute instance.
     */
    private static UUID modifierId(Player player, String boostId) {
        return UUID.nameUUIDFromBytes(
            (TradingCardsBranding.NAMESPACE + player.getUniqueId() + boostId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void record(Player player, AppliedBoost entry) {
        applied.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>())
            .put(entry.boostId(), entry);
    }

    // ==================== removal ====================

    /**
     * Removes every boost this plugin applied to a player.
     *
     * <p>Safe to call when nothing is applied, and safe to call twice: the
     * second call finds an empty record and does nothing. That matters because
     * unequip, quit and module disable can all fire for the same player.
     */
    public void remove(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        Map<String, AppliedBoost> boosts = applied.remove(id);
        if (boosts == null || boosts.isEmpty()) {
            return;
        }
        for (AppliedBoost boost : boosts.values()) {
            removeOne(player, boost);
        }
    }

    private void removeOne(Player player, AppliedBoost boost) {
        switch (boost.mechanism()) {
            case ATTRIBUTE -> {
                if (boost.attribute() == null || boost.modifierId() == null) return;
                AttributeInstance instance = player.getAttribute(boost.attribute());
                if (instance == null) return;
                instance.removeModifier(boost.modifierId());
            }
            case AURASKILL_STAT -> {
                if (boost.auraModifierName() != null) {
                    aura.removeStat(player.getUniqueId(), boost.boostId());
                }
            }
            case CARD_PERMISSION -> {
                if (boost.permission() == null || boost.permission().isBlank()) return;
                // One attachment per player holds every node this plugin grants.
                // Removing the attachment revokes them all at once, which is
                // correct: a card is the only thing that ever granted them.
                PermissionAttachment attachment = attachments.get(player.getUniqueId());
                if (attachment != null) {
                    attachment.unsetPermission(boost.permission());
                    if (attachment.getPermissions().isEmpty()) {
                        player.removeAttachment(attachment);
                        attachments.remove(player.getUniqueId());
                    }
                }
            }
            default -> {
                // Nothing held outside the card for the mechanisms that are
                // wired later; their records exist so removal is symmetric.
            }
        }
    }

    private static org.bukkit.plugin.Plugin pluginOf(Player player) {
        return player.getServer().getPluginManager().getPlugin("Allium");
    }

    /**
     * Drops every permission attachment, for a player who has left.
     *
     * <p>Called on quit and on disable. Without it an attachment outlives the
     * session and the node stays granted for a player with no card equipped.
     */
    public void detachAll(UUID player) {
        PermissionAttachment attachment = attachments.remove(player);
        if (attachment != null) {
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(player);
            if (online != null) {
                online.removeAttachment(attachment);
            }
        }
        applied.remove(player);
    }

    /** How many players currently have a permission attachment. */
    public int trackedAttachments() {
        return attachments.size();
    }

    // ==================== inspection ====================

    /**
     * The active values of every boost using a mechanism, summed.
     *
     * <p>The read side of the CARD_* mechanisms: they hold no state, so a
     * consumer asks here rather than being pushed to. Summed because two
     * multiplications are one multiplication, and summed flat rates are one
     * flat rate — which is what a player expects from two cards.
     *
     * @return the total, or 0 when the player has none of these equipped
     */
    public double total(UUID player, BoostMechanism mechanism) {
        return applied.getOrDefault(player, Map.of()).values().stream()
            .filter(entry -> entry.mechanism() == mechanism)
            .mapToDouble(AppliedBoost::value)
            .sum();
    }

    /**
     * The product of every boost using a mechanism, starting from 1.
     *
     * <p>Multipliers compose multiplicatively, so a card granting x1.25 and a
     * card granting x1.5 give x1.875 — not x2.75, which is what summing them
     * would produce.
     */
    public double product(UUID player, BoostMechanism mechanism) {
        double product = 1.0;
        for (AppliedBoost entry : applied.getOrDefault(player, Map.of()).values()) {
            if (entry.mechanism() == mechanism) {
                product *= entry.value();
            }
        }
        return product;
    }

    /**
     * A player's multiplier for a mechanism, never below {@code floor}.
     *
     * <p>The read most consumers want. {@code floor} exists because a card can
     * hold a multiplier below 1, and a sell-value multiplier of 0 would zero a
     * sale rather than discount it.
     */
    public double multiplier(UUID player, BoostMechanism mechanism, double floor) {
        return Math.max(floor, product(player, mechanism));
    }

    /** The boost ids currently applied to a player, for diagnostics. */
    public List<String> appliedTo(UUID player) {
        return List.copyOf(applied.getOrDefault(player, Map.of()).keySet());
    }

    public boolean hasApplied(UUID player) {
        return !applied.getOrDefault(player, Map.of()).isEmpty();
    }

    public int trackedPlayers() {
        return applied.size();
    }

    /** Every AuraSkills stat name this plugin knows, for config validation. */
    public List<String> knownAuraStats() {
        List<String> names = new ArrayList<>();
        for (var stat : dev.aurelium.auraskills.api.stat.Stats.values()) {
            names.add(stat.name());
        }
        return names;
    }

    /**
     * Maps the configured operation onto Bukkit's four.
     *
     * <p>Bukkit has no MULTIPLY_SCALAR_2 — that is the NMS-side operation, and
     * the API simply does not expose it. An unknown or absent value falls back
     * to ADD_NUMBER, which is what almost every attribute boost wants.
     */
    private static AttributeModifier.Operation attributeOperation(BoostDefinition boost) {
        return switch (BoostDefinition.normalise(boost.option("operation", "add_number"))) {
            case "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
            case "multiply_scalar_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    private static Attribute resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (Attribute attribute : Attribute.values()) {
            String key = attribute.getKey().toString();
            if (key.equalsIgnoreCase(raw)
                || key.substring(key.indexOf(':') + 1).equalsIgnoreCase(raw)
                || attribute.name().equalsIgnoreCase(raw)) {
                return attribute;
            }
        }
        return null;
    }

}
