package net.survivalfun.core.packetevents.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Party;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.managers.core.VanishManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.packetevents.TabListManager;
import net.survivalfun.core.util.SchedulerAdapter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * PacketEvents-based tab list manager. Loaded only when PacketEvents plugin is available.
 * Cancels removal packets only for hidden players that Allium intentionally keeps in tab,
 * and re-adds those players on demand after hidePlayer strips them from the tab list.
 */
public class TabListManagerPacketEventsImpl extends PacketListenerAbstract implements TabListManager, Listener {

    private static final long TAB_DEBUG_THROTTLE_MS = 5000L;

    private final PluginStart plugin;
    private final PartyManager partyManager;
    private final VanishManager vanishManager;
    private final int minTabListSize;
    private final boolean tabPluginPresent;
    private final boolean placeholderApiPresent;
    private final Map<String, Long> tabDebugThrottle = new ConcurrentHashMap<>();
    private final File tabGroupsFile;
    private volatile FileConfiguration tabGroupsConfig;
    private volatile long tabGroupsLastModified;
    private SchedulerAdapter.TaskHandle tabListRefreshTask;

    public TabListManagerPacketEventsImpl(PluginStart plugin, PartyManager partyManager, VanishManager vanishManager) {
        super(PacketListenerPriority.LOWEST); // Run FIRST - cancel server's removal before TAB/others process it
        this.plugin = plugin;
        this.partyManager = partyManager;
        this.vanishManager = vanishManager;
        this.minTabListSize = plugin.getConfig().getInt("party-manager.min-tablist-size", 0);
        this.tabPluginPresent = plugin.getServer().getPluginManager().isPluginEnabled("TAB");
        this.placeholderApiPresent = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        Plugin tabPlugin = plugin.getServer().getPluginManager().getPlugin("TAB");
        this.tabGroupsFile = tabPlugin != null ? new File(tabPlugin.getDataFolder(), "config/groups.yml") : null;

        // Brief delay for PacketEvents to be ready
        SchedulerAdapter.runLater(() -> {
            try {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            } catch (Throwable e) {
                Text.sendDebugLog(WARN, "Failed to register TabListManager: " + e.getMessage());
            }
        }, 2L); // Start quickly - LOWEST runs first, we cancel removals before TAB processes
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            Player viewer = (Player) event.getPlayer();
            if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
                WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(event);

                for (java.util.UUID targetUUID : packet.getProfileIds()) {
                    Player targetPlayer = plugin.getServer().getPlayer(targetUUID);
                    if (targetPlayer == null || !targetPlayer.isOnline()) continue;
                    if (shouldBeVisibleInTabList(viewer, targetPlayer)) {
                        event.setCancelled(true);
                        break;
                    }
                }
                return;
            }

