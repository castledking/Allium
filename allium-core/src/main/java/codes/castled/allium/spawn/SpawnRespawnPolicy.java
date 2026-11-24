package codes.castled.allium.spawn;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pure, platform-neutral semantic precedence policy for player join, first-join onboarding,
 * and death respawn decisions.
 *
 * <p>Consolidates precedence across discipline (jail / pending jail release), world-management
 * plugin ownership, bed/respawn anchor, death/first-join RTP (with explicit failure fallback),
 * home respawn, group spawn, default spawn, and first-join kit eligibility while leaving teleport
 * and kit execution behind their existing runtime seams.
 */
public final class SpawnRespawnPolicy {

    private SpawnRespawnPolicy() {
    }

    public enum RespawnTarget {
        /** Pending expired jail release returning the prisoner to their saved return location. */
        JAIL_RELEASE_RETURN,
        /** Active prisoner forced back into their assigned jail. */
        JAIL,
        /** Defer respawn location to a world-management plugin owning this world. */
        DEFER_TO_WORLD_PLUGIN,
        /** Vanilla bed or respawn anchor respawn. */
        BED_OR_ANCHOR,
        /** Delayed random teleport triggered on death. */
        DEATH_RTP,
        /** Saved primary home ({@code teleport.respawn-at-home}). */
        HOME,
        /** Group-specific spawn ({@code /setspawn <group>}). */
        GROUP_SPAWN,
        /** Global server spawn ({@code /setspawn}). */
        DEFAULT_SPAWN,
        /** Leave Bukkit/vanilla respawn location unchanged. */
        VANILLA_DEFAULT
    }

