package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.essentials.InventoryClearFilter;
import codes.castled.allium.item.spec.ItemSpecLexer;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class ClearInventoryCommand implements SpecCommandHandler {
    private static final String ALL_PERMISSION = "allium.clearinventory.all";
    private static final long CONFIRM_MILLIS = 30_000;
    private record Pending(List<String> arguments, Object target, long expires) {}

    private final PluginStart plugin;
    private final CommandSpec spec;
    private final NamespacedKey confirmationDisabled;
    private final Map<Object, Pending> pending;

    public ClearInventoryCommand(PluginStart plugin) {
        this(plugin, CoreCommandSpecs.CLEARINVENTORY, new HashMap<>());
    }

    private ClearInventoryCommand(PluginStart plugin, CommandSpec spec, Map<Object, Pending> pending) {
        this.plugin = plugin;
        this.spec = spec;
        this.pending = pending;
        confirmationDisabled = new NamespacedKey(plugin, "clear_confirmation_disabled");
    }

    public ClearInventoryCommand confirmationToggle() {
        return new ClearInventoryCommand(plugin, CoreCommandSpecs.CLEAR_CONFIRM_TOGGLE, pending);
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        CommandSender sender = context.sender();
        if (spec == CoreCommandSpecs.CLEAR_CONFIRM_TOGGLE) {
            if (args.length != 0) {
                context.sendInvalidArgs(spec.selfUsage());
            } else if (sender instanceof Player player) {
                String label = context.label().toLowerCase(java.util.Locale.ROOT);
                setConfirmation(player, label.substring(label.lastIndexOf(':') + 1));
            } else {
                send(sender, Messages.PLAYER_ONLY);
            }
            return;
        }
        if (args.length > 3) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }

        String first = args.length == 0 ? "" : args[0];
        boolean offline = first.equals("**");
        boolean bulk = offline || first.equals("*");
        Player target = null;
        String item = null;
        Predicate<ItemStack> matches = null;
        int max = InventoryClearFilter.UNLIMITED;
        if (bulk) {
            if (!sender.hasPermission(ALL_PERMISSION)) {
                send(sender, Messages.NO_PERMISSION, "{cmd}", "clear " + first);
                return;
            }
            if (args.length >= 2) item = args[1];
        } else {
            Player visible = args.length == 0 ? null : visiblePlayer(sender, first);
            boolean selfFilter = sender instanceof Player && visible == null && args.length <= 2
                    && (args.length == 1 || args.length == 2 && InventoryClearFilter.parseMaxCount(args[1]) != null);
            if (selfFilter && (InventoryClearFilter.isAllToken(first) || (matches = filter(first)) != null)) {
                target = (Player) sender;
                item = first;
                if (args.length == 2) max = InventoryClearFilter.parseMaxCount(args[1]);
            } else {
                target = context.resolveSelfOrTarget(0);
                if (target == null) return;
                if (args.length >= 2) item = args[1];
            }
        }
        if (args.length == 3) {
            Integer count = InventoryClearFilter.parseMaxCount(args[2]);
            if (count == null) {
                send(sender, Messages.NUMBER_INVALID, "{input}", args[2]);
                return;
            }
            max = count;
        }
        if (matches == null) matches = item == null || InventoryClearFilter.isAllToken(item)
                ? stack -> true : filter(item);
        if (matches == null) {
            send(sender, Messages.UTILITY_CLEAR_INVALID_MATERIAL, "{material}", item);
            return;
        }

        if (offline) {
            if (!(plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation)
                    || !moderation.isEnabled() || !moderation.inspection().isOfflineEditingEnabled()) {
                send(sender, Messages.INSPECT_OFFLINE_EDIT_DISABLED);
                return;
            }
            if (!confirmed(sender, args, first)) return;
            int limit = max;
            Predicate<ItemStack> offlineFilter = matches;
            var online = plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            moderation.inspection().clearOfflineInventories(sender, online,
                    slots -> clearMatching(slots, offlineFilter, limit));
            return;
        }
        if (!confirmed(sender, args, bulk ? first : target.getUniqueId())) return;
        if (bulk) {
            for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                if (PlayerNameVisibility.canSeeTarget(sender, online)) clear(sender, online, matches, max, item);
            }
        } else {
            clear(sender, target, matches, max, item);
        }
    }

    private void setConfirmation(Player player, String label) {
        var data = player.getPersistentDataContainer();
        boolean enabled = switch (label) {
            case "clearconfirmon" -> true;
            case "clearconfirmoff", "clearinventoryconfirmoff" -> false;
            default -> data.has(confirmationDisabled, PersistentDataType.BYTE);
        };
        if (enabled) data.remove(confirmationDisabled);
        else data.set(confirmationDisabled, PersistentDataType.BYTE, (byte) 1);
        pending.remove(player.getUniqueId());
        send(player, enabled ? Messages.UTILITY_CLEAR_CONFIRM_ON : Messages.UTILITY_CLEAR_CONFIRM_OFF);
    }

    private boolean confirmed(CommandSender sender, String[] args, Object target) {
        if (sender instanceof Player player
                && player.getPersistentDataContainer().has(confirmationDisabled, PersistentDataType.BYTE)) return true;
        Object key = sender instanceof Player player ? player.getUniqueId() : sender;
        long now = System.currentTimeMillis();
        pending.values().removeIf(entry -> entry.expires() <= now);
        List<String> command = List.copyOf(Arrays.asList(args));
        Pending last = pending.remove(key);
        if (last != null && last.arguments().equals(command) && Objects.equals(last.target(), target)) return true;
        pending.put(key, new Pending(command, target, now + CONFIRM_MILLIS));
        send(sender, Messages.UTILITY_CLEAR_CONFIRM);
        return false;
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || args.length > 2 || !sender.hasPermission(spec.basePermission())) return List.of();
        String prefix = args[args.length - 1];
        String lower = prefix.toLowerCase(Locale.ROOT);
        var results = new LinkedHashSet<String>();
        if (args.length == 1) {
            if (sender.hasPermission(ALL_PERMISSION)) {
                for (String wildcard : List.of("*", "**")) if (wildcard.startsWith(prefix)) results.add(wildcard);
            }
            if (sender.hasPermission(spec.othersPermission())) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)
                            && PlayerNameVisibility.canSeeTarget(sender, player)) results.add(player.getName());
                }
            }
        }
        boolean targetAllowed = args.length == 1 ? sender instanceof Player
                : args[0].equals("*") || args[0].equals("**") ? sender.hasPermission(ALL_PERMISSION)
                : sender.hasPermission(spec.othersPermission()) && visiblePlayer(sender, args[0]) != null;
        if (targetAllowed && plugin.getModuleManager().getModule("items") instanceof ItemsModule items
                && items.isEnabled()) results.addAll(items.complete(prefix));
        return List.copyOf(results);
    }

    private void clear(CommandSender sender, Player target, Predicate<ItemStack> filter, int max, String item) {
        if (!UtilitySupport.guardInventoryNotBusy(plugin, sender, target)) return;
        if (plugin.getModuleManager().getModule("moderation") instanceof ModerationModule moderation
                && moderation.isEnabled() && moderation.inspection().hasActiveMutableSession(target.getUniqueId(),
                codes.castled.allium.moderation.InspectionLayout.InspectionType.INVSEE)) {
            send(sender, Messages.UTILITY_INVENTORY_BUSY, "{player}", target.getName());
            return;
        }
        ItemStack[] contents = target.getInventory().getContents();
        int removed = clearMatching(contents, filter, max);
        if (removed > 0) target.getInventory().setContents(contents);
        if (target != sender) send(sender, Messages.UTILITY_CLEAR_OTHER,
                "{count}", Integer.toString(removed), "{player}", target.getName());
        send(target, Messages.UTILITY_CLEAR_SELF, "{count}", Integer.toString(removed));
        UtilitySupport.audit(plugin, sender, "clearinventory",
                "target=" + target.getName() + " item=" + Objects.toString(item, "*") + " max=" + max + " removed=" + removed);
    }

    private static int clearMatching(ItemStack[] contents, Predicate<ItemStack> filter, int max) {
        int remaining = max;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (UtilitySupport.isAir(stack) || !filter.test(stack)) continue;
            int removed = Math.min(stack.getAmount(), remaining);
            remaining -= removed;
            if (removed == stack.getAmount()) contents[slot] = null;
            else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(stack.getAmount() - removed);
                contents[slot] = reduced;
            }
        }
        return max - remaining;
    }

    private Player visiblePlayer(CommandSender sender, String name) {
        Player target = plugin.getServer().getPlayer(name);
        return target != null && PlayerNameVisibility.canSeeTarget(sender, target) ? target : null;
    }

    private Predicate<ItemStack> filter(String input) {
        try {
            var module = plugin.getModuleManager().getModule("items");
            ItemsModule items = module instanceof ItemsModule enabled && enabled.isEnabled() ? enabled : null;
            List<Predicate<ItemStack>> predicates = new ArrayList<>();
            for (String entry : ItemSpecLexer.splitTopLevel(input, ',')) {
                String alias = items == null ? null : items.itemAlias(entry);
                String resolved = alias == null ? entry : alias;
                Material material = Material.matchMaterial(InventoryClearFilter.normalizeMaterialToken(resolved));
                if (material != null && material.isItem() && !material.isAir()) {
                    predicates.add(stack -> stack.getType() == material);
                    continue;
                }
                if (items == null) return null;
                var parsed = items.parser().parse(resolved);
                if (parsed.items().size() != 1 || parsed.amount().isPresent() || parsed.equip()
                        || parsed.overflow().isPresent()) return null;
                var reference = parsed.items().get(0);
                if (reference.amount().isPresent()) return null;
                if (reference.raw().isEmpty() && parsed.tokens().isEmpty()) {
                    if (reference.key().provider().equals("minecraft")) return null;
                    predicates.add(stack -> plugin.getItemService().identify(stack)
                            .map(found -> found.appearance().equals(reference.key())).orElse(false));
                } else {
                    var prepared = items.library().build(parsed, false);
                    if (prepared.items().size() != 1 || prepared.items().get(0).skull() != null) return null;
                    ItemStack template = prepared.items().get(0).stack();
                    predicates.add(template::isSimilar);
                }
            }
            return stack -> predicates.stream().anyMatch(predicate -> predicate.test(stack));
        } catch (IllegalArgumentException | codes.castled.allium.item.library.ItemBuildException invalid) {
            return null;
        }
    }

    private void send(CommandSender sender, Messages message, String... values) {
        UtilitySupport.send(plugin, sender, message, values);
    }
}
