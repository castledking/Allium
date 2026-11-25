package codes.castled.curios;

import codes.castled.allium.api.CurioBoosts;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Runtime executor for equipped relic passive attributes and trading-card signature &amp; bonus boosts.
 *
 * <p>Implements {@link CurioBoosts} so sibling addons ({@code Crops}, {@code PartyLocator}) and
 * external bridges can query live player boost totals in O(1) without coupling to Curios internals.
 */
final class CardBoostRuntime implements Listener, CurioBoosts {

    static final double SCALE_MIN = 0.6;
    static final double SCALE_MAX = 2.0;

    private static final String MOD_PREFIX = "curios_";
    private static final UUID ATTR_ARMOR = modId("curios:card_armor");
    private static final UUID ATTR_HASTE = modId("curios:card_haste");
    private static final UUID ATTR_KNOCKBACK = modId("curios:card_knockback");
    private static final UUID ATTR_SCALE = modId("curios:card_scale");
    private static final UUID ATTR_STRENGTH = modId("curios:card_strength");
    private static final UUID ATTR_HEALTH = modId("curios:card_health");
    private static final UUID ATTR_SPEED = modId("curios:card_speed");
    private static final UUID ATTR_LUCK = modId("curios:card_luck");
    private static final UUID ATTR_TOUGHNESS = modId("curios:card_toughness");

    private record AppliedAttr(Attribute attribute, UUID id, String name) {}

    /** Aggregated active boosts from all equipped relics and cards for one player. */
    record PlayerBoostSnapshot(
            List<Catalog.AttributeBonus> relicAttributes,
            Map<String, Double> signatureStats,
            double armor,
            double haste,
            double knockback,
            double scale,
            double flightSpeed,
            double disarmRate,
            double xpMultiplier,
            double sellMultiplier,
            double cropQualityBonus,
            double lootBoost,
            double locatorRangeBonus,
            double locatorRangeMultiplier,
            double questMoneyBonus,
            double questTokensBonus,
            double tokenDropChance,
            double tokenDropMultiplier,
            double bonusRegeneration
    ) {
        static final PlayerBoostSnapshot EMPTY = new PlayerBoostSnapshot(
                List.of(), Map.of(),
                0.0, 0.0, 0.0, 1.0, 1.0,
                0.0, 1.0, 1.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0);

        double totalRegeneration() {
            return signatureStats.getOrDefault("regeneration", 0.0) + bonusRegeneration;
        }
    }

    private final CuriosPlugin plugin;
    private final AuraSkillsBridge auraSkills;
    private final DoubleSupplier random;
    private final Map<UUID, PlayerBoostSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, List<AppliedAttr>> appliedAttributes = new ConcurrentHashMap<>();
    private final Map<UUID, Float> savedFlySpeeds = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> pendingAuraRetries = new ConcurrentHashMap<>();

    CardBoostRuntime(CuriosPlugin plugin, AuraSkillsBridge auraSkills) {
        this(plugin, auraSkills, () -> ThreadLocalRandom.current().nextDouble());
    }

    CardBoostRuntime(CuriosPlugin plugin, AuraSkillsBridge auraSkills, DoubleSupplier random) {
        this.plugin = plugin;
        this.auraSkills = auraSkills;
        this.random = random;
    }

    /** Hooks optional third-party plugin events (such as BeastTokens drops) via reflection. */
    void registerOptionalBridges() {
        registerBeastTokensBridge();
    }

    private void registerBeastTokensBridge() {
        Plugin beastTokens = plugin.getServer().getPluginManager().getPlugin("BeastTokens");
        if (beastTokens == null || !beastTokens.isEnabled()) return;
        try {
            Class<? extends Event> dropEventClass = Class.forName(
                    "me.mraxetv.beasttokens.api.events.tokendrops.BTRewardDropEvent",
                    true, beastTokens.getClass().getClassLoader()).asSubclass(Event.class);
            Method getPlayer = dropEventClass.getMethod("getPlayer");
            Method getTokens = dropEventClass.getMethod("getTokens");
            Method setTokens = dropEventClass.getMethod("setTokens", double.class);
            Method getCurrencyId = dropEventClass.getMethod("getCurrencyId");
            plugin.getServer().getPluginManager().registerEvent(
                    dropEventClass, this, EventPriority.NORMAL,
                    (listener, event) -> handleBeastTokenDrop(dropEventClass, getPlayer, getTokens, setTokens, getCurrencyId, event),
                    plugin, true);
        } catch (Throwable unavailable) {
            plugin.getLogger().fine("BeastTokens drop bridge unavailable: " + unavailable.getMessage());
        }
    }

