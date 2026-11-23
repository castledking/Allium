package codes.castled.allium.glow;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Coloured glow outlines, ported from legacy Allium's {@code /glow}. A player shows, in order:
 * <ol>
 *   <li>the restricted-creative glow ({@code creative-manager.glow}), while they are in creative
 *       without {@code allium.gamemode.creative.bypass}, unless they hold
 *       {@value #PERMISSION_OWN_GLOW};</li>
 *   <li>their {@code /glow} choice (a colour or rainbow), unless they made it spectator-only and are
 *       not in spectator;</li>
 *   <li>the spectator glow ({@value #PERMISSION_SPECTATOR}) while in spectator, in the colour of
 *       an explicitly granted {@code allium.gamemode.spectator.glow.<colour|rainbow>} node (white).</li>
 * </ol>
 * The colour reaches viewers as a virtual team through PacketEvents, so no scoreboard is touched;
 * without PacketEvents the outline is white. Choices last for the session, as in legacy Allium;
 * the spectator-only switch is kept in the player's data.
 */
public final class GlowService implements Listener, AutoCloseable {

    public static final String PERMISSION_OWN_GLOW = "allium.gamemode.creative.own-glow";
    public static final String PERMISSION_SPECTATOR = "allium.gamemode.spectator.glow";
    public static final String RAINBOW = "rainbow";
    static final long TICK_MILLIS = 200;

    /** The 16 vanilla glow colours and their legacy colour codes. */
    public static final Map<String, Character> COLORS = colors();
    static final List<String> RAINBOW_COLORS = List.of("red", "gold", "yellow", "green", "aqua", "blue", "light_purple");

    /** Sends coloured glow to viewers. */
    interface Teams {
        /** Shows or recolours the target's glow for these viewers. */
        void show(Player target, String color, Iterable<? extends Player> viewers);

        /** Shows an already glowing target to a viewer who just joined. */
        void introduce(Player target, String color, Player viewer);

        void hide(Player target, Iterable<? extends Player> viewers);
    }

    /** The PacketEvents sender, or null without PacketEvents (the glow is then white). */
    @Nullable
    static Teams loadTeams(PluginStart plugin) {
        var manager = plugin.getServer().getPluginManager();
        if (!manager.isPluginEnabled("packetevents") && !manager.isPluginEnabled("PacketEvents")) return null;
        try {
            return PacketGlowTeams.create(plugin);
        } catch (LinkageError | RuntimeException unavailable) {
            plugin.logger().warning("Glow colours are unavailable (PacketEvents could not be used); glow is white.");
            return null;
        }
    }

    record Settings(boolean restrictedEnabled, String restrictedColor) {
        static Settings read(@Nullable ConfigurationSection c) {
            if (c == null) return new Settings(false, "light_purple");
            String color = c.getString("color", "light_purple").toLowerCase(Locale.ROOT).replace(' ', '_');
            return new Settings(c.getBoolean("enabled", false), COLORS.containsKey(color) || RAINBOW.equals(color) ? color : "light_purple");
        }
    }

    private final PluginStart plugin;
    private final NamespacedKey spectatorOnlyKey;
    @Nullable
    private final Teams teams;
    private final Map<UUID, String> choices = new ConcurrentHashMap<>();
    /** What each player shows now: a colour, or rainbow. */
    private final Map<UUID, String> shown = new ConcurrentHashMap<>();
    private volatile Settings settings;
    private TaskHandle tick = TaskHandle.NONE;
    private final AtomicInteger step = new AtomicInteger();

    public GlowService(PluginStart plugin) {
        this(plugin, loadTeams(plugin));
    }

    GlowService(PluginStart plugin, @Nullable Teams teams) {
        this.plugin = plugin;
        this.teams = teams;
        this.spectatorOnlyKey = new NamespacedKey(plugin, "glow_spectator_only");
        reload();
    }

    public void reload() {
        settings = Settings.read(plugin.getConfig().getConfigurationSection("creative-manager.glow"));
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tick = plugin.getScheduler().repeatOnServer(() -> {
            tick();
            return true;
        }, TICK_MILLIS, TICK_MILLIS);
        for (Player player : plugin.getServer().getOnlinePlayers()) evaluate(player);
    }

    @Override
    public void close() {
        tick.cancel();
        HandlerList.unregisterAll(this);
        Set<UUID> active = Set.copyOf(shown.keySet());
        shown.clear();
        choices.clear();
        List<Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
        for (Player player : online) {
            if (!active.contains(player.getUniqueId())) continue;
            if (teams != null) teams.hide(player, online);
            if (!BukkitServerThread.isFolia() || !plugin.isEnabled()) {
                player.setGlowing(false);
            } else {
                onPlayer(player, () -> player.setGlowing(false));
            }
        }
    }

    /** Whether PacketEvents colours the glow; without it the outline is white. */
    public boolean coloured() {
        return teams != null;
    }

    // -- Choices ---------------------------------------------------------------------------------------

    /** Sets a player's {@code /glow} choice (a colour or rainbow), or clears it with null. */
    public void choose(Player player, @Nullable String choice) {
        if (choice == null) choices.remove(player.getUniqueId());
        else choices.put(player.getUniqueId(), choice);
        evaluate(player);
    }

    @Nullable
    public String choice(Player player) {
        return choices.get(player.getUniqueId());
    }

    public boolean spectatorOnly(Player player) {
        return player.getPersistentDataContainer().getOrDefault(spectatorOnlyKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    /** Flips spectator-only and returns the new state. */
    public boolean toggleSpectatorOnly(Player player) {
        boolean on = !spectatorOnly(player);
        if (on) player.getPersistentDataContainer().set(spectatorOnlyKey, PersistentDataType.BYTE, (byte) 1);
        else player.getPersistentDataContainer().remove(spectatorOnlyKey);
        evaluate(player);
        return on;
    }

    /** Whether a player is in creative with the creative manager's restrictions on them. */
    public boolean restrictedCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && !player.hasPermission("allium.gamemode.creative.bypass")
                && plugin.getModuleManager() != null && plugin.getModuleManager().getModule("creative") instanceof codes.castled.allium.module.CreativeModule creative
                && creative.isEnabled();
    }

    /** Whether the restricted-creative glow covers this player's own glow right now. */
    public boolean overridden(Player player) {
        return settings.restrictedEnabled() && restrictedCreative(player) && !player.hasPermission(PERMISSION_OWN_GLOW);
    }

    // -- What shows ------------------------------------------------------------------------------------

    @Nullable
    String desired(Player player) {
        if (overridden(player)) return settings.restrictedColor();
        String choice = choices.get(player.getUniqueId());
        if (choice != null && (!spectatorOnly(player) || player.getGameMode() == GameMode.SPECTATOR)) return choice;
        if (player.getGameMode() == GameMode.SPECTATOR && player.hasPermission(PERMISSION_SPECTATOR)) {
            return spectatorColor(node -> player.isPermissionSet(node) && player.hasPermission(node));
        }
        return null;
    }

    /**
     * The spectator glow from explicitly granted {@code allium.gamemode.spectator.glow.<colour|rainbow>}
     * nodes, white first so wildcards land on the default (operators pass every undeclared node).
     */
    static String spectatorColor(Predicate<String> granted) {
        if (granted.test(PERMISSION_SPECTATOR + ".white")) return "white";
        for (String name : COLORS.keySet()) {
            if (granted.test(PERMISSION_SPECTATOR + "." + name)) return name;
        }
        return granted.test(PERMISSION_SPECTATOR + "." + RAINBOW) ? RAINBOW : "white";
    }

    /** Brings the shown glow in line with what the player should show. */
    public void evaluate(Player player) {
        onPlayer(player, () -> evaluateDirect(player));
    }

    private void evaluateDirect(Player player) {
        if (!player.isOnline()) return;
        String want = desired(player);
        String now = shown.get(player.getUniqueId());
        if (want == null) {
            if (now != null) hide(player);
            return;
        }
        if (want.equals(now) && player.isGlowing()) return;
        shown.put(player.getUniqueId(), want);
        player.setGlowing(true);
        if (teams != null) teams.show(player, RAINBOW.equals(want) ? RAINBOW_COLORS.get(step.get() % RAINBOW_COLORS.size()) : want,
                List.copyOf(plugin.getServer().getOnlinePlayers()));
    }

    private void hide(Player player) {
        shown.remove(player.getUniqueId());
        player.setGlowing(false);
        if (teams != null) teams.hide(player, List.copyOf(plugin.getServer().getOnlinePlayers()));
    }

    /** Every 200 ms: the next rainbow colour; every 2 s, everyone is re-checked (gamemodes, permissions). */
    void tick() {
        int current = step.incrementAndGet();
        List<Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
        if (teams != null) {
            String color = RAINBOW_COLORS.get(current % RAINBOW_COLORS.size());
            for (Map.Entry<UUID, String> e : shown.entrySet()) {
                if (!RAINBOW.equals(e.getValue())) continue;
                Player player = plugin.getServer().getPlayer(e.getKey());
                if (player != null) teams.show(player, color, online);
            }
        }
        if (current % 10 == 0) {
            for (Player player : online) evaluate(player);
        }
    }

    // -- Events ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (teams != null) {
            int current = step.get();
            for (Map.Entry<UUID, String> e : shown.entrySet()) {
                Player target = plugin.getServer().getPlayer(e.getKey());
                if (target != null && target != joined) {
                    String color = RAINBOW.equals(e.getValue()) ? RAINBOW_COLORS.get(current % RAINBOW_COLORS.size()) : e.getValue();
                    teams.introduce(target, color, joined);
                }
            }
        }
        // A glow flag saved with the player is not a choice: start from nothing.
        joined.setGlowing(false);
        evaluateDirect(joined);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (shown.remove(player.getUniqueId()) != null) {
            player.setGlowing(false);
            if (teams != null) {
                List<Player> others = plugin.getServer().getOnlinePlayers().stream().filter(p -> p != player).map(p -> (Player) p).toList();
                teams.hide(player, others);
            }
        }
        choices.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        // The mode changes after the event: look again on the next tick on the player's owning region.
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            if (isCurrent(player)) evaluateDirect(player);
        }, () -> {}, 0L);
    }

    private void onPlayer(Player player, Runnable action) {
        if (!BukkitServerThread.isFolia() || plugin.getScheduler() == null) {
            action.run();
            return;
        }
        var owner = BukkitServerThread.entity(plugin, player);
        if (owner.isServerThread()) {
            if (player.isOnline()) action.run();
            return;
        }
        plugin.getScheduler().runOn(owner, () -> {
            if (isCurrent(player)) action.run();
        }, () -> {}, 0L);
    }

    private boolean isCurrent(Player player) {
        if (!player.isOnline()) return false;
        Player current = plugin.getServer().getPlayer(player.getUniqueId());
        return current == null || current == player;
    }

    private static Map<String, Character> colors() {
        Map<String, Character> map = new LinkedHashMap<>();
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        String codes = "0123456789abcdef";
        for (int i = 0; i < names.length; i++) map.put(names[i], codes.charAt(i));
        return java.util.Collections.unmodifiableMap(map);
    }

    /** {@code &dlight purple}, or rainbow in its colours. */
    public static String display(String choice) {
        if (RAINBOW.equals(choice)) return "&cr&6a&ei&an&bb&9o&dw";
        Character code = COLORS.get(choice);
        return (code == null ? "&f" : "&" + code) + choice.replace('_', ' ');
    }
}
