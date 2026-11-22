package codes.castled.curios;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class LegacyImport {
    private static final String CARDS = "alliumcards";
    private static final List<String> NUMBERS = List.of("level", "quality", "rerolls");
    private static final List<String> TEXT = List.of("mob", "card_id", "tier", "signatures", "bonuses", "bonus_strengths", "slot_state");
    record Decoded(Catalog.Entry entry, Map<String, String> state) {}

    private LegacyImport() {}

    static Decoded held(Catalog catalog, ItemStack stack) {
        if (stack == null || stack.getAmount() != 1 || stack.getType().isAir() || stack.getItemMeta() == null) return null;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        String prefix = CARDS + ":";
        if (Byte.valueOf((byte) 1).equals(pdc.get(key("card"), PersistentDataType.BYTE))) {
            Map<String, Object> tags = new HashMap<>();
            tags.put(prefix + "card", true);
            for (String name : NUMBERS) tags.put(prefix + name, pdc.get(key(name), PersistentDataType.INTEGER));
            for (String name : TEXT) tags.put(prefix + name, pdc.get(key(name), PersistentDataType.STRING));
            Object xp = pdc.get(key("xp"), PersistentDataType.STRING);
            if (xp == null) xp = pdc.get(key("xp"), PersistentDataType.DOUBLE);
            if (xp == null) xp = pdc.get(key("xp"), PersistentDataType.LONG);
            if (xp == null) xp = pdc.get(key("xp"), PersistentDataType.INTEGER);
            if (xp == null) xp = pdc.get(key("xp"), PersistentDataType.FLOAT);
            tags.put(prefix + "xp", xp);
            tags.put(prefix + "bound", pdc.get(key("bound"), PersistentDataType.BYTE));
            return card(catalog, tags);
        }
        String marker = pdc.get(new NamespacedKey("abyssallib", "marker"), PersistentDataType.STRING);
        if (marker == null) marker = PackPresentation.itemModel(stack.getItemMeta());
        return relic(catalog, marker);
    }

    static Map<Catalog.Slot, Decoded> load(Catalog catalog, Path path) throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(path)) throw new IOException("No saved Relique file was found.");
        if (Files.size(path) > 2_000_000) throw new IOException("Relique file exceeds the import size limit.");
        var yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        var inventory = yaml.getConfigurationSection("inventory");
        if (inventory == null) throw new IllegalArgumentException("Missing Relique inventory section.");
        Map<Catalog.Slot, Decoded> result = new LinkedHashMap<>();
        for (String type : inventory.getKeys(false)) {
            List<?> slots = inventory.getList(type);
            if (slots == null || slots.size() > 256) throw new IllegalArgumentException("Invalid slot list: " + type);
            for (int index = 0; index < slots.size(); index++) {
                Object raw = slots.get(index);
                if (raw == null || "minecraft:air".equals(raw)) continue;
                Map<?, ?> item = raw instanceof Map<?, ?> row ? row : Map.of("id", raw);
                if ("minecraft:air".equals(item.get("id"))) continue;
                Object amount = item.get("amount");
                if (amount != null && !"1".equals(amount.toString())) {
                    throw new IllegalArgumentException("A legacy slot contains a stack larger than one.");
                }
                Map<?, ?> components = item.get("data") instanceof Map<?, ?> values ? values : Map.of();
                Map<?, ?> custom = components.get("minecraft:custom_data") instanceof Map<?, ?> values ? values : Map.of();
                Map<?, ?> bukkit = custom.get("PublicBukkitValues") instanceof Map<?, ?> values ? values : Map.of();
                Decoded decoded = card(catalog, bukkit);
                if (decoded == null) {
                    Object marker = components.get("abyssallib:marker");
                    decoded = relic(catalog, Objects.toString(marker, Objects.toString(item.get("id"), "")));
                }
                var slot = new Catalog.Slot(type, index);
                if (decoded == null || !catalog.slots.contains(slot) || !decoded.entry().slot().equals(type)) {
                    throw new IllegalArgumentException("Unsupported item or unavailable slot: " + slot.key());
                }
                result.put(slot, decoded);
            }
        }
        return result;
    }

    static Decoded card(Catalog catalog, Map<?, ?> tags) {
        String prefix = CARDS + ":";
        if (!flag(tags.get(prefix + "card"))) return null;
        String id = Objects.toString(tags.get(prefix + "card_id"), "").toLowerCase(Locale.ROOT);
        if (id.isEmpty()) id = Objects.toString(tags.get(prefix + "mob"), "").toLowerCase(Locale.ROOT);
        String tier = Objects.toString(tags.get(prefix + "tier"), "").toUpperCase(Locale.ROOT);
        for (var entry : catalog.entries.values()) {
            if (id.isEmpty() || tier.isEmpty() || !entry.legacyCard().equals(id) || !entry.legacyTier().equals(tier)) continue;
            try {
                int level = Integer.parseInt(Objects.toString(tags.get(prefix + "level"), "0"));
                double xp = Double.parseDouble(Objects.toString(tags.get(prefix + "xp"), "0"));
                if (level > 1000) return null;
                new Progression(level, xp);
                Map<String, String> state = new LinkedHashMap<>();
                state.put("level", Integer.toString(level));
                state.put("xp", Double.toString(xp));
                state.put("bound", Boolean.toString(flag(tags.get(prefix + "bound"))));
                for (String name : List.of("quality", "rerolls", "signatures", "bonuses", "bonus_strengths", "slot_state")) {
                    Object data = tags.get(prefix + name);
                    if (data != null) {
                        String value = data.toString();
                        if (value.length() > 8_000) return null;
                        state.put("legacy_" + name, value);
                    }
                }
                return new Decoded(entry, Map.copyOf(state));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
        return null;
    }

    private static Decoded relic(Catalog catalog, String id) {
        if (id != null && id.startsWith("antiquities:")) {
            for (var entry : catalog.entries.values()) {
                if (id.equals(entry.legacyRelic())) return new Decoded(entry, Map.of());
            }
        }
        return null;
    }

    private static NamespacedKey key(String name) { return new NamespacedKey(CARDS, name); }

    private static boolean flag(Object raw) {
        return Boolean.TRUE.equals(raw) || raw instanceof Number number && number.intValue() == 1
                || "true".equalsIgnoreCase(Objects.toString(raw, ""));
    }
}
