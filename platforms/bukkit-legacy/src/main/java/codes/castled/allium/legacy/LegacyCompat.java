package codes.castled.allium.legacy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 8 / Spigot 1.12.2-1.19.4 version-compatibility helpers.
 */
public final class LegacyCompat {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([smhdwSMHDW])");
    private static final Method PLAYER_GET_PING = findMethod(Player.class, "getPing");
    private static final Method BUNGEE_COLOR_OF = findBungeeColorOf();
    private static final Method HIDE_PLAYER_PLUGIN = findMethod(Player.class, "hidePlayer", Plugin.class, Player.class);
    private static final Method SHOW_PLAYER_PLUGIN = findMethod(Player.class, "showPlayer", Plugin.class, Player.class);
    private static final Method MATERIAL_MATCH_LEGACY = findMethod(Material.class, "matchMaterial", String.class, boolean.class);
    private static final Class<?> DAMAGEABLE_CLASS = findClass("org.bukkit.inventory.meta.Damageable");
    private static final Method DAMAGEABLE_HAS_DAMAGE = DAMAGEABLE_CLASS == null ? null : findMethod(DAMAGEABLE_CLASS, "hasDamage");
    private static final Method DAMAGEABLE_SET_DAMAGE = DAMAGEABLE_CLASS == null ? null : findMethod(DAMAGEABLE_CLASS, "setDamage", int.class);
    private static final Method SKULL_SET_OWNING_PLAYER = findMethod(SkullMeta.class, "setOwningPlayer", OfflinePlayer.class);

    private static final Map<String, String> ENCHANT_ALIASES = buildEnchantAliases();

    private LegacyCompat() {}

