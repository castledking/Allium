package codes.castled.allium.items.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;
import codes.castled.allium.util.SchedulerAdapter;

/**
 * An area-use tool that kills unnamed phantoms within a 64-block spherical radius.
 * State is kept on the stack, including its Nexo variant id, so every charge change updates the
 * model, lore and both item id tags together.
 */
public class PhantomObliteratorItem extends CustomItem {

    public static final String ITEM_ID = "phantom_obliterator";
    public static final int MAX_CHARGES = 3;
    public static final long RECHARGE_MS = 120_000L;
    public static final String BYPASS_PERMISSION = "allium.admin";
    private static final long STATUS_MESSAGE_COOLDOWN_MS = 1_000L;
    /** First and second uses can break too, but are deliberately much safer than the last use. */
    private static final double EARLY_USE_BREAK_CHANCE = 0.05D;
    /** The final use is the risky one, preventing players from safely resetting every two uses. */
    private static final double LAST_USE_BREAK_CHANCE = 0.25D;
    private static final double RADIUS = 64.0D;
    private static final double RADIUS_SQUARED = RADIUS * RADIUS;

    /** Indexed by remaining charges; these ids must match the Nexo item definitions. */
    private static final String[] VARIANT_IDS = {
        ITEM_ID + "_0-3", ITEM_ID + "_1-3", ITEM_ID + "_2-3", ITEM_ID
    };
    /** Indexed by remaining charges: 0/3 through 3/3, matching Nexo's 1011 down to 1008. */
    private static final int[] VARIANT_MODEL_DATA = {1011, 1010, 1009, 1008};

    private final NamespacedKey chargesKey;
    private final NamespacedKey rechargeKey;
    private final NamespacedKey brokenKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey nexoKey = new NamespacedKey("nexo", "id");
    private final Map<UUID, Long> pendingRefresh = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStatusMessage = new ConcurrentHashMap<>();

    public PhantomObliteratorItem(final Plugin plugin) {
        super(plugin, ITEM_ID);
        this.chargesKey = new NamespacedKey(plugin, "phantom_obliterator_charges");
        this.rechargeKey = new NamespacedKey(plugin, "phantom_obliterator_recharge_at");
        this.brokenKey = new NamespacedKey(plugin, "phantom_obliterator_broken");
        this.usesKey = new NamespacedKey(plugin, "phantom_obliterator_uses");
    }

