package codes.castled.allium.core;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.EssentialsModule;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.module.ToolsModule;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleManager {

    private final PluginStart plugin;
    private final Map<String, AbstractModule> modules = new LinkedHashMap<>();

    public ModuleManager(PluginStart plugin) {
        this.plugin = plugin;
    }

    public void registerModule(AbstractModule module) {
        modules.put(module.getName().toLowerCase(), module);
    }

    public void loadModules() {
        registerModule(new PreferencesModule(plugin));
        registerModule(new MessagingModule(plugin));
        registerModule(new ModerationModule(plugin));
        registerModule(new codes.castled.allium.module.PlayerInfoModule(plugin));
        registerModule(new codes.castled.allium.module.MailModule(plugin));
        registerModule(new codes.castled.allium.module.TeleportModule(plugin));
        registerModule(new codes.castled.allium.module.TravelModule(plugin));
        registerModule(new EconomyModule(plugin));
        registerModule(new EssentialsModule(plugin));
        registerModule(new ToolsModule(plugin));
        registerModule(new ItemsModule(plugin));
        registerModule(new KitsModule(plugin));
        registerModule(new codes.castled.allium.module.SignsModule(plugin));
        registerModule(new codes.castled.allium.module.ChatModule(plugin));
        registerModule(new codes.castled.allium.module.HideModule(plugin));
        registerModule(new codes.castled.allium.module.CreativeModule(plugin));
        registerModule(new codes.castled.allium.module.AltsModule(plugin));
        registerModule(new codes.castled.allium.module.RestoreModule(plugin));
        // After teleport, which it needs.
        registerModule(new codes.castled.allium.module.RtpModule(plugin));
        // After economy and items, whose prices and payments it uses.
        registerModule(new codes.castled.allium.module.WorthModule(plugin));
        registerModule(new codes.castled.allium.module.ExplosionsModule(plugin));
        registerModule(new codes.castled.allium.module.ModGuardModule(plugin));
        for (AbstractModule module : modules.values()) {
            String key = module.getName().toLowerCase();
            boolean enabled = plugin.getConfig().getBoolean("modules." + key, true);
            if (enabled) {
                try {
                    module.enable();
                } catch (Exception e) {
                    // The cause says what to fix ("economy unavailable" alone did not).
                    StringBuilder reason = new StringBuilder(String.valueOf(e.getMessage()));
                    for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                        reason.append(": ").append(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                    }
                    plugin.logger().severe("Failed to enable module [" + module.getName() + "]: " + reason);
                }
            }
        }
    }

    public void unloadModules() {
        for (AbstractModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.disable();
                } catch (Exception e) {
                    plugin.logger().severe("Failed to disable module [" + module.getName() + "]: " + e.getMessage());
                }
            }
        }
    }

    public void reloadModules() {
        unloadModules();
        loadModules();
    }

    @Nullable
    public AbstractModule getModule(String name) {
        return modules.get(name.toLowerCase());
    }

    public Map<String, AbstractModule> getModules() {
        return Collections.unmodifiableMap(modules);
    }
}
