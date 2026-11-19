package codes.castled.allium.creative;

import codes.castled.allium.message.MessageManager;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CreativeManager's item rules, each switched on in {@code creative-manager}: lore on items taken
 * from the creative menu, cleaning or refusing what only modified clients produce, and the armour
 * worn while in creative.
 */
final class CreativeItems {

    record ArmorPiece(Material material, String name, List<String> lore, @Nullable Color color) {
    }

    record Settings(boolean loreEnabled, List<String> loreLines, boolean sanitize, boolean refuseEnchanted,
                    boolean refusePotions, boolean clearEffects, boolean armorEnabled, List<ArmorPiece> armor) {

        /** Helmet, chestplate, leggings and boots, in that order. */
        static final List<String> SLOTS = List.of("helmet", "chestplate", "leggings", "boots");

        static Settings read(@Nullable ConfigurationSection c, Consumer<String> warn) {
            if (c == null) {
                return new Settings(true, DEFAULT_LORE, true, true, true, true, true, defaultArmor());
            }
            List<ArmorPiece> armor = new ArrayList<>();
            List<ArmorPiece> defaults = defaultArmor();
            for (int i = 0; i < SLOTS.size(); i++) {
                ConfigurationSection piece = c.getConfigurationSection("armor." + SLOTS.get(i));
                ArmorPiece fallback = defaults.get(i);
                if (piece == null) {
                    armor.add(fallback);
                    continue;
                }
                Material material = Material.matchMaterial(piece.getString("material", fallback.material().name()));
                if (material == null) {
                    warn.accept("creative-manager.armor." + SLOTS.get(i) + ": unknown material " + piece.getString("material"));
                    material = fallback.material();
                }
                armor.add(new ArmorPiece(material, piece.getString("name", fallback.name()),
                        piece.contains("lore") ? piece.getStringList("lore") : fallback.lore(),
                        piece.contains("color") ? color(piece.getString("color"), warn) : fallback.color()));
            }
            return new Settings(c.getBoolean("item-lore.enabled", true),
                    c.isList("item-lore.lines") ? List.copyOf(c.getStringList("item-lore.lines")) : DEFAULT_LORE,
                    c.getBoolean("menu-items.sanitize", true), c.getBoolean("menu-items.refuse-enchanted", true),
                    c.getBoolean("menu-items.refuse-potions", true), c.getBoolean("clear-effects-on-switch", true),
                    c.getBoolean("armor.enabled", true), List.copyOf(armor));
        }

        static final List<String> DEFAULT_LORE = List.of("&5Creative {item}", "&7Taken by {player}");

        /** Allium's pink, the {@code &d} of its prefix. */
        static final Color ALLIUM_PINK = Color.fromRGB(0xFF55FF);

        static List<ArmorPiece> defaultArmor() {
            List<String> lore = List.of("&7Worn while in creative mode");
            return List.of(new ArmorPiece(Material.LEATHER_HELMET, "&6Creative Helmet", lore, ALLIUM_PINK),
                    new ArmorPiece(Material.LEATHER_CHESTPLATE, "&6Creative Chestplate", lore, ALLIUM_PINK),
                    new ArmorPiece(Material.LEATHER_LEGGINGS, "&6Creative Leggings", lore, ALLIUM_PINK),
                    new ArmorPiece(Material.LEATHER_BOOTS, "&6Creative Boots", lore, ALLIUM_PINK));
        }

        @Nullable
        private static Color color(@Nullable String raw, Consumer<String> warn) {
            if (raw == null || raw.isBlank()) return null;
            String hex = raw.trim().startsWith("#") ? raw.trim().substring(1) : raw.trim();
            try {
                return Color.fromRGB(Integer.parseInt(hex, 16) & 0xFFFFFF);
            } catch (NumberFormatException invalid) {
                warn.accept("creative-manager.armor: invalid color " + raw);
                return null;
            }
        }
    }

    private CreativeItems() {
    }

    /** Whether the item carries an enchantment, or stores one (an enchanted book). */
    static boolean enchanted(@Nullable ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && (meta.hasEnchants() || meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants());
    }

    /** Whether the item is a potion, tipped arrow or stew with an effect (water bottles have none). */
    static boolean hasEffects(@Nullable ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta instanceof SuspiciousStewMeta stew) return stew.hasCustomEffects();
        if (!(meta instanceof PotionMeta potion)) return false;
        return potion.hasCustomEffects() || baseHasEffects(potion);
    }

    /** The base potion's effects, through whichever accessor this server version has. */
    private static boolean baseHasEffects(PotionMeta potion) {
        try {
            Object type = PotionMeta.class.getMethod("getBasePotionType").invoke(potion);
            if (type == null) return false;
            Object effects = type.getClass().getMethod("getPotionEffects").invoke(type);
            return effects instanceof List<?> list && !list.isEmpty();
        } catch (ReflectiveOperationException | LinkageError newer) {
            try {
                Object data = PotionMeta.class.getMethod("getBasePotionData").invoke(potion);
                Object type = data.getClass().getMethod("getType").invoke(data);
                return type.getClass().getMethod("getEffectType").invoke(type) != null;
            } catch (ReflectiveOperationException | LinkageError | NullPointerException older) {
                return false;
            }
        }
    }

    /**
     * A copy without what the creative menu never hands out: custom (plugin) data, attribute
     * modifiers, the unbreakable flag and enchantments above their highest level.
     */
    static ItemStack sanitize(ItemStack item, java.util.Set<NamespacedKey> keep) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        for (NamespacedKey key : new ArrayList<>(meta.getPersistentDataContainer().getKeys())) {
            if (!keep.contains(key)) meta.getPersistentDataContainer().remove(key);
        }
        if (meta.hasAttributeModifiers()) meta.setAttributeModifiers(null);
        meta.setUnbreakable(false);
        for (Map.Entry<Enchantment, Integer> e : new HashMap<>(meta.getEnchants()).entrySet()) {
            if (e.getValue() > e.getKey().getMaxLevel()) meta.removeEnchant(e.getKey());
        }
        if (meta instanceof EnchantmentStorageMeta book) {
            for (Map.Entry<Enchantment, Integer> e : new HashMap<>(book.getStoredEnchants()).entrySet()) {
                if (e.getValue() > e.getKey().getMaxLevel()) book.removeStoredEnchant(e.getKey());
            }
        }
        copy.setItemMeta(meta);
        return copy;
    }

    /** Whether the item already carries the creative lore marker. */
    static boolean tagged(@Nullable ItemStack item, NamespacedKey key) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** A copy with the lore lines appended and the taker's UUID recorded. */
    static ItemStack tag(ItemStack item, Player player, List<String> lines, NamespacedKey key) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String itemName = codes.castled.allium.command.essentials.UtilitySupport.titleCase(item.getType().name());
        for (String line : lines) {
            lore.add(MessageManager.colorize(line.replace("{item}", itemName).replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
        copy.setItemMeta(meta);
        return copy;
    }

    /** One configured armour piece, marked so it cannot leave the armour slot or creative mode. */
    static ItemStack armorItem(ArmorPiece piece, NamespacedKey key) {
        ItemStack item = new ItemStack(piece.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (!piece.name().isBlank()) meta.setDisplayName(MessageManager.colorize(piece.name()));
        if (!piece.lore().isEmpty()) meta.setLore(piece.lore().stream().map(MessageManager::colorize).toList());
        if (piece.color() != null && meta instanceof LeatherArmorMeta leather) leather.setColor(piece.color());
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    static boolean creativeArmor(@Nullable ItemStack item, NamespacedKey key) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