    @Override public Material getMaterial() { return Material.NETHERITE_SWORD; }
    @Override public String getDisplayName() { return ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Phantom Obliterator"; }
    @Override public String getTextureType() { return "item_model"; }
    @Override public Object getItemModel() { return "nexo:" + ITEM_ID; }
    @Override public int getCustomModelData() { return VARIANT_MODEL_DATA[MAX_CHARGES]; }
    @Override public List<String> getLore() { return buildLore(MAX_CHARGES, 0L); }

    @Override
    public ItemStack createItemStack(final int amount) {
        final ItemStack item = new ItemStack(getMaterial(), Math.max(1, amount));
        writeState(item, MAX_CHARGES, 0L);
        return item;
    }

    @Override
    public boolean isThisItem(final ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        final ItemMeta meta = item.getItemMeta();
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String customId = pdc.get(itemKey, PersistentDataType.STRING);
        final String nexoId = pdc.get(nexoKey, PersistentDataType.STRING);
        for (final String variantId : VARIANT_IDS) {
            if (variantId.equals(customId) || variantId.equals(nexoId)) return true;
        }
        // Nexo normally writes nexo:id, but model data is also the declared identity of these
        // variants. This preserves recognition for item stacks created by older pack tooling.
        if (item.getType() == getMaterial() && meta.hasCustomModelData()) {
            final int modelData = meta.getCustomModelData();
            for (final int variantModelData : VARIANT_MODEL_DATA) {
                if (modelData == variantModelData) return true;
            }
        }
        return false;
    }

    /** Called only for an air-click by the listener, never while using a block. */
    @Override
    public boolean onUse(final Player player, final ItemStack item) {
        if (isBroken(item)) {
            sendStatus(player, ChatColor.RED + "This Phantom Obliterator is broken.");
            return true;
        }
        final boolean bypass = player.hasPermission(BYPASS_PERMISSION);
        final long now = System.currentTimeMillis();
        int charges = readCharges(item);
        long readyAt = readRechargeAt(item);

        if (bypass) {
            if (charges != MAX_CHARGES || readyAt != 0L) writeState(item, MAX_CHARGES, 0L);
            obliterate(player);
            return true;
        }
        if (charges <= 0 && now >= readyAt) {
            charges = MAX_CHARGES;
            readyAt = 0L;
            writeState(item, charges, readyAt);
        }
        if (charges <= 0) {
            scheduleRefresh(player, readyAt - now);
            sendStatus(player, ChatColor.RED + "The Phantom Obliterator is recharging — ready in "
                    + ChatColor.YELLOW + formatRemaining(readyAt - now) + ChatColor.RED + ".");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return true;
        }

        final int killed = obliterate(player);
        if (killed == 0) {
            sendStatus(player, ChatColor.GRAY + "No unnamed phantoms within " + ChatColor.LIGHT_PURPLE
                    + "64 blocks" + ChatColor.GRAY + ". Charges: " + ChatColor.GREEN + charges
                    + ChatColor.GRAY + "/" + MAX_CHARGES);
            return true;
        }

        incrementUses(item);
        final double breakChance = charges == 1 ? LAST_USE_BREAK_CHANCE : EARLY_USE_BREAK_CHANCE;
        charges--;
        if (ThreadLocalRandom.current().nextDouble() < breakChance) {
            breakItem(item);
            player.sendMessage(ChatColor.RED + "The Phantom Obliterator broke while in use.");
            return true;
        }
        readyAt = charges == 0 ? now + RECHARGE_MS : 0L;
        writeState(item, charges, readyAt);
        player.sendMessage(ChatColor.DARK_PURPLE + "Obliterated " + ChatColor.LIGHT_PURPLE + killed
                + ChatColor.DARK_PURPLE + " unnamed phantom(s).");
        if (charges == 0) {
            player.sendMessage(ChatColor.RED + "That was the last charge — fully recharged in "
                    + ChatColor.YELLOW + formatRemaining(RECHARGE_MS) + ChatColor.RED + ".");
            scheduleRefresh(player, RECHARGE_MS);
        } else {
            player.sendMessage(ChatColor.GRAY + "Charges left: " + ChatColor.GREEN + charges
                    + ChatColor.GRAY + "/" + MAX_CHARGES);
        }
        return true;
    }

    private int obliterate(final Player player) {
        int killed = 0;
        for (final Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), RADIUS, RADIUS, RADIUS)) {
            if (entity.getType() != EntityType.PHANTOM || entity.getCustomName() != null
                    || entity.getLocation().distanceSquared(player.getLocation()) > RADIUS_SQUARED) continue;
            final Phantom phantom = (Phantom) entity;
            phantom.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                    phantom.getLocation().add(0, phantom.getHeight() * 0.5D, 0), 28,
                    0.55D, phantom.getHeight() * 0.35D, 0.55D, 0.02D);
            phantom.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                    phantom.getLocation().add(0, phantom.getHeight() * 0.5D, 0), 18,
                    0.45D, phantom.getHeight() * 0.25D, 0.45D, 0.0D);
            phantom.setHealth(0.0D);
            killed++;
        }
        if (killed > 0) {
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.0D, 0),
                    80, 1.0D, 0.8D, 1.0D, 0.8D);
            player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.65f);
        }
        return killed;
    }

    public void refreshInventory(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        long soonest = Long.MAX_VALUE;
        for (int slot = 0; slot < contents.length; slot++) {
            final ItemStack item = contents[slot];
            if (!isThisItem(item)) continue;
            if (isBroken(item)) {
                breakItem(item); // Also migrates an older broken stack to the 0-3 model immediately.
                inventory.setItem(slot, item);
                continue;
            }
            if (!refresh(item)) {
                final long readyAt = readRechargeAt(item);
                // Fix stale model data (notably old 0-3 stacks still carrying CMD 1008) without
                // waiting for their cooldown to end.
                writeState(item, readCharges(item), readyAt);
                if (readCharges(item) == 0 && readyAt > 0L) soonest = Math.min(soonest, readyAt);
            }
            inventory.setItem(slot, item);
        }
        if (soonest != Long.MAX_VALUE) scheduleRefresh(player, soonest - System.currentTimeMillis());
    }

    public boolean refresh(final ItemStack item) {
        if (item == null || isBroken(item) || readCharges(item) > 0 || System.currentTimeMillis() < readRechargeAt(item)) return false;
        writeState(item, MAX_CHARGES, 0L);
        return true;
    }

    public void forget(final Player player) { pendingRefresh.remove(player.getUniqueId()); }

    private void scheduleRefresh(final Player player, final long delayMs) {
        final UUID id = player.getUniqueId();
        final long now = System.currentTimeMillis();
        final long dueAt = now + Math.max(0L, delayMs);
        final Long pending = pendingRefresh.get(id);
        if (pending != null && pending <= dueAt && pending >= now) return;
        pendingRefresh.put(id, dueAt);
        final long ticks = Math.max(20L, Math.max(0L, delayMs) / 50L + 20L);
        SchedulerAdapter.runAtEntityLater(player, () -> {
            pendingRefresh.remove(id, dueAt);
            final Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) refreshInventory(online);
        }, ticks);
    }

    private int readCharges(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        final Integer value = pdc == null ? null : pdc.get(chargesKey, PersistentDataType.INTEGER);
        return value == null ? MAX_CHARGES : Math.max(0, Math.min(MAX_CHARGES, value));
    }

    private long readRechargeAt(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        final Long value = pdc == null ? null : pdc.get(rechargeKey, PersistentDataType.LONG);
        return value == null ? 0L : value;
    }

    private void writeState(final ItemStack item, final int charges, final long readyAt) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        final int clamped = Math.max(0, Math.min(MAX_CHARGES, charges));
        final String id = VARIANT_IDS[clamped];
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.STRING, id);
        pdc.set(nexoKey, PersistentDataType.STRING, id);
        pdc.set(chargesKey, PersistentDataType.INTEGER, clamped);
        pdc.set(rechargeKey, PersistentDataType.LONG, readyAt);
        pdc.remove(brokenKey);
        meta.setItemModel(new NamespacedKey("nexo", id));
        meta.setCustomModelData(VARIANT_MODEL_DATA[clamped]);
        meta.setDisplayName(getDisplayName());
        meta.setLore(buildLore(clamped, readUses(item)));
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
    }

    private PersistentDataContainer container(final ItemStack item) {
        return item == null || !item.hasItemMeta() ? null : item.getItemMeta().getPersistentDataContainer();
    }

    private boolean isBroken(final ItemStack item) {
        final PersistentDataContainer pdc = container(item);
        return pdc != null && Boolean.TRUE.equals(pdc.get(brokenKey, PersistentDataType.BOOLEAN));
    }

    /** Keeps the empty Nexo variant forever, without a recharge deadline that could revive it. */
    private void breakItem(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        final String id = VARIANT_IDS[0];
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemKey, PersistentDataType.STRING, id);
        pdc.set(nexoKey, PersistentDataType.STRING, id);
        pdc.set(chargesKey, PersistentDataType.INTEGER, 0);
        pdc.set(rechargeKey, PersistentDataType.LONG, 0L);
        pdc.set(brokenKey, PersistentDataType.BOOLEAN, true);
        meta.setItemModel(new NamespacedKey("nexo", id));
        meta.setCustomModelData(VARIANT_MODEL_DATA[0]);
        meta.setDisplayName(getDisplayName());
        final long uses = readUses(item);
        final List<String> lore = buildLore(0, uses);
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
        if (meta == null) return;
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final long uses = readUses(item);
        pdc.set(usesKey, PersistentDataType.LONG, uses == Long.MAX_VALUE ? uses : uses + 1L);
        item.setItemMeta(meta);
    }

    private List<String> buildLore(final int charges, final long uses) {
        final List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Right-click air to kill all unnamed");
        lore.add(ChatColor.GRAY + "phantoms within a 64-block radius.");
        lore.add("");
        lore.add(ChatColor.GRAY + "Charges: " + (charges > 0 ? ChatColor.GREEN + "" + charges
                : ChatColor.RED + "0") + ChatColor.GRAY + "/" + MAX_CHARGES
                + (charges == 0 ? ChatColor.DARK_GRAY + " (recharging)" : ""));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Refills all 3 charges 2m after the last is spent.");
        lore.add(ChatColor.DARK_GRAY + "Uses: " + uses);
        return lore;
    }

    private void sendStatus(final Player player, final String message) {
        final long now = System.currentTimeMillis();
        final Long last = lastStatusMessage.get(player.getUniqueId());
        if (last == null || now - last >= STATUS_MESSAGE_COOLDOWN_MS) {
            lastStatusMessage.put(player.getUniqueId(), now);
            player.sendMessage(message);
        }
    }

    private static String formatRemaining(final long millis) {
        final long seconds = Math.max(0L, (millis + 999L) / 1000L);
        return seconds < 60L ? seconds + "s" : seconds / 60L + "m"
                + (seconds % 60L == 0 ? "" : " " + seconds % 60L + "s");
    }
}
