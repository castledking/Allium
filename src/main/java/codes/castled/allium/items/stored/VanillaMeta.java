package codes.castled.allium.items.stored;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import codes.castled.allium.managers.core.Text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The hand-editable {@code vanilla:} half of a stored item.
 * <p>
 * Every field is nullable, and null means <em>absent</em> rather than <em>default</em>: applying a
 * VanillaMeta clears each property it models before setting it, so deleting a key from the yml
 * genuinely removes it from the item. Anything not modelled here is left exactly as the snapshot
 * had it — that split is what lets Allium stay lossless without covering every component in the
 * game. See docs/CUSTOM_ITEM_STORAGE.md §3.
 * <p>
 * Names and lore are stored as legacy {@code &} colour strings. A component carrying styling legacy
 * cannot express (gradients, hover events) is flattened when captured; write the line as
 * MiniMessage in the yml to get that styling back, since applying runs through
 * {@link Text#parseColors(String)}.
 */
public final class VanillaMeta {

    private String name;
    private List<String> lore;
    private Boolean unbreakable;
    private Integer damage;
    private Integer maxDurability;
    private Integer maxStackSize;
    private Integer customModelData;
    private String itemModel;
    private String rarity;
    private Integer repairCost;
    private Boolean hideTooltip;
    private Boolean glint;
    private List<String> flags;
    private Map<String, Integer> enchants;
    private List<AttributeEntry> attributes;

    private VanillaMeta() {
    }

    /** One entry of the {@code attributes:} list. */
    public static final class AttributeEntry {
        private String attribute;
        private double amount;
        private String operation;
        private String slot;
        private String id;

        public String getAttribute() {
            return attribute;
        }

        public double getAmount() {
            return amount;
        }

        public String getOperation() {
            return operation;
        }

        public String getSlot() {
            return slot;
        }

        public String getId() {
            return id;
        }
    }

    // ======================================================================
    // CAPTURE
    // ======================================================================

    /** Decomposes a live stack into readable fields. Returns null when the item carries no meta. */
    public static VanillaMeta from(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return null;
        }

        VanillaMeta model = new VanillaMeta();

        if (meta.hasDisplayName()) {
            model.name = toEditableColours(meta.displayName());
        }
        if (meta.hasLore() && meta.lore() != null) {
            model.lore = new ArrayList<>();
            for (Component line : meta.lore()) {
                model.lore.add(toEditableColours(line));
            }
        }
        if (meta.isUnbreakable()) {
            model.unbreakable = Boolean.TRUE;
        }
        // A rich custom-model-data component (strings, colours, flags, multiple floats) cannot be
        // expressed as a single legacy int, so it is deliberately left unmodelled and the snapshot
        // keeps it. Nexo and Oraxen items routinely use that form.
        if (meta.hasCustomModelData() && !hasRichCustomModelData(meta)) {
            model.customModelData = meta.getCustomModelData();
        }
        if (meta.hasItemModel() && meta.getItemModel() != null) {
            model.itemModel = meta.getItemModel().toString();
        }
        if (meta.hasRarity() && meta.getRarity() != null) {
            model.rarity = meta.getRarity().name();
        }
        if (meta.hasMaxStackSize()) {
            model.maxStackSize = meta.getMaxStackSize();
        }
        if (meta.isHideTooltip()) {
            model.hideTooltip = Boolean.TRUE;
        }
        if (meta.hasEnchantmentGlintOverride()) {
            model.glint = meta.getEnchantmentGlintOverride();
        }

        if (meta instanceof Damageable damageable) {
            if (damageable.hasDamage()) {
                model.damage = damageable.getDamage();
            }
            if (damageable.hasMaxDamage()) {
                model.maxDurability = damageable.getMaxDamage();
            }
        }
        if (meta instanceof Repairable repairable && repairable.hasRepairCost()) {
            model.repairCost = repairable.getRepairCost();
        }

        if (!meta.getItemFlags().isEmpty()) {
            model.flags = new ArrayList<>();
            for (ItemFlag flag : meta.getItemFlags()) {
                model.flags.add(flag.name());
            }
        }

        if (meta.hasEnchants()) {
            model.enchants = new LinkedHashMap<>();
            meta.getEnchants().forEach((enchantment, level) ->
                    model.enchants.put(shortKey(enchantment.getKey()), level));
        }

        // An item with an empty modifier component hides its defaults, which is different from
        // having no component at all, so an empty list is preserved rather than dropped.
        if (meta.hasAttributeModifiers()) {
            model.attributes = new ArrayList<>();
            Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
            if (modifiers != null) {
                modifiers.forEach((attribute, modifier) -> {
                    AttributeEntry entry = new AttributeEntry();
                    entry.attribute = shortKey(attribute.getKey());
                    entry.amount = modifier.getAmount();
                    entry.operation = modifier.getOperation().name();
                    entry.slot = modifier.getSlotGroup().toString();
                    entry.id = modifier.getKey().toString();
                    model.attributes.add(entry);
                });
            }
        }

        return model;
    }

    // ======================================================================
    // READ / WRITE
    // ======================================================================

    /** Reads a {@code vanilla:} section. Returns null when the section is absent. */
    public static VanillaMeta read(ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        VanillaMeta model = new VanillaMeta();
        model.name = section.getString("name");
        model.lore = section.contains("lore") ? new ArrayList<>(section.getStringList("lore")) : null;
        model.unbreakable = readBoolean(section, "unbreakable");
        model.damage = readInt(section, "damage");
        model.maxDurability = readInt(section, "max-durability");
        model.maxStackSize = readInt(section, "max-stack-size");
        model.customModelData = readInt(section, "custom-model-data");
        model.itemModel = emptyToNull(section.getString("item-model"));
        model.rarity = emptyToNull(section.getString("rarity"));
        model.repairCost = readInt(section, "repair-cost");
        model.hideTooltip = readBoolean(section, "hide-tooltip");
        model.glint = readBoolean(section, "glint");
        model.flags = section.contains("flags") ? new ArrayList<>(section.getStringList("flags")) : null;

        ConfigurationSection enchants = section.getConfigurationSection("enchants");
        if (enchants != null) {
            model.enchants = new LinkedHashMap<>();
            for (String key : enchants.getKeys(false)) {
                model.enchants.put(key, enchants.getInt(key));
            }
        }

        if (section.contains("attributes")) {
            model.attributes = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("attributes")) {
                AttributeEntry entry = new AttributeEntry();
                entry.attribute = asString(raw.get("attribute"));
                entry.amount = raw.get("amount") instanceof Number number ? number.doubleValue() : 0.0D;
                entry.operation = asString(raw.get("operation"));
                entry.slot = asString(raw.get("slot"));
                entry.id = asString(raw.get("id"));
                model.attributes.add(entry);
            }
        }

        return model;
    }

    /** Writes every set field into a {@code vanilla:} section, leaving unset ones out. */
    public void write(ConfigurationSection section) {
        section.set("name", name);
        section.set("lore", lore);
        section.set("unbreakable", unbreakable);
        section.set("damage", damage);
        section.set("max-durability", maxDurability);
        section.set("max-stack-size", maxStackSize);
        section.set("custom-model-data", customModelData);
        section.set("item-model", itemModel);
        section.set("rarity", rarity);
        section.set("repair-cost", repairCost);
        section.set("hide-tooltip", hideTooltip);
        section.set("glint", glint);
        section.set("flags", flags);

        if (enchants != null) {
            ConfigurationSection target = section.createSection("enchants");
            enchants.forEach(target::set);
        }

        if (attributes != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (AttributeEntry entry : attributes) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("attribute", entry.attribute);
                raw.put("amount", entry.amount);
                raw.put("operation", entry.operation);
                raw.put("slot", entry.slot);
                raw.put("id", entry.id);
                list.add(raw);
            }
            section.set("attributes", list);
        }
    }

    // ======================================================================
    // APPLY
    // ======================================================================

    /**
     * Clears then re-applies every modelled property on the given stack.
     * <p>
     * Unparseable enchantment, attribute, flag or rarity names are skipped with a warning rather
     * than aborting: one bad line in a hand-edited file should not cost the whole item.
     *
     * @param id the stored item's id, used to mint stable attribute modifier keys
     */
    public void applyTo(ItemStack item, String id) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.displayName(name == null ? null : toComponent(name));

        if (lore == null) {
            meta.lore(null);
        } else {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(toComponent(line));
            }
            meta.lore(lines);
        }

        meta.setUnbreakable(Boolean.TRUE.equals(unbreakable));
        meta.setHideTooltip(Boolean.TRUE.equals(hideTooltip));
        meta.setEnchantmentGlintOverride(glint);

        if (customModelData != null) {
            guard(id, "custom-model-data", () -> meta.setCustomModelData(customModelData));
        } else if (!hasRichCustomModelData(meta)) {
            meta.setCustomModelData(null);
        }

        guard(id, "max-stack-size", () -> meta.setMaxStackSize(maxStackSize));
        guard(id, "item-model", () -> meta.setItemModel(itemModel == null ? null : NamespacedKey.fromString(itemModel)));
        meta.setRarity(parseRarity(id));

        if (meta instanceof Damageable damageable) {
            guard(id, "damage", () -> {
                if (damage == null) {
                    damageable.resetDamage();
                } else {
                    damageable.setDamage(damage);
                }
            });
            guard(id, "max-durability", () -> damageable.setMaxDamage(maxDurability));
        }
        if (meta instanceof Repairable repairable) {
            guard(id, "repair-cost", () -> repairable.setRepairCost(repairCost == null ? 0 : repairCost));
        }

        meta.removeItemFlags(ItemFlag.values());
        if (flags != null) {
            for (String raw : flags) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(raw.toUpperCase(Locale.ENGLISH)));
                } catch (IllegalArgumentException e) {
                    warn(id, "unknown item flag '" + raw + "'");
                }
            }
        }

        for (Enchantment enchantment : new ArrayList<>(meta.getEnchants().keySet())) {
            meta.removeEnchant(enchantment);
        }
        if (enchants != null) {
            enchants.forEach((raw, level) -> {
                Enchantment enchantment = parseEnchantment(raw);
                if (enchantment == null) {
                    warn(id, "unknown enchantment '" + raw + "'");
                } else {
                    // Unrestricted on purpose: stored items routinely carry levels and combinations
                    // the anvil would refuse.
                    guard(id, "enchant " + raw, () -> meta.addEnchant(enchantment, level, true));
                }
            });
        }

        applyAttributes(meta, id);

        item.setItemMeta(meta);
    }

    private void applyAttributes(ItemMeta meta, String id) {
        if (attributes == null) {
            meta.setAttributeModifiers(null);
            return;
        }
        Multimap<Attribute, AttributeModifier> modifiers = LinkedHashMultimap.create();
        int index = 0;
        for (AttributeEntry entry : attributes) {
            Attribute attribute = parseAttribute(entry.attribute);
            if (attribute == null) {
                warn(id, "unknown attribute '" + entry.attribute + "'");
                index++;
                continue;
            }
            AttributeModifier.Operation operation;
            try {
                operation = entry.operation == null
                        ? AttributeModifier.Operation.ADD_NUMBER
                        : AttributeModifier.Operation.valueOf(entry.operation.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                warn(id, "unknown attribute operation '" + entry.operation + "'");
                index++;
                continue;
            }
            EquipmentSlotGroup slot = entry.slot == null
                    ? EquipmentSlotGroup.ANY
                    : EquipmentSlotGroup.getByName(entry.slot.toLowerCase(Locale.ENGLISH));
            if (slot == null) {
                warn(id, "unknown equipment slot group '" + entry.slot + "'");
                index++;
                continue;
            }
            // A modifier key must be stable across gives, or two copies of the same stored item
            // would carry different keys and refuse to stack.
            NamespacedKey key = entry.id == null ? null : NamespacedKey.fromString(entry.id);
            if (key == null) {
                key = new NamespacedKey("allium", id + "_" + attribute.getKey().getKey() + "_" + index);
            }
            modifiers.put(attribute, new AttributeModifier(key, entry.amount, operation, slot));
            index++;
        }
        meta.setAttributeModifiers(modifiers);
    }

    private ItemRarity parseRarity(String id) {
        if (rarity == null) {
            return null;
        }
        try {
            return ItemRarity.valueOf(rarity.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            warn(id, "unknown rarity '" + rarity + "'");
            return null;
        }
    }

    // ======================================================================

    /** Accepts {@code sharpness}, {@code minecraft:sharpness} and other namespaces alike. */
    private static Enchantment parseEnchantment(String raw) {
        NamespacedKey key = toKey(raw);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    private static Attribute parseAttribute(String raw) {
        NamespacedKey key = toKey(raw);
        return key == null ? null : Registry.ATTRIBUTE.get(key);
    }

    private static NamespacedKey toKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String normalised = raw.toLowerCase(Locale.ENGLISH);
        return normalised.indexOf(':') >= 0
                ? NamespacedKey.fromString(normalised)
                : NamespacedKey.minecraft(normalised);
    }

    /** Drops the {@code minecraft:} prefix so common keys read cleanly in the yml. */
    private static String shortKey(NamespacedKey key) {
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.toString();
    }

    private static Component toComponent(String raw) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(Text.parseColors(raw))
                // Vanilla renders custom names and lore italic; Allium's convention everywhere else
                // is to turn that off.
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String toEditableColours(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component).replace('§', '&');
    }

    /**
     * True when the item's custom-model-data carries more than a single legacy float, meaning the
     * {@code custom-model-data:} int cannot represent it.
     */
    private static boolean hasRichCustomModelData(ItemMeta meta) {
        if (!meta.hasCustomModelDataComponent()) {
            return false;
        }
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        return !component.getStrings().isEmpty()
                || !component.getFlags().isEmpty()
                || !component.getColors().isEmpty()
                || component.getFloats().size() > 1;
    }

    /**
     * Runs a setter, turning a rejected value into a warning instead of an aborted item.
     * Bukkit validates several of these — max stack size must be 1-99, for instance — and a
     * hand-edited file should not be able to make a whole item unbuildable.
     */
    private static void guard(String id, String field, Runnable setter) {
        try {
            setter.run();
        } catch (Exception e) {
            warn(id, "could not apply " + field + ": " + e.getMessage());
        }
    }

    private static void warn(String id, String problem) {
        Text.sendDebugLog(Text.DebugSeverity.WARN, "Stored item '" + id + "': " + problem + " (skipped)");
    }

    private static Boolean readBoolean(ConfigurationSection section, String path) {
        return section.contains(path) ? section.getBoolean(path) : null;
    }

    private static Integer readInt(ConfigurationSection section, String path) {
        return section.contains(path) ? section.getInt(path) : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // Accessors used by tests and /core item info.

    public String getName() {
        return name;
    }

    public List<String> getLore() {
        return lore;
    }

    public Boolean getUnbreakable() {
        return unbreakable;
    }

    public Integer getDamage() {
        return damage;
    }

    public Integer getMaxDurability() {
        return maxDurability;
    }

    public Integer getMaxStackSize() {
        return maxStackSize;
    }

    public Integer getCustomModelData() {
        return customModelData;
    }

    public String getItemModel() {
        return itemModel;
    }

    public String getRarity() {
        return rarity;
    }

    public Integer getRepairCost() {
        return repairCost;
    }

    public Boolean getHideTooltip() {
        return hideTooltip;
    }

    public Boolean getGlint() {
        return glint;
    }

    public List<String> getFlags() {
        return flags;
    }

    public Map<String, Integer> getEnchants() {
        return enchants;
    }

    public List<AttributeEntry> getAttributes() {
        return attributes;
    }
}
