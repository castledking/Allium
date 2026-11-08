package codes.castled.allium.api;

import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.plugin.Plugin;

/** One addon's connection to Allium, from {@link AlliumApi#connect} or {@link AlliumAddon#allium()}. */
public interface AddonConnection {

    Plugin plugin();

    /** Item definitions owned by this addon, and Allium's item create/identify. */
    AddonItems items();

    /** Command families declared in this addon's {@code plugin.yml}, run through Allium's command specs. */
    AddonCommands commands();

    /**
     * Allium's scheduler boundary, scoped to this addon: its server-thread tasks belong to the addon,
     * and everything still scheduled is cancelled when the addon disables.
     */
    TaskScheduler scheduler();

    /** Allium's text formatting and delivery. */
    AddonText text();
}
