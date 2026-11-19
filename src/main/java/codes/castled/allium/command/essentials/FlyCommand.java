package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.moderation.DurationParser;
import codes.castled.allium.placeholder.PlaceholderFormats;
import codes.castled.allium.player.TimedFlight;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /fly [player]} toggles flight. A player without {@code allium.fly} toggles timed flight
 * instead ({@link TimedFlight}), when they have time banked. {@code /fly <player> <set|add|take>
 * <time>} and {@code /fly <player> reset} change anyone's banked time (online or not) with
 * {@code allium.tfly.<action>}; a time is {@code 90}, {@code 30m}, {@code 2h}, {@code 1d} or
 * {@code 1h30m}.
 */
public final class FlyCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final Supplier<TimedFlight> timed;

    public FlyCommand(PluginStart plugin) {
        this(plugin, () -> null);
    }

    public FlyCommand(PluginStart plugin, Supplier<TimedFlight> timed) {
        this.plugin = plugin;
        this.timed = timed;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.FLY;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length >= 2) {
            change(context);
            return;
        }
        boolean self = args.length == 0 || sender instanceof Player p && args[0].equalsIgnoreCase(p.getName());
        if (self && sender instanceof Player player && !player.hasPermission("allium.fly")) {
            timedToggle(player);
            return;
        }
        Player target = context.resolveSelfOrTarget(0);
        if (target == null) {
            return;
        }

        boolean enabled = !target.getAllowFlight();
        target.setAllowFlight(enabled);
        target.setFlying(enabled);
        if(plugin.getModuleManager()!=null&&plugin.getModuleManager().getModule("essentials") instanceof codes.castled.allium.module.EssentialsModule module&&module.flights()!=null)module.flights().changed(target);

        String state = enabled ? "&aenabled" : "&cdisabled";
        context.sendOutcome(context.sender(), "toggle", "{state}", state, "{player}", target.getName());
        if (target != context.sender()) {
            context.sendOutcome(target, "toggle", "{state}", state, "{player}", target.getName());
        }
    }

    private void timedToggle(Player player) {
        TimedFlight flight = timed.get();
        if (flight == null) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "fly");
            return;
        }
        switch (flight.toggle(player)) {
            case ON -> UtilitySupport.send(plugin, player, Messages.TFLY_ON, "{time}", time(flight.seconds(player.getUniqueId())));
            case OFF -> UtilitySupport.send(plugin, player, Messages.TFLY_OFF, "{time}", time(flight.seconds(player.getUniqueId())));
            case NONE -> UtilitySupport.send(plugin, player, Messages.TFLY_NONE);
            case LOADING -> UtilitySupport.send(plugin, player, Messages.TFLY_LOADING);
        }
    }

    /** {@code /fly <player> <set|add|take> <time>} or {@code /fly <player> reset}. */
    private void change(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        TimedFlight.Change change = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> TimedFlight.Change.SET;
            case "add", "give" -> TimedFlight.Change.ADD;
            case "take", "remove" -> TimedFlight.Change.TAKE;
            case "reset" -> TimedFlight.Change.RESET;
            default -> null;
        };
        if (change == null || change == TimedFlight.Change.RESET && args.length != 2
                || change != TimedFlight.Change.RESET && args.length != 3) {
            context.sendInvalidArgs(change == TimedFlight.Change.RESET ? "/fly <player> reset" : "/fly <player> <set|add|take> <time>");
            return;
        }
        String node = "allium.tfly." + change.name().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(node)) {
            UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", "fly " + args[1].toLowerCase(Locale.ROOT));
            return;
        }
        long seconds = 0;
        if (change != TimedFlight.Change.RESET) {
            OptionalLong parsed = parseSeconds(args[2]);
            if (parsed.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.TFLY_INVALID_TIME, "{input}", args[2]);
                return;
            }
            seconds = parsed.getAsLong();
        }
        TimedFlight flight = timed.get();
        if (flight == null) {
            UtilitySupport.send(plugin, sender, Messages.TFLY_STORAGE);
            return;
        }
        long amount = seconds;
        resolve(sender, args[0], (id, name) -> flight.change(id, change, amount, total -> {
            if (total.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.TFLY_STORAGE);
                return;
            }
            String shown = time(total.getAsLong());
            UtilitySupport.send(plugin, sender, Messages.TFLY_CHANGED, "{player}", name, "{time}", shown);
            Player online = plugin.getServer().getPlayer(id);
            if (online != null && online != sender) {
                UtilitySupport.send(plugin, online, Messages.TFLY_NOTIFY, "{time}", shown);
            }
            UtilitySupport.audit(plugin, sender, "tfly_" + change.name().toLowerCase(Locale.ROOT),
                    "target=" + id + " amount=" + amount + " total=" + total.getAsLong());
        }));
    }

    /** An online player by name, else the shared name index (offline players). */
    private void resolve(CommandSender sender, String raw, java.util.function.BiConsumer<UUID, String> then) {
        Player online = plugin.getServer().getPlayerExact(raw);
        if (online != null && codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(sender, online)) {
            then.accept(online.getUniqueId(), online.getName());
            return;
        }
        var names = plugin.getPlayerNames();
        if (names == null || !raw.matches("[A-Za-z0-9_]{1,16}")) {
            UtilitySupport.send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", raw);
            return;
        }
        names.resolve(raw).whenComplete((found, error) -> plugin.getScheduler().runOnServer(() -> {
            if (error != null || found == null || found.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", raw);
                return;
            }
            then.accept(found.get().uuid(), found.get().name());
        }));
    }

    /** {@code 90} (seconds), {@code 30m}, {@code 2h}, {@code 1d}, {@code 1h30m}. */
    static OptionalLong parseSeconds(@Nullable String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        String text = raw.trim();
        if (text.matches("[0-9]{1,9}")) {
            return OptionalLong.of(Long.parseLong(text));
        }
        OptionalLong millis = DurationParser.parseMillis(text);
        return millis.isPresent() && millis.getAsLong() >= 1000 ? OptionalLong.of(millis.getAsLong() / 1000) : OptionalLong.empty();
    }

    private static String time(long seconds) {
        return PlaceholderFormats.duration(seconds);
    }
}
