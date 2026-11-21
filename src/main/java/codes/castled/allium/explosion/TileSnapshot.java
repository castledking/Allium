package codes.castled.allium.explosion;

import codes.castled.allium.mail.MailItemCodec;
import org.bukkit.DyeColor;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerProfile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The part of a block entity that survives a restart in the regeneration journal: what a container
 * (or lectern, jukebox, chiseled bookshelf, decorated pot) held, a custom name, sign text, banner
 * patterns, a skull's owner and a spawner's mob and timings.
 *
 * <p>While the server runs, a regenerated block comes back from its full in-memory snapshot instead;
 * this is only for a server that stopped without restoring it (a crash). Anything not listed here
 * (a beehive's bees, a furnace's progress) is not kept across a crash.
 *
 * <p>The values are plain strings, numbers, lists and serialized maps, so the journal reads and
 * writes them without a server. {@link #capture} and {@link #apply} run on the server thread.
 */
public final class TileSnapshot {

    private final Map<String, Object> values;

    private TileSnapshot(Map<String, Object> values) {
        this.values = values;
    }

    public Map<String, Object> values() {
        return values;
    }

    /** Whether it holds items, which must never be both restored and dropped. */
    public boolean hasItems() {
        return values.get("items") instanceof String;
    }

    /** The parts of {@code state} worth keeping, or null if it has none. */
    @Nullable
    public static TileSnapshot capture(BlockState state, MailItemCodec codec) {
        Map<String, Object> values = new LinkedHashMap<>();
        Inventory inventory = snapshotInventory(state);
        if (inventory != null) {
            ItemStack[] contents = inventory.getContents();
            List<ItemStack> stacks = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || stack.getType().isAir() || stack.getAmount() < 1) continue;
                stacks.add(stack);
                slots.add(slot);
            }
            if (!stacks.isEmpty() && stacks.size() <= 54) {
                values.put("items", codec.encode(stacks));
                values.put("slots", slots);
            }
        }
        if (state instanceof org.bukkit.Nameable nameable && nameable.getCustomName() != null) {
            values.put("name", nameable.getCustomName());
        }
        if (state instanceof Sign sign) {
            values.put("front", side(sign.getSide(Side.FRONT)));
            values.put("back", side(sign.getSide(Side.BACK)));
            Object waxed = invoke(state, "isWaxed");
            if (Boolean.TRUE.equals(waxed)) values.put("waxed", true);
        }
        if (state instanceof Banner banner && !banner.getPatterns().isEmpty()) {
            List<Map<String, Object>> patterns = new ArrayList<>();
            for (Pattern pattern : banner.getPatterns()) patterns.add(pattern.serialize());
            values.put("patterns", patterns);
        }
        if (state instanceof Skull skull && skull.getOwnerProfile() != null) {
            values.put("profile", skull.getOwnerProfile());
        }
        if (state instanceof CreatureSpawner spawner) {
            Map<String, Object> mob = new LinkedHashMap<>();
            if (spawner.getSpawnedType() != null) mob.put("type", spawner.getSpawnedType().name());
            mob.put("delay", spawner.getDelay());
            mob.put("min-delay", spawner.getMinSpawnDelay());
            mob.put("max-delay", spawner.getMaxSpawnDelay());
            mob.put("count", spawner.getSpawnCount());
            mob.put("max-nearby", spawner.getMaxNearbyEntities());
            mob.put("player-range", spawner.getRequiredPlayerRange());
            mob.put("spawn-range", spawner.getSpawnRange());
            values.put("spawner", mob);
        }
        return values.isEmpty() ? null : new TileSnapshot(values);
    }

    /** Puts what it kept back on a freshly placed {@code state}; the caller then updates it. */
    public void apply(BlockState state, MailItemCodec codec, java.util.function.Consumer<String> warn) {
        Inventory inventory = snapshotInventory(state);
        if (inventory != null && hasItems()) {
            try {
                List<ItemStack> stacks = codec.decode((String) values.get("items"));
                List<?> slots = values.get("slots") instanceof List<?> list ? list : List.of();
                for (int i = 0; i < stacks.size(); i++) {
                    int slot = i < slots.size() && slots.get(i) instanceof Number n ? n.intValue() : i;
                    if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, stacks.get(i));
                }
            } catch (IOException | RuntimeException failed) {
                warn.accept("the contents of a regenerated " + state.getType().name().toLowerCase(Locale.ROOT)
                        + " could not be read: " + failed.getMessage());
            }
        }
        if (state instanceof org.bukkit.Nameable nameable && values.get("name") instanceof String name) {
            nameable.setCustomName(name);
        }
        if (state instanceof Sign sign) {
            apply(sign.getSide(Side.FRONT), values.get("front"));
            apply(sign.getSide(Side.BACK), values.get("back"));
            if (Boolean.TRUE.equals(values.get("waxed"))) invoke(state, "setWaxed", true);
        }
        if (state instanceof Banner banner && values.get("patterns") instanceof List<?> patterns) {
            List<Pattern> restored = new ArrayList<>();
            for (Object raw : patterns) {
                try {
                    if (raw instanceof Pattern pattern) restored.add(pattern);
                    else if (raw instanceof Map<?, ?> map) restored.add(new Pattern(stringKeys(map)));
                } catch (RuntimeException ignored) {
                    // A pattern this version no longer knows.
                }
            }
            banner.setPatterns(restored);
        }
        if (state instanceof Skull skull && values.get("profile") instanceof PlayerProfile profile) {
            skull.setOwnerProfile(profile);
        }
        if (state instanceof CreatureSpawner spawner && values.get("spawner") instanceof Map<?, ?> mob) {
            try {
                if (mob.get("type") instanceof String type) spawner.setSpawnedType(EntityType.valueOf(type));
                // Max before min, so neither is ever set past the other.
                spawner.setMaxSpawnDelay(number(mob.get("max-delay"), spawner.getMaxSpawnDelay()));
                spawner.setMinSpawnDelay(number(mob.get("min-delay"), spawner.getMinSpawnDelay()));
                spawner.setDelay(number(mob.get("delay"), spawner.getDelay()));
                spawner.setSpawnCount(number(mob.get("count"), spawner.getSpawnCount()));
                spawner.setMaxNearbyEntities(number(mob.get("max-nearby"), spawner.getMaxNearbyEntities()));
                spawner.setRequiredPlayerRange(number(mob.get("player-range"), spawner.getRequiredPlayerRange()));
                spawner.setSpawnRange(number(mob.get("spawn-range"), spawner.getSpawnRange()));
            } catch (RuntimeException failed) {
                warn.accept("a regenerated spawner's settings could not be restored: " + failed.getMessage());
            }
        }
    }

    /** The items it kept, for a block whose spot was taken while it waited. */
    public List<ItemStack> items(MailItemCodec codec) throws IOException {
        return hasItems() ? codec.decode((String) values.get("items")) : List.of();
    }

    public void write(ConfigurationSection section) {
        values.forEach(section::set);
    }

    @Nullable
    public static TileSnapshot read(@Nullable ConfigurationSection section) {
        if (section == null) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            values.put(key, value instanceof ConfigurationSection nested ? nested.getValues(false) : value);
        }
        return values.isEmpty() ? null : new TileSnapshot(values);
    }

    static TileSnapshot of(Map<String, Object> values) {
        return new TileSnapshot(new LinkedHashMap<>(values));
    }

    // -- Helpers ---------------------------------------------------------------------------------

    /**
     * The snapshot inventory of any block entity with one: containers, lecterns, jukeboxes,
     * chiseled bookshelves and (on newer servers) decorated pots, found by name so one build fits
     * every version.
     */
    @Nullable
    static Inventory snapshotInventory(BlockState state) {
        if (state instanceof org.bukkit.block.Container container) return container.getSnapshotInventory();
        Object inventory = invoke(state, "getSnapshotInventory");
        return inventory instanceof Inventory found ? found : null;
    }

    private static Map<String, Object> side(SignSide side) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lines", List.of(side.getLines()));
        if (side.getColor() != null) map.put("color", side.getColor().name());
        if (side.isGlowingText()) map.put("glowing", true);
        return map;
    }

    private static void apply(SignSide side, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return;
        if (map.get("lines") instanceof List<?> lines) {
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                side.setLine(i, lines.get(i) == null ? "" : String.valueOf(lines.get(i)));
            }
        }
        if (map.get("color") instanceof String color) {
            try {
                side.setColor(DyeColor.valueOf(color));
            } catch (IllegalArgumentException ignored) {
                // Unknown colour: keep the default.
            }
        }
        side.setGlowingText(Boolean.TRUE.equals(map.get("glowing")));
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static int number(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    @Nullable
    private static Object invoke(Object target, String name, Object... args) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    return method.invoke(target, args);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not on this version.
        }
        return null;
    }
}
