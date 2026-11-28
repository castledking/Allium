package codes.castled.allium.neoforge.v1202;

import codes.castled.allium.minecraft.MinecraftNativePlatform;
import codes.castled.allium.neoforge.bootstrap.NeoForgeAdapter;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.function.Function;

public final class LegacyNeoForge implements NeoForgeAdapter {
    @Override
    public void initialize(String version) {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            Path dataDir = FMLPaths.GAMEDIR.get().resolve("plugins").resolve("Allium");
            NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> registerCommands(event, version, dataDir));
            NeoForge.EVENT_BUS.addListener((ServerChatEvent event) -> {
                if (MinecraftNativePlatform.handleChat(event.getPlayer(), event.getRawText())) {
                    event.setCanceled(true);
                }
            });
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
                if (event.getEntity() instanceof ServerPlayer) {
                    MinecraftNativePlatform.handleJoin((ServerPlayer) event.getEntity());
                }
            });
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
                if (event.getEntity() instanceof ServerPlayer) {
                    MinecraftNativePlatform.handleQuit((ServerPlayer) event.getEntity());
                }
            });
        }
    }

    private void registerCommands(RegisterCommandsEvent event, String version, Path dataDir) {
        var dispatcher = event.getDispatcher();
        MinecraftNativePlatform.register(dispatcher, dataDir, source -> source.hasPermission(2), ServerPlayer::serverLevel,
                level -> level.dimension().location().toString(), ServerLevel::getSharedSpawnPos,
                player -> player.connection.latency(),
                item -> BuiltInRegistries.ITEM.getKey(item).toString(),
                (player, level, point) -> player.teleportTo(level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch));
        register(dispatcher, Commands.literal("allium:allium")
                .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                .then(Commands.literal("help").executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS)))
                .then(Commands.literal("version").executes(ctx ->
                        reply(ctx.getSource(), UtilityMessages.version(version, "NeoForge", "1.20.2"))))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reply(ctx.getSource(), MinecraftNativePlatform.reloadAndMessage()))), "allium", "core");
        playerUtility(dispatcher, "ping", player -> UtilityMessages.ping(player.getScoreboardName(), player.connection.latency()), "ping", "pong");
        playerUtility(dispatcher, "coords", player -> {
            var pos = player.blockPosition();
            return UtilityMessages.coordinates(player.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(),
                    player.level().dimension().location().toString());
        }, "coords", "getpos", "whereami", "getloc", "getlocation", "position");
    }

    private static void playerUtility(CommandDispatcher<CommandSourceStack> dispatcher, String name,
                                      Function<ServerPlayer, String> output, String... labels) {
        register(dispatcher, Commands.literal("allium:" + name)
                .executes(ctx -> reply(ctx.getSource(), output.apply(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reply(ctx.getSource(), output.apply(EntityArgument.getPlayer(ctx, "player"))))), labels);
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                 LiteralArgumentBuilder<CommandSourceStack> command, String... labels) {
        var node = dispatcher.register(command);
        for (String label : labels) {
            for (String alias : new String[]{label, "allium:" + label}) {
                if (dispatcher.getRoot().getChild(alias) == null) {
                    dispatcher.register(Commands.literal(alias).executes(node.getCommand()).redirect(node));
                }
            }
        }
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
