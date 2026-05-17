package codes.castled.allium.managers.chat;

import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.INFO;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Intelligent-ish spam blocker focused on:
 * - singular spammy messages (elongated characters, low diversity, repeated words)
 * - repeated similar messages in a moving window
 * - optional command contexts
 * - DiscordSRV suppression for blocked outgoing chat
 */
public final class SpamBlockerManager implements Listener {

    private static final long SUPPRESS_DISCORD_RELAY_WINDOW_MS = 15_000L;
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    // Pattern to match Minecraft color codes: § followed by 0-9, a-f, k-o, r (legacy) or §x + 6 hex chars (hex colors)
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9A-Fa-fK-Ok-oRr]|§x(?:§[0-9A-Fa-f]){6}");

    private final PluginStart plugin;
    private final File chatFolder;
    private final File modulesFile;
    private final Map<UUID, Deque<MessageRecord>> messageHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> blockedTriggers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, RecentBlockedMessage> recentBlockedMessages = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile Settings settings = Settings.disabled();

    public SpamBlockerManager(PluginStart plugin) {
        this.plugin = plugin;
        this.chatFolder = new File(plugin.getDataFolder(), "chat");
        this.modulesFile = new File(chatFolder, "modules.yml");
        reload();
    }

    public void reload() {
        YamlConfiguration modulesConfig = YamlConfiguration.loadConfiguration(modulesFile);
        ConfigurationSection moduleRoot = modulesConfig.getConfigurationSection("modules.spam-blocker");
        if (moduleRoot == null || !moduleRoot.getBoolean("enabled", false)) {
            enabled = false;
            settings = Settings.disabled();
            return;
        }

        File configFile = new File(chatFolder, moduleRoot.getString("file", "modules/spam-blocker.yml"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        ConfigurationSection apply = config.getConfigurationSection("apply");
        ConfigurationSection repeatCollapse = config.getConfigurationSection("repeat-collapse");
        ConfigurationSection longWordLimit = config.getConfigurationSection("long-word-limit");
        ConfigurationSection temporaryMute = config.getConfigurationSection("temporary-mute");

        enabled = config.getBoolean("enabled", true);
        settings = new Settings(
                apply == null || apply.getBoolean("player-chat", true),
                apply != null && apply.getBoolean("player-commands", false),
                apply != null && apply.getBoolean("discord-to-minecraft", false),
                config.getBoolean("flood-combater", true),
                config.getBoolean("block-singular-message-spam", true),
                SingularMessageAction.from(config.getString("singular-message-action", "NORMALIZE")),
                Sensitivity.from(config.getString("singular-message-spam-processor-sensitivity", "NORMAL")),
                clamp(config.getDouble("block-repeated-message-similarity-threshold", 0.82d), 0.0d, 1.0d),
                Math.max(0, config.getInt("allowed-repeats", 4)),
                Math.max(1, config.getInt("repeat-cooldown-in-seconds", 15)),
                lowerCaseList(config.getStringList("phrase-whitelist")),
                clamp(config.getDouble("phrase-whitelist-similarity-threshold", 0.65d), 0.0d, 1.0d),
                normalizeCommands(config.getStringList("affected-commands")),
                repeatCollapse != null && repeatCollapse.getBoolean("enabled", true),
                repeatCollapse == null ? 3 : Math.max(2, repeatCollapse.getInt("threshold", 3)),
                repeatCollapse == null ? 1 : Math.max(1, repeatCollapse.getInt("replacement-count", 1)),
                longWordLimit != null && longWordLimit.getBoolean("enabled", false),
                longWordLimit == null ? 24 : Math.max(8, longWordLimit.getInt("length", 24)),
                temporaryMute != null && temporaryMute.getBoolean("enabled", false),
                temporaryMute == null ? 3 : Math.max(1, temporaryMute.getInt("trigger-threshold", 3)),
                temporaryMute == null ? 10 : Math.max(1, temporaryMute.getInt("duration-seconds", 10)),
                temporaryMute == null ? 30 : Math.max(5, temporaryMute.getInt("window-seconds", 30)),
                config.getString("messages.blocked-chat", "&cMessage blocked as spam."),
                config.getString("messages.blocked-command", "&cCommand blocked as spam."),
                config.getString("messages.blocked-discord", "&7[Allium] Discord message blocked as spam."),
                config.getString("messages.muted", "&cYou are temporarily muted for spam. Try again in {time}s.")
        );

        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[SpamBlocker] Loaded spam-blocker.yml");
        }
    }

    public boolean shouldSuppressDiscordRelay(UUID playerId, String message) {
        if (!enabled || playerId == null || message == null || message.isBlank()) {
            return false;
        }
        RecentBlockedMessage recent = recentBlockedMessages.get(playerId);
        if (recent == null) {
            return false;
        }
        if (System.currentTimeMillis() - recent.timestamp() > SUPPRESS_DISCORD_RELAY_WINDOW_MS) {
            recentBlockedMessages.remove(playerId);
            return false;
        }
        return recent.normalized().equals(normalizeForRelay(message));
    }

    public boolean shouldBlockDiscordInbound(DiscordGuildMessagePreProcessEvent event, String message) {
        if (!enabled || !settings.applyDiscordToMinecraft()) {
            return false;
        }
        Evaluation evaluation = evaluate(null, message, SpamContext.DISCORD_TO_MINECRAFT, null);
        if (!evaluation.blocked()) {
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    public String rewriteForDiscordRelay(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return message;
        }
        if (!settings.blockSingularMessageSpam() || settings.singularMessageAction() != SingularMessageAction.NORMALIZE) {
            return message;
        }
        String normalized = normalizeForComparison(message.trim());
        if (normalized.isBlank() || normalized.equals(message.trim())) {
            return message;
        }
        return isSingularSpam(message, normalized) ? normalized : message;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAsyncChat(AsyncChatEvent event) {
        if (!enabled || event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Evaluation evaluation = evaluate(player.getUniqueId(), message, SpamContext.PLAYER_CHAT, null);
        if (!evaluation.blocked()) {
            if (evaluation.rewritten() != null && !evaluation.rewritten().equals(message)) {
                event.message(net.kyori.adventure.text.Component.text(evaluation.rewritten()));
            }
            recordMessage(player.getUniqueId(), evaluation.normalized());
            return;
        }

        event.setCancelled(true);
        noteBlockedForRelay(player.getUniqueId(), message);
        player.sendMessage(Text.colorize(evaluation.messageToSender()));

        // Broadcast alert to staff
        if (plugin.getSecurityAlertManager() != null) {
            plugin.getSecurityAlertManager().broadcastChatFilterAlert(player, message, "Spam Blocker", "blocked as spam");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || event.isCancelled() || !settings.applyPlayerCommands()) {
            return;
        }
        Player player = event.getPlayer();

        String raw = event.getMessage();
        if (raw == null || raw.length() <= 1) {
            return;
        }
        String noSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        int space = noSlash.indexOf(' ');
        if (space < 0 || space >= noSlash.length() - 1) {
            return;
        }
        String baseCommand = "/" + noSlash.substring(0, space).toLowerCase(Locale.ROOT);
        if (!settings.affectedCommands().isEmpty() && !settings.affectedCommands().contains(baseCommand)) {
            return;
        }

        String content = noSlash.substring(space + 1).trim();
        Evaluation evaluation = evaluate(player.getUniqueId(), content, SpamContext.PLAYER_COMMAND, baseCommand);
        if (!evaluation.blocked()) {
            if (evaluation.rewritten() != null && !evaluation.rewritten().equals(content)) {
                event.setMessage(baseCommand + " " + evaluation.rewritten());
            }
            recordMessage(player.getUniqueId(), evaluation.normalized());
            return;
        }

        event.setCancelled(true);
        noteBlockedForRelay(player.getUniqueId(), content);
        player.sendMessage(Text.colorize(evaluation.messageToSender()));

        // Broadcast alert to staff
        if (plugin.getSecurityAlertManager() != null) {
            plugin.getSecurityAlertManager().broadcastChatFilterAlert(player, content, "Spam Blocker", "blocked command as spam");
        }
    }

    private Evaluation evaluate(UUID playerId, String message, SpamContext context, String commandBase) {
        String cleaned = message == null ? "" : message.trim();
        if (cleaned.isBlank()) {
            return Evaluation.allow(cleaned, cleaned, cleaned);
        }

        long now = System.currentTimeMillis();
        if (playerId != null) {
            long mutedExpiry = mutedUntil.getOrDefault(playerId, 0L);
            if (mutedExpiry > now) {
                long seconds = Math.max(1L, (mutedExpiry - now + 999L) / 1000L);
                return Evaluation.block(normalizeForComparison(cleaned), settings.mutedMessage().replace("{time}", String.valueOf(seconds)));
            }
        }

        String normalized = normalizeForComparison(cleaned);

        if (settings.blockSingularMessageSpam() && isSingularSpam(cleaned, normalized)) {
            if (settings.singularMessageAction() == SingularMessageAction.NORMALIZE && !normalized.isBlank() && !normalized.equals(cleaned)) {
                return Evaluation.allow(cleaned, normalized, normalized);
            }
            handleBlockTrigger(playerId, now);
            return Evaluation.block(normalized, messageForContext(context));
        }

        if (playerId != null && !isWhitelisted(normalized)) {
            purgeHistory(playerId, now);
            int repeats = countSimilarRecentMessages(playerId, normalized);
            if (repeats >= settings.allowedRepeats()) {
                handleBlockTrigger(playerId, now);
                return Evaluation.block(normalized, messageForContext(context));
            }
        }

        return Evaluation.allow(cleaned, normalized, cleaned);
    }

    private boolean isSingularSpam(String rawMessage, String normalized) {
        if (normalized.isBlank()) {
            return false;
        }

        SensitivityThreshold threshold = settings.sensitivity().threshold();
        if (settings.longWordLimitEnabled()) {
            for (String token : splitTokens(normalized)) {
                if (token.length() >= settings.longWordLength()) {
                    return true;
                }
            }
        }

        int longestRun = longestRepeatedCharRun(rawMessage);
        if (longestRun >= threshold.longestRunThreshold()) {
            return true;
        }

        List<String> tokens = splitTokens(normalized);
        boolean hasHealthyTokenDiversity = false;
        if (tokens.size() >= threshold.minimumTokenCount()) {
            double uniqueTokenRatio = uniqueRatio(tokens);
            // Scale diversity threshold for longer messages (more lenient)
            double adjustedMaxTokenDiversity = scaleThresholdForLength(threshold.maxTokenDiversityRatio(), tokens.size(), 10, 30);
            if (uniqueTokenRatio <= adjustedMaxTokenDiversity) {
                return true;
            }
            // Scale max token frequency for longer messages (allow more repeats)
            int adjustedMaxTokenFreq = scaleMaxFrequencyForLength(threshold.maxTokenFrequency(), tokens.size(), 10, 30);
            if (maxTokenFrequency(tokens) >= adjustedMaxTokenFreq) {
                return true;
            }
            // Token-diversity passed with a meaningful multi-word sentence - treat as natural language.
            hasHealthyTokenDiversity = true;
        }

        // Character-diversity check catches single-word spam like "aaaaaaaa" or "asdfasdfasdf".
        // Skip it for genuine multi-word sentences: English has only 26 letters, so
        // uniqueChars/totalChars is mathematically capped at 26/length. Any natural
        // sentence over ~80-100 chars would otherwise falsely trip the NORMAL threshold
        // of 0.26 (e.g. 26/100 = 0.26). If token-diversity already vouched for the
        // message being a varied multi-word sentence, don't apply the char-diversity gate.
        if (!hasHealthyTokenDiversity && normalized.length() >= threshold.minimumCharacterSample()) {
            double uniqueCharRatio = uniqueCharacterRatio(normalized);
            if (uniqueCharRatio <= threshold.maxCharacterDiversityRatio()) {
                return true;
            }
        }

        return false;
    }

    private void recordMessage(UUID playerId, String normalized) {
        if (playerId == null || normalized.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        purgeHistory(playerId, now);
        messageHistory.computeIfAbsent(playerId, ignored -> new ArrayDeque<>())
                .addLast(new MessageRecord(normalized, now));
    }

    private void purgeHistory(UUID playerId, long now) {
        Deque<MessageRecord> history = messageHistory.get(playerId);
        if (history == null) {
            return;
        }
        long cutoff = now - (settings.repeatCooldownSeconds() * 1000L);
        while (!history.isEmpty() && history.peekFirst().timestamp() < cutoff) {
            history.removeFirst();
        }
        if (history.isEmpty()) {
            messageHistory.remove(playerId);
        }
    }

    private int countSimilarRecentMessages(UUID playerId, String normalizedMessage) {
        Deque<MessageRecord> history = messageHistory.get(playerId);
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int similar = 0;
        for (MessageRecord prior : history) {
            if (similarity(prior.normalized(), normalizedMessage) >= settings.repeatedMessageSimilarityThreshold()) {
                similar++;
            }
        }
        return similar;
    }

    private boolean isWhitelisted(String normalizedMessage) {
        for (String phrase : settings.phraseWhitelist()) {
            if (similarity(phrase, normalizedMessage) >= settings.phraseWhitelistSimilarityThreshold()) {
                return true;
            }
        }
        return false;
    }

    private void handleBlockTrigger(UUID playerId, long now) {
        if (playerId == null) {
            return;
        }
        if (settings.temporaryMuteEnabled()) {
            Deque<Long> triggers = blockedTriggers.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
            long cutoff = now - (settings.temporaryMuteWindowSeconds() * 1000L);
            while (!triggers.isEmpty() && triggers.peekFirst() < cutoff) {
                triggers.removeFirst();
            }
            triggers.addLast(now);
            if (triggers.size() >= settings.temporaryMuteTriggerThreshold()) {
                mutedUntil.put(playerId, now + (settings.temporaryMuteDurationSeconds() * 1000L));
                triggers.clear();
            }
        }
    }

    private void noteBlockedForRelay(UUID playerId, String rawMessage) {
        if (playerId == null || rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        recentBlockedMessages.put(playerId, new RecentBlockedMessage(normalizeForRelay(rawMessage), System.currentTimeMillis()));
    }

    private String normalizeForComparison(String input) {
        String working = input;
        // Strip Minecraft color codes first to prevent garbage like "x e a d e 4 c" in Discord
        working = COLOR_CODE_PATTERN.matcher(working).replaceAll("");
        if (settings.repeatCollapseEnabled()) {
            working = collapseRepeatedCharacters(working, settings.repeatCollapseThreshold(), settings.repeatCollapseReplacementCount());
        }
        working = Normalizer.normalize(working, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // Keep letters, numbers, whitespace, apostrophe, and @ (for Discord mentions)
        working = working.replaceAll("[^\\p{L}\\p{N}\\s'@]", " ");
        working = SPACE_PATTERN.matcher(working).replaceAll(" ").trim();

        if (settings.floodCombater()) {
            List<String> tokens = splitTokens(working);
            if (!tokens.isEmpty()) {
                int lastIndex = tokens.size() - 1;
                String trailing = tokens.get(lastIndex);
                if (isLikelyFloodSuffix(trailing) && tokens.size() > 1) {
                    tokens.remove(lastIndex);
                    working = String.join(" ", tokens);
                }
            }
        }
        return working;
    }

    private boolean isLikelyFloodSuffix(String token) {
        if (token.isBlank() || token.length() > 6) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c)) hasDigit = true;
            if (Character.isLetter(c)) hasLetter = true;
        }
        return (hasDigit && hasLetter) || (hasDigit && token.length() <= 4);
    }

    private List<String> splitTokens(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] split = input.split(" ");
        List<String> tokens = new ArrayList<>(split.length);
        for (String token : split) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0d;
        }
        if (a.equals(b)) {
            return 1.0d;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0d;
        }
        int distance = levenshtein(a, b);
        return Math.max(0.0d, 1.0d - ((double) distance / (double) max));
    }

    private static int levenshtein(String left, String right) {
        int[] costs = new int[right.length() + 1];
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            costs[0] = i;
            int northwest = i - 1;
            for (int j = 1; j <= right.length(); j++) {
                int current = costs[j];
                int value = left.charAt(i - 1) == right.charAt(j - 1)
                        ? northwest
                        : 1 + Math.min(Math.min(costs[j - 1], current), northwest);
                costs[j] = value;
                northwest = current;
            }
        }
        return costs[right.length()];
    }

    private int longestRepeatedCharRun(String input) {
        int longest = 0;
        int current = 0;
        char previous = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toLowerCase(input.charAt(i));
            if (!Character.isLetterOrDigit(c)) {
                current = 0;
                previous = 0;
                continue;
            }
            if (c == previous) {
                current++;
            } else {
                previous = c;
                current = 1;
            }
            if (current > longest) {
                longest = current;
            }
        }
        return longest;
    }

    private double uniqueRatio(List<String> tokens) {
        if (tokens.isEmpty()) {
            return 1.0d;
        }
        return (double) new HashSet<>(tokens).size() / (double) tokens.size();
    }

    private int maxTokenFrequency(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        int max = 0;
        for (String token : tokens) {
            int count = counts.merge(token, 1, Integer::sum);
            if (count > max) {
                max = count;
            }
        }
        return max;
    }

    private double uniqueCharacterRatio(String input) {
        int total = 0;
        Set<Character> unique = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            unique.add(c);
        }
        if (total == 0) {
            return 1.0d;
        }
        return (double) unique.size() / (double) total;
    }

    /**
     * Scales a threshold ratio to be more lenient for longer messages.
     * For messages shorter than minLen, uses base threshold.
     * For messages longer than maxLen, uses base + increase.
     * Linear interpolation between minLen and maxLen.
     */
    private static double scaleThresholdForLength(double baseThreshold, int tokenCount, int minLen, int maxLen) {
        if (tokenCount <= minLen) {
            return baseThreshold;
        }
        if (tokenCount >= maxLen) {
            return baseThreshold + 0.15d; // Increase by 0.15 for long messages
        }
        double factor = (double) (tokenCount - minLen) / (double) (maxLen - minLen);
        return baseThreshold + (0.15d * factor);
    }

    /**
     * Scales max token frequency to allow more repeats in longer messages.
     * For messages shorter than minLen, uses base frequency.
     * For messages longer than maxLen, uses base + increase.
     */
    private static int scaleMaxFrequencyForLength(int baseFreq, int tokenCount, int minLen, int maxLen) {
        if (tokenCount <= minLen) {
            return baseFreq;
        }
        if (tokenCount >= maxLen) {
            return baseFreq + 3; // Allow 3 more repeats for long messages
        }
        double factor = (double) (tokenCount - minLen) / (double) (maxLen - minLen);
        return baseFreq + (int) Math.round(3 * factor);
    }

    private static String collapseRepeatedCharacters(String input, int threshold, int replacementCount) {
        StringBuilder out = new StringBuilder(input.length());
        char previous = 0;
        int run = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.toLowerCase(c) == Character.toLowerCase(previous)) {
                run++;
            } else {
                previous = c;
                run = 1;
            }

            if (run <= replacementCount || run < threshold) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalizeForRelay(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> lowerCaseList(List<String> input) {
        List<String> output = new ArrayList<>();
        for (String value : input) {
            if (value != null && !value.isBlank()) {
                output.add(value.toLowerCase(Locale.ROOT).trim());
            }
        }
        return List.copyOf(output);
    }

    private static Set<String> normalizeCommands(List<String> commands) {
        Set<String> output = new HashSet<>();
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String normalized = command.trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            output.add(normalized);
        }
        return Set.copyOf(output);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String messageForContext(SpamContext context) {
        return switch (context) {
            case PLAYER_CHAT -> settings.blockedChatMessage();
            case PLAYER_COMMAND -> settings.blockedCommandMessage();
            case DISCORD_TO_MINECRAFT -> settings.blockedDiscordMessage();
        };
    }

    private enum SpamContext {
        PLAYER_CHAT,
        PLAYER_COMMAND,
        DISCORD_TO_MINECRAFT
    }

    private record MessageRecord(String normalized, long timestamp) {}

    private record RecentBlockedMessage(String normalized, long timestamp) {}

    private record Evaluation(boolean blocked, String normalized, String messageToSender, String rewritten) {
        static Evaluation allow(String raw, String normalized, String rewritten) {
            return new Evaluation(false, normalized, raw, rewritten);
        }

        static Evaluation block(String normalized, String messageToSender) {
            return new Evaluation(true, normalized, messageToSender, null);
        }
    }

    private record Settings(boolean applyPlayerChat,
                            boolean applyPlayerCommands,
                            boolean applyDiscordToMinecraft,
                            boolean floodCombater,
                            boolean blockSingularMessageSpam,
                            SingularMessageAction singularMessageAction,
                            Sensitivity sensitivity,
                            double repeatedMessageSimilarityThreshold,
                            int allowedRepeats,
                            int repeatCooldownSeconds,
                            List<String> phraseWhitelist,
                            double phraseWhitelistSimilarityThreshold,
                            Set<String> affectedCommands,
                            boolean repeatCollapseEnabled,
                            int repeatCollapseThreshold,
                            int repeatCollapseReplacementCount,
                            boolean longWordLimitEnabled,
                            int longWordLength,
                            boolean temporaryMuteEnabled,
                            int temporaryMuteTriggerThreshold,
                            int temporaryMuteDurationSeconds,
                            int temporaryMuteWindowSeconds,
                            String blockedChatMessage,
                            String blockedCommandMessage,
                            String blockedDiscordMessage,
                            String mutedMessage) {
        static Settings disabled() {
            return new Settings(false, false, false, true, true, SingularMessageAction.NORMALIZE, Sensitivity.NORMAL, 0.82d, 4, 15,
                    List.of(), 0.65d, Set.of(), true, 3, 1, false, 24, false, 3, 10, 30,
                    "&cMessage blocked as spam.",
                    "&cCommand blocked as spam.",
                    "&7[Allium] Discord message blocked as spam.",
                    "&cYou are temporarily muted for spam. Try again in {time}s.");
        }
    }

    private enum SingularMessageAction {
        BLOCK,
        NORMALIZE;

        static SingularMessageAction from(String value) {
            return switch (value == null ? "" : value.trim().toUpperCase(Locale.ROOT)) {
                case "BLOCK" -> BLOCK;
                default -> NORMALIZE;
            };
        }
    }

    private enum Sensitivity {
        LOWEST(new SensitivityThreshold(10, 0.15d, 0.18d, 6, 6, 4)),
        LOW(new SensitivityThreshold(8, 0.20d, 0.20d, 5, 5, 4)),
        NORMAL(new SensitivityThreshold(6, 0.26d, 0.23d, 4, 5, 4)),
        HIGH(new SensitivityThreshold(5, 0.32d, 0.26d, 4, 4, 3)),
        HIGHEST(new SensitivityThreshold(4, 0.40d, 0.30d, 3, 4, 3));

        private final SensitivityThreshold threshold;

        Sensitivity(SensitivityThreshold threshold) {
            this.threshold = threshold;
        }

        SensitivityThreshold threshold() {
            return threshold;
        }

        static Sensitivity from(String value) {
            return switch (value == null ? "" : value.trim().toUpperCase(Locale.ROOT)) {
                case "LOWEST" -> LOWEST;
                case "LOW" -> LOW;
                case "HIGH" -> HIGH;
                case "HIGHEST" -> HIGHEST;
                default -> NORMAL;
            };
        }
    }

    private record SensitivityThreshold(int longestRunThreshold,
                                        double maxCharacterDiversityRatio,
                                        double maxTokenDiversityRatio,
                                        int maxTokenFrequency,
                                        int minimumTokenCount,
                                        int minimumCharacterSample) {}
}
