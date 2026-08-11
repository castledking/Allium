package codes.castled.allium.items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import codes.castled.allium.util.ApiCompat;

/** Persistent Nexo-backed states for staff and claim handcuffs. */
public final class HandcuffsItem {
    public static final String HANDCUFFS_ID = "handcuffs";
    public static final String HANDCUFFS_RESTRAINED_ID = "handcuffs_restrained";
    public static final String CLAIM_HANDCUFFS_ID = "claim_handcuffs";
    public static final String CLAIM_HANDCUFFS_RESTRAINED_ID = "claim_handcuffs_restrained";

    private static final NamespacedKey LEGACY_KEY = new NamespacedKey("allium", "handcuffs_item");
    private static final NamespacedKey ALLIUM_ID_KEY = new NamespacedKey("allium", "custom_item_id");
    private static final NamespacedKey NEXO_ID_KEY = new NamespacedKey("nexo", "id");
    private static final UUID LEGACY_SPEED_MODIFIER =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private HandcuffsItem() {}

    public enum Family { STAFF, CLAIM }

    public enum Type {
        STAFF(HANDCUFFS_ID, Family.STAFF, false, 1012),
        STAFF_RESTRAINED(HANDCUFFS_RESTRAINED_ID, Family.STAFF, true, 1013),
        CLAIM(CLAIM_HANDCUFFS_ID, Family.CLAIM, false, 1014),
        CLAIM_RESTRAINED(CLAIM_HANDCUFFS_RESTRAINED_ID, Family.CLAIM, true, 1015);

        private final String id;
        private final Family family;
        private final boolean restrained;
        private final int modelData;

        Type(final String id, final Family family, final boolean restrained, final int modelData) {
            this.id = id;
            this.family = family;
            this.restrained = restrained;
            this.modelData = modelData;
        }

        public String id() { return id; }
        public Family family() { return family; }
        public boolean restrained() { return restrained; }
        public int modelData() { return modelData; }
    }

    public static ItemStack createHandcuffs() {
        return create(Type.STAFF);
    }

    public static ItemStack createClaimHandcuffs() {
        return create(Type.CLAIM);
    }

    private static ItemStack create(final Type type) {
        final ItemStack item = new ItemStack(Material.FISHING_ROD);
        writeType(item, type);
        return item;
    }

    public static boolean isHandcuffs(final ItemStack item) {
        return getType(item) != null;
    }

    public static boolean isClaimHandcuffs(final ItemStack item) {
        final Type type = getType(item);
        return type != null && type.family() == Family.CLAIM;
    }

    /** Rewrites Nexo-issued cuffs with Allium's persistent ID, model and unbreakable metadata. */
    public static void normalize(final ItemStack item) {
        final Type type = getType(item);
        if (type != null) writeType(item, type);
    }

    public static Type getType(final ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return null;
        }
        final ItemMeta meta = item.getItemMeta();
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        final String alliumId = pdc.get(ALLIUM_ID_KEY, PersistentDataType.STRING);
        final String nexoId = pdc.get(NEXO_ID_KEY, PersistentDataType.STRING);
        final Type alliumType = byId(alliumId);
        if (alliumType != null) return alliumType;
        final Type nexoType = byId(nexoId);
        if (nexoType != null) return nexoType;
        if (meta.hasCustomModelData()) {
            final int modelData = meta.getCustomModelData();
            for (final Type type : Type.values()) {
                if (type.modelData() == modelData) return type;
            }
        }
        // Handcuffs issued by older Allium builds become ordinary staff cuffs on first rewrite.
        return pdc.has(LEGACY_KEY, PersistentDataType.BYTE) ? Type.STAFF : null;
    }

    private static Type byId(final String id) {
        if (id == null) return null;
        for (final Type type : Type.values()) {
            if (type.id().equals(id)) return type;
        }
        return null;
    }

    public static void updateFamilyState(final org.bukkit.entity.Player player,
                                         final Family family,
                                         final boolean restrained) {
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack item = inventory.getItem(slot);
            final Type current = getType(item);
            if (current == null || current.family() != family) continue;
            writeType(item, type(family, restrained));
            inventory.setItem(slot, item);
        }
    }

    /** Compatibility entry point retained for older callers. */
    public static void updateHandcuffsModelData(final org.bukkit.entity.Player player,
                                                final String modelName) {
        final boolean restrained = modelName != null
                && !modelName.endsWith("fishing_rod_handcuffs");
        updateFamilyState(player, Family.STAFF, restrained);
    }

    private static Type type(final Family family, final boolean restrained) {
        if (family == Family.CLAIM) return restrained ? Type.CLAIM_RESTRAINED : Type.CLAIM;
        return restrained ? Type.STAFF_RESTRAINED : Type.STAFF;
    }

    private static void writeType(final ItemStack item, final Type type) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(LEGACY_KEY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(ALLIUM_ID_KEY, PersistentDataType.STRING, type.id());
        pdc.set(NEXO_ID_KEY, PersistentDataType.STRING, type.id());
        meta.setItemModel(new NamespacedKey("nexo", type.id()));
        meta.setCustomModelData(type.modelData());
        meta.setDisplayName(displayName(type));
        meta.setLore(lore(type));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.setMaxStackSize(1);
        removeLegacySpeedPenalty(meta);
        item.setItemMeta(meta);
    }

    private static void removeLegacySpeedPenalty(final ItemMeta meta) {
        if (ApiCompat.MOVEMENT_SPEED == null) return;
        final Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(ApiCompat.MOVEMENT_SPEED);
        if (modifiers == null) return;
        for (final AttributeModifier modifier : new ArrayList<>(modifiers)) {
            if (LEGACY_SPEED_MODIFIER.equals(modifier.getUniqueId())) {
                meta.removeAttributeModifier(ApiCompat.MOVEMENT_SPEED, modifier);
            }
        }
    }

    private static String displayName(final Type type) {
        return switch (type) {
            case STAFF -> ChatColor.GOLD + "Handcuffs";
            case STAFF_RESTRAINED -> ChatColor.RED + "Handcuffs (Restraining Player)";
            case CLAIM -> ChatColor.GOLD + "Claim Handcuffs";
            case CLAIM_RESTRAINED -> ChatColor.RED + "Claim Handcuffs (Ban Pending)";
        };
    }

    private static List<String> lore(final Type type) {
        return switch (type) {
            case STAFF -> List.of(ChatColor.GRAY + "Right-click to cuff someone.");
            case STAFF_RESTRAINED -> List.of(ChatColor.GRAY + "Press Q to unrestrain player.");
            case CLAIM -> List.of(ChatColor.GRAY + "Right-click to cuff an untrusted player",
                    ChatColor.GRAY + "inside a claim you manage.");
            case CLAIM_RESTRAINED -> List.of(ChatColor.GRAY + "Drop this item to cancel the claim ban.");
        };
    }
}
