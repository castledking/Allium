package net.survivalfun.core.managers.chat;

import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.INFO;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.WARN;

/**
 * First-pass word & phrase filter backed by chat/modules config files.
 * The default model is token and phrase matching to avoid substring false positives.
 */
public final class ChatFilterManager implements Listener {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final long SUPPRESS_DISCORD_RELAY_WINDOW_MS = 15_000L;
    private static final List<String> DEFAULT_CHAT_RESOURCES = List.of(
            "chat/modules.yml",
            "chat/modules/word-phrase-filter.yml",
            "chat/modules/spam-blocker.yml",
            "chat/modules/admin-notifier.yml",
            "chat/modules/discord-notifier.yml",
            "chat/modules/auto-punisher.yml",
            "chat/modules/link-ad-blocker.yml",
            "chat/modules/chat-cooldown.yml",
            "chat/modules/anti-chat-flood.yml",
            "chat/modules/unicode-filter.yml",
            "chat/modules/cap-limiter.yml",
            "chat/modules/anti-parrot.yml",
            "chat/modules/chat-executor.yml",
            "chat/modules/anti-statue-spambot.yml",
            "chat/modules/anti-join-flood.yml",
            "chat/modules/anti-relog-spam.yml",
            "chat/modules/anti-command-prefix.yml",
            "chat/modules/auto-grammar.yml",
            "chat/modules/command-spy.yml"
    );

    private final PluginStart plugin;
    private final File chatFolder;
    private final File modulesFolder;
    private final File modulesFile;
    private final Map<UUID, RecentBlockedMessage> recentBlockedMessages = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile Settings settings = Settings.disabled();
    private volatile List<Entry> entries = List.of();

    public ChatFilterManager(PluginStart plugin) {
        this.plugin = plugin;
        this.chatFolder = new File(plugin.getDataFolder(), "chat");
        this.modulesFolder = new File(chatFolder, "modules");
        this.modulesFile = new File(chatFolder, "modules.yml");
        ensureDefaults();
        reload();
    }

    public void reload() {
        ensureDefaults();

        YamlConfiguration modulesConfig = YamlConfiguration.loadConfiguration(modulesFile);
        ConfigurationSection moduleRoot = modulesConfig.getConfigurationSection("modules.word-phrase-filter");
        if (moduleRoot == null || !moduleRoot.getBoolean("enabled", true)) {
            enabled = false;
            settings = Settings.disabled();
            entries = List.of();
            return;
        }

        String relativeFile = moduleRoot.getString("file", "modules/word-phrase-filter.yml");
        File filterFile = new File(chatFolder, relativeFile);
        if (!filterFile.exists()) {
            ensureChatResource("chat/" + relativeFile);
        }

        YamlConfiguration filterConfig = YamlConfiguration.loadConfiguration(filterFile);
        enabled = filterConfig.getBoolean("enabled", true);
        settings = loadSettings(filterConfig);
        entries = loadEntries(filterConfig, settings);

        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[ChatFilter] Loaded " + entries.size() + " word/phrase entries from " + filterFile.getName());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public EvaluationResult evaluate(FilterContext context, String message) {
        if (!enabled || message == null || message.isBlank()) {
            return EvaluationResult.allow(message == null ? "" : message, context, settings);
        }
        if (!settings.appliesTo(context)) {
            return EvaluationResult.allow(message, context, settings);
        }

        PreparedMessage prepared = PreparedMessage.from(message, settings);
        List<MatchHit> hits = new ArrayList<>();
        for (Entry entry : entries) {
            MatchHit hit = entry.match(prepared);
            if (hit != null) {
                hits.add(hit);
            }
        }

        if (hits.isEmpty()) {
            return EvaluationResult.allow(message, context, settings, prepared);
        }

        return EvaluationResult.block(message, context, settings, prepared, hits);
    }

    public EvaluationResult dryRun(FilterContext context, String message) {
        return evaluate(context, message);
    }

    public void noteBlockedForDiscordRelay(UUID playerId, String message) {
        if (playerId == null || message == null || message.isBlank()) {
            return;
        }
        recentBlockedMessages.put(playerId, new RecentBlockedMessage(
                normalizeForRelay(message),
                System.currentTimeMillis()
        ));
    }

