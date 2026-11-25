package codes.castled.curios;

import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Zero-dependency reflection bridge to AuraSkills' {@code AuraSkillsApi}.
 *
 * <p>All applied stat modifiers are explicitly marked non-persistent ({@code setNonPersistent()})
 * and prefixed with {@code curios:<uuid>:} so they never leak into AuraSkills storage on logout
 * and can always be removed cleanly on unequip, quit, or disable.
 */
final class AuraSkillsBridge {

    private static final String PREFIX = "curios:";

    private final Logger logger;
    private boolean initialized;
    private boolean available;

    private Method apiGet;
    private Method getUser;
    private Method isLoaded;
    private Method addStatModifier;
    private Method removeStatModifier;
    private Method getStatModifiers;
    private Class<?> statsEnum;
    private Method statsValueOf;
    private Constructor<?> statModifierCtor;
    private Method setNonPersistent;
    private Object operationAdd;

    AuraSkillsBridge(Logger logger) {
        this.logger = logger;
    }

    boolean isPluginEnabled() {
        try {
            return Bukkit.getServer() != null && Bukkit.getPluginManager().isPluginEnabled("AuraSkills");
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean isAvailable() {
        if (!isPluginEnabled()) return false;
        ensureResolved();
        return available;
    }

    private void ensureResolved() {
        if (initialized) return;
        initialized = true;
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("AuraSkills");
            ClassLoader cl = plugin != null ? plugin.getClass().getClassLoader() : getClass().getClassLoader();
            Class<?> apiClass = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi", true, cl);
            Class<?> userClass = Class.forName("dev.aurelium.auraskills.api.user.SkillsUser", true, cl);
            Class<?> statInterface = Class.forName("dev.aurelium.auraskills.api.stat.Stat", true, cl);
            Class<?> statsClass = Class.forName("dev.aurelium.auraskills.api.stat.Stats", true, cl);
            Class<?> modClass = Class.forName("dev.aurelium.auraskills.api.stat.StatModifier", true, cl);
            Class<?> opClass = Class.forName("dev.aurelium.auraskills.api.util.AuraSkillsModifier$Operation", true, cl);

            this.apiGet = apiClass.getMethod("get");
            this.getUser = apiClass.getMethod("getUser", UUID.class);
            this.isLoaded = userClass.getMethod("isLoaded");
            this.addStatModifier = userClass.getMethod("addStatModifier", modClass);
            this.removeStatModifier = userClass.getMethod("removeStatModifier", String.class);
            this.getStatModifiers = userClass.getMethod("getStatModifiers");
            this.statsEnum = statsClass;
            this.statsValueOf = statsClass.getMethod("valueOf", String.class);
            this.statModifierCtor = modClass.getConstructor(String.class, statInterface, double.class, opClass);
            this.setNonPersistent = modClass.getMethod("setNonPersistent");
            this.operationAdd = opClass.getMethod("valueOf", String.class).invoke(null, "ADD");
            this.available = true;
        } catch (Throwable unavailable) {
            this.available = false;
            logger.fine("AuraSkills API bridge unavailable: " + unavailable.getMessage());
        }
    }

    /** Returns true if AuraSkills is active and the player's SkillsUser is loaded. */
    boolean isUserLoaded(UUID playerId) {
        if (!isAvailable()) return false;
        try {
            Object api = apiGet.invoke(null);
            if (api == null) return false;
            Object user = getUser.invoke(api, playerId);
            return user != null && Boolean.TRUE.equals(isLoaded.invoke(user));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Replaces all {@code curios:} AuraSkills stat modifiers for {@code playerId} with {@code stats}.
     *
     * @return {@code true} if the user was loaded and updated, {@code false} if AuraSkills is
     *         absent or the player's data has not finished loading yet
     */
    boolean applyStats(UUID playerId, Map<String, Double> stats) {
        if (!isAvailable()) return false;
        try {
            Object api = apiGet.invoke(null);
            if (api == null) return false;
            Object user = getUser.invoke(api, playerId);
            if (user == null || !Boolean.TRUE.equals(isLoaded.invoke(user))) return false;
            clearUserModifiers(user, playerId);
            for (var entry : stats.entrySet()) {
                double amount = entry.getValue();
                if (!Double.isFinite(amount) || amount == 0.0) continue;
                Object stat = resolveStat(entry.getKey());
                if (stat == null) continue;
                String key = modifierName(playerId, entry.getKey());
                Object modifier = statModifierCtor.newInstance(key, stat, amount, operationAdd);
                setNonPersistent.invoke(modifier);
                removeStatModifier.invoke(user, key);
                addStatModifier.invoke(user, modifier);
            }
            return true;
        } catch (Throwable failure) {
            logger.warning("Could not apply AuraSkills card boosts for " + playerId + ": " + failure.getMessage());
            return false;
        }
    }

    /** Removes every {@code curios:} stat modifier from {@code playerId}. Safe if absent. */
    void removeAll(UUID playerId) {
        if (!isAvailable()) return;
        try {
            Object api = apiGet.invoke(null);
            if (api == null) return;
            Object user = getUser.invoke(api, playerId);
            if (user == null || !Boolean.TRUE.equals(isLoaded.invoke(user))) return;
            clearUserModifiers(user, playerId);
        } catch (Throwable ignored) {
        }
    }

    private void clearUserModifiers(Object user, UUID playerId) throws ReflectiveOperationException {
        Object mapObj = getStatModifiers.invoke(user);
        if (!(mapObj instanceof Map<?, ?> map)) return;
        String playerPrefix = PREFIX + playerId + ":";
        List<String> toRemove = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (key instanceof String name && (name.startsWith(playerPrefix) || name.startsWith(PREFIX))) {
                toRemove.add(name);
            }
        }
        for (String name : toRemove) {
            removeStatModifier.invoke(user, name);
        }
    }

    private Object resolveStat(String boostId) {
        String enumName = switch (boostId.trim().toLowerCase(Locale.ROOT)) {
            case "luck" -> "LUCK";
            case "strength" -> "STRENGTH";
            case "speed" -> "SPEED";
            case "health" -> "HEALTH";
            case "toughness" -> "TOUGHNESS";
            case "wisdom" -> "WISDOM";
            case "regeneration" -> "REGENERATION";
            case "crit-chance", "crit_chance" -> "CRIT_CHANCE";
            case "crit-damage", "crit_damage" -> "CRIT_DAMAGE";
            default -> boostId.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        };
        try {
            return statsValueOf.invoke(null, enumName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String modifierName(UUID playerId, String statKey) {
        return PREFIX + playerId + ":" + statKey.toLowerCase(Locale.ROOT);
    }
}
