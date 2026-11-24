package codes.castled.allium.spawn;

import codes.castled.allium.PluginStart;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.RtpModule;
import codes.castled.allium.module.TeleportModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Bukkit bridge that collects live module and configuration inputs for {@link SpawnRespawnPolicy}
 * without taking ownership of teleport, RTP, jail, or kit execution.
 */
public final class SpawnRespawnResolver {

    private static final List<String> KNOWN_WORLD_PLUGINS = List.of("Multiverse-Core", "My_Worlds", "MultiWorld");

    public record RespawnResolution(
            SpawnRespawnPolicy.RespawnDecision decision,
            @Nullable Location homeLocation,
            @Nullable Location groupSpawnLocation,
            @Nullable Location defaultSpawnLocation
    ) {
        @Nullable
        public Location targetLocation(SpawnRespawnPolicy.RespawnTarget target) {
            return switch (target) {
                case HOME -> homeLocation;
                case GROUP_SPAWN -> groupSpawnLocation;
                case DEFAULT_SPAWN -> defaultSpawnLocation;
                default -> null;
            };
        }
    }

    public record JoinResolution(
            SpawnRespawnPolicy.JoinDecision decision,
            @Nullable Location groupSpawnLocation,
            @Nullable Location defaultSpawnLocation
    ) {
        @Nullable
        public Location targetLocation(SpawnRespawnPolicy.JoinTarget target) {
            return switch (target) {
                case GROUP_SPAWN -> groupSpawnLocation;
                case DEFAULT_SPAWN -> defaultSpawnLocation;
                default -> null;
            };
        }
    }

    private SpawnRespawnResolver() {
    }

