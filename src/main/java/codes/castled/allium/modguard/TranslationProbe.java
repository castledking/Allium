package codes.castled.allium.modguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
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

import codes.castled.allium.scheduler.TaskScheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sign Translation Vulnerability (MC-265322) based client-mod probing.
 *
 * A ghost sign whose lines are translation
 * (or keybind) components is shoved at the client, the sign editor is popped
 * open for a split second, and the resolved text the client echoes back is
 * compared against the fallback/keys to fingerprint installed mods.
 *
 * Multi-probe: up to {@link #CHECKS_PER_SIGN} checks are packed onto one sign,
 * one per line, plus a control line carrying a vanilla keybind (key.forward)
 * used to detect sign-spoofing/exploit-preventer mods.
 *
 * <p>Each sign batch uses a distinct block position inside the player's chunk and an immutable
 * {@link ActiveBatch} snapshot so a delayed echo from an earlier (especially keybind-only) sign
 * can never be mis-scored against a later batch.
 */
final class TranslationProbe extends PacketListenerAbstract implements Listener {

    private static final int    CHECKS_PER_SIGN = 3;
    private static final String CTRL_KEYBIND    = "key.forward";
    private static final long   PACK_DEFER_MAX_MS = 15_000L;

    private final org.bukkit.plugin.Plugin plugin;
    private final TaskScheduler scheduler;
    private final ModGuard modGuard;
    private final Map<UUID, ProbeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> packLoadingUntil = new ConcurrentHashMap<>();
    private final Set<UUID> deferredStart = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    TranslationProbe(org.bukkit.plugin.Plugin plugin, TaskScheduler scheduler, ModGuard modGuard) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.modGuard = modGuard;
    }

    void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ModGuard] Translation probe registered with " + loadChecks().size() + " checks.");
    }

    void unregister() {
        // A reload builds a fresh probe; the old one must stop completely.
        // Leaving its join listener alive made it keep sending its own ghost
        // signs, whose echoes the new probe then scored as detections.
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        HandlerList.unregisterAll(this);
        sessions.clear();
        packLoadingUntil.clear();
        deferredStart.clear();
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
        if (isFloodgatePlayer(player)) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Skipping translation probe for Floodgate player: " + player.getName());
            }
            return;
        }

        long delayTicks = config().getLong("translation-probe.delay-ticks", 60L);
        scheduler.runOnServerLater(() -> maybeStartSession(player), delayTicks * TaskScheduler.MILLIS_PER_TICK);
    }

    /**
     * Clients downloading or applying a server resource pack stall and close/reopen UI screens;
     * defer starting the ghost-sign probe until the pack finishes applying (bounded by {@link #PACK_DEFER_MAX_MS}).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (closed) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String status = event.getStatus().name();
        if ("ACCEPTED".equals(status) || "DOWNLOADED".equals(status)) {
            packLoadingUntil.put(uuid, System.currentTimeMillis() + PACK_DEFER_MAX_MS);
            return;
        }
        packLoadingUntil.remove(uuid);
        if (deferredStart.remove(uuid) && player.isOnline()) {
            scheduler.runOnServerLater(() -> maybeStartSession(player), 20L * TaskScheduler.MILLIS_PER_TICK);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        packLoadingUntil.remove(uuid);
        deferredStart.remove(uuid);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) {
            return;
        }

        UUID uuid = event.getUser().getUUID();
        ProbeSession session = sessions.get(uuid);
        if (session == null) {
            return;
        }

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        Vector3i packetPosition = packet.getBlockPosition();
        if (!session.sentPositions.contains(packetPosition)) {
            return;
        }

        // Always cancel echoes for any ghost sign this session sent, even stale ones.
        event.setCancelled(true);

        ActiveBatch batch = session.active.get();
        if (batch == null || !batch.position.equals(packetPosition)) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Ignoring stale translation probe echo for " + uuid
                        + " at " + packetPosition + " (active=" + (batch == null ? "none" : batch.position) + ")");
            }
            return;
        }

        String[] lines = packet.getTextLines();
        String ctrlResp = lines.length > 3 && lines[3] != null ? lines[3].strip() : "";
        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);

        List<ProbeMode> modes = new ArrayList<>(batch.checks.size());
        List<String> expectedKeys = new ArrayList<>(batch.checks.size());
        for (ProbeCheck check : batch.checks) {
            modes.add(check.mode);
            expectedKeys.add(check.key);
        }
        if (isForeignEcho(lines, modes, expectedKeys, session.allKeys, batch.fallback, fallbackPrefix())) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Ignoring foreign translation probe echo for " + uuid
                        + ": " + Arrays.toString(lines) + " (expected fallback " + batch.fallback + ")");
            }
            return;
        }
        if (!session.active.compareAndSet(batch, null)) {
            return;
        }

        Map<ProbeCheck, String> hits = new LinkedHashMap<>();
        for (int i = 0; i < batch.checks.size(); i++) {
            String resolved = i < lines.length && lines[i] != null ? lines[i].strip() : "";
            if (batch.checks.get(i).evaluate(resolved, batch.fallback, exploitPreventer) == ProbeResult.DETECTED) {
                hits.put(batch.checks.get(i), resolved);
            } else if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Translation probe miss for " + uuid
                        + ": " + batch.checks.get(i).id + " key=" + batch.checks.get(i).key + " resolved=\"" + resolved + "\"");
            }
        }
        final Map<ProbeCheck, String> finalHits = hits;

        scheduler.runOnServer(() -> {
            if (closed) {
                return;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                sessions.remove(uuid);
                return;
            }

            restoreFakeBlock(player, batch.position);

            for (Map.Entry<ProbeCheck, String> hit : finalHits.entrySet()) {
                if (!player.isOnline()) {
                    break;
                }
                ProbeCheck check = hit.getKey();
                modGuard.handleTranslationProbeHit(player, check.displayName, check.key,
                        hit.getValue(), batch.fallback, check.action, check.requireCorroborationForKick);
            }

            sendNextBatch(player, session);
        });
    }

    private void maybeStartSession(Player player) {
        if (closed || !player.isOnline() || !isEnabled() || player.hasPermission(getBypassPermission())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Long deadline = packLoadingUntil.get(uuid);
        if (deadline != null) {
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs > 0L) {
                if (deferredStart.add(uuid)) {
                    // Safety wake-up if the client never sends a terminal resource-pack status.
                    scheduler.runOnServerLater(() -> {
                        if (deferredStart.remove(uuid)) {
                            packLoadingUntil.remove(uuid);
                            maybeStartSession(player);
                        }
                    }, remainingMs + 500L);
                }
                return;
            }
            packLoadingUntil.remove(uuid);
        }
        if (sessions.containsKey(uuid)) {
            return;
        }
        startSession(player);
    }

    private void startSession(Player player) {
        if (closed || !player.isOnline() || !isEnabled() || player.hasPermission(getBypassPermission())) {
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
        Vector3i origin = new Vector3i(base.getBlockX(), Math.max(minY, base.getBlockY() - 30), base.getBlockZ());
        Set<String> allKeys = new HashSet<>(checks.size() + 1);
        for (ProbeCheck check : checks) {
            allKeys.add(check.key.toLowerCase(Locale.ROOT));
        }
        allKeys.add(CTRL_KEYBIND.toLowerCase(Locale.ROOT));
        ProbeSession session = new ProbeSession(origin, new ArrayDeque<>(checks), Set.copyOf(allKeys));
        sessions.put(player.getUniqueId(), session);
        sendNextBatch(player, session);
    }

    /**
     * Assigns each batch a distinct block position inside the player's starting chunk (up to 256
     * unique coordinates per Y level) so every sign echo is unambiguously tied to its exact batch.
     */
    static Vector3i batchPosition(Vector3i origin, int batchIndex) {
        int chunkMinX = (origin.getX() >> 4) << 4;
        int chunkMinZ = (origin.getZ() >> 4) << 4;
        int startLocal = ((origin.getZ() & 15) << 4) | (origin.getX() & 15);
        int slot = (startLocal + batchIndex) & 255;
        int y = origin.getY() + (batchIndex / 256);
        return new Vector3i(chunkMinX + (slot & 15), y, chunkMinZ + ((slot >> 4) & 15));
    }

    private void sendNextBatch(Player player, ProbeSession session) {
        if (closed || !player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        List<ProbeCheck> checks = new ArrayList<>();
        while (checks.size() < CHECKS_PER_SIGN && !session.remaining.isEmpty()) {
            checks.add(session.remaining.pollFirst());
        }
        if (checks.isEmpty()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        Vector3i position = batchPosition(session.origin, session.batchIndex++);
        String fallback = createFallback();
        ActiveBatch batch = new ActiveBatch(position, List.copyOf(checks), fallback);
        session.sentPositions.add(position);
        session.active.set(batch);
        sendProbePackets(player, batch);

        long timeoutTicks = config().getLong("translation-probe.timeout-ticks", 80L);
        scheduler.runOnServerLater(() -> {
            if (closed) {
                return;
            }
            ProbeSession activeSession = sessions.get(player.getUniqueId());
            if (activeSession == session && session.active.compareAndSet(batch, null)) {
                if (player.isOnline()) {
                    restoreFakeBlock(player, batch.position);
                    sendNextBatch(player, activeSession);
                } else {
                    sessions.remove(player.getUniqueId());
                }
            }
        }, timeoutTicks * TaskScheduler.MILLIS_PER_TICK);
    }

    private void sendProbePackets(Player player, ActiveBatch batch) {
        try {
            WrappedBlockState signState = WrappedBlockState.getDefaultState(StateTypes.OAK_SIGN);
            playerPacket(player, new WrapperPlayServerBlockChange(batch.position, signState));
            playerPacket(player, new WrapperPlayServerBlockEntityData(batch.position, BlockEntityTypes.SIGN, createSignNbt(batch.checks, batch.fallback)));
            playerPacket(player, new WrapperPlayServerOpenSignEditor(batch.position, true));
            playerPacket(player, new WrapperPlayServerCloseWindow());
        } catch (Throwable t) {
            plugin.getLogger().warning("[ModGuard] Failed to send translation probe to " + player.getName() + ": " + t.getMessage());
            sessions.remove(player.getUniqueId());
            restoreFakeBlock(player, batch.position);
        }
    }

    private void restoreFakeBlock(Player player, Vector3i position) {
        World world = player.getWorld();
        if (!world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            return;
        }
        Location location = new Location(world, position.getX(), position.getY(), position.getZ());
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    private void playerPacket(Player player, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private NBTCompound createSignNbt(List<ProbeCheck> batch, String fallback) {
        NBTCompound root = new NBTCompound();
        root.setTag("front_text", createTextNbt(batch, fallback, true));
        root.setTag("back_text", createTextNbt(batch, fallback, false));
        root.setTag("is_waxed", new NBTByte((byte) 0));
        return root;
    }

    private NBTCompound createTextNbt(List<ProbeCheck> batch, String fallback, boolean front) {
        NBTList<NBTCompound> messages = new NBTList<>(NBTType.COMPOUND);

        for (int i = 0; i < 4; i++) {
            if (i < batch.size()) {
                NBTCompound component = new NBTCompound();
                ProbeCheck check = batch.get(i);
                if (check.mode == ProbeMode.KEYBIND) {
                    component.setTag("keybind", new NBTString(check.key));
                } else {
                    component.setTag("translate", new NBTString(check.key));
                    component.setTag("fallback", new NBTString(fallback));
                }
                messages.addTag(component);
            } else if (front && i == 3) {
                NBTCompound control = new NBTCompound();
                control.setTag("keybind", new NBTString(CTRL_KEYBIND));
                messages.addTag(control);
            } else {
                NBTCompound emptyMessage = new NBTCompound();
                emptyMessage.setTag("", new NBTString(""));
                messages.addTag(emptyMessage);
            }
        }

        NBTCompound text = new NBTCompound();
        text.setTag("messages", messages);
        text.setTag("color", new NBTString("black"));
        text.setTag("has_glowing_text", new NBTByte((byte) 0));
        return text;
    }

    private List<ProbeCheck> loadChecks() {
        ConfigurationSection checksSection = config().getConfigurationSection("translation-probe.checks");
        if (checksSection == null) {
            checksSection = loadBundledChecks();
        }
        if (checksSection == null) {
            return createDefaultChecks();
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
                    section.getString("display-name", id),
                    key,
                    new HashSet<>(expected),
                    section.getString("action", defaultAction),
                    section.getBoolean("require-corroboration-for-kick", true),
                    ProbeMode.parse(section.getString("mode", "translate"))
            ));
        }
        return checks;
    }

    private ConfigurationSection loadBundledChecks() {
        ConfigurationSection bundled = modGuard.bundled().getConfigurationSection("translation-probe.checks");
        if (bundled == null) return null;
        config().set("translation-probe.checks", bundled);
        return config().getConfigurationSection("translation-probe.checks");
    }

    private List<ProbeCheck> createDefaultChecks() {
        List<ProbeCheck> checks = new ArrayList<>();
        checks.add(new ProbeCheck("meteor-client", "Meteor Client", "key.meteor-client.open-gui", Collections.emptySet(), "kick", false, ProbeMode.METEOR));
        checks.add(new ProbeCheck("wurst", "Wurst Client", "key.wurst.zoom", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("liquidbounce-killaura", "LiquidBounce", "liquidbounce.module.killAura.description", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("liquidbounce-aimbot", "LiquidBounce", "liquidbounce.module.aimbot.description", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("liquidbounce-esp", "LiquidBounce", "liquidbounce.module.ESP.description", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("freecam", "Freecam", "key.freecam.toggle", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("xray-fabric", "XRay (Fabric)", "xray.config.toggle", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("chestesp", "ChestESP", "key.chestesp.toggle", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("killaura-fabric", "KillAura (Fabric)", "key.killaura", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("autofish", "AutoFish", "key.autofish.open_gui", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("lumina", "Lumina", "key.lumina.open_click_gui", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("autoswitch", "AutoSwitch", "key.autoswitch.toggle", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("bleachhack", "BleachHack", "bleachhack.module.killaura", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("aristois", "Aristois", "emc.module.killaura.name", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("coffee", "Coffee Client", "coffee.module.killaura.name", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("world-downloader", "World Downloader", "key.wdl.startStop", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("autoclicker-fabric", "AutoClicker (Fabric)", "autoclicker-fabric.hud.holding", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("antiafk", "AntiAFK", "key.antiafk.toggle", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("auto-clicker-mc", "Auto Clicker (p1k0chu)", "key.auto-clicker_.toggle", Collections.emptySet(), "kick", false, ProbeMode.KEYBIND));
        checks.add(new ProbeCheck("ipn-sort-inventory", "Inventory Profiles Next", "inventoryprofiles.config.name.sort_inventory", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-sort-columns", "Inventory Profiles Next", "inventoryprofiles.config.name.sort_inventory_in_columns", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-sort-rows", "Inventory Profiles Next", "inventoryprofiles.config.name.sort_inventory_in_rows", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-move-all", "Inventory Profiles Next", "inventoryprofiles.config.name.move_all_items", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-throw-all", "Inventory Profiles Next", "inventoryprofiles.config.name.throw_all_items", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-config-menu", "Inventory Profiles Next", "inventoryprofiles.config.name.open_config_menu", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-lock-slots", "Inventory Profiles Next", "inventoryprofiles.config.name.enable_lock_slots", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-auto-refill", "Inventory Profiles Next", "inventoryprofiles.config.name.enable_auto_refill", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-profiles", "Inventory Profiles Next", "inventoryprofiles.config.name.enable_profiles", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-tweaks", "Inventory Profiles Next", "inventoryprofiles.gui.config.Tweaks", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-tooltip-sort", "Inventory Profiles Next", "inventoryprofiles.tooltip.sort_button", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-tooltip-settings", "Inventory Profiles Next", "inventoryprofiles.tooltip.settings_open", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("ipn-title", "Inventory Profiles Next", "inventoryprofiles.gui.config.title", Collections.emptySet(), "kick", false, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("libipn-advanced-keys", "libIPN", "libipn.common.gui.config.advanced_keybind_settings", Collections.emptySet(), "alert", true, ProbeMode.TRANSLATE));
        checks.add(new ProbeCheck("libipn-keybind-tips", "libIPN", "libipn.common.gui.config.keybind_settings_tips", Collections.emptySet(), "alert", true, ProbeMode.TRANSLATE));
        return checks;
    }

    private String createFallback() {
        return fallbackPrefix() + Long.toHexString(random.nextLong());
    }

    private String fallbackPrefix() {
        return config().getString("translation-probe.fallback-prefix", "allium_probe_");
    }

    private boolean isEnabled() {
        return config().getBoolean("translation-probe.enabled", config().getBoolean("enabled", false));
    }

    private boolean isDebug() {
        return config().getBoolean("debug", false) || config().getBoolean("translation-probe.debug", false);
    }

    private String getBypassPermission() {
        return config().getString("bypass-permission", "modguard.bypass");
    }

    private ConfigurationSection config() {
        return modGuard.config();
    }

    /** Bedrock players through Geyser have no Java client to probe. Floodgate is optional. */
    private boolean isFloodgatePlayer(Player player) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return false;
        }
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, player.getUniqueId());
        } catch (Throwable unavailable) {
            return player.getUniqueId().version() == 2;
        }
    }

    private record ActiveBatch(Vector3i position, List<ProbeCheck> checks, String fallback) {}

    private static final class ProbeSession {
        private final Vector3i origin;
        private final Deque<ProbeCheck> remaining;
        private final Set<String> allKeys;
        private final Set<Vector3i> sentPositions = ConcurrentHashMap.newKeySet();
        private final AtomicReference<ActiveBatch> active = new AtomicReference<>();
        private int batchIndex;

        private ProbeSession(Vector3i origin, Deque<ProbeCheck> remaining, Set<String> allKeys) {
            this.origin = origin;
            this.remaining = remaining;
            this.allKeys = allKeys;
        }
    }

    static ProbeResult evaluateProbe(ProbeMode mode, String key, Set<String> expected, String resolved, String fallback, boolean exploitPreventer) {
        if (resolved == null || resolved.isEmpty()) {
            return ProbeResult.CLEAN;
        }
        if (isKeyPlusLetter(key, resolved)) {
            return ProbeResult.CLEAN;
        }
        return switch (mode) {
            case KEYBIND -> {
                if (exploitPreventer && resolved.equalsIgnoreCase(key)) {
                    yield ProbeResult.PROTECTED;
                }
                if (resolved.equalsIgnoreCase(key)) {
                    yield ProbeResult.CLEAN;
                }
                if (resolved.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT))) {
                    yield ProbeResult.CLEAN;
                }
                yield ProbeResult.DETECTED;
            }
            case METEOR -> {
                // Echoing the raw key id proves nothing: a vanilla client that
                // fails to apply the fallback renders the key itself. Only a
                // genuinely resolved string is evidence the mod is installed.
                if (resolved.equalsIgnoreCase(key)) {
                    yield ProbeResult.CLEAN;
                }
                if (regionStartsWith(resolved, fallback)) {
                    yield ProbeResult.CLEAN;
                }
                yield ProbeResult.DETECTED;
            }
            default -> {
                if (regionStartsWith(resolved, fallback)) {
                    yield ProbeResult.CLEAN;
                }
                if (resolved.equalsIgnoreCase(key)) {
                    yield ProbeResult.PROTECTED;
                }
                if (!expected.isEmpty()) {
                    String normalized = resolved.toLowerCase(Locale.ROOT);
                    for (String value : expected) {
                        if (normalized.equals(value.toLowerCase(Locale.ROOT))) {
                            yield ProbeResult.DETECTED;
                        }
                    }
                    yield ProbeResult.CLEAN;
                }
                yield ProbeResult.DETECTED;
            }
        };
    }

    static boolean isForeignEcho(String[] lines, List<ProbeMode> modes, String fallback, String prefix) {
        return isForeignEcho(lines, modes, List.of(), Set.of(), fallback, prefix);
    }

    /**
     * True when the echo belongs to some other sign than the one currently awaited:
     * <ul>
     *   <li>a translate line carrying a probe fallback other than ours, or a keybind line echoing a fallback; or</li>
     *   <li>any line echoing a different probe check's raw key (for example a delayed keybind-only batch).</li>
     * </ul>
     */
    static boolean isForeignEcho(
            String[] lines,
            List<ProbeMode> modes,
            List<String> expectedKeys,
            Set<String> allKnownKeys,
            String fallback,
            String prefix
    ) {
        for (int i = 0; i < modes.size() && i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty() && regionStartsWith(line, prefix)) {
                if (modes.get(i) == ProbeMode.KEYBIND || !regionStartsWith(line, fallback)) {
                    return true;
                }
            }
            if (allKnownKeys != null && !allKnownKeys.isEmpty()) {
                String expectedKey = i < expectedKeys.size() ? expectedKeys.get(i) : null;
                String lower = line.toLowerCase(Locale.ROOT);
                if (allKnownKeys.contains(lower) && (expectedKey == null || !expectedKey.equalsIgnoreCase(line))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isKeyPlusLetter(String key, String resolved) {
        int keyLen = key.length();
        return resolved.length() == keyLen + 1
                && resolved.regionMatches(true, 0, key, 0, keyLen)
                && Character.isLetter(resolved.charAt(keyLen));
    }

    private static boolean regionStartsWith(String value, String prefix) {
        return prefix != null && prefix.length() <= value.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static final class ProbeCheck {
        private final String id;
        private final String displayName;
        private final String key;
        private final Set<String> expected;
        private final String action;
        private final boolean requireCorroborationForKick;
        private final ProbeMode mode;

        private ProbeCheck(String id, String displayName, String key, Set<String> expected, String action, boolean requireCorroborationForKick, ProbeMode mode) {
            this.id = id;
            this.displayName = displayName;
            this.key = key;
            this.expected = expected;
            this.action = action;
            this.requireCorroborationForKick = requireCorroborationForKick;
            this.mode = mode;
        }

        private ProbeResult evaluate(String resolved, String fallback, boolean exploitPreventer) {
            return evaluateProbe(mode, key, expected, resolved, fallback, exploitPreventer);
        }
    }
}

enum ProbeMode {
    TRANSLATE, KEYBIND, METEOR;

    static ProbeMode parse(String value) {
        if (value == null) {
            return TRANSLATE;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "KEYBIND" -> KEYBIND;
            case "METEOR"  -> METEOR;
            default        -> TRANSLATE;
        };
    }
}

enum ProbeResult {
    DETECTED, CLEAN, PROTECTED
}
