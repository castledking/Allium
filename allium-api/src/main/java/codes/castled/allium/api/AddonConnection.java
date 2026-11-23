package codes.castled.allium.api;

import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** One addon's connection to Allium, from {@link AlliumApi#connect} or {@link AlliumAddon#allium()}. */
public interface AddonConnection {

    Plugin plugin();

    /** Whether a synchronous inventory edit may proceed while Allium deliveries are active. */
    default boolean canEditInventory(Player player) {
        return false;
    }

    /**
     * Whether {@code target} is hidden from {@code viewer}: Bukkit's {@code hidePlayer}, Allium's
     * vanish, or another plugin's {@code vanished} metadata. Addons that reveal where players are,
     * such as a locator bar, must leave hidden pairs out.
     */
    default boolean hiddenFrom(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target)) return false;
        if (!viewer.canSee(target)) return true;
        for (var value : target.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    /** Checks Allium's protection integration before an addon mutates a world cell. */
    default boolean mayModify(Player player, Block block, String action, @Nullable Event cause) {
        return false;
    }

    /** Item definitions owned by this addon, and Allium's item create/identify. */
    AddonItems items();

    /** Command families declared in this addon's {@code plugin.yml}, run through Allium's command specs. */
    AddonCommands commands();

    /**
     * Allium's scheduler boundary, scoped to this addon: its server-thread tasks belong to the addon,
     * and everything still scheduled is cancelled when the addon disables.
     */
    TaskScheduler scheduler();

    /** Addon-owned powertool actions and tools, released when the addon disables. */
    default AddonPowerTools powerTools() {
        throw new UnsupportedOperationException("Allium's powertool API is unavailable");
    }

    /** Allium's text formatting and delivery. */
    AddonText text();

    /** Allium's economy, for charging and paying players; unavailable when its economy module is off. */
    default AddonEconomy economy() {
        return AddonEconomy.UNAVAILABLE;
    }

    /** Whether the host server is Folia (regionized multithreading). */
    default boolean isFolia() {
        return false;
    }

    /** Whether the current thread owns {@code entity} (always the main thread off Folia). */
    default boolean ownsEntity(Entity entity) {
        return scheduler().isServerThread();
    }

    /** Whether the current thread owns the chunk {@code (chunkX, chunkZ)} in {@code world}. */
    default boolean ownsRegion(World world, int chunkX, int chunkZ) {
        return scheduler().isServerThread();
    }

    /** Schedules {@code task} on {@code entity}'s owning region after {@code delayMillis}. */
    default TaskHandle runForEntity(Entity entity, Runnable task, @Nullable Runnable retired, long delayMillis) {
        return delayMillis > 0L ? scheduler().runOnServerLater(task, delayMillis) : scheduler().runOnServer(task);
    }

    /** Repeats {@code step} on {@code entity}'s owning region until it returns false, is cancelled, or the entity retires. */
    default TaskHandle repeatForEntity(Entity entity, BooleanSupplier step, long delayMillis, long periodMillis) {
        return scheduler().repeatOnServer(step, delayMillis, periodMillis);
    }

    /** Schedules {@code task} on the region owning {@code (chunkX, chunkZ)} in {@code world} after {@code delayMillis}. */
    default TaskHandle runForRegion(World world, int chunkX, int chunkZ, Runnable task, long delayMillis) {
        return delayMillis > 0L ? scheduler().runOnServerLater(task, delayMillis) : scheduler().runOnServer(task);
    }

    /** Runs {@code task} inline when the current thread already owns the chunk, otherwise schedules it on that region. */
    default void onRegion(World world, int chunkX, int chunkZ, Runnable task) {
        if (ownsRegion(world, chunkX, chunkZ)) task.run();
        else runForRegion(world, chunkX, chunkZ, task, 0L);
    }

    /** Schedules {@code task} on {@code sender}'s owning region (or global tick for console) after {@code delayMillis}. */
    default TaskHandle runForSender(CommandSender sender, Runnable task, @Nullable Runnable retired, long delayMillis) {
        return delayMillis > 0L ? scheduler().runOnServerLater(task, delayMillis) : scheduler().runOnServer(task);
    }

    /** Runs {@code task} inline when the current thread already owns {@code sender}, otherwise schedules it on that owner. */
    default void onOwner(CommandSender sender, Runnable task) {
        if (scheduler().isServerThread()) task.run();
        else scheduler().runOnServer(task);
    }

    /** Moves {@code entity} to {@code destination} using {@code teleportAsync} on Folia and synchronous {@code teleport} elsewhere. */
    default CompletableFuture<Boolean> teleport(Entity entity, Location destination, TeleportCause cause) {
        return CompletableFuture.completedFuture(entity.teleport(destination, cause));
    }
}
