package codes.castled.allium.legacy;

import codes.castled.allium.xp.Experience;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyExperienceCommand {
    private static final String BASE = "allium.xp";
    private static final String SELECTOR = "minecraft.command.selector";
    private static final Method NATIVE_SELECT = selectionMethod();
    private static final List<String> SIMPLE_SELECTORS = Arrays.asList("@s", "@a", "@p", "@r");

    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;

    LegacyExperienceCommand(JavaPlugin plugin, LegacyMessageManager messages, BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.messages = messages;
        this.canSee = canSee;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(BASE)) return refuse(sender);
        if (args.length == 0) return showSelf(sender);
        String action = action(args[0]);
        if (action == null) {
            if (args.length != 1) return false;
            return show(sender, args[0]);
        }
        if ("show".equals(action)) {
            if (args.length == 1 && "show".equalsIgnoreCase(args[0])) return showSelf(sender);
            if (args.length < 2 || args.length > 3) return false;
            return show(sender, args[1]);
        }
        if (!sender.hasPermission(permission(action))) return refuse(sender);
        Experience.Amount amount;
        if ("reset".equals(action)) {
            if (args.length != 2) return false;
            amount = new Experience.Amount(0, false);
        } else {
            if (args.length < 3 || args.length > 4) return false;
            Optional<Experience.Amount> parsed = Experience.parse(args[2], args.length == 4 ? args[3] : null);
            if (!parsed.isPresent()) {
                messages.send(sender, "utility.xp-invalid",
                        "{prefix} &f{input} &cis not an amount: use points like &f100 &cor levels like &f5L &c(up to level &f{max}&c).",
                        "{input}", args[2], "{max}", Integer.toString(Experience.MAX_LEVEL));
                return true;
            }
            amount = parsed.get();
            if ("take".equals(action)) amount = new Experience.Amount(-amount.value(), amount.levels());
        }
        for (Player player : targets(sender, args[1])) {
            Experience.State before = state(player);
            Experience.State after = "set".equals(action) || "reset".equals(action)
                    ? Experience.set(before, amount) : Experience.add(before, amount);
            apply(player, after);
            String[] values = values(player, after);
            messages.send(sender, "utility.xp-changed", "{prefix} &f{player} &anow has &f{points} &aexperience points (level &f{level}&a).", values);
            if (player != sender) {
                messages.send(player, "utility.xp-notify", "{prefix} &7Your experience was changed: &f{points} &7points (level &f{level}&7).", values);
            }
            plugin.getLogger().info("Experience " + action + " by " + sender.getName() + " for " + player.getUniqueId()
                    + " before=" + before.total() + " after=" + after.total());
        }
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (!sender.hasPermission(BASE) || args.length == 0) return Collections.emptyList();
        Set<String> suggestions = new LinkedHashSet<>();
        if (args.length == 1) {
            suggestions.add("show");
            if (sender.hasPermission(BASE + ".give")) suggestions.add("give");
            if (sender.hasPermission(BASE + ".take")) suggestions.add("take");
            if (sender.hasPermission(BASE + ".set")) suggestions.addAll(Arrays.asList("set", "reset"));
            suggestions.addAll(targetSuggestions(sender, false));
        } else {
            String action = action(args[0]);
            if (action == null) return Collections.emptyList();
            boolean viewing = "show".equals(action);
            if (!viewing && !sender.hasPermission(permission(action))) return Collections.emptyList();
            if (args.length == 2) suggestions.addAll(targetSuggestions(sender, !viewing));
            if (args.length == 3 && ("query".equalsIgnoreCase(args[0]))) suggestions.addAll(Arrays.asList("levels", "points"));
            if (args.length == 4 && !viewing && !"reset".equals(action)) suggestions.addAll(Arrays.asList("levels", "points"));
        }
        return suggestions.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).collect(Collectors.toList());
    }

    private List<String> targetSuggestions(CommandSender sender, boolean changing) {
        boolean others = changing || sender.hasPermission(BASE + ".others");
        List<String> suggestions = plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> (others || player == sender) && canSee.test(sender, player))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        if (sender.hasPermission(SELECTOR)) {
            if (sender instanceof Player) suggestions.add("@s");
            if (others) suggestions.addAll(Arrays.asList("@a", "@p", "@r"));
        }
        return suggestions;
    }

    private boolean showSelf(CommandSender sender) {
        if (!(sender instanceof Player)) return false;
        sendState(sender, (Player) sender);
        return true;
    }

    private boolean show(CommandSender sender, String raw) {
        boolean own = sender instanceof Player && (raw.equalsIgnoreCase(sender.getName()) || "@s".equals(raw));
        if (!own && !sender.hasPermission(BASE + ".others")) return refuse(sender);
        for (Player target : targets(sender, raw)) sendState(sender, target);
        return true;
    }

    private void sendState(CommandSender sender, Player player) {
        if (sender == player) {
            messages.send(sender, "utility.xp-self",
                    "{prefix} &7You have &f{points} &7experience points: level &f{level}&7, &f{next} &7more for the next level.",
                    values(player, state(player)));
        } else {
            messages.send(sender, "utility.xp-other",
                    "{prefix} &f{player} &7has &f{points} &7experience points: level &f{level}&7, &f{next} &7more for the next level.",
                    values(player, state(player)));
        }
    }

    private List<Player> targets(CommandSender sender, String raw) {
        List<Player> result;
        if (raw.startsWith("@")) {
            result = sender.hasPermission(SELECTOR) ? select(sender, raw) : Collections.emptyList();
        } else {
            Player player = plugin.getServer().getPlayerExact(raw);
            if (player == null) player = plugin.getServer().getPlayer(raw);
            result = player != null && canSee.test(sender, player) ? Collections.singletonList(player) : Collections.emptyList();
        }
        if (result.isEmpty()) {
            messages.send(sender, "error.player-not-found", "{prefix} &cPlayer &f{player} &cwas not found.", "{player}", raw);
        }
        return result;
    }

    private List<Player> select(CommandSender sender, String raw) {
        Server server = plugin.getServer();
        if (NATIVE_SELECT != null) {
            try {
                Collection<?> selected = (Collection<?>) NATIVE_SELECT.invoke(server, sender, raw);
                return selected.stream().filter(Player.class::isInstance).map(Player.class::cast)
                        .filter(player -> canSee.test(sender, player)).collect(Collectors.toList());
            } catch (ReflectiveOperationException | IllegalArgumentException invalid) {
                return Collections.emptyList();
            }
        }
        if (!SIMPLE_SELECTORS.contains(raw)) return Collections.emptyList();
        if ("@s".equals(raw)) {
            return sender instanceof Player ? Collections.singletonList((Player) sender) : Collections.emptyList();
        }
        List<Player> visible = server.getOnlinePlayers().stream()
                .filter(player -> canSee.test(sender, player)).collect(Collectors.toList());
        if ("@a".equals(raw) || visible.isEmpty()) return visible;
        if ("@r".equals(raw)) return Collections.singletonList(visible.get(ThreadLocalRandom.current().nextInt(visible.size())));

        Location origin = origin(sender, server);
        if (origin == null || origin.getWorld() == null) return Collections.emptyList();
        return visible.stream().filter(player -> origin.getWorld().equals(player.getWorld()))
                .min(Comparator.comparingDouble(player -> origin.distanceSquared(player.getLocation())))
                .map(Collections::singletonList).orElseGet(Collections::emptyList);
    }

    private static Location origin(CommandSender sender, Server server) {
        if (sender instanceof Player) return ((Player) sender).getLocation();
        if (sender instanceof BlockCommandSender) return ((BlockCommandSender) sender).getBlock().getLocation();
        List<World> worlds = server.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0).getSpawnLocation();
    }

    private static Method selectionMethod() {
        try {
            return Server.class.getMethod("selectEntities", CommandSender.class, String.class);
        } catch (NoSuchMethodException unavailable) {
            return null;
        }
    }

    private static String permission(String action) {
        return BASE + "." + ("reset".equals(action) ? "set" : action);
    }

    private static String action(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "show": case "query": return "show";
            case "give": case "add": return "give";
            case "take": case "remove": return "take";
            case "set": return "set";
            case "reset": return "reset";
            default: return null;
        }
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }

    private static String[] values(Player player, Experience.State state) {
        return new String[]{"{player}", player.getName(), "{points}", Long.toString(state.total()),
                "{level}", Integer.toString(state.level()), "{next}", Integer.toString(state.toNextLevel())};
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