    void handleBeastTokenDrop(Class<?> eventClass, Method getPlayer, Method getTokens, Method setTokens,
                              Method getCurrencyId, Event event) {
        if (!eventClass.isInstance(event) || (event instanceof Cancellable c && c.isCancelled())) return;
        try {
            Object currency = getCurrencyId.invoke(event);
            if (!(currency instanceof String s) || !"Tokens".equalsIgnoreCase(s)) return;
            Object rawPlayer = getPlayer.invoke(event);
            if (!(rawPlayer instanceof Player player)) return;
            double tokens = ((Number) getTokens.invoke(event)).doubleValue();
            double adjusted = applyTokenDropBoost(player.getUniqueId(), tokens, random.getAsDouble());
            if (Double.isFinite(adjusted) && adjusted > 0.0 && adjusted != tokens) {
                setTokens.invoke(event, adjusted);
            }
        } catch (Throwable ignored) {
        }
    }

    double applyTokenDropBoost(UUID playerId, double baseTokens, double roll) {
        if (!Double.isFinite(baseTokens) || baseTokens <= 0.0) return baseTokens;
        double amount = baseTokens * tokenDropMultiplier(playerId);
        double chance = tokenDropChance(playerId);
        if (chance > 0.0 && roll < chance) {
            amount *= 2.0;
        }
        return amount;
    }