    /** Returns the player's ping in ms, using {@code Player.getPing()} on 1.17+ and NMS handle on 1.12-1.16. */
    public static int ping(Player player) {
        if (PLAYER_GET_PING != null) {
            try {
                Object val = PLAYER_GET_PING.invoke(player);
                if (val instanceof Number) {
                    return Math.max(0, ((Number) val).intValue());
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return Math.max(0, handle.getClass().getField("ping").getInt(handle));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Returns the player's max health safely across 1.12.2-1.19.4. */
    @SuppressWarnings("deprecation")
    public static double maxHealth(Player player) {
        try {
            AttributeInstance inst = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (inst != null && inst.getValue() > 0.0) {
                return inst.getValue();
            }
        } catch (Throwable ignored) {
        }
        return Math.max(1.0, player.getMaxHealth());
    }

    /** Hides {@code target} from {@code viewer} safely across 1.12.2-1.19.4. */
    @SuppressWarnings("deprecation")
    public static void hidePlayer(JavaPlugin plugin, Player viewer, Player target) {
        if (viewer == null || target == null) return;
        if (HIDE_PLAYER_PLUGIN != null && plugin != null) {
            try {
                HIDE_PLAYER_PLUGIN.invoke(viewer, plugin, target);
                return;
            } catch (Throwable ignored) {
            }
        }
        viewer.hidePlayer(target);
    }

    /** Shows {@code target} to {@code viewer} safely across 1.12.2-1.19.4. */
    @SuppressWarnings("deprecation")
    public static void showPlayer(JavaPlugin plugin, Player viewer, Player target) {
        if (viewer == null || target == null) return;
        if (SHOW_PLAYER_PLUGIN != null && plugin != null) {
            try {
                SHOW_PLAYER_PLUGIN.invoke(viewer, plugin, target);
                return;
            } catch (Throwable ignored) {
            }
        }
        viewer.showPlayer(target);
    }

    /** Repairs {@code item} safely on both 1.12.2 (durability) and 1.13+ (Damageable meta). Returns true if repaired. */
    @SuppressWarnings("deprecation")
    public static boolean repairItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.getType().getMaxDurability() <= 0) return false;
        ItemMeta meta = item.getItemMeta();
        if (DAMAGEABLE_CLASS != null && DAMAGEABLE_HAS_DAMAGE != null && DAMAGEABLE_SET_DAMAGE != null
                && meta != null && DAMAGEABLE_CLASS.isInstance(meta)) {
            try {
                boolean damaged = Boolean.TRUE.equals(DAMAGEABLE_HAS_DAMAGE.invoke(meta));
                if (!damaged) return false;
                DAMAGEABLE_SET_DAMAGE.invoke(meta, 0);
                item.setItemMeta(meta);
                return true;
            } catch (Throwable ignored) {
            }
        }
        if (item.getDurability() <= 0) return false;
        item.setDurability((short) 0);
        return true;
    }

    /** Matches a material name cleanly across 1.12.2 legacy names and 1.13+ flattened names. */
    public static Material matchMaterial(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String clean = raw.trim().toUpperCase(Locale.ROOT);
        if (clean.startsWith("MINECRAFT:")) {
            clean = clean.substring("MINECRAFT:".length());
        }
        clean = clean.replace('-', '_').replace(' ', '_');
        Material m = Material.matchMaterial(clean);
        if (m != null) return m;
        if (MATERIAL_MATCH_LEGACY != null) {
            try {
                Object legacy = MATERIAL_MATCH_LEGACY.invoke(null, clean, Boolean.TRUE);
                if (legacy instanceof Material) return (Material) legacy;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Creates a player head ItemStack across 1.12.2 (SKULL_ITEM:3) and 1.13+ (PLAYER_HEAD). */
    @SuppressWarnings("deprecation")
    public static ItemStack createPlayerHead(String ownerName, OfflinePlayer offline) {
        Material headMat = Material.matchMaterial("PLAYER_HEAD");
        ItemStack stack;
        if (headMat != null) {
            stack = new ItemStack(headMat, 1);
        } else {
            Material legacySkull = Material.matchMaterial("SKULL_ITEM");
            stack = new ItemStack(legacySkull != null ? legacySkull : Material.STONE, 1, (short) 3);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta) {
            SkullMeta skull = (SkullMeta) meta;
            boolean applied = false;
            if (SKULL_SET_OWNING_PLAYER != null && offline != null) {
                try {
                    SKULL_SET_OWNING_PLAYER.invoke(skull, offline);
                    applied = true;
                } catch (Throwable ignored) {
                }
            }
            if (!applied && ownerName != null && !ownerName.isEmpty()) {
                skull.setOwner(ownerName);
            }
            if (ownerName != null && !ownerName.isEmpty()) {
                skull.setDisplayName(ChatColor.YELLOW + ownerName + "'s Head");
            }
            stack.setItemMeta(skull);
        }
        return stack;
    }

    /** Resolves an enchantment by Bukkit name, 1.13+ namespaced key, or friendly alias (e.g. sharpness, unbreaking). */
    @SuppressWarnings("deprecation")
    public static Enchantment matchEnchantment(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        clean = clean.replace('-', '_').replace(' ', '_');
        String aliased = ENCHANT_ALIASES.get(clean);
        if (aliased != null) {
            Enchantment e = Enchantment.getByName(aliased);
            if (e != null) return e;
        }
        Enchantment direct = Enchantment.getByName(clean.toUpperCase(Locale.ROOT));
        if (direct != null) return direct;
        // 1.13+ NamespacedKey lookup via reflection
        try {
            Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
            Method minecraft = keyClass.getMethod("minecraft", String.class);
            Object key = minecraft.invoke(null, clean);
            Method getByKey = Enchantment.class.getMethod("getByKey", keyClass);
            Object found = getByKey.invoke(null, key);
            if (found instanceof Enchantment) return (Enchantment) found;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Opens a virtual workstation inventory (ANVIL, GRINDSTONE, CARTOGRAPHY, STONECUTTER, LOOM, SMITHING).
     * Returns {@code true} if opened, or {@code false} if not supported by this Minecraft version.
     */
    public static boolean openWorkstation(Player player, String inventoryTypeName, String paperMethodName) {
        if (player == null) return false;
        if (paperMethodName != null) {
            try {
                Method m = player.getClass().getMethod(paperMethodName, Location.class, boolean.class);
                Object view = m.invoke(player, null, Boolean.TRUE);
                if (view != null) return true;
            } catch (Throwable ignored) {
            }
        }
        try {
            InventoryType type = InventoryType.valueOf(inventoryTypeName);
            Inventory inv = Bukkit.createInventory(player, type);
            player.openInventory(inv);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Resets {@code TIME_SINCE_REST} on 1.13+ servers; returns {@code false} on 1.12.2. */
    public static boolean resetRestStatistic(Player player) {
        try {
            Statistic stat = Statistic.valueOf("TIME_SINCE_REST");
            player.setStatistic(stat, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reads recent server TPS (1m, 5m, 15m) via Paper API or Spigot NMS, or {@code null} if unavailable. */
    public static double[] recentTps(Server server) {
        if (server == null) return null;
        try {
            Method getTps = server.getClass().getMethod("getTPS");
            Object result = getTps.invoke(server);
            if (result instanceof double[]) return (double[]) result;
        } catch (Throwable ignored) {
        }
        try {
            Method getServer = server.getClass().getMethod("getServer");
            Object mcServer = getServer.invoke(server);
            Object tps = mcServer.getClass().getField("recentTps").get(mcServer);
            if (tps instanceof double[]) return (double[]) tps;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Parses durations like {@code 30s}, {@code 10m}, {@code 2h}, {@code 7d}, {@code 1w}, {@code 1d12h} into millis, or -1 if invalid. */
    public static long parseDurationMillis(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1L;
        String trimmed = raw.trim();
        Matcher m = DURATION_PART.matcher(trimmed);
        long total = 0L;
        int matchedChars = 0;
        while (m.find()) {
            if (m.start() != matchedChars) {
                return -1L;
            }
            matchedChars = m.end();
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1L;
            }
            if (value < 0L) return -1L;
            char unit = Character.toLowerCase(m.group(2).charAt(0));
            long mult;
            switch (unit) {
                case 's': mult = 1_000L; break;
                case 'm': mult = 60_000L; break;
                case 'h': mult = 3_600_000L; break;
                case 'd': mult = 86_400_000L; break;
                case 'w': mult = 604_800_000L; break;
                default: return -1L;
            }
            total += value * mult;
        }
        return matchedChars == trimmed.length() && total > 0L ? total : -1L;
    }

    /** Formats a millisecond duration into a concise human-readable string like {@code 2d 3h 15m 4s}. */
    public static String formatDuration(long millis) {
        if (millis <= 0L) return "0s";
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    /** Parses plain numbers or k/m/b/t shorthand into a non-negative 2-decimal BigDecimal, or null if invalid. */
    public static BigDecimal parseMoneyAmount(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("$")) s = s.substring(1);
        BigDecimal mult = BigDecimal.ONE;
        if (s.endsWith("k")) {
            mult = new BigDecimal("1000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = new BigDecimal("1000000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            mult = new BigDecimal("1000000000");
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("t")) {
            mult = new BigDecimal("1000000000000");
            s = s.substring(0, s.length() - 1);
        }
        try {
            BigDecimal base = new BigDecimal(s).multiply(mult).setScale(2, RoundingMode.HALF_UP);
            return base.compareTo(BigDecimal.ZERO) < 0 ? null : base;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Formats a BigDecimal money value as {@code $1,234.56}. */
    public static String formatMoney(BigDecimal amount) {
        BigDecimal safe = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.US, "$%,.2f", safe);
    }

    public static void writeLocation(ConfigurationSection section, Location loc) {
        if (section == null || loc == null || loc.getWorld() == null) return;
        section.set("world", loc.getWorld().getName());
        section.set("x", loc.getX());
        section.set("y", loc.getY());
        section.set("z", loc.getZ());
        section.set("yaw", (double) loc.getYaw());
        section.set("pitch", (double) loc.getPitch());
    }

    public static Location readLocation(Server server, ConfigurationSection section) {
        if (server == null || section == null) return null;
        String worldName = section.getString("world");
        if (worldName == null) return null;
        World world = server.getWorld(worldName);
        if (world == null) return null;
        return new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    static Color parseDyeColor(String value) {
        String name = value.trim();
        if (name.matches("#[0-9a-fA-F]{6}")) return Color.fromRGB(Integer.parseInt(name.substring(1), 16));
        name = name.toUpperCase(Locale.ROOT).replace('-', '_');
        if ("GREY".equals(name)) name = "GRAY";
        if ("LIGHT_GREY".equals(name) || "SILVER".equals(name)) name = "LIGHT_GRAY";
        try {
            return DyeColor.valueOf(name).getFireworkColor();
        } catch (IllegalArgumentException failure) {
            if (!"LIGHT_GRAY".equals(name)) return null;
            try {
                return DyeColor.valueOf("SILVER").getFireworkColor();
            } catch (IllegalArgumentException unavailable) {
                return null;
            }
        }
    }

    /** Translates {@code &} color codes and {@code &#RRGGBB} hex colors (1.16+, stripped gracefully on 1.12-1.15). */
    public static String color(String raw) {
        if (raw == null) return "";
        String text = raw;
        if (text.indexOf("&#") >= 0) {
            Matcher m = HEX_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String replacement = "";
                if (BUNGEE_COLOR_OF != null) {
                    try {
                        Object color = BUNGEE_COLOR_OF.invoke(null, "#" + m.group(1));
                        if (color != null) replacement = color.toString();
                    } catch (Throwable ignored) {
                    }
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            text = sb.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Sends an actionbar message safely on Spigot/Paper 1.12.2-1.19.4 (falls back to chat on bare CraftBukkit). */
    public static void sendActionBar(Player player, String coloredText) {
        if (player == null || coloredText == null) return;
        if (trySendSpigotActionBar(player, coloredText)) {
            return;
        }
        if (!coloredText.isEmpty()) {
            player.sendMessage(coloredText);
        }
    }

    /** Clears the player's actionbar safely on 1.12.2-1.19.4. */
    public static void clearActionBar(Player player) {
        if (player == null) return;
        trySendSpigotActionBar(player, "");
    }

    /**
     * Plays a namespaced or dotted sound ID (e.g. {@code minecraft:block.note_block.chime})
     * safely across 1.12.2 (remapping 1.13+ {@code block.note_block.*} -> {@code block.note.*}) and 1.13-1.19.4.
     */
    public static void playSound(Player player, String soundId, float volume, float pitch, boolean pre113) {
        if (player == null || soundId == null || soundId.trim().isEmpty() || volume <= 0.0f) return;
        String clean = soundId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        if (pre113 && clean.startsWith("block.note_block.")) {
            clean = "block.note." + clean.substring("block.note_block.".length());
        }
        float clampedPitch = Math.max(0.5f, Math.min(2.0f, pitch));
        try {
            Location loc = player.getLocation();
            if (loc != null) {
                player.playSound(loc, clean, volume, clampedPitch);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sends a clickable/hoverable chat line to a player on Spigot/Paper 1.12.2-1.19.4 via Bungee Chat API
     * reflection (zero compile-time dependency so bare CraftBukkit never fails classloading).
     */
    public static void sendInteractive(org.bukkit.command.CommandSender sender, String coloredText,
                                       String command, boolean runImmediately, String hoverColored) {
        if (sender == null || coloredText == null) return;
        if (!(sender instanceof Player) || command == null || command.isEmpty()) {
            sender.sendMessage(coloredText);
            return;
        }
        if (trySendInteractiveLine((Player) sender, coloredText, command, runImmediately, hoverColored)) {
            return;
        }
        sender.sendMessage(coloredText);
    }

    /** Uses Spigot's chat serializer when available, with readable console output. */
    static boolean sendJson(org.bukkit.command.CommandSender sender, String json) {
        try {
            Class<?> serializer = Class.forName("net.md_5.bungee.chat.ComponentSerializer");
            Object components = serializer.getMethod("parse", String.class).invoke(null, json);
            if (sender instanceof Player) {
                sendComponents((Player) sender, components);
            } else {
                StringBuilder text = new StringBuilder();
                for (Object component : (Object[]) components) {
                    text.append(component.getClass().getMethod("toLegacyText").invoke(component));
                }
                sender.sendMessage(text.toString());
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private static void sendComponents(Player player, Object components) throws ReflectiveOperationException {
        Method spigot = Player.class.getMethod("spigot");
        spigot.getReturnType().getMethod("sendMessage", components.getClass()).invoke(spigot.invoke(player), components);
    }

    /** Sends a clickable Prev / Page / Next footer line on Spigot/Paper 1.12.2-1.19.4 (plain fallback otherwise). */
    public static void sendHelpFooter(org.bukkit.command.CommandSender sender, String prevLabel, String prevCmd,
                                      String pageText, String nextLabel, String nextCmd) {
        if (sender == null) return;
        if (sender instanceof Player && trySendFooter((Player) sender, prevLabel, prevCmd, pageText, nextLabel, nextCmd)) {
            return;
        }
        sender.sendMessage(pageText);
    }

    private static boolean trySendSpigotActionBar(Player player, String coloredText) {
        try {
            Class<?> textCompClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseCompArrayClass = Class.forName("[Lnet.md_5.bungee.api.chat.BaseComponent;");
            Class<?> msgTypeClass = Class.forName("net.md_5.bungee.api.ChatMessageType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object actionBarEnum = Enum.valueOf((Class<Enum>) msgTypeClass, "ACTION_BAR");
            Method fromLegacy = textCompClass.getMethod("fromLegacyText", String.class);
            Object comps = fromLegacy.invoke(null, coloredText);
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method sendMsg = spigot.getClass().getMethod("sendMessage", msgTypeClass, baseCompArrayClass);
            sendMsg.invoke(spigot, actionBarEnum, comps);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean trySendInteractiveLine(Player player, String coloredText, String command,
                                                  boolean runImmediately, String hoverColored) {
        try {
            Class<?> textCompClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseCompClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> baseCompArrayClass = Class.forName("[Lnet.md_5.bungee.api.chat.BaseComponent;");
            Class<?> clickClass = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> clickActionClass = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
            Class<?> hoverClass = Class.forName("net.md_5.bungee.api.chat.HoverEvent");
            Class<?> hoverActionClass = Class.forName("net.md_5.bungee.api.chat.HoverEvent$Action");

            Method fromLegacy = textCompClass.getMethod("fromLegacyText", String.class);
            Object[] comps = (Object[]) fromLegacy.invoke(null, coloredText);
            Object clickAction = Enum.valueOf((Class<Enum>) clickActionClass, runImmediately ? "RUN_COMMAND" : "SUGGEST_COMMAND");
            Object clickEvent = clickClass.getConstructor(clickActionClass, String.class).newInstance(clickAction, command);
            Method setClick = baseCompClass.getMethod("setClickEvent", clickClass);

            Object hoverEvent = null;
            Method setHover = null;
            if (hoverColored != null && !hoverColored.isEmpty()) {
                Object hoverAction = Enum.valueOf((Class<Enum>) hoverActionClass, "SHOW_TEXT");
                Object hoverComps = fromLegacy.invoke(null, hoverColored);
                hoverEvent = hoverClass.getConstructor(hoverActionClass, baseCompArrayClass).newInstance(hoverAction, hoverComps);
                setHover = baseCompClass.getMethod("setHoverEvent", hoverClass);
            }
            for (Object comp : comps) {
                setClick.invoke(comp, clickEvent);
                if (hoverEvent != null && setHover != null) {
                    setHover.invoke(comp, hoverEvent);
                }
            }
            sendComponents(player, comps);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean trySendFooter(Player player, String prevLabel, String prevCmd,
                                         String pageText, String nextLabel, String nextCmd) {
        try {
            Class<?> textCompClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseCompClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> clickClass = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> clickActionClass = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
            Method fromLegacy = textCompClass.getMethod("fromLegacyText", String.class);
            Method addExtra = baseCompClass.getMethod("addExtra", baseCompClass);
            Method setClick = baseCompClass.getMethod("setClickEvent", clickClass);
            Object runAction = Enum.valueOf((Class<Enum>) clickActionClass, "RUN_COMMAND");

            Object root = textCompClass.getConstructor(String.class).newInstance("");
            Object[] prevComps = (Object[]) fromLegacy.invoke(null, prevLabel);
            if (prevCmd != null && !prevCmd.isEmpty()) {
                Object click = clickClass.getConstructor(clickActionClass, String.class).newInstance(runAction, prevCmd);
                for (Object c : prevComps) setClick.invoke(c, click);
            }
            for (Object c : prevComps) addExtra.invoke(root, c);

            Object mid = textCompClass.getConstructor(String.class).newInstance("  " + pageText + "  ");
            addExtra.invoke(root, mid);

            Object[] nextComps = (Object[]) fromLegacy.invoke(null, nextLabel);
            if (nextCmd != null && !nextCmd.isEmpty()) {
                Object click = clickClass.getConstructor(clickActionClass, String.class).newInstance(runAction, nextCmd);
                for (Object c : nextComps) setClick.invoke(c, click);
            }
            for (Object c : nextComps) addExtra.invoke(root, c);

            Object components = java.lang.reflect.Array.newInstance(baseCompClass, 1);
            java.lang.reflect.Array.set(components, 0, root);
            sendComponents(player, components);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Human-readable server platform label (Paper, Purpur, Spigot, CraftBukkit, Bukkit). */
    public static String platformName(Server server) {
        String name = server == null ? "" : server.getName();
        String version = server == null ? "" : server.getVersion();
        String combined = (name + " " + version).toLowerCase(Locale.ROOT);
        if (combined.contains("folia")) return "Folia";
        if (combined.contains("purpur")) return "Purpur";
        if (combined.contains("paper")) return "Paper";
        if (combined.contains("spigot")) return "Spigot";
        if (combined.contains("craftbukkit")) return "CraftBukkit";
        return name == null || name.isEmpty() ? "Bukkit" : name;
    }

    private static Map<String, String> buildEnchantAliases() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("protection", "PROTECTION_ENVIRONMENTAL");
        map.put("prot", "PROTECTION_ENVIRONMENTAL");
        map.put("fire_protection", "PROTECTION_FIRE");
        map.put("feather_falling", "PROTECTION_FALL");
        map.put("blast_protection", "PROTECTION_EXPLOSIONS");
        map.put("projectile_protection", "PROTECTION_PROJECTILE");
        map.put("respiration", "OXYGEN");
        map.put("aqua_affinity", "WATER_WORKER");
        map.put("thorns", "THORNS");
        map.put("depth_strider", "DEPTH_STRIDER");
        map.put("frost_walker", "FROST_WALKER");
        map.put("sharpness", "DAMAGE_ALL");
        map.put("sharp", "DAMAGE_ALL");
        map.put("smite", "DAMAGE_UNDEAD");
        map.put("bane_of_arthropods", "DAMAGE_ARTHROPODS");
        map.put("knockback", "KNOCKBACK");
        map.put("fire_aspect", "FIRE_ASPECT");
        map.put("looting", "LOOT_BONUS_MOBS");
        map.put("sweeping", "SWEEPING_EDGE");
        map.put("sweeping_edge", "SWEEPING_EDGE");
        map.put("efficiency", "DIG_SPEED");
        map.put("eff", "DIG_SPEED");
        map.put("silk_touch", "SILK_TOUCH");
        map.put("unbreaking", "DURABILITY");
        map.put("fortune", "LOOT_BONUS_BLOCKS");
        map.put("power", "ARROW_DAMAGE");
        map.put("punch", "ARROW_KNOCKBACK");
        map.put("flame", "ARROW_FIRE");
        map.put("infinity", "ARROW_INFINITE");
        map.put("luck_of_the_sea", "LUCK");
        map.put("lure", "LURE");
        map.put("mending", "MENDING");
        map.put("binding_curse", "BINDING_CURSE");
        map.put("vanishing_curse", "VANISHING_CURSE");
        return map;
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findBungeeColorOf() {
        try {
            Class<?> clazz = Class.forName("net.md_5.bungee.api.ChatColor");
            return clazz.getMethod("of", String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
