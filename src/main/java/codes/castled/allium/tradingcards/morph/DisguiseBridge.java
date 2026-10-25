package codes.castled.allium.tradingcards.morph;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Applies cosmetic morphs through LibsDisguises' console commands.
 *
 * <p>No LibsDisguises API classes are loaded. Only morphs requested by Allium
 * are tracked here; their duration and gameplay rules belong to {@link MorphService}.
 * Calls run synchronously from the morph command and lifecycle checks, while
 * LibsDisguises handles scheduling its own entity changes.
 */
public final class DisguiseBridge {

    private final Server server;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, EntityType> active = new ConcurrentHashMap<>();
    private boolean ready;

    public DisguiseBridge(Plugin plugin, Logger logger) {
        this.server = plugin.getServer();
        this.logger = logger;
    }

    /** Checks command availability without depending on the disguise API. */
    public boolean initialise() {
        ready = DisguiseBridgeLookup.commandsAvailable(server);
        return ready;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Dispatches a disguise command and explicitly enables its self view.
     * A successful dispatch means the command was handled; LibsDisguises reports
     * any internal rejection to the console.
     */
    public boolean disguise(Player player, EntityType type, boolean mobsTargetUs) {
        if (!ready || type == null) {
            return false;
        }
        if (!dispatch("disguiseplayer " + player.getName() + " "
                + type.name().toLowerCase(Locale.ROOT)
                + " setSelfDisguiseVisible true")) {
            return false;
        }
        active.put(player.getUniqueId(), type);
        return true;
    }

    /** Removes a player's disguise through the console. */
    public boolean undisguise(Player player) {
        if (!ready || !dispatch("undisguiseplayer " + player.getName())) {
            return false;
        }
        active.remove(player.getUniqueId());
        return true;
    }

    private boolean dispatch(String command) {
        try {
            if (server.dispatchCommand(server.getConsoleSender(), command)) {
                return true;
            }
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Morph command was not handled: " + command);
        } catch (RuntimeException e) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not dispatch morph command " + command + ": " + e);
        }
        return false;
    }

    /** Toggles only morphs applied by Allium. */
    public MorphState toggle(Player player, EntityType type, boolean mobsTargetUs) {
        if (isDisguised(player)) {
            return undisguise(player) ? MorphState.REMOVED : MorphState.FAILED;
        }
        return disguise(player, type, mobsTargetUs) ? MorphState.APPLIED : MorphState.FAILED;
    }

    public boolean isDisguised(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /** The form requested by Allium, without querying another plugin's API. */
    public EntityType disguisedAs(Player player) {
        return active.get(player.getUniqueId());
    }

    public int morphedCount() {
        return active.size();
    }

    public void forget(UUID player) {
        active.remove(player);
    }

    public enum MorphState { APPLIED, REMOVED, FAILED }
}
