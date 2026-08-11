package codes.castled.allium.items.impl;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob Disarmer. Right-click a mob to strip its held items and armour; each stripped piece then
 * rolls the mob's own vanilla drop chance to decide whether it hits the ground.
 * <p>
 * Charges live in the item's PDC rather than in a per-player map so the cooldown belongs to the
 * tool: three uses, then the whole set refills {@value #RECHARGE_MS} ms after the last one is spent.
 * Partial refills are deliberately not a thing — the item is either charged or recharging.
 * Holders of {@link #BYPASS_PERMISSION} skip charge accounting altogether.
 * <p>
 * Each charge count is its own Nexo item ({@code mob_disarmer}, {@code mob_disarmer_2-3},
 * {@code mob_disarmer_1-3}, {@code mob_disarmer_0-3}), so spending a charge rewrites the stack's
 * item model, custom model data and <em>both</em> id tags to the matching variant — the item
 * visibly transforms in hand, and transforms back when the recharge lands. Because the tags match
 * what Nexo writes, a stack handed out by {@code /nexo give <variant>} is accepted here too.
 */
public class MobDisarmerItem extends CustomItem {

    public static final String ITEM_ID = "mob_disarmer";
    public static final int MAX_CHARGES = 3;
    public static final long RECHARGE_MS = 120_000L; // 2 minutes
    private static final long STATUS_MESSAGE_COOLDOWN_MS = 1_000L;
    /** First and second uses can break too, but are deliberately much safer than the last use. */
    private static final double EARLY_USE_BREAK_CHANCE = 0.05D;
    /** The final use is the risky one, preventing players from safely resetting every two uses. */
    private static final double LAST_USE_BREAK_CHANCE = 0.25D;
    public static final String BYPASS_PERMISSION = "allium.admin";

    /**
     * Indexed by remaining charges, so both arrays must stay {@link #MAX_CHARGES} + 1 long and must
     * match the ids and {@code Pack.custom_model_data} values in Nexo's item config.
     */
    private static final String[] VARIANT_IDS = {
        ITEM_ID + "_0-3",
        ITEM_ID + "_1-3",
        ITEM_ID + "_2-3",
        ITEM_ID
    };
    private static final int[] VARIANT_MODEL_DATA = { 1007, 1006, 1005, 1004 };

    /** Order matters: hands first so the "disarm" reads as a disarm even on fully kitted mobs. */
    private static final EquipmentSlot[] DISARM_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    };

    /** A stripped piece paired with the drop chance read off the mob before the slot was cleared. */
    private record StrippedPiece(ItemStack stack, float dropChance) {}

    private final NamespacedKey chargesKey;
    private final NamespacedKey rechargeKey;
    private final NamespacedKey brokenKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey nexoKey;

    /** Player -> epoch millis a refresh task is already booked for, so joins do not stack tasks. */
    private final Map<UUID, Long> pendingRefresh = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStatusMessage = new ConcurrentHashMap<>();

    public MobDisarmerItem(final Plugin plugin) {
        super(plugin, ITEM_ID);
        this.chargesKey = new NamespacedKey(plugin, "mob_disarmer_charges");
        this.rechargeKey = new NamespacedKey(plugin, "mob_disarmer_recharge_at");
        this.brokenKey = new NamespacedKey(plugin, "mob_disarmer_broken");
        this.usesKey = new NamespacedKey(plugin, "mob_disarmer_uses");
        this.nexoKey = new NamespacedKey("nexo", "id");
    }

    @Override
    public Material getMaterial() {
        return Material.DIAMOND_SWORD;
    }

    @Override
    public String getDisplayName() {
        return ChatColor.RED + "" + ChatColor.BOLD + "Mob Disarmer";
    }

    @Override
    public List<String> getLore() {
        return buildLore(MAX_CHARGES, 0L, 0L);
    }

    @Override
    public String getTextureType() {
        return "item_model";
    }

    @Override
    public Object getItemModel() {
        return "nexo:" + ITEM_ID;
    }

    @Override
    public int getCustomModelData() {
        return VARIANT_MODEL_DATA[MAX_CHARGES];
    }

    /**
     * Built from scratch rather than via {@code super}: the base implementation writes the plain id
     * and a fixed model, and this item needs the full variant treatment on every state change
     * anyway, so {@link #writeState} is the one code path that produces a valid stack.
     */
    @Override
    public ItemStack createItemStack(final int amount) {
        final ItemStack item = new ItemStack(getMaterial(), Math.max(1, amount));
        writeState(item, MAX_CHARGES, 0L);
        return item;
    }

    /**
     * Accepts every charge variant, by either id tag, so a disarmer mid-cooldown is still recognised
     * as this item and so is one handed out by {@code /nexo give}.
     */
    @Override
    public boolean isThisItem(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String stored = pdc.get(itemKey, PersistentDataType.STRING);
        final String nexoId = pdc.get(nexoKey, PersistentDataType.STRING);
        for (final String variant : VARIANT_IDS) {
            if (variant.equals(stored) || variant.equals(nexoId)) {
                return true;
            }
        }
        if (item.getType() == getMaterial() && meta.hasCustomModelData()) {
            final int modelData = meta.getCustomModelData();
            for (final int variantModelData : VARIANT_MODEL_DATA) {
                if (modelData == variantModelData) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strips {@code target} and books a charge against {@code tool}.
     *
     * @return true when the interaction should be swallowed, i.e. the tool did something or refused
     *         while recharging. A mob with no gear returns false so holding the disarmer still lets
     *         you mount a horse or open a villager's trades.
     */
    public boolean disarm(final Player player, final LivingEntity target, final ItemStack tool) {
        final EntityEquipment equipment = target.getEquipment();
        if (equipment == null || !hasEquipment(equipment)) {
            return false;
        }

        final boolean bypass = player.hasPermission(BYPASS_PERMISSION);
        if (isBroken(tool)) {
            player.sendMessage(ChatColor.RED + "This Mob Disarmer is broken.");
            return true;
        }
        final long now = System.currentTimeMillis();
        int charges = readCharges(tool);
        long readyAt = readRechargeAt(tool);

        if (!bypass && charges <= 0) {
            if (now < readyAt) {
                player.sendMessage(ChatColor.RED + "The Mob Disarmer is recharging — ready in "
                        + ChatColor.YELLOW + formatRemaining(readyAt - now) + ChatColor.RED + ".");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
                scheduleRefresh(player, readyAt - now);
                return true;
            }
            charges = MAX_CHARGES;
            readyAt = 0L;
        }

        final List<StrippedPiece> stripped = stripEquipment(equipment, target instanceof Villager);
        if (stripped.isEmpty()) {
            return false; // lost a race with something else clearing the mob; no charge spent
        }

        int dropped = 0;
        for (final StrippedPiece piece : stripped) {
            // The vanilla chance already sits on the mob: 8.5% for naturally spawned gear, 2.0 for
            // anything the mob picked up itself, and whatever a spawn command asked for otherwise.
            if (ThreadLocalRandom.current().nextFloat() < piece.dropChance()) {
                target.getWorld().dropItemNaturally(target.getLocation().add(0, 0.5, 0), piece.stack());
                dropped++;
            }
        }

        playDisarmEffect(target);
        incrementUses(tool);
        player.sendMessage(ChatColor.GREEN + "Disarmed " + ChatColor.YELLOW + describe(target)
                + ChatColor.GREEN + " — " + ChatColor.YELLOW + stripped.size() + ChatColor.GREEN
                + " piece(s) removed, " + ChatColor.YELLOW + dropped + ChatColor.GREEN + " dropped.");

        if (bypass) {
            // Keep an admin's tool pinned to the full-charge variant so the model never drifts.
            if (charges != MAX_CHARGES || readyAt != 0L) {
                writeState(tool, MAX_CHARGES, 0L);
            }
            return true;
        }

        final double breakChance = charges == 1 ? LAST_USE_BREAK_CHANCE : EARLY_USE_BREAK_CHANCE;
        charges--;
        if (ThreadLocalRandom.current().nextDouble() < breakChance) {
            breakItem(tool);
            player.sendMessage(ChatColor.RED + "The Mob Disarmer broke while in use.");
            return true;
        }
        if (charges <= 0) {
            readyAt = now + RECHARGE_MS;
        }
        writeState(tool, charges, readyAt);

        if (charges <= 0) {
            player.sendMessage(ChatColor.RED + "That was the last charge — fully recharged in "
                    + ChatColor.YELLOW + formatRemaining(RECHARGE_MS) + ChatColor.RED + ".");
            scheduleRefresh(player, RECHARGE_MS);
        } else {
            player.sendMessage(ChatColor.GRAY + "Charges left: " + ChatColor.GREEN + charges
                    + ChatColor.GRAY + "/" + MAX_CHARGES);
        }
        return true;
    }

    /** Green sparkle around the mob, sized to it so it reads on a spider as well as on a zombie. */
    private void playDisarmEffect(final LivingEntity target) {
        final double height = Math.max(0.5D, target.getHeight());
        final double spread = Math.max(0.3D, target.getWidth() * 0.6D);

        target.getWorld().spawnParticle(
            Particle.HAPPY_VILLAGER,
            target.getLocation().add(0, height * 0.5D, 0),
            30,
            spread, height * 0.4D, spread,
            0.0D
        );
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.2f);
    }

    /**
     * Right-clicking air reports status and settles a finished recharge. The state work always runs;
     * only the chat line is rate-limited, so spamming right-click cannot spam chat.
     */
    @Override
    public boolean onUse(final Player player, final ItemStack item) {
        if (isBroken(item)) {
            if (canSendStatus(player)) {
                player.sendMessage(ChatColor.RED + "This Mob Disarmer is broken.");
            }
            return true;
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            if (readCharges(item) != MAX_CHARGES || readRechargeAt(item) != 0L) {
                writeState(item, MAX_CHARGES, 0L);
            }
            if (canSendStatus(player)) {
                player.sendMessage(ChatColor.YELLOW + "Right-click a mob to disarm it. "
                        + ChatColor.GRAY + "Charges: " + ChatColor.GREEN + "unlimited");
            }
            return true;
        }

        final long now = System.currentTimeMillis();
        int charges = readCharges(item);
        final long readyAt = readRechargeAt(item);

        if (charges <= 0 && now >= readyAt) {
            charges = MAX_CHARGES;
            writeState(item, charges, 0L);
        }

        if (charges <= 0) {
            scheduleRefresh(player, readyAt - now);
            if (canSendStatus(player)) {
                player.sendMessage(ChatColor.RED + "Recharging — ready in " + ChatColor.YELLOW
                        + formatRemaining(readyAt - now) + ChatColor.RED + ".");
            }
        } else if (canSendStatus(player)) {
            player.sendMessage(ChatColor.YELLOW + "Right-click a mob to disarm it. "
                    + ChatColor.GRAY + "Charges: " + ChatColor.GREEN + charges
                    + ChatColor.GRAY + "/" + MAX_CHARGES);
        }
        return true;
    }

    private boolean canSendStatus(final Player player) {
        final long now = System.currentTimeMillis();
        final Long last = lastStatusMessage.get(player.getUniqueId());
        if (last != null && now - last < STATUS_MESSAGE_COOLDOWN_MS) {
            return false;
        }
        lastStatusMessage.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * Rewrites any spent disarmer in the inventory whose recharge has elapsed, and books a follow-up
     * task for any that is still counting down. Without this the empty model would linger in hand
     * until the player next clicked something.
     */
    public void refreshInventory(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        long soonest = Long.MAX_VALUE;

        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack stack = contents[slot];
            if (stack == null || stack.getType() == Material.AIR || !isThisItem(stack)) {
                continue;
            }
            if (isBroken(stack)) {
                breakItem(stack); // Also migrates an older broken stack to the 0-3 model immediately.
                inventory.setItem(slot, stack);
                continue;
            }
            if (refresh(stack)) {
                inventory.setItem(slot, stack);
                continue;
            }
            final long readyAt = readRechargeAt(stack);
            // Nexo-issued and older Allium stacks may have correct IDs but stale model data. Keep
            // every persisted charge state canonical even when its recharge has not elapsed yet.
            writeState(stack, readCharges(stack), readyAt);
            inventory.setItem(slot, stack);
            if (readCharges(stack) <= 0 && readyAt > 0L) {
                soonest = Math.min(soonest, readyAt);
            }
        }

        if (soonest != Long.MAX_VALUE) {
            scheduleRefresh(player, soonest - System.currentTimeMillis());
        }
    }

    /** @return true when the stack was spent and its recharge has finished, i.e. it was rewritten. */
    public boolean refresh(final ItemStack item) {
        if (item == null || isBroken(item) || readCharges(item) > 0) {
            return false;
        }
        if (System.currentTimeMillis() < readRechargeAt(item)) {
            return false;
        }
        writeState(item, MAX_CHARGES, 0L);
        return true;
    }

    /** Drops any refresh task booked for a player who has left. */
    public void forget(final Player player) {
        pendingRefresh.remove(player.getUniqueId());
    }

    /**
     * Books one refresh per player. An already-pending task that fires no later than this one would
     * is left alone — it re-runs {@link #refreshInventory} and re-books if anything is still
     * counting down.
     */
    private void scheduleRefresh(final Player player, final long delayMs) {
        final UUID id = player.getUniqueId();
        final long now = System.currentTimeMillis();
        final long dueAt = now + Math.max(0L, delayMs);
        final Long pending = pendingRefresh.get(id);
        if (pending != null && pending <= dueAt && pending >= now) {
            return;
        }
        pendingRefresh.put(id, dueAt);

        // A tick of slack keeps the task from landing a hair before the wall-clock deadline.
        final long delayTicks = Math.max(20L, (Math.max(0L, delayMs) / 50L) + 20L);
        SchedulerAdapter.runAtEntityLater(player, () -> {
            pendingRefresh.remove(id, dueAt);
            final Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                refreshInventory(online);
            }
        }, delayTicks);
    }

    /** Checked before any charge accounting so an unarmed mob never costs a use. */
    private boolean hasEquipment(final EntityEquipment equipment) {
        for (final EquipmentSlot slot : DISARM_SLOTS) {
            final ItemStack piece;
            try {
                piece = equipment.getItem(slot);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                continue;
            }
            if (piece != null && piece.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }

    /** Empties every supported slot, returning the pieces that were actually on the mob. */
    private List<StrippedPiece> stripEquipment(final EntityEquipment equipment,
                                               final boolean guaranteedDrops) {
        final List<StrippedPiece> stripped = new ArrayList<>();
        for (final EquipmentSlot slot : DISARM_SLOTS) {
            final ItemStack piece;
            final float dropChance;
            try {
                piece = equipment.getItem(slot);
                // Read before the slot is cleared — an empty slot has no meaningful drop chance.
                dropChance = equipment.getDropChance(slot);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                continue; // this entity does not carry that slot
            }
            if (piece == null || piece.getType() == Material.AIR) {
                continue;
            }

            equipment.setItem(slot, null);
            stripped.add(new StrippedPiece(piece, guaranteedDrops ? 1.0F : dropChance));
        }
        return stripped;
    }

    private String describe(final LivingEntity target) {
        if (target.getCustomName() != null) {
            return target.getCustomName();
        }
        final String type = target.getType().name().toLowerCase().replace('_', ' ');
        return type.substring(0, 1).toUpperCase() + type.substring(1);
    }

    public int readCharges(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        if (pdc == null) {
            return MAX_CHARGES;
        }
        final Integer charges = pdc.get(chargesKey, PersistentDataType.INTEGER);
        // A stack straight from /nexo give carries no charge tag, so it starts full.
        return charges == null ? MAX_CHARGES : Math.max(0, Math.min(charges, MAX_CHARGES));
    }

    public long readRechargeAt(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        if (pdc == null) {
            return 0L;
        }
        final Long readyAt = pdc.get(rechargeKey, PersistentDataType.LONG);
        return readyAt == null ? 0L : readyAt;
    }

    /**
     * The single writer for this item: charge tags, both id tags, item model, model data, name and
     * lore, all moved to the variant that matches {@code charges}.
     */
    private void writeState(final ItemStack item, final int charges, final long readyAt) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        final int clamped = Math.max(0, Math.min(charges, MAX_CHARGES));
        final String variantId = VARIANT_IDS[clamped];

        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.STRING, variantId);
        pdc.set(nexoKey, PersistentDataType.STRING, variantId);
        pdc.set(chargesKey, PersistentDataType.INTEGER, clamped);
        pdc.set(rechargeKey, PersistentDataType.LONG, readyAt);
        pdc.remove(brokenKey);

        // Both are written: packs keyed on the item model definition and packs keyed on custom
        // model data then render the same variant.
        meta.setItemModel(new NamespacedKey("nexo", variantId));
        meta.setCustomModelData(VARIANT_MODEL_DATA[clamped]);
        meta.setDisplayName(getDisplayName());
        meta.setLore(buildLore(clamped, readyAt, readUses(item)));
        // Two disarmers on different charge counts must never merge into one stack.
        meta.setMaxStackSize(1);

        item.setItemMeta(meta);
    }

    private PersistentDataContainer container(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer();
    }

    private boolean isBroken(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        return pdc != null && Boolean.TRUE.equals(pdc.get(brokenKey, PersistentDataType.BOOLEAN));
    }

    /** Keeps the empty Nexo variant forever, without a recharge deadline that could revive it. */
    private void breakItem(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        final String variantId = VARIANT_IDS[0];
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.STRING, variantId);
        pdc.set(nexoKey, PersistentDataType.STRING, variantId);
        pdc.set(chargesKey, PersistentDataType.INTEGER, 0);
        pdc.set(rechargeKey, PersistentDataType.LONG, 0L);
        pdc.set(brokenKey, PersistentDataType.BOOLEAN, true);
        meta.setItemModel(new NamespacedKey("nexo", variantId));
        meta.setCustomModelData(VARIANT_MODEL_DATA[0]);
        meta.setDisplayName(getDisplayName());
        final long uses = readUses(item);
        final List<String> lore = buildLore(0, 0L, uses);
        lore.remove(lore.size() - 1); // Keep the persisted use counter as the bottom line.
        lore.add(ChatColor.RED + "This item is broken");
        lore.add(ChatColor.DARK_GRAY + "Uses: " + uses);
        meta.setLore(lore);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
    }

    private long readUses(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        final Long uses = pdc == null ? null : pdc.get(usesKey, PersistentDataType.LONG);
        return uses == null ? 0L : Math.max(0L, uses);
    }

    private void incrementUses(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final long uses = readUses(item);
        pdc.set(usesKey, PersistentDataType.LONG, uses == Long.MAX_VALUE ? uses : uses + 1L);
        item.setItemMeta(meta);
    }

    private List<String> buildLore(final int charges, final long readyAt, final long uses) {
        final List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Right-click a mob to strip its");
        lore.add(ChatColor.GRAY + "weapons and armour. Each piece");
        lore.add(ChatColor.GRAY + "drops at its vanilla drop chance.");
        lore.add("");
        if (charges > 0) {
            lore.add(ChatColor.GRAY + "Charges: " + ChatColor.GREEN + charges
                    + ChatColor.GRAY + "/" + MAX_CHARGES);
        } else {
            lore.add(ChatColor.GRAY + "Charges: " + ChatColor.RED + "0" + ChatColor.GRAY + "/"
                    + MAX_CHARGES + ChatColor.DARK_GRAY + " (recharging)");
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Refills all " + MAX_CHARGES + " charges "
                + formatRemaining(RECHARGE_MS) + " after the last is spent.");
        lore.add(ChatColor.DARK_GRAY + "Does not work on players.");
        lore.add(ChatColor.DARK_GRAY + "Uses: " + uses);
        return lore;
    }

    private static String formatRemaining(final long millis) {
        final long seconds = Math.max(0L, (millis + 999L) / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        final long remainder = seconds % 60L;
        return remainder == 0L ? (seconds / 60L) + "m" : (seconds / 60L) + "m " + remainder + "s";
    }
}
