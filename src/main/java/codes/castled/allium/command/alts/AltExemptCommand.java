package codes.castled.allium.command.alts;

import codes.castled.allium.PluginStart;
import codes.castled.allium.alts.AltService;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code /altexempt <add|remove|check> <player> [reason]}: keeps an account out of alt detection
 * (legacy Allium's alt exemptions). Adding an exemption also removes the alt group.
 */
public final class AltExemptCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final AltService alts;

    public AltExemptCommand(PluginStart plugin, AltService alts) {
        this.plugin = plugin;
        this.alts = alts;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ALTEXEMPT;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2 || !(action.equals("add") || action.equals("remove") || action.equals("check"))) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason given";
        String actor = sender instanceof Player p ? p.getName() : "Console";
        resolve(sender, args[1], target -> alts.execute(() -> {
            try {
                switch (action) {
                    case "add" -> {
                        alts.repository().exempt(target.uuid(), actor, reason, System.currentTimeMillis());
                        plugin.getScheduler().runOnServer(() -> {
                            alts.removeGroup(plugin.getServer().getOfflinePlayer(target.uuid()), alts.settings().group());
                            UtilitySupport.send(plugin, sender, Messages.ALTS_EXEMPT_ADDED, "{player}", target.name(),
                                    "{group}", alts.settings().group());
                        });
                    }
                    case "remove" -> {
                        boolean removed = alts.repository().unexempt(target.uuid());
                        tell(sender, removed ? Messages.ALTS_EXEMPT_REMOVED : Messages.ALTS_EXEMPT_NONE, "{player}", target.name());
                    }
                    default -> {
                        var exemption = alts.repository().exemption(target.uuid());
                        if (exemption.isEmpty()) {
                            tell(sender, Messages.ALTS_EXEMPT_NONE, "{player}", target.name());
                        } else {
                            tell(sender, Messages.ALTS_EXEMPT_STATUS, "{player}", target.name(), "{actor}", exemption.get().actor(),
                                    "{reason}", exemption.get().reason());
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.logger().warning("/altexempt " + action + " failed: " + e.getMessage());
                tell(sender, Messages.ALTS_UNAVAILABLE);
            }
        }, new java.util.concurrent.CompletableFuture<Void>() {
            @Override
            public boolean completeExceptionally(Throwable ex) {
                tell(sender, Messages.ALTS_UNAVAILABLE);
                return true;
            }
        }));
    }

    private void tell(CommandSender sender, Messages message, String... replacements) {
        UtilitySupport.send(plugin, sender, message, replacements);
    }

    private void resolve(CommandSender sender, String name, Consumer<NameCache.Entry> then) {
        if (plugin.getPlayerNames() == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            UtilitySupport.send(plugin, sender, Messages.INFO_SEEN_UNKNOWN, "{player}", name);
            return;
        }
        plugin.getPlayerNames().resolve(name).whenComplete((entry, error) ->
                BukkitServerThread.runFor(plugin, plugin.getScheduler(), sender, () -> {
                    Optional<NameCache.Entry> found = error == null ? entry : Optional.empty();
                    if (found.isEmpty()) {
                        plugin.getMessageManager().send(sender, Messages.INFO_SEEN_UNKNOWN, "{player}", name);
                    } else {
                        then.accept(found.get());
                    }
                }, () -> {}, 0L));
    }
}
