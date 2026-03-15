package net.survivalfun.allium.listener;

import net.survivalfun.allium.Allium;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public final class ListenerManager {

    private final Allium plugin;
    private final List<Listener> listeners = new ArrayList<>();

    public ListenerManager(Allium plugin) {
        this.plugin = plugin;
    }

    public void register(Listener listener) {
        listeners.add(listener);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    public void registerAll() {
        // Future listeners are registered here as modules add them
    }

    public void unregisterAll() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
    }
}
