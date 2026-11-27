package codes.castled.allium.modguard;

import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Legacy Allium's ModGuard: reads what a client says about itself (its brand, its plugin channels,
 * and the mod lists Forge and Lunar Client send) and matches it against {@code modguard.yml}'s
 * rules; with PacketEvents, the sign translation probe adds what the client's keybinds and texts
 * reveal. Each detection becomes a finding; the access policy decides who may stay, and staff get
 * one alert per mod per session. Operators are never checked.
 */
public final class ModGuard implements PluginMessageListener, Listener, AutoCloseable {
    private static final List<String> INCOMING = List.of("minecraft:brand", "fml:handshake", LunarWire.OFFER, LunarWire.CHANNEL);

    private final Plugin plugin;
    private final TaskScheduler scheduler;
    private final Logger logger;
    private final YamlConfiguration bundled;
    private volatile YamlConfiguration config;
    private volatile List<Detection.Rule> rules = List.of();
    private volatile Detection.Severity minimumSeverity = Detection.Severity.WARNING;
    private volatile boolean closed;
    private final Map<UUID, ModGuardSession> sessions = new ConcurrentHashMap<>();
    private final AccessPolicy access;
    private final LunarWire lunar;
    private TranslationProbe probe;
    private final List<String> registeredOutgoing = new ArrayList<>();
    private final org.bukkit.NamespacedKey alertsOff;

    public ModGuard(Plugin plugin, TaskScheduler scheduler, YamlConfiguration config, YamlConfiguration bundled) {
        Plugin hostPlugin = codes.castled.allium.PluginStart.bukkitPlugin(plugin);
        this.plugin = hostPlugin;
        this.scheduler = scheduler;
        this.logger = hostPlugin.getLogger();
        this.bundled = bundled;
        this.access = new AccessPolicy(hostPlugin, scheduler, this);
        this.lunar = new LunarWire(hostPlugin, scheduler, this);
        this.alertsOff = new org.bukkit.NamespacedKey(hostPlugin, "modguard_alerts_off");
        configure(config);
    }

    /** Registers the channels, listeners and the probe. */
    public void start() {
        var messenger = Bukkit.getMessenger();
        for (String channel : List.of(LunarWire.CHANNEL, NetworkDisconnect.CHANNEL)) {
            if (!messenger.isOutgoingChannelRegistered(plugin, channel)) {
                messenger.registerOutgoingPluginChannel(plugin, channel);
                registeredOutgoing.add(channel);
            }
        }
        for (String channel : INCOMING) {
            try {
                messenger.registerIncomingPluginChannel(plugin, channel, this);
            } catch (IllegalArgumentException reserved) {
                // Older servers reserve some of these names; the rest still work.
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startProbe();
        for (Player player : Bukkit.getOnlinePlayers()) {
            BukkitServerThread.onOwner(plugin, scheduler, player, () -> {
                if (player.isOnline()) begin(player);
            });
        }
        logger.info("[ModGuard] Watching with " + rules.size() + " rules (alerts from " + minimumSeverity + ").");
    }

    public void reload(YamlConfiguration next) {
        stopProbe();
        configure(next);
        startProbe();
    }

    private void configure(YamlConfiguration next) {
        config = next;
        ConfigurationSection section = next.getConfigurationSection("rules");
        if (section == null) section = bundled.getConfigurationSection("rules");
        List<Detection.Rule> loaded = new ArrayList<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection rule = section.getConfigurationSection(id);
                if (rule == null || !rule.getBoolean("enabled", true)) continue;
                loaded.add(new Detection.Rule(id, Detection.clean(rule.getString("name", id), 80),
                        Detection.Severity.parse(rule.getString("severity", "INFO")),
                        patterns(rule, "brands"), patterns(rule, "channels"), patterns(rule, "mods")));
            }
        }
        rules = List.copyOf(loaded);
        minimumSeverity = Detection.Severity.parse(next.getString("alerts.minimum-severity", "WARNING"));
        access.configure(next);
        lunar.enabled = next.getBoolean("lunar.direct-json", true);
    }

    private static List<Pattern> patterns(ConfigurationSection rule, String key) {
        List<Pattern> patterns = new ArrayList<>();
        for (String value : rule.getStringList(key)) {
            if (value != null && !value.isBlank() && value.length() <= 256) patterns.add(Detection.glob(value.trim()));
        }
        return List.copyOf(patterns);
    }

    private void startProbe() {
        if (!config.getBoolean("translation-probe.enabled", true) || !Bukkit.getPluginManager().isPluginEnabled("packetevents")) return;
        if (BukkitServerThread.isFolia()) {
            logger.info("[ModGuard] The translation probe is unavailable on Folia.");
            return;
        }
        try {
            probe = new TranslationProbe(plugin, scheduler, this);
            probe.register();
        } catch (Throwable unavailable) {
            probe = null;
            logger.warning("[ModGuard] The translation probe could not start: " + unavailable);
        }
    }

    private void stopProbe() {
        if (probe != null) probe.unregister();
        probe = null;
    }

