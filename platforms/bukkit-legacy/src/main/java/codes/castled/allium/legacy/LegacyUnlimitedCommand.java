package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class LegacyUnlimitedCommand implements Listener {
    private static final String PERMISSION = "allium.unlimited";
    private static final Method IS_ITEM = method("isItem");
    private static final Method CRAFTING_REMAINDER = method("getCraftingRemainingItem");

    private final JavaPlugin plugin;
    private final LegacyPlayerSettings settings;
    private final LegacyCommandManager commands;

    LegacyUnlimitedCommand(JavaPlugin plugin, LegacyPlayerSettings settings, LegacyCommandManager commands) {
        this.plugin = plugin;
        this.settings = settings;
        this.commands = commands;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1 || args.length > 2) return false;
        if (!sender.hasPermission(PERMISSION) || (args.length == 2 && !sender.hasPermission(PERMISSION + ".others"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to change these unlimited blocks.");
            return true;
        }
        Player target = args.length == 2 ? plugin.getServer().getPlayerExact(args[1])
                : sender instanceof Player ? (Player) sender : null;
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Specify an online player.");
            return true;
        }
        Set<String> blocks = settings.unlimitedBlocks(target.getUniqueId());
        String raw = args[0].toLowerCase(Locale.ROOT);
        if ("list".equals(raw)) {
            sender.sendMessage("Unlimited blocks for " + target.getName() + ": "
                    + (blocks.isEmpty() ? "-" : String.join(", ", blocks)));
            return true;
        }
        String summary;
        if ("clear".equals(raw)) {
            blocks.clear();
            summary = "Cleared unlimited blocks for " + target.getName() + ".";
        } else {
            Material material = LegacyCompat.matchMaterial(raw);
            if (!eligible(material)) {
                sender.sendMessage(ChatColor.RED + "Choose a placeable block.");
                return true;
            }
            String item = key(material);
            boolean adding = !blocks.remove(item);
            if (adding) {
                if (!permitted(sender, item)) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission for that block.");
                    return true;
                }
                blocks.add(item);
            }
            summary = "Unlimited " + item + (adding ? " enabled for " : " disabled for ") + target.getName() + ".";
        }
        boolean saved = settings.setUnlimitedBlocks(target.getUniqueId(), blocks);
        sender.sendMessage(saved ? ChatColor.GREEN + summary : ChatColor.RED + "Could not save unlimited blocks.");
        return true;
    }

    List<String> complete(CommandSender sender, String prefix) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();
        Set<String> matches = new TreeSet<>();
        if ("list".startsWith(prefix)) matches.add("list");
        if ("clear".startsWith(prefix)) matches.add("clear");
        for (Material material : Material.values()) {
            String item = key(material);
            if (item.startsWith(prefix) && eligible(material) && permitted(sender, item)) matches.add(item);
        }
        return matches.stream().limit(64).collect(Collectors.toList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack placed = event.getItemInHand();
        if (event.isCancelled() || !event.canBuild() || placed == null || placed.hasItemMeta()
                || !eligible(placed.getType())) return;
        String item = key(placed.getType());
        if (!enabled(player, item)) return;

        ItemStack restored = placed.clone();
        int expected = restored.getAmount() - 1;
        if (expected < 0) return;
        int slot = player.getInventory().getHeldItemSlot();
        boolean offhand = event.getHand() == EquipmentSlot.OFF_HAND;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !event.canBuild() || !player.isOnline()
                    || plugin.getServer().getPlayer(player.getUniqueId()) != player || !enabled(player, item)) return;
            PlayerInventory inventory = player.getInventory();
            ItemStack current = offhand ? inventory.getItemInOffHand() : inventory.getItem(slot);
            if (expected == 0) {
                if (current != null && current.getType() != Material.AIR && current.getAmount() > 0) return;
            } else if (current == null || current.getAmount() != expected || !restored.isSimilar(current)) {
                return;
            }
            if (offhand) inventory.setItemInOffHand(restored);
            else inventory.setItem(slot, restored);
        });
    }

    private boolean enabled(Player player, String item) {
        return player.getGameMode() == GameMode.SURVIVAL && player.hasPermission(PERMISSION)
                && !commands.isDisabled("unlimited") && !commands.isModuleDisabled("unlimited")
                && settings.unlimitedBlocks(player.getUniqueId()).contains(item);
    }

    private static boolean permitted(CommandSender sender, String item) {
        String node = PERMISSION + ".item." + item;
        return sender.isPermissionSet(node) ? sender.hasPermission(node) : sender.hasPermission(PERMISSION + ".item.*");
    }

    private static boolean eligible(Material material) {
        if (material == null || material == Material.AIR || !material.isBlock() || material.name().startsWith("LEGACY_")) {
            return false;
        }
        try {
            return (IS_ITEM == null || Boolean.TRUE.equals(IS_ITEM.invoke(material)))
                    && (CRAFTING_REMAINDER == null || CRAFTING_REMAINDER.invoke(material) == null);
        } catch (ReflectiveOperationException | LinkageError error) {
            return false;
        }
    }

    private static String key(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    private static Method method(String name) {
        try {
            return Material.class.getMethod(name);
        } catch (NoSuchMethodException unavailable) {
            return null;
        }
    }
}
