package codes.castled.allium.fabric.v1201;

import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.UtilityMessages;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

public final class FabricAdapter implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FabricUtilityCommands.initialize(source -> source.hasPermission(2),
                FabricAdapter::latencyOf,
                player -> {
                    var pos = player.blockPosition();
                    return UtilityMessages.coordinates(player.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(),
                            levelOf(player).dimension().location().toString());
                },
                FabricAdapter::levelOf,
                level -> level.dimension().location().toString(),
                FabricAdapter::spawnPos,
                FabricAdapter::teleport);
    }

    private static int latencyOf(ServerPlayer player) {
        try {
            return player.latency;
        } catch (LinkageError ignored) {
        }
        try {
            Object conn = player.connection;
            for (String name : new String[]{"method_52396", "latency"}) {
                try {
                    Method m = conn.getClass().getMethod(name);
                    Object v = m.invoke(conn);
                    if (v instanceof Integer) return (Integer) v;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static ServerLevel levelOf(ServerPlayer player) {
        try {
            return player.serverLevel();
        } catch (LinkageError ignored) {
            return (ServerLevel) player.level();
        }
    }

    private static BlockPos spawnPos(ServerLevel level) {
        try {
            return level.getSharedSpawnPos();
        } catch (LinkageError ignored) {
        }
        try {
            for (Method m : level.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && ("method_68077".equals(m.getName()) || "getRespawnData".equals(m.getName()))) {
                    Object data = m.invoke(level);
                    if (data != null) {
                        for (Method dm : data.getClass().getMethods()) {
                            if (dm.getParameterCount() == 0 && BlockPos.class.isAssignableFrom(dm.getReturnType())) {
                                return (BlockPos) dm.invoke(data);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return BlockPos.ZERO;
    }

    private static boolean teleport(ServerPlayer player, ServerLevel level, Homes.Position point) {
        try {
            return player.teleportTo(level, point.x, point.y, point.z,
                    Collections.emptySet(), point.yaw, point.pitch);
        } catch (LinkageError ignored) {
        }
        try {
            for (Method m : player.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 8 && p[0] == ServerLevel.class
                        && p[1] == double.class && p[2] == double.class && p[3] == double.class
                        && p[4] == Set.class && p[5] == float.class && p[6] == float.class && p[7] == boolean.class) {
                    Object res = m.invoke(player, level, point.x, point.y, point.z,
                            Collections.emptySet(), point.yaw, point.pitch, true);
                    return !(res instanceof Boolean) || (Boolean) res;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
