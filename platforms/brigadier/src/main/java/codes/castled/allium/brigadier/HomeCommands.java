package codes.castled.allium.brigadier;

import codes.castled.allium.portable.Homes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.function.BiConsumer;
import java.util.function.Function;

public final class HomeCommands {
    private HomeCommands() {}

    public static <S> void register(CommandDispatcher<S> dispatcher, Function<S, Homes> sessions,
                                    BiConsumer<S, String> reply) {
        for (Homes.Action action : Homes.Action.values()) {
            LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.literal("allium:" + action.command);
            root.executes(ctx -> execute(ctx, sessions, reply, action, null));
            if (action.acceptsName()) {
                RequiredArgumentBuilder<S, String> name = RequiredArgumentBuilder.argument("name", StringArgumentType.word());
                name.executes(ctx -> execute(ctx, sessions, reply, action, StringArgumentType.getString(ctx, "name")));
                if (action.suggestsNames()) {
                    name.suggests((ctx, suggestions) -> {
                        Homes homes = sessions.apply(ctx.getSource());
                        if (homes != null) homes.suggestions(suggestions.getRemaining()).forEach(suggestions::suggest);
                        return suggestions.buildFuture();
                    });
                }
                root.then(name);
            }
            registerAliases(dispatcher, root, action);
        }
    }

    private static <S> void registerAliases(CommandDispatcher<S> dispatcher, LiteralArgumentBuilder<S> root,
                                       Homes.Action action) {
        LiteralCommandNode<S> node = dispatcher.register(root);
        for (String label : action.labels()) {
            for (String alias : new String[]{label, "allium:" + label}) {
                if (dispatcher.getRoot().getChild(alias) == null) {
                    dispatcher.register(LiteralArgumentBuilder.<S>literal(alias).executes(node.getCommand()).redirect(node));
                }
            }
        }
    }

    private static <S> int execute(CommandContext<S> context, Function<S, Homes> sessions, BiConsumer<S, String> reply,
                                   Homes.Action action, String name) {
        S source = context.getSource();
        Homes homes = sessions.apply(source);
        if (homes == null) {
            reply.accept(source, Homes.PLAYERS_ONLY);
            return 0;
        }
        return homes.execute(action, name, message -> reply.accept(source, message));
    }
}
