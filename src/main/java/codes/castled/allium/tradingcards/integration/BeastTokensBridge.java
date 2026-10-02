package codes.castled.allium.tradingcards.integration;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Pays tokens into BeastTokens, reflectively.
 *
 * <p>BeastTokens is not on Allium's compile classpath and there is no artifact
 * to depend on, so the whole surface is looked up by name. Everything is
 * optional: on a server without BeastTokens this reports unavailable and the
 * card's token boosts simply do not pay, rather than the plugin failing to load.
 *
 * <p>The lookup goes through the plugin instance's {@code BeastTokens} API
 * object, because {@code BTTokensManager} is what actually holds balances and it
 * is reached by a getter rather than constructed.
 */
public final class BeastTokensBridge {

    private static final String PLUGIN = "BeastTokens";
    private static final String API_CLASS = "me.mraxetv.beasttokens.api.BeastTokens";

    private final Logger logger;
    private Object tokensManager;
    private Method addTokens;
    private Method getTokens;
    private boolean ready;

    public BeastTokensBridge(Logger logger) {
        this.logger = logger;
    }

    /** Resolves the API surface. Returns false when BeastTokens is absent. */
    public boolean initialise() {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return false;
        }
        try {
            Object plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
            if (plugin == null) {
                return false;
            }
            tokensManager = tokensManagerOf(plugin);
            if (tokensManager == null) {
                return false;
            }
            Class<?> manager = tokensManager.getClass();
            addTokens = findAdd(manager);
            try {
                getTokens = manager.getMethod("getTokens", Player.class);
            } catch (NoSuchMethodException ignored) {
                getTokens = null;
            }
            ready = tokensManager != null && addTokens != null;
            return ready;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME + "] BeastTokens is "
                + "installed but its API does not match this build; token boosts will "
                + "not pay. (" + t + ")");
            ready = false;
            return false;
        }
    }

    /**
     * The tokens manager, wherever this build exposes it.
     *
     * <p>BeastTokens' main class implements the {@code BeastTokens} API itself,
     * so the manager is a getter on the plugin. Some builds instead expose a
     * separate {@code getBeastTokens()} API object, so both are tried before
     * giving up.
     */
    private Object tokensManagerOf(Object plugin) throws ReflectiveOperationException {
        try {
            Method direct = plugin.getClass().getMethod("getTokensManager");
            Object manager = direct.invoke(plugin);
            if (manager != null) {
                return manager;
            }
        } catch (NoSuchMethodException ignored) {
            // Falls through to the API-object form below.
        }
        Method apiGetter = plugin.getClass().getMethod("getBeastTokens");
        Object api = apiGetter.invoke(plugin);
        return api == null ? null : api.getClass().getMethod("getTokensManager").invoke(api);
    }

    /**
     * Finds the add-tokens method.
     *
     * <p>Two overloads exist — one taking a Player and one an OfflinePlayer —
     * and the declared parameter type is what disambiguates them, since both
     * take a double. Matching on the name alone would pick whichever the JVM
     * happened to list first.
     */
    private Method findAdd(Class<?> manager) {
        for (Method method : manager.getMethods()) {
            if (!"addTokens".equals(method.getName())
                || method.getParameterCount() != 2
                || method.getParameterTypes()[1] != double.class) {
                continue;
            }
            if (Player.class.isAssignableFrom(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Adds tokens to a player.
     *
     * @param amount  how many; fractional because BeastTokens stores a double
     * @return true when the tokens were paid
     */
    public boolean addTokens(Player player, double amount) {
        if (!ready || amount <= 0.0) {
            return false;
        }
        try {
            addTokens.invoke(tokensManager, player, amount);
            return true;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not pay " + amount + " tokens to " + player.getName() + ": " + t);
            return false;
        }
    }

    /** A player's balance, or 0 when it cannot be read. */
    public double balance(Player player) {
        if (!ready || getTokens == null) {
            return 0.0;
        }
        try {
            Object value = getTokens.invoke(tokensManager, player);
            return value instanceof Double balance ? balance : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }
}