    /** Recomputes and applies all equipped relic attributes and trading-card boosts for {@code player}. */
    void refresh(Player player) {
        if (player == null) return;
        clear(player);
        if (!player.isOnline() || !player.hasPermission("curios.use")) return;

        List<EquippedItem> equipped = new ArrayList<>();
        for (Catalog.Slot slot : plugin.catalog().slots) {
            ItemStack item = plugin.equipped(player, slot);
            Catalog.Entry entry = plugin.entry(item);
            if (entry == null) continue;
            ItemIdentity id = plugin.identity(item).orElse(null);
            equipped.add(new EquippedItem(entry, id));
        }

        PlayerBoostSnapshot snap = aggregate(equipped, plugin.cardRules());
        UUID playerId = player.getUniqueId();
        snapshots.put(playerId, snap);

        List<AppliedAttr> applied = new ArrayList<>();

        // 1. Relic passive attributes
        for (int i = 0; i < snap.relicAttributes().size(); i++) {
            Catalog.AttributeBonus bonus = snap.relicAttributes().get(i);
            Attribute attr = resolveAttribute(bonus.attribute());
            if (attr == null || bonus.amount() == 0.0) continue;
            String name = MOD_PREFIX + "relic_" + i + "_" + bonus.attribute().toLowerCase(Locale.ROOT);
            UUID id = modId("curios:relic:" + i + ":" + bonus.attribute().toLowerCase(Locale.ROOT));
            if (applyModifier(player, attr, id, name, bonus.amount(), bonus.operation())) {
                applied.add(new AppliedAttr(attr, id, name));
            }
        }

        // 2. Card vanilla attributes (armor, haste, knockback, scale)
        if (snap.armor() > 0.0 && applyModifier(player, resolveAttribute("armor"), ATTR_ARMOR,
                "curios_armor", snap.armor(), AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("armor"), ATTR_ARMOR, "curios_armor"));
        }
        if (snap.haste() > 0.0 && applyModifier(player, resolveAttribute("attack_speed"), ATTR_HASTE,
                "curios_haste", snap.haste(), AttributeModifier.Operation.ADD_SCALAR)) {
            applied.add(new AppliedAttr(resolveAttribute("attack_speed"), ATTR_HASTE, "curios_haste"));
        }
        if (snap.knockback() > 0.0 && applyModifier(player, resolveAttribute("knockback_resistance"), ATTR_KNOCKBACK,
                "curios_knockback", Math.min(1.0, snap.knockback()), AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("knockback_resistance"), ATTR_KNOCKBACK, "curios_knockback"));
        }
        if (Math.abs(snap.scale() - 1.0) > 1e-4) {
            Attribute scaleAttr = resolveAttribute("scale");
            double clamped = Math.max(SCALE_MIN, Math.min(SCALE_MAX, snap.scale()));
            if (scaleAttr != null && applyModifier(player, scaleAttr, ATTR_SCALE, "curios_scale",
                    clamped - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1)) {
                applied.add(new AppliedAttr(scaleAttr, ATTR_SCALE, "curios_scale"));
            }
        }

        // 3. Flight speed boost (adjusts player flySpeed while remembering base speed)
        if (snap.flightSpeed() > 1.0001) {
            float base = savedFlySpeeds.computeIfAbsent(playerId, ignored -> player.getFlySpeed());
            float boosted = (float) Math.max(0.05, Math.min(1.0, base * snap.flightSpeed()));
            player.setFlySpeed(boosted);
        }

        // 4. Card signature stats -> AuraSkills when available, else clean vanilla fallback
        Map<String, Double> auraStats = buildAuraStats(snap);
        if (auraSkills != null && auraSkills.isAvailable()) {
            if (!auraStats.isEmpty() && !auraSkills.applyStats(playerId, auraStats)) {
                scheduleAuraRetry(player, auraStats, 10);
            }
        } else {
            applyVanillaSignatureFallback(player, snap, applied);
        }

        if (!applied.isEmpty()) {
            appliedAttributes.put(playerId, applied);
        }
    }

    private void scheduleAuraRetry(Player player, Map<String, Double> auraStats, int remainingAttempts) {
        if (remainingAttempts <= 0 || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        cancelAuraRetry(playerId);
        Runnable attempt = () -> {
            pendingAuraRetries.remove(playerId);
            if (!player.isOnline() || !snapshots.containsKey(playerId)) return;
            if (!auraSkills.applyStats(playerId, auraStats) && remainingAttempts > 1) {
                scheduleAuraRetry(player, auraStats, remainingAttempts - 1);
            }
        };
        if (plugin.core() == null) return;
        TaskHandle handle = plugin.core().runForEntity(player, attempt, () -> pendingAuraRetries.remove(playerId), 500L);
        if (handle != null && handle != TaskHandle.NONE) {
            pendingAuraRetries.put(playerId, handle);
        }
    }

    private void cancelAuraRetry(UUID playerId) {
        TaskHandle prev = pendingAuraRetries.remove(playerId);
        if (prev != null) prev.cancel();
    }

    private static Map<String, Double> buildAuraStats(PlayerBoostSnapshot snap) {
        Map<String, Double> stats = new LinkedHashMap<>(snap.signatureStats());
        if (snap.lootBoost() > 0.0) {
            stats.merge("luck", snap.lootBoost(), Double::sum);
        }
        if (snap.bonusRegeneration() > 0.0) {
            stats.merge("regeneration", snap.bonusRegeneration(), Double::sum);
        }
        return stats;
    }

    private static void applyVanillaSignatureFallback(Player player, PlayerBoostSnapshot snap, List<AppliedAttr> applied) {
        double strength = snap.signatureStats().getOrDefault("strength", 0.0);
        if (strength > 0.0 && applyModifier(player, resolveAttribute("attack_damage"), ATTR_STRENGTH,
                "curios_strength", strength * 0.2, AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("attack_damage"), ATTR_STRENGTH, "curios_strength"));
        }
        double health = snap.signatureStats().getOrDefault("health", 0.0);
        if (health > 0.0 && applyModifier(player, resolveAttribute("max_health"), ATTR_HEALTH,
                "curios_health", health * 0.5, AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("max_health"), ATTR_HEALTH, "curios_health"));
        }
        double speed = snap.signatureStats().getOrDefault("speed", 0.0);
        if (speed > 0.0 && applyModifier(player, resolveAttribute("movement_speed"), ATTR_SPEED,
                "curios_speed", Math.min(0.5, speed * 0.005), AttributeModifier.Operation.ADD_SCALAR)) {
            applied.add(new AppliedAttr(resolveAttribute("movement_speed"), ATTR_SPEED, "curios_speed"));
        }
        double luck = snap.signatureStats().getOrDefault("luck", 0.0) + snap.lootBoost();
        if (luck > 0.0 && applyModifier(player, resolveAttribute("luck"), ATTR_LUCK,
                "curios_luck", luck * 0.25, AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("luck"), ATTR_LUCK, "curios_luck"));
        }
        double toughness = snap.signatureStats().getOrDefault("toughness", 0.0);
        if (toughness > 0.0 && applyModifier(player, resolveAttribute("armor_toughness"), ATTR_TOUGHNESS,
                "curios_toughness", toughness * 0.2, AttributeModifier.Operation.ADD_NUMBER)) {
            applied.add(new AppliedAttr(resolveAttribute("armor_toughness"), ATTR_TOUGHNESS, "curios_toughness"));
        }
    }

    /** Removes every relic attribute, card boost, fly-speed tweak, and AuraSkills modifier for {@code player}. */
    void clear(Player player) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        cancelAuraRetry(id);
        snapshots.remove(id);

        List<AppliedAttr> tracked = appliedAttributes.remove(id);
        if (tracked != null) {
            for (AppliedAttr attr : tracked) {
                removeModifier(player, attr.attribute(), attr.id(), attr.name());
            }
        }
        // Clean up any orphaned modifiers (e.g. from a previous reload or crash)
        removeModifier(player, resolveAttribute("armor"), ATTR_ARMOR, "curios_armor");
        removeModifier(player, resolveAttribute("attack_speed"), ATTR_HASTE, "curios_haste");
        removeModifier(player, resolveAttribute("knockback_resistance"), ATTR_KNOCKBACK, "curios_knockback");
        removeModifier(player, resolveAttribute("scale"), ATTR_SCALE, "curios_scale");
        removeModifier(player, resolveAttribute("attack_damage"), ATTR_STRENGTH, "curios_strength");
        removeModifier(player, resolveAttribute("max_health"), ATTR_HEALTH, "curios_health");
        removeModifier(player, resolveAttribute("movement_speed"), ATTR_SPEED, "curios_speed");
        removeModifier(player, resolveAttribute("luck"), ATTR_LUCK, "curios_luck");
        removeModifier(player, resolveAttribute("armor_toughness"), ATTR_TOUGHNESS, "curios_toughness");
        removePrefixedModifiers(player);

        Float baseFly = savedFlySpeeds.remove(id);
        if (baseFly != null) {
            player.setFlySpeed(baseFly);
        }
        if (auraSkills != null) {
            auraSkills.removeAll(id);
        }
    }

    void close() {
        pendingAuraRetries.values().forEach(TaskHandle::cancel);
        pendingAuraRetries.clear();
        snapshots.clear();
        appliedAttributes.clear();
        savedFlySpeeds.clear();
    }

    // ==================== runtime listeners ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombatHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim) || !victim.isValid()) return;

        PlayerBoostSnapshot snap = snapshots.get(attacker.getUniqueId());
        if (snap == null) return;

        // Vanilla crit fallback when AuraSkills is absent
        if (auraSkills == null || !auraSkills.isAvailable()) {
            double critChance = snap.signatureStats().getOrDefault("crit-chance", 0.0);
            if (critChance > 0.0) {
                double prob = Math.min(0.75, critChance / 100.0);
                if (random.getAsDouble() < prob) {
                    double critDmg = snap.signatureStats().getOrDefault("crit-damage", 0.0);
                    event.setDamage(event.getDamage() * (1.25 + Math.max(0.0, critDmg) * 0.02));
                }
            }
        }

        // Disarm rate: strips a non-player mob's main-hand weapon without dropping it
        if (!(victim instanceof Player) && snap.disarmRate() > 0.0) {
            double probability = Math.min(1.0, snap.disarmRate());
            if (random.getAsDouble() < probability) {
                EntityEquipment equipment = victim.getEquipment();
                if (equipment != null) {
                    ItemStack weapon = equipment.getItemInMainHand();
                    if (weapon != null && !weapon.getType().isAir()) {
                        equipment.setItemInMainHand(null);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        PlayerBoostSnapshot snap = snapshots.get(killer.getUniqueId());
        if (snap == null) return;

        // XP drop boost
        int droppedExp = event.getDroppedExp();
        double xpMult = xpMultiplier(killer.getUniqueId());
        if (droppedExp > 0 && xpMult > 1.0001) {
            event.setDroppedExp((int) Math.max(droppedExp, Math.round(droppedExp * xpMult)));
        }

        // Loot boost bonus stackable mob drop
        if (!(event.getEntity() instanceof Player) && snap.lootBoost() > 0.0 && !event.getDrops().isEmpty()) {
            double extraChance = Math.min(0.5, snap.lootBoost() * 0.05);
            if (random.getAsDouble() < extraChance) {
                for (ItemStack drop : event.getDrops()) {
                    if (drop == null || drop.getType().isAir() || drop.getMaxStackSize() <= 1) continue;
                    if (plugin != null && plugin.entry(drop) != null) continue;
                    ItemStack bonus = drop.clone();
                    bonus.setAmount(1);
                    event.getDrops().add(bonus);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (auraSkills != null && auraSkills.isAvailable()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        var reason = event.getRegainReason();
        if (reason != EntityRegainHealthEvent.RegainReason.SATIATED
                && reason != EntityRegainHealthEvent.RegainReason.REGEN
                && reason != EntityRegainHealthEvent.RegainReason.MAGIC_REGEN) {
            return;
        }
        PlayerBoostSnapshot snap = snapshots.get(player.getUniqueId());
        if (snap == null) return;
        double regen = snap.totalRegeneration();
        if (regen > 0.0 && event.getAmount() > 0.0) {
            event.setAmount(event.getAmount() * (1.0 + Math.min(2.0, regen * 0.05)));
        }
    }

    // ==================== CurioBoosts service ====================

    private PlayerBoostSnapshot snapshot(UUID playerId) {
        return playerId == null ? PlayerBoostSnapshot.EMPTY : snapshots.getOrDefault(playerId, PlayerBoostSnapshot.EMPTY);
    }

    @Override
    public double cropQualityBonus(UUID player) {
        return Math.max(0.0, snapshot(player).cropQualityBonus());
    }

    @Override
    public double locatorRangeBonus(UUID player) {
        return Math.max(0.0, snapshot(player).locatorRangeBonus());
    }

    @Override
    public double locatorRangeMultiplier(UUID player) {
        return Math.max(1.0, snapshot(player).locatorRangeMultiplier());
    }

    @Override
    public double xpMultiplier(UUID player) {
        PlayerBoostSnapshot snap = snapshot(player);
        double mult = Math.max(1.0, snap.xpMultiplier());
        if (auraSkills == null || !auraSkills.isAvailable()) {
            double wisdom = snap.signatureStats().getOrDefault("wisdom", 0.0);
            if (wisdom > 0.0) mult *= 1.0 + (wisdom * 0.01);
        }
        return mult;
    }

    @Override
    public double sellMultiplier(UUID player) {
        return Math.max(1.0, snapshot(player).sellMultiplier());
    }

    @Override
    public double questMoneyBonus(UUID player) {
        return Math.max(0.0, snapshot(player).questMoneyBonus());
    }

    @Override
    public double questTokensBonus(UUID player) {
        return Math.max(0.0, snapshot(player).questTokensBonus());
    }

    @Override
    public double tokenDropChance(UUID player) {
        return Math.max(0.0, Math.min(1.0, snapshot(player).tokenDropChance()));
    }

    @Override
    public double tokenDropMultiplier(UUID player) {
        return Math.max(1.0, snapshot(player).tokenDropMultiplier());
    }

    // ==================== pure aggregation (unit-testable) ====================

    record EquippedItem(Catalog.Entry entry, ItemIdentity identity) {}

    static PlayerBoostSnapshot aggregate(List<EquippedItem> equipped, CardRules cardRules) {
        List<Catalog.AttributeBonus> relicAttrs = new ArrayList<>();
        Map<String, Double> signatures = new LinkedHashMap<>();

        double armor = 0.0;
        double haste = 0.0;
        double knockback = 0.0;
        double scale = 1.0;
        double flightSpeed = 1.0;
        double disarmRate = 0.0;
        double xpMultiplier = 1.0;
        double sellMultiplier = 1.0;
        double cropQuality = 0.0;
        double lootBoost = 0.0;
        double locatorFlat = 0.0;
        double locatorMult = 1.0;
        double questMoney = 0.0;
        double questTokens = 0.0;
        double tokenChance = 0.0;
        double tokenMult = 1.0;
        double bonusRegen = 0.0;

        for (EquippedItem item : equipped) {
            Catalog.Entry entry = item.entry();
            if (entry == null) continue;
            if (!entry.attributes().isEmpty()) {
                relicAttrs.addAll(entry.attributes());
            }
            if (entry.legacyCard().isEmpty() || item.identity() == null) continue;

            Map<String, String> state = item.identity().state();
            CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
            int level = CardRules.parseIntSafe(state.get("level"), 0);
            int quality = CardRules.parseIntSafe(state.get("quality"), 100);
            List<String> sigIds = CardRules.parseSignatures(state.get("signatures"));
            for (CardRules.StartingValue sv : cardRules.startingValues(entry.legacyCard(), tier, quality, level, sigIds)) {
                signatures.merge(sv.boostId(), sv.total(), Double::sum);
            }

            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            List<CardRules.BonusSlotData> bonuses = CardRules.parseBonuses(state.get("bonuses"));
            for (int i = 0; i < unlocked && i < bonuses.size(); i++) {
                CardRules.BonusSlotData slot = bonuses.get(i);
                if (slot.isEmpty()) continue;
                CardRules.BonusDef def = CardRules.BONUSES.get(slot.boostId());
                if (def == null) continue;
                double val = def.scaled(slot.strength());
                switch (slot.boostId()) {
                    case "armor" -> armor += val;
                    case "haste" -> haste += val / 100.0;
                    case "knockback" -> knockback += val / 100.0;
                    case "scale" -> scale *= val;
                    case "flight-speed" -> flightSpeed *= Math.max(1.0, val);
                    case "disarm-rate" -> disarmRate += val / 100.0;
                    case "xp-drop-boost" -> xpMultiplier *= 1.0 + (val / 100.0);
                    case "selling-boost" -> sellMultiplier *= 1.0 + (val / 100.0);
                    case "crop-boost" -> cropQuality += val / 100.0;
                    case "loot-boost" -> lootBoost += val;
                    case "waypoint-range" -> locatorFlat += val;
                    case "waypoint-multiplier" -> locatorMult *= Math.max(1.0, val);
                    case "money-quest" -> questMoney += val;
                    case "tokens-quest" -> questTokens += val;
                    case "token-drop-chance" -> tokenChance += val / 100.0;
                    case "token-drop-multiplier" -> tokenMult *= Math.max(1.0, val);
                    case "regeneration" -> bonusRegen += val;
                    default -> { }
                }
            }
        }

        return new PlayerBoostSnapshot(
                List.copyOf(relicAttrs),
                Collections.unmodifiableMap(signatures),
                armor, haste, Math.min(1.0, knockback),
                Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale)),
                flightSpeed,
                Math.min(1.0, disarmRate),
                xpMultiplier, sellMultiplier, cropQuality, lootBoost,
                locatorFlat, locatorMult, questMoney, questTokens,
                Math.min(1.0, tokenChance), tokenMult, bonusRegen);
    }

    // ==================== attribute helpers ====================

    /** Resolves a Bukkit Attribute across 1.20 ({@code GENERIC_*}) and 1.21+ names safely. */
    static Attribute resolveAttribute(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) clean = clean.substring("minecraft:".length());
        String noGeneric = clean.startsWith("generic.") ? clean.substring("generic.".length())
                : (clean.startsWith("generic_") ? clean.substring("generic_".length()) : clean);
        String withGenericDot = "generic." + noGeneric;
        String withGenericUnderscore = "generic_" + noGeneric;
        for (Attribute attribute : Attribute.values()) {
            String name = attribute.name().toLowerCase(Locale.ROOT);
            if (name.equals(clean) || name.equals(noGeneric) || name.equals(withGenericUnderscore)) {
                return attribute;
            }
            try {
                String key = attribute.getKey().getKey().toLowerCase(Locale.ROOT);
                if (key.equals(clean) || key.equals(noGeneric) || key.equals(withGenericDot)
                        || key.equals(withGenericUnderscore)) {
                    return attribute;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean applyModifier(Player player, Attribute attr, UUID id, String name, double amount,
                                         AttributeModifier.Operation op) {
        if (attr == null || !Double.isFinite(amount) || amount == 0.0) return false;
        AttributeInstance instance = player.getAttribute(attr);
        if (instance == null) return false;
        removeModifier(player, attr, id, name);
        instance.addModifier(new AttributeModifier(id, name, amount, op));
        return true;
    }

    @SuppressWarnings("deprecation")
    private static void removeModifier(Player player, Attribute attr, UUID id, String name) {
        if (attr == null) return;
        AttributeInstance instance = player.getAttribute(attr);
        if (instance == null) return;
        for (AttributeModifier mod : new ArrayList<>(instance.getModifiers())) {
            if ((id != null && id.equals(mod.getUniqueId())) || (name != null && name.equals(mod.getName()))) {
                instance.removeModifier(mod);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void removePrefixedModifiers(Player player) {
        for (Attribute attr : Attribute.values()) {
            AttributeInstance instance;
            try {
                instance = player.getAttribute(attr);
            } catch (Throwable ignored) {
                continue;
            }
            if (instance == null) continue;
            for (AttributeModifier mod : new ArrayList<>(instance.getModifiers())) {
                if (mod.getName() != null && mod.getName().startsWith(MOD_PREFIX)) {
                    instance.removeModifier(mod);
                }
            }
        }
    }

    private static UUID modId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
