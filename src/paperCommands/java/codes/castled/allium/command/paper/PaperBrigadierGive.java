package codes.castled.allium.command.paper;

import codes.castled.allium.command.items.GiveCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Registers {@code /give} through Paper's Brigadier command API (Paper 1.20.6+).
 *
 * <p>Paper runs legacy Bukkit commands under {@code /execute} with the initiating sender and no
 * {@code as}/{@code at} context, so a legacy {@code give @s} or {@code give @p} would target the
 * wrong players. Here the target is vanilla's own {@code players()} argument, resolved against
 * the full {@link CommandSourceStack}: command blocks, {@code /execute as ... at ...} and
 * functions-free players all select exactly as vanilla does. Loaded reflectively; never on Spigot.
 */
public final class PaperBrigadierGive {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicReference<GiveCommand> CURRENT = new AtomicReference<>();

    private PaperBrigadierGive() {
    }

    /**
     * Installs (once per server lifetime) the {@code COMMANDS} lifecycle handler, and points it at
     * {@code command}. Later calls, for example after a module reload, only swap the handler.
     *
     * @param registered receives the labels Paper registered
     */
    public static void register(JavaPlugin plugin, GiveCommand command, String permission, String description,
                                Consumer<Set<String>> registered) {
        CURRENT.set(command);
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("give")
                    .requires(source -> source.getSender().hasPermission(permission))
                    .then(Commands.argument("targets", ArgumentTypes.players())
                            .then(Commands.argument("item", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        GiveCommand current = CURRENT.get();
                                        if (current == null) {
                                            return 0;
                                        }
                                        List<Player> targets;
                                        try {
                                            targets = context.getArgument("targets", PlayerSelectorArgumentResolver.class)
                                                    .resolve(context.getSource());
                                        } catch (CommandSyntaxException e) {
                                            current.reportTargetError(context.getSource().getSender(), e.getRawMessage().getString());
                                            return 0;
                                        }
                                        if (targets.isEmpty()) {
                                            // Paper's resolver returns an empty list where vanilla /give fails with this text.
                                            current.reportTargetError(context.getSource().getSender(), "No player was found");
                                            return 0;
                                        }
                                        current.give(context.getSource().getSender(), targets,
                                                StringArgumentType.getString(context, "item"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .build();
            registered.accept(event.registrar().register(node, description, List.of()));
        });
    }

    /** Stops the registered command from acting (module disabled); Paper keeps the node until restart. */
    public static void unbind() {
        CURRENT.set(null);
    }
}