    public boolean shouldSuppressDiscordRelay(UUID playerId, String message) {
        if (playerId == null || message == null || message.isBlank()) {
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
        return recent.normalizedMessage().equals(normalizeForRelay(message));
    }

    public String getBlockedMessage(FilterContext context) {
        return switch (context) {
            case PLAYER_CHAT -> settings.blockedChatMessage();
            case PLAYER_COMMAND -> settings.blockedCommandMessage();
            case DISCORD_TO_MINECRAFT -> settings.blockedDiscordMessage();
        };
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAsyncChat(AsyncChatEvent event) {
        if (event.isCancelled() || !enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("allium.chatfilter.bypass")) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        EvaluationResult result = evaluate(FilterContext.PLAYER_CHAT, message);
        if (!result.blocked()) {
            return;
        }

        event.setCancelled(true);
        noteBlockedForDiscordRelay(player.getUniqueId(), message);
        player.sendMessage(Text.colorize(settings.blockedChatMessage()));
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[ChatFilter] Blocked chat from " + player.getName() + " via " + result.summary());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled() || !enabled || !settings.applyPlayerCommands()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("allium.chatfilter.bypass")) {
            return;
        }

        String raw = event.getMessage();
        if (raw == null || raw.length() <= 1) {
            return;
        }

        String noSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        int spaceIndex = noSlash.indexOf(' ');
        if (spaceIndex < 0 || spaceIndex >= noSlash.length() - 1) {
            return;
        }

        String content = noSlash.substring(spaceIndex + 1).trim();
        if (content.isEmpty()) {
            return;
        }

        EvaluationResult result = evaluate(FilterContext.PLAYER_COMMAND, content);
        if (!result.blocked()) {
            return;
        }

        event.setCancelled(true);
        noteBlockedForDiscordRelay(player.getUniqueId(), content);
        player.sendMessage(Text.colorize(settings.blockedCommandMessage()));
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[ChatFilter] Blocked command content from " + player.getName() + " via " + result.summary());
        }
    }

    public boolean shouldBlockDiscordInbound(DiscordGuildMessagePreProcessEvent event, String message) {
        if (!enabled || !settings.applyDiscordToMinecraft()) {
            return false;
        }
        EvaluationResult result = evaluate(FilterContext.DISCORD_TO_MINECRAFT, message);
        if (!result.blocked()) {
            return false;
        }

        event.setCancelled(true);
        if (plugin.isDebugMode()) {
            Text.sendDebugLog(INFO, "[ChatFilter] Blocked Discord inbound from #" + event.getChannel().getName() + " via " + result.summary());
        }
        return true;
    }

    private void ensureDefaults() {
        if (!chatFolder.exists()) {
            chatFolder.mkdirs();
        }
        if (!modulesFolder.exists()) {
            modulesFolder.mkdirs();
        }
        for (String resource : DEFAULT_CHAT_RESOURCES) {
            ensureChatResource(resource);
        }
    }

    private void ensureChatResource(String resourcePath) {
        String relativePath = resourcePath.startsWith("chat/") ? resourcePath.substring("chat/".length()) : resourcePath;
        File target = new File(chatFolder, relativePath);
        if (!target.exists()) {
            target.getParentFile().mkdirs();
            plugin.saveResource(resourcePath, false);
        }
    }

