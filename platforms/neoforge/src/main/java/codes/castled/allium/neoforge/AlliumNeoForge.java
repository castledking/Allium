package codes.castled.allium.neoforge;

import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.minecraft.MinecraftNativePlatform;
import codes.castled.allium.neoforge.bootstrap.NeoForgeAdapter;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runtime-adaptive NeoForge adapter covering Minecraft 1.20.5 through 26.3 in one Java 17 class,
 * paired with {@code LegacyNeoForge} (1.20.2–1.20.4) inside {@code Allium-NeoForge-<version>.jar}.
 */
public final class AlliumNeoForge implements NeoForgeAdapter {
    private static final Predicate<CommandSourceStack> GAMEMASTER = resolveGamemasterPredicate();
    private static final Method LEVEL_METHOD = resolveServerLevelMethod();
    private static final Method KEY_ID_METHOD = resolveDimensionIdMethod();
    private static final Method RESPAWN_DATA_METHOD = findMethod(ServerLevel.class, "getRespawnData");
    private static final Method SHARED_SPAWN_METHOD = findMethod(ServerLevel.class, "getSharedSpawnPos");
    private static final Method TELEPORT_8 = findMethod(ServerPlayer.class, "teleportTo",
            ServerLevel.class, double.class, double.class, double.class, Set.class, float.class, float.class, boolean.class);
    private static final Method TELEPORT_7 = findMethod(ServerPlayer.class, "teleportTo",
            ServerLevel.class, double.class, double.class, double.class, Set.class, float.class, float.class);
    private static final Method REGISTRY_GET_KEY = resolveRegistryGetKeyMethod();

    @Override
    public void initialize(String version) {
        if (!isDedicatedServer()) {
            return;
        }
        String game = ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        Path dataDir = FMLPaths.GAMEDIR.get().resolve("plugins").resolve("Allium");
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                registerCommands(event.getDispatcher(), version, game, dataDir));
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

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                         String version, String game, Path dataDir) {
        MinecraftNativePlatform.register(dispatcher, dataDir, GAMEMASTER,
                AlliumNeoForge::levelOf,
                AlliumNeoForge::worldId,
                AlliumNeoForge::spawnPos,
                player -> player.connection.latency(),
                AlliumNeoForge::itemId,
                AlliumNeoForge::teleport);
        register(dispatcher, CoreCommandSpecs.ALLIUM, root -> root
                .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                .then(Commands.literal("help").executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS)))
                .then(Commands.literal("version").executes(ctx ->
                        reply(ctx.getSource(), UtilityMessages.version(version, "NeoForge", game))))
                .then(Commands.literal("reload")
                        .requires(GAMEMASTER)
                        .executes(ctx -> reply(ctx.getSource(), MinecraftNativePlatform.reloadAndMessage()))));
        playerUtility(dispatcher, CoreCommandSpecs.PING,
                player -> UtilityMessages.ping(player.getScoreboardName(), player.connection.latency()));
        playerUtility(dispatcher, CoreCommandSpecs.COORDS, player -> {
            BlockPos pos = player.blockPosition();
            return UtilityMessages.coordinates(player.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(),
                    worldId(levelOf(player)));
        });
    }

    private static void playerUtility(CommandDispatcher<CommandSourceStack> dispatcher, CommandSpec spec,
                                      Function<ServerPlayer, String> message) {
        register(dispatcher, spec, root -> root
                .executes(ctx -> reply(ctx.getSource(), message.apply(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(GAMEMASTER)
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

    private static boolean isDedicatedServer() {
        try {
            Method getDist = FMLEnvironment.class.getMethod("getDist");
            return getDist.invoke(null) == Dist.DEDICATED_SERVER;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Field distField = FMLEnvironment.class.getField("dist");
            return distField.get(null) == Dist.DEDICATED_SERVER;
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Predicate<CommandSourceStack> resolveGamemasterPredicate() {
        try {
            Field levelField = Commands.class.getField("LEVEL_GAMEMASTERS");
            Object level = levelField.get(null);
            for (Method m : Commands.class.getMethods()) {
                if ("hasPermission".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object pred = m.invoke(null, level);
                    if (pred instanceof Predicate) {
                        return (Predicate<CommandSourceStack>) pred;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        Method legacyHasPerm = findMethod(CommandSourceStack.class, "hasPermission", int.class);
        return source -> {
            if (legacyHasPerm == null) return false;
            try {
                return Boolean.TRUE.equals(legacyHasPerm.invoke(source, 2));
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        };
    }

    private static Method resolveServerLevelMethod() {
        Method m = findMethod(ServerPlayer.class, "serverLevel");
        return m != null ? m : findMethod(ServerPlayer.class, "level");
    }

    private static ServerLevel levelOf(ServerPlayer player) {
        try {
            if (LEVEL_METHOD != null) {
                Object result = LEVEL_METHOD.invoke(player);
                if (result instanceof ServerLevel) return (ServerLevel) result;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return (ServerLevel) player.level();
    }

    private static Method resolveDimensionIdMethod() {
        try {
            Class<?> rk = Class.forName("net.minecraft.resources.ResourceKey");
            Method id = findMethod(rk, "identifier");
            return id != null ? id : findMethod(rk, "location");
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String worldId(ServerLevel level) {
        Object dim = level.dimension();
        try {
            if (KEY_ID_METHOD != null) {
                Object id = KEY_ID_METHOD.invoke(dim);
                if (id != null) return id.toString();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return String.valueOf(dim);
    }

    private static BlockPos spawnPos(ServerLevel level) {
        try {
            if (RESPAWN_DATA_METHOD != null) {
                Object data = RESPAWN_DATA_METHOD.invoke(level);
                if (data != null) {
                    Method pos = data.getClass().getMethod("pos");
                    Object result = pos.invoke(data);
                    if (result instanceof BlockPos) return (BlockPos) result;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            if (SHARED_SPAWN_METHOD != null) {
                Object result = SHARED_SPAWN_METHOD.invoke(level);
                if (result instanceof BlockPos) return (BlockPos) result;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return BlockPos.ZERO;
    }

    private static boolean teleport(ServerPlayer player, ServerLevel level, Homes.Position point) {
        try {
            if (TELEPORT_8 != null) {
                Object res = TELEPORT_8.invoke(player, level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch, true);
                return !(res instanceof Boolean) || (Boolean) res;
            }
            if (TELEPORT_7 != null) {
                Object res = TELEPORT_7.invoke(player, level, point.x, point.y, point.z,
                        Collections.emptySet(), point.yaw, point.pitch);
                return !(res instanceof Boolean) || (Boolean) res;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static Method resolveRegistryGetKeyMethod() {
        for (Method m : BuiltInRegistries.ITEM.getClass().getMethods()) {
            if ("getKey".equals(m.getName()) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private static String itemId(Item item) {
        if (item == null || REGISTRY_GET_KEY == null) return null;
        try {
            Object key = REGISTRY_GET_KEY.invoke(BuiltInRegistries.ITEM, item);
            return key == null ? null : key.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
