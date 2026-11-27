package codes.castled.allium.legacy;

import codes.castled.allium.essentials.InventoryClearFilter;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class LegacyInventoryClear {
    private static final String USE = "allium.clearinventory";
    private static final String OTHERS = USE + ".others";
    private static final String ALL = USE + ".all";
    private static final long CONFIRM_MILLIS = 30_000;

    private final JavaPlugin plugin;
    private final LegacyPlayerSettings settings;
    private final LegacyMessageManager messages;
    private final BiPredicate<CommandSender, Player> canSee;
    private final Map<Object, Pending> pending = new HashMap<>();

    LegacyInventoryClear(JavaPlugin plugin, LegacyPlayerSettings settings, LegacyMessageManager messages,
                         BiPredicate<CommandSender, Player> canSee) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.canSee = canSee;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE)) return refuse(sender);
        if (args.length > 3) return false;

        String first = args.length == 0 ? "" : args[0];
        boolean bulk = "*".equals(first) || "**".equals(first);
        Collection<? extends Player> targets;
        int itemIndex = 0;
        Predicate<ItemStack> matches = null;
        if (bulk) {
            if (!sender.hasPermission(ALL)) return refuse(sender);
            if ("**".equals(first)) {
                messages.send(sender, "utility.clear-offline-unavailable",
                        "{prefix} &cOffline playerdata editing is unavailable on this server version.");
                return true;
            }
            targets = plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> canSee.test(sender, player)).collect(Collectors.toList());
            itemIndex = 1;
        } else {
            Player named = args.length == 0 ? null : plugin.getServer().getPlayerExact(first);
            boolean self = sender instanceof Player;
            if (self && named == null && args.length > 0 && args.length <= 2) matches = filter(first);
            boolean selfFilter = self && (args.length == 0 || matches != null);
            if (selfFilter) {
                targets = Collections.singletonList((Player) sender);
            } else {
                if (args.length == 0) return false;
                if (!sender.equals(named) && !sender.hasPermission(OTHERS)) return refuse(sender);
                if (named == null || !canSee.test(sender, named)) {
                    messages.send(sender, "chat.player-not-found", "{prefix} &cPlayer not found.");
                    return true;
                }
                targets = Collections.singletonList(named);
                itemIndex = 1;
            }
        }

        String item = args.length > itemIndex ? args[itemIndex] : "*";
        if (matches == null) matches = filter(item);
        if (matches == null) {
            messages.send(sender, "utility.clear-invalid-material", "{prefix} &cUnknown material &f{material}&c.",
                    "{material}", item);
            return true;
        }
        int max = InventoryClearFilter.UNLIMITED;
        if (args.length > itemIndex + 1) {
            String count = args[itemIndex + 1];
            Integer parsed = InventoryClearFilter.parseMaxCount(count);
            if (parsed == null) {
                messages.send(sender, "error.number-invalid", "{prefix} &c'{input}' is not a valid number.", "{input}", count);
                return true;
            }
            max = parsed;
        }
        Set<UUID> targetIds = targets.stream().map(Player::getUniqueId).collect(Collectors.toSet());
        if (!confirmed(sender, args, targetIds)) return true;
        for (Player target : targets) clear(sender, target, item, matches, max);
        return true;
    }

    boolean toggleConfirmation(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("allium.clearinventoryconfirmtoggle")) return refuse(sender);
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.player-only", "{prefix} &cThis command can only be used by players.");
            return true;
        }
        if (args.length != 0) return false;
        UUID id = ((Player) sender).getUniqueId();
        String alias = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        boolean enabled;
        switch (alias) {
            case "clearconfirmon": enabled = true; break;
            case "clearconfirmoff":
            case "clearinventoryconfirmoff": enabled = false; break;
            default: enabled = !settings.confirmsInventoryClear(id);
        }
        if (!settings.setConfirmsInventoryClear(id, enabled)) {
            messages.send(sender, "error.save-failed", "{prefix} &cYour setting could not be saved.");
            return true;
        }
        forget(id);
        if (enabled) {
            messages.send(sender, "utility.clear-confirm-on", "{prefix} &aInventory clear confirmation enabled.");
        } else {
            messages.send(sender, "utility.clear-confirm-off", "{prefix} &eInventory clear confirmation disabled.");
        }
        return true;
    }

    List<String> complete(CommandSender sender, String[] args, String prefix) {
        if (args.length == 0 || args.length > 2 || !sender.hasPermission(USE)) return Collections.emptyList();
        Set<String> suggestions = new LinkedHashSet<>();
        if (args.length == 1) {
            if (sender.hasPermission(ALL) && "*".startsWith(prefix)) suggestions.add("*");
            if (sender.hasPermission(OTHERS)) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (canSee.test(sender, player) && player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        suggestions.add(player.getName());
                    }
                }
            }
            if (!(sender instanceof Player)) return new ArrayList<>(suggestions);
        } else if ("*".equals(args[0])) {
            if (!sender.hasPermission(ALL)) return Collections.emptyList();
        } else {
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null || !canSee.test(sender, target)
                    || (!sender.equals(target) && !sender.hasPermission(OTHERS))) return Collections.emptyList();
        }
        if ("all".startsWith(prefix)) suggestions.add("all");
        for (Material material : Material.values()) {
            String name = material.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(prefix) && !name.startsWith("legacy_") && isItem(material)) suggestions.add(name);
        }
        return new ArrayList<>(suggestions);
    }

    void forget(UUID player) {
        pending.remove(player);
    }

    void reset() {
        pending.clear();
    }

    private boolean confirmed(CommandSender sender, String[] args, Set<UUID> targets) {
        Object key = sender;
        if (sender instanceof Player) {
            UUID player = ((Player) sender).getUniqueId();
            if (!settings.confirmsInventoryClear(player)) return true;
            key = player;
        }
        long now = System.currentTimeMillis();
        pending.values().removeIf(attempt -> attempt.expires <= now);
        Pending previous = pending.remove(key);
        List<String> arguments = Arrays.asList(args.clone());
        if (previous != null && previous.arguments.equals(arguments) && previous.targets.equals(targets)) return true;
        pending.put(key, new Pending(arguments, targets, now + CONFIRM_MILLIS));
        messages.send(sender, "utility.clear-confirm", "{prefix} &eRun the same clear command again within 30 seconds to confirm.");
        return false;
    }

    private void clear(CommandSender sender, Player target, String item, Predicate<ItemStack> matches, int max) {
        PlayerInventory inventory = target.getInventory();
        ItemStack[] contents = inventory.getContents();
        int remaining = max;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getAmount() <= 0 || isAir(stack.getType()) || !matches.test(stack)) continue;
            int removed = Math.min(stack.getAmount(), remaining);
            remaining -= removed;
            if (removed == stack.getAmount()) {
                contents[slot] = null;
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(stack.getAmount() - removed);
                contents[slot] = reduced;
            }
        }
        int count = max - remaining;
        if (count > 0) inventory.setContents(contents);
        if (!target.equals(sender)) {
            messages.send(sender, "utility.clear-other", "{prefix} &aCleared &f{count} &aitem(s) from &f{player}&a's inventory.",
                    "{count}", Integer.toString(count), "{player}", target.getName());
        }
        messages.send(target, "utility.clear-self", "{prefix} &aCleared &f{count} &aitem(s) from your inventory.",
                "{count}", Integer.toString(count));
        plugin.getLogger().info("Inventory clear by " + sender.getName() + " for " + target.getName()
                + " filter=" + item + " max=" + max + " removed=" + count);
    }

    private static Predicate<ItemStack> filter(String item) {
        if (InventoryClearFilter.isAllToken(item)) return stack -> true;
        Set<Material> selected = new LinkedHashSet<>();
        for (String token : item.split(",", -1)) {
            Material material = LegacyCompat.matchMaterial(token);
            if (material == null || !isItem(material)) return null;
            selected.add(material);
        }
        return stack -> selected.contains(stack.getType());
    }

    private static boolean isItem(Material material) {
        return !isAir(material) && material.isItem();
    }

    private static boolean isAir(Material material) {
        String name = material.name();
        return material == Material.AIR || "CAVE_AIR".equals(name) || "VOID_AIR".equals(name) || "LEGACY_AIR".equals(name);
    }

    private boolean refuse(CommandSender sender) {
        messages.send(sender, "error.no-permission", "{prefix} &cYou don't have permission to do that.");
        return true;
    }

    private static final class Pending {
        private final List<String> arguments;
        private final Set<UUID> targets;
        private final long expires;

        private Pending(List<String> arguments, Set<UUID> targets, long expires) {
            this.arguments = arguments;
            this.targets = targets;
            this.expires = expires;
        }
    }
}
