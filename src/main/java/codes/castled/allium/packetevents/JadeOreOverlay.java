package codes.castled.allium.packetevents;

import codes.castled.allium.managers.world.OreGenerationManager;
import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Jade 26.3 discovers datapack blocks from nearby display item representations.
 * One zero-scale, packet-only item display follows each Jade player's target.
 * No world entities, block entities, per-ore entities or fake handshake providers.
 */
public final class JadeOreOverlay implements Listener, PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "jade:client_handshake";
    private final Plugin plugin;
    private final OreGenerationManager manager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final class Session {
        final Player player;
        TaskHandle task;
        int entityId = -1;
        String target;
        Session(Player player) { this.player = player; }
    }

    public JadeOreOverlay(Plugin plugin, OreGenerationManager manager) {
        this.plugin = plugin; this.manager = manager;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) detectExisting(player);
    }

    /** Minecraft's bounded VarInt-length UTF-8 handshake, currently protocol 9. */
    public static boolean supportedHandshake(byte[] bytes) {
        if (bytes.length < 2 || bytes.length > 8) return false;
        return bytes[0] == bytes.length - 1 && "9".equals(new String(bytes, 1, bytes.length - 1, StandardCharsets.UTF_8));
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (CHANNEL.equals(channel) && supportedHandshake(message)) start(player);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        SchedulerAdapter.runLaterEntity(plugin, event.getPlayer(), () -> detectExisting(event.getPlayer()), 20);
    }
    private void detectExisting(Player player) {
        if (player.getListeningPluginChannels().stream().anyMatch(channel -> channel.startsWith("jade:"))) start(player);
    }
    private void start(Player player) {
        if (closed) return;
        sessions.computeIfAbsent(player.getUniqueId(), ignored -> {
            Session session = new Session(player);
            session.task = SchedulerAdapter.runEntityRepeating(plugin, player, () -> update(session), () -> remove(player), 1, 4);
            return session;
        });
    }

    private void update(Session session) {
        Player player = session.player;
        if (closed || !player.isOnline()) return;
        var hit = player.rayTraceBlocks(6, FluidCollisionMode.NEVER);
        Block block = hit == null ? null : hit.getHitBlock();
        String id = block == null || manager.placement() == null ? null : manager.placement().idOf(block);
        if (id == null || manager.definitions().stream().noneMatch(ore -> ore.block().toString().equals(id)
            || ore.deepslateBlock().toString().equals(id))) { destroy(session); return; }
        String target = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ() + ":" + id;
        if (target.equals(session.target)) return;
        destroy(session);
        var item = manager.placement().itemOf(id);
        if (item == null || item.getType().isAir()) return;
        var packetItem = SpigotConversionUtil.fromBukkitItemStack(item);
        NBTCompound customData = packetItem.getComponentOr(ComponentTypes.CUSTOM_DATA, new NBTCompound()).copy();
        NBTCompound jadeId = new NBTCompound();
        jadeId.setTag("id", new NBTString(id));
        customData.setTag("$jade:stack", jadeId);
        packetItem.setComponent(ComponentTypes.CUSTOM_DATA, customData);
        int entityId = Bukkit.getUnsafe().nextEntityId(block.getWorld());
        var packets = PacketEvents.getAPI().getPlayerManager();
        packets.sendPacket(player, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_DISPLAY,
            new Vector3d(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5), 0, 0, 0, 0, Optional.empty()));
        packets.sendPacket(player, new WrapperPlayServerEntityMetadata(entityId, displayMetadata(packetItem)));
        session.entityId = entityId;
        session.target = target;
    }

    static List<EntityData<?>> displayMetadata(com.github.retrooper.packetevents.protocol.item.ItemStack packetItem) {
        return List.of(
            new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(0, 0, 0)),
            new EntityData<>(19, EntityDataTypes.FLOAT, 0f), // shadow strength
            new EntityData<>(20, EntityDataTypes.FLOAT, 1f), // bounding width
            new EntityData<>(21, EntityDataTypes.FLOAT, 1f),
            // Index 22 is the integer glow-color override, never a display dimension.
            new EntityData<>(23, EntityDataTypes.ITEMSTACK, packetItem));
    }

    private void destroy(Session session) {
        if (session.entityId >= 0 && session.player.isOnline())
            PacketEvents.getAPI().getPlayerManager().sendPacket(session.player, new WrapperPlayServerDestroyEntities(session.entityId));
        session.entityId = -1; session.target = null;
    }
    private void remove(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        if (session.task != null) session.task.cancel();
        destroy(session);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { remove(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) destroy(session);
    }
    @Override public void close() {
        closed = true;
        for (Session session : sessions.values()) { if (session.task != null) session.task.cancel(); destroy(session); }
        sessions.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        HandlerList.unregisterAll(this);
    }
}
