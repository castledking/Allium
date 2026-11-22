package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandArgSpec;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.SuggestionSpec;
import codes.castled.allium.message.Messages;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BlockToolCommand implements SpecCommandHandler {

    public static final CommandSpec BREAK = spec("break", List.of(), "Break the block you are looking at", "", List.of());
    public static final CommandSpec SPAWNER = spec("spawner", List.of("changems", "mobspawner"),
            "Change the mob spawned by the spawner you are looking at", " <mob> [delay]",
            List.of(CommandArgSpec.required("mob", null, SuggestionSpec.none()),
                    CommandArgSpec.optional("delay", null, SuggestionSpec.none())));

    private static CommandSpec spec(String label, List<String> aliases, String description, String arguments,
                                    List<CommandArgSpec> args) {
        String usage = "/" + label + arguments;
        return new CommandSpec(label, CommandSpec.DEFAULT_NAMESPACE, "essentials", aliases,
                description, usage, usage, "allium." + label, null, args, List.of(),
                CommandMessageKeys.ofOutcomes(Map.of()));
    }

    private final PluginStart plugin;
    private final CommandSpec spec;

    public BlockToolCommand(PluginStart plugin, CommandSpec spec) {
        this.plugin = plugin;
        this.spec = spec;
    }

    @Override
    public CommandSpec spec() {
        return spec;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        if (spec == BREAK ? args.length != 0 : args.length < 1 || args.length > 2) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        var block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_NO_TARGET);
            return;
        }
        var protection = plugin.getProtectionService();
        if (protection != null && !protection.checkBlockBreak(player, block.getLocation(), spec.canonicalName(), null).allowed()) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            return;
        }
        if (spec == BREAK) {
            if (!player.breakBlock(block)) {
                UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            }
            return;
        }
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            UtilitySupport.send(plugin, player, Messages.SPAWNER_NO_TARGET);
            return;
        }
        EntityType type = parseMob(args[0]);
        if (type == null) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        if (!canSetMob(player, type)) {
            UtilitySupport.send(plugin, player, Messages.NO_PERMISSION, "{cmd}", "spawner " + args[0]);
            return;
        }
        if (args.length == 2) {
            int delay;
            try {
                delay = Integer.parseInt(args[1]);
                if (delay < 0) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                UtilitySupport.send(plugin, player, Messages.NUMBER_INVALID, "{input}", args[1]);
                return;
            }
            spawner.setDelay(delay);
        }
        spawner.setSpawnedType(type);
        if (!spawner.update(false, false)) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            return;
        }
        UtilitySupport.send(plugin, player, Messages.SPAWNER_SET, "{mob}", mobName(type));
    }

    public List<String> complete(org.bukkit.command.CommandSender sender, String[] args) {
        if (spec != SPAWNER || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Arrays.stream(EntityType.values()).filter(BlockToolCommand::spawnableMob)
                .filter(type -> canSetMob(sender, type)).map(BlockToolCommand::mobName)
                .filter(name -> name.startsWith(prefix)).sorted().toList();
    }

    private static EntityType parseMob(String input) {
        String name = input.toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        final String key = name;
        return Arrays.stream(EntityType.values()).filter(BlockToolCommand::spawnableMob)
                .filter(type -> mobName(type).equals(key)).findFirst().orElse(null);
    }

    private static boolean spawnableMob(EntityType type) {
        return type.isAlive() && type.isSpawnable() && type != EntityType.ARMOR_STAND;
    }

    private static String mobName(EntityType type) {
        return type.getKey().getKey();
    }

    private static boolean canSetMob(org.bukkit.command.CommandSender sender, EntityType type) {
        String permission = "allium.spawner." + mobName(type);
        return sender.isPermissionSet(permission) ? sender.hasPermission(permission)
                : sender.hasPermission("allium.spawner.*");
    }
}
