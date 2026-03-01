package net.survivalfun.core.packetevents;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.chat.ChatMessageManager;
import net.survivalfun.core.managers.core.PartyManager;
import net.survivalfun.core.managers.core.VanishManager;
import org.bukkit.Bukkit;

/**
 * Loads PacketEvents-dependent implementations via reflection when the PacketEvents
 * plugin is available. Avoids hard dependency so Allium can run without PacketEvents
 * (with reduced functionality) and keeps the JAR smaller.
 */
public final class PacketEventsLoader {

    private PacketEventsLoader() {}

    /**
     * Check if PacketEvents plugin and API are available.
     */
    public static boolean isPacketEventsAvailable() {
        org.bukkit.plugin.Plugin pe = Bukkit.getPluginManager().getPlugin("packetevents");
        if (pe == null) {
            pe = Bukkit.getPluginManager().getPlugin("PacketEvents");
        }
        if (pe == null || !pe.isEnabled()) {
            return false;
        }
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Create ChatPacketTracker implementation. Returns PacketChatTrackerImpl when
     * PacketEvents is available, otherwise ChatPacketTrackerNoOp.
     */
    public static ChatPacketTracker createChatPacketTracker(PluginStart plugin, ChatMessageManager chatMessageManager) {
        if (!isPacketEventsAvailable()) {
            return new ChatPacketTrackerNoOp();
        }
        try {
            Class<?> clazz = Class.forName("net.survivalfun.core.packetevents.impl.PacketChatTrackerImpl");
            return (ChatPacketTracker) clazz
                .getConstructor(PluginStart.class, ChatMessageManager.class)
                .newInstance(plugin, chatMessageManager);
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to load PacketChatTrackerImpl: " + e.getMessage());
            return new ChatPacketTrackerNoOp();
        }
    }

    /**
     * Create TabListManager implementation. Returns TabListManagerPacketEventsImpl when
     * PacketEvents is available, otherwise TabListManagerNoOp.
     */
    public static TabListManager createTabListManager(PluginStart plugin, PartyManager partyManager, VanishManager vanishManager) {
        if (!isPacketEventsAvailable()) {
            return new TabListManagerNoOp();
        }
        try {
            Class<?> clazz = Class.forName("net.survivalfun.core.packetevents.impl.TabListManagerPacketEventsImpl");
            return (TabListManager) clazz
                .getConstructor(PluginStart.class, PartyManager.class, VanishManager.class)
                .newInstance(plugin, partyManager, vanishManager);
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to load TabListManagerPacketEventsImpl: " + e.getMessage());
            return new TabListManagerNoOp();
        }
    }
}