    @Override
    public void close() {
        closed = true;
        stopProbe();
        lunar.close();
        HandlerList.unregisterAll(this);
        var messenger = Bukkit.getMessenger();
        for (String channel : INCOMING) {
            try {
                messenger.unregisterIncomingPluginChannel(plugin, channel, this);
            } catch (IllegalArgumentException notRegistered) {
                // Nothing to undo.
            }
        }
        for (String channel : registeredOutgoing) messenger.unregisterOutgoingPluginChannel(plugin, channel);
        registeredOutgoing.clear();
        sessions.clear();
    }

    /** Whether this staff member receives ModGuard alerts; they are on unless turned off. */
    public boolean alertsOn(Player staff) {
        return !staff.getPersistentDataContainer().has(alertsOff, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Turns this staff member's alerts on or off ({@code null} toggles); returns whether they are now on. */
    public boolean setAlerts(Player staff, Boolean on) {
        boolean next = on == null ? !alertsOn(staff) : on;
        if (next) staff.getPersistentDataContainer().remove(alertsOff);
        else staff.getPersistentDataContainer().set(alertsOff, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        return next;
    }

    public String staffPermission() { return config.getString("staff-permission", "modguard.notify"); }

    ConfigurationSection config() { return config; }
    YamlConfiguration bundled() { return bundled; }
    ModGuardSession session(Player player) {
        ModGuardSession session = sessions.get(player.getUniqueId());
        return !closed && session != null && session.player == player ? session : null;
    }
    public List<Detection.Rule> rules() { return rules; }

    boolean bypassed(Player player) {
        return player != null && (player.hasPermission(config.getString("bypass-permission", "modguard.bypass"))
                || player.hasPermission("clientguard.bypass"));
    }

    /** Whether ModGuard checks this player: never operators. */
    boolean monitoring(ModGuardSession session) {
        if (session == null || session(session.player) != session) return false;
        session.excluded = session.player.isOp();
        return !session.excluded && config.getBoolean("enabled", true);
    }

    String kickMessage(String mods, String player) {
        return TextFormatter.toLegacy(config.getString("kick-message", "&c&lMODIFIED CLIENT DETECTED\n\n&7You cannot join with these mods installed:\n&c{mod}")
                .replace("{mod}", mods).replace("{player}", player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer());
    }

    private void begin(Player player) {
        if (closed || session(player) != null) return;
        ModGuardSession session = new ModGuardSession(player);
        sessions.put(session.uuid, session);
        if (!monitoring(session)) {
            session.started = true;
            return;
        }
        sample(session);
        // Channels register over the first seconds; sample again as they arrive.
        for (long ticks : new long[]{20L, 60L, 200L}) {
            BukkitServerThread.runFor(plugin, scheduler, player, () -> {
                if (!player.isOnline() || !monitoring(session)) return;
                sample(session);
                requestLunar(session);
            }, () -> {}, ticks * TaskScheduler.MILLIS_PER_TICK);
        }
        session.started = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        ModGuardSession session = session(player);
        if (!monitoring(session)) return;
        channel(session, event.getChannel(), "REGISTERED_CHANNEL");
        if (LunarWire.OFFER.equals(event.getChannel()) && config.getBoolean("lunar.apollo-integration", true)) {
            BukkitServerThread.runFor(plugin, scheduler, player, () -> {
                if (player.isOnline() && monitoring(session)) requestLunar(session);
            }, () -> {}, 20 * TaskScheduler.MILLIS_PER_TICK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ModGuardSession session = session(event.getPlayer());
        if (session == null) return;
        sessions.remove(session.uuid, session);
        lunar.quit(session);
    }

    private void requestLunar(ModGuardSession session) {
        if (!config.getBoolean("lunar.apollo-integration", true)) return;
        lunar.request(session, mods -> reportedMods(session.player, mods, "LUNAR_DIRECT_JSON"),
                error -> session.lunarStatus = error);
    }

    private void sample(ModGuardSession session) {
        Player player = session.player;
        String brand = clientBrand(player);
        if (brand != null && !brand.isBlank()) brand(session, brand, "CLIENT_BRAND");
        for (String channel : player.getListeningPluginChannels()) channel(session, channel, "LISTENING_CHANNEL");
    }

    /** Paper's Player#getClientBrandName; Spigot has none, and the brand payload covers it there. */
    private static String clientBrand(Player player) {
        try {
            Object brand = player.getClass().getMethod("getClientBrandName").invoke(player);
            return brand == null ? null : String.valueOf(brand);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public void onPluginMessageReceived(String rawChannel, Player player, byte[] data) {
        ModGuardSession session = session(player);
        if (!monitoring(session)) return;
        long now = System.currentTimeMillis();
        if (now - session.windowStart >= 1000L) {
            session.windowStart = now;
            session.windowCount = 0;
        }
        if (++session.windowCount > 50) return;
        String channel = ChannelWire.canonical(rawChannel);
        channel(session, channel, "RECEIVED_CHANNEL");
        try {
            switch (channel) {
                case LunarWire.CHANNEL -> lunar.receive(player, data);
                case LunarWire.OFFER -> Wire.lunarHandshake(data).ifPresent(mods -> reportedMods(player, mods, "LUNAR_HANDSHAKE"));
                case "minecraft:brand" -> brand(session, Wire.brand(data), "BRAND_PAYLOAD");
                case "fml:handshake" -> {
                    if (data.length > 0 && (data[0] & 0xFF) == 2) reportedMods(player, Wire.legacyForge(data), "FORGE_LEGACY_PAYLOAD");
                }
                case "minecraft:register" -> {
                    for (String registered : ChannelWire.registrations(data)) channel(session, registered, "REGISTER_PAYLOAD");
                }
                default -> { }
            }
        } catch (Throwable malformed) {
            // A malformed payload says nothing either way.
        }
    }

    private void brand(ModGuardSession session, String value, String source) {
        String brand = Detection.clean(value, 256);
        if (brand.isBlank()) return;
        session.brand = brand;
        if (session.brands.add(brand)) match(session, Detection.Kind.BRAND, brand, source, "SELF_REPORTED");
    }

    private void channel(ModGuardSession session, String value, String source) {
        String channel = Detection.clean(value, 256);
        if (!channel.isBlank() && session.channels.add(channel)) match(session, Detection.Kind.CHANNEL, channel, source, "CHANNEL_SIGNATURE");
    }

    void reportedMods(Player player, List<Detection.Mod> mods, String source) {
        ModGuardSession session = session(player);
        if (!monitoring(session)) return;
        for (Detection.Mod mod : mods) {
            if (mod == null || mod.id().isBlank()) continue;
            String id = Detection.clean(mod.id(), 256).toLowerCase(Locale.ROOT);
            session.modSources.put(id, source);
            if (session.mods.put(id, mod) == null) match(session, Detection.Kind.MOD, id, source, "REPORTED_MOD_LIST");
        }
    }

    void handleTranslationProbeHit(Player player, String checkId, String key, String resolved, String fallback,
                                   String configuredAction, boolean requireCorroboration) {
        ModGuardSession session = session(player);
        if (!monitoring(session)) return;
        String action = configuredAction == null ? "kick" : configuredAction.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(action)) return;
        Detection.Severity severity = "alert".equals(action) ? Detection.Severity.WARNING : Detection.Severity.CRITICAL;
        for (Detection.Rule rule : rules) {
            if (rule.id().equalsIgnoreCase(checkId) || rule.name().equalsIgnoreCase(checkId)) {
                severity = rule.severity();
                break;
            }
        }
        var finding = new Detection.Finding(checkId, checkId, severity, "TRANSLATION_PROBE",
                key + " -> " + Detection.clean(resolved, 160), "SIGN_TRANSLATION_PROBE", Instant.now());
        session.findings.put(checkId + ":TRANSLATION_PROBE", finding);
        boolean corroborated = !requireCorroboration || session.findings.values().stream()
                .anyMatch(other -> !"TRANSLATION_PROBE".equals(other.source()));
        boolean kick = "kick".equals(action) && corroborated && access.enabled() && !bypassed(player) && !player.isOp();
        if (kick) {
            access.disconnects.disconnect(player, kickMessage(checkId, player.getName()));
        }
        if (severity.ordinal() >= minimumSeverity.ordinal() && session.notifiedRules.add(checkId)) notifyStaff(player.getName(), finding);
    }

    private void match(ModGuardSession session, Detection.Kind kind, String value, String source, String confidence) {
        for (Detection.Rule rule : rules) {
            if (rule.matches(kind, value)) finding(session, rule, source, kind + ": " + value, confidence);
        }
    }

    private void finding(ModGuardSession session, Detection.Rule rule, String source, String evidence, String confidence) {
        if (!monitoring(session)) return;
        String key = rule.id() + ":" + source;
        if (session.findings.containsKey(key)) {
            access.check(session);
            return;
        }
        var finding = new Detection.Finding(rule.id(), rule.name(), rule.severity(), source,
                Detection.clean(evidence, 350), confidence, Instant.now());
        session.findings.put(key, finding);
        access.check(session);
        if (finding.severity().ordinal() < minimumSeverity.ordinal() || !session.notifiedRules.add(rule.id())) return;
        notifyStaff(session.name, finding);
    }

    private void notifyStaff(String playerName, Detection.Finding finding) {
        String message = config.getString("staff-alert", "&7[&c&lModGuard&7] &e{player} &7was disconnected for using: &c{mod}")
                .replace("{player}", playerName).replace("{mod}", finding.name())
                .replace("{rule}", finding.rule()).replace("{severity}", finding.severity().name());
        logger.warning("[ModGuard] " + playerName + " detected using " + finding.name() + " (" + finding.severity()
                + ") via " + finding.source() + ": " + finding.evidence());
        scheduler.runOnServer(() -> {
            if (closed) return;
            for (Player staff : Bukkit.getOnlinePlayers()) {
                BukkitServerThread.onOwner(plugin, scheduler, staff, () -> {
                    if (!closed && staff.isOnline() && staff.hasPermission(staffPermission()) && alertsOn(staff)) {
                        TextSender.send(staff, message);
                    }
                });
            }
        });
    }
}
