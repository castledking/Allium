package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import org.bukkit.TreeType;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class TreeCommand implements SpecCommandHandler {

    public static final CommandSpec TREE = spec("tree", List.of(), TreeType.TREE);
    public static final CommandSpec BIG_TREE = spec("bigtree", List.of("largetree"), TreeType.BIG_TREE);

    private static CommandSpec spec(String label, List<String> aliases, TreeType fallback) {
        String usage = "/" + label + " [type]";
        List<String> types = Arrays.stream(TreeType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT)).sorted().toList();
        return new CommandSpec(label, CommandSpec.DEFAULT_NAMESPACE, "essentials", aliases,
                "Grow a " + fallback.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                usage, usage, "allium." + label, null,
                List.of(CommandArgSpec.optional("type", null, SuggestionSpec.literals(types, null))),
                List.of(), CommandMessageKeys.ofOutcomes(Map.of()));
    }

    private final PluginStart plugin;
    private final CommandSpec spec;

    public TreeCommand(PluginStart plugin, CommandSpec spec) {
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
        TreeType type = type(context.args());
        if (type == null) {
            context.sendInvalidArgs(spec.selfUsage());
            return;
        }
        var block = player.getTargetBlockExact(6);
        if (block == null) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_NO_TARGET);
            return;
        }
        var origin = block.getRelative(BlockFace.UP).getLocation();
        var world = player.getWorld();
        List<BlockState> changes = new ArrayList<>();
        boolean generated = world.generateTree(origin, ThreadLocalRandom.current(), type, (Predicate<BlockState>) state -> {
            changes.add(state);
            return false;
        });
        if (!generated || changes.isEmpty()) {
            UtilitySupport.send(plugin, player, Messages.TREE_FAILED);
            return;
        }
        var protection = plugin.getProtectionService();
        if (protection != null && changes.stream().anyMatch(state ->
                !protection.checkBlockBreak(player, state.getLocation(), "tree", null).allowed())) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            return;
        }
        for (BlockState state : changes) {
            state.update(true, false);
        }
        UtilitySupport.send(plugin, player, Messages.TREE_GROWN);
    }

    private TreeType type(String[] args) {
        if (args.length == 0) return spec == BIG_TREE ? TreeType.BIG_TREE : TreeType.TREE;
        if (args.length != 1) return null;
        String name = args[0].toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return TreeType.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
