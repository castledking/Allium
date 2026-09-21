package codes.castled.allium.managers.security;

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

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import org.geysermc.floodgate.api.FloodgateApi;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;


import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sign Translation Vulnerability (MC-265322) based client-mod probing.
 *
 * Uses the same trick as CheckHacks: a ghost sign whose lines are translation
 * (or keybind) components is shoved at the client, the sign editor is popped
 * open for a split second, and the resolved text the client echoes back is
 * compared against the fallback/keys to fingerprint installed mods.
 *
 * Multi-probe: up to {@link #CHECKS_PER_SIGN} checks are packed onto one sign,
 * one per line, plus a control line carrying a vanilla keybind (key.forward)
 * used to detect sign-spoofing/exploit-preventer mods.
 */
final class ModGuardTranslationProbe extends PacketListenerAbstract implements Listener {

    private static final int    CHECKS_PER_SIGN = 3;
    private static final String CTRL_KEYBIND    = "key.forward";

    private final PluginStart plugin;
    private final ModGuardManager modGuard;
    private final Map<UUID, ProbeSession> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    ModGuardTranslationProbe(PluginStart plugin, ModGuardManager modGuard) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.modGuard = modGuard;
    }

    void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[ModGuard] Translation probe registered, " + loadChecks().size() + " checks loaded.");
    }

    void unregister() {
        // A reload builds a fresh probe; the old one must stop completely.
        // Leaving its join listener alive made it keep sending its own ghost
        // signs, whose echoes the new probe then scored as detections.
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        HandlerList.unregisterAll(this);
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
        if (isFloodgatePlayer(player)) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Skipping translation probe for Floodgate player: " + player.getName());
            }
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
        if (session == null || session.current.isEmpty()) {
            return;
        }

        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        Vector3i packetPosition = packet.getBlockPosition();
        if (!session.position.equals(packetPosition)) {
            return;
        }

        event.setCancelled(true);

        String[] lines = packet.getTextLines();
        String ctrlResp = lines.length > 3 && lines[3] != null ? lines[3].strip() : "";
        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);

        List<ProbeCheck> batch = new ArrayList<>(session.current);
        List<ProbeMode> modes = new ArrayList<>(batch.size());
        for (ProbeCheck check : batch) {
            modes.add(check.mode);
        }
        if (isForeignEcho(lines, modes, session.fallback, fallbackPrefix())) {
            if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Ignoring stale translation probe echo for " + uuid
                        + ": " + Arrays.toString(lines) + " (expected fallback " + session.fallback + ")");
            }
            return;
        }
        session.current = Collections.emptyList();

        Map<ProbeCheck, String> hits = new HashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            String resolved = i < lines.length && lines[i] != null ? lines[i].strip() : "";
            if (batch.get(i).evaluate(resolved, session.fallback, exploitPreventer) == ProbeResult.DETECTED) {
                hits.put(batch.get(i), resolved);
            } else if (isDebug()) {
                plugin.getLogger().info("[ModGuard] Translation probe miss for " + uuid
                        + ": " + batch.get(i).id + " key=" + batch.get(i).key + " resolved=\"" + resolved + "\"");
            }
        }
        final Map<ProbeCheck, String> finalHits = hits;

        SchedulerAdapter.run(() -> {
            if (closed) {
                return;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                sessions.remove(uuid);
                return;
            }

            restoreFakeBlock(player, session);

            for (Map.Entry<ProbeCheck, String> hit : finalHits.entrySet()) {
                if (!player.isOnline()) {
                    break;
                }
                ProbeCheck check = hit.getKey();
                modGuard.handleTranslationProbeHit(player, check.displayName, check.key,
                        hit.getValue(), session.fallback, check.action, check.requireCorroborationForKick);
            }

            sendNextBatch(player, session);
        });
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
        Vector3i position = new Vector3i(base.getBlockX(), Math.max(minY, base.getBlockY() - 30), base.getBlockZ());
        ProbeSession session = new ProbeSession(position, new ArrayDeque<>(checks));
        sessions.put(player.getUniqueId(), session);
        sendNextBatch(player, session);
    }

    private void sendNextBatch(Player player, ProbeSession session) {
        if (closed || !player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }

        List<ProbeCheck> batch = new ArrayList<>();
        while (batch.size() < CHECKS_PER_SIGN && !session.remaining.isEmpty()) {
            batch.add(session.remaining.pollFirst());
        }
        if (batch.isEmpty()) {
            sessions.remove(player.getUniqueId());
            restoreFakeBlock(player, session);
            return;
        }

        // Fresh fallback per sign, so a late echo of an earlier sign can be told apart.
        session.fallback = createFallback();
        session.current = batch;
        sendProbePackets(player, session);

        long timeoutTicks = config().getLong("translation-probe.timeout-ticks", 80L);
        SchedulerAdapter.runLater(() -> {
            if (closed) {
                return;
            }
            ProbeSession active = sessions.get(player.getUniqueId());
            if (active == session && active.current == batch) {
                active.current = Collections.emptyList();
                restoreFakeBlock(player, active);
                sendNextBatch(player, active);
            }
        }, timeoutTicks);
    }

    private void sendProbePackets(Player player, ProbeSession session) {
        try {
            WrappedBlockState signState = WrappedBlockState.getDefaultState(StateTypes.OAK_SIGN);
            playerPacket(player, new WrapperPlayServerBlockChange(session.position, signState));
            playerPacket(player, new WrapperPlayServerBlockEntityData(session.position, BlockEntityTypes.SIGN, createSignNbt(session.current, session.fallback)));
            playerPacket(player, new WrapperPlayServerOpenSignEditor(session.position, true));
            playerPacket(player, new WrapperPlayServerCloseWindow());
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
        try (var stream = plugin.getResource("modguard/config.yml")) {
            if (stream == null) return null;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection section = bundled.getConfigurationSection("translation-probe.checks");
            if (section == null) return null;
            config().set("translation-probe.checks", section);
            return config().getConfigurationSection("translation-probe.checks");
        } catch (Exception e) {
            plugin.getLogger().warning("[ModGuard] Failed to load bundled probe checks: " + e.getMessage());
            return null;
        }
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

    private org.bukkit.configuration.file.FileConfiguration config() {
        return modGuard.getModGuardConfig();
    }

    private boolean isFloodgatePlayer(Player player) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (Throwable t) {
            return player.getUniqueId().version() == 2;
        }
    }

    private static final class ProbeSession {
        private final Vector3i position;
        private final Deque<ProbeCheck> remaining;
        private String fallback = "";
        private List<ProbeCheck> current;

        private ProbeSession(Vector3i position, Deque<ProbeCheck> remaining) {
            this.position = position;
            this.remaining = remaining;
            this.current = Collections.emptyList();
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

    /**
     * True when the echo belongs to some other sign than the one currently
     * awaited: a translate line carrying a probe fallback other than ours,
     * or a keybind line (which never carries a fallback) echoing one. Such an
     * echo says nothing about the current batch and must not be scored.
     */
    static boolean isForeignEcho(String[] lines, List<ProbeMode> modes, String fallback, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < modes.size() && i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].strip();
            if (!regionStartsWith(line, prefix)) {
                continue;
            }
            if (modes.get(i) == ProbeMode.KEYBIND || !regionStartsWith(line, fallback)) {
                return true;
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