    private Settings loadSettings(YamlConfiguration config) {
        ConfigurationSection apply = config.getConfigurationSection("apply");
        ConfigurationSection normalization = config.getConfigurationSection("normalization");
        ConfigurationSection limits = config.getConfigurationSection("limits");
        ConfigurationSection messages = config.getConfigurationSection("messages");

        return new Settings(
                apply == null || apply.getBoolean("player-chat", true),
                apply != null && apply.getBoolean("player-commands", false),
                apply == null || apply.getBoolean("discord-to-minecraft", true),
                normalization == null || normalization.getBoolean("casefold", true),
                normalization == null || normalization.getBoolean("unicode-nfkc", true),
                normalization == null || normalization.getBoolean("substitutions", true),
                normalization == null || normalization.getBoolean("preserve-apostrophes", true),
                normalization == null || normalization.getBoolean("join-split-letters", true),
                limits == null ? 6 : Math.max(2, limits.getInt("max-phrase-tokens", 6)),
                messages == null ? "&cMessage blocked by chat filter." : messages.getString("blocked-chat", "&cMessage blocked by chat filter."),
                messages == null ? "&cCommand blocked by chat filter." : messages.getString("blocked-command", "&cCommand blocked by chat filter."),
                messages == null ? "&7[Allium] Discord message blocked by chat filter." : messages.getString("blocked-discord", "&7[Allium] Discord message blocked by chat filter.")
        );
    }

    private List<Entry> loadEntries(YamlConfiguration config, Settings settings) {
        List<Entry> loaded = new ArrayList<>();
        int sequence = 0;

        sequence = loadSectionEntries(config.getList("tokens"), MatchType.TOKEN, settings, loaded, sequence);
        sequence = loadSectionEntries(config.getList("phrases"), MatchType.PHRASE, settings, loaded, sequence);
        sequence = loadSectionEntries(config.getList("substrings"), MatchType.SUBSTRING, settings, loaded, sequence);
        sequence = loadSectionEntries(config.getList("regex"), MatchType.REGEX, settings, loaded, sequence);

        // Backward-compatible fallback for the original entries: [] format
        List<Map<?, ?>> rawEntries = config.getMapList("entries");
        for (Map<?, ?> rawEntry : rawEntries) {
            try {
                String value = stringValue(rawEntry.get("value"));
                if (value.isBlank()) {
                    continue;
                }

                String id = stringValue(rawEntry.get("id"));
                MatchType matchType = MatchType.from(stringValue(rawEntry.get("match")));
                Strength strength = Strength.from(stringValue(rawEntry.get("strength")));
                EvaluationTarget target = EvaluationTarget.from(stringValue(rawEntry.get("on")));
                List<String> except = readExceptionList(rawEntry.get("except"));

                Entry entry = Entry.compile(
                        id.isBlank() ? defaultEntryId(matchType, value, sequence++) : id,
                        value,
                        matchType,
                        strength,
                        target,
                        except,
                        settings
                );
                if (entry != null) {
                    loaded.add(entry);
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "[ChatFilter] Skipped invalid legacy entry: " + e.getMessage());
            }
        }

        return List.copyOf(loaded);
    }

    private int loadSectionEntries(List<?> rawValues, MatchType matchType, Settings settings, List<Entry> loaded, int sequenceStart) {
        if (rawValues == null || rawValues.isEmpty()) {
            return sequenceStart;
        }

        int sequence = sequenceStart;
        for (Object rawValue : rawValues) {
            try {
                Entry entry = parseSectionEntry(rawValue, matchType, settings, sequence);
                if (entry != null) {
                    loaded.add(entry);
                    sequence++;
                }
            } catch (Exception e) {
                Text.sendDebugLog(WARN, "[ChatFilter] Skipped invalid " + matchType.name().toLowerCase(Locale.ROOT) + " entry: " + e.getMessage());
            }
        }
        return sequence;
    }

    private Entry parseSectionEntry(Object rawValue, MatchType matchType, Settings settings, int sequence) {
        if (rawValue instanceof String stringEntry) {
            String value = stringEntry.trim();
            if (value.isBlank()) {
                return null;
            }
            return Entry.compile(
                    defaultEntryId(matchType, value, sequence),
                    value,
                    matchType,
                    Strength.NORMAL,
                    matchType == MatchType.REGEX ? EvaluationTarget.NORMALIZED : EvaluationTarget.NORMALIZED,
                    List.of(),
                    settings
            );
        }

        if (!(rawValue instanceof Map<?, ?> mapEntry)) {
            return null;
        }

        String value = stringValue(mapEntry.get("value"));
        if (value.isBlank()) {
            return null;
        }

        String id = stringValue(mapEntry.get("id"));
        Strength strength = Strength.from(stringValue(mapEntry.get("strength")));
        EvaluationTarget target = EvaluationTarget.from(stringValue(mapEntry.get("on")));
        List<String> except = readExceptionList(mapEntry.get("except"));

        return Entry.compile(
                id.isBlank() ? defaultEntryId(matchType, value, sequence) : id,
                value,
                matchType,
                strength,
                target,
                except,
                settings
        );
    }

