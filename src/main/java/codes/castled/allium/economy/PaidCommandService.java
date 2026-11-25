package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Outcomes-aware cost and persistent cooldown coordinator for self-service convenience commands.
 *
 * <p>Uses {@link PaidActionService} with {@link EconomyReservations.Kind#COMMAND} so funds are
 * reserved before mutation and captured (with cooldown persisted) strictly when the command
 * delivers its effect; refusals, no-ops, and disconnects release the hold without charging or
 * recording a cooldown.
 */
public final class PaidCommandService implements Listener {

    private final PluginStart plugin;
    private final PaidActionService actions;
    @Nullable
    private final CommandCooldownRepository repository;
    @Nullable
    private final Executor io;
    private final LongSupplier clock;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private volatile CommandActionPolicy policy;
    private volatile boolean closed;

    public PaidCommandService(PluginStart plugin,
                              @Nullable CommandCooldownRepository repository,
                              @Nullable Executor io,
                              LongSupplier clock) {
        this(plugin, new PaidActionService(plugin, io), repository, io, clock, readPolicySafe(plugin));
    }

    public PaidCommandService(PluginStart plugin,
                              PaidActionService actions,
                              @Nullable CommandCooldownRepository repository,
                              @Nullable Executor io,
                              LongSupplier clock,
                              CommandActionPolicy initialPolicy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.repository = repository;
        this.io = io;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(initialPolicy, "initialPolicy");
    }

    public void enable() {
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                loadPlayer(online.getUniqueId());
            }
        }
    }

    public void close() {
        closed = true;
        actions.close();
        HandlerList.unregisterAll(this);
        cooldowns.clear();
        loaded.clear();
    }

    public boolean busy(@Nullable Player player) {
        return actions.busy(player);
    }

    public CommandActionPolicy policy() {
        return policy;
    }

    public codes.castled.allium.config.ReloadCoordinator.Prepared prepareReload(ConfigurationSection candidateRoot) {
        CommandActionPolicy next = readPolicy(candidateRoot);
        return codes.castled.allium.config.ReloadCoordinator.Prepared.of(() -> policy = next);
    }

    public void setPolicy(CommandActionPolicy next) {
        this.policy = Objects.requireNonNull(next, "next");
    }

    /**
     * Executes an outcomes-aware convenience command for {@code player}.
     *
     * @param player   the player paying the cost / incurring the cooldown
     * @param action   canonical action key in {@link CommandActionPolicy#SUPPORTED_ACTIONS}
     * @param precheck synchronous non-mutating check run before reserving funds (must send its own refusal message when returning {@code false})
     * @param deliver  owning-thread mutation returning {@code true} iff the effect actually occurred
     */
    public void execute(Player player, String action, BooleanSupplier precheck, BooleanSupplier deliver) {
        Objects.requireNonNull(player, "player");
        String key = CommandActionPolicy.normalizeKey(action);
        CommandActionPolicy.Rule rule = policy.ruleFor(key);
        boolean charge = rule.cost().minor() > 0 && !bypassesCost(player, key);
        boolean enforceCooldown = rule.cooldownMillis() > 0 && !bypassesCooldown(player, key);

        if (!charge && !enforceCooldown) {
            deliver.getAsBoolean();
            return;
        }
        if (enforceCooldown) {
            if (repository != null && !loaded.contains(player.getUniqueId())) {
                UtilitySupport.send(plugin, player, Messages.COMMAND_POLICY_LOADING);
                return;
            }
            long remaining = remainingCooldownMillis(player.getUniqueId(), key);
            if (remaining > 0L) {
                UtilitySupport.send(plugin, player, Messages.COMMAND_POLICY_COOLDOWN,
                        "{command}", "/" + key, "{time}", formatDuration(remaining));
                return;
            }
        }
        if (!precheck.getAsBoolean()) {
            return;
        }
        if (!charge) {
            if (deliver.getAsBoolean()) {
                recordCooldown(player.getUniqueId(), key, rule.cooldownMillis());
            }
            return;
        }

        UUID workflowId = UUID.randomUUID();
        String fingerprint = EconomyReservations.fingerprint(
                "command", key, player.getUniqueId().toString(), Long.toString(rule.cost().minor()), workflowId.toString());
        actions.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.COMMAND,
                workflowId,
                player,
                rule.cost(),
                fingerprint,
                "Paid command /" + key,
                () -> player.isOnline() && (!enforceCooldown || remainingCooldownMillis(player.getUniqueId(), key) == 0L),
                (stillValid, complete) -> {
                    if (!stillValid.getAsBoolean()) {
                        complete.accept(PaidActionOutcome.refused("precondition changed"));
                        return;
                    }
                    boolean ok = deliver.getAsBoolean();
                    if (ok) {
                        if (enforceCooldown) {
                            recordCooldown(player.getUniqueId(), key, rule.cooldownMillis());
                        }
                        complete.accept(PaidActionOutcome.delivered());
                    } else {
                        complete.accept(PaidActionOutcome.refused("command did not deliver"));
                    }
                },
                result -> reportResult(player, key, rule.cost(), result),
                true
        ));
    }

    private void reportResult(Player player, String action, Money price, PaidActionResult result) {
        if (!player.isOnline()) return;
        switch (result.status()) {
            case CAPTURED, CAPTURE_DEFERRED -> UtilitySupport.send(plugin, player, Messages.COMMAND_POLICY_CHARGED,
                    "{command}", "/" + action, "{price}", formatMoney(price));
            case INSUFFICIENT_FUNDS -> UtilitySupport.send(plugin, player, Messages.COMMAND_POLICY_INSUFFICIENT_FUNDS,
                    "{command}", "/" + action, "{price}", formatMoney(price));
            case BUSY -> UtilitySupport.send(plugin, player, Messages.COMMAND_POLICY_BUSY);
            case UNAVAILABLE, STORAGE_ERROR, UNKNOWN_RESERVE, RESERVATION_REFUSED ->
                    UtilitySupport.send(plugin, player, Messages.ECO_UNAVAILABLE);
            case RELEASED, RELEASE_DEFERRED -> {
                // Delivery/precheck already explained the refusal and the hold was released.
            }
        }
    }

    public long remainingCooldownMillis(UUID playerId, String action) {
        Map<String, Long> map = cooldowns.get(playerId);
        if (map == null) return 0L;
        String key = CommandActionPolicy.normalizeKey(action);
        Long expiresAt = map.get(key);
        if (expiresAt == null) return 0L;
        long remaining = expiresAt - clock.getAsLong();
        if (remaining <= 0L) {
            map.remove(key, expiresAt);
            return 0L;
        }
        return remaining;
    }

    private void recordCooldown(UUID playerId, String action, long cooldownMillis) {
        if (cooldownMillis <= 0L) return;
        long expiresAt = clock.getAsLong() + cooldownMillis;
        cooldowns.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(action, expiresAt);
        if (repository == null || io == null || closed) return;
        try {
            io.execute(() -> {
                if (closed) return;
                try {
                    repository.save(playerId, action, expiresAt);
                } catch (SQLException | RuntimeException failure) {
                    if (plugin.logger() != null) {
                        plugin.logger().warning("Could not persist command cooldown for " + playerId + " (" + action + "): " + failure.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // In-memory cooldown still protects the active session.
        }
    }

    private void loadPlayer(UUID playerId) {
        if (repository == null || io == null) {
            loaded.add(playerId);
            return;
        }
        try {
            io.execute(() -> {
                if (closed) return;
                try {
                    Map<String, Long> active = repository.loadActive(playerId, clock.getAsLong());
                    Map<String, Long> target = cooldowns.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
                    for (var entry : active.entrySet()) {
                        target.merge(entry.getKey(), entry.getValue(), Math::max);
                    }
                    loaded.add(playerId);
                } catch (SQLException | RuntimeException failure) {
                    if (plugin.logger() != null) {
                        plugin.logger().warning("Could not load command cooldowns for " + playerId + ": " + failure.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Leave unloaded so cooldown-gated commands report loading rather than bypassing.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (repository != null) {
            loaded.remove(id);
            cooldowns.remove(id);
        }
    }

    private static boolean bypassesCost(Player player, String action) {
        return player.hasPermission(CommandActionPolicy.PERMISSION_BYPASS_COST)
                || player.hasPermission(CommandActionPolicy.bypassCostPermission(action));
    }

    private static boolean bypassesCooldown(Player player, String action) {
        return player.hasPermission(CommandActionPolicy.PERMISSION_BYPASS_COOLDOWN)
                || player.hasPermission(CommandActionPolicy.bypassCooldownPermission(action));
    }

    private String formatMoney(Money amount) {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("economy") instanceof EconomyModule eco
                && eco.isEnabled() && eco.runtime() != null) {
            return eco.runtime().display().format(amount);
        }
        return EconomyDisplay.DEFAULT.format(amount);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(1L, (millis + 999L) / 1000L);
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        if (minutes < 60L) return remSeconds == 0 ? minutes + "m" : minutes + "m " + remSeconds + "s";
        long hours = minutes / 60L;
        long remMinutes = minutes % 60L;
        return remMinutes == 0 ? hours + "h" : hours + "h " + remMinutes + "m";
    }

    private static CommandActionPolicy readPolicySafe(PluginStart plugin) {
        try {
            return readPolicy(plugin.getConfig());
        } catch (IllegalArgumentException invalid) {
            if (plugin.logger() != null) {
                plugin.logger().warning("Invalid command-policy configuration (" + invalid.getMessage() + "); using free defaults.");
            }
            return CommandActionPolicy.empty();
        }
    }

    /** Strictly parses {@code command-policy.actions}; throws {@link IllegalArgumentException} on malformed config. */
    public static CommandActionPolicy readPolicy(@Nullable ConfigurationSection root) {
        if (root == null) return CommandActionPolicy.empty();
        Object rawSection = root.get("command-policy");
        if (rawSection == null) return CommandActionPolicy.empty();
        if (!(rawSection instanceof ConfigurationSection policySection)) {
            throw new IllegalArgumentException("command-policy must be a configuration section");
        }
        Object rawActions = policySection.get("actions");
        if (rawActions == null) return CommandActionPolicy.empty();
        if (!(rawActions instanceof ConfigurationSection actionsSection)) {
            throw new IllegalArgumentException("command-policy.actions must be a configuration section");
        }
        Map<String, CommandActionPolicy.Rule> parsed = new LinkedHashMap<>();
        for (String key : actionsSection.getKeys(false)) {
            String normalized = CommandActionPolicy.normalizeKey(key);
            if (!CommandActionPolicy.SUPPORTED_ACTIONS.contains(normalized)) {
                throw new IllegalArgumentException("unsupported command-policy action '" + key + "'");
            }
            Object rawEntry = actionsSection.get(key);
            if (!(rawEntry instanceof ConfigurationSection entry)) {
                throw new IllegalArgumentException("command-policy.actions." + key + " must be a configuration section");
            }
            Money cost = parseCost(entry.get("cost"), "command-policy.actions." + key + ".cost");
            long cooldownMillis = parseCooldownMillis(entry.get("cooldown-seconds"),
                    "command-policy.actions." + key + ".cooldown-seconds");
            parsed.put(normalized, new CommandActionPolicy.Rule(cost, cooldownMillis));
        }
        return new CommandActionPolicy(parsed);
    }

    private static Money parseCost(@Nullable Object raw, String path) {
        if (raw == null) return new Money(0);
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return new Money(0);
        try {
            return Money.parse(text, false);
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException(path + " must be a non-negative money amount, got '" + text + "'");
        }
    }

    private static long parseCooldownMillis(@Nullable Object raw, String path) {
        if (raw == null) return 0L;
        if (raw instanceof Number number) {
            double seconds = number.doubleValue();
            if (Double.isFinite(seconds) && seconds >= 0 && seconds <= CommandActionPolicy.MAX_COOLDOWN_MILLIS / 1000.0) {
                return Math.round(seconds * 1000.0);
            }
        }
        throw new IllegalArgumentException(path + " must be a finite number from 0 to 2592000");
    }
}
