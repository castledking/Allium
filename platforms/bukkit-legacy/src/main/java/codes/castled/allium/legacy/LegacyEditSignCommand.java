package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class LegacyEditSignCommand {
    private final JavaPlugin plugin;
    private final LegacyMessageManager messages;
    private final Map<UUID, String[]> clipboard = new HashMap<>();

    LegacyEditSignCommand(JavaPlugin plugin, LegacyMessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            messages.sendPrefixed(sender, ChatColor.RED + "Only players can edit signs.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("allium.editsign")) return denied(player);
        if (args.length == 0) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        boolean set = "set".equals(action);
        boolean clear = "clear".equals(action);
        if (!(set && args.length >= 3 || clear && args.length <= 2
                || args.length == 1 && ("copy".equals(action) || "paste".equals(action)))) return false;
        boolean hasLine = set || clear && args.length == 2;
        if (hasLine && !args[1].matches("[1-4]")) {
            messages.sendPrefixed(sender, ChatColor.RED + "Choose a sign line from 1 to 4.");
            return true;
        }
        int line = hasLine ? Integer.parseInt(args[1]) - 1 : -1;
        String text = set ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (text.length() > 384) return false;
        Block block = player.getTargetBlock((Set<Material>) null, 6);
        BlockState state = block == null ? null : block.getState();
        if (!(state instanceof Sign)) {
            messages.send(sender, "utility.edit-sign-target", "{prefix} &cLook at a nearby sign.");
            return true;
        }
        if (!canEdit(player, block)) return denied(player);

        UUID id = player.getUniqueId();
        String[] changes = ((Sign) state).getLines().clone();
        if ("copy".equals(action)) {
            clipboard.put(id, changes);
            messages.send(player, "utility.edit-sign-copied", "{prefix} &aSign text copied.");
            return true;
        }
        if ("paste".equals(action)) {
            String[] copied = clipboard.get(id);
            if (copied == null) {
                messages.send(player, "utility.edit-sign-no-copy", "{prefix} &cCopy a sign first.");
                return true;
            }
            changes = copied.clone();
        } else if (line >= 0) {
            changes[line] = player.hasPermission("allium.editsign.color")
                    ? ChatColor.translateAlternateColorCodes('&', text) : text;
        } else {
            Arrays.fill(changes, "");
        }

        SignChangeEvent event = new SignChangeEvent(block, player, changes);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return denied(player);
        BlockState current = block.getState();
        if (!(current instanceof Sign)) return denied(player);
        Sign sign = (Sign) current;
        for (int i = 0; i < changes.length; i++) sign.setLine(i, event.getLine(i));
        if (sign.update(false, false)) {
            messages.send(player, "utility.edit-sign-changed", "{prefix} &aSign text updated.");
            return true;
        }
        return denied(player);
    }

    private boolean canEdit(Player player, Block block) {
        int radius = plugin.getServer().getSpawnRadius();
        List<World> worlds = plugin.getServer().getWorlds();
        if (!player.isOp() && radius > 0 && !worlds.isEmpty() && worlds.get(0).equals(block.getWorld())) {
            Location spawn = block.getWorld().getSpawnLocation();
            if (Math.abs(block.getX() - spawn.getBlockX()) <= radius
                    && Math.abs(block.getZ() - spawn.getBlockZ()) <= radius) return false;
        }
        BlockBreakEvent protection = new BlockBreakEvent(block, player);
        protection.setDropItems(false);
        plugin.getServer().getPluginManager().callEvent(protection);
        return !protection.isCancelled();
    }

    private boolean denied(Player player) {
        messages.send(player, "utility.block-tool.denied", "{prefix} &cYou cannot change that block.");
        return true;
    }

    List<String> complete(String[] args, String prefix) {
        List<String> candidates;
        if (args.length == 1) candidates = Arrays.asList("set", "clear", "copy", "paste");
        else if (args.length == 2 && ("set".equalsIgnoreCase(args[0]) || "clear".equalsIgnoreCase(args[0]))) {
            candidates = Arrays.asList("1", "2", "3", "4");
        } else return Collections.emptyList();
        return candidates.stream().filter(value -> value.startsWith(prefix)).collect(Collectors.toList());
    }

    void forget(UUID player) {
        clipboard.remove(player);
    }

    void clear() {
        clipboard.clear();
    }
}
