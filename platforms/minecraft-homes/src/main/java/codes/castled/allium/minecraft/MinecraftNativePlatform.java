package codes.castled.allium.minecraft;

import codes.castled.allium.brigadier.NativeChatCommands;
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
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Shared Mojang-mapped {@link NativePlatform} implementation for Fabric 1.20.1/1.21.11/26.x
 * and NeoForge 1.20.2/26.x. Stores configuration, language files, text pages, and flatfile data
 * under {@code <serverRoot>/plugins/Allium}.
 */
public final class MinecraftNativePlatform implements NativePlatform<CommandSourceStack, ServerPlayer> {
    private static final TeleportRequests TELEPORTS = new TeleportRequests();
    private static final DirectMessages MESSAGES = new DirectMessages();
    private static final NativeEconomy.PendingConfirmations PAY_CONFIRMATIONS = new NativeEconomy.PendingConfirmations();
    private static final NativePlayerInfo.SessionState PLAYER_SESSIONS = new NativePlayerInfo.SessionState();
    private static final NativeTextPages.BroadcastState BROADCASTS = new NativeTextPages.BroadcastState();
    private static final NativeModeration.SessionState MOD_STATE = new NativeModeration.SessionState();
    private static final NativeDiscordRelay DISCORD = new NativeDiscordRelay();
    private static final AtomicBoolean FABRIC_LIFECYCLE_HOOKED = new AtomicBoolean(false);
    private static volatile MinecraftNativePlatform active;

    private final Path dataDirectory;
    private final NativeConfig config;
    private final Predicate<CommandSourceStack> gamemaster;
    private final Function<ServerPlayer, ServerLevel> levelOf;
    private final Function<ServerLevel, String> worldId;
    private final Function<ServerLevel, BlockPos> spawnPos;
    private final ToIntFunction<ServerPlayer> latencyOf;
    private final Function<Item, String> itemIdOf;
    private final MinecraftHomes.Teleport teleport;

    public MinecraftNativePlatform(Path dataDirectory,
                                   Predicate<CommandSourceStack> gamemaster,
                                   Function<ServerPlayer, ServerLevel> levelOf,
                                   Function<ServerLevel, String> worldId,
                                   Function<ServerLevel, BlockPos> spawnPos,
                                   ToIntFunction<ServerPlayer> latencyOf,
                                   Function<Item, String> itemIdOf,
                                   MinecraftHomes.Teleport teleport) {
        this.dataDirectory = (dataDirectory != null ? dataDirectory : Paths.get("plugins", "Allium"))
                .toAbsolutePath().normalize();
        this.config = new NativeConfig(this.dataDirectory);
        this.gamemaster = gamemaster;
        this.levelOf = levelOf;
        this.worldId = worldId;
        this.spawnPos = spawnPos;
        this.latencyOf = latencyOf;
        this.itemIdOf = itemIdOf;
        this.teleport = teleport;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Path dataDirectory,
                                Predicate<CommandSourceStack> gamemaster,
                                Function<ServerPlayer, ServerLevel> levelOf,
                                Function<ServerLevel, String> worldId,
                                Function<ServerLevel, BlockPos> spawnPos,
                                ToIntFunction<ServerPlayer> latencyOf,
                                Function<Item, String> itemIdOf,
                                MinecraftHomes.Teleport teleport) {
        MinecraftNativePlatform instance = new MinecraftNativePlatform(
                dataDirectory, gamemaster, levelOf, worldId, spawnPos, latencyOf, itemIdOf, teleport);
        active = instance;
        NativeCommands.register(dispatcher, instance, TELEPORTS, MESSAGES, PAY_CONFIRMATIONS,
                PLAYER_SESSIONS, BROADCASTS, MOD_STATE, DISCORD);
    }

    public static boolean handleChat(ServerPlayer player, String rawMessage) {
        MinecraftNativePlatform current = active;
        if (current == null || player == null) return false;
        return NativeChatCommands.handleChat(current, player.createCommandSourceStack(), player,
                rawMessage, PLAYER_SESSIONS, MOD_STATE, DISCORD);
    }

    public static void handleJoin(ServerPlayer player) {
        MinecraftNativePlatform current = active;
        if (current == null || player == null) return;
        NativeChatCommands.handleJoin(current, player.createCommandSourceStack(), player,
                PLAYER_SESSIONS, MOD_STATE, DISCORD);
    }

    public static void handleQuit(ServerPlayer player) {
        MinecraftNativePlatform current = active;
        if (current == null || player == null) return;
        NativeChatCommands.handleQuit(current, player.createCommandSourceStack(), player,
                PLAYER_SESSIONS, MOD_STATE, DISCORD);
    }

    public static String reloadAndMessage() {
        MinecraftNativePlatform current = active;
        if (current == null) {
            return "Configuration reloaded.";
        }
        current.config.reload();
        return current.config.format("core.reload-success", "{prefix} &aConfiguration reloaded.");
    }

