package codes.castled.allium.tradingcards.morph;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Puts on and takes off a player's disguise, reflectively.
 *
 * <p>LibsDisguises is premium, so there is no artifact to compile against and
 * nothing to depend on. Every call is a lookup by name, and every one of them is
 * wrapped: a missing method is a logged warning and a disabled feature, never an
 * exception at enable.
 *
 * <h2>The disguise is cosmetic</h2>
 *
 * <p>LibsDisguises is PacketEvents-based. {@code Disguise.startDisguise} refreshes
 * trackers and broadcasts spawn packets; nothing anywhere swaps the server-side
 * entity type. A player wearing a vindicator deals player damage, takes player
 * damage, and is not a vindicator to any plugin that reads the entity.
 *
 * <p>So this class only changes what the player <i>looks</i> like. Every
 * behaviour tied to the form — resistances, size, abilities, and the targeting
 * rule — is implemented separately in {@link MorphService} and
 * {@code MorphTargetingListener}, which is the only honest way to do it.
 *
 * <h2>Premium only changes who can see it</h2>
 *
 * <p>Self-view works on the free version: {@code Disguise.java:1092} only
 * force-clears the self-view flag when premium is active, and the free fallback
 * explicitly adds the holder to the visible list. Premium widens the audience
 * to everyone. So the two paths are a graceful upgrade rather than a
 * requirement, and the free path still gives the player the morph.
 */
public final class DisguiseBridge {

    private static final String API = "me.libraryaddict.disguise.DisguiseAPI";
    private static final String MOB_DISGUISE = "me.libraryaddict.disguise.disguisetypes.MobDisguise";
    private static final String DISGUISE_TYPE = "me.libraryaddict.disguise.disguisetypes.DisguiseType";
    private static final String PREMIUM = "me.libraryaddict.disguise.utilities.LibsPremium";

    private final Plugin plugin;
    private final Logger logger;

    /** Who is currently disguised as what, so targeting does not have to guess. */
    private final ConcurrentHashMap<UUID, String> active = new ConcurrentHashMap<>();

    private Method mDisguiseEntity;
    private Method mUndisguiseToAll;
    private Method mIsDisguised;
    private Method mGetDisguise;
    private Method mGetType;
    private java.lang.reflect.Constructor<?> mMobDisguiseCtor;
    private Method mSetMobsIgnoreDisguise;
    private Method mDisguiseTypeValueOf;
    private Method mIsPremium;
    private boolean ready;

