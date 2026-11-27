package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.*;
import codes.castled.allium.message.Messages;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class EditSignCommand implements SpecCommandHandler {

    private static final String USAGE = "/editsign <set|clear|copy|paste> [line] [text]";
    public static final CommandSpec SPEC = new CommandSpec(
            "editsign", CommandSpec.DEFAULT_NAMESPACE, "essentials", List.of("sign"),
            "Edit or copy text on the sign face you are looking at", USAGE, USAGE, "allium.editsign", null,
            List.of(CommandArgSpec.required("action", null, SuggestionSpec.literals(List.of("set", "clear", "copy", "paste"), null)),
                    CommandArgSpec.optional("line", null, SuggestionSpec.literals(List.of("1", "2", "3", "4"), null))),
            List.of(), CommandMessageKeys.ofOutcomes(Map.of()));

    private final PluginStart plugin;
    private final Map<Player, String[]> clipboard = new WeakHashMap<>();

    public EditSignCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            UtilitySupport.send(plugin, context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        String[] args = context.args();
        String operation = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean set = operation.equals("set");
        if (!(set && args.length >= 3 || operation.equals("clear") && args.length <= 2
                || (operation.equals("copy") || operation.equals("paste")) && args.length == 1)) {
            context.sendInvalidArgs(USAGE);
            return;
        }
        int line = -1;
        if (set || operation.equals("clear") && args.length == 2) {
            if (!args[1].matches("[1-4]")) {
                UtilitySupport.send(plugin, player, Messages.NUMBER_INVALID, "{input}", args[1]);
                return;
            }
            line = Integer.parseInt(args[1]) - 1;
        }
        var block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Sign sign)) {
            UtilitySupport.send(plugin, player, Messages.EDIT_SIGN_TARGET);
            return;
        }
        Side side = facing(player, sign);
        var protection = plugin.getProtectionService();
        if (protection != null && !protection.checkBlockBreak(player, block.getLocation(), "editsign", null).allowed()) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            return;
        }
        String[] changes = sign.getSide(side).getLines().clone();
        if (operation.equals("copy")) {
            clipboard.put(player, changes);
            UtilitySupport.send(plugin, player, Messages.EDIT_SIGN_COPIED);
            return;
        }
        var key = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "sign_" + side.name().toLowerCase(Locale.ROOT));
        if (waxed(sign) || sign.getPersistentDataContainer().getKeys().contains(key)) {
            UtilitySupport.send(plugin, player, Messages.EDIT_SIGN_LOCKED);
            return;
        }
        if (operation.equals("paste")) {
            changes = clipboard.get(player);
            if (changes == null) {
                UtilitySupport.send(plugin, player, Messages.EDIT_SIGN_NO_COPY);
                return;
            }
            changes = changes.clone();
        } else if (set) {
            String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (value.length() > 384) {
                context.sendInvalidArgs(USAGE);
                return;
            }
            changes[line] = player.hasPermission("allium.editsign.color")
                    ? ChatColor.translateAlternateColorCodes('&', value) : value;
        } else if (line >= 0) {
            changes[line] = "";
        } else {
            Arrays.fill(changes, "");
        }
        SignChangeEvent event = new SignChangeEvent(block, player, changes, side);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled() || !(block.getState() instanceof Sign current) || waxed(current)) {
            UtilitySupport.send(plugin, player, Messages.BLOCK_TOOL_DENIED);
            return;
        }
        for (int i = 0; i < 4; i++) current.getSide(side).setLine(i, event.getLine(i));
        if (current.update(false, false)) {
            UtilitySupport.send(plugin, player, Messages.EDIT_SIGN_CHANGED);
        }
    }

    private static boolean waxed(Sign sign) {
        try {
            return Boolean.TRUE.equals(Sign.class.getMethod("isWaxed").invoke(sign));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return true;
        }
    }

    private static Side facing(Player player, Sign sign) {
        var data = sign.getBlockData();
        var normal = data instanceof Directional wall ? wall.getFacing()
                : data instanceof Rotatable standing ? standing.getRotation() : null;
        double dot = normal == null ? 0 : normal.getDirection().dot(
                player.getEyeLocation().toVector().subtract(sign.getLocation().add(0.5, 0.5, 0.5).toVector()));
        return dot < 0 ? Side.BACK : Side.FRONT;
    }
}
