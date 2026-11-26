package codes.castled.allium.fabric.v1201;

import codes.castled.allium.portable.UtilityMessages;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;

public final class FabricAdapter implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FabricUtilityCommands.initialize(source -> source.hasPermission(2),
                player -> UtilityMessages.ping(player.getScoreboardName(), player.latency), player -> {
                    var pos = player.blockPosition();
                    return UtilityMessages.coordinates(player.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(),
                            player.serverLevel().dimension().location().toString());
                }, ServerPlayer::serverLevel, level -> level.dimension().location().toString(),
                (player, level, point) -> player.teleportTo(level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch));
    }
}