    public record RespawnContext(
            boolean deathRespawn,
            boolean pendingJailRelease,
            boolean jailed,
            boolean worldPluginOwnsRespawn,
            boolean bedOrAnchorSpawn,
            boolean rtpOnDeathEnabled,
            boolean respawnAtHomeEnabled,
            boolean hasHome,
            boolean hasGroupSpawn,
            boolean hasDefaultSpawn
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean deathRespawn = true;
            private boolean pendingJailRelease;
            private boolean jailed;
            private boolean worldPluginOwnsRespawn;
            private boolean bedOrAnchorSpawn;
            private boolean rtpOnDeathEnabled;
            private boolean respawnAtHomeEnabled = true;
            private boolean hasHome;
            private boolean hasGroupSpawn;
            private boolean hasDefaultSpawn;

            public Builder deathRespawn(boolean v) { this.deathRespawn = v; return this; }
            public Builder pendingJailRelease(boolean v) { this.pendingJailRelease = v; return this; }
            public Builder jailed(boolean v) { this.jailed = v; return this; }
            public Builder worldPluginOwnsRespawn(boolean v) { this.worldPluginOwnsRespawn = v; return this; }
            public Builder bedOrAnchorSpawn(boolean v) { this.bedOrAnchorSpawn = v; return this; }
            public Builder rtpOnDeathEnabled(boolean v) { this.rtpOnDeathEnabled = v; return this; }
            public Builder respawnAtHomeEnabled(boolean v) { this.respawnAtHomeEnabled = v; return this; }
            public Builder hasHome(boolean v) { this.hasHome = v; return this; }
            public Builder hasGroupSpawn(boolean v) { this.hasGroupSpawn = v; return this; }
            public Builder hasDefaultSpawn(boolean v) { this.hasDefaultSpawn = v; return this; }

            public RespawnContext build() {
                return new RespawnContext(
                        deathRespawn, pendingJailRelease, jailed, worldPluginOwnsRespawn,
                        bedOrAnchorSpawn, rtpOnDeathEnabled, respawnAtHomeEnabled,
                        hasHome, hasGroupSpawn, hasDefaultSpawn
                );
            }
        }
    }

    public record RespawnDecision(RespawnTarget primary, RespawnTarget fallbackOnRtpFailure) {
        public RespawnDecision {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(fallbackOnRtpFailure, "fallbackOnRtpFailure");
        }

        public boolean isDisciplineOwned() {
            return primary == RespawnTarget.JAIL_RELEASE_RETURN || primary == RespawnTarget.JAIL;
        }

        public boolean schedulesRtp() {
            return primary == RespawnTarget.DEATH_RTP;
        }
    }

    public enum JoinTarget {
        /** Active prisoner kept in jail by discipline. */
        JAIL,
        /** Defer join spawn handling to a world-management plugin owning this world. */
        DEFER_TO_WORLD_PLUGIN,
        /** Delayed random teleport triggered on first join. */
        FIRST_JOIN_RTP,
        /** Group-specific spawn ({@code /setspawn <group>}). */
        GROUP_SPAWN,
        /** Global server spawn ({@code /setspawn}). */
        DEFAULT_SPAWN,
        /** Leave player at their join position. */
        KEEP_POSITION
    }

    public record JoinContext(
            boolean firstJoin,
            boolean jailed,
            boolean worldPluginOwnsJoin,
            boolean firstJoinRtpEnabled,
            boolean firstJoinSpawnEnabled,
            boolean spawnOnJoin,
            boolean hasGroupSpawn,
            boolean hasDefaultSpawn,
            @Nullable String firstJoinKit
    ) {
        public JoinContext {
            firstJoinKit = (firstJoinKit == null || firstJoinKit.isBlank()) ? null : firstJoinKit.trim();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean firstJoin;
            private boolean jailed;
            private boolean worldPluginOwnsJoin;
            private boolean firstJoinRtpEnabled;
            private boolean firstJoinSpawnEnabled;
            private boolean spawnOnJoin;
            private boolean hasGroupSpawn;
            private boolean hasDefaultSpawn;
            private @Nullable String firstJoinKit;

            public Builder firstJoin(boolean v) { this.firstJoin = v; return this; }
            public Builder jailed(boolean v) { this.jailed = v; return this; }
            public Builder worldPluginOwnsJoin(boolean v) { this.worldPluginOwnsJoin = v; return this; }
            public Builder firstJoinRtpEnabled(boolean v) { this.firstJoinRtpEnabled = v; return this; }
            public Builder firstJoinSpawnEnabled(boolean v) { this.firstJoinSpawnEnabled = v; return this; }
            public Builder spawnOnJoin(boolean v) { this.spawnOnJoin = v; return this; }
            public Builder hasGroupSpawn(boolean v) { this.hasGroupSpawn = v; return this; }
            public Builder hasDefaultSpawn(boolean v) { this.hasDefaultSpawn = v; return this; }
            public Builder firstJoinKit(@Nullable String v) { this.firstJoinKit = v; return this; }

            public JoinContext build() {
                return new JoinContext(
                        firstJoin, jailed, worldPluginOwnsJoin, firstJoinRtpEnabled,
                        firstJoinSpawnEnabled, spawnOnJoin, hasGroupSpawn, hasDefaultSpawn, firstJoinKit
                );
            }
        }
    }

    public record JoinDecision(
            JoinTarget primary,
            JoinTarget fallbackOnRtpFailure,
            @Nullable String grantKit
    ) {
        public JoinDecision {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(fallbackOnRtpFailure, "fallbackOnRtpFailure");
            grantKit = (grantKit == null || grantKit.isBlank()) ? null : grantKit.trim();
        }

        public boolean schedulesRtp() {
            return primary == JoinTarget.FIRST_JOIN_RTP;
        }

        public boolean grantsFirstJoinKit() {
            return grantKit != null;
        }
    }

    /**
     * Evaluates respawn precedence:
     * <ol>
     *   <li>Pending jail release return (even across non-death respawns or world-plugin worlds)</li>
     *   <li>Active jail confinement (never bypassed by bed/anchor, RTP, or world plugins)</li>
     *   <li>Non-death respawns (End portal exit) -> vanilla default</li>
     *   <li>World-plugin respawn ownership -> defer</li>
     *   <li>Bed or respawn anchor -> vanilla bed/anchor</li>
     *   <li>Death RTP -> {@link RespawnTarget#DEATH_RTP}, with explicit fallback chain (home -> group spawn -> default spawn -> vanilla)</li>
     *   <li>Home respawn (when enabled and saved)</li>
     *   <li>Group spawn -> default spawn -> vanilla default</li>
     * </ol>
     */
    public static RespawnDecision decideRespawn(RespawnContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (ctx.pendingJailRelease()) {
            return new RespawnDecision(
                    RespawnTarget.JAIL_RELEASE_RETURN,
                    ctx.jailed() ? RespawnTarget.JAIL : RespawnTarget.VANILLA_DEFAULT
            );
        }
        if (ctx.jailed()) {
            return new RespawnDecision(RespawnTarget.JAIL, RespawnTarget.JAIL);
        }
        if (!ctx.deathRespawn()) {
            return new RespawnDecision(RespawnTarget.VANILLA_DEFAULT, RespawnTarget.VANILLA_DEFAULT);
        }
        if (ctx.worldPluginOwnsRespawn()) {
            return new RespawnDecision(RespawnTarget.DEFER_TO_WORLD_PLUGIN, RespawnTarget.DEFER_TO_WORLD_PLUGIN);
        }
        if (ctx.bedOrAnchorSpawn()) {
            return new RespawnDecision(RespawnTarget.BED_OR_ANCHOR, RespawnTarget.BED_OR_ANCHOR);
        }
        RespawnTarget nonRtpTarget = resolveNonRtpRespawn(ctx);
        if (ctx.rtpOnDeathEnabled()) {
            return new RespawnDecision(RespawnTarget.DEATH_RTP, nonRtpTarget);
        }
        return new RespawnDecision(nonRtpTarget, nonRtpTarget);
    }

    /**
     * Evaluates join and first-join onboarding precedence:
     * <ol>
     *   <li>Active jail confinement -> jail (suppresses RTP, spawn-on-join, and onboarding kit)</li>
     *   <li>World-plugin join ownership -> defer (first-join kit still eligible on first join)</li>
     *   <li>First join: first-join RTP (with group/default spawn fallback when configured) -> first-join/join spawn -> keep position</li>
     *   <li>Returning join: spawn-on-join (group spawn -> default spawn) -> keep position</li>
     * </ol>
     */
    public static JoinDecision decideJoin(JoinContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (ctx.jailed()) {
            return new JoinDecision(JoinTarget.JAIL, JoinTarget.JAIL, null);
        }
        String kit = ctx.firstJoin() ? ctx.firstJoinKit() : null;
        if (ctx.worldPluginOwnsJoin()) {
            return new JoinDecision(JoinTarget.DEFER_TO_WORLD_PLUGIN, JoinTarget.DEFER_TO_WORLD_PLUGIN, kit);
        }
        JoinTarget spawnFallback = resolveSpawnJoinTarget(ctx.hasGroupSpawn(), ctx.hasDefaultSpawn());
        if (ctx.firstJoin()) {
            if (ctx.firstJoinRtpEnabled()) {
                JoinTarget onRtpFail = (ctx.firstJoinSpawnEnabled() || ctx.spawnOnJoin())
                        ? spawnFallback
                        : JoinTarget.KEEP_POSITION;
                return new JoinDecision(JoinTarget.FIRST_JOIN_RTP, onRtpFail, kit);
            }
            if (ctx.firstJoinSpawnEnabled() || ctx.spawnOnJoin()) {
                return new JoinDecision(spawnFallback, spawnFallback, kit);
            }
            return new JoinDecision(JoinTarget.KEEP_POSITION, JoinTarget.KEEP_POSITION, kit);
        }
        if (ctx.spawnOnJoin()) {
            return new JoinDecision(spawnFallback, spawnFallback, null);
        }
        return new JoinDecision(JoinTarget.KEEP_POSITION, JoinTarget.KEEP_POSITION, null);
    }

    private static RespawnTarget resolveNonRtpRespawn(RespawnContext ctx) {
        if (ctx.respawnAtHomeEnabled() && ctx.hasHome()) {
            return RespawnTarget.HOME;
        }
        if (ctx.hasGroupSpawn()) {
            return RespawnTarget.GROUP_SPAWN;
        }
        if (ctx.hasDefaultSpawn()) {
            return RespawnTarget.DEFAULT_SPAWN;
        }
        return RespawnTarget.VANILLA_DEFAULT;
    }

    private static JoinTarget resolveSpawnJoinTarget(boolean hasGroupSpawn, boolean hasDefaultSpawn) {
        if (hasGroupSpawn) {
            return JoinTarget.GROUP_SPAWN;
        }
        if (hasDefaultSpawn) {
            return JoinTarget.DEFAULT_SPAWN;
        }
        return JoinTarget.KEEP_POSITION;
    }
}
