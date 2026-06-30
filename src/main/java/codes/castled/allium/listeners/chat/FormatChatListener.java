package codes.castled.allium.listeners.chat;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.chat.ChatMessageManager;
import codes.castled.allium.managers.config.Config;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

public class FormatChatListener implements Listener {

    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile(
        "(?i)[&§]#([A-F0-9]{6})"
    );
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile(
        "(?i)[&§]x([&§][A-F0-9]){6}"
    );
    private static final Pattern MINI_HEX_PATTERN = Pattern.compile(
        "(?i)(?<![&§])#([A-F0-9]{6})"
    );
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile(
        "(?i)[&§]([0-9A-F])"
    );
    private static final BigDecimal PHASE_STEP = new BigDecimal("0.1");
    private static final BigDecimal PHASE_MAX = new BigDecimal("1.0");
    private static final BigDecimal PHASE_MIN = new BigDecimal("-1.0");
    private static final BigDecimal PHASE_NEGATE = new BigDecimal("-1.0");

    private final PluginStart plugin;
    private final LegacyComponentSerializer legacyComponentSerializer;
    private final Chat vaultChat;
    private final Permission vaultPermission;
    private final Map<String, String> groupFormats;
    private final Config config;
    private final String defaultFormat;
    private final boolean placeholderAPIEnabled;
    private final Pattern placeholderPattern = Pattern.compile(
        "%\\{([^}]+)\\}%"
    );
    private final Pattern rawMessagePattern = Pattern.compile(
        "\\[(&[0-9a-fA-Fk-oK-OrR].*?)\\]|\\[([^&].*?)\\]"
    );
    private final Pattern placeholderBracketPattern = Pattern.compile(
        "\\{([^}]+)\\}"
    );
    private final MiniMessage miniMessage;
    private final DecimalFormat phaseDecimalFormat;
    private final boolean blockUnicode;
    private final Map<String, String> rawTextMappings = new HashMap<>();
    private final ChatMessageManager chatMessageManager;
    private BigDecimal miniGradientPhase = PHASE_MIN;
    private SchedulerAdapter.TaskHandle phaseTask;

    public FormatChatListener(
        PluginStart plugin,
        Chat vaultChat,
        Config config,
        ChatMessageManager chatMessageManager
    ) {
        this.plugin = plugin;
        this.config = config;
        this.vaultChat = vaultChat;
        this.chatMessageManager = chatMessageManager;
        this.vaultPermission = (net.milkbowl.vault.permission.Permission) plugin.getVaultPermission();
        this.blockUnicode = config.getBoolean("block-unicode");

        legacyComponentSerializer = LegacyComponentSerializer.builder()
            .hexColors()
            .character('&')
            .build();
        miniMessage = MiniMessage.miniMessage();
        phaseDecimalFormat = new DecimalFormat(
            "#.#",
            DecimalFormatSymbols.getInstance(Locale.US)
        );
        phaseDecimalFormat.setRoundingMode(RoundingMode.DOWN);

        this.defaultFormat = plugin
            .getConfig()
            .getString(
                "chat-format.default",
                "<prefix> &a%allium_nickname%&f: &f<message>"
            );

        this.placeholderAPIEnabled =
            Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!placeholderAPIEnabled) {
            Text.sendDebugLog(
                INFO,
                "PlaceholderAPI not found. Placeholders in chat format will not work."
            );
        }

