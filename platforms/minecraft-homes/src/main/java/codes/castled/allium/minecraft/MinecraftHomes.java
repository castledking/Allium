package codes.castled.allium.minecraft;

import codes.castled.allium.brigadier.HomeCommands;
import codes.castled.allium.portable.Homes;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.function.Function;
import java.util.function.Predicate;

public final class MinecraftHomes {
    @FunctionalInterface
    public interface Teleport {
        boolean move(ServerPlayer player, ServerLevel level, Homes.Position position);
    }

    private MinecraftHomes() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Predicate<CommandSourceStack> canBypassLimit, Function<ServerPlayer, ServerLevel> levelOf,
                                Function<ServerLevel, String> worldId, Teleport teleport) {
        HomeCommands.register(dispatcher, source -> {
            ServerPlayer player = source.getPlayer();
            if (player == null) return null;
            var server = source.getServer();
            int limit = canBypassLimit.test(source) ? Homes.MAX_LIMIT : Homes.DEFAULT_LIMIT;
            return new Homes(server.getWorldPath(LevelResource.ROOT).resolve("allium").resolve("homes"),
                    player.getUUID(), limit, () -> new Homes.Position(worldId.apply(levelOf.apply(player)),
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()), point -> {
                        for (ServerLevel level : server.getAllLevels()) {
                            if (worldId.apply(level).equals(point.world)) return teleport.move(player, level, point);
                        }
                        return false;
                    });
        }, (source, message) -> source.sendSuccess(() -> Component.literal(message), false));
    }
}
