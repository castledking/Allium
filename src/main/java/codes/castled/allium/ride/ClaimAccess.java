package codes.castled.allium.ride;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The claim checks and claim bans of the claim handcuffs: whether an actor may restrain a target
 * inside a claim, and banning the target from it. {@link GpExpansionClaims} answers them through
 * GPExpansion (over GriefPrevention or GriefPrevention3D).
 */
public interface ClaimAccess {

    /** Why a claim check failed, with the lang message that says so. */
    enum Refusal { UNAVAILABLE, NOT_IN_CLAIM, NO_CLAIM_ID, NOT_MANAGER, TRUSTED, ALREADY_BANNED }

    /** A passed check names the target's main claim; a failed one says why. */
    record Check(@Nullable String claimId, @Nullable Refusal refusal, @Nullable String detail) {
        public static Check allowed(String claimId) {
            return new Check(claimId, null, null);
        }

        public static Check denied(Refusal refusal) {
            return new Check(null, refusal, null);
        }

        public static Check denied(Refusal refusal, String detail) {
            return new Check(null, refusal, detail);
        }

        public boolean allowed() {
            return refusal == null;
        }
    }

    /**
     * Whether {@code actor} may claim-restrain {@code target}: the target stands in a claim (its main
     * claim counts for a subdivision) that the actor owns or manages, holds no trust there, and is not
     * already banned from it.
     */
    Check check(Player actor, Player target);

    /** Whether {@code target} still stands in the claim {@code claimId}. */
    boolean isInside(Player target, String claimId);

    /** Bans {@code target} from {@code claimId} on {@code actor}'s behalf and saves it; false when that failed. */
    boolean ban(String claimId, Player target, CommandSender actor);
}
