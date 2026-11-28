package codes.castled.allium.fabric.remapped;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public final class FabricUtilityCommands {
    private FabricUtilityCommands() {}

    public static void initialize(Predicate<CommandSourceStack> canInspect, ToIntFunction<ServerPlayer> latencyOf,
                                  Function<ServerPlayer, String> coordinates,
                                  Function<ServerPlayer, ServerLevel> levelOf,
                                  Function<ServerLevel, String> worldId,
                                  Function<ServerLevel, BlockPos> spawnPos,
                                  MinecraftHomes.Teleport teleport) {
        FabricLoader loader = FabricLoader.getInstance();
        String version = loader.getModContainer("allium").orElseThrow().getMetadata().getVersion().getFriendlyString();
        String game = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            register(dispatcher, CoreCommandSpecs.ALLIUM, root -> root
                    .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                    .then(Commands.literal("help").executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS)))
                    .then(Commands.literal("version").executes(ctx ->
                            reply(ctx.getSource(), UtilityMessages.version(version, "Fabric", game)))));
            playerUtility(dispatcher, CoreCommandSpecs.PING, canInspect,
                    player -> UtilityMessages.ping(player.getScoreboardName(), latencyOf.applyAsInt(player)));
            playerUtility(dispatcher, CoreCommandSpecs.COORDS, canInspect, coordinates);
            MinecraftNativePlatform.register(dispatcher, canInspect, levelOf, worldId, spawnPos, latencyOf, teleport);
        });
    }

    private static void playerUtility(CommandDispatcher<CommandSourceStack> dispatcher, CommandSpec spec,
                                      Predicate<CommandSourceStack> canInspect, Function<ServerPlayer, String> message) {
        register(dispatcher, spec, root -> root
                .executes(ctx -> reply(ctx.getSource(), message.apply(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("player", EntityArgument.player()).requires(canInspect)
                        .executes(ctx -> reply(ctx.getSource(), message.apply(EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandSpec spec,
                                 Function<LiteralArgumentBuilder<CommandSourceStack>, LiteralArgumentBuilder<CommandSourceStack>> build) {
        var node = dispatcher.register(build.apply(Commands.literal(spec.namespace() + ":" + spec.canonicalName())));
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
