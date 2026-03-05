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
        org.bukkit.plugin.Plugin pe = Bukkit.getPluginManager().getPlugin("PacketEvents");
        if (pe == null) {
            pe = Bukkit.getPluginManager().getPlugin("packetevents");
        }
        if (pe == null) {
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p != null && p.isEnabled() && p.getName().toLowerCase().contains("packetevents")) {
                    pe = p;
                    break;
                }
            }
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
     * Diagnostic: log why PacketEvents is or isn't available. Call when debug-mode is on.
     */
    public static void logPacketEventsStatus(java.util.logging.Logger logger) {
        org.bukkit.plugin.Plugin pe = Bukkit.getPluginManager().getPlugin("PacketEvents");
        if (pe == null) pe = Bukkit.getPluginManager().getPlugin("packetevents");
        if (pe == null) {
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p != null && p.getName().toLowerCase().contains("packetevents")) {
                    pe = p;
                    break;
                }
            }
        }
        if (pe == null) {
            logger.info("[delmsg] PacketEvents: plugin not found (no plugin named PacketEvents/packetevents)");
            return;
        }
        if (!pe.isEnabled()) {
            logger.info("[delmsg] PacketEvents: plugin found (" + pe.getName() + ") but disabled");
            return;
        }
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            logger.info("[delmsg] PacketEvents: OK (plugin=" + pe.getName() + ", API loaded)");
        } catch (ClassNotFoundException e) {
            logger.info("[delmsg] PacketEvents: plugin found (" + pe.getName() + ") but API class not found - ensure packetevents-spigot JAR is in plugins/");
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
