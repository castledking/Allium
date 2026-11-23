package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
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
                && mail.isEnabled() && mail.mail() != null && mail.mail().busy(target))
                || (plugin.getModuleManager().getModule("economy") instanceof EconomyModule economy
                && economy.isEnabled() && economy.runtime() != null && economy.runtime().cheques() != null
                && economy.runtime().cheques().busy(target));
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
        onOwner(plugin, recipient, () -> sendDirect(plugin, recipient, message, replacements));
    }

    private static void sendDirect(PluginStart plugin, CommandSender recipient, Messages message, String... replacements) {
        if (plugin != null && plugin.getMessageManager() != null) {
            plugin.getMessageManager().send(recipient, message, replacements);
            return;
        }
        String text = message.getDefault().replace("{prefix}", Messages.PREFIX.getDefault());
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        recipient.sendMessage(codes.castled.allium.message.MessageManager.colorize(text));
    }

    /** Runs {@code action} on the thread owning {@code recipient} (inline when already owned or not on Folia). */
    public static void onOwner(PluginStart plugin, CommandSender recipient, Runnable action) {
        if (!BukkitServerThread.isFolia() || plugin == null || plugin.getScheduler() == null) {
            action.run();
            return;
        }
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), recipient, () -> {
            if (!(recipient instanceof Player player) || player.isOnline()) {
                action.run();
            }
        });
    }

    /** Runs {@code action} on {@code target}'s owning region (inline when already owned or not on Folia). */
    public static void onTarget(PluginStart plugin, CommandSender sender, Player target, Runnable action) {
        onTarget(plugin, sender, target, action, () -> {});
    }

    /**
     * Runs {@code action} on {@code target}'s owning region (inline when already owned or not on Folia).
     * If {@code target} disconnects, reconnects as a new connection, or retires before execution on Folia,
     * invokes {@code offline} on {@code sender}'s owner instead.
     */
    public static void onTarget(PluginStart plugin, CommandSender sender, Player target, Runnable action, Runnable offline) {
        if (!BukkitServerThread.isFolia() || plugin == null || plugin.getScheduler() == null) {
            action.run();
            return;
        }
        var owner = BukkitServerThread.entity(plugin, target);
        if (owner.isServerThread()) {
            if (target.isOnline()) {
                action.run();
            } else {
                onOwner(plugin, sender, offline);
            }
            return;
        }
        java.util.UUID id = target.getUniqueId();
        Runnable notifyOffline = () -> onOwner(plugin, sender, offline);
        TaskHandle handle = plugin.getScheduler().runOn(owner, () -> {
            Player current = plugin.getServer() == null ? target : plugin.getServer().getPlayer(id);
            if (current != target || !target.isOnline()) {
                notifyOffline.run();
                return;
            }
            action.run();
        }, notifyOffline, 0L);
        if (handle == TaskHandle.NONE) {
            notifyOffline.run();
        }
    }

    /** Runs {@code action} on the global region thread on Folia (inline when already global or not on Folia). */
    public static void onGlobal(PluginStart plugin, Runnable action) {
        if (!BukkitServerThread.isFolia() || plugin == null || plugin.getScheduler() == null) {
            action.run();
            return;
        }
        if (plugin.getScheduler().isServerThread()) {
            action.run();
        } else {
            plugin.getScheduler().runOnServer(action);
        }
    }

    /** Runs {@code action} on the region owning {@code (world, chunkX, chunkZ)} on Folia (inline when owned or not on Folia). */
    public static void onRegion(PluginStart plugin, World world, int chunkX, int chunkZ, Runnable action) {
        if (!BukkitServerThread.isFolia() || plugin == null || plugin.getScheduler() == null || world == null) {
            action.run();
            return;
        }
        var region = BukkitServerThread.region(plugin, world, chunkX, chunkZ);
        if (region.isServerThread()) {
            action.run();
        } else {
            plugin.getScheduler().runOn(region, action, () -> {}, 0L);
        }
    }

    public static void audit(PluginStart plugin, CommandSender actor, String action, String detail) {
        if (plugin != null && plugin.logger() != null) {
            String actorName = actor == null ? "system" : actor.getName();
            plugin.logger().info("[UtilitiesAudit] actor=" + actorName + " action=" + action + " " + detail);
        }
    }

    public static String formatMaterialName(Material material) {
        return material == null ? "Unknown" : titleCase(material.name());
    }

    /**
     * {@code DIAMOND_SWORD}, {@code diamond_sword} and {@code survival} become {@code Diamond Sword}
     * and {@code Survival}: the one capitalisation every player-facing item, mode and entity name uses.
     */
    public static String titleCase(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("[_\\s]+")) {
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

    /** {@code minecraft:fire_aspect} is shown as {@code Fire Aspect}. */
    public static String enchantName(@Nullable org.bukkit.NamespacedKey key) {
        return key == null ? "Unknown" : titleCase(key.getKey());
    }

    /**
     * How messages name an item, as {@code /give} does: its custom name without colours, else the
     * title-cased material ({@code Diamond Sword}).
     */
    public static String itemName(@Nullable ItemStack item) {
        if (item == null) {
            return "Unknown";
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String name = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).replaceAll("[\\p{Cntrl}]", " ").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return formatMaterialName(item.getType());
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