    /** Reflectively registers Fabric {@code ServerPlayConnectionEvents} join/quit hooks when available. */
    public static void tryHookFabricConnectionEvents() {
        if (!FABRIC_LIFECYCLE_HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
            Class<?> joinIface = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join");
            Class<?> discIface = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect");
            Object joinEvent = eventsClass.getField("JOIN").get(null);
            Object discEvent = eventsClass.getField("DISCONNECT").get(null);
            Method registerMethod = joinEvent.getClass().getMethod("register", Object.class);

            Object joinProxy = Proxy.newProxyInstance(joinIface.getClassLoader(), new Class<?>[]{joinIface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length >= 1 && args[0] instanceof ServerGamePacketListenerImpl) {
                                handleJoin(((ServerGamePacketListenerImpl) args[0]).getPlayer());
                            }
                            return null;
                        }
                    });
            Object discProxy = Proxy.newProxyInstance(discIface.getClassLoader(), new Class<?>[]{discIface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (args != null && args.length >= 1 && args[0] instanceof ServerGamePacketListenerImpl) {
                                handleQuit(((ServerGamePacketListenerImpl) args[0]).getPlayer());
                            }
                            return null;
                        }
                    });
            registerMethod.invoke(joinEvent, joinProxy);
            registerMethod.invoke(discEvent, discProxy);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Path dataDirectory(CommandSourceStack source) {
        return dataDirectory;
    }

    @Override
    public NativeConfig config(CommandSourceStack source) {
        return config;
    }

    @Override
    public void reloadConfig(CommandSourceStack source) {
        config.reload();
    }

    @Override
    public boolean isGamemaster(CommandSourceStack source) {
        return gamemaster.test(source);
    }

    @Override
    public boolean isGamemasterPlayer(ServerPlayer player) {
        return gamemaster.test(player.createCommandSourceStack());
    }

    @Override
    public ServerPlayer playerOrNull(CommandSourceStack source) {
        return source.getPlayer();
    }

    @Override
    public UUID id(ServerPlayer player) {
        return player.getUUID();
    }

    @Override
    public String name(ServerPlayer player) {
        return player.getScoreboardName();
    }

    @Override
    public Collection<ServerPlayer> onlinePlayers(CommandSourceStack source) {
        return source.getServer().getPlayerList().getPlayers();
    }

    @Override
    public Homes.Position positionOf(ServerPlayer player) {
        return new Homes.Position(
                worldId.apply(levelOf.apply(player)),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        );
    }

    @Override
    public Homes.Position defaultSpawn(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        if (overworld == null) return null;
        BlockPos pos = spawnPos.apply(overworld);
        if (pos == null) return null;
        return new Homes.Position(worldId.apply(overworld), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
    }

    @Override
    public boolean teleport(CommandSourceStack source, ServerPlayer player, Homes.Position destination) {
        MinecraftServer server = source.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (worldId.apply(level).equals(destination.world)) {
                return teleport.move(player, level, destination);
            }
        }
        return false;
    }

    @Override
    public void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    @Override
    public void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public void broadcast(CommandSourceStack source, String message) {
        source.getServer().getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    @Override
    public void kick(ServerPlayer player, String reason) {
        player.connection.disconnect(Component.literal(reason));
    }

    @Override
    public void heal(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        player.clearFire();
    }

    @Override
    public void feed(ServerPlayer player) {
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
    }

    @Override
    public boolean toggleFly(ServerPlayer player) {
        boolean next = !player.getAbilities().mayfly;
        player.getAbilities().mayfly = next;
        if (!next) {
            player.getAbilities().flying = false;
        }
        player.onUpdateAbilities();
        return next;
    }

    @Override
    public boolean setGameMode(ServerPlayer player, String mode) {
        GameType target;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "survival": target = GameType.SURVIVAL; break;
            case "creative": target = GameType.CREATIVE; break;
            case "adventure": target = GameType.ADVENTURE; break;
            case "spectator": target = GameType.SPECTATOR; break;
            default: return false;
        }
        player.setGameMode(target);
        return true;
    }

    @Override
    public int seaLevel(ServerPlayer player) {
        return levelOf.apply(player).getSeaLevel();
    }

    @Override
    public int ping(ServerPlayer player) {
        return Math.max(0, latencyOf.applyAsInt(player));
    }

    @Override
    public boolean setTime(CommandSourceStack source, long ticks) {
        source.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), "time set " + ticks);
        return true;
    }

    @Override
    public boolean setWeather(CommandSourceStack source, String mode) {
        String clean;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "clear": clean = "clear"; break;
            case "rain": clean = "rain"; break;
            case "thunder": clean = "thunder"; break;
            default: return false;
        }
        source.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), "weather " + clean);
        return true;
    }

    @Override
    public void clearInventory(ServerPlayer player) {
        player.getInventory().clearContent();
    }

    @Override
    public String heldItemId(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return null;
        return itemIdOf.apply(stack.getItem());
    }

    @Override
    public int heldItemCount(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    @Override
    public int consumeHeldItem(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return 0;
        int count = stack.getCount();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return count;
    }

    @Override
    public Map<String, Integer> consumeSellableItems(ServerPlayer player, Predicate<String> sellable) {
        Inventory inv = player.getInventory();
        int limit = Math.min(36, inv.getContainerSize());
        Map<String, Integer> sold = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = itemIdOf.apply(stack.getItem());
            if (!sellable.test(id)) continue;
            int count = stack.getCount();
            inv.setItem(i, ItemStack.EMPTY);
            Integer prev = sold.get(id);
            sold.put(id, prev == null ? count : prev + count);
        }
        return sold;
    }

    @Override
    public Object serverHandle(CommandSourceStack source) {
        return source.getServer();
    }

    @Override
    public int maxPlayers(CommandSourceStack source) {
        return source.getServer().getPlayerList().getMaxPlayers();
    }

    @Override
    public String serverName(CommandSourceStack source) {
        return source.getServer().getServerModName();
    }
}
