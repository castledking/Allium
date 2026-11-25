package codes.castled.allium.player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.alts.VaultGroups;
import codes.castled.allium.config.ReloadCoordinator;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.AltsModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceSessions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Enforces persistent per-player PvP opt-out ({@code /pvp}) and optional same-IP alt PvP
 * protection across melee, projectiles, TNT, area-effect clouds, fangs, lightning, combustion,
 * and harmful splash potions.
 *
 * <p>Registered at {@link EventPriority#HIGH} with {@code ignoreCancelled = true} so region
 * protection plugins (WorldGuard, GriefPrevention, Lands) that cancel PvP earlier remain
 * authoritative while player opt-out and alt protection enforce on top of allowed regions.
 */
public final class PvpService implements Listener, AutoCloseable {

    private static final Set<String> HARMFUL_EFFECT_KEYS = Set.of(
            "poison", "wither", "instant_damage", "harm", "slowness", "slow",
            "weakness", "blindness", "hunger", "mining_fatigue", "slow_digging",
            "levitation", "darkness", "bad_omen", "infested", "oozing", "weaving", "wind_charged"
    );

    public enum Decision {
        ALLOWED,
        ATTACKER_DISABLED,
        VICTIM_DISABLED,
        PREFERENCES_UNAVAILABLE,
        SAME_IP_ALT
    }

    public record Settings(boolean protectSameIpAlts, boolean notifyBlocked, long notifyCooldownMillis) {
        public static final Settings DEFAULTS = new Settings(true, true, 2_500L);

        public static Settings read(@Nullable ConfigurationSection section) {
            if (section == null) {
                return DEFAULTS;
            }
            boolean protectAlts = section.getBoolean("protect-same-ip-alts", true);
            boolean notify = section.getBoolean("notify-blocked", true);
            long cooldown = Math.max(0L, Math.min(60_000L, section.getLong("notify-cooldown-millis", 2_500L)));
            return new Settings(protectAlts, notify, cooldown);
        }
    }

    private final PluginStart plugin;
    private final LongSupplier clock;
    private final BiPredicate<Player, String> groupCheck;
    private final Map<UUID, Long> lastNotified = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public PvpService(PluginStart plugin) {
        this(plugin, System::currentTimeMillis, null);
    }

    PvpService(PluginStart plugin, LongSupplier clock, @Nullable BiPredicate<Player, String> groupCheck) {
        this.plugin = plugin;
        this.clock = clock;
        this.groupCheck = groupCheck != null ? groupCheck : this::defaultInGroup;
        this.settings = Settings.read(plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("pvp"));
    }

    public void enable() {
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        lastNotified.clear();
    }

    public Settings settings() {
        return settings;
    }

    public ReloadCoordinator.Prepared prepareReload(FileConfiguration candidateRoot) {
        Settings candidate = Settings.read(candidateRoot == null ? null : candidateRoot.getConfigurationSection("pvp"));
        return ReloadCoordinator.Prepared.of(() -> settings = candidate);
    }

    public void reloadSettings() {
        settings = Settings.read(plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("pvp"));
    }

    /** Evaluates whether {@code attacker} may engage {@code victim} in PvP right now. */
    public Decision evaluate(Player attacker, Player victim) {
        if (attacker == null || victim == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return Decision.ALLOWED;
        }
        Settings current = settings;
        if (current.protectSameIpAlts() && sharesCurrentIp(attacker, victim) && isAltGroupMember(victim)) {
            return Decision.SAME_IP_ALT;
        }
        PreferencesModule prefs = preferences();
        if (prefs != null) {
            PreferenceSessions.Acceptance attackerState = prefs.acceptance(attacker.getUniqueId(), PreferenceKey.PVP_ENABLED);
            PreferenceSessions.Acceptance victimState = prefs.acceptance(victim.getUniqueId(), PreferenceKey.PVP_ENABLED);
            if (attackerState == PreferenceSessions.Acceptance.UNAVAILABLE
                    || victimState == PreferenceSessions.Acceptance.UNAVAILABLE) {
                return Decision.PREFERENCES_UNAVAILABLE;
            }
            if (attackerState == PreferenceSessions.Acceptance.REFUSES) {
                return Decision.ATTACKER_DISABLED;
            }
            if (victimState == PreferenceSessions.Acceptance.REFUSES) {
                return Decision.VICTIM_DISABLED;
            }
        }
        return Decision.ALLOWED;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveDamagingPlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        Decision decision = evaluate(attacker, victim);
        if (decision != Decision.ALLOWED) {
            event.setCancelled(true);
            notifyBlocked(attacker, victim, decision);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityCombustByEntity(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveDamagingPlayer(event.getCombuster());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        Decision decision = evaluate(attacker, victim);
        if (decision != Decision.ALLOWED) {
            event.setCancelled(true);
            notifyBlocked(attacker, victim, decision);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player attacker)) {
            return;
        }
        boolean harmful = false;
        for (PotionEffect effect : event.getPotion().getEffects()) {
            if (isHarmful(effect.getType())) {
                harmful = true;
                break;
            }
        }
        if (!harmful) {
            return;
        }
        for (LivingEntity affected : java.util.List.copyOf(event.getAffectedEntities())) {
            if (!(affected instanceof Player victim) || victim.getUniqueId().equals(attacker.getUniqueId())) {
                continue;
            }
            Decision decision = evaluate(attacker, victim);
            if (decision != Decision.ALLOWED) {
                event.setIntensity(victim, 0.0D);
                notifyBlocked(attacker, victim, decision);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastNotified.remove(event.getPlayer().getUniqueId());
    }

    @Nullable
    public static Player resolveDamagingPlayer(@Nullable Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof EvokerFangs fangs && fangs.getOwner() instanceof Player player) {
            return player;
        }
        if (damager instanceof LightningStrike strike) {
            return causingPlayer(strike);
        }
        return null;
    }

    @Nullable
    private static Player causingPlayer(LightningStrike strike) {
        try {
            Object player = LightningStrike.class.getMethod("getCausingPlayer").invoke(strike);
            return player instanceof Player p ? p : null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean isHarmful(@Nullable PotionEffectType type) {
        if (type == null) {
            return false;
        }
        String key = type.getKey() == null ? "" : type.getKey().getKey().toLowerCase(Locale.ROOT);
        return HARMFUL_EFFECT_KEYS.contains(key);
    }

    private void notifyBlocked(Player attacker, Player victim, Decision decision) {
        Settings current = settings;
        if (!current.notifyBlocked() || plugin.getMessageManager() == null) {
            return;
        }
        long now = clock.getAsLong();
        Long previous = lastNotified.get(attacker.getUniqueId());
        if (previous != null && now - previous < current.notifyCooldownMillis()) {
            return;
        }
        lastNotified.put(attacker.getUniqueId(), now);
        switch (decision) {
            case ATTACKER_DISABLED -> plugin.getMessageManager().send(attacker, Messages.PVP_BLOCKED_SELF);
            case VICTIM_DISABLED -> plugin.getMessageManager().send(attacker, Messages.PVP_BLOCKED_TARGET,
                    "{player}", victim.getName());
            case SAME_IP_ALT -> plugin.getMessageManager().send(attacker, Messages.PVP_BLOCKED_ALT,
                    "{player}", victim.getName());
            case PREFERENCES_UNAVAILABLE -> plugin.getMessageManager().send(attacker, Messages.PVP_BLOCKED_LOADING);
            case ALLOWED -> {
            }
        }
    }

    private boolean isAltGroupMember(Player victim) {
        String group = altGroupName();
        return group != null && !group.isBlank() && groupCheck.test(victim, group);
    }

    private String altGroupName() {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("alts") instanceof AltsModule alts
                && alts.isEnabled() && alts.alts() != null) {
            return alts.alts().settings().group();
        }
        ConfigurationSection section = plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("alts");
        return section == null ? "alt" : section.getString("group", "alt");
    }

    private boolean ignoreLocalAddresses() {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("alts") instanceof AltsModule alts
                && alts.isEnabled() && alts.alts() != null) {
            return alts.alts().settings().ignoreLocal();
        }
        ConfigurationSection section = plugin.getConfig() == null ? null : plugin.getConfig().getConfigurationSection("alts");
        return section == null || section.getBoolean("ignore-local-addresses", true);
    }

    private boolean defaultInGroup(Player player, String group) {
        if (player.hasPermission("group." + group)) {
            return true;
        }
        if (plugin.getServer() == null) {
            return false;
        }
        VaultGroups vault = VaultGroups.find(plugin.getServer());
        return vault != null && vault.has(player, group);
    }

    private boolean sharesCurrentIp(Player first, Player second) {
        InetAddress firstAddr = addressOf(first);
        InetAddress secondAddr = addressOf(second);
        if (firstAddr == null || secondAddr == null) {
            return false;
        }
        if (ignoreLocalAddresses() && (firstAddr.isLoopbackAddress() || secondAddr.isLoopbackAddress())) {
            return false;
        }
        return firstAddr.getHostAddress().equals(secondAddr.getHostAddress());
    }

    @Nullable
    private static InetAddress addressOf(Player player) {
        InetSocketAddress socket = player.getAddress();
        return socket == null ? null : socket.getAddress();
    }

    @Nullable
    private PreferencesModule preferences() {
        if (plugin.getModuleManager() == null) {
            return null;
        }
        return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule module
                && module.isEnabled() ? module : null;
    }
}
