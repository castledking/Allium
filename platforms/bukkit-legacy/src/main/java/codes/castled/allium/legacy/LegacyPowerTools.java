package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powertools on legacy Bukkit: the tools saved in plugins/Allium/tools/*.yml, read with the modern
 * platform's schema, and {@code /powertool} to list, inspect, give, enable, disable and reload them.
 *
 * <p>Triggers: left, right, shift-left, shift-right, swap, drop, on-break, on-interact-entity,
 * on-hit, on-consume and on-fish. Actions: command, console, message, actionbar, sound, particle,
 * potion, grant, ride, mount and release (over {@link LegacyRides}), with the same placeholder,
 * console allow-list and selector rules. A binding's hand, sneak,
 * worlds, permission, cooldown and cancel-interaction apply, and so do the tool's use permission,
 * limited uses and click guard. {@code allium.powertool.bypass} or the tool's bypass permission
 * skips cooldowns and, unless {@code uses.bypassable} is false (as on vouchers), uses. The
 * handcuffs' item is an unbreakable fishing rod here. A tool whose item is another of Allium's own
 * items, or that uses an action this platform lacks (tree_fell, disarm, obliterate, block_replace)
 * or the ride action's claim option, is listed as needing Minecraft 1.20+.
 *
 * <p>Items before 1.14 carry no hidden data, so a tool item ends with a dark id line naming the
 * tool. Editing a tool in game ({@code add}, {@code set}, {@code cooldown} and the rest) needs
 * 1.20+; here a tool is edited in its file and reloaded.
 */
