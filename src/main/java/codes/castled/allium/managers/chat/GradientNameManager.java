package codes.castled.allium.managers.chat;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.util.SchedulerAdapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradientNameManager {

    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("(?i)[&§]#([A-F0-9]{6})");
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile("(?i)[&§]x([&§][A-F0-9]){6}");
    private static final Pattern MINI_HEX_PATTERN = Pattern.compile("(?i)(?<![&§])#([A-F0-9]{6})");
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)[&§]([0-9A-F])");
    private static final Pattern LEGACY_FORMAT_PATTERN = Pattern.compile("(?i)[&§][0-9A-FK-OR]");
    private static final BigDecimal PHASE_STEP = new BigDecimal("0.1");
    private static final BigDecimal PHASE_MAX = new BigDecimal("1.0");
    private static final BigDecimal PHASE_MIN = new BigDecimal("-1.0");
    private static final BigDecimal PHASE_NEGATE = new BigDecimal("-1.0");

    private final PluginStart plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final DecimalFormat phaseDecimalFormat;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AnimatedTabNameWriteCoordinator tabNameWriter =
            new AnimatedTabNameWriteCoordinator(new TabAnimatedNameWriter());
    private SchedulerAdapter.TaskHandle phaseTask;
    private volatile BigDecimal miniGradientPhase = PHASE_MIN;

    public GradientNameManager(PluginStart plugin) {
        this.plugin = plugin;
        this.phaseDecimalFormat = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.US));
        this.phaseDecimalFormat.setRoundingMode(RoundingMode.DOWN);
        this.phaseTask = SchedulerAdapter.runTimer(this::advancePhase, 1L, 1L);
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void shutdown() {
        if (phaseTask != null) {
            phaseTask.cancel();
            phaseTask = null;
        }
        tabNameWriter.releaseAll();
    }

    public String formatPhases(String text) {
        if (text == null || text.indexOf('#') == -1) {
            return text;
        }
        return text.replace("#phase-mm-g#", getPhaseValue(false))
                .replace("#-phase-mm-g#", getPhaseValue(true));
    }

    public String buildAnimatedGradientDisplayName(Player player) {
        if (player == null) {
            return "";
        }
        if (!player.hasPermission("allium.gradientname")) {
            return "%gradientdisplayname%";
        }

        String resolved = PlaceholderAPI.setPlaceholders(player, "%gradientdisplayname%");
        if (resolved == null || resolved.isBlank() || "%gradientdisplayname%".equals(resolved)) {
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

        String visibleName = stripFormatting(resolved);
        if (visibleName.isBlank()) {
            visibleName = player.getName();
        }

        return buildAnimatedGradientText(
                miniMessage.escapeTags(visibleName),
                colors,
                getPhaseValue(false)
        );
    }

    public static String buildAnimatedGradientText(String escapedVisibleName, List<String> colors, String phase) {
        String first = colors.isEmpty() ? "#FFFFFF" : colors.get(0);
        String last = colors.size() >= 2 ? colors.get(colors.size() - 1) : first;
        if (!colors.isEmpty() && colors.stream().allMatch(first::equalsIgnoreCase)) {
            // GradientPlus emits one color code per character even for solid
            // presets. Retain the old subtle animation by pairing that solid
            // color with its closest named color.
            last = nearestNamedColor(first);
        }
        // MiniMessage reverses the stop array for negative phases. With three or
        // more stops that changes the rendered path at phase zero, producing a
        // visible mid-cycle rewind. Two endpoint stops remain continuous.
        return "<gradient:" + first + ":" + last + ":" + phase + ">"
                + escapedVisibleName
                + "</gradient>";
    }

    private void advancePhase() {
        if (paused.get()) {
            return;
        }
        miniGradientPhase = miniGradientPhase.add(PHASE_STEP);
        if (miniGradientPhase.compareTo(PHASE_MAX) > 0) {
            miniGradientPhase = PHASE_MIN;
        }
        refreshPlayerListNames();
    }

    private void refreshPlayerListNames() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            tabNameWriter.releaseAll();
            return;
        }
        Set<UUID> tabOwnedThisFrame = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("allium.gradientname")) {
                continue;
            }
            if (refreshPlayerListName(player)) {
                tabOwnedThisFrame.add(player.getUniqueId());
            }
        }
        tabNameWriter.releaseInactive(tabOwnedThisFrame);
    }

    /** Reasserts the current frame after a tab-list entry has been recreated. */
    public boolean refreshPlayerListName(Player player) {
        if (player == null || !player.isOnline() || !player.hasPermission("allium.gradientname")) {
            return false;
        }

        String animatedName = buildAnimatedGradientDisplayName(player);
        if (animatedName == null || animatedName.isBlank() || "%gradientdisplayname%".equals(animatedName)) {
            return false;
        }

        return tabNameWriter.write(player.getUniqueId(), animatedName, () -> {
            String displayName = buildAnimatedTabDisplayName(player, animatedName);
            if (displayName != null && !displayName.isBlank() && !"%gradientdisplayname%".equals(displayName)) {
                player.playerListName(Text.colorize(displayName));
            }
        });
    }

    public String buildAnimatedTabDisplayName(Player player) {
        String animatedName = buildAnimatedGradientDisplayName(player);
        return buildAnimatedTabDisplayName(player, animatedName);
    }

    private String buildAnimatedTabDisplayName(Player player, String animatedName) {
        if (animatedName == null || animatedName.isBlank() || "%gradientdisplayname%".equals(animatedName)) {
            return animatedName;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return animatedName;
        }

        try {
            TabAffixes affixes = resolveTabAffixes(player);
            if (affixes != null) {
                String prefix = normalizeAffixFormatting(affixes.prefix());
                String suffix = normalizeAffixFormatting(affixes.suffix());
                return prefix + separatorAfterPrefix(prefix) + animatedName + suffix;
            }

            String combined = PlaceholderAPI.setPlaceholders(player, "%tab_tabprefix%" + animatedName + "%tab_tabsuffix%");
            if (combined == null || combined.isBlank() || combined.contains("%tab_")) {
                return animatedName;
            }
            return combined;
        } catch (Throwable ignored) {
            return animatedName;
        }
    }

    private TabAffixes resolveTabAffixes(Player player) {
        TabAffixes affixes = resolveTabAffixesFromApi(player);
        if (affixes != null) {
            return affixes;
        }
        return resolveTabAffixesFromSharedApi(player);
    }

    private TabAffixes resolveTabAffixesFromApi(Player player) {
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
            if (tabApi == null) {
                return null;
            }

            Object tabPlayer = tabApiClass.getMethod("getPlayer", java.util.UUID.class).invoke(tabApi, player.getUniqueId());
            if (tabPlayer == null) {
                return null;
            }

            Object tabListFormatManager = tabApiClass.getMethod("getTabListFormatManager").invoke(tabApi);
            if (tabListFormatManager == null) {
                return null;
            }

            Class<?> tabPlayerApiClass = Class.forName("me.neznamy.tab.api.TabPlayer");
            String prefix = (String) tabListFormatManager.getClass()
                    .getMethod("getOriginalReplacedPrefix", tabPlayerApiClass)
                    .invoke(tabListFormatManager, tabPlayer);
            String suffix = (String) tabListFormatManager.getClass()
                    .getMethod("getOriginalReplacedSuffix", tabPlayerApiClass)
                    .invoke(tabListFormatManager, tabPlayer);
            return new TabAffixes(prefix == null ? "" : prefix, suffix == null ? "" : suffix);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private TabAffixes resolveTabAffixesFromSharedApi(Player player) {
        try {
            Class<?> tabClass = Class.forName("me.neznamy.tab.shared.TAB");
            Object tab = tabClass.getMethod("getInstance").invoke(null);
            if (tab == null) {
                return null;
            }

            Object tabPlayer = tabClass.getMethod("getPlayer", java.util.UUID.class).invoke(tab, player.getUniqueId());
            if (tabPlayer == null) {
                return null;
            }

            Object tabListFormatManager = tabClass.getMethod("getTabListFormatManager").invoke(tab);
            if (tabListFormatManager == null) {
                return null;
            }

            String prefix = null;
            String suffix = null;
            for (java.lang.reflect.Method method : tabListFormatManager.getClass().getMethods()) {
                if (method.getParameterCount() != 1) {
                    continue;
                }
                if (method.getName().equals("getOriginalReplacedPrefix")) {
                    prefix = (String) method.invoke(tabListFormatManager, tabPlayer);
                } else if (method.getName().equals("getOriginalReplacedSuffix")) {
                    suffix = (String) method.invoke(tabListFormatManager, tabPlayer);
                }
            }
            if (prefix == null && suffix == null) {
                return null;
            }
            return new TabAffixes(prefix == null ? "" : prefix, suffix == null ? "" : suffix);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record TabAffixes(String prefix, String suffix) {
    }

    private String normalizeAffixFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String normalized = text;

        Matcher hexMatcher = LEGACY_HEX_PATTERN.matcher(normalized);
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(hexBuffer, Matcher.quoteReplacement("&#" + hexMatcher.group(1)));
        }
        hexMatcher.appendTail(hexBuffer);
        normalized = hexBuffer.toString();

        Matcher sectionHexMatcher = SECTION_HEX_PATTERN.matcher(normalized);
        StringBuffer sectionHexBuffer = new StringBuffer();
        while (sectionHexMatcher.find()) {
            String hex = sectionHexMatcher.group().substring(2).replace("§", "").replace("&", "");
            sectionHexMatcher.appendReplacement(sectionHexBuffer, Matcher.quoteReplacement("&#" + hex));
        }
        sectionHexMatcher.appendTail(sectionHexBuffer);
        normalized = sectionHexBuffer.toString();

        return normalized.replace('§', '&');
    }

    private String separatorAfterPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        String visible = visibleFormattingStripped(prefix);
        if (visible.isBlank() || Character.isWhitespace(visible.charAt(visible.length() - 1))) {
            return "";
        }
        return " ";
    }

    private String visibleFormattingStripped(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = SECTION_HEX_PATTERN.matcher(text).replaceAll("");
        stripped = LEGACY_HEX_PATTERN.matcher(stripped).replaceAll("");
        stripped = LEGACY_FORMAT_PATTERN.matcher(stripped).replaceAll("");
        stripped = stripped.replaceAll("(?i)<[^>]+>", "");
        return stripped;
    }

    private String getPhaseValue(boolean negative) {
        BigDecimal phase = negative ? miniGradientPhase.multiply(PHASE_NEGATE) : miniGradientPhase;
        synchronized (phaseDecimalFormat) {
            return phaseDecimalFormat.format(phase);
        }
    }

    private String getPrefix(Player player) {
        if (plugin.getVaultChat() == null) {
            return "";
        }
        try {
            String prefix = ((net.milkbowl.vault.chat.Chat) plugin.getVaultChat()).getPlayerPrefix(player);
            return prefix == null ? "" : prefix;
        } catch (Throwable ignored) {
            return "";
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
            tokens.add(new ColorToken(hexMatcher.start(), "#" + hexMatcher.group(1).toUpperCase(Locale.ROOT)));
            hexRanges.add(new int[] {hexMatcher.start(), hexMatcher.end()});
        }

        Matcher sectionHexMatcher = SECTION_HEX_PATTERN.matcher(input);
        while (sectionHexMatcher.find()) {
            String raw = sectionHexMatcher.group();
            String hex = raw.substring(2).replace("§", "").replace("&", "");
            tokens.add(new ColorToken(sectionHexMatcher.start(), "#" + hex.toUpperCase(Locale.ROOT)));
            hexRanges.add(new int[] {sectionHexMatcher.start(), sectionHexMatcher.end()});
        }

        Matcher miniHexMatcher = MINI_HEX_PATTERN.matcher(input);
        while (miniHexMatcher.find()) {
            tokens.add(new ColorToken(miniHexMatcher.start(), "#" + miniHexMatcher.group(1).toUpperCase(Locale.ROOT)));
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

    private String extractTrailingColor(String input) {
        List<String> colors = extractColors(input);
        return colors.isEmpty() ? null : colors.get(colors.size() - 1);
    }

    private String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return "#FFFFFF";
        }
        String trimmed = color.trim();
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String nearestNamedColor(String hex) {
        int rgb;
        try {
            rgb = Integer.parseInt(hex.substring(1), 16);
        } catch (RuntimeException ignored) {
            return hex;
        }

        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        NamedColor nearest = NamedColor.RED;
        long nearestDistance = Long.MAX_VALUE;
        for (NamedColor candidate : NamedColor.values()) {
            int candidateRed = (candidate.rgb >> 16) & 0xFF;
            int candidateGreen = (candidate.rgb >> 8) & 0xFF;
            int candidateBlue = candidate.rgb & 0xFF;
            long deltaRed = red - candidateRed;
            long deltaGreen = green - candidateGreen;
            long deltaBlue = blue - candidateBlue;
            long distance = deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest.tag;
    }

    private String stripFormatting(String input) {
        if (input == null) {
            return "";
        }
        String stripped = SECTION_HEX_PATTERN.matcher(input).replaceAll("");
        stripped = LEGACY_HEX_PATTERN.matcher(stripped).replaceAll("");
        stripped = LEGACY_COLOR_PATTERN.matcher(stripped).replaceAll("");
        stripped = stripped.replaceAll("(?i)<[^>]+>", "");
        return stripped;
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

    private record ColorToken(int index, String hex) {
    }

    private enum NamedColor {
        YELLOW("yellow", 0xFFFF55),
        GREEN("green", 0x55FF55),
        BLUE("blue", 0x5555FF),
        AQUA("aqua", 0x55FFFF),
        RED("red", 0xFF5555),
        GOLD("gold", 0xFFAA00),
        LIGHT_PURPLE("light_purple", 0xFF55FF),
        WHITE("white", 0xFFFFFF),
        GRAY("gray", 0xAAAAAA),
        DARK_RED("dark_red", 0xAA0000),
        DARK_PURPLE("dark_purple", 0xAA00AA),
        DARK_BLUE("dark_blue", 0x0000AA),
        DARK_GREEN("dark_green", 0x00AA00),
        DARK_AQUA("dark_aqua", 0x00AAAA);

        private final String tag;
        private final int rgb;

        NamedColor(String tag, int rgb) {
            this.tag = tag;
            this.rgb = rgb;
        }
    }

}
