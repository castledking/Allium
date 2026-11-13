package codes.castled.allium.preference;

/**
 * What a player's preferences are after defaults are applied.
 *
 * @param volumeOverridden whether the volume is the player's own value rather than the fallback
 */
public record EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                   double messageSoundVolume, boolean volumeOverridden, boolean socialSpy) {
    public EffectivePreferences(boolean acceptMessages, boolean acceptPayments, boolean acceptTeleportRequests,
                                double messageSoundVolume, boolean volumeOverridden) {
        this(acceptMessages, acceptPayments, acceptTeleportRequests, messageSoundVolume, volumeOverridden, false);
    }
}
