package codes.castled.allium.minecraft;

import codes.castled.allium.brigadier.NativeCommands;
import codes.castled.allium.brigadier.NativePlatform;
import codes.castled.allium.portable.DirectMessages;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.NativeEconomy;
import codes.castled.allium.portable.NativePlayerInfo;
import codes.castled.allium.portable.NativeTextPages;
import codes.castled.allium.portable.TeleportRequests;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Shared Mojang-mapped {@link NativePlatform} implementation for Fabric 1.20.1/1.21.11/26.x
 * and NeoForge 1.20.2/26.x.
 */
public final class MinecraftNativePlatform implements NativePlatform<CommandSourceStack, ServerPlayer> {
    private static final TeleportRequests TELEPORTS = new TeleportRequests();
    private static final DirectMessages MESSAGES = new DirectMessages();
    private static final NativeEconomy.PendingConfirmations PAY_CONFIRMATIONS = new NativeEconomy.PendingConfirmations();
    private static final NativePlayerInfo.SessionState PLAYER_SESSIONS = new NativePlayerInfo.SessionState();
    private static final NativeTextPages.BroadcastState BROADCASTS = new NativeTextPages.BroadcastState();

    private final Predicate<CommandSourceStack> gamemaster;
    private final Function<ServerPlayer, ServerLevel> levelOf;
    private final Function<ServerLevel, String> worldId;
    private final Function<ServerLevel, BlockPos> spawnPos;
    private final ToIntFunction<ServerPlayer> latencyOf;
    private final MinecraftHomes.Teleport teleport;

    public MinecraftNativePlatform(Predicate<CommandSourceStack> gamemaster,
                                   Function<ServerPlayer, ServerLevel> levelOf,
                                   Function<ServerLevel, String> worldId,
                                   Function<ServerLevel, BlockPos> spawnPos,
                                   ToIntFunction<ServerPlayer> latencyOf,
                                   MinecraftHomes.Teleport teleport) {
        this.gamemaster = gamemaster;
        this.levelOf = levelOf;
        this.worldId = worldId;
        this.spawnPos = spawnPos;
        this.latencyOf = latencyOf;
        this.teleport = teleport;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Predicate<CommandSourceStack> gamemaster,
                                Function<ServerPlayer, ServerLevel> levelOf,
                                Function<ServerLevel, String> worldId,
                                Function<ServerLevel, BlockPos> spawnPos,
                                ToIntFunction<ServerPlayer> latencyOf,
                                MinecraftHomes.Teleport teleport) {
        NativeCommands.register(dispatcher,
                new MinecraftNativePlatform(gamemaster, levelOf, worldId, spawnPos, latencyOf, teleport),
                TELEPORTS, MESSAGES, PAY_CONFIRMATIONS, PLAYER_SESSIONS, BROADCASTS);
    }

    @Override
    public Path dataDirectory(CommandSourceStack source) {
        return source.getServer().getWorldPath(LevelResource.ROOT).resolve("allium");
    }

    @Override
    public boolean isGamemaster(CommandSourceStack source) {
        return gamemaster.test(source);
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
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
