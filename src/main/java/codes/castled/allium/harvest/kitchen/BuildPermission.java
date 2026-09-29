package codes.castled.allium.harvest.kitchen;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Asks the installed protection plugins whether a player may build at a
 * location.
 *
 * <p>Pies are entities, not blocks, so no {@code BlockPlaceEvent} happens for
 * protection plugins to cancel. Firing a synthetic one would work, but block
 * loggers such as CoreProtect record it as a real placement. Instead the
 * GriefPrevention and WorldGuard APIs are queried directly, reflectively so
 * that neither is a hard dependency.
 */
final class BuildPermission {

    private final Logger logger;
    private boolean warnedGriefPrevention;
    private boolean warnedWorldGuard;

    BuildPermission(Logger logger) {
        this.logger = logger;
    }

    boolean canBuild(Player player, Location location) {
        return griefPrevention(player, location) && worldGuard(player, location);
    }

    private boolean griefPrevention(Player player, Location location) {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) return true;
        try {
            // GriefPrevention#allowBuild returns null when building is allowed,
            // otherwise the denial message. Same in upstream and GP3D.
            Method allowBuild = gp.getClass().getMethod("allowBuild", Player.class, Location.class);
            Object denial = allowBuild.invoke(gp, player, location);
            if (denial != null && !denial.toString().isBlank()) {
                player.sendActionBar(net.kyori.adventure.text.Component.text(
                    denial.toString(), net.kyori.adventure.text.format.NamedTextColor.RED));
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (!warnedGriefPrevention) {
                warnedGriefPrevention = true;
                logger.warning("[Kitchen] GriefPrevention build check failed, allowing: " + e);
            }
            return true;
        }
    }

    private boolean worldGuard(Player player, Location location) {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null || !wg.isEnabled()) return true;
        try {
            ClassLoader loader = wg.getClass().getClassLoader();
            Object localPlayer = wg.getClass().getMethod("wrapPlayer", Player.class).invoke(wg, player);

            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard);

            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader);
            Object weWorld = adapter.getMethod("adapt", org.bukkit.World.class).invoke(null, location.getWorld());
            Object sessions = platform.getClass().getMethod("getSessionManager").invoke(platform);
            for (Method method : sessions.getClass().getMethods()) {
                if (method.getName().equals("hasBypass") && method.getParameterCount() == 2) {
                    if ((boolean) method.invoke(sessions, localPlayer, weWorld)) return true;
                    break;
                }
            }

            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Object weLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Class<?> stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, loader);
            for (Method method : query.getClass().getMethods()) {
                if (method.getName().equals("testBuild") && method.getParameterCount() == 3
                        && method.getParameterTypes()[2].isArray()
                        && method.getParameterTypes()[0].isInstance(weLocation)) {
                    Object noFlags = Array.newInstance(stateFlag, 0);
                    return (boolean) method.invoke(query, weLocation, localPlayer, noFlags);
                }
            }
            throw new NoSuchMethodException("RegionQuery#testBuild");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (!warnedWorldGuard) {
                warnedWorldGuard = true;
                logger.warning("[Kitchen] WorldGuard build check failed, allowing: " + e);
            }
            return true;
        }
    }
}
