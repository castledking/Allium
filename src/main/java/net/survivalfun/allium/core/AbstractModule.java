package net.survivalfun.allium.core;

import net.survivalfun.allium.Allium;

public abstract class AbstractModule {

    protected final Allium plugin;
    private boolean enabled;

    protected AbstractModule(Allium plugin) {
        this.plugin = plugin;
    }

    public abstract String getName();

    public abstract void onEnable();

    public abstract void onDisable();

    public void enable() {
        if (enabled) return;
        onEnable();
        enabled = true;
        plugin.logger().info("Module [" + getName() + "] enabled.");
    }

    public void disable() {
        if (!enabled) return;
        onDisable();
        enabled = false;
        plugin.logger().info("Module [" + getName() + "] disabled.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        if (enabled) {
            disable();
            enable();
        }
    }
}