    private List<String> readExceptionList(Object exceptValue) {
        if (!(exceptValue instanceof Collection<?> collection)) {
            return List.of();
        }

        List<String> except = new ArrayList<>();
        for (Object item : collection) {
            String text = stringValue(item);
            if (!text.isBlank()) {
                except.add(text);
            }
        }
        return except;
    }

    private String defaultEntryId(MatchType matchType, String value, int sequence) {
        return matchType.name().toLowerCase(Locale.ROOT) + "_" + sequence + "_" + value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalizeForRelay(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public enum FilterContext {
        PLAYER_CHAT,
        PLAYER_COMMAND,
        DISCORD_TO_MINECRAFT
    }

    public enum MatchType {
        TOKEN,
        PHRASE,
        SUBSTRING,
        REGEX;

        static MatchType from(String value) {
            return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
                case "phrase" -> PHRASE;
                case "substring" -> SUBSTRING;
                case "regex" -> REGEX;
                default -> TOKEN;
            };
        }
    }

    public enum Strength {
        EXACT,
        NORMAL,
        LOOSE;

        static Strength from(String value) {
            return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
                case "strict", "exact", "literal" -> EXACT;
                case "aggressive", "loose", "broad" -> LOOSE;
                default -> NORMAL;
            };
        }
    }

    public enum EvaluationTarget {
        NORMALIZED,
        RAW;

        static EvaluationTarget from(String value) {
            return "raw".equalsIgnoreCase(value) ? RAW : NORMALIZED;
        }
    }

    public record MatchHit(String entryId, MatchType matchType, String value, String detail) {}

    public record EvaluationResult(boolean blocked, FilterContext context, String rawMessage,
                                   PreparedMessage preparedMessage, List<MatchHit> hits, Settings settings) {

        static EvaluationResult allow(String message, FilterContext context, Settings settings) {
            return new EvaluationResult(false, context, message, PreparedMessage.from(message, settings), List.of(), settings);
        }

        static EvaluationResult allow(String message, FilterContext context, Settings settings, PreparedMessage prepared) {
            return new EvaluationResult(false, context, message, prepared, List.of(), settings);
        }

        static EvaluationResult block(String message, FilterContext context, Settings settings,
                                      PreparedMessage prepared, List<MatchHit> hits) {
            return new EvaluationResult(true, context, message, prepared, List.copyOf(hits), settings);
        }

        public String summary() {
            return hits.stream()
                    .map(hit -> hit.entryId() + "[" + hit.matchType().name().toLowerCase(Locale.ROOT) + "]")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("no hits");
        }
    }

    public record Settings(boolean applyPlayerChat,
                            boolean applyPlayerCommands,
                            boolean applyDiscordToMinecraft,
                            boolean casefold,
                            boolean unicodeNfkc,
                            boolean substitutions,
                            boolean preserveApostrophes,
                            boolean joinSplitLetters,
                            int maxPhraseTokens,
                            String blockedChatMessage,
                            String blockedCommandMessage,
                            String blockedDiscordMessage) {

        static Settings disabled() {
            return new Settings(false, false, false, true, true, true, true, true, 6,
                    "&cMessage blocked by chat filter.",
                    "&cCommand blocked by chat filter.",
                    "&7[Allium] Discord message blocked by chat filter.");
        }

        boolean appliesTo(FilterContext context) {
            return switch (context) {
                case PLAYER_CHAT -> applyPlayerChat;
                case PLAYER_COMMAND -> applyPlayerCommands;
                case DISCORD_TO_MINECRAFT -> applyDiscordToMinecraft;
            };
        }
    }

