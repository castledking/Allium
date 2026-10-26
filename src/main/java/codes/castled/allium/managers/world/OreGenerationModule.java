package codes.castled.allium.managers.world;

import codes.castled.allium.PluginStart;
import codes.castled.allium.commands.PregenCommand;
import codes.castled.allium.listeners.world.OreGenerationListener;
import org.bukkit.Bukkit;

public final class OreGenerationModule implements AutoCloseable {
    private final PluginStart plugin;
    private final OreGenerationManager manager;
    private final PregenCommand command;
    private OreGenerationListener listener;
    private AutoCloseable jade;

    public OreGenerationModule(PluginStart plugin) {
        this.plugin = plugin;
        OrePlacement placement = null;
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine"))
            placement = new codes.castled.allium.managers.world.craftengine.CraftEngineOrePlacement(plugin.getLogger());
        manager = new OreGenerationManager(plugin, placement);
        command = new PregenCommand(plugin, manager);
        startListeners();
    }
    private void startListeners() {
        listener = new OreGenerationListener(plugin, manager);
        if (manager.placement() != null && plugin.getConfig().getBoolean("ore-generation.jade.enabled", true)
            && Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            jade = new codes.castled.allium.packetevents.JadeOreOverlay(plugin, manager);
        }
    }
    public OreGenerationManager manager() { return manager; }
    public PregenCommand command() { return command; }
    public void reload() {
        closeListeners(); manager.reload(); startListeners();
    }
    private void closeListeners() {
        if (listener != null) { listener.close(); listener = null; }
        if (jade != null) {
            try { jade.close(); } catch (Exception e) { plugin.getLogger().warning("[OreGen] Jade cleanup: " + e.getMessage()); }
            jade = null;
        }
    }
    @Override public void close() { closeListeners(); manager.close(); }
}
