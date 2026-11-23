package codes.castled.allium.placeholder;

import codes.castled.allium.PluginStart;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.module.ChatModule;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.PlayerInfoModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Allium's PlaceholderAPI placeholders ({@code %allium_<name>%}), with legacy Allium's names and
 * formats. A request on the server thread is answered live; one from another thread (a tab or
 * scoreboard plugin refreshing asynchronously, async chat) gets the value the server thread
 * computed within the last second, because most of the state is owned by the server thread.
 * Balances, the top accounts and mail counts come from snapshots their own workers refresh every
 * {@value #STORAGE_REFRESH_MILLIS} ms, so no placeholder ever reads storage.
 */
public final class AlliumPlaceholders {

    static final long SNAPSHOT_MILLIS = 1_000;
    static final long STORAGE_REFRESH_MILLIS = 10_000;

    /** The per-player placeholders, kept in each second's snapshot. */
    public static final List<String> PLAYER_KEYS = List.of(
            "nickname", "nickname_raw", "gradientdisplayname", "displayname", "ping",
            "fly", "fly_time", "fly_time_short", "gamemode", "playtime", "playtime_short", "firstjoindate",
            "god", "vanished", "afk", "muted", "jailed", "frozen",
            "reply", "spy", "mail_unread", "mail_gifts",
            "teleport_toggled", "teleport_requests", "teleport_cooldown",
            "homes_set", "homes_max", "home_set", "home_max",
            "world", "world_time", "world_time_24", "world_time_24h", "world_date",
            "balance", "balance_fixed", "balance_commas", "balance_formatted", "balance_display",
            "baltop_rank", "baltop_rank_formatted");

    private final PluginStart plugin;
    private final Map<UUID, Map<String, String>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Function<Player, String>> extra = new ConcurrentHashMap<>();
    private TaskHandle snapshot = TaskHandle.NONE;
    private TaskHandle storage = TaskHandle.NONE;
    private AutoCloseable expansion;

    public AlliumPlaceholders(PluginStart plugin) {
        this.plugin = plugin;
    }

    /** Registers {@code %allium_...%} when PlaceholderAPI is enabled; a no-op otherwise. */
    public void start() {
        if (plugin.getServer().getPluginManager() == null || !plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            expansion = AlliumExpansion.register(plugin, this);
        } catch (LinkageError | RuntimeException failed) {
            plugin.logger().warning("Allium's PlaceholderAPI placeholders could not be registered: " + failed.getMessage());
            return;
        }
        snapshot = plugin.getScheduler().repeatOnServer(() -> {
            refreshSnapshots();
            return true;
        }, SNAPSHOT_MILLIS, SNAPSHOT_MILLIS);
        storage = plugin.getScheduler().repeatOnServer(() -> {
            refreshStorage();
            return true;
        }, 500, STORAGE_REFRESH_MILLIS);
        plugin.logger().info("PlaceholderAPI placeholders registered as %allium_<name>%.");
    }

    public void stop() {
        snapshot.cancel();
        storage.cancel();
        snapshot = storage = TaskHandle.NONE;
        if (expansion != null) {
            try {
                expansion.close();
            } catch (Exception ignored) {
                // PlaceholderAPI is shutting down too
            }
            expansion = null;
        }
        snapshots.clear();
    }

    /** Lets another part of Allium answer {@code %allium_<key>%} for a player (server thread). */
    public void register(String key, Function<Player, String> value) {
        extra.put(key.toLowerCase(Locale.ROOT), value);
    }

    /** PlaceholderAPI's request: live on the server thread, the last snapshot elsewhere; null when unknown. */
    @Nullable
    public String request(@Nullable OfflinePlayer target, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith("baltop_") && !key.startsWith("baltop_rank")) {
            return baltop(key);
        }
        if (key.startsWith("worth_")) {
            return worth(key.substring("worth_".length()));
        }
        if (!PLAYER_KEYS.contains(key) && !extra.containsKey(key)) {
            return null;
        }
        Player player = target == null ? null : target.getPlayer();
        if (player == null) {
            return "";
        }
        boolean ownsPlayer = BukkitServerThread.isFolia()
                ? BukkitServerThread.entity(plugin, player).isServerThread()
                : plugin.getScheduler().isServerThread();
        if (ownsPlayer) {
            String value = compute(player, key);
            return value == null ? "" : value;
        }
        Map<String, String> values = snapshots.get(player.getUniqueId());
        return values == null ? "" : values.getOrDefault(key, "");
    }

    void refreshSnapshots() {
        var online = plugin.getServer().getOnlinePlayers();
        if (BukkitServerThread.isFolia()) {
            java.util.Set<UUID> alive = new java.util.HashSet<>();
            for (Player player : online) {
                alive.add(player.getUniqueId());
                BukkitServerThread.onOwner(plugin, plugin.getScheduler(), player, () -> {
                    if (player.isOnline()) snapshots.put(player.getUniqueId(), snapshotPlayer(player));
                });
            }
            snapshots.keySet().retainAll(alive);
            return;
        }
        Map<UUID, Map<String, String>> next = new HashMap<>();
        for (Player player : online) {
            next.put(player.getUniqueId(), snapshotPlayer(player));
        }
        snapshots.keySet().retainAll(next.keySet());
        snapshots.putAll(next);
    }

    private Map<String, String> snapshotPlayer(Player player) {
        Map<String, String> values = new HashMap<>();
        for (String key : PLAYER_KEYS) {
            String value = compute(player, key);
            values.put(key, value == null ? "" : value);
        }
        extra.forEach((key, fn) -> {
            String value = safe(() -> fn.apply(player));
            values.put(key, value == null ? "" : value);
        });
        return Map.copyOf(values);
    }

    void refreshStorage() {
        List<UUID> online = plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        EconomyRuntime economy = economy();
        if (economy != null) {
            economy.refreshPlaceholders(online);
        }
        if (plugin.getModuleManager().getModule("mail") instanceof MailModule mail && mail.isEnabled() && mail.mail() != null) {
            mail.mail().refreshCounts(online);
        }
    }

    /** Server thread. */
    @Nullable
    String compute(Player p, String key) {
        Function<Player, String> registered = extra.get(key);
        if (registered != null) {
            return safe(() -> registered.apply(p));
        }
        return safe(() -> switch (key) {
            case "nickname" -> nickname(p, false);
            case "nickname_raw" -> TextFormatter.parse(nickname(p, false)).toPlainText();
            case "gradientdisplayname" -> nickname(p, true);
            case "displayname" -> p.getDisplayName();
            case "ping" -> Integer.toString(p.getPing());
            case "fly" -> fly(p);
            case "fly_time" -> PlaceholderFormats.duration(timed() == null ? 0 : timed().seconds(p.getUniqueId()));
            case "fly_time_short" -> PlaceholderFormats.durationShort(timed() == null ? 0 : timed().seconds(p.getUniqueId()));
            case "gamemode" -> gamemode(p);
            case "playtime" -> PlaceholderFormats.duration(codes.castled.allium.info.Playtime.seconds(p));
            case "playtime_short" -> PlaceholderFormats.durationShort(codes.castled.allium.info.Playtime.seconds(p));
            case "firstjoindate" -> codes.castled.allium.info.Playtime.date(plugin, codes.castled.allium.info.Playtime.firstJoin(plugin, p));
            case "god" -> yesNo(GodModeCompatProvider.current().isGodMode(p));
            case "vanished" -> yesNo(PlayerNameVisibility.isVanished(p));
            case "afk" -> yesNo(plugin.getModuleManager().getModule("info") instanceof PlayerInfoModule info
                    && info.isEnabled() && info.info() != null && info.info().isAfk(p));
            case "muted" -> yesNo(moderation() != null && moderation().moderation() != null && moderation().moderation().isMuted(p.getUniqueId()));
            case "jailed" -> yesNo(moderation() != null && moderation().discipline() != null && moderation().discipline().jailed(p.getUniqueId()));
            case "frozen" -> yesNo(moderation() != null && moderation().discipline() != null && moderation().discipline().isFrozen(p.getUniqueId()));
            case "reply" -> reply(p);
            case "spy" -> yesNo(preferences().flatMap(prefs -> prefs.effective(p.getUniqueId())).map(EffectivePreferences::socialSpy).orElse(false));
            case "mail_unread" -> mail(p, true);
            case "mail_gifts" -> mail(p, false);
            case "teleport_toggled" -> Boolean.toString(preferences().map(prefs -> prefs.acceptance(p.getUniqueId(),
                    PreferenceKey.ACCEPT_TELEPORT_REQUESTS) == PreferenceSessions.Acceptance.REFUSES).orElse(false));
            case "teleport_requests" -> Integer.toString(teleports() == null ? 0 : teleports().teleports().requestsInvolving(p.getUniqueId()));
            case "teleport_cooldown" -> Long.toString(teleports() == null ? 0 : teleports().teleports().cooldownSeconds(p.getUniqueId()));
            case "homes_set", "home_set" -> Integer.toString(travel() == null ? 0 : Math.max(0, travel().travel().homeCount(p.getUniqueId())));
            case "homes_max", "home_max" -> Integer.toString(travel() == null ? 0 : travel().travel().limit(p));
            case "world" -> p.getWorld() == null ? "" : p.getWorld().getName();
            case "world_time" -> world(p, w -> PlaceholderFormats.clock12(w.getTime()));
            case "world_time_24", "world_time_24h" -> world(p, w -> PlaceholderFormats.clock24(w.getTime()));
            case "world_date" -> world(p, w -> PlaceholderFormats.date(w.getFullTime()));
            case "balance", "balance_fixed", "balance_commas", "balance_formatted", "balance_display" -> balance(p, key);
            case "baltop_rank" -> Integer.toString(rank(p));
            case "baltop_rank_formatted" -> rank(p) == 0 ? "Unranked" : PlaceholderFormats.ordinal(rank(p));
            default -> null;
        });
    }

    /**
     * {@code survival}, {@code creative}, {@code adventure} or {@code spectator}; creative without
     * {@code allium.gamemode.creative.bypass} (the creative manager's restrictions apply) is
     * {@code restricted-creative}.
     */
    public static String gamemode(Player p) {
        String mode = p.getGameMode().name().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("creative") && !p.hasPermission("allium.gamemode.creative.bypass") ? "restricted-creative" : mode;
    }

    /**
     * Legacy's values: {@code yes}, {@code no}, and for timed flight {@code temporary} while its time
     * runs, {@code temporary-paused} while it is on but not spending time.
     */
    private String fly(Player p) {
        if (!p.getAllowFlight()) {
            return "no";
        }
        var timed = timed();
        if (timed != null && timed.active(p.getUniqueId())) {
            return timed.running(p) ? "temporary" : "temporary-paused";
        }
        return "yes";
    }

    @Nullable
    private codes.castled.allium.player.TimedFlight timed() {
        return plugin.getModuleManager().getModule("essentials") instanceof codes.castled.allium.module.EssentialsModule essentials
                && essentials.isEnabled() ? essentials.timedFlight() : null;
    }

    private String balance(Player p, String key) {
        EconomyRuntime economy = economy();
        BigDecimal amount = economy == null ? null : economy.placeholders().balances().get(p.getUniqueId());
        if (amount == null) {
            return "";
        }
        return format(economy, amount, key.substring("balance".length()));
    }

    /** {@code baltop_player_<n>}, {@code baltop_player_stripped_<n>}, {@code baltop_balance[_commas|_fixed|_formatted|_display]_<n>}. */
    @Nullable
    /**
     * {@code worth_<item>}: one item's price from {@code worth.yml} in two decimals ({@code 200.00});
     * {@code worth_display_<item>} as {@code /worth} shows it. Empty when it does not sell. Any thread:
     * prices are an immutable snapshot.
     */
    String worth(String item) {
        boolean display = item.startsWith("display_");
        if (display) item = item.substring("display_".length());
        if (!(plugin.getModuleManager().getModule("worth") instanceof codes.castled.allium.module.WorthModule module)
                || !module.isEnabled() || module.worth() == null || item.isEmpty()) {
            return "";
        }
        var worth = module.worth();
        org.bukkit.Material material = worth.material(item);
        if (material == null) return "";
        return worth.price(material).map(price -> display
                ? worth.format(codes.castled.allium.economy.Money.exact(price)) : price.toPlainString()).orElse("");
    }

    String baltop(String key) {
        String[] parts = key.split("_");
        if (parts.length < 3) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException notARank) {
            return null;
        }
        String kind = parts[1];
        String modifier = parts.length == 4 ? parts[2] : "";
        if (parts.length > 4 || !kind.equals("player") && !kind.equals("balance")
                || kind.equals("player") && !modifier.isEmpty() && !modifier.equals("stripped")) {
            return null;
        }
        EconomyRuntime economy = economy();
        List<EconomyRuntime.TopEntry> top = economy == null ? List.of() : economy.placeholders().top();
        if (rank < 1 || rank > top.size()) {
            return "";
        }
        EconomyRuntime.TopEntry entry = top.get(rank - 1);
        if (kind.equals("player")) {
            return modifier.isEmpty() ? entry.name() : TextFormatter.parse(entry.name()).toPlainText();
        }
        return format(economy, entry.balance(), modifier.isEmpty() ? "" : "_" + modifier);
    }

    @Nullable
    private static String format(EconomyRuntime economy, BigDecimal amount, String suffix) {
        return switch (suffix) {
            case "" -> PlaceholderFormats.plain(amount);
            case "_fixed" -> PlaceholderFormats.fixed(amount);
            case "_commas" -> PlaceholderFormats.commas(amount);
            case "_formatted" -> PlaceholderFormats.shortened(amount);
            case "_display" -> economy.formatAmount(amount);
            default -> null;
        };
    }

    private int rank(Player p) {
        EconomyRuntime economy = economy();
        if (economy == null) {
            return 0;
        }
        List<EconomyRuntime.TopEntry> top = economy.placeholders().top();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).id().equals(p.getUniqueId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private String nickname(Player p, boolean gradient) {
        if (plugin.getModuleManager().getModule("chat") instanceof ChatModule chat && chat.isEnabled() && chat.chat() != null) {
            return chat.chat().placeholder(p, gradient);
        }
        return p.getName();
    }

    private String reply(Player p) {
        if (!(plugin.getModuleManager().getModule("messaging") instanceof MessagingModule messaging) || !messaging.isEnabled()
                || messaging.messaging() == null) {
            return "";
        }
        return messaging.messaging().replyTarget(p).map(id -> {
            if (id.equals(codes.castled.allium.messaging.ReplyTargets.CONSOLE)) {
                return "Console";
            }
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) {
                return online.getName();
            }
            String name = plugin.getServer().getOfflinePlayer(id).getName();
            return name == null ? "" : name;
        }).orElse("");
    }

    private String mail(Player p, boolean unread) {
        if (!(plugin.getModuleManager().getModule("mail") instanceof MailModule mail) || !mail.isEnabled() || mail.mail() == null) {
            return "0";
        }
        var counts = mail.mail().counts(p.getUniqueId());
        return Integer.toString(unread ? counts.unread() : counts.gifts());
    }

    private static String world(Player p, Function<World, String> format) {
        return p.getWorld() == null ? "" : format.apply(p.getWorld());
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    @Nullable
    private EconomyRuntime economy() {
        return plugin.getModuleManager().getModule("economy") instanceof EconomyModule economy && economy.isEnabled()
                ? economy.runtime() : null;
    }

    @Nullable
    private ModerationModule moderation() {
        return plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation && moderation.isEnabled()
                ? moderation : null;
    }

    @Nullable
    private TeleportModule teleports() {
        return plugin.getModuleManager().getModule("teleport") instanceof TeleportModule teleport && teleport.isEnabled()
                && teleport.teleports() != null ? teleport : null;
    }

    @Nullable
    private TravelModule travel() {
        return plugin.getModuleManager().getModule("travel") instanceof TravelModule travel && travel.isEnabled()
                && travel.travel() != null ? travel : null;
    }

    private Optional<PreferencesModule> preferences() {
        return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule prefs && prefs.isEnabled()
                ? Optional.of(prefs) : Optional.empty();
    }

    /** One broken source never breaks the others or PlaceholderAPI's caller. */
    @Nullable
    private static String safe(java.util.function.Supplier<String> value) {
        try {
            return value.get();
        } catch (RuntimeException | LinkageError failed) {
            return "";
        }
    }
}
