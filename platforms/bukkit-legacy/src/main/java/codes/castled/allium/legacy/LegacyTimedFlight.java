package codes.castled.allium.legacy;

import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Tracks flight balances in server ticks so toggling flight cannot discard fractional seconds. */
final class LegacyTimedFlight {
    private static final String TIMED = "allium.tfly";
    private static final String UNLIMITED = "allium.fly";
    private static final String COUNT_FLYING = "timed-flight.count-only-while-flying";
    private static final long MAX_SECONDS = 3650L * 86_400L;
    private static final long TICKS_PER_SECOND = 20;
    private static final long MAX_TICKS = MAX_SECONDS * TICKS_PER_SECOND;
    private static final List<Long> WARNINGS = Arrays.asList(60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyPlayerInfoModule playerInfo;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;
    private final File file;
    private final Map<UUID, Long> remainingTicks = new HashMap<>();
    private final Map<UUID, Player> active = new HashMap<>();
    private final Map<UUID, Long> landingUntil = new HashMap<>();
    private BukkitTask ticker;
    private int ticksSinceSave;
    private boolean writable;
    private boolean dirty;

    LegacyTimedFlight(JavaPlugin plugin, LegacyConfigManager config, LegacyPlayerInfoModule playerInfo,
                      LegacyMessageManager messages, BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.config = config;
        this.playerInfo = playerInfo;
        this.messages = messages;
        this.canSee = canSee;
        this.file = new File(plugin.getDataFolder(), "timed-flight-legacy.yml");
    }

    void enable() {
        YamlConfiguration stored = new YamlConfiguration();
        try {
            if (file.exists()) stored.load(file);
            ConfigurationSection players = stored.getConfigurationSection("ticks");
            if (players == null && stored.contains("ticks")) throw new IllegalArgumentException("ticks must be a section");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    UUID player = UUID.fromString(key);
                    if (!players.isInt(key) && !players.isLong(key)) throw new IllegalArgumentException("Invalid balance for " + key);
                    long value = clampTicks(players.getLong(key));
                    if (value > 0) remainingTicks.put(player, value);
                }
            }
            writable = true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException error) {
            remainingTicks.clear();
            writable = false;
            plugin.getLogger().warning("Could not load " + file.getName() + ": " + error.getMessage());
        }
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(TIMED)) return refuse(sender);
        if (args.length > 1) return change(sender, args);
        if (args.length == 0 && !(sender instanceof Player)) return false;
        boolean self = args.length == 0 || sender instanceof Player && sender.getName().equalsIgnoreCase(args[0]);
        if (self && !sender.hasPermission(UNLIMITED)) return toggleTimed((Player) sender);

        if (!self && !sender.hasPermission(UNLIMITED + ".others")
                && !sender.hasPermission(TIMED + ".others")) return refuse(sender);
        Player target = self ? (Player) sender : plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !canSee.test(sender, target)) return notFound(sender, args[0]);
        if (active.remove(target.getUniqueId()) != null) save();
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        if (!enable) target.setFlying(false);
        String state = enable ? "&aenabled" : "&cdisabled";
        send(target, "fly-toggle", "{prefix} &fFly mode {state} &ffor &d{player}&f.", "{state}", state, "{player}", target.getName());
        if (!self) send(sender, "fly-toggle", "{prefix} &fFly mode {state} &ffor &d{player}&f.", "{state}", state, "{player}", target.getName());
        return true;
    }

    private boolean toggleTimed(Player player) {
        UUID id = player.getUniqueId();
        if (!writable) return storageFailed(player);
        if (active.remove(id) != null) {
            revokeTimedFlight(player);
            save();
            send(player, "tfly-off", "{prefix} &7Timed flight off: &f{time} &7left.", "{time}", format(ticks(id)));
        } else if (ticks(id) == 0L) {
            send(player, "tfly-none", "{prefix} &cYou have no timed flight left.");
        } else if (!survival(player) || player.getAllowFlight()) {
            send(player, "tfly-already-allowed", "{prefix} &7Flight is already available to you.");
        } else {
            landingUntil.remove(id);
            active.put(id, player);
            player.setAllowFlight(true);
            player.setFallDistance(0.0f);
            if (ticker == null) ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            if (config.raw().getBoolean(COUNT_FLYING)) {
                send(player, "tfly-on-flying", "{prefix} &aTimed flight on: &f{time} &aleft. It only runs while you fly.",
                        "{time}", format(ticks(id)));
            } else {
                send(player, "tfly-on", "{prefix} &aTimed flight on: &f{time} &aleft.", "{time}", format(ticks(id)));
            }
        }
        return true;
    }

    private boolean change(CommandSender sender, String[] args) {
        String action = canonicalAction(args[1]);
        boolean reset = "reset".equals(action);
        if (action == null || args.length != (reset ? 2 : 3)) return false;
        if (!sender.hasPermission(TIMED + "." + action)) return refuse(sender);
        long amount = reset ? 0 : parseSeconds(args[2]);
        if (amount < 0L) {
            send(sender, "tfly-invalid-time", "{prefix} &f{input} &cis not a time; use seconds or e.g. 30m, 2h, 1d or 1h30m.",
                    "{input}", args[2]);
            return true;
        }
        Player online = plugin.getServer().getPlayerExact(args[0]);
        if (online != null && !canSee.test(sender, online)) return notFound(sender, args[0]);
        UUID id = online != null ? online.getUniqueId() : playerInfo.findKnownPlayerId(args[0]);
        if (id == null) return notFound(sender, args[0]);
        if (!writable) return storageFailed(sender);

        long previous = ticks(id);
        long amountTicks = amount * TICKS_PER_SECOND;
        long total;
        switch (action) {
            case "add": total = clampTicks(previous + amountTicks); break;
            case "take": total = clampTicks(previous - amountTicks); break;
            default: total = amountTicks;
        }
        boolean wasDirty = dirty;
        if (total != previous) putBalance(id, total);
        if (!save()) {
            if (total != previous) putBalance(id, previous);
            dirty = wasDirty;
            return storageFailed(sender);
        }
        if (total == 0 && active.containsKey(id)) expire(active.remove(id));
        String shown = format(total);
        String name = online == null ? args[0] : online.getName();
        send(sender, "tfly-changed", "{prefix} &f{player} &anow has &f{time} &aof timed flight.",
                "{player}", name, "{time}", shown);
        if (online != null && online != sender) {
            send(online, "tfly-notify", "{prefix} &7Your timed flight is now &f{time}&7.", "{time}", shown);
        }
        plugin.getLogger().info("Timed flight " + action + " by " + sender.getName() + " for " + id
                + " amount=" + amount + " total=" + shown);
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (!sender.hasPermission(TIMED)) return Collections.emptyList();
        List<String> actions = Arrays.asList("set", "add", "take", "reset").stream()
                .filter(action -> sender.hasPermission(TIMED + "." + action)).collect(Collectors.toList());
        if (args.length == 1) {
            boolean others = !actions.isEmpty() || sender.hasPermission(UNLIMITED + ".others") || sender.hasPermission(TIMED + ".others");
            if (!others) return Collections.emptyList();
            return plugin.getServer().getOnlinePlayers().stream().filter(p -> canSee.test(sender, p))
                    .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        }
        if (args.length == 2) return actions.stream().filter(action -> action.startsWith(prefix)).collect(Collectors.toList());
        String action = args.length == 3 ? canonicalAction(args[1]) : null;
        if (action == null || "reset".equals(action) || !actions.contains(action)) return Collections.emptyList();
        return Arrays.asList("30s", "5m", "1h").stream().filter(time -> time.startsWith(prefix)).collect(Collectors.toList());
    }

    private void tick() {
        boolean onlyWhileFlying = config.raw().getBoolean(COUNT_FLYING);
        boolean hadActive = !active.isEmpty();
        boolean expired = false;
        Iterator<Player> flying = active.values().iterator();
        while (flying.hasNext()) {
            Player player = flying.next();
            if (!player.isOnline() || !survival(player) || player.hasPermission(UNLIMITED) || !player.getAllowFlight()) {
                flying.remove();
                continue;
            }
            if (!player.hasPermission(TIMED)) {
                flying.remove();
                revokeTimedFlight(player);
                continue;
            }
            if (onlyWhileFlying && !player.isFlying()) continue;
            UUID id = player.getUniqueId();
            long left = Math.max(0, ticks(id) - 1);
            putBalance(id, left);
            if (left == 0L) {
                flying.remove();
                expire(player);
                expired = true;
            } else if (left % TICKS_PER_SECOND == 0 && WARNINGS.contains(left / TICKS_PER_SECOND)) {
                send(player, "tfly-warning", "{prefix} &eTimed flight: &f{time} &eleft.", "{time}", format(left));
            }
        }
        ticksSinceSave++;
        if (expired || ticksSinceSave >= 30 * TICKS_PER_SECOND || (hadActive && active.isEmpty())) {
            save();
            ticksSinceSave = 0;
        }
        if (active.isEmpty() && !dirty) stopTimer();
    }

    private void expire(Player player) {
        revokeTimedFlight(player);
        send(player, "tfly-expired", "{prefix} &cYour timed flight has run out.");
    }

    private void revokeTimedFlight(Player player) {
        if (!survival(player) || player.hasPermission(UNLIMITED)) return;
        if (player.isFlying()) landingUntil.put(player.getUniqueId(), System.currentTimeMillis() + 15_000L);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0.0f);
    }

    boolean protectFall(Player player) {
        Long expires = landingUntil.remove(player.getUniqueId());
        return expires != null && expires >= System.currentTimeMillis();
    }

    void forget(Player player) {
        if (active.remove(player.getUniqueId()) != null) {
            revokeTimedFlight(player);
            save();
        }
        landingUntil.remove(player.getUniqueId());
    }

    void close() {
        for (Player player : new ArrayList<>(active.values())) forget(player);
        save();
        stopTimer();
        landingUntil.clear();
    }

    private void stopTimer() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        ticksSinceSave = 0;
    }

    private boolean save() {
        if (!dirty) return true;
        YamlConfiguration stored = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : remainingTicks.entrySet()) {
            stored.set("ticks." + entry.getKey(), entry.getValue());
        }
        try {
            LegacyCompat.saveYamlAtomically(stored, file);
            dirty = false;
            return true;
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + error.getMessage());
            return false;
        }
    }

    private static boolean survival(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private long ticks(UUID player) {
        return remainingTicks.getOrDefault(player, 0L);
    }

    private void putBalance(UUID player, long amount) {
        if (amount == 0L) remainingTicks.remove(player);
        else remainingTicks.put(player, amount);
        dirty = true;
    }

    private static long clampTicks(long amount) {
        return Math.max(0, Math.min(MAX_TICKS, amount));
    }

    private static long parseSeconds(String raw) {
        if (raw.matches("[0-9]{1,9}")) return Math.min(MAX_SECONDS, Long.parseLong(raw));
        long millis = LegacyCompat.parseDurationMillis(raw);
        return millis < 1000 ? -1 : Math.min(MAX_SECONDS, millis / 1000);
    }

    private static String format(long ticks) {
        long seconds = (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        return LegacyCompat.formatDuration(seconds * 1000L);
    }

    private static String canonicalAction(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "set": return "set";
            case "add":
            case "give": return "add";
            case "take":
            case "remove": return "take";
            case "reset": return "reset";
            default: return null;
        }
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }

    private boolean storageFailed(CommandSender sender) {
        send(sender, "tfly-storage", "{prefix} &cFlight time could not be read or saved. Try again shortly.");
        return true;
    }

    private boolean notFound(CommandSender sender, String name) {
        messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", name);
        return true;
    }

    private void send(CommandSender sender, String key, String fallback, String... replacements) {
        messages.send(sender, "player." + key, fallback, replacements);
    }
}
