package codes.castled.allium.fabric.v12111;

import codes.castled.allium.portable.UtilityMessages;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;

public final class FabricAdapter implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FabricUtilityCommands.initialize(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS),
                player -> UtilityMessages.ping(player.getScoreboardName(), player.connection.latency()), player -> {
                    var pos = player.blockPosition();
                    return UtilityMessages.coordinates(player.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(),
                            player.level().dimension().identifier().toString());
                }, ServerPlayer::level, level -> level.dimension().identifier().toString(),
                (player, level, point) -> player.teleportTo(level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch, true));
    }
}