final class LegacyPowerTools implements Listener {
    static final String DIRECTORY = "tools";
    private static final String MARKER = "§8powertool:";
    private static final String USES = "§7Uses left: §f";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)\\}");
    private static final Pattern SELECTOR = Pattern.compile("(?<!\\S)@[aeprs](\\[[^\\]]*\\])?", Pattern.CASE_INSENSITIVE);
    static final Set<String> PLACEHOLDERS = new HashSet<>(Arrays.asList("player", "uuid", "world", "x", "y", "z", "yaw", "pitch",
            "hand", "item", "target", "target_uuid", "block_x", "block_y", "block_z", "block_type"));
    private static final Map<String, String> LEGACY_ENCHANTMENTS = new HashMap<>();

    static {
        String[][] pairs = {{"sharpness", "DAMAGE_ALL"}, {"smite", "DAMAGE_UNDEAD"}, {"bane_of_arthropods", "DAMAGE_ARTHROPODS"},
                {"efficiency", "DIG_SPEED"}, {"unbreaking", "DURABILITY"}, {"fortune", "LOOT_BONUS_BLOCKS"},
                {"looting", "LOOT_BONUS_MOBS"}, {"power", "ARROW_DAMAGE"}, {"punch", "ARROW_KNOCKBACK"}, {"flame", "ARROW_FIRE"},
                {"infinity", "ARROW_INFINITE"}, {"protection", "PROTECTION_ENVIRONMENTAL"}, {"fire_protection", "PROTECTION_FIRE"},
                {"blast_protection", "PROTECTION_EXPLOSIONS"}, {"projectile_protection", "PROTECTION_PROJECTILE"},
                {"feather_falling", "PROTECTION_FALL"}, {"respiration", "OXYGEN"}, {"aqua_affinity", "WATER_WORKER"},
                {"luck_of_the_sea", "LUCK"}, {"sweeping", "SWEEPING_EDGE"}};
        for (String[] pair : pairs) LEGACY_ENCHANTMENTS.put(pair[0], pair[1]);
    }

    private static final Set<String> SUPPORTED_ACTIONS = new HashSet<>(Arrays.asList(
            "command", "console", "message", "actionbar", "sound", "particle", "potion", "grant", "ride", "mount", "release"));
    /** Allium's own items that have a plain stand-in here: the handcuffs are an unbreakable fishing rod. */
    private static final Map<String, String> STAND_INS = new HashMap<>();

    static {
        STAND_INS.put("allium:handcuffs", "fishing_rod");
        STAND_INS.put("allium:claim_handcuffs", "fishing_rod");
    }

    /** The bundled tools that work here, installed once as on the modern platform. */
    static final List<String> BUNDLED = Collections.singletonList("handcuffs.yml");

    enum Trigger {
        LEFT("left", false, "l", "lmb", "left-click", "left_click"),
        RIGHT("right", true, "r", "rmb", "right-click", "right_click"),
        SHIFT_LEFT("shift-left", false, "sl", "shift_left", "shift-lmb", "shift_lmb"),
        SHIFT_RIGHT("shift-right", true, "sr", "shift_right", "shift-rmb", "shift_rmb"),
        SWAP("swap", false, "f", "swap-hands", "swap_hands"),
        DROP("drop", true, "q", "drop-item", "drop_item"),
        ON_BREAK("on-break", false, "on_break", "break", "block-break", "block_break"),
        ON_INTERACT_ENTITY("on-interact-entity", true, "on_interact_entity", "interact-entity", "interact_entity", "entity-interact",
                "right-entity", "right_entity"),
        ON_HIT("on-hit", false, "on_hit", "hit", "attack", "damage-entity", "damage_entity"),
        ON_CONSUME("on-consume", false, "on_consume", "consume", "eat"),
        ON_FISH("on-fish", false, "on_fish", "fish", "fishing", "rod");

        final String id;
        final boolean cancelByDefault;
        final List<String> aliases;

        Trigger(String id, boolean cancelByDefault, String... aliases) {
            this.id = id;
            this.cancelByDefault = cancelByDefault;
            this.aliases = Arrays.asList(aliases);
        }

        static Trigger parse(String raw) {
            String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            for (Trigger trigger : values()) if (trigger.id.equals(key) || trigger.aliases.contains(key)) return trigger;
            return null;
        }
    }

    static final class Step {
        final String type, value, requirePermission;
        final boolean console, required;
        final long delayTicks;
        final Map<String, Object> params;

        Step(String type, String value, boolean console, long delayTicks, String requirePermission, boolean required, Map<String, Object> params) {
            this.type = type;
            this.value = value;
            this.console = console;
            this.delayTicks = delayTicks;
            this.requirePermission = requirePermission;
            this.required = required;
            this.params = params;
        }
    }

    static final class Binding {
        final List<Step> steps = new ArrayList<>();
        boolean cancel;
        String hand = "main", sneak = "any", requirePermission, cooldownScope = "player", cooldownMessage;
        Set<String> worlds = Collections.emptySet();
        Set<String> fishing = Collections.emptySet();
        long cooldownMillis;
        boolean consumeUse = true;
    }

    static final class Tool {
        final String name;
        String display, usePermission, bypassPermission, unsupported, material, itemName;
        boolean enabled = true, unbreakable, consumeOnEmpty, bypassable = true, requireSingleItem;
        long clickGuardMillis = 100L;
        List<String> lore = Collections.emptyList();
        Map<String, Integer> enchantments = Collections.emptyMap();
        List<String> flags = Collections.emptyList();
        int uses;
        final Map<Trigger, Binding> bindings = new EnumMap<>(Trigger.class);

        Tool(String name) {
            this.name = name;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyMessageManager messages;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> clickGuard = new HashMap<>();
    private final Set<UUID> toggledOff = new HashSet<>();
    private final LegacyRides rides;

    LegacyPowerTools(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages) {
        this(plugin, config, messages, new LegacyRides(plugin, config, messages));
    }

    LegacyPowerTools(JavaPlugin plugin, LegacyConfigManager config, LegacyMessageManager messages, LegacyRides rides) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.rides = rides;
    }

    LegacyRides rides() {
        return rides;
    }

    // ---- loading ---------------------------------------------------------------------------------

    void load() {
        tools.clear();
        File folder = new File(plugin.getDataFolder(), DIRECTORY);
        installDefaults(folder);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_]{2,48}")) continue;
            try {
                Tool tool = parse(name, YamlConfiguration.loadConfiguration(file));
                if (tool != null) tools.put(name, tool);
            } catch (RuntimeException invalid) {
                plugin.getLogger().warning("tools/" + file.getName() + " was skipped: " + invalid.getMessage());
            }
        }
    }

    /**
     * Installs each bundled tool that works here once, recorded in {@code tools/.defaults} as the
     * modern platform does, so a deleted default stays deleted and the modern platform installs the
     * rest after an upgrade.
     */
    private void installDefaults(File folder) {
        File record = new File(folder, ".defaults");
        Set<String> installed = new LinkedHashSet<>();
        try {
            if (record.isFile()) {
                for (String line : java.nio.file.Files.readAllLines(record.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) installed.add(trimmed);
                }
            } else {
                installed.add("mob_disarmer.yml");
            }
            boolean changed = !record.isFile();
            for (String name : BUNDLED) {
                if (installed.contains(name)) continue;
                File target = new File(folder, name);
                if (!target.exists()) {
                    java.io.InputStream in = plugin.getResource(DIRECTORY + "/" + name);
                    if (in == null) continue;
                    try {
                        folder.mkdirs();
                        java.nio.file.Files.copy(in, target.toPath());
                    } finally {
                        in.close();
                    }
                    plugin.getLogger().info("Created " + DIRECTORY + "/" + name + ", a new default tool definition.");
                }
                installed.add(name);
                changed = true;
            }
            if (changed) {
                List<String> lines = new ArrayList<>();
                lines.add("# Default tool definitions Allium has installed once. A default listed here is not");
                lines.add("# installed again, so deleting its file removes the tool for good.");
                lines.addAll(installed);
                folder.mkdirs();
                java.nio.file.Files.write(record.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException failed) {
            plugin.getLogger().warning("Could not install the default tools: " + failed.getMessage());
        }
    }

    static Tool parse(String name, ConfigurationSection yaml) {
        String behavior = yaml.getString("behavior", "allium:powertool");
        if (!"allium:powertool".equals(behavior) && !"powertool".equals(behavior)) return null;
        Tool tool = new Tool(name);
        tool.display = yaml.getString("name", name);
        tool.enabled = yaml.getBoolean("enabled", true);
        tool.usePermission = yaml.getString("permissions.use", yaml.getString("use-permission", null));
        String bypass = yaml.getString("permissions.bypass", "");
        tool.bypassPermission = bypass.trim().isEmpty() ? "allium.tool." + name + ".bypass" : bypass.trim();
        tool.clickGuardMillis = Math.max(0L, Math.min(10_000L, yaml.getLong("timing.click-guard-millis", 100L)));
        ConfigurationSection item = yaml.getConfigurationSection("item");
        if (item != null) {
            tool.material = item.getString("material", "paper");
            tool.itemName = item.getString("name", null);
            tool.lore = item.getStringList("lore");
            tool.unbreakable = item.getBoolean("unbreakable", false);
            tool.flags = item.getStringList("hide-flags");
            ConfigurationSection enchants = item.getConfigurationSection("enchantments");
            if (enchants != null) {
                Map<String, Integer> levels = new LinkedHashMap<>();
                for (String key : enchants.getKeys(false)) levels.put(key, enchants.getInt(key, 1));
                tool.enchantments = levels;
            }
        } else {
            String appearance = yaml.getString("appearance.default", "");
            if (appearance.startsWith("minecraft:")) {
                tool.material = appearance.substring("minecraft:".length());
            } else if (STAND_INS.containsKey(appearance)) {
                tool.material = STAND_INS.get(appearance);
                tool.unbreakable = true;
            } else {
                tool.unsupported = "its item (" + appearance + ") is one of Allium's own items";
            }
        }
        ConfigurationSection uses = yaml.getConfigurationSection("uses");
        if (uses != null) {
            tool.uses = Math.max(0, uses.getInt("max", -1));
            tool.consumeOnEmpty = uses.getBoolean("consume-on-empty", false);
            tool.requireSingleItem = uses.getBoolean("require-single-item", tool.uses > 0 && !tool.consumeOnEmpty);
            tool.bypassable = uses.getBoolean("bypassable", true);
        }
        ConfigurationSection bindings = yaml.getConfigurationSection("bindings");
        if (bindings != null) {
            for (String key : bindings.getKeys(false)) {
                Trigger trigger = Trigger.parse(key);
                ConfigurationSection section = bindings.getConfigurationSection(key);
                if (trigger == null || section == null) continue;
                Binding binding = new Binding();
                binding.cancel = section.getBoolean("cancel-interaction", trigger.cancelByDefault);
                binding.hand = section.getString("hand", "main").toLowerCase(Locale.ROOT);
                binding.sneak = section.getString("sneak", "any").toLowerCase(Locale.ROOT);
                binding.requirePermission = section.getString("require-permission", null);
                binding.worlds = new HashSet<>(section.getStringList("worlds"));
                Set<String> states = new HashSet<>();
                for (String state : section.getStringList("fishing")) {
                    String known = fishState(state);
                    if (known != null) states.add(known);
                }
                binding.fishing = states;
                binding.cooldownMillis = LegacyCompat.parseDurationMillis(section.getString("cooldown", "0s"));
                if (binding.cooldownMillis < 0) binding.cooldownMillis = 0;
                binding.cooldownScope = section.getString("cooldown-scope", "player").toLowerCase(Locale.ROOT);
                binding.cooldownMessage = section.getString("cooldown-message", null);
                binding.consumeUse = section.getBoolean("consume-use", true);
                for (Map<?, ?> row : section.getMapList("actions")) {
                    String type = action(String.valueOf(row.get("type")));
                    if (!SUPPORTED_ACTIONS.contains(type)) {
                        tool.unsupported = "it uses the " + type + " action";
                        continue;
                    }
                    Object as = row.get("as");
                    boolean console = "console".equals(type) || "console".equalsIgnoreCase(String.valueOf(as));
                    long delay = row.get("delay") == null ? 0 : Math.max(0, LegacyCompat.parseDurationMillis(String.valueOf(row.get("delay"))) / 50L);
                    Object params = row.get("params");
                    Map<String, Object> map = new LinkedHashMap<>();
                    if (params instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) params).entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
                    if ("ride".equals(type) && truthy(map.get("claim"))) {
                        tool.unsupported = "its claim option needs GriefPrevention support";
                    }
                    binding.steps.add(new Step("console".equals(type) ? "command" : type, row.get("value") == null ? null : String.valueOf(row.get("value")),
                            console, delay, row.get("require-permission") == null ? null : String.valueOf(row.get("require-permission")),
                            Boolean.TRUE.equals(row.get("required")), map));
                }
                tool.bindings.put(trigger, binding);
            }
        }
        return tool;
    }

    /** The modern platform's fishing states, from their aliases. */
    static String fishState(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "cast": case "fishing": case "throw": return "cast";
            case "bite": return "bite";
            case "lured": case "lure": return "lured";
            case "caught-fish": case "catch": case "caught": return "caught-fish";
            case "caught-entity": case "hooked": case "hook": case "hook-entity": case "entity": return "caught-entity";
            case "in-ground": case "ground": case "block": return "in-ground";
            case "failed": case "failed-attempt": case "fail": case "miss": return "failed";
            case "reel-in": case "reel": case "retract": return "reel-in";
            default: return null;
        }
    }

    private static String fishState(PlayerFishEvent.State state) {
        switch (state.name()) {
            case "FISHING": return "cast";
            case "CAUGHT_FISH": return "caught-fish";
            case "CAUGHT_ENTITY": return "caught-entity";
            case "IN_GROUND": return "in-ground";
            case "FAILED_ATTEMPT": return "failed";
            default: return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean ? (Boolean) value : value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static String action(String raw) {
        String type = raw.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "cmd": case "player": case "player-command": return "command";
            case "console-command": return "console";
            case "msg": case "chat": return "message";
            case "action-bar": case "action_bar": return "actionbar";
            case "permission": case "grant_permission": case "allium:grant": return "grant";
            default: return type;
        }
    }

    // ---- items -----------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    ItemStack item(Tool tool, int amount) {
        Material material = LegacyCompat.matchMaterial(tool.material == null ? "paper" : tool.material);
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(LegacyCompat.color(tool.itemName != null ? tool.itemName : "&f" + tool.display));
        List<String> lore = new ArrayList<>();
        for (String line : tool.lore) lore.add(LegacyCompat.color(line));
        if (tool.uses > 0) lore.add(USES + tool.uses);
        lore.add(MARKER + tool.name);
        meta.setLore(lore);
        for (Map.Entry<String, Integer> e : tool.enchantments.entrySet()) {
            Enchantment enchantment = enchantment(e.getKey());
            if (enchantment != null) meta.addEnchant(enchantment, e.getValue(), true);
        }
        for (String flag : tool.flags) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // A flag from a newer version.
            }
        }
        if (tool.unbreakable) meta.setUnbreakable(true);
        stack.setItemMeta(meta);
        return stack;
    }

    @SuppressWarnings("deprecation")
    private static Enchantment enchantment(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String legacy = LEGACY_ENCHANTMENTS.get(key);
        if (legacy != null && Enchantment.getByName(legacy) != null) return Enchantment.getByName(legacy);
        Enchantment byName = Enchantment.getByName(key.toUpperCase(Locale.ROOT));
        if (byName != null) return byName;
        try {
            Object namespaced = Class.forName("org.bukkit.NamespacedKey").getMethod("minecraft", String.class).invoke(null, key);
            return (Enchantment) Enchantment.class.getMethod("getByKey", namespaced.getClass()).invoke(null, namespaced);
        } catch (ReflectiveOperationException | RuntimeException old) {
            return null;
        }
    }

    /** The tool a stack is, or null. */
    static String toolOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || !stack.getItemMeta().hasLore()) return null;
        List<String> lore = stack.getItemMeta().getLore();
        String last = lore.get(lore.size() - 1);
        return last.startsWith(MARKER) ? last.substring(MARKER.length()) : null;
    }

    // ---- triggers --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        boolean sneaking = event.getPlayer().isSneaking();
        Trigger trigger = left ? (sneaking ? Trigger.SHIFT_LEFT : Trigger.LEFT) : (sneaking ? Trigger.SHIFT_RIGHT : Trigger.RIGHT);
        fire(event.getPlayer(), event.getItem(), offHand(event), trigger, null, event.getClickedBlock() == null ? null : event.getClickedBlock().getLocation(), event);
    }

    private static boolean offHand(PlayerInteractEvent event) {
        try {
            return event.getHand() == EquipmentSlot.OFF_HAND;
        } catch (NoSuchMethodError old) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        fire(event.getPlayer(), event.getOffHandItem(), false, Trigger.SWAP, null, null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        fire(event.getPlayer(), event.getItemDrop().getItemStack(), false, Trigger.DROP, null, null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        fire(event.getPlayer(), event.getPlayer().getItemInHand(), false, Trigger.ON_BREAK, null, event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        boolean off;
        try {
            off = event.getHand() == EquipmentSlot.OFF_HAND;
        } catch (NoSuchMethodError old) {
            off = false;
        }
        ItemStack item = off ? event.getPlayer().getInventory().getItemInOffHand() : event.getPlayer().getInventory().getItemInMainHand();
        fire(event.getPlayer(), item, off, Trigger.ON_INTERACT_ENTITY, event.getRightClicked(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        fire(player, player.getInventory().getItemInMainHand(), false, Trigger.ON_HIT, event.getEntity(), null, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        boolean mainRod = main != null && main.getType() == Material.FISHING_ROD;
        ItemStack item = mainRod ? main : player.getInventory().getItemInOffHand();
        if (item == null || item.getType() == Material.AIR) return;
        String name = toolOf(item);
        Tool tool = name == null ? null : tools.get(name);
        Binding binding = tool == null ? null : tool.bindings.get(Trigger.ON_FISH);
        if (binding == null || !binding.fishing.isEmpty() && !binding.fishing.contains(fishState(event.getState()))) return;
        fire(player, item, !mainRod, Trigger.ON_FISH, event.getCaught(), null, event);
        // A cancelled reel-in leaves the line out; take it in, as reeling in would have.
        if (event.isCancelled() && event.getHook() != null) event.getHook().remove();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        fire(event.getPlayer(), event.getItem(), false, Trigger.ON_CONSUME, null, null, event);
    }

    /** Runs a tool's binding for {@code trigger}; true when it ran. */
    boolean fire(Player player, ItemStack stack, boolean offHand, Trigger trigger, Entity target, Location block, Cancellable event) {
        String name = toolOf(stack);
        Tool tool = name == null ? null : tools.get(name);
        if (tool == null) return false;
        Binding binding = tool.bindings.get(trigger);
        if (binding == null) return false;
        if (!tool.enabled || tool.unsupported != null || toggledOff.contains(player.getUniqueId())) return false;
        if (!binding.hand.equals("both") && binding.hand.equals("off") != offHand) return false;
        if (binding.sneak.equals("require") && !player.isSneaking() || binding.sneak.equals("disallow") && player.isSneaking()) return false;
        if (!binding.worlds.isEmpty() && !binding.worlds.contains(player.getWorld().getName())) return false;
        if (binding.cancel && event != null) event.setCancelled(true);
        if (!player.hasPermission("allium.powertool.use")
                || tool.usePermission != null && !tool.usePermission.isEmpty() && !player.hasPermission(tool.usePermission)
                || binding.requirePermission != null && !player.hasPermission(binding.requirePermission)) {
            messages.send(player, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return false;
        }
        long now = System.currentTimeMillis();
        String guardKey = player.getUniqueId() + "|" + trigger.id;
        Long last = clickGuard.get(guardKey);
        if (last != null && now - last < tool.clickGuardMillis) return false;
        clickGuard.put(guardKey, now);
        boolean bypass = player.hasPermission("allium.powertool.bypass") || player.hasPermission(tool.bypassPermission);
        boolean spendUse = tool.uses > 0 && binding.consumeUse && !(bypass && tool.bypassable);
        if (tool.uses > 0 && tool.requireSingleItem && stack.getAmount() > 1) {
            messages.send(player, "tool.powertool.single-item", "{prefix} &cHold a single item, not a stack.");
            return false;
        }
        if (spendUse && usesLeft(tool, stack) <= 0) {
            messages.send(player, "tool.powertool.no-uses", "{prefix} &cThis powertool has no uses remaining.");
            return false;
        }
        String cooldownKey = (binding.cooldownScope.equals("global") ? "*" : player.getUniqueId().toString()) + "|" + tool.name + "|" + trigger.id;
        Long until = cooldowns.get(cooldownKey);
        if (!bypass && binding.cooldownMillis > 0 && until != null && until > now) {
            String wait = LegacyCompat.formatDuration(until - now);
            if (binding.cooldownMessage != null) player.sendMessage(LegacyCompat.color(binding.cooldownMessage.replace("{time}", wait)));
            else messages.send(player, "tool.powertool.cooldown", "&cRecharging: &f{time}", "{time}", wait);
            return false;
        }
        if (!bypass && binding.cooldownMillis > 0) cooldowns.put(cooldownKey, now + binding.cooldownMillis);
        Map<String, String> values = placeholders(player, stack, offHand, target, block);
        boolean ran = false;
        for (Step step : binding.steps) {
            if (step.requirePermission != null && !player.hasPermission(step.requirePermission)) {
                if (step.required) break;
                continue;
            }
            if (step.delayTicks > 0) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) run(tool, step, player, target, values, null);
                }, step.delayTicks);
                ran = true;
            } else {
                boolean done = run(tool, step, player, target, values, event);
                ran |= done;
                if (!done && step.required) break;
            }
        }
        if (ran && spendUse) useUp(tool, player, stack, offHand);
        return ran;
    }

    /** The uses left on a stack: its "Uses left" line, or the tool's maximum on an item without one. */
    static int usesLeft(Tool tool, ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasLore()) {
            for (String line : stack.getItemMeta().getLore()) {
                if (!line.startsWith(USES)) continue;
                try {
                    return Integer.parseInt(line.substring(USES.length()).trim());
                } catch (NumberFormatException ignored) {
                    return tool.uses;
                }
            }
        }
        return tool.uses;
    }

    private void useUp(Tool tool, Player player, ItemStack stack, boolean offHand) {
        ItemMeta meta = stack.getItemMeta();
        int left = Math.max(0, usesLeft(tool, stack) - 1);
        List<String> lore = new ArrayList<>(meta.getLore());
        boolean found = false;
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).startsWith(USES)) {
                lore.set(i, USES + left);
                found = true;
            }
        }
        if (!found) lore.add(Math.max(0, lore.size() - 1), USES + left);
        if (left > 0 || !tool.consumeOnEmpty) {
            meta.setLore(lore);
            stack.setItemMeta(meta);
            return;
        }
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
            meta.setLore(withUses(lore, tool.uses));
            stack.setItemMeta(meta);
        } else if (offHand) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private static List<String> withUses(List<String> lore, int uses) {
        List<String> out = new ArrayList<>();
        for (String line : lore) out.add(line.startsWith(USES) ? USES + uses : line);
        return out;
    }

    private static Map<String, String> placeholders(Player player, ItemStack stack, boolean offHand, Entity target, Location block) {
        Map<String, String> values = new HashMap<>();
        Location at = player.getLocation();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("world", player.getWorld().getName());
        values.put("x", Integer.toString(at.getBlockX()));
        values.put("y", Integer.toString(at.getBlockY()));
        values.put("z", Integer.toString(at.getBlockZ()));
        values.put("yaw", String.format(Locale.ROOT, "%.1f", at.getYaw()));
        values.put("pitch", String.format(Locale.ROOT, "%.1f", at.getPitch()));
        values.put("hand", offHand ? "off" : "main");
        values.put("item", stack.getType().name().toLowerCase(Locale.ROOT));
        if (target != null) {
            values.put("target", target instanceof Player ? ((Player) target).getName() : target.getType().name().toLowerCase(Locale.ROOT));
            values.put("target_uuid", target.getUniqueId().toString());
        }
        if (block != null) {
            values.put("block_x", Integer.toString(block.getBlockX()));
            values.put("block_y", Integer.toString(block.getBlockY()));
            values.put("block_z", Integer.toString(block.getBlockZ()));
            values.put("block_type", block.getBlock().getType().name().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    // ---- actions ---------------------------------------------------------------------------------

    private boolean run(Tool tool, Step step, Player player, Entity target, Map<String, String> values, Cancellable cause) {
        switch (step.type) {
            case "ride": return ride(player, target, step.params);
            case "mount": return mount(player, target, step.params);
            case "release": return release(player, step.params, cause);
            default: break;
        }
        if (step.value == null || step.value.trim().isEmpty()) return false;
        String text = apply(step.value, values);
        switch (step.type) {
            case "command": {
                String unknown = firstUnknownPlaceholder(step.value);
                if (unknown != null) {
                    plugin.getLogger().warning("PowerTool '" + tool.name + "' command has disallowed placeholder {" + unknown + "}");
                    return false;
                }
                String command = cleanCommand(text);
                if (command.isEmpty()) return false;
                if (!step.console) return player.performCommand(command);
                ConfigurationSection settings = config.raw().getConfigurationSection("powertools");
                List<String> allowlist = settings == null ? Collections.<String>emptyList() : settings.getStringList("console-allowlist");
                if (!consoleAllowed(command, allowlist)) {
                    plugin.getLogger().warning("PowerTool '" + tool.name + "' blocked console command not in powertools.console-allowlist: " + command);
                    return false;
                }
                if ((settings == null || settings.getBoolean("block-selectors", true)) && SELECTOR.matcher(command).find()) {
                    plugin.getLogger().warning("PowerTool '" + tool.name + "' blocked console command containing selector: " + command);
                    return false;
                }
                return plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
            }
            case "message":
                for (String line : LegacyCompat.color(text).split("\n", -1)) player.sendMessage(line);
                return true;
            case "actionbar":
                LegacyCompat.sendActionBar(player, LegacyCompat.color(text));
                return true;
            case "sound": {
                String[] parts = text.trim().split("\\s+");
                float volume = parts.length >= 2 ? parseFloat(parts[1], 1f) : 1f;
                float pitch = parts.length >= 3 ? parseFloat(parts[2], 1f) : 1f;
                Location at = target != null ? target.getLocation() : player.getLocation();
                String id = parts[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (plugin.getServer().getBukkitVersion().startsWith("1.12") && id.startsWith("block.note_block.")) {
                    id = "block.note." + id.substring("block.note_block.".length());
                }
                try {
                    at.getWorld().playSound(at, id, Math.max(0f, Math.min(10f, volume)), Math.max(0.5f, Math.min(2f, pitch)));
                } catch (RuntimeException unknown) {
                    return false;
                }
                return true;
            }
            case "particle": {
                String[] parts = text.trim().split("\\s+");
                Particle particle;
                try {
                    particle = Particle.valueOf(parts[0].toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
                } catch (IllegalArgumentException unknown) {
                    return false;
                }
                int count = parts.length >= 2 ? Math.max(1, Math.min(200, (int) parseFloat(parts[1], 10))) : 10;
                double spread = parts.length >= 3 ? Math.max(0, Math.min(5, parseFloat(parts[2], 0.35f))) : 0.35;
                Location at = (target != null ? target.getLocation() : player.getLocation()).add(0, target != null ? 0.8 : 1.0, 0);
                at.getWorld().spawnParticle(particle, at, count, spread, spread, spread, 0.02);
                return true;
            }
            case "potion":
                return potion(player, target, text);
            case "grant":
                return grant(player, text, step.params);
            default:
                return false;
        }
    }

    // ---- ride, mount, release ---------------------------------------------------------------------

    private boolean ride(Player player, Entity target, Map<String, Object> params) {
        if (!eligible(player, target, params)) return false;
        boolean restrain = truthy(params.get("restrain"));
        String name = nameOf(target);
        if (restrain && !(target instanceof Player)) {
            messages.send(player, "tool.ride.players-only", "{prefix} &cOnly players can be restrained.");
            return false;
        }
        int max = 5;
        try {
            if (params.get("max-riders") != null) max = Integer.parseInt(String.valueOf(params.get("max-riders")).trim());
        } catch (NumberFormatException ignored) {
        }
        max = Math.max(1, Math.min(16, max));
        switch (rides.carry(player, target, restrain, max)) {
            case RESTRAINED: {
                messages.send(player, "tool.ride.restrained", "{prefix} &aYou restrained &f{target}&a.", "{target}", name);
                messages.send(target, "tool.ride.restrained-target", "{prefix} &cYou have been restrained by &f{player}&c!", "{player}", player.getName());
                ((Player) target).sendTitle(messages.resolveColored("tool.ride.restrained-title", "&c&lRESTRAINED"),
                        messages.resolveColored("tool.ride.restrained-subtitle", "&7Restrained by &f{player}", "{player}", player.getName()), 5, 45, 10);
                return true;
            }
            case CARRIED:
                messages.send(player, "tool.ride.carrying", "{prefix} &aYou picked up &f{target}&a.", "{target}", name);
                if (target instanceof Player) {
                    messages.send(target, "tool.ride.carried", "{prefix} &f{player} &apicked you up. Sneak to get down.", "{player}", player.getName());
                }
                return true;
            case FULL:
                messages.send(player, "tool.ride.full", "{prefix} &cYou can only carry &f{max} &cat once.", "{max}", Integer.toString(max));
                return false;
            case BUSY:
                messages.send(player, "tool.ride.busy", "{prefix} &f{target} &cis already riding or carrying someone.", "{target}", name);
                return false;
            default:
                return false;
        }
    }

    private boolean mount(Player player, Entity target, Map<String, Object> params) {
        if (!eligible(player, target, params)) return false;
        if (rides.isRestrained(player.getUniqueId()) || rides.isRestrained(target.getUniqueId())
                || player.getVehicle() != null && target.getUniqueId().equals(player.getVehicle().getUniqueId())) {
            messages.send(player, "tool.ride.busy", "{prefix} &f{target} &cis already riding or carrying someone.", "{target}", nameOf(target));
            return false;
        }
        if (rides.isCarried(player.getUniqueId())) rides.let(player.getUniqueId());
        player.leaveVehicle();
        return target.addPassenger(player);
    }

    private boolean release(Player player, Map<String, Object> params, Cancellable cause) {
        Object which = params.get("which");
        boolean all = which == null || !"last".equalsIgnoreCase(String.valueOf(which).trim());
        List<Entity> released = rides.release(player, all);
        if (released.isEmpty()) return false;
        for (Entity entity : released) {
            messages.send(player, "tool.ride.released", "{prefix} &aYou released &f{target}&a.", "{target}", nameOf(entity));
            if (entity instanceof Player && ((Player) entity).isOnline()) {
                messages.send(entity, "tool.ride.released-target", "{prefix} &aYou were released by &f{player}&a.", "{player}", player.getName());
            }
        }
        // The handcuffs' drop lets go; the handcuffs stay in hand.
        if (cause instanceof PlayerDropItemEvent) cause.setCancelled(true);
        return true;
    }

    /** A living target, of an allowed type, not exempt and visible. */
    private boolean eligible(Player player, Entity target, Map<String, Object> params) {
        if (target == null || !target.isValid() || !(target instanceof LivingEntity) || target.getUniqueId().equals(player.getUniqueId())) {
            return false;
        }
        Object rawTypes = params.get("entity-types");
        List<String> types = new ArrayList<>();
        if (rawTypes instanceof Collection) {
            for (Object type : (Collection<?>) rawTypes) if (type != null) types.add(String.valueOf(type).trim().toLowerCase(Locale.ROOT));
        } else if (rawTypes != null) {
            for (String part : String.valueOf(rawTypes).split("[,\\s]+")) if (!part.trim().isEmpty()) types.add(part.trim().toLowerCase(Locale.ROOT));
        }
        String type = target.getType().name().toLowerCase(Locale.ROOT);
        if (!types.isEmpty() && !types.contains(type) && !types.contains("minecraft:" + type)) return false;
        if (target instanceof Player) {
            Player other = (Player) target;
            if (!player.canSee(other)) return false;
            Object exempt = params.get("exempt-permission");
            if (exempt != null && !String.valueOf(exempt).trim().isEmpty() && other.hasPermission(String.valueOf(exempt).trim())) {
                messages.send(player, "tool.ride.exempt", "{prefix} &f{target} &ccannot be restrained or picked up.", "{target}", other.getName());
                return false;
            }
        }
        return true;
    }

    private static String nameOf(Entity entity) {
        if (entity instanceof Player) return ((Player) entity).getName();
        String custom = entity.getCustomName();
        return custom != null && !custom.isEmpty() ? custom : entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @SuppressWarnings("deprecation")
    private static boolean potion(Player player, Entity target, String text) {
        String[] parts = text.trim().split("\\s+");
        String raw = parts[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String canonical;
        switch (raw) {
            case "jump_boost": canonical = "jump"; break;
            case "instant_health": case "heal": canonical = "heal"; break;
            case "instant_damage": case "harm": canonical = "harm"; break;
            case "regen": canonical = "regeneration"; break;
            case "slowness": case "slow": canonical = "slow"; break;
            case "haste": canonical = "fast_digging"; break;
            case "mining_fatigue": canonical = "slow_digging"; break;
            case "strength": canonical = "increase_damage"; break;
            case "resistance": canonical = "damage_resistance"; break;
            case "nausea": canonical = "confusion"; break;
            default: canonical = raw;
        }
        PotionEffectType type = PotionEffectType.getByName(canonical.toUpperCase(Locale.ROOT));
        if (type == null) type = PotionEffectType.getByName(raw.toUpperCase(Locale.ROOT));
        if (type == null) return false;
        long millis = parts.length >= 2 ? LegacyCompat.parseDurationMillis(parts[1]) : 5000L;
        if (millis <= 0) millis = 5000L;
        int ticks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis / 50L));
        int amplifier = parts.length >= 3 ? Math.max(0, Math.min(255, (int) parseFloat(parts[2], 1) - 1)) : 0;
        LivingEntity recipient = parts.length >= 4 && "target".equalsIgnoreCase(parts[3]) && target instanceof LivingEntity ? (LivingEntity) target : player;
        recipient.addPotionEffect(new PotionEffect(type, ticks, amplifier), true);
        return true;
    }

    /** {@code 10:node 1:node} by weight, or every missing node with {@code pick: all}; through LuckPerms or Vault. */
    private boolean grant(Player player, String value, Map<String, Object> params) {
        List<String[]> choices = new ArrayList<>();
        long total = 0;
        for (String token : value.trim().split("[\\s,]+")) {
            int weight = 1;
            String node = token;
            int colon = token.indexOf(':');
            if (colon >= 0) {
                try {
                    weight = Integer.parseInt(token.substring(0, colon).trim());
                } catch (NumberFormatException invalid) {
                    continue;
                }
                node = token.substring(colon + 1);
            }
            node = node.trim().toLowerCase(Locale.ROOT);
            if (weight < 1 || !node.matches("[a-z0-9][a-z0-9_.*-]{0,127}") || player.hasPermission(node)) continue;
            choices.add(new String[]{Integer.toString(weight), node});
            total += weight;
        }
        if (choices.isEmpty()) return false;
        List<String> give = new ArrayList<>();
        if ("all".equalsIgnoreCase(String.valueOf(params.get("pick")))) {
            for (String[] choice : choices) give.add(choice[1]);
        } else {
            long roll = ThreadLocalRandom.current().nextLong(total);
            for (String[] choice : choices) {
                roll -= Integer.parseInt(choice[0]);
                if (roll < 0) {
                    give.add(choice[1]);
                    break;
                }
            }
        }
        boolean any = false;
        for (String node : give) any |= givePermission(player, node);
        return any;
    }

    private boolean givePermission(Player player, String node) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            return plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    "lp user " + player.getUniqueId() + " permission set " + node + " true");
        }
        try {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) return false;
            Class<?> type = Class.forName("net.milkbowl.vault.permission.Permission");
            Object vault = plugin.getServer().getServicesManager().load(type);
            return vault != null && Boolean.TRUE.equals(type.getMethod("playerAdd", String.class, org.bukkit.OfflinePlayer.class, String.class)
                    .invoke(vault, null, player, node));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    // ---- the modern platform's command rules -----------------------------------------------------

    static String cleanCommand(String raw) {
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1).trim();
        return trimmed;
    }

    static String firstUnknownPlaceholder(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) if (!PLACEHOLDERS.contains(matcher.group(1))) return matcher.group(1);
        return null;
    }

    static String apply(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        return result;
    }

    /** An empty allow-list allows any command; else {@code *}, a namespace ({@code allium:}) or a label. */
    static boolean consoleAllowed(String command, Collection<String> allowlist) {
        String cleaned = cleanCommand(command);
        if (cleaned.isEmpty()) return false;
        if (allowlist.isEmpty()) return true;
        int space = cleaned.indexOf(' ');
        String head = (space >= 0 ? cleaned.substring(0, space) : cleaned).toLowerCase(Locale.ROOT);
        for (String raw : allowlist) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String entry = cleanCommand(raw).toLowerCase(Locale.ROOT);
            if ("*".equals(entry) || entry.endsWith(":") && head.startsWith(entry) || head.equals(entry)) return true;
        }
        return false;
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    // ---- /powertool and /voucher -----------------------------------------------------------------

    Map<String, Tool> tools() {
        return Collections.unmodifiableMap(tools);
    }

    boolean powertool(CommandSender sender, String label, String[] args) {
        String bare = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (bare.equals("ptt") || bare.equals("powertooltoggle") || bare.equals("pttoggle")) return toggle(sender);
        if (bare.equals("ptlist") || bare.equals("powertoollist")) return list(sender, args.length > 0 ? args[0] : "", false);
        if (!sender.hasPermission("allium.powertool")) return refuse(sender);
        if (args.length == 0) return false;
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                return list(sender, args.length > 1 ? args[1] : "", false);
            case "info":
                return info(sender, args.length > 1 ? args[1] : null);
            case "toggle":
                return toggle(sender);
            case "get":
            case "give":
                return give(sender, sub.equals("get"), Arrays.copyOfRange(args, 1, args.length), "/powertool", false);
            case "enable":
            case "disable":
                return setEnabled(sender, args, sub.equals("enable"));
            case "reload":
                if (!sender.hasPermission("allium.powertool.reload")) return refuse(sender);
                load();
                messages.send(sender, "powertool.reloaded", "{prefix} &aReloaded &f{count} &apowertool(s).", "{count}", Integer.toString(tools.size()));
                return true;
            case "add": case "set": case "remove": case "clear": case "cooldown": case "perm": case "uses": case "cancel":
            case "sneak": case "hand": case "fish": case "save": case "delete": case "bind":
                messages.send(sender, "powertool.edit-modern-only",
                        "{prefix} &eEditing tools in game needs Minecraft 1.20+. Edit &fplugins/Allium/tools/<name>.yml &eand run &f/powertool reload&e.");
                return true;
            default:
                return false;
        }
    }

    boolean voucher(CommandSender sender, String label, String[] args) {
        String bare = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        String sub = bare.equals("vouchers") ? "list" : args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                if (!sender.hasPermission("allium.voucher.give")) return refuse(sender);
                return list(sender, "", true);
            case "give":
                if (!sender.hasPermission("allium.voucher.give")) return refuse(sender);
                return give(sender, false, Arrays.copyOfRange(args, 1, args.length), "/voucher", true);
            case "reload":
                if (!sender.hasPermission("allium.voucher.reload")) return refuse(sender);
                load();
                long count = tools.keySet().stream().filter(name -> name.startsWith("voucher_")).count();
                messages.send(sender, "voucher.reloaded", "{prefix} &aReloaded the powertools; &f{count} &avoucher(s) loaded.",
                        "{count}", Long.toString(count));
                return true;
            default:
                return false;
        }
    }

    private boolean list(CommandSender sender, String filter, boolean vouchers) {
        List<String> rows = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (vouchers != tool.name.startsWith("voucher_") && vouchers) continue;
            String shown = vouchers ? tool.name.substring("voucher_".length()) : tool.name;
            if (!filter.isEmpty() && !shown.contains(filter.toLowerCase(Locale.ROOT))) continue;
            String state = tool.unsupported != null ? "&8needs 1.20+" : tool.enabled ? "&aon" : "&coff";
            rows.add("&f" + shown + " &7(" + state + "&7)");
        }
        if (rows.isEmpty()) {
            if (vouchers) {
                messages.send(sender, "voucher.none", "{prefix} &7No vouchers yet: save a powertool as &fvoucher_<name>&7.");
            } else {
                messages.send(sender, "powertool.none", "{prefix} &7No powertools in plugins/Allium/tools.");
            }
            return true;
        }
        sender.sendMessage(LegacyCompat.color((vouchers ? "&6Vouchers: " : "&6Powertools: ") + String.join("&7, ", rows)));
        return true;
    }

    private boolean info(CommandSender sender, String name) {
        Tool tool;
        if (name == null) {
            if (!(sender instanceof Player)) return false;
            String held = toolOf(((Player) sender).getInventory().getItemInMainHand());
            tool = held == null ? null : tools.get(held);
        } else {
            tool = tools.get(name.toLowerCase(Locale.ROOT));
        }
        if (tool == null) {
            messages.send(sender, "powertool.unknown", "{prefix} &cNo powertool &f{tool}&c.", "{tool}", name == null ? "in your hand" : name);
            return true;
        }
        sender.sendMessage(LegacyCompat.color("&6Powertool &f" + tool.name + " &7(" + tool.display + "&7): "
                + (tool.unsupported != null ? "&8needs 1.20+, as " + tool.unsupported : tool.enabled ? "&aon" : "&coff")));
        if (tool.usePermission != null) sender.sendMessage(LegacyCompat.color("&7Permission: &f" + tool.usePermission));
        if (tool.uses > 0) sender.sendMessage(LegacyCompat.color("&7Uses: &f" + tool.uses + (tool.consumeOnEmpty ? " &7(used up when empty)" : "")));
        for (Map.Entry<Trigger, Binding> entry : tool.bindings.entrySet()) {
            Binding b = entry.getValue();
            List<String> steps = new ArrayList<>();
            for (Step step : b.steps) steps.add((step.console ? "console" : step.type) + (step.value == null ? "" : " " + step.value));
            sender.sendMessage(LegacyCompat.color("&e" + entry.getKey().id + "&7: &f" + String.join("&7; &f", steps)
                    + (b.cooldownMillis > 0 ? " &7(cooldown " + LegacyCompat.formatDuration(b.cooldownMillis) + ")" : "")));
        }
        return true;
    }

    private boolean give(CommandSender sender, boolean self, String[] args, String command, boolean voucher) {
        if (!voucher && !sender.hasPermission("allium.powertool.give")) return refuse(sender);
        Player target;
        int index;
        if (self) {
            if (!(sender instanceof Player) || args.length < 1) {
                LegacyUsageCards.invalid(sender, command + " get <item> [amount]");
                return true;
            }
            target = (Player) sender;
            index = 0;
        } else {
            if (args.length < 2) {
                LegacyUsageCards.invalid(sender, command + " give <player> <" + (voucher ? "voucher" : "item") + "> [amount]");
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || sender instanceof Player && !((Player) sender).canSee(target)) {
                messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", args[0]);
                return true;
            }
            index = 1;
        }
        String name = args[index].toLowerCase(Locale.ROOT);
        Tool tool = tools.get(voucher && !name.startsWith("voucher_") ? "voucher_" + name : name);
        if (tool == null) {
            messages.send(sender, "powertool.unknown", "{prefix} &cNo powertool &f{tool}&c.", "{tool}", args[index]);
            return true;
        }
        if (tool.unsupported != null) {
            messages.send(sender, "powertool.modern-only", "{prefix} &e{tool} needs Minecraft 1.20+, as {reason}.",
                    "{tool}", tool.name, "{reason}", tool.unsupported);
            return true;
        }
        int amount = 1;
        if (args.length > index + 1) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[index + 1])));
            } catch (NumberFormatException invalid) {
                messages.send(sender, "economy.invalid-amount", "{prefix} &cEnter a whole number from 1 to 64.");
                return true;
            }
        }
        for (ItemStack left : target.getInventory().addItem(item(tool, amount)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        messages.send(sender, "powertool.given", "{prefix} &aGave &f{amount}x {tool} &ato &f{player}&a.",
                "{amount}", Integer.toString(amount), "{tool}", tool.name, "{player}", target.getName());
        return true;
    }

    private boolean setEnabled(CommandSender sender, String[] args, boolean on) {
        if (!sender.hasPermission("allium.powertool.save") && !sender.hasPermission("allium.powertool.toggle.global")) return refuse(sender);
        if (args.length < 2) return false;
        Tool tool = tools.get(args[1].toLowerCase(Locale.ROOT));
        if (tool == null) {
            messages.send(sender, "powertool.unknown", "{prefix} &cNo powertool &f{tool}&c.", "{tool}", args[1]);
            return true;
        }
        File file = new File(new File(plugin.getDataFolder(), DIRECTORY), tool.name + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("enabled", on);
        try {
            yaml.save(file);
        } catch (IOException failed) {
            messages.send(sender, "economy.storage-unavailable", "{prefix} &cCould not save the tool.");
            return true;
        }
        tool.enabled = on;
        messages.send(sender, on ? "powertool.enabled" : "powertool.disabled", on ? "{prefix} &aTurned &f{tool} &aon." : "{prefix} &eTurned &f{tool} &eoff.",
                "{tool}", tool.name);
        return true;
    }

    private boolean toggle(CommandSender sender) {
        if (!(sender instanceof Player)) return false;
        if (!sender.hasPermission("allium.powertool.toggle")) return refuse(sender);
        UUID id = ((Player) sender).getUniqueId();
        boolean off = toggledOff.add(id);
        if (!off) toggledOff.remove(id);
        messages.send(sender, off ? "powertool.toggle-off" : "powertool.toggle-on",
                off ? "{prefix} &ePowertools are off for you." : "{prefix} &aPowertools are on for you.");
        return true;
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }

    List<String> complete(CommandSender sender, String command, String[] args, String prefix) {
        List<String> out = new ArrayList<>();
        if (command.equals("voucher")) {
            if (args.length == 1) for (String s : Arrays.asList("give", "list", "reload")) if (s.startsWith(prefix)) out.add(s);
            else if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
                for (String name : tools.keySet()) if (name.startsWith("voucher_") && name.substring(8).startsWith(prefix)) out.add(name.substring(8));
            }
            if (args.length == 2 && "give".equalsIgnoreCase(args[0])) out.addAll(players(prefix));
            return out;
        }
        if (args.length == 1) {
            for (String s : Arrays.asList("list", "info", "get", "give", "enable", "disable", "toggle", "reload")) if (s.startsWith(prefix)) out.add(s);
        } else if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            out.addAll(players(prefix));
        } else if (args.length == 2 || args.length == 3 && "give".equalsIgnoreCase(args[0])) {
            for (String name : tools.keySet()) if (name.startsWith(prefix)) out.add(name);
        }
        return out;
    }

    private List<String> players(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(online.getName());
        return out;
    }

    Set<String> loadedNames() {
        return new LinkedHashSet<>(tools.keySet());
    }
}
