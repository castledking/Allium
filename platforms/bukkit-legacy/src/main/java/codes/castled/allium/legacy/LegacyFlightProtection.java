package codes.castled.allium.legacy;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class LegacyFlightProtection {
    private static final int EFFECT_TICKS = 60;
    private static final long LANDING_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final JavaPlugin plugin;
    private final LegacyPlayerSettings settings;
    private final LegacyMessageManager messages;
    private final PotionEffectType slowFalling;
    private final Map<UUID, Landing> pending = new HashMap<>();
    private BukkitTask monitor;

    LegacyFlightProtection(JavaPlugin plugin, LegacyPlayerSettings settings, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.slowFalling = PotionEffectType.getByName("SLOW_FALLING");
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.fly")) {
            messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (args.length > 1) return false;
        Player player = (Player) sender;
        UUID id = player.getUniqueId();
        boolean enabled = settings.flightProtectionEnabled(id);
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "":
                enabled = !enabled;
                break;
            case "on": case "enable": case "true":
                enabled = true;
                break;
            case "off": case "disable": case "false":
                enabled = false;
                break;
            case "status": case "state":
                sendState(player, enabled);
                return true;
            default:
                return false;
        }
        if (!settings.setFlightProtectionEnabled(id, enabled)) {
            messages.send(player, "error.save-failed", "{prefix} &cYour setting could not be saved.");
            return true;
        }
        if (!enabled) forget(player);
        sendState(player, enabled);
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (!(sender instanceof Player) || !sender.hasPermission("allium.fly") || args.length != 1) {
            return Collections.emptyList();
        }
        return Arrays.asList("on", "off", "status").stream().filter(state -> state.startsWith(prefix))
                .collect(Collectors.toList());
    }

    void protectLanding(Player player) {
        forget(player);
        if (!settings.flightProtectionEnabled(player.getUniqueId()) || !needsLanding(player)) return;
        Landing landing = new Landing(player, System.nanoTime() + LANDING_NANOS);
        pending.put(player.getUniqueId(), landing);
        maintainPotion(landing);
        if (monitor == null) {
            monitor = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkLandings, 5L, 5L);
        }
    }

    boolean consumeLanding(Player player) {
        Landing landing = pending.get(player.getUniqueId());
        if (landing == null || landing.player != player) return false;
        forget(player);
        return landing.world == player.getWorld() && landing.expires - System.nanoTime() > 0
                && settings.flightProtectionEnabled(player.getUniqueId());
    }

    void forget(Player player) {
        Landing landing = pending.remove(player.getUniqueId());
        if (landing != null) removePotion(landing);
        stopIdleMonitor();
    }

    void close() {
        pending.values().forEach(this::removePotion);
        pending.clear();
        stopIdleMonitor();
    }

    private void checkLandings() {
        long now = System.nanoTime();
        Iterator<Landing> landings = pending.values().iterator();
        while (landings.hasNext()) {
            Landing landing = landings.next();
            Player player = landing.player;
            if (!player.isOnline() || player.getWorld() != landing.world || landing.expires - now <= 0
                    || !settings.flightProtectionEnabled(player.getUniqueId()) || !needsLanding(player)) {
                landings.remove();
                removePotion(landing);
            } else {
                maintainPotion(landing);
            }
        }
        stopIdleMonitor();
    }

    private void stopIdleMonitor() {
        if (monitor != null && pending.isEmpty()) {
            monitor.cancel();
            monitor = null;
        }
    }

    private static boolean needsLanding(Player player) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return false;
        if (player.getAllowFlight() || player.isOnGround() || player.isInsideVehicle() || player.isGliding()) return false;
        Location location = player.getLocation();
        Block at = location == null || location.getWorld() == null ? null : location.getBlock();
        return at == null || !at.isLiquid();
    }

    private void maintainPotion(Landing landing) {
        if (slowFalling == null) return;
        PotionEffect current = landing.player.getPotionEffect(slowFalling);
        if (current == null || (landing.appliedPotion && isProtectionPotion(current) && current.getDuration() <= 20)) {
            boolean applied = landing.player.addPotionEffect(new PotionEffect(slowFalling, EFFECT_TICKS, 0, false, false));
            landing.appliedPotion = applied || (current != null && landing.appliedPotion);
        } else if (!isProtectionPotion(current)) {
            landing.appliedPotion = false;
        }
    }

    private void removePotion(Landing landing) {
        if (landing.appliedPotion && isProtectionPotion(landing.player.getPotionEffect(slowFalling))) {
            landing.player.removePotionEffect(slowFalling);
        }
    }

    private static boolean isProtectionPotion(PotionEffect effect) {
        return effect != null && effect.getAmplifier() == 0 && effect.getDuration() <= EFFECT_TICKS
                && !effect.isAmbient() && !effect.hasParticles();
    }

    private void sendState(Player player, boolean enabled) {
        messages.send(player, "player.slowfall-toggle", "{prefix} &aSlow fall protection {state}&a.",
                "{state}", enabled ? "&aenabled" : "&cdisabled");
    }

    private static final class Landing {
        private final Player player;
        private final World world;
        private final long expires;
        private boolean appliedPotion;

        private Landing(Player player, long expires) {
            this.player = player;
            this.world = player.getWorld();
            this.expires = expires;
        }
    }
}
