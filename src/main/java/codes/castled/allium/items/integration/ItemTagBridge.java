package codes.castled.allium.items.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

/**
 * Single gateway between Allium and ItemTag.
 * <p>
 * ItemTag keeps every one of its mechanics on the ItemStack itself — the Spigot
 * PersistentDataContainer under {@code itemtag:<key>}, or raw NBT when it is configured with
 * {@code data.preference: NBTAPI}. Going through ItemTag's own {@code getTagItem(ItemStack)} method
 * rather than touching the container directly is what keeps Allium correct under both backends.
 * <p>
 * ItemTag and ItemEdit are optional runtime plugins and are not published as Maven artifacts. This
 * bridge therefore resolves their API reflectively through ItemTag's plugin class loader. No
 * ItemTag type appears in a public signature, and every entry point returns a null/empty/no-op
 * result when {@link #isAvailable()} is false.
 */
public final class ItemTagBridge {

    private static final String PLUGIN_NAME = "ItemTag";

    /** Tri-state cache: null until first probe. Cleared by {@link #invalidate()}. */
    private static volatile Boolean available;
    private static volatile String keyPrefix;
    private static volatile ReflectiveApi api;

    private ItemTagBridge() {
    }

    /**
     * Whether ItemTag is installed, enabled, and its classes are loadable.
     * <p>
     * Deliberately touches no ItemTag type until after the plugin-manager check, so a missing
     * plugin produces a clean false rather than a NoClassDefFoundError.
     */
    public static synchronized boolean isAvailable() {
        if (available != null) {
            return available;
        }
        try {
            Plugin itemTag = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (itemTag == null || !itemTag.isEnabled()) {
                available = false;
                return false;
            }
            // Resolve through ItemTag's own loader. A soft dependency makes its classes available
            // to Bukkit, but Class.forName from Allium's loader is not portable across every plugin
            // class-loader implementation.
            api = ReflectiveApi.load(itemTag.getClass().getClassLoader());
            keyPrefix = itemTag.getName().toLowerCase(java.util.Locale.ENGLISH);
            available = true;
            Text.sendDebugLog(INFO, "ItemTag integration active (key prefix: " + keyPrefix + ")");
        } catch (Throwable t) {
            available = false;
            Text.sendDebugLog(WARN, "ItemTag is present but its API could not be reached; "
                    + "custom item ItemTag data will be skipped: " + t.getMessage());
        }
        return available;
    }

    /** Drops the cached availability probe. Call on plugin reload. */
    public static synchronized void invalidate() {
        available = null;
        keyPrefix = null;
        api = null;
    }

    /**
     * Builds a fully qualified ItemTag key, e.g. {@code itemtag:placeable}.
     * Only valid once {@link #isAvailable()} has returned true.
     */
    private static String key(String sub) {
        return keyPrefix + ":" + sub;
    }

    /** Null when the item cannot carry tags. Mutations through the result apply to {@code item} in place. */
    private static Object tag(ItemStack item) {
        if (!isAvailable() || item == null || item.getType() == Material.AIR) {
            return null;
        }
        return callStatic("itemTag", "getTagItem", item);
    }

    // ======================================================================
    // FLAGS
    // ======================================================================

    /**
     * ItemTag's custom flags.
     * <p>
     * Mirrors the {@code CustomFlag} instances registered in ItemTag's {@code Flag} subcommand,
     * which exposes no public accessor for them. {@link #id()} matches the flag's ItemTag command id
     * (and so the key used in Allium's item yml); {@link #subKey()} is the tag it writes, and the two
     * differ for {@code recipeingredient} and {@code entityfood}. Keep this list in sync with
     * {@code emanondev.itemtag.command.itemtag.Flag}'s constructor.
     * <p>
     * Some flags are only registered by ItemTag on newer server versions ({@code grindable} on 1.14+,
     * {@code smithing_table} on 1.16.5+). Reading or writing their tags on an older server is
     * harmless — ItemTag simply never acts on them.
     */
    public enum Flag {
        PLACEABLE("placeable", "placeable", true),
        USABLE("usable", "usable", true),
        RECIPE_INGREDIENT("recipeingredient", "craft_ingredient", true),
        SMELT("smelt", "smelt", true),
        FURNACE_FUEL("furnacefuel", "furnacefuel", true),
        ENCHANTABLE("enchantable", "enchantable", true),
        ENTITY_FOOD("entityfood", "entity_food", true),
        RENAMABLE("renamable", "renamable", true),
        GRINDABLE("grindable", "grindable", true),
        EQUIPMENT("equipment", "equipment", true),
        CLICK_MOVE("clickmove", "clickmove", true),
        TRADEABLE("tradeable", "tradeable", true),
        SMITHING_TABLE("smithing_table", "smithing_table", true),
        VANISH_CURSE("vanishcurse", "vanishcurse", false);

