package codes.castled.curios;

import codes.castled.allium.api.AddonEconomy;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Durable player-data-backed recovery for Curios trade-in outputs and paid bonus slot rolls.
 *
 * <p>Trade-in claims live in {@code curios:pending_trade_claims} on the player's
 * {@link PersistentDataContainer} and are flushed together with card removal via
 * {@link Player#saveData()}, so a hard stop or full inventory never loses an earned payout.
 *
 * <p>Paid bonus rolls reserve funds under a stable {@code operationId}, record the completed
 * mutation receipt in {@code allium:addon_receipts} in the same {@link Player#saveData()} as
 * the mutated card, and capture or release idempotently with durable retry state when settlement
 * fails transiently.
 */
final class CuriosRecoveryStore {

    static final NamespacedKey KEY_ADDON_RECEIPTS = new NamespacedKey("allium", "addon_receipts");
    static final int MAX_ADDON_RECEIPTS = 64;

    interface ItemCodec {
        String encode(ItemStack stack);
        ItemStack decode(String encoded);

        ItemCodec YAML = new ItemCodec() {
            @Override
            public String encode(ItemStack stack) {
                var yaml = new YamlConfiguration();
                yaml.set("item", stack);
                return yaml.saveToString();
            }

            @Override
            public ItemStack decode(String encoded) {
                if (encoded == null || encoded.isBlank()) return null;
                var yaml = new YamlConfiguration();
                try {
                    yaml.loadFromString(encoded);
                    return yaml.getItemStack("item");
                } catch (InvalidConfigurationException | RuntimeException corrupt) {
                    return null;
                }
            }
        };
    }

    record TradeClaim(UUID id, String cardEntryId, ItemStack reward, long createdAt) {}

    record CollectResult(boolean deliveredAll, ItemStack remaining, boolean found) {}

    record RecoverySummary(int claimsCompleted, int claimsRemaining) {}

    enum RollStatus {
        FREE_APPLIED,
        CAPTURED,
        CAPTURED_DEFERRED,
        RELEASED,
        RELEASE_DEFERRED,
        INSUFFICIENT_FUNDS,
        ECONOMY_UNAVAILABLE,
        MUTATION_FAILED
    }

    record RollOutcome(RollStatus status, UUID operationId) {
        boolean applied() {
            return status == RollStatus.FREE_APPLIED
                    || status == RollStatus.CAPTURED
                    || status == RollStatus.CAPTURED_DEFERRED;
        }
    }

    record PendingSettlement(UUID operationId, double amount) {}

    private final NamespacedKey tradeClaimsKey;
    private final NamespacedKey pendingRollsKey;
    private final ItemCodec codec;

    CuriosRecoveryStore(org.bukkit.plugin.Plugin plugin) {
        this(new NamespacedKey(plugin, "pending_trade_claims"),
                new NamespacedKey(plugin, "pending_roll_settlements"),
                ItemCodec.YAML);
    }

    CuriosRecoveryStore(NamespacedKey tradeClaimsKey, NamespacedKey pendingRollsKey, ItemCodec codec) {
        this.tradeClaimsKey = Objects.requireNonNull(tradeClaimsKey, "tradeClaimsKey");
        this.pendingRollsKey = Objects.requireNonNull(pendingRollsKey, "pendingRollsKey");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    // -- Trade-in recoverable claims -----------------------------------------------------------

    /**
     * Records a durable trade-in claim and removes the input card in the same player save.
     * If encoding fails, the input card is untouched and {@code null} is returned.
     */
    UUID issueTradeClaim(Player player, String cardEntryId, ItemStack reward, Runnable removeInputCard) {
        if (player == null || reward == null || reward.getType().isAir() || reward.getAmount() <= 0) {
            return null;
        }
        UUID claimId = UUID.randomUUID();
        List<TradeClaim> claims = new ArrayList<>(readClaims(player));
        claims.add(new TradeClaim(claimId, cardEntryId == null ? "" : cardEntryId, reward.clone(), System.currentTimeMillis()));
        String serialized;
        try {
            serialized = encodeClaims(claims);
        } catch (RuntimeException failed) {
            return null;
        }
        player.getPersistentDataContainer().set(tradeClaimsKey, PersistentDataType.STRING, serialized);
        removeInputCard.run();
        player.saveData();
        return claimId;
    }

    List<TradeClaim> pendingClaims(Player player) {
        return List.copyOf(readClaims(player));
    }

    /**
     * Attempts to deliver one persisted trade-in claim into the player's inventory.
     * Any overflow that does not fit remains durably persisted in player data (never dropped or lost).
     */
    CollectResult collectTradeClaim(Player player, UUID claimId) {
        List<TradeClaim> claims = new ArrayList<>(readClaims(player));
        int index = -1;
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).id().equals(claimId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return new CollectResult(true, null, false);
        }
        TradeClaim claim = claims.get(index);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(claim.reward().clone());
        if (overflow.isEmpty()) {
            claims.remove(index);
            writeClaims(player, claims);
            player.saveData();
            return new CollectResult(true, null, true);
        }
        ItemStack remaining = combineOverflow(overflow.values(), claim.reward());
        claims.set(index, new TradeClaim(claim.id(), claim.cardEntryId(), remaining, claim.createdAt()));
        writeClaims(player, claims);
        player.saveData();
        return new CollectResult(false, remaining.clone(), true);
    }

    /**
     * Attempts to deliver all pending trade-in claims for {@code player}, keeping any remainder
     * durably stored when inventory space is exhausted.
     */
    RecoverySummary claimAllPending(Player player) {
        List<TradeClaim> existing = readClaims(player);
        if (existing.isEmpty()) {
            return new RecoverySummary(0, 0);
        }
        List<TradeClaim> remaining = new ArrayList<>();
        int completed = 0;
        boolean dirty = false;
        for (TradeClaim claim : existing) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(claim.reward().clone());
            if (overflow.isEmpty()) {
                completed++;
                dirty = true;
            } else {
                ItemStack left = combineOverflow(overflow.values(), claim.reward());
                if (left.getAmount() != claim.reward().getAmount()) {
                    dirty = true;
                }
                remaining.add(new TradeClaim(claim.id(), claim.cardEntryId(), left, claim.createdAt()));
            }
        }
        if (dirty) {
            writeClaims(player, remaining);
            player.saveData();
        }
        return new RecoverySummary(completed, remaining.size());
    }

    private static ItemStack combineOverflow(Collection<ItemStack> overflow, ItemStack template) {
        int total = 0;
        ItemStack sample = null;
        for (ItemStack stack : overflow) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                if (sample == null) sample = stack.clone();
                total += stack.getAmount();
            }
        }
        if (sample == null) {
            sample = template.clone();
            total = template.getAmount();
        }
        sample.setAmount(total);
        return sample;
    }

    private List<TradeClaim> readClaims(Player player) {
        String raw = player.getPersistentDataContainer().get(tradeClaimsKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return List.of();
        var yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(raw);
        } catch (InvalidConfigurationException | RuntimeException corrupt) {
            return List.of();
        }
        var section = yaml.getConfigurationSection("claims");
        if (section == null) return List.of();
        List<TradeClaim> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            var row = section.getConfigurationSection(key);
            if (row == null) continue;
            ItemStack item = codec.decode(row.getString("item"));
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            out.add(new TradeClaim(id, row.getString("card", ""), item, row.getLong("created", 0L)));
        }
        return out;
    }

    private String encodeClaims(List<TradeClaim> claims) {
        var yaml = new YamlConfiguration();
        for (TradeClaim claim : claims) {
            String path = "claims." + claim.id();
            yaml.set(path + ".card", claim.cardEntryId());
            yaml.set(path + ".created", claim.createdAt());
            String encodedItem = codec.encode(claim.reward());
            if (encodedItem == null || encodedItem.isBlank()) {
                throw new IllegalStateException("Failed to encode trade-in reward");
            }
            yaml.set(path + ".item", encodedItem);
        }
        return yaml.saveToString();
    }

    private void writeClaims(Player player, List<TradeClaim> claims) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (claims.isEmpty()) {
            pdc.remove(tradeClaimsKey);
        } else {
            pdc.set(tradeClaimsKey, PersistentDataType.STRING, encodeClaims(claims));
        }
    }

    // -- Paid bonus roll reservation, receipt & recovery ---------------------------------------

    /**
     * Runs a paid bonus roll under a stable {@code operationId}:
     * <ol>
     *   <li>Reserves funds under {@code operationId} (when {@code cost > 0}).</li>
     *   <li>Applies the card mutation via {@code applyMutation}.</li>
     *   <li>On mutation success: appends {@code operationId} to {@code allium:addon_receipts}
     *       and calls {@link Player#saveData()}, then captures {@code operationId} (queueing
     *       durable settlement recovery if capture fails transiently).</li>
     *   <li>On mutation failure: releases {@code operationId} (queueing durable settlement
     *       recovery if release fails transiently).</li>
     * </ol>
     */
    RollOutcome executePaidRoll(Player player, AddonEconomy economy, double cost, BooleanSupplier applyMutation) {
        UUID operationId = UUID.randomUUID();
        if (cost > 0) {
            if (economy == null || !economy.available()) {
                return new RollOutcome(RollStatus.ECONOMY_UNAVAILABLE, operationId);
            }
            if (!economy.has(player.getUniqueId(), cost)
                    || !economy.reserve(operationId, player.getUniqueId(), cost)) {
                return new RollOutcome(RollStatus.INSUFFICIENT_FUNDS, operationId);
            }
        }

        boolean applied;
        try {
            applied = applyMutation.getAsBoolean();
        } catch (RuntimeException failed) {
            applied = false;
        }

        if (applied) {
            if (cost > 0) {
                recordAddonReceipt(player, operationId);
                player.saveData();
                if (economy.capture(operationId, player.getUniqueId(), cost)) {
                    return new RollOutcome(RollStatus.CAPTURED, operationId);
                }
                addPendingSettlement(player, new PendingSettlement(operationId, cost));
                player.saveData();
                return new RollOutcome(RollStatus.CAPTURED_DEFERRED, operationId);
            }
            return new RollOutcome(RollStatus.FREE_APPLIED, operationId);
        }

        if (cost > 0) {
            if (economy.release(operationId, player.getUniqueId(), cost)) {
                return new RollOutcome(RollStatus.RELEASED, operationId);
            }
            addPendingSettlement(player, new PendingSettlement(operationId, cost));
            player.saveData();
            return new RollOutcome(RollStatus.RELEASE_DEFERRED, operationId);
        }
        return new RollOutcome(RollStatus.MUTATION_FAILED, operationId);
    }

    /**
     * Reconciles any deferred paid-roll settlements for {@code player} using the durable receipt
     * proof in {@code allium:addon_receipts}: captures iff the receipt is present, releases otherwise.
     */
    int reconcilePendingRolls(Player player, AddonEconomy economy) {
        if (player == null || economy == null || !economy.available()) return 0;
        List<PendingSettlement> pending = readPendingSettlements(player);
        if (pending.isEmpty()) return 0;
        Set<UUID> receipts = readAddonReceipts(player);
        List<PendingSettlement> remaining = new ArrayList<>();
        int settled = 0;
        for (PendingSettlement item : pending) {
            boolean hasReceipt = receipts.contains(item.operationId());
            boolean done = hasReceipt
                    ? economy.capture(item.operationId(), player.getUniqueId(), item.amount())
                    : economy.release(item.operationId(), player.getUniqueId(), item.amount());
            if (done || !economy.isHeld(item.operationId())) {
                settled++;
            } else {
                remaining.add(item);
            }
        }
        if (settled > 0) {
            writePendingSettlements(player, remaining);
            player.saveData();
        }
        return settled;
    }

    List<PendingSettlement> pendingSettlements(Player player) {
        return List.copyOf(readPendingSettlements(player));
    }

    boolean hasAddonReceipt(Player player, UUID operationId) {
        return readAddonReceipts(player).contains(operationId);
    }

    private static void recordAddonReceipt(Player player, UUID operationId) {
        LinkedHashSet<UUID> receipts = readAddonReceipts(player);
        receipts.add(operationId);
        while (receipts.size() > MAX_ADDON_RECEIPTS) {
            Iterator<UUID> it = receipts.iterator();
            it.next();
            it.remove();
        }
        String joined = String.join(",", receipts.stream().map(UUID::toString).toList());
        player.getPersistentDataContainer().set(KEY_ADDON_RECEIPTS, PersistentDataType.STRING, joined);
    }

    private static LinkedHashSet<UUID> readAddonReceipts(Player player) {
        String raw = player.getPersistentDataContainer().get(KEY_ADDON_RECEIPTS, PersistentDataType.STRING);
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private void addPendingSettlement(Player player, PendingSettlement entry) {
        List<PendingSettlement> list = new ArrayList<>(readPendingSettlements(player));
        list.removeIf(existing -> existing.operationId().equals(entry.operationId()));
        list.add(entry);
        writePendingSettlements(player, list);
    }

    private List<PendingSettlement> readPendingSettlements(Player player) {
        String raw = player.getPersistentDataContainer().get(pendingRollsKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return List.of();
        List<PendingSettlement> out = new ArrayList<>();
        for (String entry : raw.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            try {
                UUID id = UUID.fromString(trimmed.substring(0, eq));
                double amount = Double.parseDouble(trimmed.substring(eq + 1));
                if (Double.isFinite(amount) && amount > 0) {
                    out.add(new PendingSettlement(id, amount));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private void writePendingSettlements(Player player, List<PendingSettlement> list) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (list.isEmpty()) {
            pdc.remove(pendingRollsKey);
        } else {
            String encoded = String.join(";", list.stream()
                    .map(s -> s.operationId() + "=" + s.amount())
                    .toList());
            pdc.set(pendingRollsKey, PersistentDataType.STRING, encoded);
        }
    }
}
