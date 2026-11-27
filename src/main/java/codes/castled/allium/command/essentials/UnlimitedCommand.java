package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class UnlimitedCommand implements SpecCommandHandler, Listener {

    private static final String USAGE = "/unlimited <list|item|clear> [player]";
    private static final String BASE = "allium.unlimited";
    private static final String OTHERS = BASE + ".others";
    public static final CommandSpec SPEC = new CommandSpec(
            "unlimited", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("ul", "unl"),
            "Manage blocks that are replenished after placement", USAGE, USAGE, BASE, OTHERS,
            List.of(CommandArgSpec.required("item", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("player", OTHERS, SuggestionSpec.onlinePlayers(OTHERS))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;
    private final NamespacedKey key;
    private volatile boolean closed;

    public UnlimitedCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "unlimited_blocks");
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length == 0 || args.length > 2) {
            context.sendInvalidArgs(USAGE);
            return;
        }
        Player target = context.resolveSelfOrTarget(1);
        if (target == null) return;
        var sender = context.sender();
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> UtilitySupport.onTarget(plugin, sender, target, () -> {
                Set<String> blocks = blocks(target);
                UtilitySupport.send(plugin, sender, Messages.UNLIMITED_LIST,
                        "{player}", target.getName(), "{items}", blocks.isEmpty() ? "-" : String.join(", ", blocks));
            });
            case "clear" -> UtilitySupport.onTarget(plugin, sender, target, () -> {
                target.getPersistentDataContainer().remove(key);
                UtilitySupport.send(plugin, sender, Messages.UNLIMITED_CLEARED, "{player}", target.getName());
                UtilitySupport.audit(plugin, sender, "unlimited", "target=" + target.getName() + " cleared");
            });
            default -> {
                var module = plugin.getModuleManager().getModule("items");
                Material material = module instanceof ItemsModule items && items.isEnabled()
                        ? items.material(args[0]) : Material.matchMaterial(args[0]);
                if (!eligible(material)) {
                    UtilitySupport.send(plugin, sender, Messages.UNLIMITED_INVALID);
                    return;
                }
                String item = material.getKey().getKey();
                boolean allowed = canToggle(sender, item);
                UtilitySupport.onTarget(plugin, sender, target, () -> {
                    Set<String> blocks = blocks(target);
                    boolean enabled = !blocks.remove(item);
                    if (enabled) {
                        if (!allowed) {
                            UtilitySupport.send(plugin, sender, Messages.NO_PERMISSION, "{cmd}", "unlimited " + item);
                            return;
                        }
                        blocks.add(item);
                    }
                    target.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.join(",", blocks));
                    UtilitySupport.send(plugin, sender, enabled ? Messages.UNLIMITED_ON : Messages.UNLIMITED_OFF,
                            "{player}", target.getName(), "{item}", item);
                    UtilitySupport.audit(plugin, sender, "unlimited", "target=" + target.getName() + " item=" + item
                            + " enabled=" + enabled);
                });
            }
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        var values = new TreeSet<>(List.of("list", "clear"));
        for (Material material : Material.values()) {
            if (!eligible(material)) continue;
            String name = material.getKey().getKey();
            if (name.startsWith(prefix) && canToggle(sender, name)) values.add(name);
        }
        return values.stream().filter(value -> value.startsWith(prefix)).limit(64).toList();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack held = event.getItemInHand();
        Material material = held.getType();
        if (plugin.getCommandManager().getFamilyPolicy().isDisabled(SPEC.canonicalName())
                || player.getGameMode() != GameMode.SURVIVAL || !event.canBuild()
                || !player.hasPermission(BASE) || !eligible(material) || held.hasItemMeta()) return;
        String name = material.getKey().getKey();
        if (!blocks(player).contains(name)) return;
        var hand = event.getHand();
        int slot = player.getInventory().getHeldItemSlot();
        int expectedAmount = held.getAmount() - 1;
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            if (closed || plugin.getCommandManager().getFamilyPolicy().isDisabled(SPEC.canonicalName())
                    || event.isCancelled() || !event.canBuild() || !player.isOnline()
                    || player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission(BASE)
                    || !blocks(player).contains(name) || UtilitySupport.isInventoryBusy(plugin, player)
                    || plugin.getServer().getPlayer(player.getUniqueId()) != player) return;
            var inventory = player.getInventory();
            ItemStack current = hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                    ? inventory.getItemInOffHand() : inventory.getItem(slot);
            if (expectedAmount == 0 ? !UtilitySupport.isAir(current)
                    : current == null || current.getType() != material || current.hasItemMeta()
                            || current.getAmount() != expectedAmount) return;
            ItemStack restored = new ItemStack(material, expectedAmount + 1);
            if (hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) inventory.setItemInOffHand(restored);
            else inventory.setItem(slot, restored);
        }, () -> {}, 50);
    }

    public void close() {
        closed = true;
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    private Set<String> blocks(Player player) {
        String stored = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return stored == null || stored.isEmpty() ? new TreeSet<>() : new TreeSet<>(Arrays.asList(stored.split(",")));
    }

    private static boolean eligible(Material material) {
        return material != null && !material.isLegacy() && material.isBlock() && material.isItem() && !material.isAir()
                && material.getCraftingRemainingItem() == null;
    }

    private static boolean canToggle(CommandSender sender, String item) {
        String node = BASE + ".item." + item;
        return sender.isPermissionSet(node) ? sender.hasPermission(node) : sender.hasPermission(BASE + ".item.*");
    }
}