        private final String id;
        private final String subKey;
        private final boolean defaultValue;

        Flag(String id, String subKey, boolean defaultValue) {
            this.id = id;
            this.subKey = subKey;
            this.defaultValue = defaultValue;
        }

        public String id() {
            return id;
        }

        public String subKey() {
            return subKey;
        }

        public boolean defaultValue() {
            return defaultValue;
        }

        /** Resolves a flag by its yml/command id, or null. */
        public static Flag byId(String id) {
            if (id == null) {
                return null;
            }
            String needle = id.toLowerCase(java.util.Locale.ENGLISH).replace('-', '_');
            for (Flag flag : values()) {
                if (flag.id.equals(needle)) {
                    return flag;
                }
            }
            return null;
        }
    }

    /** The flag's explicit value, or null when the item leaves it at ItemTag's default. */
    public static Boolean getFlag(ItemStack item, Flag flag) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asBoolean(call(tagItem, "getBoolean", key(flag.subKey())));
    }

    public static boolean getFlagOrDefault(ItemStack item, Flag flag) {
        Boolean value = getFlag(item, flag);
        return value == null ? flag.defaultValue() : value;
    }

    /**
     * Sets a flag, or clears it when {@code value} is null.
     * <p>
     * Writing the flag's default removes the tag instead of storing it, matching
     * {@code CustomFlag.setValue}. Without this a stored item would accumulate a dozen redundant
     * tags that ItemTag itself would never have written.
     */
    public static void setFlag(ItemStack item, Flag flag, Boolean value) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        if (value == null || value == flag.defaultValue()) {
            call(tagItem, "removeTag", key(flag.subKey()));
        } else {
            call(tagItem, "setTag", key(flag.subKey()), value.booleanValue());
        }
    }

    /** Every flag the item sets away from its default. Empty when ItemTag is absent. */
    public static Map<Flag, Boolean> getNonDefaultFlags(ItemStack item) {
        Map<Flag, Boolean> values = new EnumMap<>(Flag.class);
        Object tagItem = tag(item);
        if (tagItem == null) {
            return values;
        }
        for (Flag flag : Flag.values()) {
            Boolean value = asBoolean(call(tagItem, "getBoolean", key(flag.subKey())));
            if (value != null && value != flag.defaultValue()) {
                values.put(flag, value);
            }
        }
        return values;
    }

    /** Clears every flag, then applies the given ones. Allium owns this domain wholesale. */
    public static void setFlags(ItemStack item, Map<Flag, Boolean> values) {
        if (tag(item) == null) {
            return;
        }
        for (Flag flag : Flag.values()) {
            setFlag(item, flag, values == null ? null : values.get(flag));
        }
    }

    // ======================================================================
    // EFFECTS
    // ======================================================================

    /**
     * Potion effects ItemTag applies while the item is equipped.
     * <p>
     * ItemTag stores type/amplifier/ambient/particles/icon only and recomputes duration as infinite
     * on load, so the duration on the returned effects carries no information worth persisting.
     */
    public static Collection<PotionEffect> getEffects(ItemStack item) {
        if (tag(item) == null) {
            return Collections.emptyList();
        }
        Object info = construct("effectsInfo", item);
        Object effects = info == null ? null : call(info, "getEffects");
        if (!(effects instanceof Collection<?> collection)) {
            return Collections.emptyList();
        }
        List<PotionEffect> result = new ArrayList<>();
        for (Object effect : collection) {
            if (effect instanceof PotionEffect potionEffect) {
                result.add(potionEffect);
            }
        }
        return result;
    }

    /** Slots the effects apply in. Empty when ItemTag is absent; otherwise never empty. */
    public static Set<EquipmentSlot> getEffectSlots(ItemStack item) {
        if (tag(item) == null) {
            return EnumSet.noneOf(EquipmentSlot.class);
        }
        Object info = construct("effectsInfo", item);
        Object slots = info == null ? null : call(info, "getValidSlots");
        EnumSet<EquipmentSlot> result = EnumSet.noneOf(EquipmentSlot.class);
        if (slots instanceof Collection<?> collection) {
            for (Object slot : collection) {
                if (slot instanceof EquipmentSlot equipmentSlot) {
                    result.add(equipmentSlot);
                }
            }
        }
        return result;
    }

    /**
     * Replaces the item's effects and their slots.
     * <p>
     * An empty or null {@code effects} clears them. A null or full {@code slots} lets ItemTag fall
     * back to every player equipment slot, which is how it encodes "unrestricted".
     */
    public static void setEffects(ItemStack item, Collection<PotionEffect> effects, Set<EquipmentSlot> slots) {
        if (tag(item) == null) {
            return;
        }
        Object info = construct("effectsInfo", item);
        if (info == null) {
            return;
        }
        Object effectsMap = call(info, "getEffectsMap");
        if (effectsMap instanceof Map<?, ?> map) {
            for (Object type : new ArrayList<>(map.keySet())) {
                if (type instanceof PotionEffectType potionType) {
                    call(info, "removeEffect", potionType);
                }
            }
        }
        if (effects != null) {
            for (PotionEffect effect : effects) {
                call(info, "addEffect", effect);
            }
        }
        if (slots != null && !slots.isEmpty()) {
            for (EquipmentSlot slot : EnumSet.allOf(EquipmentSlot.class)) {
                boolean wanted = slots.contains(slot);
                if (asBoolean(call(info, "isValidSlot", slot), false) != wanted) {
                    call(info, "toggleSlot", slot);
                }
            }
        }
        call(info, "update");
    }

    /**
     * Builds an effect in the shape ItemTag stores: infinite duration (or the largest value the
     * running server supports), with the flags it round-trips.
     */
    public static PotionEffect craftEffect(PotionEffectType type, int amplifier, boolean ambient,
                                           boolean particles, boolean icon) {
        Object effect = callStatic(
                "effectsInfo", "craftPotionEffect", type, amplifier, ambient, particles, icon);
        return effect instanceof PotionEffect potionEffect ? potionEffect : null;
    }

    // ======================================================================
    // ACTIONS
    // ======================================================================

    /**
     * Raw action lines, each {@code <type>%%:%%<argument>}.
     * <p>
     * {@code servercommand} and {@code commandasop} lines carry a {@code -pin<hash>} control key that
     * ItemTag validates before running them. Copying lines verbatim between items preserves it;
     * a hand-authored line without one will be refused at use time.
     */
    public static List<String> getActions(ItemStack item) {
        Object tagItem = tag(item);
        if (tagItem == null || !asBoolean(callStatic("actionsUtility", "hasActions", tagItem), false)) {
            return Collections.emptyList();
        }
        return stringList(callStatic("actionsUtility", "getActions", tagItem));
    }

    public static void setActions(ItemStack item, List<String> actions) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        callStatic("actionsUtility", "setActions", tagItem,
                actions == null ? Collections.emptyList() : actions);
    }

    public static int getUses(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? 0 : asInt(callStatic("actionsUtility", "getUses", tagItem), 0);
    }

    public static void setUses(ItemStack item, int uses) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setUses", tagItem, uses);
        }
    }

    public static int getMaxUses(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? 0 : asInt(callStatic("actionsUtility", "getMaxUses", tagItem), 0);
    }

    public static void setMaxUses(ItemStack item, int maxUses) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setMaxUses", tagItem, maxUses);
        }
    }

    public static boolean getDisplayUses(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem != null
                && asBoolean(callStatic("actionsUtility", "getDisplayUses", tagItem), false);
    }

    public static void setDisplayUses(ItemStack item, boolean value) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setDisplayUses", tagItem, value);
        }
    }

    /** Whether the item is consumed once its uses run out. */
    public static boolean getConsumeAtEnd(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem != null
                && asBoolean(callStatic("actionsUtility", "getConsume", tagItem), false);
    }

    public static void setConsumeAtEnd(ItemStack item, boolean value) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setConsume", tagItem, value);
        }
    }

    public static int getCooldownMs(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? 0
                : asInt(callStatic("actionsUtility", "getCooldownMs", tagItem), 0);
    }

    public static void setCooldownMs(ItemStack item, int millis) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setCooldownMs", tagItem, millis);
        }
    }

    public static String getCooldownId(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null
                : asString(callStatic("actionsUtility", "getCooldownId", tagItem));
    }

    public static void setCooldownId(ItemStack item, String id) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setCooldownId", tagItem, id);
        }
    }

    /** The cooldown id ItemTag assumes when an item sets none. */
    public static String getDefaultCooldownId() {
        return isAvailable()
                ? asString(callStatic("actionsUtility", "getDefaultCooldownId"))
                : null;
    }

    public static boolean getVisualCooldown(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem != null
                && asBoolean(callStatic("actionsUtility", "getVisualCooldown", tagItem), false);
    }

    public static void setVisualCooldown(ItemStack item, boolean value) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setVisualCooldown", tagItem, value);
        }
    }

    public static String getCooldownMessage(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null
                : asString(callStatic("actionsUtility", "getCooldownMsg", tagItem));
    }

    public static void setCooldownMessage(ItemStack item, String message) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setCooldownMsg", tagItem, message);
        }
    }

    public static String getCooldownMessageType(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null
                : asString(callStatic("actionsUtility", "getCooldownMsgType", tagItem));
    }

    public static void setCooldownMessageType(ItemStack item, String type) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setCooldownMsgType", tagItem, type);
        }
    }

    public static String getActionPermission(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null
                : asString(callStatic("actionsUtility", "getPermission", tagItem));
    }

    public static void setActionPermission(ItemStack item, String permission) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            callStatic("actionsUtility", "setPermission", tagItem, permission);
        }
    }

    /** Rewrites the "uses left" line ItemTag maintains in the lore. */
    public static void updateUsesDisplay(ItemStack item) {
        if (tag(item) != null) {
            callStatic("actionsUtility", "updateUsesDisplay", item);
        }
    }

    // ======================================================================
    // CONSUME ACTIONS
    // ======================================================================
    // ItemTag's ConsumeActions holds its keys privately and exposes no static API, so this domain
    // goes through raw keys. Its list separator matches TagItem's own (%%;%%).

    public static List<String> getConsumeActions(ItemStack item) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return Collections.emptyList();
        }
        return stringList(call(tagItem, "getStringList", key("consume_actions")));
    }

    public static void setConsumeActions(ItemStack item, List<String> actions) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        if (actions == null || actions.isEmpty()) {
            call(tagItem, "removeTag", key("consume_actions"));
        } else {
            call(tagItem, "setTag", key("consume_actions"), actions);
        }
    }

    public static Integer getConsumeCooldownMs(ItemStack item) {
        Object tagItem = tag(item);
        Object value = tagItem == null ? null : call(tagItem, "getInteger", key("consume_cooldown"));
        return value instanceof Number number ? number.intValue() : null;
    }

    public static void setConsumeCooldownMs(ItemStack item, Integer millis) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        if (millis == null || millis <= 0) {
            call(tagItem, "removeTag", key("consume_cooldown"));
        } else {
            call(tagItem, "setTag", key("consume_cooldown"), millis.intValue());
        }
    }

    public static String getConsumeCooldownId(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("consume_cooldown_id")));
    }

    public static void setConsumeCooldownId(ItemStack item, String id) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            setNullableStringTag(tagItem, "consume_cooldown_id", id);
        }
    }

    public static String getConsumePermission(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("consume_permission")));
    }

    public static void setConsumePermission(ItemStack item, String permission) {
        Object tagItem = tag(item);
        if (tagItem != null) {
            setNullableStringTag(tagItem, "consume_permission", permission);
        }
    }

    // ======================================================================
    // USE / WEAR PERMISSIONS
    // ======================================================================
    // Writes go through ItemTag's public setters, which already treat empty as "clear".
    // It exposes no matching getters, so reads use the raw keys.

    public static String getUsePermission(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("useperm")));
    }

    public static String getUsePermissionMessage(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("usepermmsg")));
    }

    public static void setUsePermission(ItemStack item, String permission, String message) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        callStatic("usePermission", "setUseKey", tagItem, permission);
        callStatic("usePermission", "setUseMsgKey", tagItem, message);
    }

    public static String getWearPermission(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("wearperm")));
    }

    public static String getWearPermissionMessage(ItemStack item) {
        Object tagItem = tag(item);
        return tagItem == null ? null : asString(call(tagItem, "getString", key("wearpermmsg")));
    }

    public static void setWearPermission(ItemStack item, String permission, String message) {
        Object tagItem = tag(item);
        if (tagItem == null) {
            return;
        }
        callStatic("wearPermission", "setUseKey", tagItem, permission);
        callStatic("wearPermission", "setUseMsgKey", tagItem, message);
    }

    // ======================================================================

    /** True when the item carries any ItemTag data at all — useful for deciding whether to emit an itemtag block. */
    public static boolean hasAnyData(ItemStack item) {
        if (tag(item) == null) {
            return false;
        }
        return !getNonDefaultFlags(item).isEmpty()
                || !getEffects(item).isEmpty()
                || !getActions(item).isEmpty()
                || !getConsumeActions(item).isEmpty()
                || getUsePermission(item) != null
                || getWearPermission(item) != null;
    }

    private static void setNullableStringTag(Object tagItem, String subKey, String value) {
        if (value == null || value.isEmpty()) {
            call(tagItem, "removeTag", key(subKey));
        } else {
            call(tagItem, "setTag", key(subKey), value);
        }
    }

    private static Object call(Object target, String method, Object... arguments) {
        ReflectiveApi current = currentApi();
        if (current == null || target == null) {
            return null;
        }
        try {
            return current.invoke(target, method, arguments);
        } catch (Throwable t) {
            disableAfterFailure(t);
            return null;
        }
    }

    private static Object callStatic(String owner, String method, Object... arguments) {
        ReflectiveApi current = currentApi();
        if (current == null) {
            return null;
        }
        try {
            return current.invokeStatic(owner, method, arguments);
        } catch (Throwable t) {
            disableAfterFailure(t);
            return null;
        }
    }

    private static Object construct(String owner, Object... arguments) {
        ReflectiveApi current = currentApi();
        if (current == null) {
            return null;
        }
        try {
            return current.construct(owner, arguments);
        } catch (Throwable t) {
            disableAfterFailure(t);
            return null;
        }
    }

    private static ReflectiveApi currentApi() {
        return isAvailable() ? api : null;
    }

    private static synchronized void disableAfterFailure(Throwable failure) {
        if (Boolean.FALSE.equals(available)) {
            return;
        }
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null
                ? invocation.getCause()
                : failure;
        available = false;
        api = null;
        Text.sendDebugLog(WARN, "ItemTag API call failed; integration disabled until reload: " + cause);
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        Boolean bool = asBoolean(value);
        return bool == null ? fallback : bool;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(collection.size());
        for (Object entry : collection) {
            if (entry instanceof String string) {
                result.add(string);
            }
        }
        return result;
    }

    /** Minimal reflection layer over the optional ItemTag API. */
    private static final class ReflectiveApi {

        private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
                boolean.class, Boolean.class,
                byte.class, Byte.class,
                short.class, Short.class,
                int.class, Integer.class,
                long.class, Long.class,
                float.class, Float.class,
                double.class, Double.class,
                char.class, Character.class
        );

        private final Map<String, Class<?>> classes;

        private ReflectiveApi(Map<String, Class<?>> classes) {
            this.classes = classes;
        }

        static ReflectiveApi load(ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
            Map<String, Class<?>> classes = new HashMap<>();
            classes.put("itemTag", Class.forName("emanondev.itemtag.ItemTag", false, loader));
            classes.put("tagItem", Class.forName("emanondev.itemtag.TagItem", false, loader));
            classes.put("effectsInfo", Class.forName("emanondev.itemtag.EffectsInfo", false, loader));
            classes.put("actionsUtility",
                    Class.forName("emanondev.itemtag.actions.ActionsUtility", false, loader));
            classes.put("usePermission",
                    Class.forName("emanondev.itemtag.command.itemtag.UsePermission", false, loader));
            classes.put("wearPermission",
                    Class.forName("emanondev.itemtag.command.itemtag.WearPermission", false, loader));

            // Validate the entry point up front. This distinguishes an enabled plugin with a
            // compatible API from an unrelated or future plugin that merely has the same name.
            classes.get("itemTag").getMethod("getTagItem", ItemStack.class);
            return new ReflectiveApi(Map.copyOf(classes));
        }

        Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
            Class<?> tagItem = classes.get("tagItem");
            Class<?> owner = tagItem.isInstance(target) ? tagItem : target.getClass();
            Method method = findMethod(owner, name, false, arguments);
            return method.invoke(target, arguments);
        }

        Object invokeStatic(String owner, String name, Object... arguments)
                throws ReflectiveOperationException {
            Method method = findMethod(requireClass(owner), name, true, arguments);
            return method.invoke(null, arguments);
        }

        Object construct(String owner, Object... arguments) throws ReflectiveOperationException {
            Class<?> type = requireClass(owner);
            Constructor<?> best = null;
            int bestScore = -1;
            for (Constructor<?> constructor : type.getConstructors()) {
                int score = compatibilityScore(constructor.getParameterTypes(), arguments);
                if (score > bestScore) {
                    best = constructor;
                    bestScore = score;
                }
            }
            if (best == null) {
                throw new NoSuchMethodException("No compatible constructor on " + type.getName());
            }
            return best.newInstance(arguments);
        }

        private Class<?> requireClass(String key) throws ClassNotFoundException {
            Class<?> type = classes.get(key);
            if (type == null) {
                throw new ClassNotFoundException("Unknown ItemTag API class key: " + key);
            }
            return type;
        }

        private static Method findMethod(
                Class<?> owner,
                String name,
                boolean requireStatic,
                Object[] arguments
        ) throws NoSuchMethodException {
            Method best = null;
            int bestScore = -1;
            for (Method method : owner.getMethods()) {
                if (!method.getName().equals(name)
                        || Modifier.isStatic(method.getModifiers()) != requireStatic) {
                    continue;
                }
                int score = compatibilityScore(method.getParameterTypes(), arguments);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            if (best == null) {
                throw new NoSuchMethodException(
                        "No compatible " + owner.getName() + "." + name + " method");
            }
            return best;
        }

        private static int compatibilityScore(Class<?>[] parameters, Object[] arguments) {
            if (parameters.length != arguments.length) {
                return -1;
            }
            int score = 0;
            for (int i = 0; i < parameters.length; i++) {
                Object argument = arguments[i];
                Class<?> parameter = wrap(parameters[i]);
                if (argument == null) {
                    if (parameters[i].isPrimitive()) {
                        return -1;
                    }
                    score += 1;
                } else if (parameter.equals(argument.getClass())) {
                    score += 4;
                } else if (parameter.isInstance(argument)) {
                    score += 2;
                } else {
                    return -1;
                }
            }
            return score;
        }

        private static Class<?> wrap(Class<?> type) {
            return type.isPrimitive() ? PRIMITIVE_WRAPPERS.get(type) : type;
        }
    }
}
