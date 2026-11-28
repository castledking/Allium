package codes.castled.allium.minecraft;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.function.Function;

/** The native Minecraft implementation shared by Fabric and NeoForge for this target. */
public final class MinecraftCommands {
    private MinecraftCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String platform, String version) {
        String game = SharedConstants.getCurrentVersion().name();
        MinecraftNativePlatform.register(dispatcher, Commands.hasPermission(Commands.LEVEL_GAMEMASTERS),
                ServerPlayer::level,
                level -> level.dimension().identifier().toString(),
                level -> level.getRespawnData().pos(),
                player -> player.connection.latency(),
                (player, level, point) -> player.teleportTo(level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch, true));
        register(dispatcher, CoreCommandSpecs.ALLIUM, root -> root
                .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                .then(Commands.literal("help").executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS)))
                .then(Commands.literal("version").executes(ctx ->
                        reply(ctx.getSource(), UtilityMessages.version(version, platform, game)))));
        playerUtility(dispatcher, CoreCommandSpecs.PING,
                player -> UtilityMessages.ping(player.getScoreboardName(), player.connection.latency()));
        playerUtility(dispatcher, CoreCommandSpecs.COORDS, player -> {
            var position = player.blockPosition();
            return UtilityMessages.coordinates(player.getScoreboardName(), position.getX(), position.getY(),
                    position.getZ(), player.level().dimension().identifier().toString());
        });
    }

    private static void playerUtility(CommandDispatcher<CommandSourceStack> dispatcher, CommandSpec spec,
                                      Function<ServerPlayer, String> message) {
        register(dispatcher, spec, root -> root
                .executes(ctx -> reply(ctx.getSource(), message.apply(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> reply(ctx.getSource(), message.apply(EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandSpec spec,
                                 Function<LiteralArgumentBuilder<CommandSourceStack>, LiteralArgumentBuilder<CommandSourceStack>> build) {
        String namespaced = spec.namespace() + ":" + spec.canonicalName();
        var node = dispatcher.register(build.apply(Commands.literal(namespaced)));
        for (String label : spec.allOwnedLabels()) {
            if (dispatcher.getRoot().getChild(label) == null) {
                dispatcher.register(Commands.literal(label).executes(node.getCommand()).redirect(node));
            }
        }
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
