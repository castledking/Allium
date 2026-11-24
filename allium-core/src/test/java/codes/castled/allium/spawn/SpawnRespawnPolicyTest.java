package codes.castled.allium.spawn;

import codes.castled.allium.spawn.SpawnRespawnPolicy.JoinContext;
import codes.castled.allium.spawn.SpawnRespawnPolicy.JoinDecision;
import codes.castled.allium.spawn.SpawnRespawnPolicy.JoinTarget;
import codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnContext;
import codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnDecision;
import codes.castled.allium.spawn.SpawnRespawnPolicy.RespawnTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision-table tests for {@link SpawnRespawnPolicy} covering jail, pending jail release,
 * world-plugin ownership, bed/anchor, failed RTP fallback, home, group spawn, default spawn,
 * first join, spawn-on-join, and onboarding kits.
 */
class SpawnRespawnPolicyTest {

    @Test
    void respawnPrecedenceDecisionTable() {
        // 1. Pending jail release beats everything (including world plugin, bed/anchor, RTP, home, group spawn)
        RespawnDecision release = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .pendingJailRelease(true)
                .jailed(true)
                .worldPluginOwnsRespawn(true)
                .bedOrAnchorSpawn(true)
                .rtpOnDeathEnabled(true)
                .respawnAtHomeEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.JAIL_RELEASE_RETURN, release.primary());
        assertEquals(RespawnTarget.JAIL, release.fallbackOnRtpFailure());
        assertTrue(release.isDisciplineOwned());

