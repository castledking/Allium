package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.xp.Experience;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /xp}: shows and changes experience. {@code give|take|set <player> <amount>[L]} and
 * {@code reset <player>}; vanilla's {@code add}, {@code remove} and {@code query} words and a
 * trailing {@code levels}/{@code points} word work too, so command blocks written for
 * {@code /xp add @p 5 levels} keep working. Levels keep the bar's fill, as vanilla does.
 */
public final class XpCommand implements SpecCommandHandler {

    public static final String PERMISSION_OTHERS = "allium.xp.others";
    public static final String PERMISSION_GIVE = "allium.xp.give";
    public static final String PERMISSION_TAKE = "allium.xp.take";
    public static final String PERMISSION_SET = "allium.xp.set";
    private static final String PERMISSION_SELECTOR = "minecraft.command.selector";

    private final PluginStart plugin;

    public XpCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.XP;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                context.sendInvalidArgs(spec().consoleUsage());
                return;
            }
            show(sender, self);
            return;
        }
        String action = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "add" -> "give";
            case "take", "remove" -> "take";
            case "set" -> "set";
            case "reset" -> "reset";
            case "show", "query" -> "show";
            default -> null;
        };
        if (action == null) {
            if (args.length != 1) {
                context.sendInvalidArgs(spec().selfUsage());
                return;
            }
            showTargets(context, args[0]);
            return;
        }
        if (action.equals("show")) {
            if (args.length < 2 || args.length > 3) {
                context.sendInvalidArgs("/xp show <player>");
                return;
            }
            showTargets(context, args[1]);
            return;
        }
        String permission = switch (action) {
            case "give" -> PERMISSION_GIVE;
            case "take" -> PERMISSION_TAKE;
            default -> PERMISSION_SET;
        };
        if (!sender.hasPermission(permission)) {
            UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", "xp " + action);
            return;
        }
        Experience.Amount amount;
        if (action.equals("reset")) {
            if (args.length != 2) {
                context.sendInvalidArgs("/xp reset <player>");
                return;
            }
            amount = new Experience.Amount(0, false);
        } else {
            if (args.length < 3 || args.length > 4) {
                context.sendInvalidArgs("/xp " + action + " <player> <amount>[L]");
                return;
            }
            Optional<Experience.Amount> parsed = Experience.parse(args[2], args.length == 4 ? args[3] : null);
            if (parsed.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.UTILITY_XP_INVALID, "{input}", args[2],
                        "{max}", Integer.toString(Experience.MAX_LEVEL));
                return;
            }
            amount = parsed.get();
            if (action.equals("take")) {
                amount = new Experience.Amount(-amount.value(), amount.levels());
            }
        }
        Experience.Amount delta = amount;
        List<Player> targets = targets(sender, args[1]);
        for (Player target : targets) {
            UtilitySupport.onTarget(plugin, sender, target, () -> {
                Experience.State before = state(target);
                Experience.State after = action.equals("give") || action.equals("take")
                        ? Experience.add(before, delta)
                        : Experience.set(before, delta);
                apply(target, after);
                String[] values = values(target, after);
                UtilitySupport.send(plugin, sender, Messages.UTILITY_XP_CHANGED, values);
                if (target != sender) {
                    UtilitySupport.send(plugin, target, Messages.UTILITY_XP_NOTIFY, values);
                }
                UtilitySupport.audit(plugin, sender, "xp_" + action, "target=" + target.getName()
                        + " before=" + before.total() + " after=" + after.total());
            });
        }
    }

    private void showTargets(BukkitCommandContext context, String raw) {
        CommandSender sender = context.sender();
        boolean self = sender instanceof Player p && (raw.equalsIgnoreCase(p.getName()) || raw.equals("@s"));
        if (!self && !sender.hasPermission(PERMISSION_OTHERS)) {
            UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", "xp for others");
            return;
        }
        for (Player target : targets(sender, raw)) {
            show(sender, target);
        }
    }

    private void show(CommandSender sender, Player target) {
        UtilitySupport.onTarget(plugin, sender, target, () -> {
            Experience.State state = state(target);
            UtilitySupport.send(plugin, sender, target == sender ? Messages.UTILITY_XP_SELF : Messages.UTILITY_XP_OTHER,
                    values(target, state));
        });
    }

    private static String[] values(Player target, Experience.State state) {
        return new String[]{"{player}", target.getName(), "{points}", Long.toString(state.total()),
                "{level}", Integer.toString(state.level()), "{next}", Integer.toString(state.toNextLevel())};
    }

    /** A visible player's name, or a selector for holders of vanilla's selector permission. */
    private List<Player> targets(CommandSender sender, String raw) {
        List<Player> out = new ArrayList<>();
        if (raw.startsWith("@")) {
            if (!sender.hasPermission(PERMISSION_SELECTOR)) {
                UtilitySupport.send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", raw);
                return out;
            }
            try {
                for (Entity entity : plugin.getServer().selectEntities(sender, raw)) {
                    if (entity instanceof Player player) {
                        out.add(player);
                    }
                }
            } catch (IllegalArgumentException invalid) {
                // reported as not found below
            }
            if (out.isEmpty()) {
                UtilitySupport.send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", raw);
            }
            return out;
        }
        Player player = plugin.getServer().getPlayer(raw);
        if (player == null || !PlayerNameVisibility.canSeeTarget(sender, player)) {
            UtilitySupport.send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", raw);
            return out;
        }
        out.add(player);
        return out;
    }

    static Experience.State state(Player player) {
        return Experience.fromBar(player.getLevel(), player.getExp());
    }

    static void apply(Player player, Experience.State state) {
        player.setLevel(state.level());
        player.setExp(state.progress());
        player.setTotalExperience((int) Math.min(Integer.MAX_VALUE, state.total()));
    }
}
