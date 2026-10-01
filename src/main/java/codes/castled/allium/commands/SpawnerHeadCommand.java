package codes.castled.allium.commands;

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

import codes.castled.allium.PluginStart;
import codes.castled.allium.spawnercraft.MobHeadFactory;
import codes.castled.allium.spawnercraft.SpawnerCoreManager;
import codes.castled.allium.spawnercraft.SpawnerHeadConfig;
import codes.castled.allium.util.PlayerMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Give Allium mob heads to players.
 * Usage: /spawnerhead give &lt;player&gt; &lt;mob&gt; [amount]
 *
 * The allowed mobs are exactly the ones configured in {@code spawner_heads.yml},
 * so removing a mob there also removes it here.
 */
public class SpawnerHeadCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;

    public SpawnerHeadCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("allium.spawnerhead") && !sender.hasPermission("allium.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§cUsage: /spawnerhead give <player> <mob> [amount]");
            return true;
        }

        EntityType entityType = null;
        try {
            entityType = EntityType.valueOf(args[2].toLowerCase(Locale.ENGLISH).replace(" ", "_").toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ignored) {
        }

        SpawnerHeadConfig.MobHead headData = entityType == null ? null : SpawnerHeadConfig.get(entityType);
        if (headData == null) {
            List<String> allowed = SpawnerHeadConfig.mobNames();
            sender.sendMessage(allowed.isEmpty()
                    ? "§cNo mobs are configured in spawner_heads.yml."
                    : "§cInvalid or not allowed mob. Allowed: " + String.join(", ", allowed));
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount: " + args[3]);
                return true;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage("§cAmount must be between 1 and 64.");
                return true;
            }
        }

        Player target = PlayerMatcher.match(sender, args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§cPlayer not found or not online: " + args[1]);
            return true;
        }

        ItemStack head = MobHeadFactory.create(plugin, entityType, headData);
        if (head == null) {
            sender.sendMessage("§cCould not build that head. Check its texture in spawner_heads.yml.");
            return true;
        }

        for (int i = 0; i < amount; i++) {
            // Each head carries its own profile, so they never stack: hand them out one by one.
            ItemStack toGive = head.clone();
            if (target.getInventory().firstEmpty() == -1) {
                target.getWorld().dropItemNaturally(target.getLocation(), toGive);
            } else {
                target.getInventory().addItem(toGive);
            }
        }
        target.updateInventory();

        String entityName = SpawnerCoreManager.formatEntityName(entityType);
        sender.sendMessage("§aGave " + target.getDisplayName() + " §ax " + amount + " §a" + entityName + " Head(s).");
        if (!sender.equals(target)) {
            target.sendMessage("§aYou received " + amount + " " + entityName + " Head(s).");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (!sender.hasPermission("allium.spawnerhead") && !sender.hasPermission("allium.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(Collections.singletonList("give"), args[0]);
        }
        if (!args[0].equalsIgnoreCase("give")) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> args[1].isEmpty()
                            || name.toLowerCase(Locale.ENGLISH).startsWith(args[1].toLowerCase(Locale.ENGLISH)))
                    .collect(Collectors.toList());
        }
        if (args.length == 3) {
            return filter(SpawnerHeadConfig.mobNames(), args[2]);
        }
        if (args.length == 4) {
            return filter(Arrays.asList("1", "2", "4", "8", "16", "64"), args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = input.toLowerCase(Locale.ENGLISH);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ENGLISH).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
