package codes.castled.allium.fabric.v114;

import codes.castled.allium.brigadier.NativeCommands;
import codes.castled.allium.brigadier.NativePlatform;
import codes.castled.allium.portable.DirectMessages;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeConfig;
import codes.castled.allium.portable.NativeDiscordRelay;
import codes.castled.allium.portable.NativeEconomy;
import codes.castled.allium.portable.NativeModeration;
import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.NativeTextPages;
import codes.castled.allium.portable.TeleportRequests;
import codes.castled.allium.portable.UtilityMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.registry.CommandRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.arguments.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.LevelProperties;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class LegacyFabric implements DedicatedServerModInitializer {
    private static final TeleportRequests TELEPORTS = new TeleportRequests();
    private static final DirectMessages MESSAGES = new DirectMessages();
    private static final NativeEconomy.PendingConfirmations PAY_CONFIRMATIONS = new NativeEconomy.PendingConfirmations();
    private static final NativePlayerInfo.SessionState PLAYER_SESSIONS = new NativePlayerInfo.SessionState();
    private static final NativeTextPages.BroadcastState BROADCASTS = new NativeTextPages.BroadcastState();
    private static final NativeModeration.SessionState MOD_STATE = new NativeModeration.SessionState();
    private static final NativeDiscordRelay DISCORD = new NativeDiscordRelay();

    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        String version = loader.getModContainer("allium").get().getMetadata().getVersion().getFriendlyString();
        Path dataDir = loader.getGameDirectory().toPath().resolve("plugins").resolve("Allium").toAbsolutePath().normalize();
        final Fabric114Platform platform = new Fabric114Platform(dataDir);
        CommandRegistry.INSTANCE.register(true, dispatcher -> {
            NativeCommands.register(dispatcher, platform, TELEPORTS, MESSAGES,
                    PAY_CONFIRMATIONS, PLAYER_SESSIONS, BROADCASTS, MOD_STATE, DISCORD);
            register(dispatcher, CommandManager.literal("allium:allium")
                    .executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS))
                    .then(CommandManager.literal("help").executes(ctx -> reply(ctx.getSource(), UtilityMessages.COMMANDS)))
                    .then(CommandManager.literal("version").executes(ctx ->
                            reply(ctx.getSource(), UtilityMessages.version(version, "Fabric", "1.14"))))
                    .then(CommandManager.literal("reload")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(ctx -> {
                                platform.reloadConfig(ctx.getSource());
                                return reply(ctx.getSource(), platform.config(ctx.getSource())
                                        .format("core.reload-success", "{prefix} &aConfiguration reloaded."));
                            })), "allium", "core");
            playerUtility(dispatcher, "ping", player -> UtilityMessages.ping(player.getEntityName(), player.field_13967), "pong");
            playerUtility(dispatcher, "coords", player -> {
                BlockPos position = player.getBlockPos();
                return UtilityMessages.coordinates(player.getEntityName(), position.getX(), position.getY(), position.getZ(),
                        DimensionType.getId(player.dimension).toString());
            }, "getpos", "whereami", "getloc", "getlocation", "position");
        });
    }

    private static final class Fabric114Platform implements NativePlatform<ServerCommandSource, ServerPlayerEntity> {
        private final Path dataDirectory;
        private final NativeConfig config;

        Fabric114Platform(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
            this.config = new NativeConfig(dataDirectory);
        }

        @Override
        public Path dataDirectory(ServerCommandSource source) {
            return dataDirectory;
        }

        @Override
        public NativeConfig config(ServerCommandSource source) {
            return config;
        }

        @Override
        public void reloadConfig(ServerCommandSource source) {
            config.reload();
        }

        @Override
        public boolean isGamemaster(ServerCommandSource source) {
            return source.hasPermissionLevel(2);
        }

        @Override
        public boolean isGamemasterPlayer(ServerPlayerEntity player) {
            return player.getCommandSource().hasPermissionLevel(2);
        }

        @Override
        public void kick(ServerPlayerEntity player, String reason) {
            player.networkHandler.disconnect(new TextComponent(reason));
        }

        @Override
        public ServerPlayerEntity playerOrNull(ServerCommandSource source) {
            return source.getEntity() instanceof ServerPlayerEntity ? (ServerPlayerEntity) source.getEntity() : null;
        }

        @Override
        public UUID id(ServerPlayerEntity player) {
            return player.getUuid();
        }

        @Override
        public String name(ServerPlayerEntity player) {
            return player.getEntityName();
        }

        @Override
        public Collection<ServerPlayerEntity> onlinePlayers(ServerCommandSource source) {
            return source.getMinecraftServer().getPlayerManager().getPlayerList();
        }

        @Override
        public Homes.Position positionOf(ServerPlayerEntity player) {
            return new Homes.Position(DimensionType.getId(player.dimension).toString(),
                    player.x, player.y, player.z, player.yaw, player.pitch);
        }

        @Override
        public Homes.Position defaultSpawn(ServerCommandSource source) {
            ServerWorld overworld = source.getMinecraftServer().getWorld(DimensionType.OVERWORLD);
            if (overworld == null) return null;
            LevelProperties props = overworld.getLevelProperties();
            return new Homes.Position(DimensionType.getId(DimensionType.OVERWORLD).toString(),
                    props.getSpawnX() + 0.5, props.getSpawnY(), props.getSpawnZ() + 0.5, 0.0F, 0.0F);
        }

        @Override
        public boolean teleport(ServerCommandSource source, ServerPlayerEntity player, Homes.Position destination) {
            DimensionType type = DimensionType.byId(new Identifier(destination.world));
            ServerWorld world = type == null ? null : source.getMinecraftServer().getWorld(type);
            if (world == null) return false;
            player.teleport(world, destination.x, destination.y, destination.z, destination.yaw, destination.pitch);
            return true;
        }

        @Override
        public void reply(ServerCommandSource source, String message) {
            source.sendFeedback(new TextComponent(message), false);
        }

        @Override
        public void send(ServerPlayerEntity player, String message) {
            player.sendMessage(new TextComponent(message));
        }

        @Override
        public void broadcast(ServerCommandSource source, String message) {
            source.getMinecraftServer().getPlayerManager().sendToAll(new TextComponent(message));
        }

        @Override
        public void heal(ServerPlayerEntity player) {
            player.setHealth(player.getHealthMaximum());
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().add(20, 1.0F);
            player.extinguish();
        }

        @Override
        public void feed(ServerPlayerEntity player) {
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().add(20, 1.0F);
        }

        @Override
        public boolean toggleFly(ServerPlayerEntity player) {
            boolean next = !player.abilities.allowFlying;
            player.abilities.allowFlying = next;
            if (!next) {
                player.abilities.flying = false;
            }
            player.sendAbilitiesUpdate();
            return next;
        }

        @Override
        public boolean setGameMode(ServerPlayerEntity player, String mode) {
            GameMode target;
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "survival": target = GameMode.SURVIVAL; break;
                case "creative": target = GameMode.CREATIVE; break;
                case "adventure": target = GameMode.ADVENTURE; break;
                case "spectator": target = GameMode.SPECTATOR; break;
                default: return false;
            }
            player.setGameMode(target);
            return true;
        }

        @Override
        public int seaLevel(ServerPlayerEntity player) {
            return player.world.getSeaLevel();
        }

        @Override
        public int ping(ServerPlayerEntity player) {
            return Math.max(0, player.field_13967);
        }

        @Override
        public boolean setTime(ServerCommandSource source, long ticks) {
            source.getMinecraftServer().getCommandManager().execute(source.withSilent(), "time set " + ticks);
            return true;
        }

        @Override
        public boolean setWeather(ServerCommandSource source, String mode) {
            String clean;
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "clear": clean = "clear"; break;
                case "rain": clean = "rain"; break;
                case "thunder": clean = "thunder"; break;
                default: return false;
            }
            source.getMinecraftServer().getCommandManager().execute(source.withSilent(), "weather " + clean);
            return true;
        }

        @Override
        public void clearInventory(ServerPlayerEntity player) {
            player.inventory.clear();
        }

        @Override
        public String heldItemId(ServerPlayerEntity player) {
            ItemStack stack = player.getMainHandStack();
            if (stack.isEmpty()) return null;
            return Registry.ITEM.getId(stack.getItem()).toString();
        }

        @Override
        public int heldItemCount(ServerPlayerEntity player) {
            ItemStack stack = player.getMainHandStack();
            return stack.isEmpty() ? 0 : stack.getAmount();
        }

        @Override
        public int consumeHeldItem(ServerPlayerEntity player) {
            ItemStack stack = player.getMainHandStack();
            if (stack.isEmpty()) return 0;
            int count = stack.getAmount();
            player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
            return count;
        }

        @Override
        public Map<String, Integer> consumeSellableItems(ServerPlayerEntity player, Predicate<String> sellable) {
            int limit = Math.min(36, player.inventory.getInvSize());
            Map<String, Integer> sold = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < limit; i++) {
                ItemStack stack = player.inventory.getInvStack(i);
                if (stack.isEmpty()) continue;
                String id = Registry.ITEM.getId(stack.getItem()).toString();
                if (!sellable.test(id)) continue;
                int count = stack.getAmount();
                player.inventory.setInvStack(i, ItemStack.EMPTY);
                Integer prev = sold.get(id);
                sold.put(id, prev == null ? count : prev + count);
            }
            return sold;
        }

        @Override
        public Object serverHandle(ServerCommandSource source) {
            return source.getMinecraftServer();
        }

        @Override
        public int maxPlayers(ServerCommandSource source) {
            return source.getMinecraftServer().getMaxPlayerCount();
        }

        @Override
        public String serverName(ServerCommandSource source) {
            return source.getMinecraftServer().getServerModName();
        }
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
