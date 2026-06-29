package codes.castled.allium.managers.security;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ModGuardTranslationProbe extends PacketListenerAbstract implements Listener {

    private final PluginStart plugin;
    private final ModGuardManager modGuard;
    private final Map<UUID, ProbeSession> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    ModGuardTranslationProbe(PluginStart plugin, ModGuardManager modGuard) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.modGuard = modGuard;
    }

    void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(getBypassPermission())) {
            return;
        }

        long delayTicks = config().getLong("translation-probe.delay-ticks", 60L);
        SchedulerAdapter.runLater(() -> startSession(player), delayTicks);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) {
            return;
        }

        UUID uuid = event.getUser().getUUID();
        ProbeSession session = sessions.get(uuid);
        if (session == null || session.current == null) {
            return;
        }

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        Vector3i packetPosition = packet.getBlockPosition();
        if (!session.position.equals(packetPosition)) {
            return;
        }

        event.setCancelled(true);

        String[] lines = packet.getTextLines();
        String resolved = lines.length == 0 || lines[0] == null ? "" : lines[0];
        ProbeCheck check = session.current;

        SchedulerAdapter.run(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                sessions.remove(uuid);
                return;
            }

            if (!resolved.equals(session.fallback) && check.matchesExpected(resolved)) {
                modGuard.handleTranslationProbeHit(player, check.id, check.key, resolved, session.fallback, check.action, check.requireCorroborationForKick);
            } else if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Translation probe miss for " + player.getName()
                        + ": " + check.id + " key=" + check.key + " resolved=\"" + resolved + "\"");
            }

            restoreFakeBlock(player, session);
            sendNextProbe(player, session);
        });
    }

    private void startSession(Player player) {
        if (!player.isOnline() || !isEnabled() || player.hasPermission(getBypassPermission())) {
            return;
        }

        List<ProbeCheck> checks = loadChecks();
        if (checks.isEmpty()) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Translation probe has no configured checks.");
            }
            return;
        }

        Location base = player.getLocation();
        int minY = player.getWorld().getMinHeight() + 1;
        Vector3i position = new Vector3i(base.getBlockX(), Math.max(minY, base.getBlockY() - 4), base.getBlockZ());
        ProbeSession session = new ProbeSession(position, new ArrayDeque<>(checks), createFallback());
        sessions.put(player.getUniqueId(), session);
        sendNextProbe(player, session);
    }

    private void sendNextProbe(Player player, ProbeSession session) {
        if (!player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        ProbeCheck next = session.remaining.pollFirst();
        if (next == null) {
            sessions.remove(player.getUniqueId());
            restoreFakeBlock(player, session);
            return;
        }

        session.current = next;
        sendProbePackets(player, session);

        long timeoutTicks = config().getLong("translation-probe.timeout-ticks", 80L);
        SchedulerAdapter.runLater(() -> {
            ProbeSession active = sessions.get(player.getUniqueId());
            if (active == session && active.current == next) {
                restoreFakeBlock(player, active);
                sendNextProbe(player, active);
            }
        }, timeoutTicks);
    }

    private void sendProbePackets(Player player, ProbeSession session) {
        ProbeCheck check = session.current;
        try {
            WrappedBlockState signState = WrappedBlockState.getDefaultState(StateTypes.OAK_SIGN);
            playerPacket(player, new WrapperPlayServerBlockChange(session.position, signState));
            playerPacket(player, new WrapperPlayServerBlockEntityData(session.position, BlockEntityTypes.SIGN, createSignNbt(check.key, session.fallback)));
            playerPacket(player, new WrapperPlayServerOpenSignEditor(session.position, true));

            SchedulerAdapter.runLater(() -> {
                if (player.isOnline()) {
                    playerPacket(player, new WrapperPlayServerCloseWindow());
                }
            }, 2L);
        } catch (Throwable t) {
            plugin.getLogger().warning("[ModGuard] Failed to send translation probe to " + player.getName() + ": " + t.getMessage());
            sessions.remove(player.getUniqueId());
            restoreFakeBlock(player, session);
        }
    }

    private void restoreFakeBlock(Player player, ProbeSession session) {
        Location location = new Location(player.getWorld(), session.position.getX(), session.position.getY(), session.position.getZ());
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    private void playerPacket(Player player, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private NBTCompound createSignNbt(String key, String fallback) {
        NBTCompound root = new NBTCompound();
        NBTCompound frontText = createTextNbt(key, fallback);
        NBTCompound backText = createTextNbt("", "");
        root.setTag("front_text", frontText);
        root.setTag("back_text", backText);
        root.setTag("is_waxed", new NBTByte(false));
        return root;
    }

    private NBTCompound createTextNbt(String key, String fallback) {
        NBTCompound text = new NBTCompound();
        NBTList<NBTString> messages = NBTList.createStringList();
        messages.addTag(new NBTString(key.isEmpty() ? "{\"text\":\"\"}" : translatableJson(key, fallback)));
        messages.addTag(new NBTString("{\"text\":\"\"}"));
        messages.addTag(new NBTString("{\"text\":\"\"}"));
        messages.addTag(new NBTString("{\"text\":\"\"}"));
        text.setTag("messages", messages);
        text.setTag("color", new NBTString("black"));
        text.setTag("has_glowing_text", new NBTByte(false));
        return text;
    }

    private String translatableJson(String key, String fallback) {
        return new JSONObject()
                .put("translate", key)
                .put("fallback", fallback)
                .toString();
    }

    private List<ProbeCheck> loadChecks() {
        ConfigurationSection checksSection = config().getConfigurationSection("translation-probe.checks");
        if (checksSection == null) {
            return Collections.emptyList();
        }

        List<ProbeCheck> checks = new ArrayList<>();
        String defaultAction = config().getString("translation-probe.default-action", "alert");
        for (String id : checksSection.getKeys(false)) {
            ConfigurationSection section = checksSection.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) {
                continue;
            }
            String key = section.getString("key", "");
            if (key.isBlank()) {
                continue;
            }
            List<String> expected = section.getStringList("expected");
            if (expected.isEmpty()) {
                expected = section.getStringList("expected-resolved");
            }
            checks.add(new ProbeCheck(
                    id,
                    key,
                    new HashSet<>(expected),
                    section.getString("action", defaultAction),
                    section.getBoolean("require-corroboration-for-kick", true)
            ));
        }
        return checks;
    }

    private String createFallback() {
        return config().getString("translation-probe.fallback-prefix", "allium_probe_") + Long.toHexString(random.nextLong());
    }

    private boolean isEnabled() {
        return config().getBoolean("translation-probe.enabled", false);
    }

    private boolean isDebug() {
        return config().getBoolean("debug", false) || config().getBoolean("translation-probe.debug", false);
    }

    private String getBypassPermission() {
        return config().getString("bypass-permission", "modguard.bypass");
    }

    private org.bukkit.configuration.file.FileConfiguration config() {
        return modGuard.getModGuardConfig();
    }

    private static final class ProbeSession {
        private final Vector3i position;
        private final Deque<ProbeCheck> remaining;
        private final String fallback;
        private ProbeCheck current;

        private ProbeSession(Vector3i position, Deque<ProbeCheck> remaining, String fallback) {
            this.position = position;
            this.remaining = remaining;
            this.fallback = fallback;
        }
    }

    private static final class ProbeCheck {
        private final String id;
        private final String key;
        private final Set<String> expected;
        private final String action;
        private final boolean requireCorroborationForKick;

        private ProbeCheck(String id, String key, Set<String> expected, String action, boolean requireCorroborationForKick) {
            this.id = id;
            this.key = key;
            this.expected = expected;
            this.action = action;
            this.requireCorroborationForKick = requireCorroborationForKick;
        }

        private boolean matchesExpected(String resolved) {
            if (expected.isEmpty()) {
                return false;
            }
            String normalized = resolved.toLowerCase(Locale.ROOT);
            for (String value : expected) {
                if (normalized.equals(value.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }
}
