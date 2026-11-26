package codes.castled.allium.fabric.v114;

import codes.castled.allium.brigadier.HomeCommands;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.registry.CommandRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.arguments.EntityArgumentType;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.dimension.DimensionType;

import java.util.function.Function;

public final class LegacyFabric implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        String version = FabricLoader.getInstance().getModContainer("allium").get().getMetadata().getVersion().getFriendlyString();
        CommandRegistry.INSTANCE.register(true, dispatcher -> {
            HomeCommands.register(dispatcher, LegacyFabric::homes, LegacyFabric::reply);
            register(dispatcher, CommandManager.literal("allium:allium")
                    .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                    .then(CommandManager.literal("version").executes(ctx ->
                            reply(ctx.getSource(), UtilityMessages.version(version, "Fabric", "1.14")))), "allium", "core");
            playerUtility(dispatcher, "ping", player -> UtilityMessages.ping(player.getEntityName(), player.field_13967), "pong");
            playerUtility(dispatcher, "coords", player -> {
                BlockPos position = player.getBlockPos();
                return UtilityMessages.coordinates(player.getEntityName(), position.getX(), position.getY(), position.getZ(),
                        DimensionType.getId(player.dimension).toString());
            }, "getpos", "whereami", "getloc", "getlocation", "position");
        });
    }

    private static Homes homes(ServerCommandSource source) {
        if (!(source.getEntity() instanceof ServerPlayerEntity)) return null;
        ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
        MinecraftServer server = source.getMinecraftServer();
        int limit = source.hasPermissionLevel(2) ? Homes.MAX_LIMIT : Homes.DEFAULT_LIMIT;
        return new Homes(server.getLevelStorage().resolveFile(server.getLevelName(), "allium").toPath().resolve("homes"),
                player.getUuid(), limit, () -> new Homes.Position(DimensionType.getId(player.dimension).toString(),
                        player.x, player.y, player.z, player.yaw, player.pitch), point -> {
                    DimensionType type = DimensionType.byId(new Identifier(point.world));
                    ServerWorld world = type == null ? null : server.getWorld(type);
                    if (world == null) return false;
                    player.teleport(world, point.x, point.y, point.z, point.yaw, point.pitch);
                    return true;
                });
    }

    private static void playerUtility(CommandDispatcher<ServerCommandSource> dispatcher, String name,
                                      Function<ServerPlayerEntity, String> output, String... aliases) {
        String[] labels = new String[aliases.length + 1];
        labels[0] = name;
        System.arraycopy(aliases, 0, labels, 1, aliases.length);
        register(dispatcher, CommandManager.literal("allium:" + name)
                .executes(ctx -> reply(ctx.getSource(), output.apply(ctx.getSource().getPlayer())))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> reply(ctx.getSource(), output.apply(EntityArgumentType.getPlayer(ctx, "player"))))), labels);
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                 LiteralArgumentBuilder<ServerCommandSource> command, String... labels) {
        com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> node = dispatcher.register(command);
        for (String label : labels) {
            for (String alias : new String[]{label, "allium:" + label}) {
                if (dispatcher.getRoot().getChild(alias) == null) {
                    dispatcher.register(CommandManager.literal(alias).executes(node.getCommand()).redirect(node));
                }
            }
        }
    }

    private static int reply(ServerCommandSource source, String text) {
        source.sendFeedback(new TextComponent(text), false);
        return 1;
    }
}