    public DisguiseBridge(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * Resolves the API surface.
     *
     * <p>Called once, after LibsDisguises has enabled. Returns false rather than
     * throwing so the module loads on a server without it.
     */
    public boolean initialise() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            return false;
        }
        try {
            Class<?> api = Class.forName(API);
            mDisguiseEntity = api.getMethod("disguiseEntity",
                org.bukkit.entity.Entity.class, Class.forName(MOB_DISGUISE));
            mUndisguiseToAll = api.getMethod("undisguiseToAll", org.bukkit.entity.Entity.class);
            mIsDisguised = api.getMethod("isDisguised", org.bukkit.entity.Entity.class);
            mGetDisguise = api.getMethod("getDisguise", org.bukkit.entity.Entity.class);
            mGetType = Class.forName(MOB_DISGUISE).getMethod("getType");
            mMobDisguiseCtor = Class.forName(MOB_DISGUISE)
                .getConstructor(Class.forName(DISGUISE_TYPE));
            mSetMobsIgnoreDisguise = Class.forName(MOB_DISGUISE)
                .getMethod("setMobsIgnoreDisguise", boolean.class);
            mDisguiseTypeValueOf = Class.forName(DISGUISE_TYPE)
                .getMethod("valueOf", String.class);
            try {
                mIsPremium = Class.forName(PREMIUM).getMethod("isPremium");
            } catch (Throwable ignored) {
                // The premium marker is optional; free is assumed.
                mIsPremium = null;
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] LibsDisguises is installed but its API does not match what "
                + "this build expects; morphing is unavailable. (" + t + ")");
            ready = false;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** True when the free version is in use, which limits who can see the morph. */
    public boolean isFree() {
        if (mIsPremium == null) return true;
        try {
            return !Boolean.TRUE.equals(mIsPremium.invoke(null));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Disguises a player as an entity type.
     *
     * <p>Any existing disguise is removed first: {@code disguiseEntity} while
     * already disguised does not throw, it stacks a second layer, and a player
     * wearing two mobs looks like neither.
     *
     * @param mobsTargetUs when false, LibsDisguises is told NOT to cancel
     *                     {@code EntityTargetEvent} for this disguise. That
     *                     cancellation is on by default and would make a
     *                     morphed player invisible to mob AI, which is the
     *                     opposite of what a morph is for.
     * @return true when the disguise is live
     */
    public boolean disguise(Player player, EntityType type, boolean mobsTargetUs) {
        if (!ready || type == null) {
            return false;
        }
        // The hook enforces region ownership itself and throws on violation, so
        // this is dispatched rather than called from whatever thread the
        // command ran on.
        Bukkit.getScheduler().runTask(plugin,
            () -> applyDisguise(player, type, mobsTargetUs));
        return true;
    }

    private void applyDisguise(Player player, EntityType type, boolean mobsTargetUs) {
        try {
            if (Boolean.TRUE.equals(mIsDisguised.invoke(null, player))) {
                mUndisguiseToAll.invoke(null, player);
                active.remove(player.getUniqueId());
            }
            Object disguiseType = mDisguiseTypeValueOf.invoke(null, type.name());
            Object disguise = mMobDisguiseCtor.newInstance(disguiseType);
            mSetMobsIgnoreDisguise.invoke(disguise, !mobsTargetUs);
            // One call either way: free shows the morph to the holder, premium
            // to everyone. Nothing about setting it up differs, so branching here
            // would only be a place for the two paths to drift apart.
            mDisguiseEntity.invoke(null, player, disguise);
            active.put(player.getUniqueId(), type.name());
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not disguise " + player.getName() + ": " + t);
        }
    }

    /** Removes any disguise from a player. */
    public boolean undisguise(Player player) {
        if (!ready) {
            return false;
        }
        try {
            // undisguiseToAll rather than removeDisguise on a held instance: the
            // latter leaves any additional layers in place.
            mUndisguiseToAll.invoke(null, player);
            active.remove(player.getUniqueId());
            return true;
        } catch (Throwable t) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Could not undisguise " + player.getName() + ": " + t);
            return false;
        }
    }

    /** Toggles a player's morph, returning the state they are now in. */
    public MorphState toggle(Player player, EntityType type, boolean mobsTargetUs) {
        if (isDisguised(player)) {
            undisguise(player);
            return MorphState.REMOVED;
        }
        return disguise(player, type, mobsTargetUs) ? MorphState.APPLIED : MorphState.FAILED;
    }

    public boolean isDisguised(Player player) {
        if (!ready) return false;
        try {
            return Boolean.TRUE.equals(mIsDisguised.invoke(null, player));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The entity type a player is currently disguised as, or null.
     *
     * <p>Read from the disguise rather than from our own map, so a morph applied
     * by another plugin is recognised and gated the same way.
     */
    public EntityType disguisedAs(Player player) {
        if (!ready) return null;
        try {
            Object disguise = mGetDisguise.invoke(null, player);
            if (disguise == null) return null;
            Object type = mGetType.invoke(disguise);
            if (type == null) return null;
            Object entityType = type.getClass().getMethod("getEntityType").invoke(type);
            if (entityType instanceof EntityType resolved) {
                return resolved;
            }
            return EntityType.valueOf(type.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** How many players are currently morphed, for the command. */
    public int morphedCount() {
        return active.size();
    }

    /** Forgets a player, for cleanup on quit. */
    public void forget(UUID player) {
        active.remove(player);
    }

    public enum MorphState { APPLIED, REMOVED, FAILED }
}