        groupFormats = new HashMap<>();
        loadGroupFormats();
    }

    private void loadGroupFormats() {
        FileConfiguration fileConfig = plugin.getConfig();
        ConfigurationSection groupsSection = fileConfig.getConfigurationSection(
            "chat-format.groups"
        );

        if (groupsSection != null) {
            Map<String, Object> tempMap = groupsSection.getValues(false);
            for (Map.Entry<String, Object> entry : tempMap.entrySet()) {
                groupFormats.put(entry.getKey(), entry.getValue().toString());
            }
            if (config.getBoolean("debug-mode")) {
                Text.sendDebugLog(
                    INFO,
                    "Loaded " + groupFormats.size() + " group chat formats."
                );
            }
        }
    }

    private void startPhaseRenderer() {
        phaseTask = SchedulerAdapter.runTaskTimerAsynchronously(
            plugin,
            () -> {
                miniGradientPhase = miniGradientPhase.add(PHASE_STEP);
                if (miniGradientPhase.compareTo(PHASE_MAX) > 0) {
                    miniGradientPhase = PHASE_MIN;
                }
            },
            0L,
            1L
        );
    }

    public void shutdown() {
        if (phaseTask != null) {
            phaseTask.cancel();
            phaseTask = null;
        }
    }

    private String getHighestPriorityGroup(Player player) {
        if (vaultPermission == null) {
            return "default";
        }

        String[] groups;
        try {
            groups = vaultPermission.getPlayerGroups(null, player);
        } catch (Exception e) {
            Text.sendDebugLog(
                WARN,
                "Error getting player groups: " + e.getMessage()
            );
            return "default";
        }

        if (groups == null || groups.length == 0) {
            return "default";
        }

        String highestGroup = "default";
        int highestWeight = Integer.MIN_VALUE;
        boolean debugMode = config.getBoolean("debug-mode");

        for (String group : groups) {
            try {
                int weight = 0;
                if (vaultChat != null) {
                    String weightStr = vaultChat.getGroupInfoString(
                        player.getWorld().getName(),
                        group,
                        "weight",
                        "0"
                    );
                    try {
                        weight = Integer.parseInt(weightStr);
                    } catch (NumberFormatException e) {
                        weight = 0;
                    }
                } else {
                    weight = Arrays.asList(groups).indexOf(group);
                }

                if (weight > highestWeight) {
                    highestWeight = weight;
                    highestGroup = group;
                }
            } catch (Exception e) {
                if (debugMode) {
                    Text.sendDebugLog(
                        WARN,
                        "Error getting weight for group: " +
                            group +
                            " - " +
                            e.getMessage()
                    );
                }
            }
        }

        if (!plugin.getConfig().contains("chat-hover.groups." + highestGroup)) {
            for (String group : groups) {
                if (plugin.getConfig().contains("chat-hover.groups." + group)) {
                    return group;
                }
            }
            return "default";
        }

        if (debugMode) {
            Text.sendDebugLog(
                INFO,
                "Selected highest priority group: " +
                    highestGroup +
                    " with weight " +
                    highestWeight
            );
        }
        return highestGroup;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getHoverClickConfig(
        Player player,
        String element
    ) {
        String group = getHighestPriorityGroup(player);
        String configPath = "chat-hover.groups." + group + "." + element;

        if (
            !plugin
                .getConfig()
                .contains("chat-hover.groups." + group + "." + element)
        ) {
            configPath = "chat-hover.groups.default." + element;
            if (!plugin.getConfig().contains(configPath)) {
                return new HashMap<>();
            }
        }

        Object value = plugin.getConfig().get(configPath);
        if (value instanceof ConfigurationSection) {
            return ((ConfigurationSection) value).getValues(false);
        } else if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new HashMap<>();
    }

    /**
     * Processes PlaceholderAPI placeholders in configuration values (hover, format, etc.)
     * This does NOT require permission as it's for server-configured content.
     * When PlaceholderAPI is not installed, replaces %allium_*% ourselves.
     * Always runs our own %allium_nickname% replacement as fallback (handles PAPI missing or expansion not registered).
     */
    private String processPlaceholderAPIPlaceholdersInConfig(
        String text,
        Player player
    ) {
        // Replace our placeholders FIRST so we control the output (PAPI expansion may return blank)
        text = replaceAlliumPlaceholders(text, player);
        if (!placeholderAPIEnabled) {
            return text;
        }

        if (text.contains("%gradientdisplayname%")) {
            if (player.hasPermission("allium.gradientname")) {
                text = text.replace(
                    "%gradientdisplayname%",
                    buildAnimatedGradientDisplayName(player)
                );
            }
        }

        text = PlaceholderAPI.setPlaceholders(player, text);

        Matcher matcher = placeholderBracketPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (
                placeholder.equals("player") ||
                placeholder.equals("prefix") ||
                placeholder.equals("group")
            ) {
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(matcher.group(0))
                );
                continue;
            }
            String papiPlaceholder = "%" + placeholder + "%";
            String replacement = PlaceholderAPI.setPlaceholders(
                player,
                papiPlaceholder
            );
            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(replacement)
            );
        }
        matcher.appendTail(buffer);

        return buffer.toString();
    }

    /** Returns the player's display name (nickname if set, otherwise username) with formatting applied. */
    private String getPlayerDisplayName(Player player) {
        if (player == null) return "";
        codes.castled.allium.managers.NicknameManager nm =
            plugin.getNicknameManager();
        if (nm == null) return player.getName();
        String stored = nm.getStoredNickname(player);
        String formatted = nm.getFormattedNickname(
            player,
            stored != null && !stored.isEmpty() ? stored : player.getName()
        );
        return (formatted != null && !formatted.isEmpty())
            ? formatted
            : player.getName();
    }

    /**
     * Replaces %allium_nickname% and %allium_nickname_raw% with our own logic.
     * Used when PlaceholderAPI is missing or as fallback when our expansion isn't registered.
     * Defaults to player's real in-game name when no nickname is set.
     */
    private String replaceAlliumPlaceholders(String text, Player player) {
        if (text == null || player == null) return text;
        if (!text.contains("%allium_")) return text;

        String defaultName = player.getName();
        if (defaultName == null || defaultName.isEmpty()) defaultName = player
            .getUniqueId()
            .toString();

        codes.castled.allium.managers.NicknameManager nm =
            plugin.getNicknameManager();
        String raw = (nm != null) ? nm.getStoredNickname(player) : defaultName;
        if (raw == null || raw.isEmpty()) raw = defaultName;
        String formatted = (nm != null)
            ? nm.getFormattedNickname(player, raw)
            : defaultName;
        if (formatted == null || formatted.isEmpty()) formatted = defaultName;

        return text
            .replace("%allium_nickname%", formatted)
            .replace("%allium_nickname_raw%", raw);
    }

    private String getFormattedPrefix(Player player) {
        String rawPrefix = getPrefix(player);
        if (rawPrefix != null && !rawPrefix.trim().isEmpty()) {
            Component prefixComponent = legacyComponentSerializer.deserialize(
                rawPrefix
            );
            return legacyComponentSerializer.serialize(prefixComponent);
        }
        return "";
    }

    private String buildAnimatedGradientDisplayName(Player player) {
        if (plugin.getGradientNameManager() != null) {
            return plugin
                .getGradientNameManager()
                .buildAnimatedGradientDisplayName(player);
        }

        String resolved = PlaceholderAPI.setPlaceholders(
            player,
            "%gradientdisplayname%"
        );
        if (
            resolved == null ||
            resolved.isBlank() ||
            "%gradientdisplayname%".equals(resolved)
        ) {
            return miniMessage.escapeTags(player.getName());
        }

        resolved = resolved.replaceAll("^([&§]r)+", "");
        List<String> colors = extractColors(resolved);
        if (colors.isEmpty()) {
            String prefixColor = extractTrailingColor(getPrefix(player));
            if (prefixColor != null && !prefixColor.isBlank()) {
                colors.add(normalizeColor(prefixColor));
            }
        }

        String first = colors.isEmpty() ? "#FFFFFF" : colors.get(0);
        String last =
            colors.size() >= 2 ? colors.get(colors.size() - 1) : first;
        String midpoint = nearestNamedColor(average(first, last));
        String visibleName = stripFormatting(resolved);
        if (visibleName.isBlank()) {
            visibleName = player.getName();
        }

        return (
            "<gradient:" +
            first +
            ":" +
            last +
            ":" +
            midpoint +
            ":" +
            getPhaseValue(false) +
            ">" +
            miniMessage.escapeTags(visibleName) +
            "</gradient>"
        );
    }

    private String formatPhases(String text) {
        if (plugin.getGradientNameManager() != null) {
            return plugin.getGradientNameManager().formatPhases(text);
        }
        if (text == null || text.indexOf('#') == -1) {
            return text;
        }
        return text
            .replace("#phase-mm-g#", getPhaseValue(false))
            .replace("#-phase-mm-g#", getPhaseValue(true));
    }

    private String getPhaseValue(boolean negative) {
        BigDecimal phase = negative
            ? miniGradientPhase.multiply(PHASE_NEGATE)
            : miniGradientPhase;
        synchronized (phaseDecimalFormat) {
            return phaseDecimalFormat.format(phase);
        }
    }

    private List<String> extractColors(String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }

        List<ColorToken> tokens = new ArrayList<>();

        List<int[]> hexRanges = new ArrayList<>();

        Matcher hexMatcher = LEGACY_HEX_PATTERN.matcher(input);
        while (hexMatcher.find()) {
            tokens.add(
                new ColorToken(
                    hexMatcher.start(),
                    "#" + hexMatcher.group(1).toUpperCase(Locale.ROOT)
                )
            );
            hexRanges.add(new int[] { hexMatcher.start(), hexMatcher.end() });
        }

        Matcher sectionHexMatcher = SECTION_HEX_PATTERN.matcher(input);
        while (sectionHexMatcher.find()) {
            String raw = sectionHexMatcher.group();
            String hex = raw.substring(2).replace("§", "").replace("&", "");
            tokens.add(
                new ColorToken(
                    sectionHexMatcher.start(),
                    "#" + hex.toUpperCase(Locale.ROOT)
                )
            );
            hexRanges.add(new int[] {
                sectionHexMatcher.start(),
                sectionHexMatcher.end(),
            });
        }

        Matcher miniHexMatcher = MINI_HEX_PATTERN.matcher(input);
        while (miniHexMatcher.find()) {
            tokens.add(
                new ColorToken(
                    miniHexMatcher.start(),
                    "#" + miniHexMatcher.group(1).toUpperCase(Locale.ROOT)
                )
            );
        }

        Matcher legacyMatcher = LEGACY_COLOR_PATTERN.matcher(input);
        while (legacyMatcher.find()) {
            if (isInsideRange(legacyMatcher.start(), hexRanges)) {
                continue;
            }
            String hex = legacyColorToHex(legacyMatcher.group(1).charAt(0));
            if (hex != null) {
                tokens.add(new ColorToken(legacyMatcher.start(), hex));
            }
        }

        tokens.sort(Comparator.comparingInt(ColorToken::index));
        List<String> colors = new ArrayList<>();
        for (ColorToken token : tokens) {
            colors.add(token.hex());
        }
        return colors;
    }

    private boolean isInsideRange(int index, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (index >= range[0] && index < range[1]) {
                return true;
            }
        }
        return false;
    }

    private String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return "#FFFFFF";
        }
        if (color.startsWith("&#") || color.startsWith("§#")) {
            return "#" + color.substring(2).toUpperCase(Locale.ROOT);
        }
        if (color.startsWith("#") && color.length() == 7) {
            return color.toUpperCase(Locale.ROOT);
        }
        if (
            color.length() >= 2 &&
            (color.charAt(0) == '&' || color.charAt(0) == '§')
        ) {
            String hex = legacyColorToHex(color.charAt(1));
            if (hex != null) {
                return hex;
            }
        }
        return "#FFFFFF";
    }

    private String stripFormatting(String input) {
        if (input == null) {
            return "";
        }
        String stripped = SECTION_HEX_PATTERN.matcher(input).replaceAll("");
        stripped = LEGACY_HEX_PATTERN.matcher(stripped).replaceAll("");
        stripped = stripped.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
        stripped = stripped.replaceAll("<[^>]+>", "");
        return stripped;
    }

    private Color average(String first, String last) {
        Color a = Color.decode(first);
        Color b = Color.decode(last);
        return new Color(
            (a.getRed() + b.getRed()) / 2,
            (a.getGreen() + b.getGreen()) / 2,
            (a.getBlue() + b.getBlue()) / 2
        );
    }

    private String nearestNamedColor(Color color) {
        Map<String, Color> named = new LinkedHashMap<>();
        named.put("black", new Color(0x000000));
        named.put("dark_blue", new Color(0x0000AA));
        named.put("dark_green", new Color(0x00AA00));
        named.put("dark_aqua", new Color(0x00AAAA));
        named.put("dark_red", new Color(0xAA0000));
        named.put("dark_purple", new Color(0xAA00AA));
        named.put("gold", new Color(0xFFAA00));
        named.put("gray", new Color(0xAAAAAA));
        named.put("dark_gray", new Color(0x555555));
        named.put("blue", new Color(0x5555FF));
        named.put("green", new Color(0x55FF55));
        named.put("aqua", new Color(0x55FFFF));
        named.put("red", new Color(0xFF5555));
        named.put("light_purple", new Color(0xFF55FF));
        named.put("yellow", new Color(0xFFFF55));
        named.put("white", new Color(0xFFFFFF));

        String best = "white";
        long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<String, Color> entry : named.entrySet()) {
            Color candidate = entry.getValue();
            long dr = color.getRed() - candidate.getRed();
            long dg = color.getGreen() - candidate.getGreen();
            long db = color.getBlue() - candidate.getBlue();
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }

    private String legacyColorToHex(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "#000000";
            case '1' -> "#0000AA";
            case '2' -> "#00AA00";
            case '3' -> "#00AAAA";
            case '4' -> "#AA0000";
            case '5' -> "#AA00AA";
            case '6' -> "#FFAA00";
            case '7' -> "#AAAAAA";
            case '8' -> "#555555";
            case '9' -> "#5555FF";
            case 'a' -> "#55FF55";
            case 'b' -> "#55FFFF";
            case 'c' -> "#FF5555";
            case 'd' -> "#FF55FF";
            case 'e' -> "#FFFF55";
            case 'f' -> "#FFFFFF";
            default -> null;
        };
    }

    private record ColorToken(int index, String hex) {}

    private Component applyHoverClick(
        Player player,
        Component component,
        String element
    ) {
        Map<String, Object> hoverClickConfig = getHoverClickConfig(
            player,
            element
        );
        if (hoverClickConfig.isEmpty()) {
            return component;
        }

        Component hoverComponent = Component.empty();
        if (hoverClickConfig.containsKey("hover")) {
            List<String> hoverText = hoverClickConfig.get("hover") instanceof
                List
                ? (List<String>) hoverClickConfig.get("hover")
                : Collections.singletonList(
                      hoverClickConfig.get("hover").toString()
                  );

            if (hoverText != null && !hoverText.isEmpty()) {
                String hover = String.join("\n", hoverText)
                    .replace("{player}", player.getName())
                    .replace("{prefix}", getFormattedPrefix(player))
                    .replace("{group}", getHighestPriorityGroup(player));

                hover = processPlaceholderAPIPlaceholdersInConfig(
                    hover,
                    player
                );

                if (hover.contains("&") || hover.contains("§")) {
                    hover = convertLegacyToMiniMessage(hover);
                }

                try {
                    hoverComponent = miniMessage.deserialize(hover);
                } catch (Exception e) {
                    if (config.getBoolean("debug-mode")) {
                        Text.sendDebugLog(
                            WARN,
                            "Error parsing hover text: " + e.getMessage()
                        );
                    }
                    hoverComponent = Component.text(
                        LegacyComponentSerializer.legacyAmpersand()
                            .deserialize(hover)
                            .content()
                    );
                }
            }
        }

        ClickEvent clickEvent = null;
        if (
            hoverClickConfig.containsKey("action") &&
            hoverClickConfig.get("action") != null &&
            hoverClickConfig.containsKey("click") &&
            hoverClickConfig.get("click") != null
        ) {
            String action = hoverClickConfig.get("action").toString();
            String click = hoverClickConfig
                .get("click")
                .toString()
                .replace("{player}", player.getName())
                .replace("{prefix}", getFormattedPrefix(player))
                .replace("{group}", getHighestPriorityGroup(player));

            click = processPlaceholderAPIPlaceholdersInConfig(click, player);

            switch (action.toLowerCase()) {
                case "run-command":
                    clickEvent = ClickEvent.runCommand(click);
                    break;
                case "suggest-command":
                    clickEvent = ClickEvent.suggestCommand(click);
                    break;
                case "open-url":
                    clickEvent = ClickEvent.openUrl(click);
                    break;
                case "copy-to-clipboard":
                    String content = hoverClickConfig.containsKey("content")
                        ? hoverClickConfig.get("content").toString()
                        : click;
                    content = content
                        .replace("{player}", player.getName())
                        .replace("{prefix}", getFormattedPrefix(player))
                        .replace("{group}", getHighestPriorityGroup(player));
                    content = processPlaceholderAPIPlaceholdersInConfig(
                        content,
                        player
                    );
                    clickEvent = ClickEvent.copyToClipboard(content);
                    break;
            }
        }

        if (hoverComponent != Component.empty()) {
            component = component.hoverEvent(hoverComponent);
        }
        if (clickEvent != null) {
            component = component.clickEvent(clickEvent);
        }

        return component;
    }

    public boolean canEnable() {
        return true;
    }

    private boolean containsUnicode(String message) {
        return message.codePoints().anyMatch(cp -> cp > 127);
    }

    private String convertLegacyToMiniMessage(String message) {
        if (message == null || message.isEmpty()) return message;

        message = message.replaceAll("&#([A-Fa-f0-9]{6})", "<#$1>");
        message = message.replace("&l", "<bold>");
        message = message.replace("&0", "<black>");
        message = message.replace("&1", "<dark_blue>");
        message = message.replace("&2", "<dark_green>");
        message = message.replace("&3", "<dark_aqua>");
        message = message.replace("&4", "<dark_red>");
        message = message.replace("&5", "<dark_purple>");
        message = message.replace("&6", "<gold>");
        message = message.replace("&7", "<gray>");
        message = message.replace("&8", "<dark_gray>");
        message = message.replace("&9", "<blue>");
        message = message.replace("&a", "<green>");
        message = message.replace("&b", "<aqua>");
        message = message.replace("&c", "<red>");
        message = message.replace("&d", "<light_purple>");
        message = message.replace("&e", "<yellow>");
        message = message.replace("&f", "<white>");
        message = message.replace("&k", "<obfuscated>");
        message = message.replace("&m", "<strikethrough>");
        message = message.replace("&n", "<underlined>");
        message = message.replace("&o", "<italic>");
        message = message.replace("&r", "<reset>");
        message = message.replace("§0", "<black>");
        message = message.replace("§1", "<dark_blue>");
        message = message.replace("§2", "<dark_green>");
        message = message.replace("§3", "<dark_aqua>");
        message = message.replace("§4", "<dark_red>");
        message = message.replace("§5", "<dark_purple>");
        message = message.replace("§6", "<gold>");
        message = message.replace("§7", "<gray>");
        message = message.replace("§8", "<dark_gray>");
        message = message.replace("§9", "<blue>");
        message = message.replace("§a", "<green>");
        message = message.replace("§b", "<aqua>");
        message = message.replace("§c", "<red>");
        message = message.replace("§d", "<light_purple>");
        message = message.replace("§e", "<yellow>");
        message = message.replace("§f", "<white>");
        message = message.replace("§k", "<obfuscated>");
        message = message.replace("§l", "<bold>");
        message = message.replace("§m", "<strikethrough>");
        message = message.replace("§n", "<underlined>");
        message = message.replace("§o", "<italic>");
        message = message.replace("§r", "<reset>");

        return message;
    }

    private String editMessageWithRawText(String message, Player player) {
        if (message == null || message.isEmpty()) return message;

        // Early exit if message doesn't contain any raw text patterns
        if (!message.matches(".*\\[.*\\].*")) {
            return message;
        }

        StringBuilder result = new StringBuilder();
        Matcher matcher = rawMessagePattern.matcher(message);
        int lastEnd = 0;
        boolean hasRawContent = false;

        while (matcher.find()) {
            String rawText =
                matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String fullMatch = matcher.group(0);

            // Append text before the match
            result.append(message, lastEnd, matcher.start());

            // Check if the text inside brackets contains any color codes or formatting
            boolean hasFormatting = rawText.matches(
                ".*[&§][0-9a-fA-Fk-oK-OrR].*"
            );

            // Check if brackets are escaped (preceded by a color code with optional space)
            boolean isEscaped = false;
            if (matcher.start() > 0) {
                String beforeMatch = message.substring(0, matcher.start());
                if (beforeMatch.matches(".*[&§][0-9a-fA-Fk-oK-OrR](?:\\s)?$")) {
                    isEscaped = true;
                }
            }

            if (isEscaped) {
                // If escaped, keep the original text including brackets
                result.append(fullMatch);
            } else if (!hasFormatting) {
                // If no formatting codes, keep the brackets and their content as-is
                result.append('[').append(rawText).append(']');
            } else {
                // Has formatting codes, process as raw text
                hasRawContent = true;
                String protectedMarker =
                    "\uE000RAW_" +
                    System.nanoTime() +
                    "_" +
                    rawText.hashCode() +
                    "\uE000";
                result.append(protectedMarker);

                if (!rawTextMappings.containsKey(protectedMarker)) {
                    rawTextMappings.put(protectedMarker, rawText);
                }
            }

            lastEnd = matcher.end();
        }

        // Append any remaining text after the last match
        if (lastEnd < message.length()) {
            result.append(message.substring(lastEnd));
        }

        // If no raw content was processed, return the original message to preserve any formatting
        return hasRawContent ? result.toString() : message;
    }

    private boolean hasMiniMessagePermission(Player player, String tagType) {
        if (player == null) return false;
        String permission = "chat.minimessage." + tagType.toLowerCase();
        return (
            player.hasPermission(permission) ||
            player.hasPermission("chat.minimessage.*") ||
            player.hasPermission(permission + ".*")
        );
    }

    private boolean validateMiniMessage(Player player, String message) {
        if (
            message.matches(
                "(?i).*<(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>.*"
            )
        ) {
            if (!hasMiniMessagePermission(player, "color")) {
                String color = message.replaceAll(
                    "(?i).*<(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>.*",
                    "$1"
                );
                if (
                    !hasMiniMessagePermission(
                        player,
                        "color." + color.toLowerCase()
                    )
                ) {
                    return false;
                }
            }
        }

        if (message.matches("(?i).*<gradient[:#].*?>.*")) {
            // Check for animated gradient (with phase parameter)
            if (message.matches("(?i).*<gradient[^>]*:phase-.*?>.*")) {
                if (!hasMiniMessagePermission(player, "gradient.animation")) {
                    return false;
                }
            } else {
                // Static gradient
                if (!hasMiniMessagePermission(player, "gradient")) {
                    return false;
                }
            }
        }

        if (message.matches("(?i).*<rainbow[:#].*?>.*")) {
            if (!hasMiniMessagePermission(player, "rainbow")) {
                return false;
            }
        }

        if (message.matches("(?i).*<click:.*?>.*")) {
            if (!hasMiniMessagePermission(player, "click")) {
                return false;
            }
        }

        if (message.matches("(?i).*<hover:.*?>.*")) {
            if (!hasMiniMessagePermission(player, "hover")) {
                return false;
            }
        }

        if (
            message.matches(
                "(?i).*<(b|bold|i|italic|u|underlined|st|strikethrough|obf|obfuscated|reset)>.*"
            )
        ) {
            if (!hasMiniMessagePermission(player, "format")) {
                return false;
            }
        }

        return true;
    }

    private boolean containsMiniMessageTags(String message) {
        Pattern miniMessagePattern = Pattern.compile(
            "<[^>]+>|</[^>]+>|&#[0-9a-fA-F]{6}"
        );
        return miniMessagePattern.matcher(message).find();
    }

    private Component processMessageContent(String content) {
        try {
            return miniMessage.deserialize(content);
        } catch (Exception e) {
            return Component.text(content);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!config.getBoolean("enable-chat-formatting")) {
            return;
        }

        Player player = event.getPlayer();
        Component messageComponent = event.message();
        String rawMessage = legacyComponentSerializer.serialize(
            messageComponent
        );
        String originalMessage =
            PlainTextComponentSerializer.plainText().serialize(event.message());

        boolean debugMode = config.getBoolean("debug-mode");

        // Unicode check and cleanup
        if (blockUnicode && !player.hasPermission("chat.unicode")) {
            if (containsUnicode(originalMessage)) {
                String cleanedMessage = originalMessage.replaceAll(
                    "[^-\u007F]+",
                    ""
                );
                if (debugMode) {
                    Text.sendDebugLog(
                        INFO,
                        "Stripped unicode from message by " +
                            player.getName() +
                            ": " +
                            originalMessage +
                            " -> " +
                            cleanedMessage
                    );
                }
                rawMessage = legacyComponentSerializer.serialize(
                    Component.text(cleanedMessage)
                );
                originalMessage = cleanedMessage;
            }
        }

        // Process raw text in brackets FIRST, before any formatting processing
        String messageContent = editMessageWithRawText(rawMessage, player);

        // Strip unauthorized formatting and track if we have legacy codes
        boolean hasLegacyCodes = false;
        if (vaultPermission != null) {
            hasLegacyCodes = rawMessage.matches(".*[&§][0-9a-fA-Fk-oK-OrR].*");
            messageContent = stripUnauthorizedFormatting(
                messageContent,
                player
            );
            messageContent = convertLegacyToMiniMessage(messageContent);
        } else {
            messageContent = Text.stripColor(messageContent, null);
        }

        // Check MiniMessage permissions
        boolean useMiniMessage = false;
        boolean hasMiniMessageTags = containsMiniMessageTags(messageContent);
        boolean hasOriginalMiniMessageTags = containsMiniMessageTags(
            rawMessage
        );

        if (hasMiniMessageTags) {
            if (hasLegacyCodes && !hasOriginalMiniMessageTags) {
                useMiniMessage = true;
                if (debugMode) {
                    Text.sendDebugLog(
                        INFO,
                        "Allowing legacy-converted MiniMessage tags for " +
                            player.getName()
                    );
                }
            } else if (player.hasPermission("chat.minimessage")) {
                if (!validateMiniMessage(player, messageContent)) {
                    messageContent = miniMessage.stripTags(messageContent);
                    if (debugMode) {
                        Text.sendDebugLog(
                            INFO,
                            "Stripped unauthorized MiniMessage tags from message by " +
                                player.getName()
                        );
                    }
                } else {
                    useMiniMessage = true;
                }
            } else {
                messageContent = miniMessage.stripTags(messageContent);
                if (debugMode) {
                    Text.sendDebugLog(
                        INFO,
                        "Stripped MiniMessage tags from message by " +
                            player.getName() +
                            " (no permission)"
                    );
                }
            }
        }

        // Process PlaceholderAPI placeholders if player has permission
        if (
            vaultPermission != null &&
            vaultPermission.playerHas(player, "chat.placeholderapi")
        ) {
            messageContent = processPlaceholderAPIPlaceholdersInChat(
                messageContent,
                player
            );
        }

        // Restore protected raw text as the final step
        messageContent = restoreRawText(messageContent);

        onPlayerChat(event, player, rawMessage, messageContent);
    }

    private void onPlayerChat(
        AsyncChatEvent event,
        Player player,
        String message,
        String messageContent
    ) {
        // Get chat format and prefix
        String chatFormat = getChatFormat(player);

        // Process placeholders in the chat format string
        chatFormat = processPlaceholderAPIPlaceholdersInConfig(
            chatFormat,
            player
        );

        String prefix = getPrefix(player);
        String suffix = getSuffix(player);

        // Process the message content with MiniMessage if needed
        final Component messageComponent = processMessageContent(
            messageContent
        );

        // Create the basic chat components
        final boolean hasPrefix = (prefix != null && !prefix.trim().isEmpty());
        final Component prefixComponent = hasPrefix
            ? (containsMiniMessageTags(prefix)
                  ? miniMessage.deserialize(prefix)
                  : legacyComponentSerializer.deserialize(prefix))
            : Component.empty();
        final Component basePrefixComponent = hasPrefix
            ? applyHoverClick(player, prefixComponent, "prefix")
            : Component.empty();
        final boolean hasSuffix = (suffix != null && !suffix.trim().isEmpty());
        final Component suffixComponent = hasSuffix
            ? (containsMiniMessageTags(suffix)
                  ? miniMessage.deserialize(suffix)
                  : legacyComponentSerializer.deserialize(suffix))
            : Component.empty();
        final Component baseSuffixComponent = hasSuffix
            ? applyHoverClick(player, suffixComponent, "suffix")
            : Component.empty();
        String playerDisplayName = getPlayerDisplayName(player);
        final Component basePlayerComponent = applyHoverClick(
            player,
            legacyComponentSerializer.deserialize(playerDisplayName),
            "name"
        );

        // If no prefix/suffix, remove the token and collapse surrounding whitespace
        // to avoid stray spaces. We replace the tag plus adjacent whitespace with a
        // single space, then trim any leading/trailing space that may result.
        String formatBase = chatFormat;
        if (!hasPrefix) {
            formatBase = formatBase
                .replaceAll("(?i)\\s*<prefix>\\s*", " ")
                .trim();
        }
        if (!hasSuffix) {
            formatBase = formatBase
                .replaceAll("(?i)\\s*<suffix>\\s*", " ")
                .trim();
        }
        // Collapse any runs of multiple spaces that may have been introduced
        formatBase = formatBase.replaceAll(" {2,}", " ");

        // Replace placeholders in the format string with temporary markers
        String processedFormat = formatBase
            .replace("<prefix>", "{PREFIX_COMPONENT}")
            .replace("<player>", "{PLAYER_COMPONENT}")
            .replace("<message>", "{MESSAGE_COMPONENT}")
            .replace("<suffix>", "{SUFFIX_COMPONENT}");

        // Build the final message using the processed format
        processedFormat = formatPhases(processedFormat);
        Component baseMessage = containsMiniMessageTags(processedFormat)
            ? miniMessage.deserialize(
                  convertLegacyToMiniMessage(processedFormat)
              )
            : legacyComponentSerializer.deserialize(processedFormat);

        // Store the message for potential deletion by staff (using temporary message)
        Component tempMessage = baseMessage
            .replaceText(builder ->
                builder
                    .matchLiteral("{PREFIX_COMPONENT}")
                    .replacement(basePrefixComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{PLAYER_COMPONENT}")
                    .replacement(basePlayerComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{MESSAGE_COMPONENT}")
                    .replacement(messageComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{SUFFIX_COMPONENT}")
                    .replacement(baseSuffixComponent)
            );

        long messageId = chatMessageManager.storeMessage(player, tempMessage);
        if (plugin.getDiscordSrvMessageBridge() != null) {
            plugin
                .getDiscordSrvMessageBridge()
                .noteOutgoingPlayerChat(
                    messageId,
                    player,
                    messageComponent,
                    tempMessage
                );
        }

        // Track this message for per-player chat history (for packet-based deletion)
        ChatMessageManager.ChatMessage chatMessage =
            new ChatMessageManager.ChatMessage(
                messageId,
                player.getUniqueId(),
                player.getName(),
                tempMessage
            );

        // Track this message for all online players (since chat is broadcast to everyone)
        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            chatMessageManager.trackMessageForPlayer(
                onlinePlayer.getUniqueId(),
                chatMessage
            );
        }

        // Create the final message with base player component (no extra hover/suggest delete text)
        Component finalMessage = baseMessage
            .replaceText(builder ->
                builder
                    .matchLiteral("{PREFIX_COMPONENT}")
                    .replacement(basePrefixComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{PLAYER_COMPONENT}")
                    .replacement(basePlayerComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{MESSAGE_COMPONENT}")
                    .replacement(messageComponent)
            )
            .replaceText(builder ->
                builder
                    .matchLiteral("{SUFFIX_COMPONENT}")
                    .replacement(baseSuffixComponent)
            );

        // Use a per-viewer renderer to append staff-only hover/click without altering the visible message
        final Component baseBroadcast = finalMessage; // capture for lambda
        final Component basePrefixForAugment = basePrefixComponent;
        final Component baseNameForAugment = basePlayerComponent;
        final Component baseSuffixForAugment = baseSuffixComponent;
        final Component baseMsgForAugment = messageComponent;
        final Component baseMessageTemplate = baseMessage; // for reconstructing per-viewer messages
        event.renderer((source, sourceDisplayName, msg, viewer) -> {
            if (viewer instanceof Player) {
                Player v = (Player) viewer;
                if (
                    v.hasPermission("allium.deletemsg") ||
                    v.hasPermission("allium.delmsg") ||
                    v.hasPermission("allium.staff")
                ) {
                    String mode = config
                        .getString("chat-hover.delete-hover-mode", "prefix")
                        .toLowerCase();
                    String effectiveMode = mode;
                    if (
                        !hasPrefix &&
                        !"name".equals(effectiveMode) &&
                        !"player".equals(effectiveMode) &&
                        !"none".equals(effectiveMode)
                    ) {
                        effectiveMode = "name";
                    }
                    if ("none".equals(effectiveMode)) {
                        return baseBroadcast;
                    }

                    // Helper to extend hover text and set suggest-command on a single component
                    java.util.function.Function<Component, Component> augment =
                        comp -> {
                            HoverEvent<?> hv = comp.hoverEvent();
                            Component hvText = null;
                            if (
                                hv != null &&
                                hv.action() == HoverEvent.Action.SHOW_TEXT
                            ) {
                                Object value = hv.value();
                                if (value instanceof Component) hvText =
                                    (Component) value;
                            }
                            if (hvText == null) hvText = Component.empty();

                            String plain =
                                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                                    hvText
                                );
                            if (
                                !plain.contains("Click to delete this message")
                            ) {
                                Component staffLine = Component.text(
                                    "Click to delete this message ",
                                    NamedTextColor.RED
                                )
                                    .append(
                                        Component.text(
                                            "[",
                                            NamedTextColor.DARK_GRAY
                                        )
                                    )
                                    .append(
                                        Component.text(
                                            String.valueOf(messageId),
                                            NamedTextColor.WHITE
                                        )
                                    )
                                    .append(
                                        Component.text(
                                            "]",
                                            NamedTextColor.DARK_GRAY
                                        )
                                    );
                                hvText = hvText
                                    .append(Component.newline())
                                    .append(staffLine);
                            }
                            return comp
                                .hoverEvent(HoverEvent.showText(hvText))
                                .clickEvent(
                                    ClickEvent.suggestCommand(
                                        "/delmsg " + messageId
                                    )
                                );
                        };

                    // Build per-viewer message: augment only the selected segment
                    final Component viewerPrefixFinal = "prefix".equals(
                        effectiveMode
                    )
                        ? augment.apply(basePrefixForAugment)
                        : basePrefixForAugment;
                    final Component viewerNameFinal = ("name".equals(
                            effectiveMode
                        ) || "player".equals(effectiveMode))
                        ? augment.apply(baseNameForAugment)
                        : baseNameForAugment;
                    final Component viewerSuffixFinal = "suffix".equals(
                        effectiveMode
                    )
                        ? augment.apply(baseSuffixForAugment)
                        : baseSuffixForAugment;

                    Component viewerMessage = baseMessageTemplate
                        .replaceText(builder ->
                            builder
                                .matchLiteral("{PREFIX_COMPONENT}")
                                .replacement(viewerPrefixFinal)
                        )
                        .replaceText(builder ->
                            builder
                                .matchLiteral("{PLAYER_COMPONENT}")
                                .replacement(viewerNameFinal)
                        )
                        .replaceText(builder ->
                            builder
                                .matchLiteral("{MESSAGE_COMPONENT}")
                                .replacement(baseMsgForAugment)
                        )
                        .replaceText(builder ->
                            builder
                                .matchLiteral("{SUFFIX_COMPONENT}")
                                .replacement(viewerSuffixFinal)
                        );
                    return viewerMessage;
                }
            }
            return baseBroadcast;
        });
    }

    private String getChatFormat(Player player) {
        String groupName = "default";
        boolean debugMode = config.getBoolean("debug-mode");

        if (vaultChat != null) {
            groupName = vaultChat.getPrimaryGroup(player);
            if (groupName == null) {
                groupName = "default";
            }
        } else {
            if (debugMode) {
                Text.sendDebugLog(
                    INFO,
                    "Vault Chat unavailable, using default group for " +
                        player.getName()
                );
            }
        }

        if (groupFormats.containsKey(groupName)) {
            return groupFormats.get(groupName);
        } else {
            return defaultFormat;
        }
    }

    /**
     * Processes PlaceholderAPI placeholders in user-generated chat messages.
     * This REQUIRES the chat.placeholderapi permission.
     * When PlaceholderAPI is missing, we still replace our own %allium_nickname% placeholders.
     */
    private String processPlaceholderAPIPlaceholdersInChat(
        String text,
        Player player
    ) {
        if (vaultPermission == null) {
            return text;
        }
        // Always replace our own placeholders (works with or without PlaceholderAPI)
        if (text.contains("%allium_")) {
            text = replaceAlliumPlaceholders(text, player);
        }
        if (!placeholderAPIEnabled) {
            return text;
        }

        // Check if player has the placeholderapi permission
        boolean hasPermission = false;
        try {
            // Try modern Vault method first (1.7.3+)
            try {
                java.lang.reflect.Method hasMethod = vaultPermission
                    .getClass()
                    .getMethod(
                        "playerHas",
                        String.class,
                        org.bukkit.OfflinePlayer.class,
                        String.class
                    );
                hasPermission = (boolean) hasMethod.invoke(
                    vaultPermission,
                    player.getWorld().getName(),
                    player,
                    "chat.placeholderapi"
                );
            } catch (NoSuchMethodException e) {
                // Fall back to older methods
                hasPermission =
                    vaultPermission.has(player, "chat.placeholderapi") ||
                    vaultPermission.playerHas(
                        (String) null,
                        player,
                        "chat.placeholderapi"
                    );
            }
        } catch (Exception e) {
            Text.sendDebugLog(
                WARN,
                "Error checking permission: " + e.getMessage()
            );
            return text;
        }

        if (!hasPermission) {
            return text;
        }

        // Handle TPS placeholders specially for Folia
        boolean isFolia = false;
        try {
            isFolia = Bukkit.getServer().getClass().getName().contains("folia");
            if (isFolia) {
                text = text
                    .replaceAll("%server_tps_\\d+%", "N/A")
                    .replaceAll("%server_tps%", "N/A");
            }
        } catch (Exception e) {
            Text.sendDebugLog(
                WARN,
                "Error checking for Folia: " + e.getMessage()
            );
        }

        // Process %{placeholder}% format first
        Matcher matcher = placeholderPattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        boolean hasPlaceholders = false;

        while (matcher.find()) {
            hasPlaceholders = true;
            String placeholder = matcher.group(1);
            // Convert %{placeholder}% to %placeholder% for PAPI
            String papiPlaceholder = "%" + placeholder + "%";
            String replacement;
            try {
                replacement = PlaceholderAPI.setPlaceholders(
                    player,
                    papiPlaceholder
                );
                // If placeholder wasn't processed, keep the original %{placeholder}%
                if (replacement.equals(papiPlaceholder)) {
                    replacement = matcher.group(0);
                }
            } catch (Exception e) {
                Text.sendDebugLog(
                    WARN,
                    "Error processing placeholder " +
                        papiPlaceholder +
                        ": " +
                        e.getMessage()
                );
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(replacement)
            );
        }

        if (hasPlaceholders) {
            matcher.appendTail(buffer);
            text = buffer.toString();
        }

        // Process regular %placeholder% format if any placeholders remain
        if (text.contains("%")) {
            try {
                text = PlaceholderAPI.setPlaceholders(player, text);
            } catch (UnsupportedOperationException e) {
                if (
                    e.getMessage() != null &&
                    e.getMessage().contains("Not on any region")
                ) {
                    Text.sendDebugLog(
                        WARN,
                        "PlaceholderAPI placeholder not supported in this context: " +
                            text
                    );
                    text = text.replaceAll("%[^%]+%", "[Placeholder Error]");
                } else {
                    throw e;
                }
            }
        }

        return text;
    }

    /**
     * Extracts the last color/formatting code from a legacy-formatted string.
     * Returns it in & format (e.g. "&#ECC97D", "&a"). Returns empty string if none found.
     */
    private String extractTrailingColor(String text) {
        if (text == null || text.isEmpty()) return "";
        // Normalise § -> & so we work in a single format
        String s = text.replace('§', '&');
        String last = "";
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (
                    next == '#' &&
                    i + 7 < s.length() &&
                    s.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")
                ) {
                    // &#rrggbb
                    last = s.substring(i, i + 8);
                    i += 8;
                } else if (next == 'x' && i + 13 < s.length()) {
                    // &x&r&r&g&g&b&b Minecraft internal hex format
                    String candidate = s.substring(i, i + 14);
                    if (candidate.matches("&x(&[0-9a-fA-F]){6}")) {
                        String hex = candidate.replaceAll(
                            "&x(&([0-9a-fA-F])){6}",
                            "$2"
                        );
                        // Rebuild &#rrggbb from the six nibbles
                        StringBuilder hexColor = new StringBuilder("&#");
                        for (int j = 2; j < 14; j += 2) hexColor.append(
                            candidate.charAt(j + 1)
                        );
                        last = hexColor.toString();
                        i += 14;
                    } else {
                        i += 2;
                    }
                } else if (
                    "0123456789abcdefABCDEFklmnorKLMNOR".indexOf(next) >= 0
                ) {
                    last = "&" + next;
                    i += 2;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return last;
    }

    private String getPrefix(Player player) {
        String prefix = "";
        boolean debugMode = config.getBoolean("debug-mode");

        try {
            if (vaultChat != null) {
                prefix = vaultChat.getPlayerPrefix(player);
                if (prefix != null && prefix.trim().isEmpty()) {
                    if (debugMode) {
                        Text.sendDebugLog(
                            INFO,
                            "Empty prefix detected for " +
                                player.getName() +
                                ", returning null"
                        );
                    }
                    return null;
                }
                prefix = Objects.requireNonNullElse(prefix, "");

                // Clean up unwanted group names or formatting that might appear before the actual prefix
                if (prefix.contains("<") && prefix.contains(">")) {
                    // Remove patterns like <groupname> that appear before the actual prefix
                    prefix = prefix.replaceAll("<[^>]*>", "").trim();
                    if (debugMode) {
                        Text.sendDebugLog(
                            INFO,
                            "Cleaned prefix for " +
                                player.getName() +
                                ": removed group tags, result: '" +
                                prefix +
                                "'"
                        );
                    }
                }
            } else {
                if (debugMode) {
                    Text.sendDebugLog(
                        INFO,
                        "Vault Chat unavailable, using empty prefix for " +
                            player.getName()
                    );
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error getting prefix: " + e.getMessage());
        }

        return prefix;
    }

    private String getSuffix(Player player) {
        String suffix = "";
        boolean debugMode = config.getBoolean("debug-mode");

        try {
            if (vaultChat != null) {
                suffix = vaultChat.getPlayerSuffix(player);
                if (suffix != null && suffix.trim().isEmpty()) {
                    if (debugMode) {
                        Text.sendDebugLog(
                            INFO,
                            "Empty suffix detected for " +
                                player.getName() +
                                ", returning null"
                        );
                    }
                    return null;
                }
                suffix = Objects.requireNonNullElse(suffix, "");

                // Clean up unwanted group names or formatting that might appear before the actual suffix
                if (suffix.contains("<") && suffix.contains(">")) {
                    suffix = suffix.replaceAll("<[^>]*>", "").trim();
                    if (debugMode) {
                        Text.sendDebugLog(
                            INFO,
                            "Cleaned suffix for " +
                                player.getName() +
                                ": removed group tags, result: '" +
                                suffix +
                                "'"
                        );
                    }
                }
            } else {
                if (debugMode) {
                    Text.sendDebugLog(
                        INFO,
                        "Vault Chat unavailable, using empty suffix for " +
                            player.getName()
                    );
                }
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Error getting suffix: " + e.getMessage());
        }

        return suffix;
    }

    private String stripUnauthorizedFormatting(String message, Player player) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        boolean allowAnyColor =
            player.hasPermission("chat.color") ||
            player.hasPermission("chat.color.*");
        boolean allowAnyFormat =
            player.hasPermission("chat.format") ||
            player.hasPermission("chat.format.*");
        boolean allowHex =
            allowAnyColor || player.hasPermission("chat.color.hex");

        StringBuilder filteredMessage = new StringBuilder();
        int i = 0;

        while (i < message.length()) {
            if (
                i + 1 < message.length() &&
                (message.charAt(i) == '&' || message.charAt(i) == '\u00a7')
            ) {
                char colorChar = Character.toLowerCase(message.charAt(i + 1));
                boolean keepCode = false;

                switch (colorChar) {
                    case '0':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.black");
                        break;
                    case '1':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_blue");
                        break;
                    case '2':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_green");
                        break;
                    case '3':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_aqua");
                        break;
                    case '4':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_red");
                        break;
                    case '5':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_purple");
                        break;
                    case '6':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.gold");
                        break;
                    case '7':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.gray");
                        break;
                    case '8':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.dark_gray");
                        break;
                    case '9':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.blue");
                        break;
                    case 'a':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.green");
                        break;
                    case 'b':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.aqua");
                        break;
                    case 'c':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.red");
                        break;
                    case 'd':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.light_purple");
                        break;
                    case 'e':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.yellow");
                        break;
                    case 'f':
                        keepCode =
                            allowAnyColor ||
                            player.hasPermission("chat.color.white");
                        break;
                    case 'l':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.bold");
                        break;
                    case 'o':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.italic");
                        break;
                    case 'n':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.underline");
                        break;
                    case 'm':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.strikethrough");
                        break;
                    case 'k':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.magic");
                        break;
                    case 'r':
                        keepCode =
                            allowAnyFormat ||
                            player.hasPermission("chat.format.reset");
                        break;
                    default:
                        keepCode = false;
                        break;
                }

                if (keepCode) {
                    filteredMessage
                        .append(message.charAt(i))
                        .append(message.charAt(i + 1));
                    i += 2;
                } else {
                    i += 2;
                }
            } else if (
                i + 7 < message.length() &&
                message.charAt(i) == '&' &&
                message.charAt(i + 1) == '#' &&
                message.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")
            ) {
                if (allowHex) {
                    filteredMessage.append(message, i, i + 8);
                    i += 8;
                } else {
                    i += 8;
                }
            } else {
                filteredMessage.append(message.charAt(i));
                i++;
            }
        }

        return filteredMessage.toString();
    }

    /**
     * Restore protected raw text markers with their literal content
     * @param message The message containing protection markers
     * @return The message with markers replaced by literal raw text
     */
    private String restoreRawText(String message) {
        if (message == null || rawTextMappings.isEmpty()) {
            return message;
        }

        String result = message;
        for (Map.Entry<String, String> entry : rawTextMappings.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        // Clear mappings after use to prevent memory leaks
        rawTextMappings.clear();

        return result;
    }

    public void reload() {
        groupFormats.clear();
        loadGroupFormats();
    }
}
