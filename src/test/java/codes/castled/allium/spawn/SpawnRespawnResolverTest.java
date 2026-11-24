package codes.castled.allium.spawn;

import codes.castled.allium.PluginStart;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.rtp.RtpService;
import codes.castled.allium.rtp.RtpSettings;
import codes.castled.allium.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Bukkit {@link SpawnRespawnResolver} adapter for world-plugin ownership detection,
 * respawn resolution, and join onboarding resolution.
 */
class SpawnRespawnResolverTest {

    @Test
    void worldPluginOwnershipMatchesConfiguredWorldsOrEnabledWorldManagerPlugin() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);

        World survival = mock(World.class);
        when(survival.getName()).thenReturn("survival");
        World minigames = mock(World.class);
        when(minigames.getName()).thenReturn("minigames");

        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);

        assertFalse(SpawnRespawnResolver.worldPluginOwns(plugin, survival));

        // Explicit per-world ownership list
        config.set("teleport.world-plugin-ownership.worlds", List.of("minigames"));
        assertFalse(SpawnRespawnResolver.worldPluginOwns(plugin, survival));
        assertTrue(SpawnRespawnResolver.worldPluginOwns(plugin, minigames));

        // Global enabled flag + Multiverse-Core present
        config.set("teleport.world-plugin-ownership.worlds", List.of());
        config.set("teleport.world-plugin-ownership.enabled", true);
        when(pm.isPluginEnabled("Multiverse-Core")).thenReturn(false);
        assertFalse(SpawnRespawnResolver.worldPluginOwns(plugin, survival));
        when(pm.isPluginEnabled("Multiverse-Core")).thenReturn(true);
        assertTrue(SpawnRespawnResolver.worldPluginOwns(plugin, survival));
    }

    @Test
    void resolverEvaluatesRespawnAndJoinUsingSharedPolicyPrecedence() {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);

        YamlConfiguration config = new YamlConfiguration();
        config.set("teleport.respawn-at-home", false);
        config.set("teleport.first-join-spawn", true);
        config.set("kits.first-join-kit", "starter");
        when(plugin.getConfig()).thenReturn(config);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location groupSpawn = new Location(world, 10, 64, 10);
        Location defaultSpawn = new Location(world, 0, 64, 0);

        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getWorld()).thenReturn(world);
        when(player.hasPlayedBefore()).thenReturn(false);

        TeleportService teleports = mock(TeleportService.class);
        when(teleports.groupSpawn(player)).thenReturn(groupSpawn);
        when(teleports.defaultSpawn()).thenReturn(defaultSpawn);

        RtpService rtp = mock(RtpService.class);
        YamlConfiguration rtpYaml = new YamlConfiguration();
        rtpYaml.set("on-death.enabled", true);
        rtpYaml.set("first-join.enabled", true);
        RtpSettings rtpSettings = RtpSettings.read(rtpYaml);
        when(rtp.settings()).thenReturn(rtpSettings);

        DisciplineService discipline = mock(DisciplineService.class);
        when(discipline.jailed(uuid)).thenReturn(false);
        when(discipline.hasPendingRelease(player)).thenReturn(false);

        // Respawn with death RTP on -> DEATH_RTP with GROUP_SPAWN fallback
        PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class);
        when(respawn.getPlayer()).thenReturn(player);
        when(respawn.getRespawnReason()).thenReturn(PlayerRespawnEvent.RespawnReason.DEATH);
        when(respawn.getRespawnLocation()).thenReturn(defaultSpawn);

        var respawnRes = SpawnRespawnResolver.evaluateRespawn(plugin, respawn, teleports, rtp, discipline);
        assertEquals(SpawnRespawnPolicy.RespawnTarget.DEATH_RTP, respawnRes.decision().primary());
        assertEquals(SpawnRespawnPolicy.RespawnTarget.GROUP_SPAWN, respawnRes.decision().fallbackOnRtpFailure());
        assertEquals(groupSpawn, respawnRes.targetLocation(respawnRes.decision().fallbackOnRtpFailure()));

        // First join with RTP + first-join-spawn + starter kit
        var joinRes = SpawnRespawnResolver.evaluateJoin(plugin, player, teleports, rtp, discipline);
        assertEquals(SpawnRespawnPolicy.JoinTarget.FIRST_JOIN_RTP, joinRes.decision().primary());
        assertEquals(SpawnRespawnPolicy.JoinTarget.GROUP_SPAWN, joinRes.decision().fallbackOnRtpFailure());
        assertEquals("starter", joinRes.decision().grantKit());
        assertEquals(groupSpawn, joinRes.targetLocation(joinRes.decision().fallbackOnRtpFailure()));

        // Once jailed, discipline overrides both respawn and join
        when(discipline.jailed(uuid)).thenReturn(true);
        assertEquals(SpawnRespawnPolicy.RespawnTarget.JAIL,
                SpawnRespawnResolver.evaluateRespawn(plugin, respawn, teleports, rtp, discipline).decision().primary());
        assertEquals(SpawnRespawnPolicy.JoinTarget.JAIL,
                SpawnRespawnResolver.evaluateJoin(plugin, player, teleports, rtp, discipline).decision().primary());
    }
}