    /**
     * Returns whether a world-management plugin owns spawn/respawn for {@code world}.
     * Configured via {@code teleport.world-plugin-ownership.enabled} (auto-detecting enabled world
     * managers) or explicit world names in {@code teleport.world-plugin-ownership.worlds}.
     */
    public static boolean worldPluginOwns(PluginStart plugin, @Nullable World world) {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            return false;
        }
        List<String> configuredWorlds = config.getStringList("teleport.world-plugin-ownership.worlds");
        if (!configuredWorlds.isEmpty()) {
            String worldName = world == null ? null : world.getName();
            for (String entry : configuredWorlds) {
                if (entry == null) continue;
                String trimmed = entry.trim();
                if ("*".equals(trimmed) || (worldName != null && worldName.equalsIgnoreCase(trimmed))) {
                    return true;
                }
            }
        }
        if (!config.getBoolean("teleport.world-plugin-ownership.enabled", false)) {
            return false;
        }
        var server = plugin.getServer();
        PluginManager pm = server == null ? null : server.getPluginManager();
        if (pm == null) {
            return false;
        }
        for (String pluginName : KNOWN_WORLD_PLUGINS) {
            if (pm.isPluginEnabled(pluginName)) {
                return true;
            }
        }
        return false;
    }

    /** Evaluates the shared respawn decision table for {@code event}. */
    public static RespawnResolution evaluateRespawn(
            PluginStart plugin,
            PlayerRespawnEvent event,
            @Nullable TeleportService teleportsOverride,
            @Nullable RtpService rtpOverride,
            @Nullable DisciplineService disciplineOverride
    ) {
        Player player = event.getPlayer();
        boolean death = event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.DEATH;
        boolean bedOrAnchor = event.isBedSpawn() || event.isAnchorSpawn();

        DisciplineService discipline = disciplineOverride != null ? disciplineOverride : discipline(plugin);
        boolean pendingRelease = player != null && discipline != null && discipline.hasPendingRelease(player);
        boolean jailed = player != null && discipline != null && discipline.jailed(player.getUniqueId());

        World respawnWorld = event.getRespawnLocation() != null
                ? event.getRespawnLocation().getWorld()
                : (player != null ? player.getWorld() : null);
        boolean worldPluginOwns = worldPluginOwns(plugin, respawnWorld);

        RtpService rtp = rtpOverride != null ? rtpOverride : rtp(plugin);
        boolean rtpOnDeath = rtp != null && rtp.settings().onDeath();

        FileConfiguration config = plugin.getConfig();
        boolean respawnAtHome = config == null || config.getBoolean("teleport.respawn-at-home", true);

        // Only query world/home/spawn lookups when non-discipline, death, non-bed, non-world-plugin path needs them.
        Location home = null;
        Location groupSpawn = null;
        Location defaultSpawn = null;
        if (player != null && death && !pendingRelease && !jailed && !worldPluginOwns && !bedOrAnchor) {
            if (respawnAtHome) {
                home = respawnHome(plugin, player);
            }
            TeleportService tp = teleportsOverride != null ? teleportsOverride : teleports(plugin);
            if (tp != null) {
                groupSpawn = tp.groupSpawn(player);
                defaultSpawn = tp.defaultSpawn();
            }
        }

        SpawnRespawnPolicy.RespawnContext ctx = SpawnRespawnPolicy.RespawnContext.builder()
                .deathRespawn(death)
                .pendingJailRelease(pendingRelease)
                .jailed(jailed)
                .worldPluginOwnsRespawn(worldPluginOwns)
                .bedOrAnchorSpawn(bedOrAnchor)
                .rtpOnDeathEnabled(rtpOnDeath)
                .respawnAtHomeEnabled(respawnAtHome)
                .hasHome(home != null)
                .hasGroupSpawn(groupSpawn != null)
                .hasDefaultSpawn(defaultSpawn != null)
                .build();

        return new RespawnResolution(SpawnRespawnPolicy.decideRespawn(ctx), home, groupSpawn, defaultSpawn);
    }

    /** Evaluates the shared join and first-join onboarding decision table for {@code player}. */
    public static JoinResolution evaluateJoin(
            PluginStart plugin,
            Player player,
            @Nullable TeleportService teleportsOverride,
            @Nullable RtpService rtpOverride,
            @Nullable DisciplineService disciplineOverride
    ) {
        return evaluateJoin(plugin, player, !player.hasPlayedBefore(), teleportsOverride, rtpOverride, disciplineOverride);
    }

    /** Evaluates the shared join decision table with an explicit {@code firstJoin} flag (for onboarding recovery). */
    public static JoinResolution evaluateJoin(
            PluginStart plugin,
            Player player,
            boolean firstJoin,
            @Nullable TeleportService teleportsOverride,
            @Nullable RtpService rtpOverride,
            @Nullable DisciplineService disciplineOverride
    ) {
        DisciplineService discipline = disciplineOverride != null ? disciplineOverride : discipline(plugin);
        boolean jailed = discipline != null && discipline.jailed(player.getUniqueId());
        boolean worldPluginOwns = worldPluginOwns(plugin, player.getWorld());

        RtpService rtp = rtpOverride != null ? rtpOverride : rtp(plugin);
        boolean firstJoinRtp = rtp != null && rtp.settings().firstJoin();

        FileConfiguration config = plugin.getConfig();
        boolean firstJoinSpawn = config != null && config.getBoolean("teleport.first-join-spawn", false);
        boolean spawnOnJoin = config != null && config.getBoolean("teleport.spawn-on-join", false);
        String firstJoinKit = config == null ? null : config.getString("kits.first-join-kit", "");

        TeleportService tp = teleportsOverride != null ? teleportsOverride : teleports(plugin);
        Location groupSpawn = tp == null ? null : tp.groupSpawn(player);
        Location defaultSpawn = tp == null ? null : tp.defaultSpawn();

        SpawnRespawnPolicy.JoinContext ctx = SpawnRespawnPolicy.JoinContext.builder()
                .firstJoin(firstJoin)
                .jailed(jailed)
                .worldPluginOwnsJoin(worldPluginOwns)
                .firstJoinRtpEnabled(firstJoinRtp)
                .firstJoinSpawnEnabled(firstJoinSpawn)
                .spawnOnJoin(spawnOnJoin)
                .hasGroupSpawn(groupSpawn != null)
                .hasDefaultSpawn(defaultSpawn != null)
                .firstJoinKit(firstJoinKit)
                .build();

        return new JoinResolution(SpawnRespawnPolicy.decideJoin(ctx), groupSpawn, defaultSpawn);
    }

    /** Resolves a fallback target after a death RTP search fails. */
    @Nullable
    public static Location resolveRespawnFallbackLocation(
            PluginStart plugin,
            Player player,
            SpawnRespawnPolicy.RespawnTarget fallback,
            @Nullable TeleportService teleportsOverride
    ) {
        if (fallback == SpawnRespawnPolicy.RespawnTarget.HOME) {
            return respawnHome(plugin, player);
        }
        TeleportService tp = teleportsOverride != null ? teleportsOverride : teleports(plugin);
        if (tp == null) return null;
        return switch (fallback) {
            case GROUP_SPAWN -> tp.groupSpawn(player);
            case DEFAULT_SPAWN -> tp.defaultSpawn();
            default -> null;
        };
    }

    /** Resolves a fallback target after a first-join RTP search fails. */
    @Nullable
    public static Location resolveJoinFallbackLocation(
            PluginStart plugin,
            Player player,
            SpawnRespawnPolicy.JoinTarget fallback,
            @Nullable TeleportService teleportsOverride
    ) {
        TeleportService tp = teleportsOverride != null ? teleportsOverride : teleports(plugin);
        if (tp == null) return null;
        return switch (fallback) {
            case GROUP_SPAWN -> tp.groupSpawn(player);
            case DEFAULT_SPAWN -> tp.defaultSpawn();
            default -> null;
        };
    }

    @Nullable
    private static Location respawnHome(PluginStart plugin, Player player) {
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("travel") instanceof TravelModule travel
                && travel.isEnabled() && travel.travel() != null) {
            return travel.travel().respawnHome(player);
        }
        return null;
    }

    @Nullable
    private static TeleportService teleports(PluginStart plugin) {
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("teleport") instanceof TeleportModule mod && mod.isEnabled()) {
            return mod.teleports();
        }
        return null;
    }

    @Nullable
    private static RtpService rtp(PluginStart plugin) {
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("rtp") instanceof RtpModule mod && mod.isEnabled()) {
            return mod.rtp();
        }
        return null;
    }

    @Nullable
    private static DisciplineService discipline(PluginStart plugin) {
        var modules = plugin.getModuleManager();
        if (modules != null && modules.getModule("moderation") instanceof ModerationModule mod && mod.isEnabled()) {
            return mod.discipline();
        }
        return null;
    }
}