            if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
                WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
                List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(packet.getEntries());
                boolean touchedListed = false;

                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : entries) {
                    Player targetPlayer = plugin.getServer().getPlayer(entry.getProfileId());
                    if (targetPlayer == null || !targetPlayer.isOnline()) {
                        continue;
                    }
                    if (!shouldBeVisibleInTabList(viewer, targetPlayer)) {
                        continue;
                    }

                    // Only ensure player is listed - NEVER modify display name
                    // TAB plugin handles all formatting via groups.yml
                    if (!entry.isListed()) {
                        entry.setListed(true);
                        touchedListed = true;
                    }
                }

                if (touchedListed) {
                    EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.copyOf(packet.getActions());
                    if (!actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
                        actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
                    }
                    packet.setActions(actions);
                    packet.setEntries(entries);
                }
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "TabListManager packet interception failed for " + event.getPacketType() + ": " + t.getMessage());
        }
    }

    @Override
    public boolean shouldBeVisibleInTabList(Player viewer, Player target) {
        if (viewer.equals(target)) {
            return false;
        }
        if (vanishManager.isVanished(target)) {
            return false;
        }
        if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR
                && !viewer.hasPermission("allium.gamemode.spectator")) {
            return true;
        }
        return !partyManager.shouldBeVisible(viewer, target);
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo createPlayerInfoEntry(Player viewer, Player player) {
        UserProfile gameProfile = new UserProfile(player.getUniqueId(), player.getName());
        int latency = 0;
        GameMode gameMode = convertBukkitGameModeToPacketEvents(player.getGameMode());
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            gameProfile, true, latency, gameMode,
            resolveDisplayName(viewer, player), null
        );
    }

    private Component resolveDisplayName(Player viewer, Player target) {
        Component fallback = target.playerListName();
        if (fallback == null) {
            fallback = target.displayName();
        }
        if (fallback == null) {
            fallback = Component.text(target.getName());
        }
        if (!tabPluginPresent) {
            return fallback;
        }

        Component tabDisplayName = resolveTabDisplayName(viewer, target);
        return tabDisplayName != null ? tabDisplayName : fallback;
    }

    private Component resolveTabDisplayName(Player viewer, Player target) {
        Component groupsDisplayName = resolveTabDisplayNameFromGroupsConfig(target);
        if (groupsDisplayName != null) {
            return groupsDisplayName;
        }

        Component placeholderDisplayName = resolveTabDisplayNameFromPlaceholders(target);
        if (placeholderDisplayName != null) {
            return placeholderDisplayName;
        }

        Component internalDisplayName = resolveTabDisplayNameFromInternalApi(viewer, target);
        if (internalDisplayName != null) {
            return internalDisplayName;
        }

        return resolveTabDisplayNameFromApi(target);
    }

    private Component resolveTabDisplayNameFromGroupsConfig(Player target) {
        FileConfiguration config = getTabGroupsConfig();
        if (config == null) {
            return null;
        }

        try {
            String group = resolveTabGroup(target, null);
            String world = target.getWorld().getName();

            String prefix = firstConfiguredValue(config, world, group, "tabprefix");
            String name = firstConfiguredValue(config, world, group, "customtabname");
            String suffix = firstConfiguredValue(config, world, group, "tabsuffix");

            if (name == null || name.isBlank()) {
                name = target.getName();
            }

            String combined = applyPlaceholders(target, safe(prefix) + name + safe(suffix));
            if (combined.isBlank()) {
                return null;
            }

            return Text.colorize(combined);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Component resolveTabDisplayNameFromInternalApi(Player viewer, Player target) {
        try {
            Class<?> tabClass = Class.forName("me.neznamy.tab.shared.TAB");
            Object tab = tabClass.getMethod("getInstance").invoke(null);
            if (tab == null) {
                return null;
            }

            Method getPlayer = tabClass.getMethod("getPlayer", java.util.UUID.class);
            Object viewerTabPlayer = getPlayer.invoke(tab, viewer.getUniqueId());
            Object targetTabPlayer = getPlayer.invoke(tab, target.getUniqueId());
            if (viewerTabPlayer == null || targetTabPlayer == null) {
                return null;
            }

            Object tabListFormatManager = tabClass.getMethod("getTabListFormatManager").invoke(tab);
            if (tabListFormatManager == null) {
                return null;
            }

            Method getTabFormat = null;
            for (Method method : tabListFormatManager.getClass().getMethods()) {
                if (method.getName().equals("getTabFormat") && method.getParameterCount() == 2) {
                    getTabFormat = method;
                    break;
                }
            }
            if (getTabFormat == null) {
                return null;
            }

            Object tabComponent = getTabFormat.invoke(tabListFormatManager, targetTabPlayer, viewerTabPlayer);
            if (tabComponent == null) {
                return null;
            }

            Object adventureComponent = tabComponent.getClass().getMethod("toAdventure").invoke(tabComponent);
            return adventureComponent instanceof Component component ? component : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Component resolveTabDisplayNameFromPlaceholders(Player target) {
        if (!placeholderApiPresent) {
            return null;
        }

        try {
            String combined = PlaceholderAPI.setPlaceholders(
                target,
                "%tab_tabprefix%%tab_customtabname%%tab_tabsuffix%"
            );
            if (combined == null || combined.isBlank()) {
                return null;
            }

            return Text.colorize(combined);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Component resolveTabDisplayNameFromApi(Player target) {
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
            if (tabApi == null) {
                return null;
            }

            Method getPlayer = tabApiClass.getMethod("getPlayer", java.util.UUID.class);
            Object targetTabPlayer = getPlayer.invoke(tabApi, target.getUniqueId());
            if (targetTabPlayer == null) {
                return null;
            }

            Object tabListFormatManager = tabApiClass.getMethod("getTabListFormatManager").invoke(tabApi);
            if (tabListFormatManager == null) {
                return null;
            }

            Class<?> tabPlayerApiClass = Class.forName("me.neznamy.tab.api.TabPlayer");
            String prefix = (String) tabListFormatManager.getClass()
                    .getMethod("getOriginalReplacedPrefix", tabPlayerApiClass)
                    .invoke(tabListFormatManager, targetTabPlayer);
            String name = (String) tabListFormatManager.getClass()
                    .getMethod("getOriginalReplacedName", tabPlayerApiClass)
                    .invoke(tabListFormatManager, targetTabPlayer);
            String suffix = (String) tabListFormatManager.getClass()
                    .getMethod("getOriginalReplacedSuffix", tabPlayerApiClass)
                    .invoke(tabListFormatManager, targetTabPlayer);

            String combined = (prefix == null ? "" : prefix)
                    + (name == null ? "" : name)
                    + (suffix == null ? "" : suffix);

            if (combined.isBlank()) {
                return null;
            }

            return Text.colorize(combined);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void updateTablistAfterPartyEvent(Player player, boolean hideNonParty) {
        // No-op: TAB plugin handles all tab list management.
        // Allium only intercepts remove packets to prevent removing party members.
    }

    @Override
    public void sendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        // No-op: TAB plugin handles all tab list additions
    }

    @Override
    public void forceSendTabListAddPacket(Player targetPlayer, List<Player> viewers) {
        // No-op: TAB plugin handles all tab list additions
    }

    private void sendTabListAddPacketInternal(Player targetPlayer, List<Player> viewers, boolean respectVisibilityRules) {
        // No-op: TAB plugin handles all tab list additions
    }

    private void sendPlayerInfoUpdatePacket(Player viewer, WrapperPlayServerPlayerInfoUpdate packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
    }

    private boolean addEntryViaTab(Player viewer, Player target) {
        try {
            Class<?> tabClass = Class.forName("me.neznamy.tab.shared.TAB");
            Object tab = tabClass.getMethod("getInstance").invoke(null);
            if (tab == null) {
                logTabDebug(viewer, target, "TAB add skipped: TAB instance was null");
                return false;
            }

            Method getPlayer = tabClass.getMethod("getPlayer", java.util.UUID.class);
            Object viewerTabPlayer = getPlayer.invoke(tab, viewer.getUniqueId());
            Object targetTabPlayer = getPlayer.invoke(tab, target.getUniqueId());
            if (viewerTabPlayer == null || targetTabPlayer == null) {
                logTabDebug(viewer, target, "TAB add skipped: viewerTabPlayer=" + (viewerTabPlayer != null) + ", targetTabPlayer=" + (targetTabPlayer != null));
                return false;
            }

            Object viewerTabList = viewerTabPlayer.getClass().getMethod("getTabList").invoke(viewerTabPlayer);
            Object targetTabList = targetTabPlayer.getClass().getMethod("getTabList").invoke(targetTabPlayer);
            Object tablistId = targetTabPlayer.getClass().getMethod("getTablistId").invoke(targetTabPlayer);
            String nickname = (String) targetTabPlayer.getClass().getMethod("getNickname").invoke(targetTabPlayer);
            Object skin = targetTabList.getClass().getMethod("getSkin").invoke(targetTabList);
            int latency = ((Number) targetTabPlayer.getClass().getMethod("getPing").invoke(targetTabPlayer)).intValue();
            int gamemode = ((Number) targetTabPlayer.getClass().getMethod("getGamemode").invoke(targetTabPlayer)).intValue();
            Object tabComponent = resolveTabComponent(tab, viewerTabPlayer, targetTabPlayer);
            boolean groupsFallback = false;
            boolean placeholderFallback = false;
            if (tabComponent == null) {
                tabComponent = createTabComponentFromGroupsConfig(target, targetTabPlayer);
                groupsFallback = tabComponent != null;
            }
            if (tabComponent == null) {
                tabComponent = createTabComponentFromPlaceholders(target);
                placeholderFallback = true;
            }

            Class<?> entryClass = Class.forName("me.neznamy.tab.shared.platform.TabList$Entry");
            Class<?> skinClass = Class.forName("me.neznamy.tab.shared.platform.TabList$Skin");
            Class<?> tabComponentClass = Class.forName("me.neznamy.tab.shared.chat.component.TabComponent");
            Object entry = entryClass.getConstructor(
                java.util.UUID.class,
                String.class,
                skinClass,
                boolean.class,
                int.class,
                int.class,
                tabComponentClass,
                int.class,
                boolean.class
            ).newInstance(
                tablistId,
                nickname,
                skin,
                true,
                latency,
                gamemode,
                tabComponent,
                0,
                true
            );

            Method addEntry = viewerTabList.getClass().getMethod("addEntry", entryClass);
            addEntry.invoke(viewerTabList, entry);
            if (tabComponent != null) {
                Method updateDisplayName = viewerTabList.getClass().getMethod("updateDisplayName", targetTabPlayer.getClass().getSuperclass(), tabComponentClass);
                updateDisplayName.invoke(viewerTabList, targetTabPlayer, tabComponent);
            }
            logTabDebug(
                viewer,
                target,
                "TAB add success: tablistId=" + tablistId
                    + ", nickname=" + nickname
                    + ", latency=" + latency
                    + ", gamemode=" + gamemode
                    + ", tabComponent=" + (tabComponent != null ? tabComponent.getClass().getSimpleName() : "null")
                    + ", groupsFallback=" + groupsFallback
                    + ", placeholderFallback=" + placeholderFallback
                    + ", papiCombined=" + safeResolveTabPlaceholders(target)
            );
            return true;
        } catch (Throwable t) {
            logTabDebug(viewer, target, "TAB add failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private Object resolveTabComponent(Object tab, Object viewerTabPlayer, Object targetTabPlayer) throws ReflectiveOperationException {
        Object tabListFormatManager = tab.getClass().getMethod("getTabListFormatManager").invoke(tab);
        if (tabListFormatManager == null) {
            return null;
        }

        for (Method method : tabListFormatManager.getClass().getMethods()) {
            if (!method.getName().equals("getTabFormat") || method.getParameterCount() != 2) {
                continue;
            }
            return method.invoke(tabListFormatManager, targetTabPlayer, viewerTabPlayer);
        }

        return null;
    }

    private Object createTabComponentFromGroupsConfig(Player target, Object targetTabPlayer) {
        FileConfiguration config = getTabGroupsConfig();
        if (config == null) {
            return null;
        }

        try {
            String group = resolveTabGroup(target, targetTabPlayer);
            String world = target.getWorld().getName();

            String prefix = firstConfiguredValue(config, world, group, "tabprefix");
            String name = firstConfiguredValue(config, world, group, "customtabname");
            String suffix = firstConfiguredValue(config, world, group, "tabsuffix");

            if (name == null || name.isBlank()) {
                name = target.getName();
            }

            String combined = applyPlaceholders(target, safe(prefix) + name + safe(suffix));
            if (combined.isBlank()) {
                return null;
            }

            Class<?> tabComponentClass = Class.forName("me.neznamy.tab.shared.chat.component.TabComponent");
            Method fromColoredText = tabComponentClass.getMethod("fromColoredText", String.class);
            return fromColoredText.invoke(null, combined);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object createTabComponentFromPlaceholders(Player target) {
        if (!placeholderApiPresent) {
            return null;
        }

        try {
            String combined = PlaceholderAPI.setPlaceholders(
                target,
                "%tab_tabprefix%%tab_customtabname%%tab_tabsuffix%"
            );
            if (combined == null || combined.isBlank()) {
                return null;
            }

            Class<?> tabComponentClass = Class.forName("me.neznamy.tab.shared.chat.component.TabComponent");
            Method fromColoredText = tabComponentClass.getMethod("fromColoredText", String.class);
            return fromColoredText.invoke(null, combined);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private FileConfiguration getTabGroupsConfig() {
        if (tabGroupsFile == null || !tabGroupsFile.isFile()) {
            return null;
        }

        long lastModified = tabGroupsFile.lastModified();
        FileConfiguration current = tabGroupsConfig;
        if (current != null && tabGroupsLastModified == lastModified) {
            return current;
        }

        synchronized (this) {
            if (tabGroupsConfig != null && tabGroupsLastModified == lastModified) {
                return tabGroupsConfig;
            }
            tabGroupsConfig = YamlConfiguration.loadConfiguration(tabGroupsFile);
            tabGroupsLastModified = lastModified;
            return tabGroupsConfig;
        }
    }

    private String resolveTabGroup(Player target, Object targetTabPlayer) {
        try {
            Object group = targetTabPlayer.getClass().getMethod("getGroup").invoke(targetTabPlayer);
            if (group instanceof String groupName && !groupName.isBlank()) {
                return groupName;
            }
        } catch (Throwable ignored) {
        }

        if (plugin.getVaultPermission() != null) {
            try {
                String primaryGroup = plugin.getVaultPermission().getPrimaryGroup((String) null, target.getName());
                if (primaryGroup != null && !primaryGroup.isBlank()) {
                    return primaryGroup;
                }
            } catch (Throwable ignored) {
            }
        }

        return "default";
    }

    private String firstConfiguredValue(FileConfiguration config, String world, String group, String key) {
        String worldValue = getConfigValue(config, "per-world." + world + "." + group + "." + key);
        if (worldValue != null) {
            return worldValue;
        }

        String groupValue = getConfigValue(config, group + "." + key);
        if (groupValue != null) {
            return groupValue;
        }

        return getConfigValue(config, "_DEFAULT_." + key);
    }

    private String getConfigValue(FileConfiguration config, String path) {
        if (config.contains(path)) {
            return config.getString(path);
        }

        String[] parts = path.split("\\.");
        ConfigurationSection section = config;
        for (int i = 0; i < parts.length - 1; i++) {
            String actualKey = findSectionKey(section, parts[i]);
            if (actualKey == null) {
                return null;
            }
            section = section.getConfigurationSection(actualKey);
            if (section == null) {
                return null;
            }
        }

        String leaf = findSectionKey(section, parts[parts.length - 1]);
        return leaf != null ? section.getString(leaf) : null;
    }

    private String findSectionKey(ConfigurationSection section, String wanted) {
        if (section == null) {
            return null;
        }
        if (section.contains(wanted)) {
            return wanted;
        }
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase(wanted)) {
                return key;
            }
        }
        return null;
    }

    private String applyPlaceholders(Player target, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!placeholderApiPresent) {
            return value;
        }
        try {
            return PlaceholderAPI.setPlaceholders(target, value);
        } catch (Throwable ignored) {
            return value;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeResolveTabPlaceholders(Player target) {
        if (!placeholderApiPresent) {
            return "<papi-disabled>";
        }

        try {
            String combined = PlaceholderAPI.setPlaceholders(
                target,
                "%tab_tabprefix%%tab_customtabname%%tab_tabsuffix%"
            );
            return combined == null || combined.isBlank() ? "<blank>" : combined;
        } catch (Throwable t) {
            return "<error:" + t.getClass().getSimpleName() + ">";
        }
    }

    private void logTabDebug(Player viewer, Player target, String message) {
        if (!plugin.getConfig().getBoolean("debug-mode", false)) {
            return;
        }

        String key = viewer.getUniqueId() + "->" + target.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = tabDebugThrottle.get(key);
        if (previous != null && now - previous < TAB_DEBUG_THROTTLE_MS) {
            return;
        }
        tabDebugThrottle.put(key, now);

        Text.sendDebugLog(INFO, "[TABLIST] viewer=" + viewer.getName() + " target=" + target.getName() + " " + message, true);
    }

    @Override
    public void sendTabListRemovePacket(Player targetPlayer, List<Player> viewers) {
        try {
            List<Player> playersToRemove = new ArrayList<>();
            for (Player viewer : viewers) {
                if (!shouldBeVisibleInTabList(viewer, targetPlayer)) {
                    playersToRemove.add(viewer);
                }
            }
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(targetPlayer.getUniqueId());
            for (Player viewer : playersToRemove) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packet for " + targetPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        // No-op: TAB plugin handles all tab list additions
    }

    @Override
    public void forceSendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers) {
        // No-op: TAB plugin handles all tab list additions
    }

    private void sendTabListAddPacketForMultiplePlayers(List<Player> targetPlayers, List<Player> viewers, boolean respectVisibilityRules) {
        // No-op: TAB plugin handles all tab list additions
    }

    @Override
    public void ensureAllPlayersVisibleInTabLists() {
        // Intentionally no-op. TAB or other tab plugins own normal entries;
        // Allium only re-adds players when it hides their entity but still wants them in tab.
    }

    @Override
    public void sendTabListRemoveForLeavingPlayer(Player leavingPlayer) {
        try {
            List<Player> allOtherPlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            allOtherPlayers.remove(leavingPlayer);
            WrapperPlayServerPlayerInfoRemove packet = new WrapperPlayServerPlayerInfoRemove(leavingPlayer.getUniqueId());
            for (Player viewer : allOtherPlayers) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to send tablist remove packets for leaving player " + leavingPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void sendInitialTabListState(Player joinedPlayer) {
        // No-op: TAB plugin handles all tab list initialization and formatting.
        // Allium only intercepts remove packets to prevent removing party members.
    }

    private GameMode convertBukkitGameModeToPacketEvents(org.bukkit.GameMode bukkitGameMode) {
        return switch (bukkitGameMode) {
            case SURVIVAL -> GameMode.SURVIVAL;
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SPECTATOR -> GameMode.SPECTATOR;
        };
    }

    @Override
    public void shutdown() {
        try {
            if (tabListRefreshTask != null) {
                tabListRefreshTask.cancel();
                tabListRefreshTask = null;
            }
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Error unregistering tablist manager listener: " + e.getMessage());
        }
    }
}
