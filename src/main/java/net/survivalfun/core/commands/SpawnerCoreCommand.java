package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.spawnercraft.SpawnerCoreManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Give spawner cores to players.
 * Usage: /spawnercore give &lt;player&gt; &lt;mob&gt; [amount]
 */
public class SpawnerCoreCommand implements CommandExecutor, TabCompleter {

    private static final Set<EntityType> ALLOWED_MOBS = new HashSet<>(Arrays.asList(
        EntityType.ENDERMAN,
        EntityType.IRON_GOLEM,
        EntityType.BLAZE,
        EntityType.MAGMA_CUBE,
        EntityType.SLIME,
        EntityType.GLOW_SQUID,
        EntityType.SQUID,
        EntityType.CREEPER,
        EntityType.SPIDER,
        EntityType.SKELETON,
        EntityType.ZOMBIE,
        EntityType.COW,
        EntityType.SHEEP,
        EntityType.PIG,
        EntityType.CHICKEN
    ));

    private static final List<String> ALLOWED_MOB_NAMES = Arrays.asList(
        "enderman", "iron_golem", "blaze", "magma_cube", "slime",
        "glow_squid", "squid", "creeper", "spider", "skeleton",
        "zombie", "cow", "sheep", "pig", "chicken"
    );

    private final PluginStart plugin;

    public SpawnerCoreCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("allium.spawnercore") && !sender.hasPermission("allium.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /spawnercore give <player> <mob> [amount]");
            return true;
        }

        if (!args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§cUsage: /spawnercore give <player> <mob> [amount]");
            return true;
        }

        SpawnerCoreManager coreManager = plugin.getSpawnerCoreManager();
        if (coreManager == null) {
            sender.sendMessage("§cSpawner cores are not enabled.");
            return true;
        }

        String mobName = args[2].toLowerCase(Locale.ENGLISH).replace(" ", "_");
        EntityType entityType = null;
        try {
            entityType = EntityType.valueOf(mobName.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
        }

        if (entityType == null || !ALLOWED_MOBS.contains(entityType)) {
            sender.sendMessage("§cInvalid or not allowed mob. Allowed: " + String.join(", ", ALLOWED_MOB_NAMES));
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 1 || amount > 64) {
                    sender.sendMessage("§cAmount must be between 1 and 64.");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount: " + args[3]);
                return true;
            }
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§cPlayer not found or not online: " + args[1]);
            return true;
        }

        ItemStack core = coreManager.createCore(entityType);
        String entityName = SpawnerCoreManager.formatEntityName(entityType);

        for (int i = 0; i < amount; i++) {
            ItemStack toGive = core.clone();
            if (target.getInventory().firstEmpty() == -1) {
                target.getWorld().dropItemNaturally(target.getLocation(), toGive);
            } else {
                target.getInventory().addItem(toGive);
            }
        }
        target.updateInventory();

        sender.sendMessage("§aGave " + target.getDisplayName() + " §ax " + amount + " §a" + entityName + " Spawner Core(s).");
        if (!sender.equals(target)) {
            target.sendMessage("§aYou received " + amount + " " + entityName + " Spawner Core(s).");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, String[] args) {
        if (!sender.hasPermission("allium.spawnercore") && !sender.hasPermission("allium.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter("give", args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> args[1].isEmpty() || n.toLowerCase(Locale.ENGLISH).startsWith(args[1].toLowerCase(Locale.ENGLISH)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(ALLOWED_MOB_NAMES, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filter(Arrays.asList("1", "2", "4", "8", "16", "64"), args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(String single, String input) {
        return filter(Collections.singletonList(single), input);
    }

    private List<String> filter(List<String> options, String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = input.toLowerCase(Locale.ENGLISH);
        List<String> result = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ENGLISH).startsWith(lower)) {
                result.add(s);
            }
        }
        return result;
    }
}