    public record PreparedMessage(Settings settings,
                                   String raw,
                                   String normalizedMessage,
                                   List<String> tokens,
                                   Set<String> joinedLetterRuns) {

        static PreparedMessage from(String message, Settings settings) {
            List<String> rawTokens = tokenize(message, settings.preserveApostrophes());
            List<String> normalizedTokens = new ArrayList<>(rawTokens.size());
            for (String rawToken : rawTokens) {
                String normalized = normalizeToken(rawToken, Strength.NORMAL, settings);
                if (!normalized.isBlank()) {
                    normalizedTokens.add(normalized);
                }
            }
            String normalizedMessage = String.join(" ", normalizedTokens);
            Set<String> joinedRuns = buildJoinedLetterRuns(normalizedTokens, settings.joinSplitLetters());
            return new PreparedMessage(settings, message, normalizedMessage, List.copyOf(rawTokens), Set.copyOf(joinedRuns));
        }

        private static List<String> tokenize(String input, boolean preserveApostrophes) {
            String working = input == null ? "" : input;
            if (!preserveApostrophes) {
                working = working.replace('\'', ' ');
            }

            Matcher matcher = TOKEN_PATTERN.matcher(working);
            List<String> tokens = new ArrayList<>();
            while (matcher.find()) {
                String token = matcher.group();
                token = trimApostrophes(token);
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
            return tokens;
        }

        private static Set<String> buildJoinedLetterRuns(List<String> normalizedTokens, boolean enabled) {
            if (!enabled) {
                return Set.of();
            }

            Set<String> runs = new LinkedHashSet<>();
            StringBuilder current = new StringBuilder();
            for (String token : normalizedTokens) {
                if (token.length() == 1) {
                    current.append(token);
                    continue;
                }
                if (current.length() >= 2) {
                    runs.add(current.toString());
                }
                current.setLength(0);
            }
            if (current.length() >= 2) {
                runs.add(current.toString());
            }
            return runs;
        }
    }

    private record Entry(String id,
                         String value,
                         MatchType matchType,
                         Strength strength,
                         EvaluationTarget target,
                         String normalizedValue,
                         List<String> normalizedPhraseTokens,
                         List<String> normalizedExceptions,
                         Pattern regexPattern) {

        static Entry compile(String id, String value, MatchType matchType, Strength strength,
                             EvaluationTarget target, List<String> exceptions, Settings settings) {
            if (matchType == MatchType.PHRASE) {
                List<String> phraseTokens = PreparedMessage.tokenize(value, settings.preserveApostrophes()).stream()
                        .map(token -> normalizeToken(token, strength, settings))
                        .filter(token -> !token.isBlank())
                        .toList();
                if (phraseTokens.isEmpty() || phraseTokens.size() > settings.maxPhraseTokens()) {
                    return null;
                }
                return new Entry(id, value, matchType, strength, target, String.join(" ", phraseTokens), phraseTokens,
                        normalizeExceptionList(exceptions, strength, settings), null);
            }

            if (matchType == MatchType.REGEX) {
                return new Entry(id, value, matchType, strength, target, value, List.of(),
                        normalizeExceptionList(exceptions, strength, settings), Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }

            String normalizedValue = normalizeToken(value, strength, settings);
            if (normalizedValue.isBlank()) {
                return null;
            }
            return new Entry(id, value, matchType, strength, target, normalizedValue, List.of(),
                    normalizeExceptionList(exceptions, strength, settings), null);
        }

        MatchHit match(PreparedMessage prepared) {
            if (isExcepted(prepared)) {
                return null;
            }

            return switch (matchType) {
                case TOKEN -> matchToken(prepared);
                case PHRASE -> matchPhrase(prepared);
                case SUBSTRING -> matchSubstring(prepared);
                case REGEX -> matchRegex(prepared);
            };
        }

        private MatchHit matchToken(PreparedMessage prepared) {
            for (String token : prepared.tokens()) {
                String normalized = normalizeToken(token, strength, prepared.settings());
                if (normalizedValue.equals(normalized)) {
                    return new MatchHit(id, matchType, value, "token=" + token);
                }
            }
            for (String run : prepared.joinedLetterRuns()) {
                String normalized = normalizeToken(run, strength, prepared.settings());
                if (normalizedValue.equals(normalized)) {
                    return new MatchHit(id, matchType, value, "joined-run=" + run);
                }
            }
            return null;
        }

        private MatchHit matchPhrase(PreparedMessage prepared) {
            if (prepared.tokens().size() < normalizedPhraseTokens.size()) {
                return null;
            }
            for (int i = 0; i <= prepared.tokens().size() - normalizedPhraseTokens.size(); i++) {
                boolean match = true;
                for (int j = 0; j < normalizedPhraseTokens.size(); j++) {
                    String token = normalizeToken(prepared.tokens().get(i + j), strength, prepared.settings());
                    if (!normalizedPhraseTokens.get(j).equals(token)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return new MatchHit(id, matchType, value, "phrase@" + i);
                }
            }
            return null;
        }

        private MatchHit matchSubstring(PreparedMessage prepared) {
            for (String token : prepared.tokens()) {
                String normalized = normalizeToken(token, strength, prepared.settings());
                if (normalized.contains(normalizedValue)) {
                    return new MatchHit(id, matchType, value, "substring-token=" + token);
                }
            }
            for (String run : prepared.joinedLetterRuns()) {
                String normalized = normalizeToken(run, strength, prepared.settings());
                if (normalized.contains(normalizedValue)) {
                    return new MatchHit(id, matchType, value, "substring-run=" + run);
                }
            }
            return null;
        }

        private MatchHit matchRegex(PreparedMessage prepared) {
            String subject = target == EvaluationTarget.RAW
                    ? prepared.raw()
                    : normalizeWholeMessage(prepared.raw(), strength, prepared.settings());
            if (regexPattern.matcher(subject).find()) {
                return new MatchHit(id, matchType, value, "regex");
            }
            return null;
        }

        private boolean isExcepted(PreparedMessage prepared) {
            if (normalizedExceptions.isEmpty()) {
                return false;
            }
            String normalizedMessage = normalizeWholeMessage(prepared.raw(), strength, prepared.settings());
            for (String exception : normalizedExceptions) {
                if (normalizedMessage.contains(exception)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record RecentBlockedMessage(String normalizedMessage, long timestamp) {}

    private static List<String> normalizeExceptionList(List<String> exceptions, Strength strength, Settings settings) {
        if (exceptions == null || exceptions.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String exception : exceptions) {
            String value = normalizeWholeMessage(exception, strength, settings);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeWholeMessage(String input, Strength strength, Settings settings) {
        List<String> tokens = PreparedMessage.tokenize(input, settings.preserveApostrophes());
        List<String> normalized = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String value = normalizeToken(token, strength, settings);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return String.join(" ", normalized);
    }

    private static String normalizeToken(String input, Strength strength, Settings settings) {
        String normalized = input == null ? "" : input;
        if (settings.unicodeNfkc()) {
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        }
        if (settings.casefold()) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        if (!settings.preserveApostrophes()) {
            normalized = normalized.replace('\'', ' ');
        }

        normalized = trimApostrophes(normalized);
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}']+", "");

        if (strength != Strength.EXACT && settings.substitutions()) {
            normalized = applySubstitutions(normalized);
        }
        if (strength == Strength.LOOSE) {
            normalized = normalized.replace("'", "");
        }
        return normalized.trim();
    }

    private static String trimApostrophes(String input) {
        String value = input == null ? "" : input;
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '\'') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '\'') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String applySubstitutions(String input) {
        if (input.isEmpty()) {
            return input;
        }
        Map<Character, Character> substitutions = new HashMap<>();
        substitutions.put('@', 'a');
        substitutions.put('4', 'a');
        substitutions.put('3', 'e');
        substitutions.put('0', 'o');
        substitutions.put('1', 'i');
        substitutions.put('!', 'i');
        substitutions.put('5', 's');
        substitutions.put('$', 's');
        substitutions.put('7', 't');
        substitutions.put('+', 't');

        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            out.append(substitutions.getOrDefault(c, c));
        }
        return out.toString();
    }

}
