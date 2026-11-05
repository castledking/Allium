package codes.castled.allium.listener;

import codes.castled.allium.PluginStart;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public final class ListenerManager {

    private final PluginStart plugin;
    private final List<Listener> listeners = new ArrayList<>();

    public ListenerManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void register(Listener listener) {
        listeners.add(listener);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    public void registerAll() {
        register(new GodModeListener());
    }

    public void unregisterAll() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
    }
}
