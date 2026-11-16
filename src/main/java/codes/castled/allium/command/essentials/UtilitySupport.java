package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared runtime helpers for S25 parity utility commands: inventory recovery gating, vanish-aware
 * player resolution, world resolution, and structured {@code [UtilitiesAudit]} logging.
 */
public final class UtilitySupport {

    private UtilitySupport() {
    }

    public static boolean isInventoryBusy(PluginStart plugin, Player target) {
        if (plugin == null || target == null || plugin.getModuleManager() == null) {
            return false;
        }
        return (plugin.getModuleManager().getModule("kits") instanceof KitsModule kits
                && kits.isEnabled() && kits.kits() != null && kits.kits().busy(target))
                || (plugin.getModuleManager().getModule("mail") instanceof MailModule mail
                && mail.isEnabled() && mail.mail() != null && mail.mail().busy(target));
    }

    public static boolean guardInventoryNotBusy(PluginStart plugin, CommandSender sender, Player target) {
        if (!isInventoryBusy(plugin, target)) {
            return true;
        }
        send(plugin, sender, Messages.UTILITY_INVENTORY_BUSY, "{player}", target.getName());
        return false;
    }

    @Nullable
    public static Player resolveVisibleOnlinePlayer(PluginStart plugin, CommandSender sender, String rawName) {
        if (plugin.getServer() == null || rawName == null || rawName.isBlank()) {
            send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", rawName == null ? "" : rawName);
            return null;
        }
        Player target = plugin.getServer().getPlayer(rawName);
        if (target == null || !PlayerNameVisibility.canSeeTarget(sender, target)) {
            send(plugin, sender, Messages.PLAYER_NOT_FOUND, "{player}", rawName);
            return null;
        }
        return target;
    }

    @Nullable
    public static List<World> resolveWorlds(PluginStart plugin, CommandSender sender, @Nullable String selector) {
        if (plugin.getServer() == null) {
            return List.of();
        }
        if (selector == null || selector.isBlank()) {
            if (sender instanceof Player player && player.getWorld() != null) {
                return List.of(player.getWorld());
            }
            List<World> worlds = plugin.getServer().getWorlds();
            if (worlds == null || worlds.isEmpty()) {
                send(plugin, sender, Messages.WORLD_NOT_FOUND, "{world}", "default");
                return null;
            }
            return List.of(worlds.get(0));
        }
        if ("*".equals(selector.trim())) {
            List<World> worlds = plugin.getServer().getWorlds();
            return worlds == null ? List.of() : new ArrayList<>(worlds);
        }
        World world = plugin.getServer().getWorld(selector);
        if (world == null) {
            send(plugin, sender, Messages.WORLD_NOT_FOUND, "{world}", selector);
            return null;
        }
        return List.of(world);
    }

    public static void send(PluginStart plugin, CommandSender recipient, Messages message, String... replacements) {
        if (plugin != null && plugin.getMessageManager() != null) {
            plugin.getMessageManager().send(recipient, message, replacements);
            return;
        }
        String text = message.getDefault().replace("{prefix}", "[Allium]");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        recipient.sendMessage(text);
    }

    public static void audit(PluginStart plugin, CommandSender actor, String action, String detail) {
        if (plugin != null && plugin.logger() != null) {
            String actorName = actor == null ? "system" : actor.getName();
            plugin.logger().info("[UtilitiesAudit] actor=" + actorName + " action=" + action + " " + detail);
        }
    }

    public static String formatMaterialName(Material material) {
        if (material == null) {
            return "Unknown";
        }
        String[] parts = material.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    public static String describeItem(PluginStart plugin, @Nullable ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return "air";
        }
        if (plugin != null && plugin.getItemService() != null) {
            var identified = plugin.getItemService().identify(stack);
            if (identified.isPresent()) {
                var ref = identified.get();
                if (!ref.appearance().isVanilla() || ref.behaviorId().isPresent()) {
                    return ref.appearance().asString() + " (" + stack.getType().getKey() + ")";
                }
            }
        }
        return stack.getType().getKey().toString();
    }

    public static boolean isAir(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }
}