        // 2. Active jail overrides world-plugin ownership, bed/anchor, RTP, home, and spawns (even on non-death respawn)
        RespawnDecision jailed = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(false)
                .jailed(true)
                .worldPluginOwnsRespawn(true)
                .bedOrAnchorSpawn(true)
                .rtpOnDeathEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.JAIL, jailed.primary());
        assertTrue(jailed.isDisciplineOwned());

        // 3. Non-death respawn (e.g. End exit portal) stays vanilla when not jailed
        RespawnDecision nonDeath = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(false)
                .rtpOnDeathEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.VANILLA_DEFAULT, nonDeath.primary());
        assertFalse(nonDeath.schedulesRtp());

        // 4. World-plugin ownership defers respawn when not jailed
        RespawnDecision worldPlugin = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .worldPluginOwnsRespawn(true)
                .bedOrAnchorSpawn(true)
                .rtpOnDeathEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.DEFER_TO_WORLD_PLUGIN, worldPlugin.primary());
        assertFalse(worldPlugin.schedulesRtp());

        // 5. Bed or respawn anchor beats death RTP, home, and configured spawns
        RespawnDecision bed = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .bedOrAnchorSpawn(true)
                .rtpOnDeathEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.BED_OR_ANCHOR, bed.primary());
        assertFalse(bed.schedulesRtp());

        // 6. Death RTP schedules RTP with explicit failure fallback chain: HOME > GROUP_SPAWN > DEFAULT_SPAWN > VANILLA
        RespawnDecision rtpHomeFallback = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .rtpOnDeathEnabled(true)
                .respawnAtHomeEnabled(true)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.DEATH_RTP, rtpHomeFallback.primary());
        assertEquals(RespawnTarget.HOME, rtpHomeFallback.fallbackOnRtpFailure());
        assertTrue(rtpHomeFallback.schedulesRtp());

        RespawnDecision rtpGroupFallback = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .rtpOnDeathEnabled(true)
                .respawnAtHomeEnabled(false)
                .hasHome(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.DEATH_RTP, rtpGroupFallback.primary());
        assertEquals(RespawnTarget.GROUP_SPAWN, rtpGroupFallback.fallbackOnRtpFailure());

        RespawnDecision rtpDefaultFallback = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .rtpOnDeathEnabled(true)
                .hasGroupSpawn(false)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(RespawnTarget.DEATH_RTP, rtpDefaultFallback.primary());
        assertEquals(RespawnTarget.DEFAULT_SPAWN, rtpDefaultFallback.fallbackOnRtpFailure());

        RespawnDecision rtpVanillaFallback = SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .deathRespawn(true)
                .rtpOnDeathEnabled(true)
                .build());
        assertEquals(RespawnTarget.DEATH_RTP, rtpVanillaFallback.primary());
        assertEquals(RespawnTarget.VANILLA_DEFAULT, rtpVanillaFallback.fallbackOnRtpFailure());

        // 7. Non-RTP death precedence: HOME > GROUP_SPAWN > DEFAULT_SPAWN > VANILLA
        assertEquals(RespawnTarget.HOME, SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .respawnAtHomeEnabled(true).hasHome(true).hasGroupSpawn(true).hasDefaultSpawn(true).build()).primary());
        assertEquals(RespawnTarget.GROUP_SPAWN, SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .respawnAtHomeEnabled(false).hasHome(true).hasGroupSpawn(true).hasDefaultSpawn(true).build()).primary());
        assertEquals(RespawnTarget.DEFAULT_SPAWN, SpawnRespawnPolicy.decideRespawn(RespawnContext.builder()
                .hasGroupSpawn(false).hasDefaultSpawn(true).build()).primary());
        assertEquals(RespawnTarget.VANILLA_DEFAULT, SpawnRespawnPolicy.decideRespawn(RespawnContext.builder().build()).primary());
    }

    @Test
    void joinAndFirstJoinOnboardingDecisionTable() {
        // 1. Jailed player stays in jail and never triggers RTP, spawn-on-join, or onboarding kit
        JoinDecision jailed = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(true)
                .jailed(true)
                .firstJoinRtpEnabled(true)
                .spawnOnJoin(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .firstJoinKit("starter")
                .build());
        assertEquals(JoinTarget.JAIL, jailed.primary());
        assertFalse(jailed.schedulesRtp());
        assertNull(jailed.grantKit());

        // 2. World-plugin ownership defers spawn movement while still allowing first-join kit on first join only
        JoinDecision worldPluginFirst = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(true)
                .worldPluginOwnsJoin(true)
                .firstJoinRtpEnabled(true)
                .spawnOnJoin(true)
                .hasGroupSpawn(true)
                .firstJoinKit("starter")
                .build());
        assertEquals(JoinTarget.DEFER_TO_WORLD_PLUGIN, worldPluginFirst.primary());
        assertEquals("starter", worldPluginFirst.grantKit());

        JoinDecision worldPluginReturning = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(false)
                .worldPluginOwnsJoin(true)
                .spawnOnJoin(true)
                .firstJoinKit("starter")
                .build());
        assertEquals(JoinTarget.DEFER_TO_WORLD_PLUGIN, worldPluginReturning.primary());
        assertNull(worldPluginReturning.grantKit());

        // 3. First-join RTP with group spawn fallback and starter kit
        JoinDecision firstJoinRtp = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(true)
                .firstJoinRtpEnabled(true)
                .firstJoinSpawnEnabled(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .firstJoinKit("  starter  ")
                .build());
        assertEquals(JoinTarget.FIRST_JOIN_RTP, firstJoinRtp.primary());
        assertEquals(JoinTarget.GROUP_SPAWN, firstJoinRtp.fallbackOnRtpFailure());
        assertEquals("starter", firstJoinRtp.grantKit());
        assertTrue(firstJoinRtp.grantsFirstJoinKit());

        // 4. First-join RTP without spawn fallback -> KEEP_POSITION on failure
        JoinDecision firstJoinRtpNoSpawnFallback = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(true)
                .firstJoinRtpEnabled(true)
                .firstJoinSpawnEnabled(false)
                .spawnOnJoin(false)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(JoinTarget.FIRST_JOIN_RTP, firstJoinRtpNoSpawnFallback.primary());
        assertEquals(JoinTarget.KEEP_POSITION, firstJoinRtpNoSpawnFallback.fallbackOnRtpFailure());

        // 5. First-join spawn (no RTP) chooses group spawn over default spawn and grants kit
        JoinDecision firstJoinGroup = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(true)
                .firstJoinSpawnEnabled(true)
                .hasGroupSpawn(true)
                .hasDefaultSpawn(true)
                .firstJoinKit("welcome")
                .build());
        assertEquals(JoinTarget.GROUP_SPAWN, firstJoinGroup.primary());
        assertEquals("welcome", firstJoinGroup.grantKit());

        // 6. Returning player with spawn-on-join -> DEFAULT_SPAWN (when no group spawn) and never grants first-join kit
        JoinDecision returningSpawn = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(false)
                .firstJoinRtpEnabled(true)
                .spawnOnJoin(true)
                .hasGroupSpawn(false)
                .hasDefaultSpawn(true)
                .firstJoinKit("starter")
                .build());
        assertEquals(JoinTarget.DEFAULT_SPAWN, returningSpawn.primary());
        assertFalse(returningSpawn.schedulesRtp());
        assertNull(returningSpawn.grantKit());

        // 7. Returning player without spawn-on-join -> KEEP_POSITION
        JoinDecision returningKeep = SpawnRespawnPolicy.decideJoin(JoinContext.builder()
                .firstJoin(false)
                .spawnOnJoin(false)
                .hasDefaultSpawn(true)
                .build());
        assertEquals(JoinTarget.KEEP_POSITION, returningKeep.primary());
    }
}
